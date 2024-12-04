package io.github.dingyi222666.luaparser.semantic.types

import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.semantic.Diagnostic

class TypeInferer {
    private val diagnostics = mutableListOf<Diagnostic>()
    
    fun inferType(node: ExpressionNode, context: TypeContext): Type {
        diagnostics.clear()
        return doInferType(node, context)
    }
    
    fun getDiagnostics(): List<Diagnostic> = diagnostics.toList()

    private fun doInferType(node: ExpressionNode, context: TypeContext): Type {
        return when (node) {
            is ConstantNode -> inferConstantType(node)
            is BinaryExpression -> inferBinaryExpression(node, context)
            is UnaryExpression -> inferUnaryExpression(node, context)
            is CallExpression -> inferCallExpression(node, context)
            is TableConstructorExpression -> inferTableConstructor(node, context)
            is ArrayConstructorExpression -> inferArrayConstructor(node, context)
            is FunctionDeclaration -> inferFunctionDeclaration(node, context)
            is LambdaDeclaration -> inferLambdaDeclaration(node, context)
            is MemberExpression -> inferMemberExpression(node, context)
            is IndexExpression -> inferIndexExpression(node, context)
            is Identifier -> context.getType(node.name)
            else -> PrimitiveType.ANY
        }
    }

    private fun inferConstantType(node: ConstantNode): Type {
        return when (node.constantType) {
            ConstantNode.TYPE.NIL -> PrimitiveType.NIL
            ConstantNode.TYPE.BOOLEAN -> PrimitiveType.BOOLEAN
            ConstantNode.TYPE.STRING -> PrimitiveType.STRING
            ConstantNode.TYPE.INTERGER, ConstantNode.TYPE.FLOAT -> PrimitiveType.NUMBER
            ConstantNode.TYPE.UNKNOWN -> PrimitiveType.ANY
        }
    }

    private fun inferArrayConstructor(
        node: ArrayConstructorExpression,
        context: TypeContext
    ): TableType {
        val elementTypes = node.values.map { inferType(it, context) }.toSet()
        val elementType = when {
            elementTypes.isEmpty() -> PrimitiveType.ANY
            elementTypes.size == 1 -> elementTypes.first()
            else -> UnionType(elementTypes)
        }

        return TableType(
            fields = emptyMap(),
            indexSignature = TableType.IndexSignature(
                PrimitiveType.NUMBER,
                elementType
            )
        )
    }

    private fun inferMemberExpression(
        node: MemberExpression,
        context: TypeContext
    ): Type {
        val baseType = inferType(node.base, context)
        return when (baseType) {
            is TableType -> {
                baseType.fields[node.identifier.name] ?: run {
                    val fullPath = buildMemberPath(node)
                    context.getType(fullPath).takeIf { it != PrimitiveType.ANY }
                        ?: baseType.indexSignature?.valueType
                        ?: PrimitiveType.ANY
                }
            }
            else -> PrimitiveType.ANY
        }
    }

    private fun buildMemberPath(node: MemberExpression): String {
        return when (val base = node.base) {
            is MemberExpression -> "${buildMemberPath(base)}.${node.identifier.name}"
            else -> "${base}.${node.identifier.name}"
        }
    }

    private fun inferIndexExpression(
        node: IndexExpression,
        context: TypeContext
    ): Type {
        val baseType = inferType(node.base, context)
        val indexType = inferType(node.index, context)

        return when (baseType) {
            is TableType -> {
                baseType.indexSignature?.let { signature ->
                    if (signature.keyType.isAssignableFrom(indexType)) {
                        signature.valueType
                    } else {
                        PrimitiveType.ANY
                    }
                } ?: PrimitiveType.ANY
            }

            else -> PrimitiveType.ANY
        }
    }

    private fun inferLambdaDeclaration(
        node: LambdaDeclaration,
        context: TypeContext
    ): FunctionType {
        val paramTypes = node.params.map { param ->
            ParameterType(param.name, context.getType(param.name))
        }

        val functionContext = TypeContext(parent = context)
        node.params.forEach { param ->
            functionContext.defineType(param.name, PrimitiveType.ANY)
        }

        val returnType = inferType(node.expression, functionContext)

        return FunctionType(paramTypes, returnType)
    }

    private fun inferBinaryExpression(node: BinaryExpression, context: TypeContext): Type {
        val leftType = inferType(node.left!!, context)
        val rightType = inferType(node.right!!, context)

        return when (node.operator) {
            ExpressionOperator.ADD,
            ExpressionOperator.MINUS,
            ExpressionOperator.MULT,
            ExpressionOperator.DIV,
            ExpressionOperator.MOD -> {
                if (leftType != PrimitiveType.NUMBER || rightType != PrimitiveType.NUMBER) {
                    diagnostics.add(Diagnostic(
                        range = node.range,
                        message = "Operator '${node.operator}' cannot be applied to types '${leftType.name}' and '${rightType.name}'",
                        severity = Diagnostic.Severity.ERROR
                    ))
                }
                PrimitiveType.NUMBER
            }

            ExpressionOperator.CONCAT -> PrimitiveType.STRING

            ExpressionOperator.LT,
            ExpressionOperator.GT,
            ExpressionOperator.LE,
            ExpressionOperator.GE,
            ExpressionOperator.EQ,
            ExpressionOperator.NE -> PrimitiveType.BOOLEAN

            else -> PrimitiveType.ANY
        }
    }

