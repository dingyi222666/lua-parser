package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.api.TypeInfoKind

internal class CompletionProvider(
    private val nodePositionIndex: NodePositionIndex,
    private val referenceQueries: ReferenceQueries,
    private val adapters: ApiAdapters
) {
    fun getCompletionsAt(position: Position): List<CompletionItem> {
        val node = nodePositionIndex.findInnermost(position)
        val memberExpression = resolveMemberExpression(node, position)

        return if (memberExpression != null) {
            memberCompletions(memberExpression)
        } else {
            lexicalCompletions(position, node)
        }
    }

    private fun resolveMemberExpression(node: BaseASTNode?, position: Position): MemberExpression? {
        memberExpressionFromNode(node, position)?.let { return it }

        // Fall back to enclosing nodes when parent links are missing or the innermost
        // hit is a broader expression covering the member access site.
        nodePositionIndex.findEnclosing(position)
            .asSequence()
            .mapNotNull { candidate -> memberExpressionFromNode(candidate, position) }
            .firstOrNull()
            ?.let { return it }

        // NodePositionIndex is half-open [start, end). Trailing-dot carets often sit exactly
        // at MemberExpression.range.end (`table.|` / `greeter.|`) and miss the member node.
        // Probe one column left so incomplete member access still uses the member surface.
        if (position.column > 1) {
            val left = Position(position.line, position.column - 1)
            val leftNode = nodePositionIndex.findInnermost(left)
            memberExpressionFromNode(leftNode, left)?.let { return it }
            nodePositionIndex.findEnclosing(left)
                .asSequence()
                .mapNotNull { candidate -> memberExpressionFromNode(candidate, left) }
                .firstOrNull()
                ?.let { return it }
        }

        if (node is Identifier) {
            val parent = runCatching { node.parent }.getOrNull() as? MemberExpression
            if (
                parent != null &&
                parent.base === node &&
                parent.identifier.name.isBlank() &&
                position.line == parent.range.end.line &&
                position.column >= parent.range.end.column
            ) {
                return parent
            }
        }
        return null
    }

    /**
     * Member completions apply when the caret is on the member name / after the indexer
     * (`base.|`, `base:set|`), not when the caret is still on the receiver identifier
     * of a completed access (`mess|ageText:setText` must stay lexical for the local).
     */
    private fun memberExpressionFromNode(node: BaseASTNode?, position: Position): MemberExpression? {
        return when (node) {
            is MemberExpression -> node.takeIf { isMemberCompletionSite(it, position) }
            is Identifier -> {
                val parent = runCatching { node.parent }.getOrNull() as? MemberExpression
                parent?.takeIf {
                    // Caret on member name, or incomplete `base.` / `base:` with blank member.
                    it.identifier === node ||
                        (it.base === node && it.identifier.name.isBlank())
                }?.takeIf { isMemberCompletionSite(it, position) }
            }
            else -> null
        }
    }

    private fun isMemberCompletionSite(expression: MemberExpression, position: Position): Boolean {
        if (expression.identifier.name.isBlank()) {
            return true
        }
        val baseEnd = expression.base.range.end
        val afterBase =
            position.line > baseEnd.line ||
                (position.line == baseEnd.line && position.column > baseEnd.column)
        if (!afterBase) {
            return false
        }
        val end = expression.range.end
        return position.line < end.line ||
            (position.line == end.line && position.column <= end.column + 1)
    }

    private fun lexicalCompletions(position: Position, node: BaseASTNode?): List<CompletionItem> {
        val visibleItems = referenceQueries.visibleValueDeclarations(position)
            .mapNotNull { visible ->
                adapters.toDeclarationSymbol(visible.declaration)?.let { symbol ->
                    // Prefer declaredType-backed detail/kind for ambient binder builtins
                    // (activity/service VARIABLE, load* FUNCTION, luajava MODULE).
                    completionItem(symbol, categoryPrefix(symbol), visible.lexicalDepth)
                }
            }
        val lazyImportItem = (node as? Identifier)
            ?.let { identifier -> referenceQueries.importedCompletionSymbol(identifier.name, position) }
            ?.let { symbol -> completionItem(symbol, categoryPrefix(symbol), 0) }
        return (visibleItems + listOfNotNull(lazyImportItem))
            .distinctBy(CompletionItem::label)
            .sortedBy(CompletionItem::sortText)
    }

    private fun memberCompletions(expression: MemberExpression): List<CompletionItem> {
        // Member surface includes conservative JavaBean property aliases as FIELD symbols
        // (from ReferenceQueries) alongside direct getter/setter METHOD members.
        return referenceQueries.resolveMemberCompletionSurface(expression)
            .mapIndexed { index, symbol ->
                val prefix = if (expression.indexer == ":") {
                    if (symbol.kind == SymbolKind.METHOD) "0" else "1"
                } else {
                    // Prefer fields (including Java static fields and JavaBean aliases on bindClass
                    // targets) over methods for `.` members.
                    if (symbol.kind == SymbolKind.FIELD || isMemberFieldLike(symbol)) "0" else "1"
                }
                memberCompletionItem(symbol, prefix, index)
            }
            .sortedBy(CompletionItem::sortText)
    }

    private fun completionItem(symbol: Symbol, category: String, depth: Int): CompletionItem {
        val detail = symbol.declaredType?.displayName ?: symbol.type?.displayName
        return CompletionItem(
            label = symbol.name,
            kind = lexicalCompletionKind(symbol),
            detail = detail,
            insertText = symbol.name,
            sortText = "$category:${depth.toString().padStart(4, '0')}:${symbol.name}"
        )
    }

    /**
     * Free-identifier completions should surface binder declaredType when present:
     * MODULE for luajava-like module globals, FUNCTION for load* / print-like callables,
     * VARIABLE for activity/service/this/context. Fall back to [ApiAdapters.completionKind].
     */
    private fun lexicalCompletionKind(symbol: Symbol): CompletionItemKind {
        val declared = symbol.declaredType
        when (declared?.kind) {
            TypeInfoKind.MODULE -> return CompletionItemKind.MODULE
            TypeInfoKind.FUNCTION -> return CompletionItemKind.FUNCTION
            TypeInfoKind.CLASS,
            TypeInfoKind.TABLE,
            TypeInfoKind.UNKNOWN,
            null -> Unit
        }
        return when (symbol.kind) {
            SymbolKind.MODULE -> CompletionItemKind.MODULE
            SymbolKind.FUNCTION,
            SymbolKind.METHOD -> CompletionItemKind.FUNCTION
            SymbolKind.PARAMETER -> CompletionItemKind.PARAMETER
            SymbolKind.LOCAL,
            SymbolKind.VARIABLE -> CompletionItemKind.VARIABLE
            SymbolKind.CLASS -> CompletionItemKind.CLASS
            SymbolKind.TYPE_ALIAS -> CompletionItemKind.TYPE_ALIAS
            SymbolKind.FIELD -> CompletionItemKind.FIELD
            SymbolKind.UNKNOWN -> adapters.completionKind(symbol)
        }
    }

    /**
     * Member-surface completions (table/module/Java class members) must expose field-like
     * symbols as [CompletionItemKind.FIELD], including Java static fields from
     * `luajava.bindClass` targets and conservative JavaBean property aliases. Backing
     * declarations sometimes arrive as value kinds (LOCAL/VARIABLE); hover still reports
     * [SymbolKind.FIELD] via member resolution.
     */
    private fun memberCompletionItem(symbol: Symbol, category: String, depth: Int): CompletionItem {
        val detail = symbol.declaredType?.displayName ?: symbol.type?.displayName
        return CompletionItem(
            label = symbol.name,
            kind = memberCompletionKind(symbol),
            detail = detail,
            insertText = symbol.name,
            sortText = "$category:${depth.toString().padStart(4, '0')}:${symbol.name}"
        )
    }

    private fun memberCompletionKind(symbol: Symbol): CompletionItemKind {
        return when (symbol.kind) {
            SymbolKind.METHOD -> CompletionItemKind.METHOD
            SymbolKind.FUNCTION -> CompletionItemKind.FUNCTION
            SymbolKind.CLASS -> CompletionItemKind.CLASS
            SymbolKind.MODULE -> CompletionItemKind.MODULE
            SymbolKind.TYPE_ALIAS -> CompletionItemKind.TYPE_ALIAS
            SymbolKind.PARAMETER -> CompletionItemKind.PARAMETER
            // Explicit FIELD, plus value-kind fallthrough for field-like member surfaces
            // (LOCAL/VARIABLE-backed Java static members and JavaBean aliases).
            SymbolKind.FIELD,
            SymbolKind.LOCAL,
            SymbolKind.VARIABLE -> CompletionItemKind.FIELD
            // Soft dual-path: unknown synthetic members still prefer FIELD when the
            // declared/type display looks non-callable (static field reads), else adapters.
            SymbolKind.UNKNOWN -> {
                val display = symbol.declaredType?.displayName ?: symbol.type?.displayName
                if (display != null && !looksCallableDisplay(display)) {
                    CompletionItemKind.FIELD
                } else {
                    adapters.completionKind(symbol)
                }
            }
        }
    }

    private fun looksCallableDisplay(display: String): Boolean {
        val normalized = display.trim()
        return normalized.startsWith("fun(") ||
            normalized.contains(" -> ") ||
            normalized.startsWith("(") && normalized.contains(")->")
    }

    private fun isMemberFieldLike(symbol: Symbol): Boolean {
        return symbol.kind == SymbolKind.LOCAL || symbol.kind == SymbolKind.VARIABLE
    }

    private fun categoryPrefix(symbol: Symbol): String {
        return when (lexicalCompletionKind(symbol)) {
            CompletionItemKind.PARAMETER -> "0"
            CompletionItemKind.VARIABLE -> {
                if (symbol.kind == SymbolKind.LOCAL) "1" else "3"
            }
            CompletionItemKind.FUNCTION,
            CompletionItemKind.METHOD -> "2"
            CompletionItemKind.MODULE -> "4"
            else -> "9"
        }
    }
}
