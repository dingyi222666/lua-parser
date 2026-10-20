package source

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.source.AST2Lua
import parser.renderShape
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * AST2Lua round-trip corpus focused on **nested** Lua 5.3 table constructors (TASK-359).
 *
 * Complements [AST2LuaTableKeyFormsRoundTripTddTest] (field-kind surface) by locking multi-level
 * nesting: tables as list values, record values, bracket keys/values, and mixed forms at every
 * depth. Does not invent printer/parser APIs — only asserts existing `parse → AST2Lua.asCode →
 * reparse` shape stability and nested field-kind retention.
 *
 * ## Shape-stable print policy
 *
 * Round-trip acceptance is **AST-shape stable**, not byte-identical to the input source.
 *
 * 1. **Acceptance metric** — `parse(source)` → `AST2Lua.asCode` → reparse must yield the same
 *    `renderShape` as the original parse. Whitespace / cosmetic spacing may change; nested
 *    constructor structure, field kinds, and field order must not.
 * 2. **Inline nesting** — nested constructors print inline inside the outer field list:
 *    `{ outer = { inner = 1 } }`. The printer does not introduce multi-line indentation for
 *    table constructors (indent applies to statement blocks only).
 * 3. **Field kinds at every depth** — list (`exp` → [TableKey] with synthetic integer key),
 *    record (`Name = exp` → [TableKeyString]), and general (`[exp] = exp` → [TableKey] with
 *    non-synthetic key) retain their kinds after print→reparse, including when the *value* or
 *    *key* is itself a nested table.
 * 4. **Implicit array keys** — restart at 1 **per constructor**. Nested list fields do not
 *    consume outer array slots; outer list slots ignore nested constructors' internal indices.
 * 5. **Separator normalization** — nested constructors inherit outer policy: `,` / `;` /
 *    trailing separators normalize to comma-separated fields with single spaces; empty nested
 *    tables print as `{}`.
 * 6. **Printer/parser interaction guard** — AST2Lua always ends chunks with a trailing newline.
 *    With the current parser, a **bare Name as the last table field** immediately before `}`
 *    then that trailing newline fails reparse. Nested corpora therefore either end list fields
 *    with a non-bare expression (const / call / member / index / unary / binary / vararg /
 *    function / nested table) or place a safe trailing field after bare names. Pure bare-last
 *    constructors remain out of scope for full print→reparse until production lands a fix.
 * 7. **Version** — all samples use [LuaVersion.LUA_5_3].
 * 8. **Out of scope** — comment preservation, recovery of malformed tables, AndroLua array
 *    constructors (`[1, 2]`), semantic key uniqueness, and multi-line pretty-print of tables.
 */
class AST2LuaNestedTableRoundTripTddTest {

    private val printer = AST2Lua()
    private val version = LuaVersion.LUA_5_3

    // --- Nested as list / array field values ---

