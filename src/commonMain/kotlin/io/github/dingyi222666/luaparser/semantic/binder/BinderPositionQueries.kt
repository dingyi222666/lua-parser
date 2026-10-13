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

    // Build a scope's name index on first use. Eagerly grouping every declaration
    // made the first Android completion walk the entire builtin/binder surface.
    private val valuesByScopeAndName = scopeGraph.scopes.associate { scope ->
        scope.id to lazy {
            scope.declarationIds.asReversed().asSequence()
                .mapNotNull(declarationIndex::getDeclaration)
                .filter { it.kind.namespace == DeclarationNamespace.VALUE }
                .groupBy(BinderDeclaration::name)
        }
    }

    internal fun valueDeclarationsInScope(scopeId: ScopeId, name: String): List<BinderDeclaration> {
        return valuesByScopeAndName[scopeId]?.value?.get(name).orEmpty()
    }

    private val scopeDepths by lazy { buildScopeDepths(scopeGraph) }
    private val declarationOrders by lazy {
        declarationIndex.declarations.mapIndexed { index, declaration -> declaration.id to index }.toMap()
    }

    private val scopeIndex by lazy {
        PositionRangeIndex(scopeGraph.scopes.mapIndexed { index, scope ->
            PositionRangeIndex.Entry(scope.range, scope, index)
        })
    }

    private val declarationIndexByPosition by lazy {
        PositionRangeIndex(declarationIndex.declarations.mapIndexed { index, declaration ->
            PositionRangeIndex.Entry(declaration.range, declaration, index)
        })
    }

    fun getScopeAt(position: Position): Scope? = scopeIndex.query(position).minWithOrNull(::compareScopesAtPosition)

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

    private fun loopBodyTailScopeAt(position: Position): Scope? =
        forLoopBodyScopes.asSequence()
            .mapNotNull { scope -> tailGapCandidate(scope, position) }
            .maxWithOrNull(compareBy({ it.range.start.line }, { it.range.start.column }))

    /**
     * [scope] when [position] sits in its statement's tail gap, else `null`. Tail only:
     * inside the statement's full range (end-exclusive) but at/after the body's
     * end-exclusive end — header positions stay in the enclosing scope so
     * `for i = 1, #i do` keeps resolving the OUTER `i`. Ties keep the first-seen scope.
     */
    private fun tailGapCandidate(scope: Scope, position: Position): Scope? {
        // `forLoopBodyScopes` only yields LOOP scopes whose ownerNode is a BlockNode, and
        // `parent` is a not-null delegate that can still throw on synthetic/detached trees —
        // treat both as non-candidates like CompletionProvider does.
        val statement = runCatching { (scope.ownerNode as BlockNode).parent }.getOrNull()
            ?.takeIf { it is ForNumericStatement || it is ForGenericStatement || it is WhileStatement }
            ?: return null
        if (!isPositionWithin(statement.range.start, statement.range.end, position)) return null
        if (comparePositions((scope.ownerNode as BlockNode).range.end, position) > 0) return null
        return scope
    }

    fun getDeclarationsAt(position: Position): List<BinderDeclaration> =
        declarationIndexByPosition.query(position)
            .sortedWith { a, b -> compareDeclarationsAtPosition(a, b, position) }

    fun getDeclarationAt(position: Position): BinderDeclaration? {
        val matches = getDeclarationsAt(position)
        val best = matches.firstOrNull() ?: return null
        val second = matches.getOrNull(1) ?: return best
        // A tie on positional precedence is ambiguous (two equally-specific declarations):
        // resolve to nothing rather than an arbitrary winner.
        return best.takeUnless { compareDeclarationPrecedence(best, second, position) == 0 }
    }

    fun getSymbolAt(position: Position): BinderSymbol? {
        val symbolId = getDeclarationAt(position)?.symbolId ?: return null
        return declarationIndex.getSymbol(symbolId)
    }

    private fun compareScopesAtPosition(a: Scope, b: Scope): Int =
        compareRangeSpecificity(a.range, b.range).takeIf { it != 0 }
            ?: scopeDepths.getValue(b.id).compareTo(scopeDepths.getValue(a.id)).takeIf { it != 0 }
            ?: b.id.value.compareTo(a.id.value)

    private fun compareDeclarationsAtPosition(
        a: BinderDeclaration,
        b: BinderDeclaration,
        position: Position
    ): Int =
        compareDeclarationPrecedence(a, b, position).takeIf { it != 0 }
            ?: declarationOrders.getValue(a.id).compareTo(declarationOrders.getValue(b.id))

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

    /**
     * Every scope the binder creates is a child of the root scope chain (createScope always
     * parents to the pushed scope, and the root is pushed before any binding), so the tree
     * walk from the root covers all scopes and compareScopesAtPosition's `getValue` is total.
     */
    private fun buildScopeDepths(scopeGraph: ScopeGraph): Map<ScopeId, Int> = buildMap<ScopeId, Int> {
        fun visit(scope: Scope, depth: Int) {
            put(scope.id, depth)
            scopeGraph.getChildren(scope.id).forEach { child -> visit(child, depth + 1) }
        }

        visit(scopeGraph.rootScope, 0)
    }
}
