package parser.lua53

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.parser.compactCallArguments

import io.github.dingyi222666.luaparser.parser.compactCallBase
import io.github.dingyi222666.luaparser.parser.isCompactShortCall
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.firstStatement
import parser.parse
import parser.renderShape
import parser.returnExpression

/**
 * Lua 5.3 table-constructor + call AST shape corpus.
 *
 * Complements [parser.ast.CompactCallAstShapeTddTest] by documenting the broader
 * family of table-call forms (statement/expression positions, bases, field shapes,
 * nesting, and compact multi-arg continuations) that the Lua 5.3 parser emits.
 *
 * Documented outer-wrapper convention (same as compact short-call helpers):
 * - `callee { fields }`  -> `Call(TableCall(callee:Table(...)):)`
 * - `callee { fields }, x` -> `Call(TableCall(callee:Table(...)):Id(x))`
 * - `callee({ fields })` stays a plain parenthesized call (not compact short-call).
 */
class Lua53TableCallShapeCorpusTddTest {

    @Test
    fun documentsSimpleIdentifierTableCallShapes() {
        assertCaseShapes(
            "statement empty table call" to (
                "print {}" to
                    "Chunk(Block[CallStmt(Call(TableCall(Id(print):Table()):))])"
            ),
            "statement single string field" to (
                "print { value = 1 }" to
                    "Chunk(Block[CallStmt(Call(TableCall(Id(print):Table(TableKeyString(Id(value)=Const(1)))):))])"
            ),
            "return empty table call" to (
                "return print {}" to
                    "Chunk(Block[Return(Call(TableCall(Id(print):Table()):))])"
            ),
            "return single array field" to (
                "return print { 1 }" to
                    "Chunk(Block[Return(Call(TableCall(Id(print):Table(TableKey(Const(1)=Const(1)))):))])"
            ),
            "return single string field" to (
                "return factory { name = value }" to
                    "Chunk(Block[Return(Call(TableCall(Id(factory):Table(TableKeyString(Id(name)=Id(value)))):))])"
            ),
            "local initialized by table call" to (
                "local widget = Button { text = \"Save\" }" to
                    "Chunk(Block[Local(Id(widget)=Call(TableCall(Id(Button):Table(TableKeyString(Id(text)=Const(\"Save\")))):))])"
            ),
            "assignment target from table call" to (
                "widget = Button { text = \"Save\" }" to
                    "Chunk(Block[Assign(Id(widget)=Call(TableCall(Id(Button):Table(TableKeyString(Id(text)=Const(\"Save\")))):))])"
            )
        )
    }

    @Test
    fun documentsTableFieldVariantsInsideTableCalls() {
        assertCaseShapes(
            "bracketed field" to (
                "return factory { [key] = value }" to
                    "Chunk(Block[Return(Call(TableCall(Id(factory):Table(TableKey(Id(key)=Id(value)))):))])"
            ),
            "numeric bracketed field" to (
                "return factory { [1] = 'one' }" to
                    "Chunk(Block[Return(Call(TableCall(Id(factory):Table(TableKey(Const(1)=Const('one')))):))])"
            ),
            "mixed string bracketed and array fields" to (
                "return factory { name = value, [key] = other, third }" to
                    "Chunk(Block[Return(Call(TableCall(Id(factory):Table(TableKeyString(Id(name)=Id(value)),TableKey(Id(key)=Id(other)),TableKey(Const(1)=Id(third)))):))])"
            ),
            "trailing comma after string field" to (
                "return factory { name = value, }" to
                    "Chunk(Block[Return(Call(TableCall(Id(factory):Table(TableKeyString(Id(name)=Id(value)))):))])"
            ),
            "trailing semicolon after array field" to (
                "return factory { value; }" to
                    "Chunk(Block[Return(Call(TableCall(Id(factory):Table(TableKey(Const(1)=Id(value)))):))])"
            ),
            "nested constructor field" to (
                "return factory { nested = { enabled = true } }" to
                    "Chunk(Block[Return(Call(TableCall(Id(factory):Table(TableKeyString(Id(nested)=Table(TableKeyString(Id(enabled)=Const(true)))))):))])"
            ),
            "function field" to (
                "return factory { make = function(value) return value end }" to
                    "Chunk(Block[Return(Call(TableCall(Id(factory):Table(TableKeyString(Id(make)=Function(null,Block[Return(Id(value))])))):))])"
            ),
            "call expression field" to (
                "return factory { value = seed() }" to
                    "Chunk(Block[Return(Call(TableCall(Id(factory):Table(TableKeyString(Id(value)=Call(Id(seed):)))):))])"
            )
        )
    }

