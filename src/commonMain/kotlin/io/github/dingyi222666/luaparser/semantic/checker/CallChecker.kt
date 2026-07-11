package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeExpansion
import io.github.dingyi222666.luaparser.semantic.types.resolve.isAssignableFrom

class CallChecker(
    private val binder: BinderPassResult
) {

    fun resolveCallable(type: Type, lexicalScopeId: ScopeId, declaration: BinderDeclaration? = null): CallableResolution {
        val normalized = ValueSequence.of(type).collapseToSingle().typeAt(0)
        val callable = asCallableType(normalized, lexicalScopeId)
            ?: return CallableResolution(normalizedType = normalized, failureReason = CallFailureReason.NON_CALLABLE)
        val signatures = buildList {
            addAll(callable.callSignatures)
            addAll(resolveOverloadSignatures(declaration))
        }.distinct()
        val finalCallable: CallableType = when (signatures.size) {
            1 -> signatures.single()
            else -> OverloadedFunctionType(signatures)
        }
        return CallableResolution(
            callableType = finalCallable,
            normalizedType = normalized,
            signatures = finalCallable.callSignatures
        )
    }

    fun checkCallValues(
        callableType: Type,
        argumentValues: List<ValueSequence>,
        lexicalScopeId: ScopeId,
        declaration: BinderDeclaration? = null
    ): CallResolution {
        val argumentTypes = mutableListOf<Type>()
        argumentValues.forEach { it.appendToCallArguments(argumentTypes) }
        return checkCall(callableType, argumentTypes, lexicalScopeId, declaration)
    }

    fun checkCall(
        callableType: Type,
        argumentTypes: List<Type>,
        lexicalScopeId: ScopeId,
        declaration: BinderDeclaration? = null
    ): CallResolution {
        val callableResolution = resolveCallable(callableType, lexicalScopeId, declaration)
        if (!callableResolution.isSuccess) {
            return CallResolution(
                callableResolution = callableResolution,
                failureReason = callableResolution.failureReason
            )
        }

        val compatible = callableResolution.signatures.mapIndexedNotNull { index, signature ->
            rankSignature(signature, argumentTypes, index)
        }
        if (compatible.isEmpty()) {
            return CallResolution(
                callableResolution = callableResolution,
                failureReason = CallFailureReason.NO_MATCHING_SIGNATURE
            )
        }

        // TASK-585 method overload arity product ranking (also used by constructor surfaces):
        // 1) requiredPenalty — exact closed arity first, then optional-open, then vararg/spread
        // 2) assignabilityPenalty / exactMismatchCount — type shape
        // 3) fallbackPenalty — fine-grained unused optional / consumed vararg slots
        // 4) index — deterministic first-signature tie-break (AMBIGUOUS_MATCH when scores tie)
        val sorted = compatible.sortedWith(
            compareBy<Candidate>(
                { it.score.requiredPenalty },
                { it.score.assignabilityPenalty },
                { it.score.exactMismatchCount },
                { it.score.fallbackPenalty },
                { it.index }
            )
        )
        val best = sorted.first()
        val ties = sorted.takeWhile { it.score == best.score }
        val ambiguous = ties.size > 1
        return CallResolution(
            returnType = best.signature.returnType,
            callableResolution = callableResolution,
            selectedSignature = best.signature,
            failureReason = if (ambiguous) CallFailureReason.AMBIGUOUS_MATCH else null,
            ambiguous = ambiguous
        )
    }

    private fun asCallableType(type: Type, lexicalScopeId: ScopeId): CallableType? {
        return when (val normalized = TypeExpansion.expandForCallableSurface(type, lexicalScopeId, binder)) {
            is CallableType -> normalized
            is ModuleType -> moduleCallableType(normalized)
                ?: (normalized.fields["__class"] as? JavaClassType)
                    ?.let { asCallableType(it, lexicalScopeId) }
            is TypeParameterType -> normalized.constraint?.let { asCallableType(it, lexicalScopeId) }
            is UnionType -> {
                val signatures = normalized.types.map { asCallableType(it, lexicalScopeId) ?: return null }
                    .flatMap { it.callSignatures }
                when (signatures.size) {
                    0 -> null
                    1 -> signatures.single()
                    else -> OverloadedFunctionType(signatures)
                }
            }

            is IntersectionType -> {
                val signatures = normalized.types.mapNotNull { asCallableType(it, lexicalScopeId)?.callSignatures }.flatten()
                when (signatures.size) {
                    0 -> null
                    1 -> signatures.single()
                    else -> OverloadedFunctionType(signatures)
                }
            }

            else -> null
        }
    }

    private fun resolveOverloadSignatures(declaration: BinderDeclaration?): List<FunctionType> {
        if (declaration == null) {
            return emptyList()
        }
        if (declaration.kind != DeclarationKind.FUNCTION && declaration.kind != DeclarationKind.GLOBAL) {
            return emptyList()
        }
        return declaration.documentation?.resolvedOverloadTypes.orEmpty()
    }

    private fun moduleCallableType(moduleType: ModuleType): CallableType? {
        val callField = moduleType.fields["__call"] ?: return null
        val callable = if (moduleType.isJavaBackedModule()) {
            callField.withJavaCallableSurface()
        } else {
            callField
        }
        return callable as? CallableType
    }

    /**
     * Rank one overload candidate for [argumentTypes].
     *
     * Product contract (TASK-585 method arity pick):
     * - Closed signatures match only when `argumentTypes.size` is within
     *   `[required, parameters.size]` (required excludes optional/vararg).
     * - Exact closed arity is preferred over optional-open and vararg/spread siblings
     *   via [Score.requiredPenalty] (primary sort key; vararg/spread base penalty 100+).
     * - Candidates never invent missing closed arities; impossible arity yields null
     *   (→ [CallFailureReason.NO_MATCHING_SIGNATURE]).
     * - Unknown arguments remain soft through assignability and do not invent signatures.
     */
    private fun rankSignature(signature: FunctionType, argumentTypes: List<Type>, index: Int): Candidate? {
        val parameters = signature.parameters
        val requiredParameters = parameters.count { !it.optional && !it.vararg }
        val hasVararg = parameters.any { it.vararg }
        if (argumentTypes.size < requiredParameters) {
            return null
        }
        if (!hasVararg && argumentTypes.size > parameters.size) {
            return null
        }

        var assignabilityPenalty = 0
        var exactMismatchCount = 0
        var fallbackPenalty = 0

        argumentTypes.forEachIndexed { argumentIndex, argumentType ->
            val parameter = parameters.getOrNull(argumentIndex)
                ?: parameters.lastOrNull { it.vararg }
                ?: return null
            if (!isArgumentAssignable(parameter.type, argumentType)) {
                return null
            }
            if (parameter.type != argumentType) {
                exactMismatchCount++
            }
            if (parameter.vararg) {
                // Consumed vararg / spread slot — ranks behind fixed parameters of same shape.
                fallbackPenalty += 2
            }
        }

        if (argumentTypes.size < parameters.size) {
            parameters.drop(argumentTypes.size).forEach { parameter ->
                if (parameter.optional) {
                    fallbackPenalty += 1
                } else if (parameter.vararg) {
                    fallbackPenalty += 2
                }
            }
        }

        parameters.forEachIndexed { parameterIndex, parameter ->
            if (parameterIndex >= argumentTypes.size && !parameter.optional && !parameter.vararg) {
                assignabilityPenalty += 10
            }
        }

        return Candidate(
            signature = signature,
            index = index,
            score = Score(
                requiredPenalty = arityRequiredPenalty(
                    parameters = parameters,
                    argumentCount = argumentTypes.size,
                    requiredParameters = requiredParameters,
                    hasVararg = hasVararg
                ),
                assignabilityPenalty = assignabilityPenalty,
                exactMismatchCount = exactMismatchCount,
                fallbackPenalty = fallbackPenalty
            )
        )
    }

    /**
     * Primary arity ranking key (lower is better).
     *
     * - 0: exact closed arity (no vararg; argument count equals fixed parameter count).
     * - small positive: closed optional-open match (trailing optionals unused, or only
     *   optional parameters filled beyond the required head).
     * - 100+: any vararg/spread signature — exact closed siblings always win when both match.
     *
     * This is the product fix for "exact arity over varargs/spread" (TASK-585). Previously
     * [requiredPenalty] was hard-coded 0, so order-dependent index ties could surface when
     * fallback scores collided; the explicit base penalty makes ranking order-stable.
     */
    private fun arityRequiredPenalty(
        parameters: List<FunctionParameter>,
        argumentCount: Int,
        requiredParameters: Int,
        hasVararg: Boolean
    ): Int {
        if (hasVararg) {
            val fixedHead = parameters.count { !it.vararg }
            val absorbedExtras = (argumentCount - fixedHead).coerceAtLeast(0)
            return VARARG_SPREAD_BASE_PENALTY + absorbedExtras
        }
        val unusedTrailing = (parameters.size - argumentCount).coerceAtLeast(0)
        val usedOptionals = (argumentCount - requiredParameters).coerceAtLeast(0)
        // Fully-supplied closed signatures with no trailing unused slots score 0 only when
        // every parameter is required. Optional-filled closed signatures keep a small penalty
        // so a pure exact required sibling of the same arity still wins.
        return if (unusedTrailing == 0 && usedOptionals == 0) {
            0
        } else {
            unusedTrailing + usedOptionals
        }
    }

    private fun isArgumentAssignable(parameterType: Type, argumentType: Type): Boolean {
        return parameterType.isAssignableFrom(argumentType) ||
            parameterType.isJavaListenerAssignableFrom(argumentType)
    }

    private data class Candidate(
        val signature: FunctionType,
        val index: Int,
        val score: Score
    )

    private data class Score(
        val requiredPenalty: Int,
        val assignabilityPenalty: Int,
        val exactMismatchCount: Int,
        val fallbackPenalty: Int
    )

    private companion object {
        /** Base penalty applied to every vararg/spread candidate (exact closed always lower). */
        const val VARARG_SPREAD_BASE_PENALTY = 100
    }
}


/**
 * TASK-525: Shared LuaJava `newArray` invalid-dimension surface for diagnostic emission.
 *
 * Typing degrade lives in [ExpressionTypeEvaluator]; this object only owns the stable
 * diagnostic code/message so checker dual-path rewrites stay localized.
 */
internal object LuaJavaNewArrayDimensionDiagnostics {
    const val CODE = "checker.luajava.newarray.dimension.invalid"
    const val MESSAGE = "Invalid newArray dimension: expected a positive numeric size."
}

/**
 * TASK-593: Shared LuaJava `loadLib` invalid-argument surface for diagnostic emission.
 *
 * Documented surface is `luajava.loadLib(className: string, methodName: string)`.
 * Typing degrade lives in [ExpressionTypeEvaluator]; this object only owns the stable
 * diagnostic code/message so ExpressionUsageChecker dual-path rewrites stay localized.
 *
 * Not stdout — structured [io.github.dingyi222666.luaparser.semantic.api.Diagnostic] only.
 */
internal object LuaJavaLoadLibArgumentDiagnostics {
    const val CODE = "checker.luajava.loadlib.arguments.invalid"
    const val MESSAGE =
        "Invalid loadLib arguments: expected two non-empty string arguments (className, methodName)."
}

