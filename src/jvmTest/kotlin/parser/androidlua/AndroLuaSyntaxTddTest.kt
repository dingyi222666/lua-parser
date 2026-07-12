package parser.androidlua

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.assertParseFails
import parser.firstStatement
import parser.parse
import parser.renderShape
import parser.returnExpression
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement

class AndroLuaSyntaxTddTest {

    @Test
    fun parsesResourceBackedControlFlowImportAndLuaJavaFixture() {
        val chunk = parse(LuaVersion.ANDROLUA_5_3, loadSyntaxFixture("control_flow_luajava.lua"))

        assertEquals(7, chunk.body.statements.size)
        assertIs<CallStatement>(chunk.body.statements[0])
        assertIs<CallStatement>(chunk.body.statements[1])
        assertIs<CallStatement>(chunk.body.statements[2])
        assertIs<LocalStatement>(chunk.body.statements[3])
        assertIs<LocalStatement>(chunk.body.statements[4])
        assertIs<WhileStatement>(chunk.body.statements[5])
        assertIs<SwitchStatement>(chunk.body.statements[6])
        assertEquals("Id(ids)", renderShape(chunk.returnExpression()))

        val requireCall = assertIs<CallStatement>(chunk.body.statements[0]).expression
        assertIs<StringCallExpression>(requireCall.base)

        val importTableCall = assertIs<CallStatement>(chunk.body.statements[2]).expression
        val importedTable = assertIs<TableCallExpression>(importTableCall.base).arguments.single()
        assertEquals(3, assertIs<TableConstructorExpression>(importedTable).fields.size)

        val listener = assertIs<LocalStatement>(chunk.body.statements[3]).variables.single()
        assertEquals("Lambda(Id(view):Call(Member(Id(view):getId):))", renderShape(listener))

        val ids = assertIs<LocalStatement>(chunk.body.statements[4]).variables.single()
        assertEquals("Array(Const(1),Const(2),Call(Id(listener):Id(button)))", renderShape(ids))

        val whileStatement = assertIs<WhileStatement>(chunk.body.statements[5])
        assertEquals(2, whileStatement.body.statements.size)
        assertIs<WhenStatement>(whileStatement.body.statements[1])
        assertTrue(renderShape(whileStatement).contains("Continue"))

        val switchStatement = assertIs<SwitchStatement>(chunk.body.statements[6])
        assertEquals(3, switchStatement.causes.size)
        assertIs<CaseCause>(switchStatement.causes[0])
        assertIs<CaseCause>(switchStatement.causes[1])
        assertIs<DefaultCause>(switchStatement.causes[2])
    }

    @Test
    fun parsesResourceBackedAlyStyleLayoutTableFixture() {
        val chunk = parse(LuaVersion.ANDROLUA_5_3, loadSyntaxFixture("layout_tables.lua"))
        val layout = assertIs<TableConstructorExpression>(chunk.returnExpression())

        assertEquals(5, layout.fields.size)
        assertEquals("LinearLayout", assertIs<Identifier>(layout.fields[0].value).name)
        assertEquals("TableKeyString(Id(orientation)=Const(\"vertical\"))", renderShape(layout.fields[1]))
        assertEquals("TableKeyString(Id(id)=Const(\"root\"))", renderShape(layout.fields[2]))

        val nestedChild = assertIs<TableConstructorExpression>(layout.fields[3].value)
        assertEquals(3, nestedChild.fields.size)
        assertEquals("TextView", assertIs<Identifier>(nestedChild.fields[0].value).name)

        val buttonCall = assertIs<CallExpression>(layout.fields[4].value)
        val tableCall = assertIs<TableCallExpression>(buttonCall.base)
        assertEquals("Button", assertIs<Identifier>(tableCall.base).name)
        assertEquals(2, assertIs<TableConstructorExpression>(tableCall.arguments.single()).fields.size)
        assertTrue(renderShape(layout).contains("Lambda(Id(view):Call(Id(save):Id(view)))"))
    }