    @Test
    fun documentsMemberIndexAndCallBasesForTableCalls() {
        assertCaseShapes(
            "dotted member base" to (
                "return module.make { name = value }" to
                    "Chunk(Block[Return(Call(TableCall(Member(Id(module).make):Table(TableKeyString(Id(name)=Id(value)))):))])"
            ),
            "colon method base" to (
                "return module:make { name = value }" to
                    "Chunk(Block[Return(Call(TableCall(Member(Id(module):make):Table(TableKeyString(Id(name)=Id(value)))):))])"
            ),
            "index base" to (
                "return widgets[1] { text = \"Save\" }" to
                    "Chunk(Block[Return(Call(TableCall(Index(Id(widgets)[Const(1)]):Table(TableKeyString(Id(text)=Const(\"Save\")))):))])"
            ),
            "nested member then index base" to (
                "return root.child[1] { text = \"Save\" }" to
                    "Chunk(Block[Return(Call(TableCall(Index(Member(Id(root).child)[Const(1)]):Table(TableKeyString(Id(text)=Const(\"Save\")))):))])"
            ),
            "call result base then table call" to (
                "return factory() { name = value }" to
                    "Chunk(Block[Return(Call(TableCall(Call(Id(factory):):Table(TableKeyString(Id(name)=Id(value)))):))])"
            ),
            "call result member then table call" to (
                "return factory().create { name = value }" to
                    "Chunk(Block[Return(Call(TableCall(Member(Call(Id(factory):).create):Table(TableKeyString(Id(name)=Id(value)))):))])"
            ),
            "statement member table call" to (
                "activity.setContentView { LinearLayout }" to
                    "Chunk(Block[CallStmt(Call(TableCall(Member(Id(activity).setContentView):Table(TableKey(Const(1)=Id(LinearLayout)))):))])"
            ),
            "statement index table call" to (
                "widgets[1] { text = \"mutate\" }" to
                    "Chunk(Block[CallStmt(Call(TableCall(Index(Id(widgets)[Const(1)]):Table(TableKeyString(Id(text)=Const(\"mutate\")))):))])"
            )
        )
    }

    @Test
    fun documentsCompactMultiArgTableCallContinuations() {
        // Outer CallExpression wraps TableCallExpression for the short first arg,
        // then keeps comma-separated continuations on the outer call.arguments.
        assertCaseShapes(
            "compact table call plus identifier" to (
                "return widgets[1] { text = \"Save\" }, options" to
                    "Chunk(Block[Return(Call(TableCall(Index(Id(widgets)[Const(1)]):Table(TableKeyString(Id(text)=Const(\"Save\")))):Id(options)))])"
            ),
            "compact table call plus table and identifier" to (
                "return factory { id = 1 }, { extra = true }, count" to
                    "Chunk(Block[Return(Call(TableCall(Id(factory):Table(TableKeyString(Id(id)=Const(1)))):Table(TableKeyString(Id(extra)=Const(true))),Id(count)))])"
            ),
            "compact table call plus string" to (
                "return factory { name = value }, \"tag\"" to
                    "Chunk(Block[Return(Call(TableCall(Id(factory):Table(TableKeyString(Id(name)=Id(value)))):Const(\"tag\")))])"
            ),
            "statement compact table call plus option" to (
                "factory { name = value }, options" to
                    "Chunk(Block[CallStmt(Call(TableCall(Id(factory):Table(TableKeyString(Id(name)=Id(value)))):Id(options)))])"
            ),
            "member compact table call plus option" to (
                "return module.make { name = value }, options" to
                    "Chunk(Block[Return(Call(TableCall(Member(Id(module).make):Table(TableKeyString(Id(name)=Id(value)))):Id(options)))])"
            )
        )
    }

