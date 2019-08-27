/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.deepCopyWithWrappedDescriptors
import org.jetbrains.kotlin.backend.common.ir.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.backend.common.lower.InitializersLowering
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.common.phaser.PhaserState
import org.jetbrains.kotlin.backend.common.phaser.SameTypeCompilerPhase
import org.jetbrains.kotlin.backend.common.phaser.namedIrModulePhase
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.JvmLoweredDeclarationOrigin
import org.jetbrains.kotlin.backend.jvm.codegen.fileParent
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.impl.EmptyPackageFragmentDescriptor
import org.jetbrains.kotlin.ir.SourceManager
import org.jetbrains.kotlin.ir.SourceRangeInfo
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.dump
import org.jetbrains.kotlin.resolve.jvm.JvmClassName

internal val generateMultifileFacadesPhase = namedIrModulePhase(
    name = "GenerateMultifileFacades",
    description = "Generate JvmMultifileClass facades, based on the information provided by FileClassLowering",
    prerequisite = setOf(fileClassPhase),
    lower = object : SameTypeCompilerPhase<JvmBackendContext, IrModuleFragment> {
        override fun invoke(
            phaseConfig: PhaseConfig,
            phaserState: PhaserState<IrModuleFragment>,
            context: JvmBackendContext,
            input: IrModuleFragment
        ): IrModuleFragment {
            input.files.addAll(generateMultifileFacades(input.descriptor, context))
            context.multifileFacadesToAdd.clear()
            return input
        }
    }
)

internal class MultifileFacadeFileEntry(
    private val className: JvmClassName,
    val partFiles: List<IrFile>
) : SourceManager.FileEntry {
    override val name: String
        get() = "<multi-file facade $className>"

    override val maxOffset: Int
        get() = UNDEFINED_OFFSET

    override fun getSourceRangeInfo(beginOffset: Int, endOffset: Int): SourceRangeInfo =
        error("Multifile facade doesn't support debug info: $className")

    override fun getLineNumber(offset: Int): Int =
        error("Multifile facade doesn't support debug info: $className")

    override fun getColumnNumber(offset: Int): Int =
        error("Multifile facade doesn't support debug info: $className")
}

private fun generateMultifileFacades(module: ModuleDescriptor, context: JvmBackendContext): List<IrFile> =
    context.multifileFacadesToAdd.map { (jvmClassName, partClasses) ->
        val fileEntry = MultifileFacadeFileEntry(jvmClassName, partClasses.map(IrClass::fileParent))
        val file = IrFileImpl(fileEntry, EmptyPackageFragmentDescriptor(module, jvmClassName.packageFqName))

        val facadeClass = buildClass {
            name = jvmClassName.fqNameForTopLevelClassMaybeWithDollars.shortName()
        }.apply {
            parent = file
            createImplicitParameterDeclarationWithWrappedDescriptor()
        }
        file.declarations.add(facadeClass)

        for (partClass in partClasses) {
            for (member in partClass.declarations) {
                member.createMultifileDelegateIfNeeded(context, facadeClass)
            }
        }

        file
    }

private fun IrDeclaration.createMultifileDelegateIfNeeded(context: JvmBackendContext, facadeClass: IrClass) {
    if (this !is IrFunction ||
        Visibilities.isPrivate(visibility) ||
        name == InitializersLowering.clinitName ||
        origin == JvmLoweredDeclarationOrigin.SYNTHETIC_ACCESSOR
    ) return

    // TODO: perform copy of the signature only, without body
    val function = deepCopyWithSymbols(facadeClass)
    function.body = context.createIrBuilder(symbol).irBlockBody {
        val functionForCall = computeFunctionForCall()
        +irReturn(irCall(functionForCall).also { call ->
            function.extensionReceiverParameter?.let { parameter ->
                call.extensionReceiver = irGet(parameter)
            }
            for (parameter in function.valueParameters) {
                call.putValueArgument(parameter.index, irGet(parameter))
            }
        })
    }
    function.origin = JvmLoweredDeclarationOrigin.MULTIFILE_BRIDGE

    facadeClass.declarations.add(function)
}

// This deep copy is needed while we still use KotlinTypeMapper to map signatures in method calls. Without it, KotlinTypeMapper takes
// the descriptor and assumes that a call to that function must go through the public facade (see mapOwner call in mapToCallableMethod),
// which results in endless recursion here. With this copy, we trick it into thinking that the function is actually a static function
// in a class whose name is the name of the multi-file part, as opposed to being top level.
private fun IrFunction.computeFunctionForCall(): IrFunction {
    val property = (this as? IrSimpleFunction)?.correspondingPropertySymbol?.owner
        ?: return deepCopyWithWrappedDescriptors(parent)

    val propertyCopy = property.deepCopyWithWrappedDescriptors(property.parent)
    return when (this) {
        property.getter -> propertyCopy.getter!!
        property.setter -> propertyCopy.setter!!
        else -> error("Property accessor must be getter or setter: ${dump()}")
    }
}