    /**
     * TASK-610: Windows corpus strict rows fail when Android-Lua comment/attribute
     * forms inside tables, optional switch-do, bare .aly tables, or trailing
     * end-of-line comments after `return` are treated as expressions/statements.
     */
    @Test
    fun acceptsAndroidLuaTableCommentsOptionalSwitchDoAndBareAlyForms() {
        // loadlayout-style attribute comments between table fields
        val loadlayoutLike = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            local toint={
              --android:drawingCacheQuality
              auto=0,
              low=1,
              high=2,
              --android:visibility
              visible=0,
              gone=8,
            }
            """.trimIndent()
        )
        val toint = assertIs<LocalStatement>(loadlayoutLike.body.statements.single())
        val tointTable = assertIs<TableConstructorExpression>(toint.variables.single())
        assertEquals(5, tointTable.fields.size)
        assertEquals("TableKeyString(Id(auto)=Const(0))", renderShape(tointTable.fields[0]))
        assertEquals("TableKeyString(Id(gone)=Const(8))", renderShape(tointTable.fields[4]))

        // http.lua-style table call with leading field comments
        val httpLike = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            local result = trequest {
              -- the RFC says the redirect URL has to be absolute, but some
              -- servers do not respect that
              url = location,
              source = reqt.source,
            }
            """.trimIndent()
        )
        val httpLocal = assertIs<LocalStatement>(httpLike.body.statements.single())
        assertTrue(renderShape(httpLocal.variables.single()).contains("TableKeyString(Id(url)=Id(location))"))

