package io.github.dingyi222666.luaparser.semantic

import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.comment.ClassTag
import io.github.dingyi222666.luaparser.semantic.types.*
import io.github.dingyi222666.luaparser.semantic.symbol.SymbolTable
import io.github.dingyi222666.luaparser.semantic.comment.CommentProcessor
import io.github.dingyi222666.luaparser.semantic.comment.FieldTag
import io.github.dingyi222666.luaparser.semantic.comment.GenericTag
import io.github.dingyi222666.luaparser.semantic.comment.MethodTag
import io.github.dingyi222666.luaparser.semantic.comment.ParamTag
import io.github.dingyi222666.luaparser.semantic.comment.ReturnTag
import io.github.dingyi222666.luaparser.semantic.comment.TypeTag
import io.github.dingyi222666.luaparser.semantic.symbol.Symbol

class SemanticAnalyzer : ASTVisitor<TypeContext> {
    private val typeInferer = TypeInferer()
    private val typeAnnotationParser = TypeAnnotationParser()
    private val commentProcessor = CommentProcessor(typeAnnotationParser)
    private val diagnostics = mutableListOf<Diagnostic>()
    private var currentSymbolTable: SymbolTable = SymbolTable()
    
    private val globalSymbols = mutableMapOf<String, Symbol>()

    init {
        defineGlobalSymbol("print", FunctionType(
            parameters = listOf(ParameterType("...", PrimitiveType.ANY, vararg = true)),
            returnType = PrimitiveType.NIL
        ))
    }

    private fun defineGlobalSymbol(name: String, type: Type) {
        globalSymbols[name] = Symbol(name, type, Symbol.Kind.VARIABLE)
    }

    fun analyze(ast: ChunkNode): AnalysisResult {
        diagnostics.clear()
        currentSymbolTable = SymbolTable()
        

        visitChunkNode(ast, TypeContext())
        
        return AnalysisResult(
            diagnostics = diagnostics,
            symbolTable = currentSymbolTable,
            globalSymbols = globalSymbols.toMap()
        )
    }

    override fun visitBlockNode(node: BlockNode, context: TypeContext) {
        val previousTable = currentSymbolTable
        currentSymbolTable = currentSymbolTable.createChild(node.range)
        
        commentProcessor.processComments(node.statements)
        
        processClassDefinitions(node.statements)
        
        super.visitBlockNode(node, context)
        
        currentSymbolTable = previousTable
    }


