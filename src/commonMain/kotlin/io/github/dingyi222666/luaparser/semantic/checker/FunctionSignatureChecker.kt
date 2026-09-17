package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.comments.ParamTagSyntax
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType

class FunctionSignatureChecker(
    private val binder: BinderPassResult
) {

    fun checkDeclaration(declaration: BinderDeclaration): List<Diagnostic> {
        if (
            declaration.kind != DeclarationKind.FUNCTION &&
            declaration.kind != DeclarationKind.GLOBAL &&
            declaration.kind != DeclarationKind.METHOD
        ) {
            return emptyList()
        }

        if (declaration.kind == DeclarationKind.METHOD) {
            return checkMethodDeclaration(declaration)
        }

        val function = resolveOwningFunctionDeclaration(binder, declaration) ?: return emptyList()
        val signature = declaration.declaredType as? FunctionType
        val paramTags = declaration.documentation?.docComment?.tags.orEmpty().filterIsInstance<ParamTagSyntax>()
        if (signature == null && paramTags.isEmpty()) {
            return emptyList()
        }

        val diagnostics = mutableListOf<Diagnostic>()
        val params = function.params
        val functionRange = function.identifier?.range ?: declaration.range
        val overloads = declaration.documentation?.resolvedOverloadTypes.orEmpty()

        validateAstVarargs(params, functionRange, diagnostics)
        validateResolvedSignature(signature, params, functionRange, diagnostics, requireAstContractMatch = true)
        overloads.forEach { overload ->
            validateResolvedSignature(overload, params, functionRange, diagnostics, requireAstContractMatch = false)
        }
        validateParamTags(paramTags, params, functionRange, diagnostics)

        return diagnostics
    }

    private fun checkMethodDeclaration(declaration: BinderDeclaration): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val signatures = when (val declaredType = declaration.declaredType) {
            is FunctionType -> listOf(declaredType)
            is CallableType -> declaredType.callSignatures
            else -> emptyList()
        }

        signatures.forEach { signature ->
            validateResolvedSignature(
                signature = signature,
                params = emptyList(),
                functionRange = declaration.range,
                diagnostics = diagnostics,
                requireAstContractMatch = false
            )
        }

        return diagnostics
    }

    private fun validateAstVarargs(
        params: List<Identifier>,
        functionRange: Range?,
        diagnostics: MutableList<Diagnostic>
    ) {
        val varargIndices = params.mapIndexedNotNull { index, parameter -> index.takeIf { parameter.name == "..." } }
        if (varargIndices.size > 1) {
            varargIndices.drop(1).forEach { index ->
                diagnostics += Diagnostic(
                    message = "Function declaration may only declare one vararg parameter.",
                    range = params.getOrNull(index)?.range ?: functionRange,
                    code = "checker.function.signature.multipleVararg"
                )
            }
        }

        varargIndices.filter { it != params.lastIndex }.forEach { index ->
            diagnostics += Diagnostic(
                message = "Vararg parameter must be the last parameter.",
                range = params.getOrNull(index)?.range ?: functionRange,
                code = "checker.function.signature.varargNotLast"
            )
        }
    }

    private fun validateResolvedSignature(
        signature: FunctionType?,
        params: List<Identifier>,
        functionRange: Range?,
        diagnostics: MutableList<Diagnostic>,
        requireAstContractMatch: Boolean
    ) {
        if (signature == null) {
            return
        }

        if (requireAstContractMatch && signature.parameters.size != params.size) {
            diagnostics += Diagnostic(
                message = "Resolved function signature does not align with the declaration parameter list.",
                range = functionRange,
                code = "checker.function.signature.parameterContractMismatch"
            )
        }

        var seenOptional = false
        var seenVararg = false
        signature.parameters.forEachIndexed { index, parameter ->
            val astParameter = params.getOrNull(index)
            if (requireAstContractMatch && astParameter != null && parameter.name != astParameter.name) {
                diagnostics += Diagnostic(
                    message = "Resolved function signature parameter order does not align with the declaration.",
                    range = astParameter.range,
                    code = "checker.function.signature.parameterContractMismatch"
                )
            }

            if (parameter.vararg && parameter.name != "...") {
                diagnostics += Diagnostic(
                    message = "Only the trailing '...' parameter may be marked vararg.",
                    range = astParameter?.range ?: functionRange,
                    code = "checker.function.signature.namedVararg"
                )
            }

            if (parameter.vararg && seenVararg) {
                diagnostics += Diagnostic(
                    message = "Function declaration may only declare one vararg parameter.",
                    range = astParameter?.range ?: functionRange,
                    code = "checker.function.signature.multipleVararg"
                )
            }
            if (parameter.vararg && index != signature.parameters.lastIndex) {
                diagnostics += Diagnostic(
                    message = "Vararg parameter must be the last parameter.",
                    range = astParameter?.range ?: functionRange,
                    code = "checker.function.signature.varargNotLast"
                )
            }
            if (parameter.optional && parameter.vararg) {
                diagnostics += Diagnostic(
                    message = "A parameter cannot be both optional and vararg.",
                    range = astParameter?.range ?: functionRange,
                    code = "checker.function.signature.optionalVararg"
                )
            }
            if (!parameter.optional && !parameter.vararg && seenOptional) {
                diagnostics += Diagnostic(
                    message = "A required parameter cannot follow an optional parameter.",
                    range = astParameter?.range ?: functionRange,
                    code = "checker.function.signature.requiredAfterOptional"
                )
            }

            if (parameter.optional) {
                seenOptional = true
            }
            if (parameter.vararg) {
                seenVararg = true
            }
        }
    }

    private fun validateParamTags(
        paramTags: List<ParamTagSyntax>,
        params: List<Identifier>,
        functionRange: Range?,
        diagnostics: MutableList<Diagnostic>
    ) {
        val trailingVararg = params.lastOrNull()?.takeIf { it.name == "..." }
        paramTags.forEach { tag ->
            if (tag.name.isBlank()) {
                diagnostics += Diagnostic(
                    message = "@param tag must declare a parameter name.",
                    range = functionRange,
                    code = "checker.function.signature.missingParamName"
                )
                return@forEach
            }

            if (tag.vararg && tag.name != "...") {
                diagnostics += Diagnostic(
                    message = "Only the trailing '...' parameter may be marked vararg.",
                    range = params.firstOrNull { it.name == tag.name }?.range ?: functionRange,
                    code = "checker.function.signature.namedVararg"
                )
                return@forEach
            }

            if (tag.name == "...") {
                if (trailingVararg == null) {
                    diagnostics += Diagnostic(
                        message = "@param ... must match a real trailing '...' parameter.",
                        range = functionRange,
                        code = "checker.function.signature.unknownParam"
                    )
                }
                return@forEach
            }

            if (params.none { it.name == tag.name }) {
                diagnostics += Diagnostic(
                    message = "@param '${tag.name}' does not match any declared parameter.",
                    range = functionRange,
                    code = "checker.function.signature.unknownParam"
                )
            }
        }
    }
}