    @Test
    fun contrastsParenthesizedTableArgCallsWithCompactTableCalls() {
        assertCaseShapes(
            "parenthesized single table argument" to (
                "return factory({ name = value })" to
                    "Chunk(Block[Return(Call(Id(factory):Table(TableKeyString(Id(name)=Id(value)))))])"
            ),
            "parenthesized table plus extra argument" to (
                "return factory({ name = value }, options)" to
                    "Chunk(Block[Return(Call(Id(factory):Table(TableKeyString(Id(name)=Id(value))),Id(options)))])"
            ),
            "parenthesized empty table argument" to (
                "return factory({})" to
                    "Chunk(Block[Return(Call(Id(factory):Table()))])"
            ),
            "statement parenthesized table argument" to (
                "factory({ name = value })" to
                    "Chunk(Block[CallStmt(Call(Id(factory):Table(TableKeyString(Id(name)=Id(value)))))])"
            )
        )

        val plain = assertIs<CallExpression>(
            parse(LuaVersion.LUA_5_3, "return factory({ name = value }, options)").returnExpression()
        )
        assertFalse(plain.isCompactShortCall())
        assertIs<Identifier>(plain.compactCallBase())
        assertEquals(plain.arguments, plain.compactCallArguments())
        assertEquals(
            listOf(
                "Table(TableKeyString(Id(name)=Id(value)))",
                "Id(options)"
            ),
            plain.arguments.map(::renderShape)
        )

        val compact = assertIs<CallExpression>(
            parse(LuaVersion.LUA_5_3, "return factory { name = value }, options").returnExpression()
        )
        assertTrue(compact.isCompactShortCall())
        assertIs<TableCallExpression>(compact.base)
        assertEquals("Id(factory)", renderShape(compact.compactCallBase()))
        assertEquals(
            listOf(
                "Table(TableKeyString(Id(name)=Id(value)))",
                "Id(options)"
            ),
            compact.compactCallArguments().map(::renderShape)
        )
    }

    @Test
    fun exposesNodeTypesParentsAndCompactHelpersForRepresentativeTableCalls() {
        val simple = assertIs<CallExpression>(
            parse(LuaVersion.LUA_5_3, "return factory { name = value }").returnExpression()
        )
        assertTrue(simple.isCompactShortCall())
        val simpleTableCall = assertIs<TableCallExpression>(simple.base)
        assertSame(simpleTableCall.base, simple.compactCallBase())
        assertEquals("factory", assertIs<Identifier>(simple.compactCallBase()).name)
        assertEquals(0, simple.arguments.size)
        assertEquals(1, simple.compactCallArguments().size)
        val simpleArg = assertIs<TableConstructorExpression>(simple.compactCallArguments().single())
        assertEquals(1, simpleArg.fields.size)
        assertIs<TableKeyString>(simpleArg.fields.single())
        assertEquals(simple, simpleTableCall.parent)
        assertEquals(simpleTableCall, simpleTableCall.base.parent)
        assertEquals(simpleTableCall, simpleArg.parent)

        val indexed = assertIs<CallExpression>(
            parse(LuaVersion.LUA_5_3, """return widgets[1] { text = "Save" }, options""").returnExpression()
        )
        assertTrue(indexed.isCompactShortCall())
        val indexedTableCall = assertIs<TableCallExpression>(indexed.base)
        val indexBase = assertIs<IndexExpression>(indexed.compactCallBase())
        assertSame(indexedTableCall.base, indexBase)
        assertEquals("widgets", assertIs<Identifier>(indexBase.base).name)
        assertEquals("Const(1)", renderShape(indexBase.index))
        assertContentEquals(
            listOf(
                "Table(TableKeyString(Id(text)=Const(\"Save\")))",
                "Id(options)"
            ),
            indexed.compactCallArguments().map(::renderShape)
        )
        assertIs<TableConstructorExpression>(indexed.compactCallArguments()[0])
        assertEquals("options", assertIs<Identifier>(indexed.compactCallArguments()[1]).name)
        assertEquals(1, indexed.arguments.size)

        val method = assertIs<CallExpression>(
            parse(LuaVersion.LUA_5_3, "return module:make { name = value }").returnExpression()
        )
        val methodBase = assertIs<MemberExpression>(method.compactCallBase())
        assertEquals(":", methodBase.indexer)
        assertEquals("module", assertIs<Identifier>(methodBase.base).name)
        assertEquals("make", methodBase.identifier.name)

        val statement = parse(LuaVersion.LUA_5_3, """Button { text = "Save" }""")
            .firstStatement<CallStatement>()
        val statementCall = assertIs<CallExpression>(statement.expression)
        assertTrue(statementCall.isCompactShortCall())
        assertIs<TableCallExpression>(statementCall.base)
        assertEquals("Button", assertIs<Identifier>(statementCall.compactCallBase()).name)
        val buttonTable = assertIs<TableConstructorExpression>(statementCall.compactCallArguments().single())
        assertEquals("Const(\"Save\")", renderShape(buttonTable.fields.single().value))
        assertIs<ConstantNode>(buttonTable.fields.single().value)

        val local = parse(LuaVersion.LUA_5_3, """local widget = Button { text = "Save" }""")
            .firstStatement<LocalStatement>()
        val localCall = assertIs<CallExpression>(local.variables.single())
        assertIs<TableCallExpression>(localCall.base)
        assertEquals(
            "Call(TableCall(Id(Button):Table(TableKeyString(Id(text)=Const(\"Save\")))):)",
            renderShape(localCall)
        )
    }

