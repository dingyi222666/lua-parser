package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator

/**
 * AndroLua layout-table completions: when the caret sits on a property-key position inside a
 * loadlayout-style table (`{ ListView, ad| }`), suggest the view class's Lua property keys
 * instead of lexical globals. Property semantics follow the Android-Lua `loadlayout.lua`
 * runtime (property `k` applies `view.setCap(k)(value)`), so the suggestions are derived from
 * the reflected `set*` methods of the enclosing view class plus the runtime-special keys.
 *
 * String literals are completed too: property values use the loadlayout value domains
 * (`orientation="vert|"` → vertical/horizontal, layout sizes → wrap/fill/-1/-2/%w/%h…), and
 * `require("…")` / `import "…"` strings offer workspace module names.
 *
 * Returns null whenever the position is not a layout-table or modeled-string context, letting
 * callers fall back to the regular completion pipeline.
 */
internal class LuaLayoutCompletionProvider(
    private val nodePositionIndex: NodePositionIndex,
    private val evaluator: ExpressionTypeEvaluator
) {
    fun getCompletionsAt(position: Position): List<CompletionItem>? {
        val innermost = nodePositionIndex.findInnermost(position)
        val enclosing = nodePositionIndex.findEnclosing(position)
        stringLiteralCompletions(innermost, enclosing, position)?.let { return it }

        val enclosingTables = enclosing.filterIsInstance<TableConstructorExpression>().toMutableList()
        val innermostTable = innermost as? TableConstructorExpression
        if (innermostTable != null) {
            // Keep innermost-first ordering; enclosingTables.last() must stay the outermost
            // table so layoutPropertyClassAt walks the full class chain down to the caret.
            enclosingTables.add(0, innermostTable)
        }
        if (enclosingTables.isEmpty() || !isPropertyKeyContext(innermost, enclosingTables.first())) {
            return null
        }
        val outermost = enclosingTables.last()
        val match = enclosingTables.firstNotNullOfOrNull { table ->
            evaluator.layoutPropertyClassAt(outermost, table)
                ?: evaluator.layoutPropertyClassAt(table, table)
        } ?: return null
        return evaluator.layoutPropertySuggestions(match).map { suggestion ->
            CompletionItem(
                label = suggestion.label,
                kind = CompletionItemKind.FIELD,
                detail = suggestion.detail,
                insertText = suggestion.label,
                sortText = "${sortGroup(suggestion.label)}:${suggestion.label}"
            )
        }
    }

    /**
     * String-literal completion, two shapes:
     * - the innermost node is a STRING constant (`gravity = "left|"` with content) — complete
     *   from its table-key value domain or the require/import module list;
     * - the constant carries no indexed node because the literal is EMPTY (`layout_width=""`):
     *   empty-string nodes fall under the index's `start < end` node filter, so detect via an
     *   enclosing [TableKey] whose string value range covers the caret.
     */
    private fun stringLiteralCompletions(
        innermost: BaseASTNode?,
        enclosing: List<BaseASTNode>,
        position: Position
    ): List<CompletionItem>? {
        if (innermost is ConstantNode && innermost.constantType == ConstantNode.TYPE.STRING) {
            val parent = runCatching { innermost.parent }.getOrNull()
            when (parent) {
                is TableKey ->
                    if (parent.value === innermost) {
                        valueDomainCompletions(tableKeyName(parent))?.let { return it }
                    }

                is CallExpression ->
                    if (innermost in parent.arguments) {
                        moduleArgumentCompletions(parent)?.let { return it }
                    }
            }
        }
        // Empty-string value: recover through the enclosing key node.
        val enclosingKey = enclosing.filterIsInstance<TableKey>().firstOrNull { keyNode ->
            val value = keyNode.value as? ConstantNode
            value != null &&
                value.constantType == ConstantNode.TYPE.STRING &&
                rangeContains(value.range, position)
        }
        if (enclosingKey != null) {
            valueDomainCompletions(tableKeyName(enclosingKey))?.let { return it }
        }
        return null
    }

    private fun valueDomainCompletions(key: String?): List<CompletionItem>? {
        val values = evaluator.layoutValueSuggestionsForKey(key ?: return null)
        return values.takeIf { it.isNotEmpty() }?.map { value ->
            CompletionItem(
                label = value,
                kind = CompletionItemKind.KEYWORD,
                insertText = value,
                sortText = value
            )
        }
    }

    private fun moduleArgumentCompletions(call: CallExpression): List<CompletionItem>? {
        val base = call.base as? Identifier ?: return null
        if (base.name !in MODULE_NAME_FUNCTIONS) {
            return null
        }
        val names = evaluator.workspaceModuleCompletionNames()
        return names.takeIf { it.isNotEmpty() }?.map { name ->
            CompletionItem(
                label = name,
                kind = CompletionItemKind.MODULE,
                insertText = name,
                sortText = name
            )
        }
    }

    private fun tableKeyName(field: TableKey): String? {
        return when (val key = field.key) {
            is Identifier -> key.name
            is ConstantNode -> when (key.constantType) {
                ConstantNode.TYPE.STRING -> key.stringOf()
                ConstantNode.TYPE.INTERGER -> key.rawValue.toString().toIntOrNull()?.toString()
                else -> null
            }

            else -> null
        }
    }

    private fun rangeContains(range: Range, position: Position): Boolean {
        val afterStart = range.start.line < position.line ||
            (range.start.line == position.line && range.start.column <= position.column)
        val beforeEnd = range.end.line > position.line ||
            (range.end.line == position.line && range.end.column > position.column)
        return afterStart && beforeEnd
    }

    /**
     * Key context means the caret can receive a new table property:
     * - on the table itself (whitespace/punctuation between fields) or on a [TableKey];
     * - on a field's KEY identifier / constant (`layou| = "-1"`);
     * - on a positional identifier entry that is a property key being typed
     *   (`{ ListView, adap }` — Lua parses the bare word as an array value). The class slot
     *   (first positional entry, e.g. `ListV|` while typing the view class) is excluded.
     *
     * Carets inside value expressions (call arguments, lambdas, string values) keep the
     * regular completion pipeline.
     */
    private fun isPropertyKeyContext(
        innermost: BaseASTNode?,
        innermostTable: TableConstructorExpression
    ): Boolean {
        when (innermost) {
            is TableConstructorExpression, is TableKey -> return true
            is Identifier, is ConstantNode -> Unit
            else -> return false
        }
        val parent = runCatching { innermost.parent }.getOrNull() as? TableKey ?: return false
        if (parent.key === innermost) {
            return true
        }
        if (parent.value !== innermost) {
            return false
        }
        if (isPositionalField(parent)) {
            // Bare word being typed as a property key — but the first positional entry is
            // the view class slot, not a property.
            return !isClassSlot(parent, innermostTable)
        }
        // Named-key value position (`gravity = cen|`, `onClick = handl|`): completing a
        // value expression, not a key — keep the regular completion pipeline.
        return false
    }

    private fun isClassSlot(field: TableKey, table: TableConstructorExpression): Boolean {
        return table.fields.asSequence()
            .filter(::isPositionalField)
            .firstOrNull() === field
    }

    private fun isPositionalField(field: TableKey): Boolean {
        if (field is TableKeyString) {
            return false
        }
        val key = field.key
        return key !is Identifier && key !is ConstantNode ||
            key is ConstantNode && key.constantType == ConstantNode.TYPE.INTERGER
    }

    private fun sortGroup(label: String): String = when (label) {
        "id" -> "0"
        "layout_width", "layout_height" -> "2"
        else -> "1"
    }

    private companion object {
        private val MODULE_NAME_FUNCTIONS = setOf("require", "import")
    }
}
