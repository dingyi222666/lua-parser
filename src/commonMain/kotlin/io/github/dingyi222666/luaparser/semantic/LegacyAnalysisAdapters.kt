package io.github.dingyi222666.luaparser.semantic

import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.comments.ClassTagSyntax
import io.github.dingyi222666.luaparser.semantic.symbol.GlobalSymbolTable
import io.github.dingyi222666.luaparser.semantic.symbol.Symbol
import io.github.dingyi222666.luaparser.semantic.symbol.SymbolTable
import io.github.dingyi222666.luaparser.semantic.types.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.bridges.toLegacyType
import io.github.dingyi222666.luaparser.semantic.types.model.Type as ModelType
import io.github.dingyi222666.luaparser.semantic.types.resolve.isAssignableFrom

/**
 * Compatibility adapters that translate pipeline output into the legacy semantic surface.
 */
internal object LegacyAnalysisAdapters {
    fun adapt(snapshot: SemanticPipelineSnapshot): AnalysisResult {
        val symbolTables = LegacySymbolTableBuilder(snapshot).build()
        val diagnostics = buildList {
            addAll(snapshot.model.getDiagnostics().map(::toLegacyDiagnostic))
            addAll(collectLegacyAssignmentDiagnostics(snapshot))
        }.distinctBy { listOf(it.range, it.severity, it.message) }

        return AnalysisResult(
            diagnostics = diagnostics,
            symbolTable = symbolTables.root,
            globalSymbolTable = symbolTables.globals
        )
    }

    private fun toLegacyDiagnostic(diagnostic: io.github.dingyi222666.luaparser.semantic.api.Diagnostic): Diagnostic {
        return Diagnostic(
            range = diagnostic.range ?: emptyRange(),
            message = diagnostic.message,
            severity = when (diagnostic.severity) {
                io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity.ERROR -> Diagnostic.Severity.ERROR
                io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity.WARNING -> Diagnostic.Severity.WARNING
                io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity.INFO -> Diagnostic.Severity.INFO
            }
        )
    }

    private fun collectLegacyAssignmentDiagnostics(snapshot: SemanticPipelineSnapshot): List<Diagnostic> {
        val evaluator = ExpressionTypeEvaluator(snapshot.binder)
        val diagnostics = mutableListOf<Diagnostic>()

        visitStatements(snapshot.chunk.body) { statement ->
            when (statement) {
                is LocalStatement -> {
                    val hasClassTag = snapshot.comments.getAttachment(statement)
                        ?.docComment
                        ?.tags
                        .orEmpty()
                        .any { it is ClassTagSyntax }
                    statement.init.forEachIndexed { index, identifier ->
                        val declaration = snapshot.binder.declarationIndex.getDeclarations(identifier).lastOrNull() ?: return@forEachIndexed
                        val expectedType = declaration.declaredType ?: return@forEachIndexed
                        val value = statement.variables.getOrNull(index) ?: return@forEachIndexed
                        if (hasClassTag) {
                            return@forEachIndexed
                        }
                        collectTypeMismatchDiagnostic(snapshot, evaluator, value, expectedType, statement.range)?.let(diagnostics::add)
                    }
                }

                is AssignmentStatement -> {
                    val hasClassTag = snapshot.comments.getAttachment(statement)
                        ?.docComment
                        ?.tags
                        .orEmpty()
                        .any { it is ClassTagSyntax }
                    statement.init.forEachIndexed { index, target ->
                        val identifier = target as? Identifier ?: return@forEachIndexed
                        val value = statement.variables.getOrNull(index) ?: return@forEachIndexed
                        val expectedType = resolveAssignmentDeclaredType(snapshot, statement, identifier) ?: return@forEachIndexed
                        if (hasClassTag) {
                            return@forEachIndexed
                        }
                        collectTypeMismatchDiagnostic(snapshot, evaluator, value, expectedType, statement.range)?.let(diagnostics::add)
                    }
                }

                else -> Unit
            }
        }

        return diagnostics
    }

    private fun collectTypeMismatchDiagnostic(
        snapshot: SemanticPipelineSnapshot,
        evaluator: ExpressionTypeEvaluator,
        value: ExpressionNode,
        expectedType: ModelType,
        range: Range
    ): Diagnostic? {
        val actualType = resolveLegacyExpressionType(snapshot, evaluator, value)
        if (expectedType.isAssignableFrom(actualType)) {
            return null
        }

        val legacyExpected = expectedType.toLegacyType()
        val legacyActual = actualType.toLegacyType()
        return Diagnostic(
            range = range,
            message = "Type '${legacyActual.name}' is not assignable to type '${legacyExpected.name}'",
            severity = Diagnostic.Severity.ERROR
        )
    }