    @Test
    fun roundTripsNestedTablesAsListFieldValues() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return { {} }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Table())))])",
                    printedFragments = listOf("{ {} }")
                ),
                Sample(
                    source = "return { { 1 }, { 2 }, 0 }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Table(TableKey(Const(1)=Const(1)))),TableKey(Const(2)=Table(TableKey(Const(1)=Const(2)))),TableKey(Const(3)=Const(0))))])",
                    printedFragments = listOf("{ 1 }", "{ 2 }", "0")
                ),
                Sample(
                    source = "return { { a = 1 }, { b = 2 }, true }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Table(TableKeyString(Id(a)=Const(1)))),TableKey(Const(2)=Table(TableKeyString(Id(b)=Const(2)))),TableKey(Const(3)=Const(true))))])",
                    printedFragments = listOf("{ a = 1 }", "{ b = 2 }", "true")
                ),
                Sample(
                    source = "return { { 1, flag = true }, { [k] = v }, 0 }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Table(TableKey(Const(1)=Const(1)),TableKeyString(Id(flag)=Const(true)))),TableKey(Const(2)=Table(TableKey(Id(k)=Id(v)))),TableKey(Const(3)=Const(0))))])",
                    printedFragments = listOf("{ 1, flag = true }", "{ [k] = v }", "0")
                ),
                Sample(
                    source = "return { { { 1 } }, 0 }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Table(TableKey(Const(1)=Table(TableKey(Const(1)=Const(1)))))),TableKey(Const(2)=Const(0))))])",
                    printedFragments = listOf("{ { 1 } }", "0")
                )
            )
        )
    }

    // --- Nested as record field values ---

    @Test
    fun roundTripsNestedTablesAsRecordFieldValues() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return { nested = {} }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(nested)=Table())))])",
                    printedFragments = listOf("{ nested = {} }")
                ),
                Sample(
                    source = "return { nested = { enabled = true } }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(nested)=Table(TableKeyString(Id(enabled)=Const(true))))))])",
                    printedFragments = listOf("nested = { enabled = true }")
                ),
                Sample(
                    source = "return { a = { b = { c = 1 } } }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(a)=Table(TableKeyString(Id(b)=Table(TableKeyString(Id(c)=Const(1))))))))])",
                    printedFragments = listOf("a = { b = { c = 1 } }")
                ),
                Sample(
                    source = "return { left = { 1, 2 }, right = { name = value }, 0 }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(left)=Table(TableKey(Const(1)=Const(1)),TableKey(Const(2)=Const(2)))),TableKeyString(Id(right)=Table(TableKeyString(Id(name)=Id(value)))),TableKey(Const(1)=Const(0))))])",
                    printedFragments = listOf(
                        "left = { 1, 2 }",
                        "right = { name = value }",
                        "0"
                    )
                ),
                Sample(
                    source = "return { make = function(x) return { x = x, nested = { 0 } } end }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(make)=Function(null,Block[Return(Table(TableKeyString(Id(x)=Id(x)),TableKeyString(Id(nested)=Table(TableKey(Const(1)=Const(0))))))]))))])",
                    printedFragments = listOf(
                        "make = function(x)",
                        "return { x = x, nested = { 0 } }"
                    )
                )
            )
        )
    }

    // --- Nested as bracket key and/or value ---

    // --- Multi-level mixed nesting (list + record + general at several depths) ---

    @Test
    fun roundTripsMultiLevelMixedNestedConstructors() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return { outer = { 1, flag = true, [k] = v }, 2, [3] = three }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(outer)=Table(TableKey(Const(1)=Const(1)),TableKeyString(Id(flag)=Const(true)),TableKey(Id(k)=Id(v)))),TableKey(Const(1)=Const(2)),TableKey(Const(3)=Id(three))))])",
                    printedFragments = listOf(
                        "outer = { 1, flag = true, [k] = v }",
                        "2",
                        "[3] = three"
                    )
                ),
                Sample(
                    source = "return { a = { b = { c = { d = 1, 0 }, [x] = y }, 2 }, 3 }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(a)=Table(TableKeyString(Id(b)=Table(TableKeyString(Id(c)=Table(TableKeyString(Id(d)=Const(1)),TableKey(Const(1)=Const(0)))),TableKey(Id(x)=Id(y)))),TableKey(Const(1)=Const(2)))),TableKey(Const(1)=Const(3))))])",
                    printedFragments = listOf(
                        "d = 1",
                        "[x] = y",
                        "2",
                        "3"
                    )
                ),
                Sample(
                    source =
                        "local value = { total = (a + b) * c, { one = 1 }, items[index], ['name'] = other.name }",
                    expectedShape =
                        "Chunk(Block[Local(Id(value)=Table(TableKeyString(Id(total)=Binary(*,Binary(+,Id(a),Id(b)),Id(c))),TableKey(Const(1)=Table(TableKeyString(Id(one)=Const(1)))),TableKey(Const(2)=Index(Id(items)[Id(index)])),TableKey(Const('name')=Member(Id(other).name))))])",
                    printedFragments = listOf(
                        "total = (a + b) * c",
                        "{ one = 1 }",
                        "items[index]",
                        "['name'] = other.name"
                    )
                ),
                Sample(
                    source =
                        "return root.child[1 + offset]:call('x', { nested = values[2], flag = true })",
                    expectedShape =
                        "Chunk(Block[Return(Call(Member(Index(Member(Id(root).child)[Binary(+,Const(1),Id(offset))]):call):Const('x'),Table(TableKeyString(Id(nested)=Index(Id(values)[Const(2)])),TableKeyString(Id(flag)=Const(true)))))])",
                    printedFragments = listOf(
                        "nested = values[2]",
                        "flag = true"
                    )
                ),
                Sample(
                    source =
                        "return { tree = { left = { value = 1, children = { 0 } }, right = { value = 2, [\"meta\"] = { ok = true } } }, root = 0 }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(tree)=Table(TableKeyString(Id(left)=Table(TableKeyString(Id(value)=Const(1)),TableKeyString(Id(children)=Table(TableKey(Const(1)=Const(0)))))),TableKeyString(Id(right)=Table(TableKeyString(Id(value)=Const(2)),TableKey(Const(\"meta\")=Table(TableKeyString(Id(ok)=Const(true)))))))),TableKeyString(Id(root)=Const(0))))])",
                    printedFragments = listOf(
                        "left = { value = 1, children = { 0 } }",
                        "right = { value = 2, [\"meta\"] = { ok = true } }",
                        "root = 0"
                    )
                ),
                Sample(
                    source = "return { f = function(x) return { x, y = { z = x }, 0 } end }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(f)=Function(null,Block[Return(Table(TableKey(Const(1)=Id(x)),TableKeyString(Id(y)=Table(TableKeyString(Id(z)=Id(x)))),TableKey(Const(2)=Const(0))))]))))])",
                    printedFragments = listOf(
                        "f = function(x)",
                        "return { x, y = { z = x }, 0 }"
                    )
                )
            )
        )
    }

    // --- Nested separators / empty nested constructors ---

    // --- Nested tables inside statement contexts ---

    // --- AST field-kind / parent checks after reparse of nested constructors ---

    @Test
    fun roundTripsBulkNestedTableCorpusWithoutShapeDrift() {
        val samples = listOf(
            "return { {} }",
            "return { { 1 }, { 2 }, 0 }",
            "return { { a = 1 }, { b = 2 }, true }",
            "return { { 1, flag = true }, { [k] = v }, 0 }",
            "return { { { 1 } }, 0 }",
            "return { nested = {} }",
            "return { nested = { enabled = true } }",
            "return { a = { b = { c = 1 } } }",
            "return { left = { 1, 2 }, right = { name = value }, 0 }",
            "return { make = function(x) return { x = x, nested = { 0 } } end }",
            "return { [{}] = 1 }",
            "return { [{ k = v }] = 1 }",
            "return { [key] = { nested = true } }",
            "return { [{ a = 1 }] = { b = 2 } }",
            "return { [1] = { [2] = { [3] = 'deep' } } }",
            "return { [{ 1, flag = true }] = next, 0 }",
            "return { outer = { 1, flag = true, [k] = v }, 2, [3] = three }",
            "return { a = { b = { c = { d = 1, 0 }, [x] = y }, 2 }, 3 }",
            "local value = { total = (a + b) * c, { one = 1 }, items[index], ['name'] = other.name }",
            "return root.child[1 + offset]:call('x', { nested = values[2], flag = true })",
            "return { tree = { left = { value = 1, children = { 0 } }, right = { value = 2, [\"meta\"] = { ok = true } } }, root = 0 }",
            "return { f = function(x) return { x, y = { z = x }, 0 } end }",
            "return { nested = { name = value, }, }",
            "return { nested = { name = value; }; }",
            "return { { a = 1; b = 2, }, 0, }",
            "return { a = { b  =  2  ;  [k]=v }, 0 }",
            "return { {}, { }, nested = {}, 0 }",
            "local t = { a = { b = 1 }, 2, [k] = { v = 3 } }",
            "t = { first = { 1 }, name = { value = true } }",
            "function pack(...) return { nested = { ... }, 0 } end",
            "function module.options() return { defaults = { enabled = true }, 0 } end",
            "for k, v in pairs({ a = { 1, 2 }, nested = {} }) do end",
            "return print { value = 1, nested = { 2, 3 } }",
            "local M = { name = \"module\", [1] = \"first\", options = { enabled = true, nested = { 0 } } }",
            "return { outer = { name = value, [key] = other, third, 0 }, [\"meta\"] = { ok = true }, 1 }",
            "local t = { a, nested = { x, y = 2, z, 0 }, b, 1 }",
            "return { outer = { [1] = 'one', [2] = 'two' } }",
            "return { outer = { 'one', 'two' } }",
            "return { l1 = { l2 = { l3 = { 1, a = 2, [k] = 3, 0 }, mid = true }, 4 }, 5 }",
            "return { a = { b = { c = 1 } }, [0] = z, w, true }",
            "return { f = function(x) return { x, y = x } end }",
            "return { cfg = { ports = { 80, 443 }, tls = { enabled = true, alpn = { \"h2\", \"http/1.1\", 0 } } }, 0 }"
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
