package io.github.dingyi222666.luaparser.semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType

fun unionTypeOf(vararg types: Type): Type = unionTypeOf(types.asIterable())

fun unionTypeOf(types: Iterable<Type>): Type = TypeNormalizer.normalize(
    UnionType(types.toCollection(linkedSetOf()))
)

fun intersectionTypeOf(vararg types: Type): Type = intersectionTypeOf(types.asIterable())

fun intersectionTypeOf(types: Iterable<Type>): Type = TypeNormalizer.normalize(
    IntersectionType(types.toCollection(linkedSetOf()))
)

fun optionalTypeOf(type: Type): Type = unionTypeOf(type, PrimitiveType.NIL)

fun overloadedFunctionOf(vararg signatures: FunctionType): CallableType = overloadedFunctionOf(signatures.asIterable())

fun overloadedFunctionOf(signatures: Iterable<FunctionType>): CallableType {
    val normalizedSignatures = signatures.map { TypeNormalizer.normalize(it) as FunctionType }
    require(normalizedSignatures.isNotEmpty()) { "overloadedFunctionOf requires at least one signature" }

    return when (normalizedSignatures.size) {
        1 -> normalizedSignatures.single()
        else -> TypeNormalizer.normalize(OverloadedFunctionType(normalizedSignatures)) as CallableType
    }
}
