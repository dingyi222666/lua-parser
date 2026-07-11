package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind

internal class CompletionProvider(
    private val nodePositionIndex: NodePositionIndex,
    private val referenceQueries: ReferenceQueries,
    private val adapters: ApiAdapters
) {
    fun getCompletionsAt(position: Position): List<CompletionItem> {
        val node = nodePositionIndex.findInnermost(position)
        val memberExpression = when (node) {
            is MemberExpression -> node
            is Identifier -> (runCatching { node.parent }.getOrNull() as? MemberExpression)?.takeIf { it.identifier === node }
            else -> null
        }

        return if (memberExpression != null) {
            memberCompletions(memberExpression)
        } else {
            lexicalCompletions(position, node)
        }
    }

    private fun lexicalCompletions(position: Position, node: BaseASTNode?): List<CompletionItem> {
        val visibleItems = referenceQueries.visibleValueDeclarations(position)
            .mapNotNull { visible ->
                adapters.toDeclarationSymbol(visible.declaration)?.let { symbol ->
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
            kind = adapters.completionKind(symbol),
            detail = detail,
            insertText = symbol.name,
            sortText = "$category:${depth.toString().padStart(4, '0')}:${symbol.name}"
        )
    }

    /**
     * Member-surface completions (table/module/Java class members) must expose field-like
     * symbols as [CompletionItemKind.FIELD], including Java static fields from
     * `luajava.bindClass` targets. Backing declarations sometimes arrive as value kinds
     * (LOCAL/VARIABLE); hover still reports [SymbolKind.FIELD] via member resolution.
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
            // Explicit FIELD, plus value-kind fallthrough for field-like member surfaces.
            SymbolKind.FIELD,
            SymbolKind.LOCAL,
            SymbolKind.VARIABLE -> CompletionItemKind.FIELD
            SymbolKind.UNKNOWN -> adapters.completionKind(symbol)
        }
    }

    private fun isMemberFieldLike(symbol: Symbol): Boolean {
        return symbol.kind == SymbolKind.LOCAL || symbol.kind == SymbolKind.VARIABLE
    }

    private fun categoryPrefix(symbol: Symbol): String {
        return when (symbol.kind) {
            SymbolKind.PARAMETER -> "0"
            SymbolKind.LOCAL -> "1"
            SymbolKind.FUNCTION -> "2"
            SymbolKind.VARIABLE -> "3"
            SymbolKind.MODULE -> "4"
            else -> "9"
        }
    }
}
