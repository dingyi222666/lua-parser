package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement

class BinderPositionQueries(
    private val scopeGraph: ScopeGraph,
    private val declarationIndex: DeclarationIndex
) {

    private val scopeDepths: Map<ScopeId, Int> = buildScopeDepths(scopeGraph)
    private val declarationOrders: Map<DeclarationId, Int> = declarationIndex.declarations
        .mapIndexed { index, declaration -> declaration.id to index }
        .toMap()

    private val scopeIndex = PositionRangeIndex(
        scopeGraph.scopes.mapIndexed { index, scope ->
            PositionRangeIndex.Entry(
                range = scope.range,
                payload = scope,
                stableOrder = index
            )
        }
    )

    private val declarationIndexByPosition = PositionRangeIndex(
        declarationIndex.declarations.mapIndexed { index, declaration ->
            PositionRangeIndex.Entry(
                range = declaration.range,
                payload = declaration,
                stableOrder = index
            )
        }
    )

    fun getScopeAt(position: Position): Scope? {
        return scopeIndex.query(position)
            .sortedWith(::compareScopesAtPosition)
            .firstOrNull()
    }

    /**
     * Scope-chain entry for free-position queries (completion enumeration, bare-name
     * resolution, import shadowing).
     *
     * Tail-of-body gap: a for/while statement's LOOP scope is created with exactly
     * `body.range` (header expressions must evaluate in the ENCLOSING scope — see
     * DeclarationBinder), and block ranges end at the last committed token, so a query
     * position between the last body statement and the statement's `end` token sits OUTSIDE
     * the loop scope's range. getScopeAt therefore resolves it to the enclosing scope and the
     * loop's control variables / body locals vanish from the surface even though the block is
     * still open. When the position falls inside a numeric/generic for or while statement's
     * full range but at or after its body's end, surface that loop scope instead: it holds
     * the control variables and body locals, and its parent chain still reaches every
     * enclosing scope. Header positions (before the body) keep enclosing-scope semantics —
     * Lua header expressions must not see the freshly declared control variables.
     *
     * Every free-position surface (ReferenceQueries, the workspace facade's local
     * name/declaration walks, the legacy adapters) must resolve through this helper so a
     * tail caret behaves identically everywhere. Node-anchored lookups (hover/type on REAL
     * nodes) keep using getScopeAt directly.
     */
    internal fun scopeForFreePositionQuery(position: Position): Scope? {
        return loopBodyTailScopeAt(position) ?: getScopeAt(position)
    }

    /**
     * The innermost LOOP scope whose loop statement spans [position] while its body ends at or
     * before it — i.e. the caret sits in the statement's tail gap. Restricted to
     * ForNumericStatement/ForGenericStatement/WhileStatement owners: their LOOP scopes are
     * built on `body.range` (end-exclusive), so tail carets fall to the enclosing scope
     * without this promotion. Repeat keeps `node.range` (until-condition included) and is
     * excluded — its tail genuinely resolves through getScopeAt.
     */
    private val forLoopBodyScopes: List<Scope> by lazy {
        scopeGraph.scopes.filter { scope ->
            scope.kind == ScopeKind.LOOP && scope.ownerNode is BlockNode
        }
    }

    private fun loopBodyTailScopeAt(position: Position): Scope? {
        var innermost: Scope? = null
        forLoopBodyScopes.forEach { scope ->
            // `forLoopBodyScopes` only yields LOOP scopes whose ownerNode is a BlockNode.
            val body = scope.ownerNode as BlockNode
            // `parent` is a not-null delegate that can still throw on synthetic/detached
            // trees; treat those as non-candidates like CompletionProvider does.
            val statement = runCatching { body.parent }.getOrNull() ?: return@forEach
            if (
                statement !is ForNumericStatement &&
                statement !is ForGenericStatement &&
                statement !is WhileStatement
            ) {
                return@forEach
            }
            // Tail only: inside the statement's full range (end-exclusive) but at/after the
            // body's end-exclusive end. Header positions stay in the enclosing scope so
            // `for i = 1, #i do` keeps resolving the OUTER `i`.
            if (!isPositionWithin(statement.range.start, statement.range.end, position)) {
                return@forEach
            }
            if (comparePositions(body.range.end, position) > 0) {
                return@forEach
            }
            val current = innermost
            if (current == null || comparePositions(current.range.start, scope.range.start) < 0) {
                innermost = scope
            }
        }
        return innermost
    }

    fun getDeclarationsAt(position: Position): List<BinderDeclaration> {
        return declarationIndexByPosition.query(position)
            .sortedWith { a, b -> compareDeclarationsAtPosition(a, b, position) }
    }

    fun getDeclarationAt(position: Position): BinderDeclaration? {
        val matches = getDeclarationsAt(position)
        val best = matches.firstOrNull() ?: return null
        val second = matches.getOrNull(1) ?: return best
        return if (compareDeclarationPrecedence(best, second, position) == 0) {
            null
        } else {
            best
        }
    }

    fun getSymbolAt(position: Position): BinderSymbol? {
        val symbolId = getDeclarationAt(position)?.symbolId ?: return null
        return declarationIndex.getSymbol(symbolId)
    }

    private fun compareScopesAtPosition(a: Scope, b: Scope): Int {
        val specificity = compareRangeSpecificity(a.range, b.range)
        if (specificity != 0) {
            return specificity
        }

        val depthComparison = scopeDepths.getValue(b.id).compareTo(scopeDepths.getValue(a.id))
        if (depthComparison != 0) {
            return depthComparison
        }

        return b.id.value.compareTo(a.id.value)
    }

    private fun compareDeclarationsAtPosition(
        a: BinderDeclaration,
        b: BinderDeclaration,
        position: Position
    ): Int {
        val precedence = compareDeclarationPrecedence(a, b, position)
        if (precedence != 0) {
            return precedence
        }

        return declarationOrders.getValue(a.id).compareTo(declarationOrders.getValue(b.id))
    }

    private fun compareDeclarationPrecedence(
        a: BinderDeclaration,
        b: BinderDeclaration,
        position: Position
    ): Int {
        val aAnchorContains = a.anchorNode?.range?.let { rangeContains(it, position) } == true
        val bAnchorContains = b.anchorNode?.range?.let { rangeContains(it, position) } == true
        if (aAnchorContains != bAnchorContains) {
            return if (aAnchorContains) -1 else 1
        }

        val aRange = a.range ?: return if (b.range == null) 0 else 1
        val bRange = b.range ?: return -1

        return compareRangeSpecificity(aRange, bRange)
    }

    private fun buildScopeDepths(scopeGraph: ScopeGraph): Map<ScopeId, Int> {
        val depths = mutableMapOf<ScopeId, Int>()

        fun visit(scope: Scope, depth: Int) {
            depths[scope.id] = depth
            scopeGraph.getChildren(scope.id).forEach { child -> visit(child, depth + 1) }
        }

        visit(scopeGraph.rootScope, 0)

        scopeGraph.scopes.forEach { scope ->
            if (scope.id !in depths) {
                val parentDepth = scope.parentId?.let(depths::get)?.plus(1) ?: 0
                depths[scope.id] = parentDepth
            }
        }

        return depths
    }
}
