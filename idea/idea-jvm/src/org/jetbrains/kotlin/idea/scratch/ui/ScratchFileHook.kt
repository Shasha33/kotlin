/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.scratch.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.idea.scratch.*
import org.jetbrains.kotlin.idea.scratch.output.InlayScratchOutputHandler
import org.jetbrains.kotlin.idea.scratch.output.ScratchOutputHandlerAdapter

class ScratchFileHook(val project: Project) : ProjectComponent {

    override fun projectOpened() {
        project.messageBus.connect(project).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, ScratchEditorListener())
    }

    private inner class ScratchEditorListener : FileEditorManagerListener {
        override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
            if (!isPluggable(file)) return

            val editor = getEditorWithoutScratchFile(source, file) ?: return

            val scratchFile = createScratchFile(project, file) ?: return

            val inlayOutputHandler = InlayScratchOutputHandler(editor)
            scratchFile.compilingScratchExecutor?.addOutputHandler(inlayOutputHandler)
            scratchFile.replScratchExecutor?.addOutputHandler(inlayOutputHandler)
            editor.attachOutputHandler(inlayOutputHandler)

            editor.addScratchPanel(ScratchTopPanel(scratchFile))

            ScratchFileAutoRunner.addListener(project, editor)
        }

        override fun fileClosed(source: FileEditorManager, file: VirtualFile) {}
    }

    private fun isPluggable(file: VirtualFile): Boolean {
        if (!file.isValid) return false
        if (!(file.isKotlinScratch || file.isKotlinWorksheet)) return false
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return false
        return ScratchFileLanguageProvider.get(psiFile.fileType) != null
    }
}

private fun createScratchFile(project: Project, file: VirtualFile): ScratchFile? {
    val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
    val scratchFile = ScratchFileLanguageProvider.get(psiFile.language)?.newScratchFile(project, file) ?: return null
    setupReplRestartingOutputHandler(project, scratchFile)

    return scratchFile
}

private fun setupReplRestartingOutputHandler(project: Project, scratchFile: ScratchFile) {
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