    private fun emptyRange(): Range = Range(
        start = io.github.dingyi222666.luaparser.parser.ast.node.Position(1, 1),
        end = io.github.dingyi222666.luaparser.parser.ast.node.Position(1, 1)
    )

    private fun resolveLegacyExpressionType(
        snapshot: SemanticPipelineSnapshot,
        evaluator: ExpressionTypeEvaluator,
        expression: ExpressionNode
    ): ModelType {
        val identifier = expression as? Identifier ?: return evaluator.evaluate(expression)
        val declaration = visibleValueDeclaration(snapshot, identifier.name, identifier.range.start)
        return declaration?.let { classTaggedDeclarationType(snapshot, it) ?: it.declaredType } ?: evaluator.evaluate(expression)
    }
}

private data class LegacySymbolTables(
    val root: SymbolTable,
    val globals: GlobalSymbolTable
)

private class LegacySymbolTableBuilder(
    private val snapshot: SemanticPipelineSnapshot
) {
    private val tableByScopeId = linkedMapOf<ScopeId, SymbolTable>()
    private val evaluator = ExpressionTypeEvaluator(snapshot.binder)

    fun build(): LegacySymbolTables {
        val rootScope = snapshot.binder.scopeGraph.rootScope
        val rootTable = SymbolTable(
            parent = null,
            range = rootScope.range,
            kind = rootScope.kind.toLegacyScopeKind(),
            owner = rootScope.ownerNode
        )
        tableByScopeId[rootScope.id] = rootTable
        addScopeDeclarations(rootScope.id, rootTable)
        snapshot.binder.scopeGraph.getChildren(rootScope.id).forEach { child ->
            buildScopeTree(child.id, rootTable)
        }
        val globalSymbolTable = GlobalSymbolTable().also { it.setRoot(rootTable) }

        snapshot.binder.declarationIndex.declarations.forEach { declaration ->
            if (declaration.kind == DeclarationKind.GLOBAL) {
                globalSymbolTable.defineGlobal(
                    name = declaration.name,
                    type = declaration.declaredType?.toLegacyType() ?: UnknownType,
                    kind = declaration.toLegacySymbolKind(),
                    range = declaration.range
                )
            }
        }

        collectSyntheticGlobalAssignments(snapshot).forEach { symbol ->
            globalSymbolTable.defineGlobal(symbol.name, symbol.type, symbol.kind, symbol.range)
        }

        return LegacySymbolTables(root = rootTable, globals = globalSymbolTable)
    }

    private fun buildScopeTree(scopeId: ScopeId, parentTable: SymbolTable): SymbolTable {
        tableByScopeId[scopeId]?.let { return it }
        val scope = requireNotNull(snapshot.binder.scopeGraph.getScope(scopeId))
        val table = parentTable.createChild(
            range = scope.range,
            kind = scope.kind.toLegacyScopeKind(),
            owner = scope.ownerNode
        )
        tableByScopeId[scopeId] = table
        addScopeDeclarations(scopeId, table)

        snapshot.binder.scopeGraph.getChildren(scopeId).forEach { child ->
            buildScopeTree(child.id, table)
        }

        return table
    }

    private fun addScopeDeclarations(scopeId: ScopeId, table: SymbolTable) {
        val scope = requireNotNull(snapshot.binder.scopeGraph.getScope(scopeId))
        scope.declarationIds.forEach { declarationId ->
            val declaration = snapshot.binder.declarationIndex.getDeclaration(declarationId) ?: return@forEach
            if (!declaration.belongsInLegacyScopeTable()) {
                return@forEach
            }

            table.define(
                name = declaration.name,
                type = resolveLegacyDeclarationType(declaration),
                kind = declaration.toLegacySymbolKind(),
                range = declaration.range,
                declaration = declaration.anchorNode
            )
        }
    }

    private fun resolveLegacyDeclarationType(declaration: BinderDeclaration): io.github.dingyi222666.luaparser.semantic.types.Type {
        classTaggedDeclarationType(snapshot, declaration)?.let { return it.toLegacyType() }
        declaration.declaredType?.let { return it.toLegacyType() }

        if (declaration.kind == DeclarationKind.LOCAL) {
            val identifier = declaration.anchorNode as? Identifier
            val localStatement = identifier?.parent as? LocalStatement
            if (identifier != null && localStatement != null) {
                val index = localStatement.init.indexOfFirst { it === identifier }
                val value = localStatement.variables.getOrNull(index)
                if (value != null) {
                    return evaluator.evaluate(value).toLegacyType()
                }
            }
        }

        return UnknownType
    }
}

