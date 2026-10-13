package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.api.TypeInfoKind
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType

internal class CompletionProvider(
    private val nodePositionIndex: NodePositionIndex,
    private val referenceQueries: ReferenceQueries,
    private val adapters: ApiAdapters,
    private val evaluator: ExpressionTypeEvaluator,
    private val signatures: SignatureHelpProvider
) {
    fun getCompletionsAt(position: Position, nameFilter: ((String) -> Boolean)? = null): List<CompletionItem> {
        val node = nodePositionIndex.findInnermost(position)
        val memberExpression = nodePositionIndex.completionMemberAt(position)

        return if (memberExpression != null) memberCompletions(memberExpression, position, nameFilter)
        else lexicalCompletions(position, node, nameFilter)
    }

    private fun lexicalCompletions(position: Position, node: BaseASTNode?, nameFilter: ((String) -> Boolean)?): List<CompletionItem> {
        val visibleItems = referenceQueries.visibleValueDeclarations(position, nameFilter)
            .mapNotNull { visible ->
                // Prefer declaredType-backed detail/kind for ambient binder builtins
                // (activity/service VARIABLE, load* FUNCTION, luajava MODULE).
                val declared = visible.declaration.declaredType
                val kind = visible.declaration.kind
                val isCallableDeclaration = kind == DeclarationKind.FUNCTION || kind == DeclarationKind.GLOBAL ||
                    kind == DeclarationKind.METHOD || declared is CallableType
                if (!isCallableDeclaration) {
                    val symbol = adapters.toDeclarationSymbol(visible.declaration)
                    buildCompletionItem(symbol, lexicalCompletionKind(symbol), categoryPrefix(symbol), visible.lexicalDepth)
                } else {
                    val type = declared.takeIf { candidate ->
                        candidate is CallableType && candidate.callSignatures.all { it.returnType.displayName != "unknown" }
                    } ?: evaluator.declarationValueType(visible.declaration)
                    adapters.toDeclarationSymbol(visible.declaration, typeOverride = type).let { symbol ->
                        val expression = (node as? Identifier)?.takeIf { it.name == symbol.name }
                        buildCompletionItem(symbol, lexicalCompletionKind(symbol), categoryPrefix(symbol), visible.lexicalDepth).copy(
                            detail = signatures.callableDetail(type, expression, position, visible.declaration)
                        )
                    }
                }
            }
        val lazyImportItem = (node as? Identifier)
            ?.let { identifier -> referenceQueries.importedCompletionSymbol(identifier.name, position) }
            ?.let { symbol ->
                val item = buildCompletionItem(symbol, lexicalCompletionKind(symbol), categoryPrefix(symbol), 0)
                val importedType = adapters.typeByKey(symbol.declaredType?.typeKey ?: symbol.type?.typeKey)
                if (importedType is CallableType) {
                    item.copy(detail = signatures.callableDetail(importedType, null, position))
                } else {
                    item
                }
            }
        return (visibleItems + listOfNotNull(lazyImportItem).filter { nameFilter?.invoke(it.label) != false })
            .distinctBy(CompletionItem::label)
            .sortedBy(CompletionItem::sortText)
    }

    private fun memberCompletions(expression: MemberExpression, position: Position, nameFilter: ((String) -> Boolean)?): List<CompletionItem> {
        // Member surface includes conservative JavaBean property aliases as FIELD symbols
        // (from ReferenceQueries) alongside direct getter/setter METHOD members. For `:`
        // the surface already excludes non-callable FIELD members (aliases, data fields)
        // because a colon site is a call site; `.` keeps the full list.
        return referenceQueries.resolveMemberCompletionSurface(expression)
            .filter { nameFilter?.invoke(it.name) != false }
            .mapIndexed { index, symbol ->
                // Prefer fields (including Java static fields and JavaBean aliases on
                // bindClass targets) over methods for `.` members; `:` is a call site.
                val firstGroupKind = if (expression.indexer == ":") SymbolKind.METHOD else SymbolKind.FIELD
                val prefix = if (symbol.kind == firstGroupKind ||
                    (expression.indexer != ":" && (symbol.kind == SymbolKind.LOCAL || symbol.kind == SymbolKind.VARIABLE))
                ) "0" else "1"
                val type = adapters.typeByKey(symbol.declaredType?.typeKey ?: symbol.type?.typeKey)
                buildCompletionItem(symbol, memberCompletionKind(symbol), prefix, index).let { item ->
                    if (type == null || (type !is CallableType && symbol.kind != SymbolKind.METHOD && symbol.kind != SymbolKind.FUNCTION)) {
                        item
                    } else item.copy(detail = signatures.callableDetail(
                        type, expression.takeIf { it.identifier.name == symbol.name }, position
                    ))
                }
            }
            .sortedBy(CompletionItem::sortText)
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
            else -> Unit
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
    /** Shared item construction; lexical and member surfaces differ only in kind mapping. */
    private fun buildCompletionItem(
        symbol: Symbol,
        kind: CompletionItemKind,
        category: String,
        depth: Int
    ): CompletionItem {
        val detail = symbol.declaredType?.displayName ?: symbol.type?.displayName
        return CompletionItem(
            label = symbol.name,
            kind = kind,
            detail = detail,
            insertText = symbol.name,
            sortText = "$category:${depth.toString().padStart(4, '0')}:${symbol.name}"
        )
    }

    private fun memberCompletionKind(symbol: Symbol): CompletionItemKind {
        val display = symbol.declaredType?.displayName ?: symbol.type?.displayName
        return when (symbol.kind) {
            SymbolKind.METHOD -> CompletionItemKind.METHOD
            SymbolKind.FUNCTION -> CompletionItemKind.FUNCTION
            SymbolKind.CLASS -> CompletionItemKind.CLASS
            SymbolKind.MODULE -> CompletionItemKind.MODULE
            SymbolKind.TYPE_ALIAS -> CompletionItemKind.TYPE_ALIAS
            SymbolKind.PARAMETER -> CompletionItemKind.PARAMETER
            // Explicit FIELD plus value-kind fallthrough for field-like member surfaces
            // (LOCAL/VARIABLE-backed Java static members and JavaBean aliases) and unknown
            // synthetic members: a callable display (Lua function stored in a table/module
            // field) is a function to call, any other known display is a field/value icon,
            // and only an unknown synthetic with no display evidence at all falls back to
            // the adapter (TEXT).
            SymbolKind.FIELD,
            SymbolKind.LOCAL,
            SymbolKind.VARIABLE,
            SymbolKind.UNKNOWN -> when {
                display != null && looksCallableDisplay(display) -> CompletionItemKind.FUNCTION
                display != null -> CompletionItemKind.FIELD
                symbol.kind == SymbolKind.UNKNOWN -> adapters.completionKind(symbol)
                else -> CompletionItemKind.FIELD
            }
        }
    }

    private fun looksCallableDisplay(display: String): Boolean {
        val normalized = display.trim()
        return normalized.startsWith("fun(") ||
            normalized.contains(" -> ") ||
            normalized.startsWith("(") && normalized.contains(")->")
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
