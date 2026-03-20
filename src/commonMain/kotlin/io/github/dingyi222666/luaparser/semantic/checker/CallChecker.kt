package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
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

        val sorted = compatible.sortedWith(compareBy<Candidate>({ it.score.requiredPenalty }, { it.score.assignabilityPenalty }, { it.score.exactMismatchCount }, { it.score.fallbackPenalty }, { it.index }))
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

    private fun rankSignature(signature: FunctionType, argumentTypes: List<Type>, index: Int): Candidate? {
        val requiredParameters = signature.parameters.count { !it.optional && !it.vararg }
        val hasVararg = signature.parameters.any { it.vararg }
        if (argumentTypes.size < requiredParameters) {
            return null
        }
        if (!hasVararg && argumentTypes.size > signature.parameters.size) {
            return null
        }

        var assignabilityPenalty = 0
        var exactMismatchCount = 0
        var fallbackPenalty = 0

        argumentTypes.forEachIndexed { argumentIndex, argumentType ->
            val parameter = signature.parameters.getOrNull(argumentIndex)
                ?: signature.parameters.lastOrNull { it.vararg }
                ?: return null
            if (!parameter.type.isAssignableFrom(argumentType)) {
                return null
            }
            if (parameter.type != argumentType) {
                exactMismatchCount++
            }
            if (parameter.vararg) {
                fallbackPenalty += 2
            }
        }

        if (argumentTypes.size < signature.parameters.size) {
            signature.parameters.drop(argumentTypes.size).forEach { parameter ->
                if (parameter.optional) {
                    fallbackPenalty += 1
                } else if (parameter.vararg) {
                    fallbackPenalty += 2
                }
            }
        }

        signature.parameters.forEachIndexed { parameterIndex, parameter ->
            if (parameterIndex >= argumentTypes.size && !parameter.optional && !parameter.vararg) {
                assignabilityPenalty += 10
            }
        }

        return Candidate(
            signature = signature,
            index = index,
            score = Score(
                requiredPenalty = 0,
                assignabilityPenalty = assignabilityPenalty,
                exactMismatchCount = exactMismatchCount,
                fallbackPenalty = fallbackPenalty
            )
        )
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
}