private fun collectSyntheticGlobalAssignments(snapshot: SemanticPipelineSnapshot): List<Symbol> {
    val evaluator = ExpressionTypeEvaluator(snapshot.binder)
    val globals = snapshot.workspaceContext.importedSymbols
        .values
        .map { imported ->
            Symbol(
                name = imported.alias,
                type = imported.moduleType.toLegacyType(),
                kind = Symbol.Kind.MODULE,
                range = null,
                declaration = null
            )
        }
        .toMutableList()

    visitStatements(snapshot.chunk.body) { statement ->
        if (statement !is AssignmentStatement) {
            return@visitStatements
        }

        statement.init.forEachIndexed { index, target ->
            val identifier = target as? Identifier ?: return@forEachIndexed
            if (identifier.isLocal) {
                return@forEachIndexed
            }

            val value = statement.variables.getOrNull(index) ?: return@forEachIndexed
            val declaredType = resolveAssignmentDeclaredType(snapshot, statement, identifier)
            val legacyType = (declaredType ?: evaluator.evaluate(value)).toLegacyType()
            globals += Symbol(
                name = identifier.name,
                type = legacyType,
                kind = if (legacyType is io.github.dingyi222666.luaparser.semantic.types.CallableType) {
                    Symbol.Kind.FUNCTION
                } else {
                    Symbol.Kind.VARIABLE
                },
                range = identifier.range,
                declaration = statement
            )
        }
    }

    return globals
}

private fun BinderDeclaration.belongsInLegacyScopeTable(): Boolean {
    return kind == DeclarationKind.LOCAL ||
        kind == DeclarationKind.FUNCTION ||
        kind == DeclarationKind.PARAMETER ||
        kind == DeclarationKind.MODULE
}

private fun resolveAssignmentDeclaredType(
    snapshot: SemanticPipelineSnapshot,
    statement: AssignmentStatement,
    identifier: Identifier
): ModelType? {
    val attachment = snapshot.comments.getAttachment(statement)
    val classTag = attachment?.docComment?.tags.orEmpty().filterIsInstance<ClassTagSyntax>().lastOrNull()
    if (classTag != null) {
        return snapshot.binder.declarationIndex.declarations
            .lastOrNull { it.kind == DeclarationKind.CLASS && it.name == classTag.name }
            ?.declaredType
    }

    val inlineTypeText = attachment?.inlineTypeText?.trim().orEmpty()
    if (inlineTypeText.isNotEmpty()) {
        resolveSimpleInlineType(snapshot, inlineTypeText)?.let { return it }
    }

    return null
}

private fun resolveSimpleInlineType(snapshot: SemanticPipelineSnapshot, text: String): ModelType? {
    return when (text) {
        "nil", "void" -> io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType.NIL
        "boolean", "bool" -> io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType.BOOLEAN
        "number", "integer" -> io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType.NUMBER
        "string" -> io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType.STRING
        "thread" -> io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType.THREAD
        "userdata" -> io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType.USERDATA
        "any" -> io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType.ANY
        "unknown" -> io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
        "never" -> io.github.dingyi222666.luaparser.semantic.types.model.NeverType
        else -> snapshot.binder.declarationIndex.declarations
            .lastOrNull { declaration ->
                declaration.name == text && declaration.kind in setOf(DeclarationKind.CLASS, DeclarationKind.TYPE_ALIAS, DeclarationKind.TYPE_PARAMETER)
            }
            ?.declaredType
    }
}

private fun visibleValueDeclaration(
    snapshot: SemanticPipelineSnapshot,
    name: String,
    position: Position
): BinderDeclaration? {
    var scope = snapshot.binder.positionQueries.getScopeAt(position)
    while (scope != null) {
        val declaration = scope.declarationIds
            .asReversed()
            .mapNotNull(snapshot.binder.declarationIndex::getDeclaration)
            .firstOrNull { candidate ->
                candidate.kind.namespace == DeclarationNamespace.VALUE &&
                    candidate.name == name &&
                    candidate.range?.start?.let { comparePositions(it, position) <= 0 } != false
            }
        if (declaration != null) {
            return declaration
        }
        scope = scope.parentId?.let(snapshot.binder.scopeGraph::getScope)
    }
    return null
}

private fun classTaggedDeclarationType(
    snapshot: SemanticPipelineSnapshot,
    declaration: BinderDeclaration
): ModelType? {
    if (declaration.kind != DeclarationKind.LOCAL) {
        return null
    }

    val statement = (declaration.anchorNode as? Identifier)?.parent as? LocalStatement ?: return null
    val classTag = snapshot.comments.getAttachment(statement)
        ?.docComment
        ?.tags
        .orEmpty()
        .filterIsInstance<ClassTagSyntax>()
        .lastOrNull()
        ?: return null

    return snapshot.binder.declarationIndex.declarations
        .lastOrNull { it.kind == DeclarationKind.CLASS && it.name == classTag.name }
        ?.declaredType
}

private fun comparePositions(left: Position, right: Position): Int {
    val lineComparison = left.line.compareTo(right.line)
    if (lineComparison != 0) {
        return lineComparison
    }
    return left.column.compareTo(right.column)
}

