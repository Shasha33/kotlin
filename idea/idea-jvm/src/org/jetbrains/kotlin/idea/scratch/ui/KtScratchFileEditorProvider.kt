/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.scratch.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.diff.tools.util.BaseSyncScrollable
import com.intellij.diff.tools.util.SyncScrollSupport.TwosideSyncScrollSupport
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FocusChangeListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.editor.fixers.endLine
import org.jetbrains.kotlin.idea.editor.fixers.startLine
import org.jetbrains.kotlin.idea.scratch.*
import org.jetbrains.kotlin.idea.scratch.output.*
import org.jetbrains.kotlin.psi.UserDataProperty
import java.awt.Color

private const val KTS_SCRATCH_EDITOR_PROVIDER: String = "KtsScratchFileEditorProvider"

class KtScratchFileEditorProvider : FileEditorProvider, DumbAware {
    override fun getEditorTypeId(): String = KTS_SCRATCH_EDITOR_PROVIDER

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!file.isValid) return false
        if (!(file.isKotlinScratch || file.isKotlinWorksheet)) return false
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return false
        return ScratchFileLanguageProvider.get(psiFile.fileType) != null
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val scratchFile = createScratchFile(project, file) ?: return TextEditorProvider.getInstance().createEditor(project, file)

        return KtScratchFileEditorWithPreview.create(scratchFile)
    }

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class KtScratchFileEditorWithPreview private constructor(
    val scratchFile: ScratchFile,
    sourceTextEditor: TextEditor,
    private val previewTextEditor: TextEditor
) : TextEditorWithPreview(sourceTextEditor, previewTextEditor), TextEditor {

    private val sourceEditor = sourceTextEditor.editor as EditorEx
    private val previewEditor = previewTextEditor.editor as EditorEx

    private val sourceHighlighter: ScratchSourceEditorHighlighter = ScratchSourceEditorHighlighter(sourceEditor)
    private val previewOutputManager: PreviewOutputBlocksManager = PreviewOutputBlocksManager(previewEditor)

    private val toolWindowHandler: ScratchOutputHandler = requestToolWindowHandler()
    private val inlayScratchOutputHandler = InlayScratchOutputHandler(sourceTextEditor, toolWindowHandler)
    private val previewEditorScratchOutputHandler = PreviewEditorScratchOutputHandler(
        previewOutputManager,
        toolWindowHandler,
        previewTextEditor as Disposable
    )
    private val commonPreviewOutputHandler = LayoutDependantOutputHandler(
        noPreviewOutputHandler = inlayScratchOutputHandler,
        previewOutputHandler = previewEditorScratchOutputHandler,
        layoutProvider = ::getLayout
    )

    private val scratchTopPanel = ScratchTopPanel(scratchFile)

    init {
        sourceTextEditor.parentScratchEditorWithPreview = this

        scratchFile.compilingScratchExecutor?.addOutputHandler(commonPreviewOutputHandler)
        scratchFile.replScratchExecutor?.addOutputHandler(commonPreviewOutputHandler)

        configureSyncScrollForSourceAndPreview()

        configureSyncHighlighting()

        ScratchFileAutoRunner.addListener(scratchFile.project, sourceTextEditor)
    }

    private fun configureSyncScrollForSourceAndPreview() {
        val scrollable = object : BaseSyncScrollable() {
            override fun processHelper(helper: ScrollHelper) {
                if (!helper.process(0, 0)) return

                val alignments = previewOutputManager.computeSourceToPreviewAlignments()

                for ((fromSource, fromPreview) in alignments) {
                    if (!helper.process(fromSource, fromPreview)) return
                    if (!helper.process(fromSource, fromPreview)) return
                }

                helper.process(sourceEditor.document.lineCount, previewEditor.document.lineCount)
            }

            override fun isSyncScrollEnabled(): Boolean = true
        }

        val scrollSupport = TwosideSyncScrollSupport(listOf(sourceEditor, previewEditor), scrollable)
        val listener = VisibleAreaListener { e -> scrollSupport.visibleAreaChanged(e) }

        sourceEditor.scrollingModel.addVisibleAreaListener(listener)
        previewEditor.scrollingModel.addVisibleAreaListener(listener)
    }

    private fun configureSyncHighlighting() {
        configureExclusiveCaretRowHighlighting()
        configureHighlightUpdateOnDocumentChange()
        configureSourceToPreviewHighlighting()
        configurePreviewToSourceHighlighting()
    }

    /**
     * Configures editors such that only one of them have caret row highlighting enabled.
     */
    private fun configureExclusiveCaretRowHighlighting() {
        val exclusiveCaretHighlightingListener = object : FocusChangeListener {
            override fun focusLost(editor: Editor) {}

            override fun focusGained(editor: Editor) {
                sourceEditor.settings.isCaretRowShown = false
                previewEditor.settings.isCaretRowShown = false

                editor.settings.isCaretRowShown = true
            }
        }

        sourceEditor.addFocusListener(exclusiveCaretHighlightingListener)
        previewEditor.addFocusListener(exclusiveCaretHighlightingListener)
    }

    /**
     * When source or preview documents change, we need to update highlighting, because
     * expression output may become bigger.
     *
     * We can do that only when document is fully committed, so [ScratchFile.getExpressions] will return correct expressions
     * with correct PSIs.
     */
    private fun configureHighlightUpdateOnDocumentChange() {
        val focusedEditorKeeper = object : FocusChangeListener {
            /**
             * Read and write only from EDT, no synchronization or volatile is needed.
             */
            var lastFocusedEditor: Editor = sourceEditor

            override fun focusLost(editor: Editor) {}

            override fun focusGained(editor: Editor) {
                lastFocusedEditor = editor
            }
        }

        sourceEditor.addFocusListener(focusedEditorKeeper)
        previewEditor.addFocusListener(focusedEditorKeeper)

        val updateHighlightOnDocumentChangeListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                clearAllHighlights()

                val lastFocusedEditor = focusedEditorKeeper.lastFocusedEditor
                val singleCaret = lastFocusedEditor.caretModel.allCarets.singleOrNull() ?: return

                PsiDocumentManager.getInstance(scratchFile.project).performWhenAllCommitted {
                    if (lastFocusedEditor === sourceEditor) {
                        highlightPreviewBySourceLine(singleCaret.logicalPosition.line)
                    } else {
                        highlightSourceByPreviewLine(singleCaret.logicalPosition.line)
                    }
                }
            }
        }

        previewEditor.document.addDocumentListener(updateHighlightOnDocumentChangeListener)
        sourceEditor.document.addDocumentListener(updateHighlightOnDocumentChangeListener)
    }

    /**
     * When caret in [sourceEditor] is moved, highlight is recalculated.
     *
     * When focus is switched to the [sourceEditor], highlight is recalculated,
     * because it is possible to switch focus without changing cursor position,
     * which would lead to the outdated highlighting.
     */
    private fun configureSourceToPreviewHighlighting() {
        sourceEditor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                clearAllHighlights()

                highlightPreviewBySourceLine(event.newPosition.line)
            }
        })

        sourceEditor.addFocusListener(object : FocusChangeListener {
            override fun focusLost(editor: Editor) {}

            override fun focusGained(editor: Editor) {
                clearAllHighlights()

                val singleCaret = sourceEditor.caretModel.allCarets.singleOrNull() ?: return
                highlightPreviewBySourceLine(singleCaret.logicalPosition.line)
            }
        })
    }

    /**
     * When caret in [previewEditor] is moved, highlight is recalculated.
     *
     * When focus is switched to the [previewEditor], highlight is recalculated,
     * because it is possible to switch focus without changing cursor position,
     * which would lead to the outdated highlighting.
     */
    private fun configurePreviewToSourceHighlighting() {
        previewEditor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                clearAllHighlights()

                highlightSourceByPreviewLine(event.newPosition.line)
            }
        })

        previewEditor.addFocusListener(object : FocusChangeListener {
            override fun focusLost(editor: Editor) {}

            override fun focusGained(editor: Editor) {
                clearAllHighlights()

                val singleCaret = previewEditor.caretModel.allCarets.singleOrNull() ?: return
                highlightSourceByPreviewLine(singleCaret.logicalPosition.line)
            }
        })
    }

    private fun highlightSourceByPreviewLine(selectedPreviewLine: Int) {
        val highlightColor = previewEditor.colorsScheme.getColor(EditorColors.CARET_ROW_COLOR) ?: return

        val block = previewOutputManager.getBlockAtLine(selectedPreviewLine) ?: return
        if (!block.sourceExpression.linesInformationIsCorrect()) return

        sourceHighlighter.highlightSourceExpression(block.sourceExpression, highlightColor)
    }

    private fun highlightPreviewBySourceLine(selectedSourceLine: Int) {
        val highlightColor = previewEditor.colorsScheme.getColor(EditorColors.CARET_ROW_COLOR) ?: return

        val expressionUnderCaret = scratchFile.getExpressionAtLine(selectedSourceLine) ?: return
        val outputBlock = previewOutputManager.getBlock(expressionUnderCaret) ?: return

        outputBlock.addHighlight(highlightColor)
    }

    private fun clearAllHighlights() {
        sourceHighlighter.clearAllHighlights()
        previewOutputManager.clearAllHighlights()
    }

    override fun dispose() {
        scratchFile.replScratchExecutor?.stop()
        scratchFile.compilingScratchExecutor?.stop()
        releaseToolWindowHandler(toolWindowHandler)
        super.dispose()
    }

    override fun navigateTo(navigatable: Navigatable) {
        myEditor.navigateTo(navigatable)
    }

    override fun canNavigateTo(navigatable: Navigatable): Boolean {
        return myEditor.canNavigateTo(navigatable)
    }

    override fun getEditor(): Editor {
        return myEditor.editor
    }

    override fun createToolbar(): ActionToolbar {
        return scratchTopPanel.actionsToolbar
    }

    fun clearOutputHandlers() {
        commonPreviewOutputHandler.clear(scratchFile)
    }

    override fun setLayout(newLayout: Layout) {
        val previous = layout
        super.setLayout(newLayout)
        val current = layout

        when {
            previous == Layout.SHOW_EDITOR && current != Layout.SHOW_EDITOR -> clearOutputHandlers()
            previous != Layout.SHOW_EDITOR && current == Layout.SHOW_EDITOR -> clearOutputHandlers()
        }
    }

    @TestOnly
    fun setPreviewEnabled(isPreviewEnabled: Boolean) {
        layout = if (isPreviewEnabled) Layout.SHOW_EDITOR_AND_PREVIEW else Layout.SHOW_EDITOR
    }

    @TestOnly
    fun getPreviewEditor(): TextEditor = previewTextEditor

    companion object {
        fun create(scratchFile: ScratchFile): KtScratchFileEditorWithPreview {
            val textEditorProvider = TextEditorProvider.getInstance()

            val mainEditor = textEditorProvider.createEditor(scratchFile.project, scratchFile.file) as TextEditor
            val editorFactory = EditorFactory.getInstance()

            val viewer = editorFactory.createViewer(editorFactory.createDocument(""), scratchFile.project, EditorKind.PREVIEW)
            Disposer.register(mainEditor, Disposable { editorFactory.releaseEditor(viewer) })

            val previewEditor = textEditorProvider.getTextEditor(viewer)

            return KtScratchFileEditorWithPreview(scratchFile, mainEditor, previewEditor)
        }
    }
}

