package parser

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import kotlin.test.Test
import kotlin.test.assertFailsWith

class StrictLuaScopeTest {
    private val parser = LuaParser(LuaVersion.LUA_5_3, errorRecovery = false)

    @Test
    fun loop_and_vararg_contexts_restore_across_nested_functions_and_failed_parses() {
        parser.parse("function f() while true do break end end")
        assertFailsWith<IllegalStateException> { parser.parse("while true do function f() break end end") }
        parser.parse("while true do function f() end break end")
        assertFailsWith<IllegalStateException> { parser.parse("function f(...) function g() return ... end end") }
        parser.parse("function f(...) function g() end return ... end")
        parser.parse("return ...")
    }

    @Test
    fun strict_jump_lifetimes_handle_shadowing_tail_labels_repeat_and_functions() {
        parser.parse("goto done local x = 1 ::done::")
        parser.parse("::outer:: do local x = 1 goto outer end")
        parser.parse("::same:: do ::same:: goto same end")
        parser.parse("local x = 1 goto done local x = 2 ::done::")
        for (source in listOf(
            "goto done local x = 1 ::done:: print(x)",
            "repeat goto done local x = 1 ::done:: until true",
            "::outer:: function f() goto outer end",
            "goto hidden do ::hidden:: end",
            "::same:: ::same::"
        )) assertFailsWith<IllegalStateException>(source) { parser.parse(source) }
    }

    @Test
    fun parenthesized_receivers_remain_valid_but_parenthesized_lvalues_do_not() {
        parser.parse("(object).field = 1; (factory())()")
        assertFailsWith<IllegalStateException> { parser.parse("(object.field) = 1") }
        assertFailsWith<IllegalStateException> { parser.parse("(factory())") }
    }

    @Test
    fun recovery_keeps_unresolved_jumps_and_incomplete_colon_members() {
        LuaParser(LuaVersion.LUA_5_3).parse("goto missing\na:b")
        LuaParser(LuaVersion.ANDROLUA_5_3).parse("a:b")
    }
}