        // json.lua-style trailing comment after return before end
        val jsonLike = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            if ready then
              return value -- Need to handle encoding in string
            end
            """.trimIndent()
        )
        assertEquals(1, jsonLike.body.statements.size)

        // AndroLua compact switch without `do` (main.lua / main2.lua assets)
        val switchChunk = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            switch s
             case true
              handle(s)
            end
            """.trimIndent()
        )
        val switchStmt = assertIs<SwitchStatement>(switchChunk.body.statements.single())
        assertEquals(1, switchStmt.causes.size)
        assertIs<CaseCause>(switchStmt.causes.single())

        // Bare .aly layout table (alyloader wraps as return at runtime)
        val alyChunk = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            {
              LinearLayout;
              orientation="vertical";
              {
                TextView;
                text="Hi";
              };
            }
            """.trimIndent()
        )
        val alyTable = assertIs<TableConstructorExpression>(alyChunk.returnExpression())
        assertEquals(3, alyTable.fields.size)
        assertEquals("LinearLayout", assertIs<Identifier>(alyTable.fields[0].value).name)
        assertEquals("TableKeyString(Id(orientation)=Const(\"vertical\"))", renderShape(alyTable.fields[1]))

        // Inline layout field comment after semicolon (file.lua / plugin-main style)
        val inlineComment = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            local layout = {
              ListView;
              id="lv",
              DividerHeight=0;-- no divider
              layout_width="match_parent";
            }
            """.trimIndent()
        )
        val layoutLocal = assertIs<LocalStatement>(inlineComment.body.statements.single())
        val layoutTable = assertIs<TableConstructorExpression>(layoutLocal.variables.single())
        assertEquals(4, layoutTable.fields.size)
        assertTrue(renderShape(layoutTable).contains("TableKeyString(Id(DividerHeight)=Const(0))"))
        assertTrue(renderShape(layoutTable).contains("TableKeyString(Id(layout_width)=Const(\"match_parent\"))"))

        // Strict mode still rejects missing do when next token is not case/default/end
        assertParseFails(LuaVersion.ANDROLUA_5_3, "switch s then case 1 print(1) end")
    }

    @Test
    fun acceptsAndroLuaOptionalThenAndParenthesizedCallFunctionBodies() {
        // asset-main.lua scaleup/scaledown: optional `then` before else / body.
        val emptyThen = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            if actp.height<dp2px(50)
             else
              actp.height = actp.height - 1
            end
            """.trimIndent()
        )
        val emptyIf = assertIs<IfStatement>(emptyThen.body.statements.single())
        assertEquals(2, emptyIf.causes.size)
        assertTrue(emptyIf.causes[0].body.statements.isEmpty(), "empty then-branch before else")
        assertFalse(emptyIf.bad, "AndroLua optional then must not mark IfStatement bad")
        assertFalse(emptyIf.causes[0].bad, "AndroLua optional then must not mark IfClause bad")

        val bodyWithoutThen = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            if actp.height>actheight
              stop=true
             else
              actp.height = actp.height + 1
            end
            """.trimIndent()
        )
        val bodyIf = assertIs<IfStatement>(bodyWithoutThen.body.statements.single())
        assertEquals(2, bodyIf.causes.size)
        assertTrue(
            renderShape(bodyIf).contains("Assign(Id(stop)=Const(true))"),
            "body without then must parse assignment: ${renderShape(bodyIf)}"
        )
        assertFalse(bodyIf.bad)
        assertFalse(bodyIf.causes[0].bad)

        // loadlayout.lua: compact anonymous function whose body is a parenthesized call.
        val loadlayoutLike = parse(
            LuaVersion.ANDROLUA_5_3,
            "listener=OnClickListener{onClick=function(a)(root[v] or _G[v])(a)end}"
        )
        val assign = assertIs<AssignmentStatement>(loadlayoutLike.body.statements.single())
        val shape = renderShape(assign)
        assertTrue(
            shape.contains("Function(null,Block[CallStmt(Call(Binary(or,"),
            "expected parenthesized call statement in function body, got $shape"
        )
        assertTrue(
            shape.contains("Index(Id(root)[Id(v)])") && shape.contains("Index(Id(_G)[Id(v)])"),
            "expected root[v]/_G[v] shape, got $shape"
        )

        // Plain Lua 5.3 still requires `then` in strict mode.
        assertParseFails(
            LuaVersion.LUA_5_3,
            "if ready\n  work()\nend",
            recovery = false
        )
    }


    @Test
    fun parsesAndroLuaOnlyStatementShapes() {
        assertCaseShapes(androluaStatementShapeCases, LuaVersion.ANDROLUA_5_3)
    }

    @Test
    fun parsesLambdaExpressionShapes() {
        assertCaseShapes(lambdaShapeCases, LuaVersion.ANDROLUA_5_3)

        val lambda = assertIs<LambdaDeclaration>(
            parse(LuaVersion.ANDROLUA_5_3, "return lambda x, y -> x + y").returnExpression()
        )
        assertContentEquals(listOf("x", "y"), lambda.params.map { it.name })
        assertEquals("Binary(+,Id(x),Id(y))", renderShape(lambda.expression))
    }

    @Test
    fun parsesArrayConstructorShapes() {
        assertCaseShapes(arrayShapeCases, LuaVersion.ANDROLUA_5_3)

        val array = assertIs<ArrayConstructorExpression>(
            parse(LuaVersion.ANDROLUA_5_3, "return [lambda value: value, [1, 2], call()]").returnExpression()
        )
        assertEquals(3, array.values.size)
        assertIs<LambdaDeclaration>(array.values[0])
        assertIs<ArrayConstructorExpression>(array.values[1])
        assertIs<CallExpression>(array.values[2])
    }

    @Test
    fun parsesImportAndLuaJavaCallFormsAsNormalCalls() {
        portableImportCallSources.forEach { source ->
            parse(LuaVersion.LUA_5_3, source)
            parse(LuaVersion.ANDROLUA_5_3, source)
        }

        val requireCall = parse(LuaVersion.ANDROLUA_5_3, "require \"import\"")
            .firstStatement<CallStatement>().expression
        assertIs<StringCallExpression>(requireCall.base)

        val tableImportCall = parse(
            LuaVersion.ANDROLUA_5_3,
            "import { \"java.io.File\", \"java.util.*\" }"
        ).firstStatement<CallStatement>().expression
        val tableCall = assertIs<TableCallExpression>(tableImportCall.base)
        assertEquals("import", assertIs<Identifier>(tableCall.base).name)
        assertEquals(2, assertIs<TableConstructorExpression>(tableCall.arguments.single()).fields.size)

        androluaLuaJavaCallSources.forEach { source ->
            val shape = renderShape(parse(LuaVersion.ANDROLUA_5_3, source))
            assertTrue(shape.contains("luajava") || shape.contains("TextView") || shape.contains("Button"), source)
        }
    }

    @Test
    fun parsesAlyStyleTableAndJavaObjectCallPatterns() {
        alyStyleSources.forEach { source ->
            val chunk = parse(LuaVersion.ANDROLUA_5_3, source)
            assertTrue(renderShape(chunk).contains("Table"), source)
        }

        val objectCall = parse(
            LuaVersion.ANDROLUA_5_3,
            "Button { text = \"Save\", onClick = lambda view -> save(view) }"
        ).firstStatement<CallStatement>().expression
        val tableCall = assertIs<TableCallExpression>(objectCall.base)
        assertEquals("Button", assertIs<Identifier>(tableCall.base).name)
        val args = assertIs<TableConstructorExpression>(tableCall.arguments.single())
        assertEquals(2, args.fields.size)

        val indexedObjectCall = parse(
            LuaVersion.ANDROLUA_5_3,
            "items[1] { text = \"mutate\" }"
        ).firstStatement<CallStatement>().expression
        assertIs<TableCallExpression>(indexedObjectCall.base)
    }

    @Test
    fun gatesAndroLuaOnlySyntaxFromPlainLua53() {
        androluaOnlySources.forEach { source ->
            parse(LuaVersion.ANDROLUA_5_3, source)
            assertParseFails(LuaVersion.LUA_5_3, source)
        }
    }

    @Test
    fun treatsAndroLuaKeywordsAsPlainLuaIdentifiersOutsideAndroLuaMode() {
        val names = listOf("switch", "case", "default", "continue", "when", "lambda")
        val source = "local ${names.joinToString()} = ${names.joinToString()}"
        val returnSource = "return ${names.joinToString()}"
        val expectedReturnShape =
            "Chunk(Block[Return(Id(switch),Id(case),Id(default),Id(continue),Id(when),Id(lambda))])"

        listOf(LuaVersion.LUA_5_3, LuaVersion.LUA_5_4).forEach { version ->
            val local = assertIs<LocalStatement>(parse(version, source).body.statements.single())

            assertContentEquals(names, local.init.map { it.name }, version.name)
            assertContentEquals(names, local.variables.map { assertIs<Identifier>(it).name }, version.name)
            assertEquals(expectedReturnShape, renderShape(parse(version, returnSource)), version.name)
            assertEquals(
                expectedReturnShape,
                renderShape(LuaParser(luaVersion = version, errorRecovery = false).parse(LuaLexer(returnSource))),
                "${version.name} with external lexer"
            )
        }
    }

    @Test
    fun keepsImportSyntaxPortableAcrossLua53AndAndroLua53() {
        portableImportCallSources.forEach { source ->
            parse(LuaVersion.LUA_5_3, source)
            parse(LuaVersion.ANDROLUA_5_3, source)
        }
    }

    @Test
    fun switchCaseBodiesUseDocumentedShapeWithoutExtraParen() {
        val cases = listOf(
            SyntaxCase(
                "single case with then",
                "switch value do case 1 then print(1) end",
                "Chunk(Block[Switch(Id(value):Case(Const(1):Block[CallStmt(Call(Id(print):Const(1)))]))])"
            ),
            SyntaxCase(
                "multi condition case without then",
                "switch value do case 1, 2 print(value) end",
                "Chunk(Block[Switch(Id(value):Case(Const(1),Const(2):Block[CallStmt(Call(Id(print):Id(value)))]))])"
            ),
            SyntaxCase(
                "case then with default",
                "switch value do case 1 then break default continue end",
                "Chunk(Block[Switch(Id(value):Case(Const(1):Block[Break]),Default(Block[Continue]))])"
            )
        )
        assertCaseShapes(cases, LuaVersion.ANDROLUA_5_3)
        cases.forEach { case ->
            val shape = renderShape(parse(LuaVersion.ANDROLUA_5_3, case.source))
            assertTrue(
                shape.contains("Case(") && shape.contains(":Block["),
                "${case.name} should keep Case(...:Block[...]) shape: $shape"
            )
            // Reject unbalanced form that drops Switch's closing paren after the case body.
            assertFalse(
                shape.endsWith("])])") && !shape.endsWith("]))])"),
                "${case.name} must not omit Switch close after case body: $shape"
            )
            assertEquals(case.expectedShape, shape, case.name)
        }

        // Optional-then fidelity: CaseCause.hasThen must track source keyword presence
        // without changing documented shape (TASK-552 / TASK-171).
        val withThen = parse(LuaVersion.ANDROLUA_5_3, "switch value do case 1 then print(1) end")
        val withoutThen = parse(LuaVersion.ANDROLUA_5_3, "switch value do case 1, 2 print(value) end")
        val withThenCase = assertIs<CaseCause>(assertIs<SwitchStatement>(withThen.body.statements.single()).causes.single())
        val withoutThenCase = assertIs<CaseCause>(assertIs<SwitchStatement>(withoutThen.body.statements.single()).causes.single())
        assertTrue(withThenCase.hasThen, "case with then must set hasThen=true")
        assertFalse(withoutThenCase.hasThen, "case without then must set hasThen=false")
    }

    @Test
    fun recordsAtLeastFortyTaskSyntaxCases() {
        val totalCases = androluaStatementShapeCases.size +
            lambdaShapeCases.size +
            arrayShapeCases.size +
            portableImportCallSources.size +
            androluaLuaJavaCallSources.size +
            alyStyleSources.size +
            androluaOnlySources.size

        assertTrue(totalCases >= 40, "Expected at least 40 TASK-013 syntax cases, got $totalCases")
    }

    private fun assertCaseShapes(cases: List<SyntaxCase>, version: LuaVersion) {
        val failures = cases.mapNotNull { case ->
            runCatching {
                assertEquals(case.expectedShape, renderShape(parse(version, case.source)), case.name)
            }.exceptionOrNull()?.let { failure ->
                "${case.name}\nsource: ${case.source}\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    private fun loadSyntaxFixture(name: String): String {
        val path = "/parser/tdd/androidlua/syntax/$name"
        return checkNotNull(javaClass.getResourceAsStream(path)) {
            "Missing AndroLua syntax fixture: $path"
        }.bufferedReader().use { it.readText() }
    }

    private data class SyntaxCase(
        val name: String,
        val source: String,
        val expectedShape: String
    )

    private companion object {
        val androluaStatementShapeCases = listOf(
            SyntaxCase(
                "continue in while body",
                "while ready do continue end",
                "Chunk(Block[While(Id(ready):Block[Continue])])"
            ),
            SyntaxCase(
                "continue in repeat body",
                "repeat continue until done",
                "Chunk(Block[Repeat(Block[Continue]:Id(done))])"
            ),
            SyntaxCase(
                "continue in numeric for body",
                "for i = 1, 3 do continue end",
                "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(3),null:Block[Continue])])"
            ),
            SyntaxCase(
                "when assignment without else",
                "when ready target = 1",
                "Chunk(Block[When(Id(ready)?Assign(Id(target)=Const(1)):null)])"
            ),
            SyntaxCase(
                "when assignment with else",
                "when ready target = 1 else target = 2",
                "Chunk(Block[When(Id(ready)?Assign(Id(target)=Const(1)):Assign(Id(target)=Const(2)))])"
            ),
            SyntaxCase(
                "when call without else",
                "when ready print(1)",
                "Chunk(Block[When(Id(ready)?CallStmt(Call(Id(print):Const(1))):null)])"
            ),
            SyntaxCase(
                "when call with else",
                "when ready print(1) else fallback(2)",
                "Chunk(Block[When(Id(ready)?CallStmt(Call(Id(print):Const(1))):CallStmt(Call(Id(fallback):Const(2))))])"
            ),
            SyntaxCase(
                "when method calls",
                "when ready object:show(\"ok\") else object:hide()",
                "Chunk(Block[When(Id(ready)?CallStmt(Call(Member(Id(object):show):Const(\"ok\"))):CallStmt(Call(Member(Id(object):hide):)))])"
            ),
            SyntaxCase(
                "empty switch",
                "switch value do end",
                "Chunk(Block[Switch(Id(value):)])"
            ),
            SyntaxCase(
                "single case switch",
                "switch value do case 1 then print(1) end",
                "Chunk(Block[Switch(Id(value):Case(Const(1):Block[CallStmt(Call(Id(print):Const(1)))]))])"
            ),
            SyntaxCase(
                "case supports optional then",
                "switch value do case 1, 2 print(value) end",
                "Chunk(Block[Switch(Id(value):Case(Const(1),Const(2):Block[CallStmt(Call(Id(print):Id(value)))]))])"
            ),
            SyntaxCase(
                "case and default control transfer",
                "switch value do case 1 then break default continue end",
                "Chunk(Block[Switch(Id(value):Case(Const(1):Block[Break]),Default(Block[Continue]))])"
            ),
            SyntaxCase(
                "switch expression condition",
                "switch value + 1 do case limit then result = limit default result = value end",
                "Chunk(Block[Switch(Binary(+,Id(value),Const(1)):Case(Id(limit):Block[Assign(Id(result)=Id(limit))]),Default(Block[Assign(Id(result)=Id(value))]))])"
            ),
            SyntaxCase(
                "nested switch in do block",
                "do switch value do case 1 then continue default break end end",
                "Chunk(Block[Do(Block[Switch(Id(value):Case(Const(1):Block[Continue]),Default(Block[Break]))])])"
            )
        )

        val lambdaShapeCases = listOf(
            SyntaxCase("zero arg colon lambda", "return lambda: value", "Chunk(Block[Return(Lambda(:Id(value)))])"),
            SyntaxCase("zero arg paren lambda", "return lambda(): value", "Chunk(Block[Return(Lambda(:Id(value)))])"),
            SyntaxCase("single arg colon lambda", "return lambda value: value", "Chunk(Block[Return(Lambda(Id(value):Id(value)))])"),
            SyntaxCase("single arg arrow lambda", "return lambda value -> value", "Chunk(Block[Return(Lambda(Id(value):Id(value)))])"),
            SyntaxCase(
                "single arg fat arrow lambda",
                "return lambda(value) => value + 1",
                "Chunk(Block[Return(Lambda(Id(value):Binary(+,Id(value),Const(1))))])"
            ),
            SyntaxCase(
                "multi arg paren lambda",
                "return lambda(x, y): x + y",
                "Chunk(Block[Return(Lambda(Id(x),Id(y):Binary(+,Id(x),Id(y))))])"
            ),
            SyntaxCase(
                "multi arg bare lambda",
                "return lambda x, y -> x * y",
                "Chunk(Block[Return(Lambda(Id(x),Id(y):Binary(*,Id(x),Id(y))))])"
            ),
            SyntaxCase(
                "nested lambda expression",
                "return lambda value: lambda inner: value + inner",
                "Chunk(Block[Return(Lambda(Id(value):Lambda(Id(inner):Binary(+,Id(value),Id(inner)))))])"
            ),
            SyntaxCase(
                "lambda call expression",
                "return (lambda value: value)(1)",
                "Chunk(Block[Return(Call(Lambda(Id(value):Id(value)):Const(1)))])"
            ),
            SyntaxCase(
                "lambda body method call",
                "return lambda value: value:getText()",
                "Chunk(Block[Return(Lambda(Id(value):Call(Member(Id(value):getText):)))])"
            )
        )

        val arrayShapeCases = listOf(
            SyntaxCase("empty array", "return []", "Chunk(Block[Return(Array())])"),
            SyntaxCase("single value array", "return [1]", "Chunk(Block[Return(Array(Const(1)))])"),
            SyntaxCase("multi value array", "return [1, 2, 3]", "Chunk(Block[Return(Array(Const(1),Const(2),Const(3)))])"),
            SyntaxCase(
                "nested arrays",
                "return [ [1, 2], [3, 4] ]",
                "Chunk(Block[Return(Array(Array(Const(1),Const(2)),Array(Const(3),Const(4))))])"
            ),
            SyntaxCase(
                "array with calls",
                "return [foo(), bar(1)]",
                "Chunk(Block[Return(Array(Call(Id(foo):),Call(Id(bar):Const(1))))])"
            ),
            SyntaxCase(
                "array with lambda",
                "return [lambda value: value, 2]",
                "Chunk(Block[Return(Array(Lambda(Id(value):Id(value)),Const(2)))])"
            ),
            SyntaxCase(
                "local array initializer",
                "local values = [1, 2]",
                "Chunk(Block[Local(Id(values)=Array(Const(1),Const(2)))])"
            ),
            SyntaxCase(
                "dollar local array initializer",
                "local ${'$'}values = [1, 2]",
                "Chunk(Block[Local(Id(values)=Array(Const(1),Const(2)))])"
            ),
            SyntaxCase(
                "array assignment",
                "items = [first, second]",
                "Chunk(Block[Assign(Id(items)=Array(Id(first),Id(second)))])"
            ),
            SyntaxCase(
                "arrays as call arguments",
                "print([1, 2], [])",
                "Chunk(Block[CallStmt(Call(Id(print):Array(Const(1),Const(2)),Array()))])"
            )
        )

        val portableImportCallSources = listOf(
            "require \"import\"",
            "local importFn = require \"import\"",
            "import \"java.io.File\"",
            "import \"java.io.*\"",
            "import \"dexname:com.example.Plugin\"",
            "import { \"java.io.File\", \"java.util.*\" }",
            "import({ \"java.io.File\", \"java.util.*\" })",
            "local imported = import { \"java.io.File\", \"java.util.*\" }",
            "luajava.bindClass(\"java.lang.String\")",
            "luajava.newInstance(\"java.io.File\", path)",
            "luajava.loadLib(\"com.example.Loader\", \"open\")"
        )

        val androluaLuaJavaCallSources = listOf(
            "luajava.createArray(\"java.lang.String\", [\"a\", \"b\"])",
            "local ids = luajava.createArray(\"int\", [1, 2, 3])",
            "luajava.override(View_OnClickListener, { onClick = lambda view -> view:getId() })",
            "TextView { text = \"Title\", onClick = lambda view -> print(view) }",
            "Button { ids = [1, 2], onClick = lambda view -> save(view) }"
        )

        val alyStyleSources = listOf(
            "return { LinearLayout, orientation = \"vertical\", { TextView, text = \"Hello\" } }",
            "activity.setContentView(loadlayout { LinearLayout, id = \"root\", { TextView, id = \"title\" } })",
            "Button { text = \"Save\", onClick = lambda view -> save(view) }",
            "local menu = { { \"File\", id = 1 }, { \"Edit\", id = 2 } }",
            "items[1] { text = \"mutate\" }",
            "return { onClick = lambda view -> view:getId(), ids = [1, 2] }"
        )

        val androluaOnlySources = listOf(
            "while ready do continue end",
            "repeat continue until done",
            "when ready print(1) else print(2)",
            "switch value do case 1 then print(1) default print(2) end",
            "return lambda value: value",
            "return lambda(value) => value",
            "return lambda value -> value",
            "return [1, 2]",
            "local ${'$'}value = 1",
            "luajava.createArray(\"java.lang.String\", [\"a\"])",
            "return { onClick = lambda view -> view:getId() }",
            "Button { ids = [1, 2] }"
        )
    }
}
