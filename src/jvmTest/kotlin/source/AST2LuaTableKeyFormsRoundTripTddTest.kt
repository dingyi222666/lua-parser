package source

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
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
 * AST2Lua round-trip corpus focused on Lua 5.3 table constructor key forms (TASK-304).
 *
 * Covers the three field productions from the Lua 5.3 grammar:
 *   field ::= '[' exp ']' '=' exp | Name '=' exp | exp
 *
 * ## Shape-stable print policy
 *
 * Round-trip acceptance is **AST-shape stable**, not byte-identical to the input source.
 *
 * 1. **Acceptance metric** — `parse(source)` → `AST2Lua.asCode` → reparse must yield the same
 *    `renderShape` as the original parse. Whitespace, indentation, and cosmetic spacing may
 *    change; structural field kinds and ordering must not.
 * 2. **List / array fields** (`exp`) — modeled as [TableKey] with an implicit integer key
 *    (`Const(1)`, `Const(2)`, …) whose key range is zero-width. Printer emits only the value
 *    (no `[n] =` surface). Implicit indices restart at 1 for each constructor and skip over
 *    explicit keyed fields (Name / bracket keys do not consume array slots).
 * 3. **Record fields** (`Name '=' exp`) — modeled as [TableKeyString] with an [Identifier]
 *    key. Printer emits `name = value` (single spaces around `=`).
 * 4. **General / bracket fields** (`'[' exp ']' '=' exp`) — modeled as [TableKey] with an
 *    explicit expression key (identifier, constant, binary, call, nested table, etc.). Printer
 *    emits `[key] = value`. Numeric / string bracket keys stay bracketed; they are **not**
 *    rewritten to Name-key or bare-list form even when the key is a plain integer or identifier.
 * 5. **Field separators** — input may use `,` or `;` (including trailing separators). Printer
 *    normalizes to comma-separated fields with single spaces: `{ a, b = c, [k] = v }`. Trailing
 *    separators are dropped. Empty constructors print as `{}` (no interior space).
 * 6. **Nested constructors** — nested tables retain their own field-kind sequence under shape
 *    reparse; mixed outer/inner list+record+general forms must not collapse.
 * 7. **Version** — all samples use [LuaVersion.LUA_5_3].
 * 8. **Out of scope** — comment preservation, recovery of malformed tables, AndroLua array
 *    constructors (`[1, 2]`), and semantic key uniqueness are not asserted here.
 */
class AST2LuaTableKeyFormsRoundTripTddTest {

    private val printer = AST2Lua()
    private val version = LuaVersion.LUA_5_3

    // --- List / array (implicit integer keys) ---

