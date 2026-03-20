package io.github.dingyi222666.luaparser.semantic.types.syntax

object TypeSyntaxRenderer {

    fun render(type: TypeSyntax): String = render(type, parentPrecedence = Precedence.TOP_LEVEL)

    private fun render(type: TypeSyntax, parentPrecedence: Int, isRightChild: Boolean = false): String {
        val currentPrecedence = precedenceOf(type)
        val rendered = when (type) {
            is NamedTypeSyntax -> type.name
            is LiteralTypeSyntax -> type.value
            is UnionTypeSyntax -> renderSeparated(type.options, " | ", currentPrecedence)
            is IntersectionTypeSyntax -> renderSeparated(type.types, " & ", currentPrecedence)
            is ArrayTypeSyntax -> "${render(type.elementType, currentPrecedence)}[]"
            is GenericTypeSyntax -> {
                val base = render(type.baseType, currentPrecedence)
                val arguments = type.arguments.joinToString(", ") { render(it, Precedence.TOP_LEVEL) }
                "$base<$arguments>"
            }

            is NullableTypeSyntax -> "${render(type.innerType, currentPrecedence)}?"
            is TupleTypeSyntax -> type.elements.joinToString(prefix = "[", postfix = "]", separator = ", ") { render(it) }
            is MultiReturnTypeSyntax -> buildMultiReturnType(type, currentPrecedence)
            is VarargTypeSyntax -> "${render(type.elementType, currentPrecedence)}..."
            is FunctionTypeSyntax -> buildFunctionType(type)
            is ObjectTypeSyntax -> buildObjectType(type)
            is IndexTableTypeSyntax -> "table<${render(type.keyType)}, ${render(type.valueType)}>"
        }

        return if (needsParentheses(type, currentPrecedence, parentPrecedence, isRightChild)) "($rendered)" else rendered
    }

    private fun renderSeparated(types: List<TypeSyntax>, separator: String, parentPrecedence: Int): String {
        return buildString {
            types.forEachIndexed { index, type ->
                if (index != 0) {
                    append(separator)
                }
                append(render(type, parentPrecedence, isRightChild = index > 0))
            }
        }
    }

    private fun buildMultiReturnType(type: MultiReturnTypeSyntax, currentPrecedence: Int): String {
        return buildString {
            type.types.forEachIndexed { index, child ->
                if (index != 0) {
                    append(", ")
                }

                val renderedChild = render(child, currentPrecedence)
                if (index > 0 && child.requiresDelimitedMultiReturnParens()) {
                    append('(')
                    append(renderedChild)
                    append(')')
                } else {
                    append(renderedChild)
                }
            }
        }
    }

    private fun buildFunctionType(type: FunctionTypeSyntax): String {
        val typeParameterText = if (type.typeParameters.isEmpty()) {
            ""
        } else {
            type.typeParameters.joinToString(prefix = "<", postfix = ">", separator = ", ") { parameter ->
                parameter.constraint?.let { "${parameter.name}: ${render(it)}" } ?: parameter.name
            }
        }

        val parameterText = type.parameters.joinToString(", ") { parameter ->
            buildString {
                parameter.name?.let {
                    append(it)
                    if (parameter.optional) {
                        append('?')
                    }
                    append(": ")
                }
                append(render(parameter.type, Precedence.MULTI_RETURN))
                if (parameter.vararg) {
                    append("...")
                }
            }
        }

        return "fun$typeParameterText($parameterText): ${render(type.returnType, Precedence.FUNCTION, isRightChild = true)}"
    }

    private fun buildObjectType(type: ObjectTypeSyntax): String {
        val members = buildList {
            addAll(type.fields.map { field ->
                val optional = if (field.optional) "?" else ""
                "${render(field.name)}$optional: ${render(field.type, Precedence.MULTI_RETURN)}"
            })
            addAll(type.indexers.map { indexer ->
                val keyPrefix = indexer.keyName?.let { "$it: " } ?: ""
                "[$keyPrefix${render(indexer.keyType)}]: ${render(indexer.valueType, Precedence.MULTI_RETURN)}"
            })
        }

        if (members.isEmpty()) {
            return "{}"
        }

        return members.joinToString(prefix = "{ ", postfix = " }", separator = ", ")
    }

    private fun precedenceOf(type: TypeSyntax): Int {
        return when (type) {
            is UnionTypeSyntax -> Precedence.UNION
            is IntersectionTypeSyntax -> Precedence.INTERSECTION
            is FunctionTypeSyntax -> Precedence.FUNCTION
            is MultiReturnTypeSyntax -> Precedence.MULTI_RETURN
            is NullableTypeSyntax, is ArrayTypeSyntax, is VarargTypeSyntax -> Precedence.SUFFIX
            is GenericTypeSyntax -> Precedence.GENERIC
            is NamedTypeSyntax,
            is LiteralTypeSyntax,
            is TupleTypeSyntax,
            is ObjectTypeSyntax,
            is IndexTableTypeSyntax -> Precedence.PRIMARY
        }
    }

    private fun render(name: ObjectFieldNameSyntax): String {
        return when (name) {
            is IdentifierObjectFieldNameSyntax -> name.value
            is QuotedObjectFieldNameSyntax -> name.literal
        }
    }

    private fun needsParentheses(
        type: TypeSyntax,
        currentPrecedence: Int,
        parentPrecedence: Int,
        isRightChild: Boolean
    ): Boolean {
        if (currentPrecedence < parentPrecedence) {
            return true
        }
        if (currentPrecedence > parentPrecedence) {
            return false
        }

        return isRightChild && (type is UnionTypeSyntax || type is IntersectionTypeSyntax || type is MultiReturnTypeSyntax)
    }

    private fun TypeSyntax.requiresDelimitedMultiReturnParens(): Boolean {
        return this is UnionTypeSyntax || this is IntersectionTypeSyntax || this is MultiReturnTypeSyntax
    }

    private object Precedence {
        const val TOP_LEVEL = 0
        const val FUNCTION = 1
        const val MULTI_RETURN = 2
        const val UNION = 3
        const val INTERSECTION = 4
        const val SUFFIX = 5
        const val GENERIC = 6
        const val PRIMARY = 7
    }
}

fun TypeSyntax.render(): String = TypeSyntaxRenderer.render(this)
