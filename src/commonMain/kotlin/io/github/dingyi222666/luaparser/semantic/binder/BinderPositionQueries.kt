package io.github.dingyi222666.luaparser.semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.Position

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
