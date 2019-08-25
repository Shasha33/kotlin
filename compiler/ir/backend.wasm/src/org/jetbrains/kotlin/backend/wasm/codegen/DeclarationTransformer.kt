/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.codegen

import org.jetbrains.kotlin.backend.wasm.ast.*
import org.jetbrains.kotlin.backend.wasm.utils.getWasmImportAnnotation
import org.jetbrains.kotlin.backend.wasm.utils.getWasmInstructionAnnotation
import org.jetbrains.kotlin.backend.wasm.utils.hasExcludedFromCodegenAnnotation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isAnnotationClass
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

class DeclarationTransformer : BaseTransformer<WasmModuleField?, WasmCodegenContext> {
    override fun visitSimpleFunction(declaration: IrSimpleFunction, data: WasmCodegenContext): WasmModuleField? {
        if (declaration.hasExcludedFromCodegenAnnotation())
            return null
        if (declaration.getWasmInstructionAnnotation() != null)
            return null
        if (declaration.isFakeOverride)
            return null
        // Virtual functions are not supported yet
        if (declaration.origin == IrDeclarationOrigin.BRIDGE)
            return null

        // Collect local variables
        val localNames = wasmNameTable<IrValueDeclaration>()
        val labels = wasmNameTable<LoopLabel>()

        val wasmName = data.getGlobalName(declaration)

        val irParameters = declaration.run {
            listOfNotNull(dispatchReceiverParameter, extensionReceiverParameter) + valueParameters
        }

        val wasmParameters = irParameters.map { parameter ->
            val name = localNames.declareFreshName(parameter, parameter.name.asString())
            WasmParameter(name, data.transformType(parameter.type))
        }

        val wasmReturnType = data.resultType(declaration.returnType)

        val importedName = declaration.getWasmImportAnnotation()
        if (importedName != null) {
            data.imports.add(
                WasmFunction(
                    name = wasmName,
                    parameters = wasmParameters,
                    returnType = wasmReturnType,
                    locals = emptyList(),
                    instructions = emptyList(),
                    importPair = importedName
                )
            )
            return null
        }

        val body = declaration.body
            ?: error("Function ${declaration.fqNameWhenAvailable} without a body")

        data.localNames = localNames.names
        data.labels = labels.names

        val locals = mutableListOf<WasmLocal>()
        body.acceptChildrenVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitVariable(declaration: IrVariable) {
                val name = localNames.declareFreshName(declaration, declaration.name.asString())
                locals += WasmLocal(name, data.transformType(declaration.type))
                super.visitVariable(declaration)
            }

            override fun visitLoop(loop: IrLoop) {
                val suggestedLabel = loop.label ?: ""
                for (labelType in LoopLabelType.values()) {
                    labels.declareFreshName(
                        LoopLabel(loop, labelType),
                        "${labelType.name}_$suggestedLabel"
                    )
                }
                super.visitLoop(loop)
            }
        })

        val wasmBody = bodyToWasmInstructionList(body, data)
        return WasmFunction(
            name = wasmName,
            parameters = wasmParameters,
            returnType = wasmReturnType,
            locals = locals,
            instructions = wasmBody,
            importPair = null
        )
    }

    override fun visitConstructor(declaration: IrConstructor, data: WasmCodegenContext): WasmModuleField? {
        TODO()
    }

    override fun visitClass(declaration: IrClass, data: WasmCodegenContext): WasmModuleField? {
        if (declaration.isAnnotationClass) return null
        if (declaration.hasExcludedFromCodegenAnnotation()) return null

        val wasmMembers = declaration.declarations.mapNotNull { member ->
            when (member) {
                is IrSimpleFunction -> this.visitSimpleFunction(member, data)
                else -> null
            }
        }

        return WasmModuleFieldList(wasmMembers)
    }

    override fun visitField(declaration: IrField, data: WasmCodegenContext): WasmModuleField {
        val wasmType = data.transformType(declaration.type)
        return WasmGlobal(
            name = data.getGlobalName(declaration),
            type = wasmType,
            isMutable = true,
            // TODO: move non-constexpr initializers out
            init = defaultInitializerForType(wasmType)
        )
    }
}

enum class LoopLabelType { BREAK, CONTINUE, LOOP }
data class LoopLabel(val loop: IrLoop, val isBreak: LoopLabelType)

fun defaultInitializerForType(type: WasmValueType): WasmInstruction = when (type) {
    WasmI32 -> WasmI32Const(0)
    WasmI64 -> WasmI64Const(0)
    WasmF32 -> WasmF32Const(0f)
    WasmF64 -> WasmF64Const(0.0)
    WasmAnyRef -> WasmRefNull
}