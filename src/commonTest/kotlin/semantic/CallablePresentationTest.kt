package semantic

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.checker.ValueSequence
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CallablePresentationTest {
    private fun snapshot(source: String) = SemanticPipeline().analyzeSnapshot(LuaParser().parse(source),
        SemanticWorkspaceContext(overlayGlobals = BuiltinOverlaySnapshot.EMPTY.globals))

    private fun position(source: String, text: String): Position {
        val index = source.lastIndexOf(text)
        require(index >= 0)
        val before = source.substring(0, index)
        return Position(before.count { it == '\n' } + 1, before.substringAfterLast('\n').length + 2)
    }

    @Test
    fun no_return_bare_return_and_explicit_nil_have_distinct_signatures() {
        val source = """
            local function idle() end
            local function stop() return end
            local function nullValue() return nil end
            local function forward() return idle() end
            local value = idle()
            local extra = nullValue()
            return idle, stop, nullValue, forward, value, extra
        """.trimIndent()
        val snapshot = snapshot(source)
        val evaluator = ExpressionTypeEvaluator(snapshot.binder, snapshot.workspaceContext)
        val expressions = snapshot.chunk.body.returnStatement!!.arguments
        val returns = expressions.take(4).map { assertIs<FunctionType>(evaluator.evaluate(it)).returnType }
        assertEquals(listOf(PrimitiveType.VOID, PrimitiveType.VOID, PrimitiveType.NIL, PrimitiveType.VOID), returns)
        assertEquals(PrimitiveType.NIL, evaluator.evaluate(expressions[4]))
        assertEquals(PrimitiveType.NIL, evaluator.evaluate(expressions[5]))
        val completions = snapshot.model.getCompletionsAt(position(source, "idle, stop"))
        assertEquals("fun(): void", completions.single { it.label == "idle" }.detail)
        assertEquals("fun(): nil", completions.single { it.label == "nullValue" }.detail)
    }

    @Test
    fun zero_values_do_not_add_a_nil_argument_or_a_return_slot() {
        assertTrue(ValueSequence.of(PrimitiveType.VOID).fixed.isEmpty())
        assertTrue(ValueSequence.fromTypes(emptyList()).fixed.isEmpty())
        assertTrue(ValueSequence.fromExpressionResults(emptyList()).fixed.isEmpty())
        assertEquals(listOf(PrimitiveType.NIL), ValueSequence.of(PrimitiveType.NIL).fixed)
        val source = """
            local function idle() end
            ---@return void
            local function valid() return idle() end
            ---@return void
            local function invalid() return nil end
            ---@return nil
            local function explicit() return nil end
        """.trimIndent()
        val diagnostics = snapshot(source).model.getDiagnostics()
        assertEquals(1, diagnostics.count { it.code == "checker.function.return.extraValues" }, diagnostics.toString())
        assertTrue(diagnostics.single { it.code == "checker.function.return.extraValues" }.range!!.start.line >= 4)
    }

    @Test
    fun parameter_annotations_do_not_turn_no_return_into_nil_and_unknown_values_stay_unknown() {
        val source = """
            ---@param value string
            local function consume(value) end
            local function unresolved(value) return value end
            local function explicit() return nil end
            return consume, unresolved, explicit
        """.trimIndent()
        val model = snapshot(source).model
        val items = model.getCompletionsAt(position(source, "consume, unresolved"))
        assertEquals("fun(value: string): void", items.single { it.label == "consume" }.detail)
        // An unresolved value is not evidence of zero return values.
        assertEquals("fun(value: unknown): unknown", items.single { it.label == "unresolved" }.detail)
        assertEquals("fun(): nil", items.single { it.label == "explicit" }.detail)
    }

    @Test
    fun conditional_fallthrough_infers_nil_but_unconditional_value_does_not() {
        val source = """
            local function maybe(flag) if flag then return 1 end end
            local function certain(flag) if flag then return 1 else return 2 end end
            return maybe, certain
        """.trimIndent()
        val snapshot = snapshot(source)
        val evaluator = ExpressionTypeEvaluator(snapshot.binder, snapshot.workspaceContext)
        val types = snapshot.chunk.body.returnStatement!!.arguments.map { assertIs<FunctionType>(evaluator.evaluate(it)).returnType }
        assertTrue(PrimitiveType.NIL in assertIs<UnionType>(types[0]).types)
        assertFalse(PrimitiveType.NIL in assertIs<UnionType>(types[1]).types)
    }

    @Test
    fun completion_and_hover_select_member_overloads_from_actual_arguments() {
        val source = """
            local API = {}
            ---@param value number
            ---@param option string
            ---@return number
            ---@overload fun(value: string, option: number): string
            function API.convert(value, option) return value end
            API.convert('item', 3)
            API.convert(3, 'item')
        """.trimIndent()
        val model = snapshot(source).model
        val stringSite = position(source, "convert('item'")
        val numberSite = position(source, "convert(3")
        val stringDetail = model.getCompletionsAt(stringSite).single { it.label == "convert" }.detail
        val numberDetail = model.getCompletionsAt(numberSite).single { it.label == "convert" }.detail
        assertEquals("fun(value: string, option: number): string", stringDetail)
        assertEquals("fun(value: number, option: string): number", numberDetail)
        assertEquals(stringDetail, assertNotNull(model.getCallableHoverAt(stringSite)).displayName)
        assertEquals(numberDetail, assertNotNull(model.getCallableHoverAt(numberSite)).displayName)
    }

    @Test
    fun lexical_completion_and_partial_signature_help_use_the_same_overload_matcher() {
        val source = """
            ---@param value number
            ---@param option string
            ---@return number
            ---@overload fun(value: string, option: number): string
            local function convert(value, option) return value end
            convert('item')
        """.trimIndent()
        val snapshot = snapshot(source)
        val model = snapshot.model
        val namePosition = position(source, "convert('item'")
        assertEquals("fun(value: string, option: number): string",
            model.getCompletionsAt(namePosition).single { it.label == "convert" }.detail)
        val help = assertNotNull(model.getSignatureHelpAt(Position(6, 12)))
        assertEquals("fun(value: string, option: number): string", help.signatures[help.activeSignature].label)
        // Presentation of an incomplete call must not change the checker's rejection.
        val call = snapshot.chunk.body.statements.filterIsInstance<CallStatement>().single().expression
        assertEquals("unknown", ExpressionTypeEvaluator(snapshot.binder, snapshot.workspaceContext).evaluate(call).displayName)
    }

    @Test
    fun instantiated_generic_overload_keeps_its_original_active_signature_index() {
        val source = """
            ---@param value number
            ---@return number
            ---@overload fun<T>(value: T, extra: T): T
            local function choose(value) return value end
            choose('first', 'second')
        """.trimIndent()
        val model = snapshot(source).model
        val help = assertNotNull(model.getSignatureHelpAt(Position(5, 17)))
        assertEquals(1, help.activeSignature, help.toString())
        assertTrue(help.signatures[help.activeSignature].label.startsWith("fun<T>"))
        val detail = assertNotNull(model.getCompletionsAt(position(source, "choose('")).single { it.label == "choose" }.detail)
        assertFalse(": T" in detail, detail)
        assertFalse("overloads" in detail, detail)
    }

    @Test
    fun partial_ambiguous_call_previews_the_ranked_signature_instead_of_all_overloads() {
        val source = """
            ---@param value number
            ---@return number
            ---@overload fun(value: string, extra: number): string
            ---@overload fun(value: string, extra: boolean): boolean
            local function choose(value) return value end
            choose('item')
        """.trimIndent()
        val model = snapshot(source).model
        val detail = model.getCompletionsAt(position(source, "choose('")).single { it.label == "choose" }.detail
        assertEquals("fun(value: string, extra: number): string", detail)
        assertFalse('&' in detail.orEmpty())
        assertFalse("overloads" in detail.orEmpty())
    }

    @Test
    fun idle_lexical_completion_shows_one_signature_without_concatenating_overloads() {
        val source = """
            ---@param value number
            ---@return number
            ---@overload fun(value: string): string
            local function choose(value) return value end
            choose
        """.trimIndent()
        val detail = snapshot(source).model.getCompletionsAt(position(source, "choose"))
            .single { it.label == "choose" }.detail
        assertEquals("fun(value: number): number", detail)
        assertFalse('&' in detail.orEmpty())
    }

    @Test
    fun idle_completion_previews_one_overload_without_inventing_correlated_parameters() {
        val source = """
            local API = {}
            ---@param value number
            ---@param option string
            ---@return number
            ---@overload fun(value: string, option: number): string
            function API.convert(value, option) return value end
            API.convert
        """.trimIndent()
        val model = snapshot(source).model
        val detail = assertNotNull(model.getCompletionsAt(position(source, "convert")).single { it.label == "convert" }.detail)
        assertEquals("fun(value: number, option: string): number", detail)
        assertFalse('&' in detail)
        assertFalse('\n' in detail)
        assertFalse('|' in detail)
    }

    @Test
    fun real_multi_return_slots_remain_correct_and_display_as_a_tuple() {
        val source = """
            ---@return number, string
            local function pair() return 1, 'item' end
            local first, second = pair()
            return first, second
        """.trimIndent()
        val snapshot = snapshot(source)
        val call = snapshot.chunk.body.statements.filterIsInstance<LocalStatement>().single().variables.single()
        assertEquals("(number, string)", snapshot.model.getTypeAt(call)?.displayName)
        val evaluator = ExpressionTypeEvaluator(snapshot.binder, snapshot.workspaceContext)
        assertEquals(listOf(PrimitiveType.NUMBER, PrimitiveType.STRING),
            snapshot.chunk.body.returnStatement!!.arguments.map(evaluator::evaluate))

        val completion = snapshot.model.getCompletionsAt(position(source, "first, second"))
            .single { it.label == "pair" }
        assertEquals("fun(): (number, string)", completion.detail)
    }
}
