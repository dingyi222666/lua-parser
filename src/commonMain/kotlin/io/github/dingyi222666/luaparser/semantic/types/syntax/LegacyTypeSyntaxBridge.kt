package io.github.dingyi222666.luaparser.semantic.types.syntax

import io.github.dingyi222666.luaparser.semantic.types.ArrayTypeSyntax as LegacyArrayTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.FunctionParameterSyntax as LegacyFunctionParameterSyntax
import io.github.dingyi222666.luaparser.semantic.types.FunctionTypeSyntax as LegacyFunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.GenericTypeSyntax as LegacyGenericTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.IndexTypeSyntax as LegacyIndexTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.IntersectionTypeSyntax as LegacyIntersectionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.LiteralTypeSyntax as LegacyLiteralTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.MultiReturnTypeSyntax as LegacyMultiReturnTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.NamedTypeSyntax as LegacyNamedTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.NullableTypeSyntax as LegacyNullableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.ObjectFieldSyntax as LegacyObjectFieldSyntax
import io.github.dingyi222666.luaparser.semantic.types.ObjectTypeSyntax as LegacyObjectTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.TupleTypeSyntax as LegacyTupleTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.TypeParameterDeclarationSyntax as LegacyTypeParameterDeclarationSyntax
import io.github.dingyi222666.luaparser.semantic.types.TypeSyntax as LegacyTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.UnionTypeSyntax as LegacyUnionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.VarargTypeSyntax as LegacyVarargTypeSyntax

fun LegacyTypeSyntax.toSyntaxAst(): TypeSyntax {
    return when (this) {
        is LegacyNamedTypeSyntax -> NamedTypeSyntax(name = identifier)
        is LegacyLiteralTypeSyntax -> LiteralTypeSyntax(value = value)
        is LegacyUnionTypeSyntax -> UnionTypeSyntax(options = types.map { it.toSyntaxAst() })
        is LegacyIntersectionTypeSyntax -> IntersectionTypeSyntax(types = types.map { it.toSyntaxAst() })
        is LegacyArrayTypeSyntax -> ArrayTypeSyntax(elementType = elementType.toSyntaxAst())
        is LegacyGenericTypeSyntax -> GenericTypeSyntax(
            baseType = NamedTypeSyntax(baseName),
            arguments = arguments.map { it.toSyntaxAst() }
        )

        is LegacyNullableTypeSyntax -> NullableTypeSyntax(innerType = innerType.toSyntaxAst())
        is LegacyTupleTypeSyntax -> TupleTypeSyntax(elements = elementTypes.map { it.toSyntaxAst() })
        is LegacyMultiReturnTypeSyntax -> MultiReturnTypeSyntax(types = types.map { it.toSyntaxAst() })
        is LegacyVarargTypeSyntax -> VarargTypeSyntax(elementType = elementType.toSyntaxAst())
        is LegacyFunctionTypeSyntax -> FunctionTypeSyntax(
            parameters = parameters.map(LegacyFunctionParameterSyntax::toSyntaxAst),
            returnType = when (returnTypes.size) {
                0 -> NamedTypeSyntax("nil")
                1 -> returnTypes.single().toSyntaxAst()
                else -> MultiReturnTypeSyntax(returnTypes.map { it.toSyntaxAst() })
            },
            typeParameters = typeParameters.map(LegacyTypeParameterDeclarationSyntax::toSyntaxAst)
        )

        is LegacyObjectTypeSyntax -> ObjectTypeSyntax(fields = fields.map(LegacyObjectFieldSyntax::toSyntaxAst))
        is LegacyIndexTypeSyntax -> IndexTableTypeSyntax(
            keyType = keyType.toSyntaxAst(),
            valueType = valueType.toSyntaxAst()
        )
    }
}

fun LegacyFunctionParameterSyntax.toSyntaxAst(): FunctionParameterSyntax {
    return FunctionParameterSyntax(
        name = if (vararg && name == "...") null else name,
        type = type.toSyntaxAst(),
        optional = optional,
        vararg = vararg
    )
}

fun LegacyTypeParameterDeclarationSyntax.toSyntaxAst(): TypeParameterSyntax {
    return TypeParameterSyntax(
        name = name,
        constraint = constraint?.toSyntaxAst()
    )
}

fun LegacyObjectFieldSyntax.toSyntaxAst(): ObjectFieldSyntax {
    return ObjectFieldSyntax(
        name = IdentifierObjectFieldNameSyntax(name),
        type = type.toSyntaxAst(),
        optional = optional
    )
}