    private fun processClassDefinitions(statements: List<StatementNode>) {
        // 第一遍：收集所有类定义
        statements.forEach { stmt ->
            if (stmt is LocalStatement || stmt is AssignmentStatement) {
                val comments = commentProcessor.getAdjacentComments(stmt)
                val classTag = comments.findLast { comment ->
                    comment.isDocComment && comment.comment.contains("@class")
                }?.let { comment ->
                    commentProcessor.parseDocComment(comment, comment.range.start.line)
                        .tags.firstOrNull { it is ClassTag } as? ClassTag
                }

                if (classTag != null) {
                    val fields = mutableMapOf<String, Type>()
                    val methods = mutableMapOf<String, FunctionType>()
                    
                    // 处理类的字段和基本方法
                    comments.forEach { comment ->
                        if (comment.isDocComment) {
                            val docComment = commentProcessor.parseDocComment(
                                comment, 
                                comment.range.start.line
                            )
                            
                            docComment.tags.forEach { tag ->
                                when (tag) {
                                    is FieldTag -> fields[tag.name] = tag.type
                                    is MethodTag -> methods[tag.methodName] = tag.type
                                    is ClassTag -> TODO()
                                    is GenericTag -> TODO()
                                    is ParamTag -> TODO()
                                    is ReturnTag -> TODO()
                                    is TypeTag -> TODO()
                                }
                            }
                        }
                    }
                    
                    // 添加动态声明的方法
                    methods.putAll(commentProcessor.getClassMethods(classTag.name))

                    val classType = typeAnnotationParser.defineClass(
                        name = classTag.name,
                        fields = fields,
                        methods = methods
                    )

                    if (stmt is LocalStatement) {
                        stmt.init.forEach { id ->
                            currentSymbolTable.define(
                                id.name,
                                classType,
                                Symbol.Kind.CLASS,
                                id.range
                            )
                        }
                    }
                }
            }
        }
        
        // 第二遍：处理方法实现和类型检查
        statements.forEach { stmt ->
            if (stmt is AssignmentStatement) {
                val target = stmt.init.firstOrNull()
                if (target is MemberExpression) {
                    val baseType = typeInferer.inferType(target.base, TypeContext())
                    if (baseType is ClassType) {
                        // 检查是否是方法赋值
                        val methodName = target.identifier.name
                        val methodType = baseType.methods[methodName]
                        if (methodType != null) {
                            // 验证方法实现的类型
                            val implementation = stmt.variables.firstOrNull()
                            if (implementation != null) {
                                val implType = typeInferer.inferType(implementation, TypeContext())
                                if (!methodType.isAssignableFrom(implType)) {
                                    diagnostics.add(Diagnostic(
                                        range = stmt.range,
                                        message = "Method implementation type mismatch: expected ${methodType.name}, got ${implType.name}",
                                        severity = Diagnostic.Severity.ERROR
                                    ))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun visitAttributeIdentifier(
        identifier: AttributeIdentifier,
        value: TypeContext
    ) {
        TODO("Not yet implemented")
    }

    override fun visitLocalStatement(node: LocalStatement, context: TypeContext) {
        node.init.forEachIndexed { index, identifier ->
            val valueExpr = node.variables.getOrNull(index)
            
            // 获取声明的类型注释
            val declaredType = commentProcessor.findTypeAnnotation(node) ?: run {
                if (valueExpr != null) {
                    when (valueExpr) {
                        is FunctionDeclaration -> {
                            // 对于函数声明，获取其文档注释
                            val docComment = commentProcessor.getFunctionDocComment(valueExpr)
                            if (docComment != null) {
                                try {
                                    val params = mutableListOf<ParameterType>()
                                    var returnType: Type = PrimitiveType.NIL
                                    
                                    docComment.tags.forEach { tag ->
                                        when (tag) {
                                            is ParamTag -> {
                                                params.add(ParameterType(tag.name, tag.type))
                                            }
                                            is ReturnTag -> returnType = tag.type
                                            else -> {} // 忽略其他标签
                                        }
                                    }
                                    
                                    FunctionType(params, returnType)
                                } catch (e: Exception) {
                                    typeInferer.inferType(valueExpr, context)
                                }
                            } else {
                                typeInferer.inferType(valueExpr, context)
                            }
                        }
                        else -> typeInferer.inferType(valueExpr, context)
                    }
                } else {
                    PrimitiveType.ANY
                }
            }
            
            currentSymbolTable.define(
                identifier.name,
                declaredType,
                Symbol.Kind.LOCAL,
                identifier.range
            )
            
            context.defineType(identifier.name, declaredType)
            
            if (valueExpr != null) {
                val inferredType = typeInferer.inferType(valueExpr, context)
                diagnostics.addAll(typeInferer.getDiagnostics())
                
                if (!declaredType.isAssignableFrom(inferredType)) {
                    diagnostics.add(Diagnostic(
                        range = node.range,
                        message = "Type '${inferredType.name}' is not assignable to type '${declaredType.name}'",
                        severity = Diagnostic.Severity.ERROR
                    ))
                }
            }
        }
    }

    override fun visitFunctionDeclaration(node: FunctionDeclaration, context: TypeContext) {
        val functionScope = currentSymbolTable.createChild(node.range)
        val previousTable = currentSymbolTable
        currentSymbolTable = functionScope
        
        // 获取函数的文档注释
        val docComment = commentProcessor.getFunctionDocComment(node)
        
        // 获取推导的类型
        val inferredType = typeInferer.inferType(node, context) as? FunctionType

        // 处理函数类型
        val functionType = if (docComment != null) {
            try {
                val params = mutableListOf<ParameterType>()
                var returnType: Type = inferredType?.returnType ?: PrimitiveType.NIL
                val genericParams = mutableListOf<GenericTag>()

                // 创建参数映射
                val paramMap = node.params.associateBy { it.name }
                
                // 处理参数和返回类型标注
                docComment.tags.forEach { tag ->
                    when (tag) {
                        is ParamTag -> {
                            println(tag)
                            // 只处理存在的参数
                            if (paramMap.containsKey(tag.name)) {
                                params.add(ParameterType(tag.name, tag.type))
                            }
                        }
                        is GenericTag -> {
                            genericParams.add(tag)
                            context.defineType(tag.name, GenericType(tag.name, emptyList()))
                        }

                        is ReturnTag -> returnType = tag.type
                        else -> {} // 忽略其他标签
                    }
                }
                
                // 如果有参数没有类型注释，使用推导的类型或 ANY
                node.params.forEach { param ->
                    if (!params.any { it.name == param.name }) {
                        val inferredParamType = inferredType?.parameters?.find { it.name == param.name }?.type
                        params.add(ParameterType(param.name, inferredParamType ?: PrimitiveType.ANY))
                    }
                }
                
                FunctionType(params, returnType)
            } catch (e: Exception) {
                inferredType ?: FunctionType(
                    parameters = node.params.map { ParameterType(it.name, PrimitiveType.ANY) },
                    returnType = PrimitiveType.ANY
                )
            }
        } else {
            inferredType ?: FunctionType(
                parameters = node.params.map { ParameterType(it.name, PrimitiveType.ANY) },
                returnType = PrimitiveType.ANY
            )
        }
        
        // 处理函数标识符
        when (val identifier = node.identifier) {
            is Identifier -> {
                if (!node.isLocal) {
                    // 全局函数
                    defineGlobalSymbol(identifier.name, functionType)
                    context.defineType(identifier.name, functionType)
                } else {
                    // 局部函数
                    currentSymbolTable.define(
                        identifier.name,
                        functionType,
                        Symbol.Kind.FUNCTION,
                        identifier.range
                    )
                    context.defineType(identifier.name, functionType)
                }
            }
            is MemberExpression -> {
                // 处理方法声明
                val baseType = typeInferer.inferType(identifier.base, context)
                if (baseType is ClassType) {
                    val methodName = identifier.identifier.name
                    // 更新类的方法
                    val updatedMethods = baseType.methods + (methodName to functionType)
                    typeAnnotationParser.defineClass(
                        baseType.name,
                        baseType.fields,
                        updatedMethods,
                        baseType.parent?.name
                    )
                }
            }
        }
        
        // 处理参数
        node.params.forEach { param ->
            val paramType = functionType.parameters.find { it.name == param.name }?.type ?: PrimitiveType.ANY
            currentSymbolTable.define(
                param.name,
                paramType,
                Symbol.Kind.PARAMETER,
                param.range
            )
            context.defineType(param.name, paramType)
        }
        
        // 处理函数体
        node.body?.let { visitBlockNode(it, context) }
        
        currentSymbolTable = previousTable
    }

    override fun visitAssignmentStatement(node: AssignmentStatement, context: TypeContext) {
        node.init.forEachIndexed { index, target ->
            val valueExpr = node.variables.getOrNull(index)
            if (valueExpr != null) {
                val targetType = when (target) {
                    is Identifier -> {
                        if (!target.isLocal) {
                            val inferredType = typeInferer.inferType(valueExpr, context)
                            val declaredType = commentProcessor.findTypeAnnotation(node) ?: inferredType
                            
                            defineGlobalSymbol(target.name, declaredType)
                            
                            if (!declaredType.isAssignableFrom(inferredType)) {
                                diagnostics.add(Diagnostic(
                                    range = node.range,
                                    message = "Global variable '${target.name}' of type '${declaredType.name}' is not assignable from type '${inferredType.name}'",
                                    severity = Diagnostic.Severity.ERROR
                                ))
                            }
                            
                            declaredType
                        } else {
                            val inferredType = typeInferer.inferType(valueExpr, context)
                            currentSymbolTable.define(
                                target.name,
                                inferredType,
                                Symbol.Kind.VARIABLE,
                                target.range
                            )
                            context.defineType(target.name, inferredType)
                            inferredType
                        }
                    }
                    is MemberExpression -> {
                        val baseType = typeInferer.inferType(target.base, context)
                        when (baseType) {
                            is TableType -> baseType.fields[target.identifier.name] ?: PrimitiveType.ANY
                            else -> PrimitiveType.ANY
                        }
                    }
                    else -> PrimitiveType.ANY
                }
                
                val valueType = typeInferer.inferType(valueExpr, context)
                if (!targetType.isAssignableFrom(valueType)) {
                    diagnostics.add(Diagnostic(
                        range = node.range,
                        message = "Type '${valueType.name}' is not assignable to type '${targetType.name}'",
                        severity = Diagnostic.Severity.ERROR
                    ))
                }
            }
        }
    }

    override fun visitMemberExpression(node: MemberExpression, context: TypeContext) {
        val baseType = typeInferer.inferType(node.base, context)
        when (baseType) {
            is TableType -> {
                val fieldType = baseType.fields[node.identifier.name]
                print(fieldType)
                if (fieldType != null) {
                    val fullPath = buildMemberPath(node)
                    context.defineType(fullPath, fieldType)
                    
                    context.defineType(node.toString(), fieldType)
                }
            }

            is FunctionType -> TODO()
            NeverType -> TODO()
            is PrimitiveType -> TODO()
            is UnionType -> TODO()
            is ArrayType -> TODO()
            is CustomType -> TODO()
            is GenericType -> TODO()
            is VarArgType -> TODO()
            is ClassType -> {
                val member = if (node.indexer == ":") {
                    baseType.getAllMethods()[node.identifier.name]
                } else {
                    baseType.getAllFields()[node.identifier.name]
                }
                
                if (member != null) {
                    context.defineType(buildMemberPath(node), member)
                    context.defineType(node.toString(), member)
                } else {
                    diagnostics.add(Diagnostic(
                        range = node.range,
                        message = "Member '${node.identifier.name}' not found in class '${baseType.name}'",
                        severity = Diagnostic.Severity.ERROR
                    ))
                }
            }
        }
    }

    private fun buildMemberPath(node: MemberExpression): String {
        return when (val base = node.base) {
            is MemberExpression -> "${buildMemberPath(base)}.${node.identifier.name}"
            else -> "${base}.${node.identifier.name}"
        }
    }

    override fun visitCommentStatement(commentStatement: CommentStatement, value: TypeContext) {
        super.visitCommentStatement(commentStatement, value)
    }

    private fun resolveSymbol(name: String, position: Position): Symbol? {
        return currentSymbolTable.resolveAtPosition(name, position) 
            ?: globalSymbols[name]
    }


    private fun parseType(typeStr: String?): Type {
        if (typeStr == null) return PrimitiveType.ANY
        
        return typeAnnotationParser.parse(typeStr)
    }
}

data class AnalysisResult(
    val diagnostics: List<Diagnostic>,
    val symbolTable: SymbolTable,
    val globalSymbols: Map<String, Symbol>
)

data class Diagnostic(
    val range: Range,
    val message: String,
    val severity: Severity = Severity.ERROR
) {
    enum class Severity {
        ERROR,
        WARNING,
        INFO
    }
}
