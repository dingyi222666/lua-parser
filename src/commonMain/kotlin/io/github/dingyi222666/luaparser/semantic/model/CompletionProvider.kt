package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
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
            lexicalCompletions(position)
        }
    }

    private fun lexicalCompletions(position: Position): List<CompletionItem> {
        return referenceQueries.visibleValueDeclarations(position)
            .mapNotNull { visible ->
                adapters.toDeclarationSymbol(visible.declaration)?.let { symbol ->
                    completionItem(symbol, categoryPrefix(symbol), visible.lexicalDepth)
                }
            }
            .sortedBy(CompletionItem::sortText)
    }

    private fun memberCompletions(expression: MemberExpression): List<CompletionItem> {
        return referenceQueries.resolveMemberCompletionSurface(expression)
            .mapIndexed { index, symbol ->
                val prefix = if (expression.indexer == ":") {
                    if (symbol.kind == SymbolKind.METHOD) "0" else "1"
                } else {
                    if (symbol.kind == SymbolKind.FIELD) "0" else "1"
                }
                completionItem(symbol, prefix, index)
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

    private fun categoryPrefix(symbol: Symbol): String {
        return when (symbol.kind) {
            SymbolKind.PARAMETER -> "0"
            SymbolKind.LOCAL -> "1"
            SymbolKind.FUNCTION -> "2"
            SymbolKind.VARIABLE -> "3"
            else -> "9"
        }
    }
}