    private fun inferUnaryExpression(node: UnaryExpression, context: TypeContext): Type {
        return when (node.operator) {
            ExpressionOperator.MINUS,
            ExpressionOperator.BIT_TILDE -> PrimitiveType.NUMBER

            ExpressionOperator.NOT -> PrimitiveType.BOOLEAN
            ExpressionOperator.GETLEN -> PrimitiveType.NUMBER
            else -> PrimitiveType.ANY
        }
    }

    private fun inferCallExpression(node: CallExpression, context: TypeContext): Type {
        val funcType = when (val base = node.base) {
            is MemberExpression -> {
                val baseType = inferType(base.base, context)
                when (baseType) {
                    is TableType -> {
                        val methodType = baseType.fields[base.identifier.name]
                        if (methodType is FunctionType) {
                            methodType
                        } else {
                            diagnostics.add(Diagnostic(
                                range = base.range,
                                message = "Member '${base.identifier.name}' is not a function",
                                severity = Diagnostic.Severity.ERROR
                            ))
                            return PrimitiveType.ANY
                        }
                    }
                    else -> {
                        diagnostics.add(Diagnostic(
                            range = base.base.range,
                            message = "Cannot read property '${base.identifier.name}' of type '${baseType.name}'",
                            severity = Diagnostic.Severity.ERROR
                        ))
                        return PrimitiveType.ANY
                    }
                }
            }
            else -> {
                val type = inferType(base, context)
                if (type !is FunctionType) {
                    diagnostics.add(Diagnostic(
                        range = base.range,
                        message = "Value of type '${type.name}' is not callable",
                        severity = Diagnostic.Severity.ERROR
                    ))
                    return PrimitiveType.ANY
                }
                type
            }
        }

        val argumentTypes = node.arguments.map { inferType(it, context) }
        if (argumentTypes.size != funcType.parameters.size) {
            diagnostics.add(Diagnostic(
                range = node.range,
                message = "Expected ${funcType.parameters.size} arguments, but got ${argumentTypes.size}",
                severity = Diagnostic.Severity.ERROR
            ))
            return PrimitiveType.ANY
        }

        argumentTypes.zip(funcType.parameters).forEachIndexed { index, (argType, param) ->
            if (!param.type.isAssignableFrom(argType)) {
                diagnostics.add(Diagnostic(
                    range = node.arguments[index].range,
                    message = "Argument of type '${argType.name}' is not assignable to parameter of type '${param.type.name}'",
                    severity = Diagnostic.Severity.ERROR
                ))
            }
        }

        return funcType.returnType
    }

    private fun inferTableConstructor(
        node: TableConstructorExpression,
        context: TypeContext
    ): TableType {
        val fields = mutableMapOf<String, Type>()
        
        node.fields.forEach { field ->
            when (field) {
                is TableKeyString -> {
                    val key = field.key
                    val valueExpr = field.value
                    
                    when (key) {
                        is Identifier -> {
                            val valueType = when (valueExpr) {
                                is Identifier -> {
                                    context.getType(valueExpr.name)
                                }
                                is MemberExpression -> {
                                    inferMemberExpression(valueExpr, context)
                                }
                                else -> inferType(valueExpr, context)
                            }
                            
                            fields[key.name] = valueType
                        }
                        is ConstantNode -> {
                            fields[key.stringOf()] = inferType(valueExpr, context)
                        }
                    }
                }
                is TableKey -> {
                    when (val key = field.key) {
                        is ConstantNode -> {
                            when (key.constantType) {
                                ConstantNode.TYPE.INTERGER -> {
                                    fields[key.intOf().toString()] = inferType(field.value, context)
                                }
                                ConstantNode.TYPE.STRING -> {
                                    fields[key.stringOf()] = inferType(field.value, context)
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
        
        return TableType(fields)
    }

    private fun inferFunctionDeclaration(
        node: FunctionDeclaration,
        context: TypeContext
    ): FunctionType {
        val functionContext = TypeContext(parent = context)

        val paramTypes = node.params.map { param ->
            val paramType = PrimitiveType.ANY
            functionContext.defineType(param.name, paramType)
            ParameterType(param.name, paramType)
        }

        val returnType = node.body?.let { node ->
            inferFunctionReturnType(node, functionContext)
        } ?: PrimitiveType.ANY

        return FunctionType(paramTypes, returnType)
    }

    private fun inferFunctionReturnType(body: BlockNode, context: TypeContext): Type {
        val returnTypes = mutableSetOf<Type>()

        body.statements.forEach { stmt ->
            if (stmt is ReturnStatement) {
                if (stmt.arguments.isEmpty()) {
                    returnTypes.add(PrimitiveType.NIL)
                } else {
                    val firstReturnType = inferType(stmt.arguments.first(), context)
                    returnTypes.add(firstReturnType)
                }
            }
        }

        return when {
            returnTypes.isEmpty() -> PrimitiveType.NIL
            returnTypes.size == 1 -> returnTypes.first()
            else -> UnionType(returnTypes)
        }
    }
} 