fun TextEditor.findScratchFileEditorWithPreview(): KtScratchFileEditorWithPreview? =
    if (this is KtScratchFileEditorWithPreview) this else parentScratchEditorWithPreview

private var TextEditor.parentScratchEditorWithPreview: KtScratchFileEditorWithPreview?
        by UserDataProperty(Key.create("parent.preview.editor"))

fun createScratchFile(project: Project, file: VirtualFile): ScratchFile? {
    val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
    val scratchFile = ScratchFileLanguageProvider.get(psiFile.language)?.newScratchFile(project, file) ?: return null
    setupCodeAnalyzerRestarterOutputHandler(project, scratchFile)

    return scratchFile
}

private fun setupCodeAnalyzerRestarterOutputHandler(project: Project, scratchFile: ScratchFile) {
    scratchFile.replScratchExecutor?.addOutputHandler(object : ScratchOutputHandlerAdapter() {
        override fun onFinish(file: ScratchFile) {
            ApplicationManager.getApplication().invokeLater {
                if (!file.project.isDisposed) {
                    val scratch = file.getPsiFile()
                    if (scratch?.isValid == true) {
                        DaemonCodeAnalyzer.getInstance(project).restart(scratch)
                    }
                }
            }
        }
    })
}

/**
 * Redirects output to [noPreviewOutputHandler] or [previewOutputHandler] depending on the result of [layoutProvider] call.
 *
 * However, clears both handlers to simplify clearing when switching between layouts.
 */