    @Test
    fun roundTripsListFieldForms() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return {}",
                    expectedShape = "Chunk(Block[Return(Table())])",
                    printedFragments = listOf("{}")
                ),
                Sample(
                    source = "return { 1 }",
                    expectedShape = "Chunk(Block[Return(Table(TableKey(Const(1)=Const(1))))])",
                    printedFragments = listOf("{ 1 }")
                ),
                Sample(
                    source = "return { a }",
                    expectedShape = "Chunk(Block[Return(Table(TableKey(Const(1)=Id(a))))])",
                    printedFragments = listOf("{ a }")
                ),
                Sample(
                    source = "return { 1, 2, 3 }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Const(1)),TableKey(Const(2)=Const(2)),TableKey(Const(3)=Const(3))))])",
                    printedFragments = listOf("{ 1, 2, 3 }")
                ),
                Sample(
                    source = "return { a, b, c }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Id(a)),TableKey(Const(2)=Id(b)),TableKey(Const(3)=Id(c))))])",
                    printedFragments = listOf("{ a, b, c }")
                ),
                Sample(
                    source = "return { a + b, not ready, #items }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Binary(+,Id(a),Id(b))),TableKey(Const(2)=Unary(not,Id(ready))),TableKey(Const(3)=Unary(#,Id(items)))))])",
                    printedFragments = listOf("a + b", "not ready", "#items")
                ),
                Sample(
                    source = "return { factory(seed), object.member, items[i] }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Call(Id(factory):Id(seed))),TableKey(Const(2)=Member(Id(object).member)),TableKey(Const(3)=Index(Id(items)[Id(i)]))))])",
                    printedFragments = listOf("factory(seed)", "object.member", "items[i]")
                ),
                Sample(
                    source = "return { ... }",
                    expectedShape = "Chunk(Block[Return(Table(TableKey(Const(1)=Vararg)))])",
                    printedFragments = listOf("{ ... }")
                ),
                Sample(
                    source = "return { function() return 1 end }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Function(null,Block[Return(Const(1))]))))])",
                    printedFragments = listOf("function()", "return 1", "end")
                )
            )
        )
    }

    // --- Record (Name = exp) ---

    @Test
    fun roundTripsRecordFieldForms() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return { name = value }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(name)=Id(value))))])",
                    printedFragments = listOf("{ name = value }")
                ),
                Sample(
                    source = "return { a = 1, b = 2 }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(a)=Const(1)),TableKeyString(Id(b)=Const(2))))])",
                    printedFragments = listOf("{ a = 1, b = 2 }")
                ),
                Sample(
                    source = "return { enabled = true, count = 0, label = \"ok\" }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(enabled)=Const(true)),TableKeyString(Id(count)=Const(0)),TableKeyString(Id(label)=Const(\"ok\"))))])",
                    printedFragments = listOf("enabled = true", "count = 0", "label = \"ok\"")
                ),
                Sample(
                    source = "return { value = factory(seed) }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(value)=Call(Id(factory):Id(seed)))))])",
                    printedFragments = listOf("value = factory(seed)")
                ),
                Sample(
                    source = "return { value = object.member }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(value)=Member(Id(object).member))))])",
                    printedFragments = listOf("value = object.member")
                ),
                Sample(
                    source = "return { make = function(value) return value end }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(make)=Function(null,Block[Return(Id(value))]))))])",
                    printedFragments = listOf("make = function(value)", "return value")
                ),
                Sample(
                    source = "return { nested = { enabled = true } }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(nested)=Table(TableKeyString(Id(enabled)=Const(true))))))])",
                    printedFragments = listOf("nested = { enabled = true }")
                )
            )
        )
    }

    // --- General / bracket ([exp] = exp) ---

    @Test
    fun roundTripsGeneralBracketFieldForms() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return { [key] = value }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Id(key)=Id(value))))])",
                    printedFragments = listOf("{ [key] = value }")
                ),
                Sample(
                    source = "return { [1] = 'one' }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Const('one'))))])",
                    printedFragments = listOf("{ [1] = 'one' }")
                ),
                Sample(
                    source = "return { [\"name\"] = value }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(\"name\")=Id(value))))])",
                    printedFragments = listOf("[\"name\"] = value")
                ),
                Sample(
                    source = "return { [prefix .. suffix] = value }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Binary(..,Id(prefix),Id(suffix))=Id(value))))])",
                    printedFragments = listOf("[prefix .. suffix] = value")
                ),
                Sample(
                    source = "return { [a + b] = c * d }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Binary(+,Id(a),Id(b))=Binary(*,Id(c),Id(d)))))])",
                    printedFragments = listOf("[a + b] = c * d")
                ),
                Sample(
                    source = "return { [items[i]] = next }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Index(Id(items)[Id(i)])=Id(next))))])",
                    printedFragments = listOf("[items[i]] = next")
                ),
                Sample(
                    source = "return { [factory(seed)] = object.member }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Call(Id(factory):Id(seed))=Member(Id(object).member))))])",
                    printedFragments = listOf("[factory(seed)] = object.member")
                ),
                Sample(
                    source = "return { [true] = 1, [false] = 0 }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(true)=Const(1)),TableKey(Const(false)=Const(0))))])",
                    printedFragments = listOf("[true] = 1", "[false] = 0")
                ),
                // Explicit integer bracket keys must stay bracketed (not rewritten to list form).
                Sample(
                    source = "return { [2] = 'second', [1] = 'first' }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(2)=Const('second')),TableKey(Const(1)=Const('first'))))])",
                    printedFragments = listOf("[2] = 'second'", "[1] = 'first'")
                )
            )
        )
    }

    // --- Mixed constructors retain field-kind order / shape ---

    @Test
    fun roundTripsMixedListRecordGeneralConstructors() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return { name = value, [key] = other, third }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(name)=Id(value)),TableKey(Id(key)=Id(other)),TableKey(Const(1)=Id(third))))])",
                    printedFragments = listOf("name = value", "[key] = other", "third")
                ),
                Sample(
                    source = "return { first, name = value, [2] = second }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Id(first)),TableKeyString(Id(name)=Id(value)),TableKey(Const(2)=Id(second))))])",
                    printedFragments = listOf("first", "name = value", "[2] = second")
                ),
                Sample(
                    source = "return { a, b = 2, c, [k] = v, d }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Id(a)),TableKeyString(Id(b)=Const(2)),TableKey(Const(2)=Id(c)),TableKey(Id(k)=Id(v)),TableKey(Const(3)=Id(d))))])",
                    printedFragments = listOf("a", "b = 2", "c", "[k] = v", "d")
                ),
                Sample(
                    source = "local value = { total = (a + b) * c, { one = 1 }, items[index], ['name'] = other.name }",
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
                    source = "return root.child[1 + offset]:call('x', { nested = values[2], flag = true })",
                    expectedShape =
                        "Chunk(Block[Return(Call(Member(Index(Member(Id(root).child)[Binary(+,Const(1),Id(offset))]):call):Const('x'),Table(TableKeyString(Id(nested)=Index(Id(values)[Const(2)])),TableKeyString(Id(flag)=Const(true)))))])",
                    printedFragments = listOf(
                        "nested = values[2]",
                        "flag = true"
                    )
                )
            )
        )
    }

    // --- Separator normalization (comma / semicolon / trailing) ---

    @Test
    fun normalizesFieldSeparatorsWhilePreservingShape() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "return { name = value, }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(name)=Id(value))))])",
                    printedFragments = listOf("{ name = value }")
                ),
                Sample(
                    source = "return { name = value; }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKeyString(Id(name)=Id(value))))])",
                    printedFragments = listOf("{ name = value }")
                ),
                Sample(
                    source = "return { [key] = value, }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Id(key)=Id(value))))])",
                    printedFragments = listOf("{ [key] = value }")
                ),
                Sample(
                    source = "return { [key] = value; }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Id(key)=Id(value))))])",
                    printedFragments = listOf("{ [key] = value }")
                ),
                Sample(
                    source = "return { value, }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Id(value))))])",
                    printedFragments = listOf("{ value }")
                ),
                Sample(
                    source = "return { value; }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Id(value))))])",
                    printedFragments = listOf("{ value }")
                ),
                Sample(
                    source = "return { a; b = 2, [k] = v; c, }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Id(a)),TableKeyString(Id(b)=Const(2)),TableKey(Id(k)=Id(v)),TableKey(Const(2)=Id(c))))])",
                    printedFragments = listOf("{ a, b = 2, [k] = v, c }")
                ),
                Sample(
                    source = "return { a , b  =  2  ;  [k]=v }",
                    expectedShape =
                        "Chunk(Block[Return(Table(TableKey(Const(1)=Id(a)),TableKeyString(Id(b)=Const(2)),TableKey(Id(k)=Id(v))))])",
                    printedFragments = listOf("{ a, b = 2, [k] = v }")
                )
            )
        )
    }

    // --- Statement contexts ---

    @Test
    fun roundTripsTableKeyFormsInsideStatementContexts() {
        assertRoundTrips(
            listOf(
                Sample(
                    source = "local t = { a = 1, 2, [k] = v }",
                    expectedShape =
                        "Chunk(Block[Local(Id(t)=Table(TableKeyString(Id(a)=Const(1)),TableKey(Const(1)=Const(2)),TableKey(Id(k)=Id(v))))])",
                    printedFragments = listOf("local t = { a = 1, 2, [k] = v }")
                ),
                Sample(
                    source = "t = { first, name = value }",
                    expectedShape =
                        "Chunk(Block[Assign(Id(t)=Table(TableKey(Const(1)=Id(first)),TableKeyString(Id(name)=Id(value))))])",
                    printedFragments = listOf("t = { first, name = value }")
                ),
                Sample(
                    source = "function pack(...) return { ... } end",
                    expectedShape =
                        "Chunk(Block[Function(Id(pack),Block[Return(Table(TableKey(Const(1)=Vararg)))])])",
                    printedFragments = listOf("return { ... }")
                ),
                Sample(
                    source = "function module.options() return { enabled = true } end",
                    expectedShape =
                        "Chunk(Block[Function(Member(Id(module).options),Block[Return(Table(TableKeyString(Id(enabled)=Const(true))))])])",
                    printedFragments = listOf("return { enabled = true }")
                ),
                Sample(
                    source = "for k, v in pairs({ a = 1, 2 }) do end",
                    expectedShape =
                        "Chunk(Block[ForGeneric(Id(k),Id(v) in Call(Id(pairs):Table(TableKeyString(Id(a)=Const(1)),TableKey(Const(1)=Const(2)))):Block[])])",
                    printedFragments = listOf("pairs({ a = 1, 2 })")
                ),
                Sample(
                    source = "return print { value = 1, nested = { 2, 3 } }",
                    expectedShape =
                        "Chunk(Block[Return(Call(TableCall(Id(print):Table(TableKeyString(Id(value)=Const(1)),TableKeyString(Id(nested)=Table(TableKey(Const(1)=Const(2)),TableKey(Const(2)=Const(3)))))):))])",
                    printedFragments = listOf("print { value = 1, nested = { 2, 3 } }")
                )
            )
        )
    }

    // --- AST field-kind / parent checks after reparse ---

    @Test
    fun reparsePreservesFieldKindsOrderingAndParents() {
        val source = "return { name = value, [key] = other, third, [\"x\"] = 1 }"
        val printed = printer.asCode(LuaParser(luaVersion = version).parse(source))
        val chunk = LuaParser(luaVersion = version).parse(printed)
        val ret = assertIs<ReturnStatement>(chunk.body.returnStatement)
        val table = assertIs<TableConstructorExpression>(ret.arguments.single())

        assertEquals(4, table.fields.size)
        assertIs<TableKeyString>(table.fields[0])
        assertIs<TableKey>(table.fields[1])
        assertIs<TableKey>(table.fields[2])
        assertIs<TableKey>(table.fields[3])

        assertContentEquals(
            listOf(
                "TableKeyString(Id(name)=Id(value))",
                "TableKey(Id(key)=Id(other))",
                "TableKey(Const(1)=Id(third))",
                "TableKey(Const(\"x\")=Const(1))"
            ),
            table.fields.map(::renderShape)
        )

        table.fields.forEach { field ->
            assertEquals(table, field.parent, "field parent for ${renderShape(field)}")
            assertEquals(field, field.key.parent, "key parent for ${renderShape(field)}")
            assertEquals(field, field.value.parent, "value parent for ${renderShape(field)}")
        }

        assertTrue(printed.contains("name = value"), "printed:\n$printed")
        assertTrue(printed.contains("[key] = other"), "printed:\n$printed")
        assertTrue(printed.contains("third"), "printed:\n$printed")
        assertTrue(printed.contains("[\"x\"] = 1"), "printed:\n$printed")
    }

    @Test
    fun implicitArrayKeysRemainSyntheticAndDoNotPrintAsBrackets() {
        val source = "local t = { a, b = 2, c }"
        val initial = LuaParser(luaVersion = version).parse(source)
        val printed = printer.asCode(initial)
        val reparsed = LuaParser(luaVersion = version).parse(printed)

        assertEquals(renderShape(initial), renderShape(reparsed), "printed:\n$printed")

        val local = assertIs<LocalStatement>(reparsed.body.statements.single())
        val table = assertIs<TableConstructorExpression>(local.variables.single())
        assertEquals(3, table.fields.size)

        val first = assertIs<TableKey>(table.fields[0])
        val record = assertIs<TableKeyString>(table.fields[1])
        val third = assertIs<TableKey>(table.fields[2])

        val firstKey = assertIs<ConstantNode>(first.key)
        val thirdKey = assertIs<ConstantNode>(third.key)
        assertEquals(ConstantNode.TYPE.INTERGER, firstKey.constantType)
        assertEquals(ConstantNode.TYPE.INTERGER, thirdKey.constantType)
        assertEquals("1", firstKey.rawValue.toString())
        assertEquals("2", thirdKey.rawValue.toString())
        // Synthetic implicit keys have zero-width ranges (start == end).
        assertEquals(firstKey.range.start, firstKey.range.end)
        assertEquals(thirdKey.range.start, thirdKey.range.end)

        assertEquals("b", record.key.name)
        assertTrue(!printed.contains("[1]"), "list fields must not print as brackets:\n$printed")
        assertTrue(!printed.contains("[2]"), "list fields must not print as brackets:\n$printed")
        assertTrue(printed.contains("{ a, b = 2, c }"), "printed:\n$printed")
    }

    @Test
    fun explicitIntegerBracketKeysDoNotCollapseToListFields() {
        // Bracket form with integer keys is a general key, not an implicit array field.
        val bracketed = "return { [1] = 'one', [2] = 'two' }"
        val listed = "return { 'one', 'two' }"

        val bracketShape = renderShape(LuaParser(luaVersion = version).parse(bracketed))
        val listShape = renderShape(LuaParser(luaVersion = version).parse(listed))
        assertEquals(
            "Chunk(Block[Return(Table(TableKey(Const(1)=Const('one')),TableKey(Const(2)=Const('two'))))])",
            bracketShape
        )
        assertEquals(
            "Chunk(Block[Return(Table(TableKey(Const(1)=Const('one')),TableKey(Const(2)=Const('two'))))])",
            listShape
        )
        // Shapes share the same TableKey skeleton for integer keys, but print surface differs:
        // bracket keys re-emit `[n] =`, list keys emit bare values. Round-trip each independently.
        val bracketPrinted = printer.asCode(LuaParser(luaVersion = version).parse(bracketed))
        val listPrinted = printer.asCode(LuaParser(luaVersion = version).parse(listed))
        assertTrue(bracketPrinted.contains("[1] = 'one'"), "printed:\n$bracketPrinted")
        assertTrue(bracketPrinted.contains("[2] = 'two'"), "printed:\n$bracketPrinted")
        assertTrue(listPrinted.contains("{ 'one', 'two' }"), "printed:\n$listPrinted")
        assertTrue(!listPrinted.contains("[1]"), "list form must stay bare:\n$listPrinted")

        assertEquals(
            bracketShape,
            renderShape(LuaParser(luaVersion = version).parse(bracketPrinted))
        )
        assertEquals(
            listShape,
            renderShape(LuaParser(luaVersion = version).parse(listPrinted))
        )
    }

    @Test
    fun roundTripsBulkTableKeyFormsCorpusWithoutShapeDrift() {
        val samples = listOf(
            "return {}",
            "return { 1 }",
            "return { a }",
            "return { 1, 2, 3 }",
            "return { a, b, c }",
            "return { a + b, not ready, #items }",
            "return { factory(seed), object.member, items[i] }",
            "return { ... }",
            "return { function() return 1 end }",
            "return { name = value }",
            "return { a = 1, b = 2 }",
            "return { enabled = true, count = 0, label = \"ok\" }",
            "return { value = factory(seed) }",
            "return { value = object.member }",
            "return { make = function(value) return value end }",
            "return { nested = { enabled = true } }",
            "return { [key] = value }",
            "return { [1] = 'one' }",
            "return { [\"name\"] = value }",
            "return { [prefix .. suffix] = value }",
            "return { [a + b] = c * d }",
            "return { [items[i]] = next }",
            "return { [factory(seed)] = object.member }",
            "return { [true] = 1, [false] = 0 }",
            "return { [2] = 'second', [1] = 'first' }",
            "return { name = value, [key] = other, third }",
            "return { first, name = value, [2] = second }",
            "return { a, b = 2, c, [k] = v, d }",
            "local value = { total = (a + b) * c, { one = 1 }, items[index], ['name'] = other.name }",
            "return { outer = { 1, flag = true, [k] = v }, 2, [3] = three }",
            "return root.child[1 + offset]:call('x', { nested = values[2], flag = true })",
            "return { name = value, }",
            "return { name = value; }",
            "return { [key] = value, }",
            "return { [key] = value; }",
            "return { value, }",
            "return { value; }",
            "return { a; b = 2, [k] = v; c, }",
            "return { a , b  =  2  ;  [k]=v }",
            "local t = { a = 1, 2, [k] = v }",
            "t = { first, name = value }",
            "function pack(...) return { ... } end",
            "function module.options() return { enabled = true } end",
            "for k, v in pairs({ a = 1, 2 }) do end",
            "return print { value = 1, nested = { 2, 3 } }",
            "return { [1] = 'one', [2] = 'two' }",
            "return { 'one', 'two' }",
            "return { a = { b = { c = 1 } }, [0] = z, w }",
            "return { f = function(x) return { x, y = x } end }",
            "local M = { name = \"module\", [1] = \"first\", options = { enabled = true } }"
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
