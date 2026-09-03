package io.github.dingyi222666.luaparser.semantic.model

import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.Position
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
 * Returns null whenever the position is not a layout-table key context, letting callers fall
 * back to the regular completion pipeline.
 */
internal class LuaLayoutCompletionProvider(
    private val nodePositionIndex: NodePositionIndex,
    private val evaluator: ExpressionTypeEvaluator
) {
    fun getCompletionsAt(position: Position): List<CompletionItem>? {
        val innermost = nodePositionIndex.findInnermost(position)
        val enclosingTables = nodePositionIndex.findEnclosing(position)
            .filterIsInstance<TableConstructorExpression>()
            .toMutableList()
        val innermostTable = innermost as? TableConstructorExpression
        if (innermostTable != null) {
            enclosingTables += innermostTable
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
        // Named-key value position: a string value being typed is not a key context.
        return innermost !is ConstantNode || innermost.constantType != ConstantNode.TYPE.STRING
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
}
