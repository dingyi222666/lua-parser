package source

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.source.AST2Lua
import parser.renderShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AST2LuaRoundTripTest {

    private val printer = AST2Lua()

    @Test
    fun roundTripsLua53FormsWithExplicitVersion() {
        assertRoundTrips(
            LuaVersion.LUA_5_3,
            listOf(
                RoundTripSample("return (a + b) * c - d / (e + f)"),
                RoundTripSample("return -a^b, a .. b .. c, not a and b"),
                RoundTripSample("return (a // b) << 2 | flags & mask ~ toggle"),
                RoundTripSample("return root.child[1 + offset]:call('x', { nested = values[2], flag = true })"),
                RoundTripSample(
                    source = "local function greet(a, ...) return ... end",
                    printedFragments = listOf("local function greet(a, ...)", "return ...")
                ),
                RoundTripSample(
                    source = "function obj:run(x, ...) return self, ... end",
                    printedFragments = listOf("function obj:run(x, ...)")
                ),
                RoundTripSample(
                    source = "::again:: goto again",
                    printedFragments = listOf("::again::", "goto again")
                ),
                RoundTripSample("for i = 1, limit, 2 do total = total + i end"),
                RoundTripSample("for key, value in pairs(source) do target[key] = value end"),
                RoundTripSample(
                    "local value = { total = (a + b) * c, { one = 1 }, items[index], ['name'] = other.name }"
                )
            )
        )
    }

    @Test
    fun roundTripsLua54AttributesWithExplicitVersion() {
        assertRoundTrips(
            LuaVersion.LUA_5_4,
            listOf(
                RoundTripSample(
                    source = "local pinned <const>, handle <close> = 1, open()",
                    printedFragments = listOf("local pinned <const>, handle <close> = 1, open()")
                ),
                RoundTripSample(
                    source = "do local file <close> = open() end",
                    printedFragments = listOf("local file <close> = open()")
                ),
                RoundTripSample(
                    source = "for i = 1, 2 do local limit <const> = i end",
                    printedFragments = listOf("local limit <const> = i")
                )
            )
        )
    }

    @Test
    fun roundTripsAndroLuaCompactCallsAndStatementsWithExplicitVersion() {
        assertRoundTrips(
            LuaVersion.ANDROLUA_5_3,
            listOf(
                RoundTripSample(
                    source = "require \"import\"",
                    printedFragments = listOf("require \"import\"")
                ),
                RoundTripSample(
                    source = "return luajava.loadLib \"java.util.Locale\", \"getDefault\"",
                    printedFragments = listOf("luajava.loadLib \"java.util.Locale\", \"getDefault\"")
                ),
                RoundTripSample(
                    source = "return print { value = 1, nested = { 2, 3 } }",
                    printedFragments = listOf("print { value = 1, nested = { 2, 3 } }")
                ),
                RoundTripSample(
                    source = "return receiver:emit \"ready\", { code = 200 }",
                    printedFragments = listOf("receiver:emit \"ready\", { code = 200 }")
                ),
                RoundTripSample(
                    source = "switch expr do case 1, 2 then call() default other() end",
                    printedFragments = listOf("switch expr do", "case 1, 2 then", "default")
                ),
                RoundTripSample(
                    // Optional then omitted in source must not be force-injected by AST2Lua.
                    source = "switch value do case 1, 2 print(value) end",
                    printedFragments = listOf("switch value do", "case 1, 2"),
                    absentFragments = listOf(" then")
                ),
                RoundTripSample(
                    source = "switch value do case 1 then break default continue end",
                    printedFragments = listOf("case 1 then", "default")
                ),
                RoundTripSample(
                    source = "while ready do continue end",
                    printedFragments = listOf("continue")
                ),
                RoundTripSample(
                    source = "when ready call() else fallback()",
                    printedFragments = listOf("when ready call() else fallback()")
                ),
                RoundTripSample(
                    source = "when ready target = value else other = fallback",
                    printedFragments = listOf("when ready target = value else other = fallback")
                ),
                RoundTripSample(
                    source = "return lambda value -> value",
                    printedFragments = listOf("lambda (value) : value")
                ),
                RoundTripSample(
                    source = "return [1, 2, foo()]",
                    printedFragments = listOf("[1, 2, foo()]")
                )
            )
        )
    }

    @Test
    fun switchCaseOptionalThenFidelityPreservesKeywordPresence() {
        val withThenSource = "switch value do case 1 then print(1) end"
        val withoutThenSource = "switch value do case 1, 2 print(value) end"

        val withThen = LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3).parse(withThenSource)
        val withoutThen = LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3).parse(withoutThenSource)

        val withThenCase = assertIs<CaseCause>(assertIs<SwitchStatement>(withThen.body.statements.single()).causes.single())
        val withoutThenCase = assertIs<CaseCause>(assertIs<SwitchStatement>(withoutThen.body.statements.single()).causes.single())
        assertTrue(withThenCase.hasThen, "parser must record then presence for case with then")
        assertFalse(withoutThenCase.hasThen, "parser must record then absence for optional-then case")

        val printedWithThen = printer.asCode(withThen)
        val printedWithoutThen = printer.asCode(withoutThen)
        assertTrue(printedWithThen.contains("case 1 then"), printedWithThen)
        assertTrue(printedWithoutThen.contains("case 1, 2"), printedWithoutThen)
        assertFalse(
            printedWithoutThen.contains(" then"),
            "AST2Lua must not inject then when CaseCause.hasThen is false:\n$printedWithoutThen"
        )

        assertEquals(renderShape(withThen), renderShape(LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3).parse(printedWithThen)))
        assertEquals(renderShape(withoutThen), renderShape(LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3).parse(printedWithoutThen)))
    }

    private fun assertRoundTrips(version: LuaVersion, samples: List<RoundTripSample>) {
        samples.forEach { sample ->
            val initial = LuaParser(luaVersion = version).parse(sample.source)
            val printed = printer.asCode(initial)
            val reparsed = LuaParser(luaVersion = version).parse(printed)

            assertEquals(renderShape(initial), renderShape(reparsed), sample.source)
            sample.printedFragments.forEach { fragment ->
                assertTrue(
                    printed.contains(fragment),
                    "Printed code for ${sample.source} did not contain <$fragment>:\n$printed"
                )
            }
            sample.absentFragments.forEach { fragment ->
                assertFalse(
                    printed.contains(fragment),
                    "Printed code for ${sample.source} unexpectedly contained <$fragment>:\n$printed"
                )
            }
        }
    }

    private data class RoundTripSample(
        val source: String,
        val printedFragments: List<String> = emptyList(),
        val absentFragments: List<String> = emptyList()
    )
}