private class LayoutDependantOutputHandler(
    private val noPreviewOutputHandler: ScratchOutputHandler,
    private val previewOutputHandler: ScratchOutputHandler,
    private val layoutProvider: () -> TextEditorWithPreview.Layout
) : ScratchOutputHandler {

    override fun onStart(file: ScratchFile) {
        targetHandler.onStart(file)
    }

    override fun handle(file: ScratchFile, expression: ScratchExpression, output: ScratchOutput) {
        targetHandler.handle(file, expression, output)
    }

    override fun error(file: ScratchFile, message: String) {
        targetHandler.error(file, message)
    }

    override fun onFinish(file: ScratchFile) {
        targetHandler.onFinish(file)
    }

    override fun clear(file: ScratchFile) {
        noPreviewOutputHandler.clear(file)
        previewOutputHandler.clear(file)
    }

    private val targetHandler
        get() = when (layoutProvider()) {
            TextEditorWithPreview.Layout.SHOW_EDITOR -> noPreviewOutputHandler
            else -> previewOutputHandler
        }
}

private class ScratchSourceEditorHighlighter(private val sourceEditor: Editor) {
    private val highlights = mutableMapOf<ScratchExpression, RangeHighlighter>()

    fun highlightSourceExpression(sourceExpression: ScratchExpression, highlightColor: Color) {
        highlights[sourceExpression]?.let(sourceEditor.markupModel::removeHighlighter)
        val range = sourceExpression.element.textRange
        highlights[sourceExpression] = sourceEditor.markupModel.addRangeHighlighter(
            range.startOffset,
            range.endOffset,
            HighlighterLayer.CARET_ROW,
            TextAttributes().apply { backgroundColor = highlightColor },
            HighlighterTargetArea.LINES_IN_RANGE
        )
    }

    fun clearAllHighlights() {
        highlights.values.forEach(sourceEditor.markupModel::removeHighlighter)
        highlights.clear()
    }
}

/**
 * Checks if [ScratchExpression.element] is actually starts at the [ScratchExpression.lineStart]
 * and ends at the [ScratchExpression.lineEnd].
 */
private fun ScratchExpression.linesInformationIsCorrect(): Boolean {
    if (!element.isValid) return false

    val psiFile = element.containingFile ?: return false
    val document = PsiDocumentManager.getInstance(element.project).getDocument(psiFile) ?: return false

    return element.startLine(document) == lineStart && element.endLine(document) == lineEnd
}
