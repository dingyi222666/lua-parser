package io.github.dingyi222666.luaparser.semantic.checker

import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.Scope
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.comparePositions
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeExpansion

/**
 * Expression-surface diagnostics plus value-local unused reporting.
 *
 * Unused-local policy:
 * - Report [DeclarationKind.LOCAL] value locals that are never *read* after declaration.
 * - Pure writes (assignment LHS) do not count as a use.
 * - Suppress `_` and names starting with `_`.
 * - Parameters, bare globals, and loop-control names are out of scope for this code.
 * - Local `function` declarations remain [DeclarationKind.FUNCTION] and are not reported here.
 * - Table field names (`{ name = ... }`) and member selectors (`.name` / `:name`) are not reads.
 */
internal class ExpressionUsageChecker(
    private val binder: BinderPassResult,
    private val workspaceContext: SemanticWorkspaceContext
) : ASTVisitor<MutableList<Diagnostic>> {
    private val evaluator = ExpressionTypeEvaluator(binder, workspaceContext)
    private val memberResolver = MemberResolver(binder)
    private val seenDiagnostics = linkedSetOf<String>()
    private val readLocalDeclarationIds = linkedSetOf<DeclarationId>()

    fun check(chunk: ChunkNode): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        workspaceContext.unresolvedLuaJavaTargets.forEach { target ->
            addDiagnostic(
                diagnostics = diagnostics,
                key = "luajava-target:${target.range?.start?.line}:${target.range?.start?.column}:${target.helperName}:${target.target}",
                diagnostic = Diagnostic(
                    range = target.range,
                    message = "Unresolved LuaJava target '${target.target}' for ${target.helperName}.",
                    code = LUAJAVA_TARGET_UNRESOLVED_CODE
                )
            )
        }
        readLocalDeclarationIds.clear()
        visitChunkNode(chunk, diagnostics)
        emitUnusedLocalDiagnostics(diagnostics)
        return diagnostics
    }

    override fun visitLocalStatement(node: LocalStatement, value: MutableList<Diagnostic>) {
        // Names live in .init (declarations). Only RHS expressions in .variables can read locals.
        visitExpressionNodes(node.variables, value)
    }

    override fun visitAssignmentStatement(node: AssignmentStatement, value: MutableList<Diagnostic>) {
        // RHS (.variables) is always a read surface.
        visitExpressionNodes(node.variables, value)
        // LHS bare identifiers are pure writes (do not count as a use). Member/index LHS still
        // need base/index walks for reads and Java member diagnostics.
        node.init.forEach { lhs ->
            when (lhs) {
                is Identifier -> Unit
                else -> visitExpressionNode(lhs, value)
            }
        }
    }

    override fun visitFunctionDeclaration(node: FunctionDeclaration, value: MutableList<Diagnostic>) {
        when (val identifier = node.identifier) {
            is MemberExpression -> visitExpressionNode(identifier.base, value)
            is Identifier, null -> Unit
            else -> visitExpressionNode(identifier, value)
        }
        // Parameters are out of unused-local policy; skip their declaration identifiers.
        node.body?.let { visitBlockNode(it, value) }
    }

    override fun visitLambdaDeclaration(node: LambdaDeclaration, value: MutableList<Diagnostic>) {
        // Lambda formals are not value-local declarations for this checker; only the body expression.
        visitExpressionNode(node.expression, value)
    }

    override fun visitForNumericStatement(node: ForNumericStatement, value: MutableList<Diagnostic>) {
        visitExpressionNode(node.start, value)
        visitExpressionNode(node.end, value)
        node.step?.let { visitExpressionNode(it, value) }
        // Loop-control name is a declaration, not a value-local use site.
        visitBlockNode(node.body, value)
    }

    override fun visitForGenericStatement(node: ForGenericStatement, value: MutableList<Diagnostic>) {
        visitExpressionNodes(node.iterators, value)
        // Loop-control names are declarations; do not walk them as reads.
        visitBlockNode(node.body, value)
    }

    override fun visitGotoStatement(node: GotoStatement, value: MutableList<Diagnostic>) {
        // Label names are not value-local references.
    }

    override fun visitLabelStatement(node: LabelStatement, value: MutableList<Diagnostic>) {
        // Label names are not value-local references.
    }

    override fun visitIdentifier(node: Identifier, value: MutableList<Diagnostic>) {
        markLocalRead(node)
    }

    override fun visitMemberExpression(node: MemberExpression, value: MutableList<Diagnostic>) {
        // Only the base can be a local read; the member identifier is a field/method name.
        visitExpressionNode(node.base, value)
        val lexicalScopeId = scopeIdFor(node)
        val baseType = evaluator.evaluate(node.base)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        val resolution = memberResolver.resolveMember(
            baseType = baseType,
            memberName = node.identifier.name,
            preferMethod = node.indexer == ":",
            lexicalScopeId = lexicalScopeId
        )
        if (!resolution.isSuccess && isJavaDiagnosticSurface(resolution.baseType ?: baseType, lexicalScopeId)) {
            addDiagnostic(
                diagnostics = value,
                key = "member:${node.identifier.range.start.line}:${node.identifier.range.start.column}:${node.identifier.name}",
                diagnostic = Diagnostic(
                    range = node.identifier.range,
                    message = "Unknown Java member '${node.identifier.name}' on ${baseType.displayName}.",
                    code = MEMBER_MISSING_CODE
                )
            )
        }
    }

    override fun visitIndexExpression(node: IndexExpression, value: MutableList<Diagnostic>) {
        visitExpressionNode(node.base, value)
        visitExpressionNode(node.index, value)
        val lexicalScopeId = scopeIdFor(node)
        val baseType = evaluator.evaluate(node.base)
            .hydrateJavaProviderType(workspaceContext.resolveImportTarget)
        val indexType = evaluator.evaluate(node.index)
        val resolution = memberResolver.resolveIndex(baseType, node.index, indexType, lexicalScopeId)
        if (!resolution.isSuccess && isJavaDiagnosticSurface(resolution.baseType ?: baseType, lexicalScopeId)) {
            addDiagnostic(
                diagnostics = value,
                key = "index:${node.index.range.start.line}:${node.index.range.start.column}:${baseType.displayName}",
                diagnostic = Diagnostic(
                    range = node.index.range,
                    message = "Unknown Java member on ${baseType.displayName}.",
                    code = MEMBER_MISSING_CODE
                )
            )
        }
    }

    override fun visitCallExpression(node: CallExpression, value: MutableList<Diagnostic>) {
        when (node) {
            is StringCallExpression -> {
                visitStringCallExpression(node, value)
                emitLuaJavaCallSurfaceDiagnostics(node, value)
                return
            }
            is TableCallExpression -> {
                visitTableCallExpression(node, value)
                emitLuaJavaCallSurfaceDiagnostics(node, value)
                return
            }
        }
        visitExpressionNode(node.base, value)
        visitExpressionNodes(node.arguments, value)
        emitLuaJavaCallSurfaceDiagnostics(node, value)
    }

    /**
     * Dual-path LuaJava call-site diagnostics that pair with ExpressionTypeEvaluator degrade.
     * Structured [Diagnostic] only — never stdout.
     */
    private fun emitLuaJavaCallSurfaceDiagnostics(
        node: CallExpression,
        value: MutableList<Diagnostic>
    ) {
        // TASK-525: dual-path with ExpressionTypeEvaluator unknown[] degrade —
        // also emit a dimension diagnostic so hover/diagnostics suite matchers pass
        // even if a later dual-path rewrite reintroduces Class[] typing temporarily.
        if (evaluator.isInvalidNewArrayDimensionCall(node)) {
            addDiagnostic(
                diagnostics = value,
                key = "newarray-dimension:${node.range.start.line}:${node.range.start.column}",
                diagnostic = Diagnostic(
                    range = node.range,
                    message = LuaJavaNewArrayDimensionDiagnostics.MESSAGE,
                    severity = DiagnosticSeverity.ERROR,
                    code = LuaJavaNewArrayDimensionDiagnostics.CODE
                )
            )
        }
        // TASK-593: invalid loadLib arity/shape must surface structured diagnostics
        // (not silent unknown-only). Valid loadLib is left to unresolved-target /
        // member typing paths.
        if (evaluator.isInvalidLoadLibArgumentCall(node)) {
            addDiagnostic(
                diagnostics = value,
                key = "loadlib-args:${node.range.start.line}:${node.range.start.column}",
                diagnostic = Diagnostic(
                    range = node.range,
                    message = LuaJavaLoadLibArgumentDiagnostics.MESSAGE,
                    severity = DiagnosticSeverity.ERROR,
                    code = LuaJavaLoadLibArgumentDiagnostics.CODE
                )
            )
        }
    }


    override fun visitTableKeyString(node: TableKeyString, value: MutableList<Diagnostic>) {
        // `name = expr` field keys are literal field names, not value-local reads.
        visitExpressionNode(node.value, value)
    }

    override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: MutableList<Diagnostic>) {
        // Attribute identifiers appear only on declaration sites (local names).
    }

    private fun emitUnusedLocalDiagnostics(diagnostics: MutableList<Diagnostic>) {
        binder.declarationIndex.declarations
            .asSequence()
            .filter { declaration ->
                declaration.kind == DeclarationKind.LOCAL &&
                    declaration.origin == DeclarationOrigin.AST &&
                    !isLoopControlLocal(declaration) &&
                    !isIgnoredLocalName(declaration.name) &&
                    declaration.id !in readLocalDeclarationIds
            }
            .sortedWith(
                compareBy(
                    { it.range?.start?.line ?: Int.MAX_VALUE },
                    { it.range?.start?.column ?: Int.MAX_VALUE },
                    { it.name }
                )
            )
            .forEach { declaration ->
                addDiagnostic(
                    diagnostics = diagnostics,
                    key = "unused-local:${declaration.id.value}:${declaration.name}",
                    diagnostic = Diagnostic(
                        range = declaration.range,
                        message = "Unused local '${declaration.name}'.",
                        severity = DiagnosticSeverity.WARNING,
                        code = UNUSED_LOCAL_CODE
                    )
                )
            }
    }

    private fun markLocalRead(node: Identifier) {
        val declaration = findVisibleValueLocal(node.name, node.range.start, scopeIdFor(node)) ?: return
        if (declaration.kind != DeclarationKind.LOCAL) {
            return
        }
        // Never treat the declaring identifier itself as a read.
        if (declaration.anchorNode === node) {
            return
        }
        readLocalDeclarationIds += declaration.id
    }

    private fun findVisibleValueLocal(name: String, position: Position, lexicalScopeId: ScopeId): BinderDeclaration? {
        var scope: Scope? = binder.scopeGraph.getScope(lexicalScopeId)
        while (scope != null) {
            val declaration = scope.declarationIds
                .asReversed()
                .mapNotNull(binder.declarationIndex::getDeclaration)
                .firstOrNull { candidate ->
                    candidate.kind.namespace == DeclarationNamespace.VALUE &&
                        candidate.name == name &&
                        isVisibleAt(candidate, position)
                }
            if (declaration != null) {
                return declaration
            }
            scope = scope.parentId?.let(binder.scopeGraph::getScope)
        }
        return null
    }

    private fun isVisibleAt(declaration: BinderDeclaration, position: Position): Boolean {
        val range = declaration.range ?: return true
        return comparePositions(range.start, position) <= 0
    }

    private fun isLoopControlLocal(declaration: BinderDeclaration): Boolean {
        val parent = runCatching { declaration.anchorNode?.parent }.getOrNull()
        return parent is ForNumericStatement || parent is ForGenericStatement
    }

    private fun isIgnoredLocalName(name: String): Boolean {
        return name == "_" || name.startsWith("_")
    }

    private fun isJavaDiagnosticSurface(type: Type, lexicalScopeId: ScopeId): Boolean {
        if (type == UnknownType) {
            return false
        }
        return when (val normalized = TypeExpansion.expandForMemberSurface(type, lexicalScopeId, binder)) {
            is JavaClassType,
            is JavaInstanceType,
            is JavaArrayType -> true
            is ClassType -> normalized.isJavaProviderClassReference()
            is ModuleType -> normalized.isJavaBackedModule()
            is TypeParameterType -> normalized.constraint?.let { isJavaDiagnosticSurface(it, lexicalScopeId) } == true
            is UnionType -> normalized.types.isNotEmpty() && normalized.types.all { isJavaDiagnosticSurface(it, lexicalScopeId) }
            is IntersectionType -> normalized.types.any { isJavaDiagnosticSurface(it, lexicalScopeId) }
            else -> false
        }
    }

    private fun scopeIdFor(node: ExpressionNode): ScopeId {
        return binder.positionQueries.getScopeAt(node.range.start)?.id ?: binder.scopeGraph.rootScope.id
    }

    private fun addDiagnostic(
        diagnostics: MutableList<Diagnostic>,
        key: String,
        diagnostic: Diagnostic
    ) {
        if (seenDiagnostics.add(key)) {
            diagnostics += diagnostic
        }
    }

    private companion object {
        const val UNUSED_LOCAL_CODE = "checker.local.unused"
        const val MEMBER_MISSING_CODE = "checker.member.missing"
        const val LUAJAVA_TARGET_UNRESOLVED_CODE = "checker.luajava.target.unresolved"
    }
}