    @Test
    fun documentsNestedAndChainedTableCallCombos() {
        assertCaseShapes(
            "table field value is itself a table call" to (
                "return { child = Button { text = \"Save\" } }" to
                    "Chunk(Block[Return(Table(TableKeyString(Id(child)=Call(TableCall(Id(Button):Table(TableKeyString(Id(text)=Const(\"Save\")))):))))])"
            ),
            "table call argument contains nested table call field" to (
                "return layout { content = TextView { text = \"Hi\" } }" to
                    "Chunk(Block[Return(Call(TableCall(Id(layout):Table(TableKeyString(Id(content)=Call(TableCall(Id(TextView):Table(TableKeyString(Id(text)=Const(\"Hi\")))):)))):))])"
            ),
            "array-style nested constructors inside table call" to (
                "return layout { LinearLayout, { TextView, text = \"Hi\" } }" to
                    "Chunk(Block[Return(Call(TableCall(Id(layout):Table(TableKey(Const(1)=Id(LinearLayout)),TableKey(Const(2)=Table(TableKey(Const(1)=Id(TextView)),TableKeyString(Id(text)=Const(\"Hi\")))))):))])"
            ),
            "chained table call on previous table-call result" to (
                "return factory { kind = \"a\" } { kind = \"b\" }" to
                    "Chunk(Block[Return(Call(TableCall(Call(TableCall(Id(factory):Table(TableKeyString(Id(kind)=Const(\"a\")))):):Table(TableKeyString(Id(kind)=Const(\"b\")))):))])"
            ),
            "require short string then member table call" to (
                "return require \"pkg\".create { name = value }" to
                    "Chunk(Block[Return(Call(TableCall(Member(Call(StringCall(Id(require):Const(\"pkg\")):).create):Table(TableKeyString(Id(name)=Id(value)))):))])"
            )
        )
    }

    @Test
    fun recordsCorpusBreadthForTableCallCombos() {
        val totalCases =
            simpleIdentifierCases.size +
                fieldVariantCases.size +
                baseVariantCases.size +
                compactContinuationCases.size +
                parenthesizedContrastCases.size +
                nestedChainedCases.size

        assertTrue(
            totalCases >= 30,
            "Expected at least 30 documented table-call shape cases, got $totalCases"
        )
    }

    private val simpleIdentifierCases = listOf(
        "print {}",
        "print { value = 1 }",
        "return print {}",
        "return print { 1 }",
        "return factory { name = value }",
        "local widget = Button { text = \"Save\" }",
        "widget = Button { text = \"Save\" }"
    )

    private val fieldVariantCases = listOf(
        "return factory { [key] = value }",
        "return factory { [1] = 'one' }",
        "return factory { name = value, [key] = other, third }",
        "return factory { name = value, }",
        "return factory { value; }",
        "return factory { nested = { enabled = true } }",
        "return factory { make = function(value) return value end }",
        "return factory { value = seed() }"
    )

    private val baseVariantCases = listOf(
        "return module.make { name = value }",
        "return module:make { name = value }",
        "return widgets[1] { text = \"Save\" }",
        "return root.child[1] { text = \"Save\" }",
        "return factory() { name = value }",
        "return factory().create { name = value }",
        "activity.setContentView { LinearLayout }",
        "widgets[1] { text = \"mutate\" }"
    )

    private val compactContinuationCases = listOf(
        "return widgets[1] { text = \"Save\" }, options",
        "return factory { id = 1 }, { extra = true }, count",
        "return factory { name = value }, \"tag\"",
        "factory { name = value }, options",
        "return module.make { name = value }, options"
    )

    private val parenthesizedContrastCases = listOf(
        "return factory({ name = value })",
        "return factory({ name = value }, options)",
        "return factory({})",
        "factory({ name = value })"
    )

    private val nestedChainedCases = listOf(
        "return { child = Button { text = \"Save\" } }",
        "return layout { content = TextView { text = \"Hi\" } }",
        "return layout { LinearLayout, { TextView, text = \"Hi\" } }",
        "return factory { kind = \"a\" } { kind = \"b\" }",
        "return require \"pkg\".create { name = value }"
    )

    private fun assertCaseShapes(vararg cases: Pair<String, Pair<String, String>>) {
        val failures = cases.mapNotNull { (name, case) ->
            val (source, expectedShape) = case
            runCatching {
                assertEquals(expectedShape, renderShape(parse(LuaVersion.LUA_5_3, source)), name)
            }.exceptionOrNull()?.let { failure ->
                "$name\nsource: $source\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }
}