private fun BinderDeclaration.toLegacySymbolKind(): Symbol.Kind {
    if (kind == DeclarationKind.LOCAL && declaredType is io.github.dingyi222666.luaparser.semantic.types.model.ClassType) {
        return Symbol.Kind.CLASS
    }

    return when (kind) {
        DeclarationKind.LOCAL -> Symbol.Kind.LOCAL
        DeclarationKind.GLOBAL -> if (declaredType is io.github.dingyi222666.luaparser.semantic.types.model.FunctionType || declaredType is io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType) {
            Symbol.Kind.FUNCTION
        } else {
            Symbol.Kind.VARIABLE
        }
        DeclarationKind.FUNCTION -> Symbol.Kind.FUNCTION
        DeclarationKind.MODULE -> Symbol.Kind.MODULE
        DeclarationKind.PARAMETER -> Symbol.Kind.PARAMETER
        DeclarationKind.CLASS -> Symbol.Kind.CLASS
        DeclarationKind.TYPE_ALIAS -> Symbol.Kind.TYPE_ALIAS
        DeclarationKind.TYPE_PARAMETER -> Symbol.Kind.TYPE_ALIAS
        DeclarationKind.FIELD -> Symbol.Kind.FIELD
        DeclarationKind.METHOD -> Symbol.Kind.METHOD
    }
}

private fun io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.toLegacyScopeKind(): SymbolTable.ScopeKind {
    return when (this) {
        io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.CHUNK -> SymbolTable.ScopeKind.CHUNK
        io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.BLOCK -> SymbolTable.ScopeKind.BLOCK
        io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.FUNCTION -> SymbolTable.ScopeKind.FUNCTION
        io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.MODULE -> SymbolTable.ScopeKind.MODULE
        io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.LOOP -> SymbolTable.ScopeKind.LOOP
        io.github.dingyi222666.luaparser.semantic.binder.ScopeKind.CONDITIONAL -> SymbolTable.ScopeKind.CONDITIONAL
    }
}

private fun visitStatements(block: BlockNode, visitor: (StatementNode) -> Unit) {
    block.statements.forEach { statement ->
        if (statement is CommentStatement) {
            return@forEach
        }
        visitor(statement)
        when (statement) {
            is DoStatement -> visitStatements(statement.body, visitor)
            is FunctionDeclaration -> statement.body?.let { visitStatements(it, visitor) }
            is IfStatement -> statement.causes.forEach { clause -> visitClause(clause, visitor) }
            is WhileStatement -> visitStatements(statement.body, visitor)
            is RepeatStatement -> visitStatements(statement.body, visitor)
            is ForNumericStatement -> visitStatements(statement.body, visitor)
            is ForGenericStatement -> visitStatements(statement.body, visitor)
            is SwitchStatement -> statement.causes.forEach { cause ->
                when (cause) {
                    is io.github.dingyi222666.luaparser.parser.ast.node.CaseCause -> visitStatements(cause.body, visitor)
                    is io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause -> visitStatements(cause.body, visitor)
                }
            }
            is WhenStatement -> {
                visitNestedStatement(statement.ifCause, visitor)
                statement.elseCause?.let { visitNestedStatement(it, visitor) }
            }
            else -> Unit
        }
    }
}

private fun visitNestedStatement(statement: StatementNode, visitor: (StatementNode) -> Unit) {
    when (statement) {
        is IfClause -> visitClause(statement, visitor)
        is DoStatement -> visitStatements(statement.body, visitor)
        is FunctionDeclaration -> statement.body?.let { visitStatements(it, visitor) }
        is IfStatement -> statement.causes.forEach { clause -> visitClause(clause, visitor) }
        is WhileStatement -> visitStatements(statement.body, visitor)
        is RepeatStatement -> visitStatements(statement.body, visitor)
        is ForNumericStatement -> visitStatements(statement.body, visitor)
        is ForGenericStatement -> visitStatements(statement.body, visitor)
        is SwitchStatement -> statement.causes.forEach { cause ->
            when (cause) {
                is io.github.dingyi222666.luaparser.parser.ast.node.CaseCause -> visitStatements(cause.body, visitor)
                is io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause -> visitStatements(cause.body, visitor)
            }
        }
        is WhenStatement -> {
            visitNestedStatement(statement.ifCause, visitor)
            statement.elseCause?.let { visitNestedStatement(it, visitor) }
        }
        else -> Unit
    }
}

private fun visitClause(clause: IfClause, visitor: (StatementNode) -> Unit) {
    when (clause) {
        is ElseClause -> visitStatements(clause.body, visitor)
        is ElseIfClause -> visitStatements(clause.body, visitor)
        else -> visitStatements(clause.body, visitor)
    }
}
