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

/**
 * Reverse direction: projects the canonical syntax AST onto the legacy `semantic.types.*` shape.
 *
 * There used to be two hand-written recursive-descent parsers for the same annotation grammar —
 * one per AST shape — parsing the same text back to back. [TypeSyntaxParser] is now the only
 * parser; the legacy facade re-projects its output through here, so grammar fixes land once.
 */
fun TypeSyntax.toLegacySyntax(): LegacyTypeSyntax {
    return when (this) {
        is NamedTypeSyntax -> LegacyNamedTypeSyntax(name)
        is LiteralTypeSyntax -> LegacyLiteralTypeSyntax(value)
        is UnionTypeSyntax -> LegacyUnionTypeSyntax(options.map { it.toLegacySyntax() })
        is IntersectionTypeSyntax -> LegacyIntersectionTypeSyntax(types.map { it.toLegacySyntax() })
        is ArrayTypeSyntax -> LegacyArrayTypeSyntax(elementType.toLegacySyntax())
        is NullableTypeSyntax -> LegacyNullableTypeSyntax(innerType.toLegacySyntax())
        is TupleTypeSyntax -> LegacyTupleTypeSyntax(elements.map { it.toLegacySyntax() })
        is MultiReturnTypeSyntax -> LegacyMultiReturnTypeSyntax(types.map { it.toLegacySyntax() })
        is VarargTypeSyntax -> LegacyVarargTypeSyntax(elementType.toLegacySyntax())
        is IndexTableTypeSyntax -> LegacyIndexTypeSyntax(keyType.toLegacySyntax(), valueType.toLegacySyntax())

        is GenericTypeSyntax -> LegacyGenericTypeSyntax(
            baseName = (baseType as? NamedTypeSyntax)?.name ?: baseType.toLegacySyntax().legacyBaseName(),
            arguments = arguments.map { it.toLegacySyntax() }
        )

        is FunctionTypeSyntax -> LegacyFunctionTypeSyntax(
            parameters = parameters.map { parameter ->
                LegacyFunctionParameterSyntax(
                    name = parameter.name ?: if (parameter.vararg) "..." else null,
                    type = parameter.type.toLegacySyntax(),
                    optional = parameter.optional,
                    vararg = parameter.vararg
                )
            },
            // The legacy shape stores a return list; MultiReturn flattens back into it.
            returnTypes = when (val returned = returnType) {
                is MultiReturnTypeSyntax -> returned.types.map { it.toLegacySyntax() }
                else -> listOf(returned.toLegacySyntax())
            },
            typeParameters = typeParameters.map { parameter ->
                LegacyTypeParameterDeclarationSyntax(
                    name = parameter.name,
                    constraint = parameter.constraint?.toLegacySyntax()
                )
            }
        )

        // Legacy object types carry no indexer syntax. An indexer-only object is exactly a
        // legacy index type; when fields are also present the fields win (legacy could not
        // have produced such a node at all, so nothing regresses).
        is ObjectTypeSyntax -> when {
            fields.isEmpty() && indexers.size == 1 -> LegacyIndexTypeSyntax(
                keyType = indexers.single().keyType.toLegacySyntax(),
                valueType = indexers.single().valueType.toLegacySyntax()
            )

            else -> LegacyObjectTypeSyntax(
                fields = fields.map { field ->
                    LegacyObjectFieldSyntax(
                        name = when (val name = field.name) {
                            is IdentifierObjectFieldNameSyntax -> name.value
                            is QuotedObjectFieldNameSyntax -> name.literal
                                .removeSurrounding("\"")
                                .removeSurrounding("'")
                        },
                        type = field.type.toLegacySyntax(),
                        optional = field.optional
                    )
                }
            )
        }
    }
}

private fun LegacyTypeSyntax.legacyBaseName(): String {
    return (this as? LegacyNamedTypeSyntax)?.identifier ?: "table"
}
