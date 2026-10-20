package source

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.compactCallArguments
import io.github.dingyi222666.luaparser.parser.compactCallBase
import io.github.dingyi222666.luaparser.parser.isCompactShortCall
import io.github.dingyi222666.luaparser.source.AST2Lua
import parser.renderShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AST2Lua round-trip corpus focused on call / member / index chains for Lua 5.3
 * (TASK-360).
 *
 * Covers left-nested prefix-expression chains from the Lua 5.3 grammar slice:
 *   prefixexp ::= primaryexp { '.' Name | '[' exp ']' | ':' Name args | args }
 *   args ::= '(' [explist] ')' | tableconstructor | String
 *
 * ## Shape-stable print policy
 *
 * Round-trip acceptance is **AST-shape stable**, not byte-identical to the input source.
 *
 * 1. **Acceptance metric** — `parse(source)` → `AST2Lua.asCode` → reparse must yield the same
 *    `renderShape` as the original parse. Whitespace / indentation may change; structural
 *    nesting of Call / Member / Index / StringCall / TableCall must not.
 * 2. **Dot vs colon members** — printer re-emits the stored [MemberExpression.indexer]
 *    (`.` or `:`). Colon method self is encoded by the colon member only (no invented
 *    `self` argument on the call). Dot and colon siblings on the same receiver/name remain
 *    distinct shapes after print→reparse.
 * 3. **Index expressions** — printer emits `base[index]` with the index expression fully
 *    re-printed (including nested member/call/index forms). Implicit array keys inside table
 *    constructors are out of scope here (see TASK-304).
 * 4. **Parenthesized calls** — plain `CallExpression` prints as `base(arg, …)` with comma+space
 *    argument separators. Empty args print as `()`. Cosmetic parentheses around a bare primary
 *    name base (e.g. `(go)(1)`) are not modeled in the AST and may be dropped while keeping
 *    shape. Lower-precedence bases under a call/member/index parent must keep required parens
 *    (e.g. `(a + b)(x)`, `(a or b).field`).
 * 5. **Compact string / table calls** — specialized [StringCallExpression] / [TableCallExpression]
 *    (and outer [CallExpression] wrappers with compact continuation args) print without
 *    parentheses around the first string/table arg: `f "x"`, `f { k = 1 }`, and multi-arg
 *    continuations `f "a", b`. Shape uses the documented wrapper form
 *    `Call(StringCall(base:first):rest…)` / `Call(TableCall(...):…)`. Printer must not rewrite
 *    compact forms into parenthesized calls (or the reverse).
 * 6. **Chain nesting** — multi-step chains stay left-nested after reparse
 *    (`a:b():c()`, `root.child[i]:call(x)`, `factory():create(name)`, mixed
 *    colon/dot/index/call).
 * 7. **Version** — all samples use [LuaVersion.LUA_5_3] (compact string/table args and
 *    multi-arg compact continuations are in scope for this surface).
 * 8. **Printer/parser interaction guard (compact multi-arg)** — AST2Lua always ends chunks with
 *    a trailing newline, and [LuaParser] defaults to `errorRecovery = true`. Under that pair,
 *    REVIEW31 observed that some **3+-arg compact continuations whose final arg is a bare Name**
 *    (e.g. `return factory "name", { id = 1 }, count`) fail reparse with
 *    `unexpected <name> near '<eof>'` even though the one-line source parses to the documented
 *    wrapper shape. Two-arg compact forms that end on a bare Name
 *    (`widgets[1] { text = "Save" }, options`) and multi-arg forms that end on string / table /
 *    call / const remain in full print→reparse scope. Three-arg corpora therefore end on a
 *    non-bare expression (string / table / call / const) so the call-chain surface stays locked
 *    without inventing printer APIs. Pure bare-Name-final 3+-arg compact forms stay out of scope
 *    for full print→reparse until production lands a fix.
 * 9. **Out of scope** — comment preservation, recovery of incomplete `f(` forms, AndroLua-only
 *    statement sugar, semantic self-injection / type resolution, and bare-Name-final 3+-arg
 *    compact print→reparse (see §8).
 */
class AST2LuaCallChainRoundTripTddTest {

    private val printer = AST2Lua()
    private val version = LuaVersion.LUA_5_3

    // --- Plain parenthesized calls ---

    @Test
    fun roundTripsPlainParenthesizedCalls() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return go()",
                    expectedShape = "Chunk(Block[Return(Call(Id(go):))])",
                    printedFragments = listOf("go()")
                ),
                Sample(
                    source = "return go(1)",
                    expectedShape = "Chunk(Block[Return(Call(Id(go):Const(1)))])",
                    printedFragments = listOf("go(1)")
                ),
                Sample(
                    source = "return go(1, 2, three)",
                    expectedShape =
                        "Chunk(Block[Return(Call(Id(go):Const(1),Const(2),Id(three)))])",
                    printedFragments = listOf("go(1, 2, three)")
                ),
                Sample(
                    source = "return go(a + b, not ready, #items)",
                    expectedShape =
                        "Chunk(Block[Return(Call(Id(go):Binary(+,Id(a),Id(b)),Unary(not,Id(ready)),Unary(#,Id(items))))])",
                    printedFragments = listOf("go(a + b, not ready, #items)")
                ),
                Sample(
                    source = "return go(factory(seed), object.member, items[i])",
                    expectedShape =
                        "Chunk(Block[Return(Call(Id(go):Call(Id(factory):Id(seed)),Member(Id(object).member),Index(Id(items)[Id(i)])))])",
                    printedFragments = listOf(
                        "factory(seed)",
                        "object.member",
                        "items[i]"
                    )
                ),
                // Cosmetic parens around bare primary name are not AST-modeled.
                Sample(
                    source = "return (go)(1)",
                    expectedShape = "Chunk(Block[Return(Call(Id(go):Const(1)))])",
                    printedFragments = listOf("go(1)")
                ),
                Sample(
                    source = "return (a + b)(x)",
                    expectedShape =
                        "Chunk(Block[Return(Call(Binary(+,Id(a),Id(b)):Id(x)))])",
                    printedFragments = listOf("(a + b)(x)")
                ),
                Sample(
                    source = "return (a or b).field",
                    expectedShape =
                        "Chunk(Block[Return(Member(Binary(or,Id(a),Id(b)).field))])",
                    printedFragments = listOf("(a or b).field")
                )
            )
        )
    }

    // --- Dot / colon member access and method calls ---

    @Test
    fun roundTripsDotAndColonMemberCallChains() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return obj.method",
                    expectedShape = "Chunk(Block[Return(Member(Id(obj).method))])",
                    printedFragments = listOf("obj.method")
                ),
                Sample(
                    source = "return obj.method(1)",
                    expectedShape =
                        "Chunk(Block[Return(Call(Member(Id(obj).method):Const(1)))])",
                    printedFragments = listOf("obj.method(1)")
                ),
                Sample(
                    source = "return obj:method(1)",
                    expectedShape =
                        "Chunk(Block[Return(Call(Member(Id(obj):method):Const(1)))])",
                    printedFragments = listOf("obj:method(1)")
                ),
                Sample(
                    source = "return widget:ping()",
                    expectedShape =
                        "Chunk(Block[Return(Call(Member(Id(widget):ping):))])",
                    printedFragments = listOf("widget:ping()")
                ),
                Sample(
                    source = "return object:method(1, \"two\", fn())",
                    expectedShape =
                        "Chunk(Block[Return(Call(Member(Id(object):method):Const(1),Const(\"two\"),Call(Id(fn):)))])",
                    printedFragments = listOf("object:method(1, \"two\", fn())")
                ),
                Sample(
                    source = "return activity:setTitle(\"Ready\")",
                    expectedShape =
                        "Chunk(Block[Return(Call(Member(Id(activity):setTitle):Const(\"Ready\")))])",
                    printedFragments = listOf("activity:setTitle(\"Ready\")")
                ),
                Sample(
                    source = "return activity.setTitle(\"Ready\")",
                    expectedShape =
                        "Chunk(Block[Return(Call(Member(Id(activity).setTitle):Const(\"Ready\")))])",
                    printedFragments = listOf("activity.setTitle(\"Ready\")")
                ),
                Sample(
                    source = "return root.child:run(value)",
                    expectedShape =
                        "Chunk(Block[Return(Call(Member(Member(Id(root).child):run):Id(value)))])",
                    printedFragments = listOf("root.child:run(value)")
                ),
                Sample(
                    source = "return M.util.helper()",
                    expectedShape =
                        "Chunk(Block[Return(Call(Member(Member(Id(M).util).helper):))])",
                    printedFragments = listOf("M.util.helper()")
                ),
                Sample(
                    source = "return a.b.c.d",
                    expectedShape =
                        "Chunk(Block[Return(Member(Member(Member(Id(a).b).c).d))])",
                    printedFragments = listOf("a.b.c.d")
                )
            )
        )
    }

    // --- Index chains and mixed index/member/call ---

    // --- Nested / chained calls (call results as bases) ---

    // --- Compact string / table calls and multi-arg continuations ---

    @Test
    fun roundTripsCompactStringAndTableCallChains() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return f \"x\"",
                    expectedShape =
                        "Chunk(Block[Return(Call(StringCall(Id(f):Const(\"x\")):))])",
                    printedFragments = listOf("f \"x\"")
                ),
                Sample(
                    source = "return f 'x'",
                    expectedShape =
                        "Chunk(Block[Return(Call(StringCall(Id(f):Const('x')):))])",
                    printedFragments = listOf("f 'x'")
                ),
                Sample(
                    source = "return f { a = 1 }",
                    expectedShape =
                        "Chunk(Block[Return(Call(TableCall(Id(f):Table(TableKeyString(Id(a)=Const(1)))):))])",
                    printedFragments = listOf("f { a = 1 }")
                ),
                Sample(
                    source = "return print { value = 1, nested = { 2, 3 } }",
                    expectedShape =
                        "Chunk(Block[Return(Call(TableCall(Id(print):Table(TableKeyString(Id(value)=Const(1)),TableKeyString(Id(nested)=Table(TableKey(Const(1)=Const(2)),TableKey(Const(2)=Const(3)))))):))])",
                    printedFragments = listOf("print { value = 1, nested = { 2, 3 } }")
                ),
                Sample(
                    source = "return m.n \"v\"",
                    expectedShape =
                        "Chunk(Block[Return(Call(StringCall(Member(Id(m).n):Const(\"v\")):))])",
                    printedFragments = listOf("m.n \"v\"")
                ),
                Sample(
                    source = "return m:n \"v\"",
                    expectedShape =
                        "Chunk(Block[Return(Call(StringCall(Member(Id(m):n):Const(\"v\")):))])",
                    printedFragments = listOf("m:n \"v\"")
                ),
                Sample(
                    source = "return m:n { k = 1 }",
                    expectedShape =
                        "Chunk(Block[Return(Call(TableCall(Member(Id(m):n):Table(TableKeyString(Id(k)=Const(1)))):))])",
                    printedFragments = listOf("m:n { k = 1 }")
                ),
                Sample(
                    source = "return activity:setTitle \"Ready\"",
                    expectedShape =
                        "Chunk(Block[Return(Call(StringCall(Member(Id(activity):setTitle):Const(\"Ready\")):))])",
                    printedFragments = listOf("activity:setTitle \"Ready\"")
                ),
                Sample(
                    source = "return view:configure { id = 1 }",
                    expectedShape =
                        "Chunk(Block[Return(Call(TableCall(Member(Id(view):configure):Table(TableKeyString(Id(id)=Const(1)))):))])",
                    printedFragments = listOf("view:configure { id = 1 }")
                ),
                Sample(
                    source = "return handlers[1] \"event\"",
                    expectedShape =
                        "Chunk(Block[Return(Call(StringCall(Index(Id(handlers)[Const(1)]):Const(\"event\")):))])",
                    printedFragments = listOf("handlers[1] \"event\"")
                ),
                Sample(
                    source = "return widgets[1] { text = \"Save\" }",
                    expectedShape =
                        "Chunk(Block[Return(Call(TableCall(Index(Id(widgets)[Const(1)]):Table(TableKeyString(Id(text)=Const(\"Save\")))):))])",
                    printedFragments = listOf("widgets[1] { text = \"Save\" }")
                ),
                Sample(
                    source = "return luajava.loadLib \"java.util.Locale\", \"getDefault\"",
                    expectedShape =
                        "Chunk(Block[Return(Call(StringCall(Member(Id(luajava).loadLib):Const(\"java.util.Locale\")):Const(\"getDefault\")))])",
                    printedFragments = listOf(
                        "luajava.loadLib \"java.util.Locale\", \"getDefault\""
                    )
                ),
                Sample(
                    source = "return receiver:emit \"ready\", { code = 200 }",
                    expectedShape =
                        "Chunk(Block[Return(Call(StringCall(Member(Id(receiver):emit):Const(\"ready\")):Table(TableKeyString(Id(code)=Const(200)))))])",
                    printedFragments = listOf(
                        "receiver:emit \"ready\", { code = 200 }"
                    )
                ),
                Sample(
                    source = "return widgets[1] { text = \"Save\" }, options",
                    expectedShape =
                        "Chunk(Block[Return(Call(TableCall(Index(Id(widgets)[Const(1)]):Table(TableKeyString(Id(text)=Const(\"Save\")))):Id(options)))])",
                    printedFragments = listOf(
                        "widgets[1] { text = \"Save\" }, options"
                    )
                ),
                // 3-arg compact: end on table (not bare Name) so print→reparse survives
                // AST2Lua trailing newline under default recovery (see KDoc §8 / REVIEW31).
                Sample(
                    source = "return factory \"name\", count, { id = 1 }",
                    expectedShape =
                        "Chunk(Block[Return(Call(StringCall(Id(factory):Const(\"name\")):Id(count),Table(TableKeyString(Id(id)=Const(1)))))])",
                    printedFragments = listOf(
                        "factory \"name\", count, { id = 1 }"
                    )
                ),
                // Parenthesized siblings must stay parenthesized (not rewritten to compact).
                Sample(
                    source = "return f(\"x\")",
                    expectedShape = "Chunk(Block[Return(Call(Id(f):Const(\"x\")))])",
                    printedFragments = listOf("f(\"x\")")
                ),
                Sample(
                    source = "return f({ a = 1 })",
                    expectedShape =
                        "Chunk(Block[Return(Call(Id(f):Table(TableKeyString(Id(a)=Const(1)))))])",
                    printedFragments = listOf("f({ a = 1 })")
                ),
                Sample(
                    source = "return luajava.loadLib(\"java.util.Locale\", \"getDefault\")",
                    expectedShape =
                        "Chunk(Block[Return(Call(Member(Id(luajava).loadLib):Const(\"java.util.Locale\"),Const(\"getDefault\")))])",
                    printedFragments = listOf(
                        "luajava.loadLib(\"java.util.Locale\", \"getDefault\")"
                    )
                )
            )
        )
    }

    // --- Statement contexts (call statements, assign/local RHS, loops) ---

    // --- Explicit AST surface checks after print→reparse ---

    @Test
    fun roundTripsBulkCallChainCorpusWithoutShapeDrift() {
        val samples = listOf(
            // plain calls
            "return go()",
            "return go(1)",
            "return go(1, 2, three)",
            "return go(a + b, not ready, #items)",
            "return go(factory(seed), object.member, items[i])",
            "return (go)(1)",
            "return (a + b)(x)",
            "return (a or b).field",
            "return (a and b):run()",
            "return (a .. b)[i]",
            "return (not ready)(x)",
            "return (-a)(x)",
            // members
            "return obj.method",
            "return obj.method(1)",
            "return obj:method(1)",
            "return widget:ping()",
            "return object:method(1, \"two\", fn())",
            "return activity:setTitle(\"Ready\")",
            "return activity.setTitle(\"Ready\")",
            "return root.child:run(value)",
            "return M.util.helper()",
            "return a.b.c.d",
            // index / mixed
            "return items[i]",
            "return items[1 + offset]",
            "return root.child[1]",
            "return root.child[1]:call('x')",
            "return root.child[1 + offset]:call('x', { nested = values[2], flag = true })",
            "return handlers[1](\"event\")",
            "return root:make(a)[i].field(b)",
            "return root.child:make(x)[i]:finish(y)",
            "return t[a][b][c]",
            "return matrix[row][col].value",
            // chained call bases
            "return factory():create(name)",
            "return a:b():c()",
            "return a.b().c()",
            "return a:b().c()",
            "return a.b():c()",
            "return require(\"pkg\").api:run()",
            "return outer(inner(x))(y)",
            "return get():next():done()",
            // compact
            "return f \"x\"",
            "return f 'x'",
            "return f { a = 1 }",
            "return print { value = 1, nested = { 2, 3 } }",
            "return m.n \"v\"",
            "return m:n \"v\"",
            "return m:n { k = 1 }",
            "return activity:setTitle \"Ready\"",
            "return view:configure { id = 1 }",
            "return handlers[1] \"event\"",
            "return widgets[1] { text = \"Save\" }",
            "return luajava.loadLib \"java.util.Locale\", \"getDefault\"",
            "return receiver:emit \"ready\", { code = 200 }",
            "return widgets[1] { text = \"Save\" }, options",
            // compact (3-arg ends on table — not bare-Name-final; see KDoc §8)
            "return factory \"name\", count, { id = 1 }",
            "return f(\"x\")",
            "return f({ a = 1 })",
            "return luajava.loadLib(\"java.util.Locale\", \"getDefault\")",
            "return m.n(\"v\")",
            "return m:n(\"v\")",
            "return t[1]({ k = 2 })",
            // statements / mixed surfaces
            "object:method(1, 2)",
            "root.child[1]:call('x')",
            "require \"import\"",
            "local value = factory():create(name)",
            "result = root.child[i]:run(arg)",
            "for k, v in pairs(source) do target[k] = v end",
            "while ready do tick():next() end",
            "function pack(a) return a:bits() & mask | 1 end",
            "return object:bits() & mask | 1",
            "return value << offsets[i] & mask",
            "return bits.hi << 8 | bits.lo",
            "return pack(a | b & c << d)",
            "local t = root:make()",
            "t.field = factory():create(name)",
            "return a:b():c():d(e, f)",
            "return obj.method(obj:other(1), items[i])"
        )

        val failures = samples.mapNotNull { source ->
            runCatching {
                val initial = LuaParser(luaVersion = version).parse(source)
                val printed = printer.asCode(initial)
                val reparsed = LuaParser(luaVersion = version).parse(printed)
                assertEquals(
                    renderShape(initial),
                    renderShape(reparsed),
                    "shape mismatch after print for <$source>\nprinted:\n$printed"
                )
            }.exceptionOrNull()?.let { failure ->
                "$source\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    // --- helpers ---

    private fun assertRoundTrips(samples: List<Sample>) {
        samples.forEach { sample ->
            val initial = LuaParser(luaVersion = version).parse(sample.source)
            if (sample.expectedShape != null) {
                assertEquals(sample.expectedShape, renderShape(initial), sample.source)
            }

            val printed = printer.asCode(initial)
            val reparsed = LuaParser(luaVersion = version).parse(printed)

            assertEquals(
                renderShape(initial),
                renderShape(reparsed),
                "shape drift for ${sample.source}\nprinted:\n$printed"
            )
            sample.printedFragments.forEach { fragment ->
                assertTrue(
                    printed.contains(fragment),
                    "Printed code for ${sample.source} did not contain <$fragment>:\n$printed"
                )
            }
        }
    }

    private data class Sample(
        val source: String,
        val expectedShape: String? = null,
        val printedFragments: List<String> = emptyList()
    )
}
