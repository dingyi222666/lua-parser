package parser.androidlua

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ContinueStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.assertParseFails
import parser.firstStatement
import parser.parse
import parser.renderShape
import parser.returnExpression

/**
 * Focused AndroLua-only keyword / construct version-policy corpus.
 *
 * Complements [ParserVersionPolicyTddTest] with a denser matrix of:
 * - AndroLua acceptance (stable shapes / typed AST nodes)
 * - plain Lua 5.3 / 5.4 rejection of AndroLua-only constructs
 * - deterministic version-policy / parse-failure messages under plain Lua
 *
 * Keyword demotion: under plain Lua, AndroLua keywords (`switch`, `when`,
 * `continue`, `lambda`, `case`, `default`) are lexed as NAME, so statement
 * forms fail as ordinary parse errors. Dollar-locals hit the name-policy gate
 * (`'$' is not allowed in name ...`); bare `$name` statements hit the
 * AndroLua-only local-prefix assertVersion surface.
 */
class ParserVersionPolicyKeywordGatingTddTest {

    @Test
    fun androLuaAcceptsGatedKeywordStatementsAndExpressions() {
        val failures = gatedConstructCases.mapNotNull { case ->
            runCatching {
                val chunk = parse(LuaVersion.ANDROLUA_5_3, case.source)
                assertEquals(case.expectedShape, renderShape(chunk), case.name)
            }.exceptionOrNull()?.let { failure ->
                "${case.name}\nsource: ${case.source}\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    @Test
    fun plainLua53RejectsGatedKeywordConstructs() {
        gatedConstructCases.forEach { case ->
            assertParseFails(LuaVersion.LUA_5_3, case.source)
        }
    }

    @Test
    fun plainLua54AlsoRejectsGatedKeywordConstructs() {
        gatedConstructCases.forEach { case ->
            assertParseFails(LuaVersion.LUA_5_4, case.source)
        }
    }

    @Test
    fun plainLuaFailuresAreDeterministicAcrossRepeatedParses() {
        val failures = gatedConstructCases.mapNotNull { case ->
            runCatching {
                plainVersions.forEach { version ->
                    val first = assertParseFails(version, case.source)
                    val second = assertParseFails(version, case.source)

                    assertNotNull(first.message, "${case.name} / ${version.name}: missing message")
                    assertEquals(
                        first.message,
                        second.message,
                        "${case.name} / ${version.name}: repeated parse messages must match"
                    )
                    assertTrue(
                        first is IllegalStateException,
                        "${case.name} / ${version.name}: expected IllegalStateException, got ${first::class.simpleName}"
                    )
                }
            }.exceptionOrNull()?.let { failure ->
                "${case.name}\nsource: ${case.source}\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    @Test
    fun dollarLocalNamePolicyMessageIsPinnedAndDeterministic() {
        // `local $value` under plain Lua rejects inside parseName when dollar support is off.
        plainVersions.forEach { version ->
            val first = assertParseFails(version, "local \$value = 1")
            val second = assertParseFails(version, "local \$value = 1")

            assertEquals(first.message, second.message, version.name)
            assertEquals(
                "(1,7): '\$' is not allowed in name \$value",
                first.message,
                version.name
            )
            assertTrue(first is IllegalStateException, version.name)
        }
    }

    @Test
    fun bareDollarLocalHitsAndroLuaVersionPolicyMessage() {
        // Bare `$value = 1` is treated as AndroLua-only dollar-local statement syntax.
        plainVersions.forEach { version ->
            val first = assertParseFails(version, "\$value = 1")
            val second = assertParseFails(version, "\$value = 1")

            assertEquals(first.message, second.message, version.name)
            assertEquals(
                "(1,1): local variables with prefix $ are not supported in this version",
                first.message,
                version.name
            )
            assertTrue(first is IllegalStateException, version.name)
        }

        assertEquals(
            "Chunk(Block[Local(Id(value)=Const(1))])",
            renderShape(parse(LuaVersion.ANDROLUA_5_3, "\$value = 1"))
        )
    }

    @Test
    fun arrayConstructorIsRejectedWithDeterministicPlainLuaMessage() {
        // Arrays are gated via isExpressionStart / parseSubExp; under plain Lua the
        // public path reports a missing-expression error rather than the AndroLua-only
        // assertVersion string, but the message must still be stable.
        plainVersions.forEach { version ->
            val first = assertParseFails(version, "return [1, 2]")
            val second = assertParseFails(version, "return [1, 2]")

            assertEquals(first.message, second.message, version.name)
            assertNotNull(first.message, version.name)
            assertTrue(
                first.message!!.contains("expression", ignoreCase = true) ||
                    first.message!!.contains("array constructor"),
                "${version.name}: unexpected array rejection message: ${first.message}"
            )
        }
    }

    @Test
    fun androLuaKeywordsRemainPlainIdentifiersOutsideAndroLuaMode() {
        val names = listOf("switch", "case", "default", "continue", "when", "lambda")
        val localSource = "local ${names.joinToString()} = ${names.joinToString()}"
        val returnSource = "return ${names.joinToString()}"
        val expectedReturnShape =
            "Chunk(Block[Return(Id(switch),Id(case),Id(default),Id(continue),Id(when),Id(lambda))])"

        plainVersions.forEach { version ->
            val local = assertIs<LocalStatement>(parse(version, localSource).body.statements.single())
            assertContentEquals(names, local.init.map { it.name }, version.name)
            assertContentEquals(
                names,
                local.variables.map { assertIs<Identifier>(it).name },
                version.name
            )
            assertEquals(
                expectedReturnShape,
                renderShape(parse(version, returnSource)),
                version.name
            )
            assertEquals(
                expectedReturnShape,
                renderShape(
                    LuaParser(luaVersion = version, errorRecovery = false)
                        .parse(LuaLexer(returnSource, supportAndroLuaKeywords = false))
                ),
                "${version.name} with external plain lexer"
            )
        }
    }

    @Test
    fun androLuaModeExposesTypedAstForEachGatedConstruct() {
        val continueWhile = parse(LuaVersion.ANDROLUA_5_3, "while ready do continue end")
            .firstStatement<WhileStatement>()
        assertIs<ContinueStatement>(continueWhile.body.statements.single())

        parse(LuaVersion.ANDROLUA_5_3, "when ready target = 1").firstStatement<WhenStatement>()
        parse(LuaVersion.ANDROLUA_5_3, "switch value do case 1 then break end")
            .firstStatement<SwitchStatement>()

        assertIs<LambdaDeclaration>(
            parse(LuaVersion.ANDROLUA_5_3, "return lambda value: value").returnExpression()
        )
        assertIs<ArrayConstructorExpression>(
            parse(LuaVersion.ANDROLUA_5_3, "return [1, 2]").returnExpression()
        )

        val dollarLocal = parse(LuaVersion.ANDROLUA_5_3, "local \$value = 1")
            .firstStatement<LocalStatement>()
        assertEquals("value", dollarLocal.init.single().name)
    }

    @Test
    fun continueIsGatedInAllLoopBodiesUnderPlainLua() {
        listOf(
            "while ready do continue end",
            "repeat continue until done",
            "for i = 1, 3 do continue end",
            "for k, v in pairs(t) do continue end",
            "do continue end"
        ).forEach { source ->
            parse(LuaVersion.ANDROLUA_5_3, source)
            assertParseFails(LuaVersion.LUA_5_3, source)
            assertParseFails(LuaVersion.LUA_5_4, source)
        }
    }

    @Test
    fun whenSwitchLambdaArrayAndDollarAreIndependentlyGated() {
        listOf(
            "when ready print(1) else print(2)",
            "when ready a = 1 else b = 2",
            "switch x do case 1 then print(1) default print(2) end",
            "switch x do end",
            "return lambda: 1",
            "return lambda(): 1",
            "return lambda(x, y) => x + y",
            "return lambda value -> value",
            "return []",
            "return [1]",
            "return [1, 2, foo()]",
            "local items = [1, 2]",
            "local \$x = 1",
            "local \$a, \$b = 1, 2",
            "\$handler = 1"
        ).forEach { source ->
            parse(LuaVersion.ANDROLUA_5_3, source)
            assertParseFails(LuaVersion.LUA_5_3, source)
            assertParseFails(LuaVersion.LUA_5_4, source)
        }
    }

    @Test
    fun defaultParserPolicyMatchesAndroLuaKeywordAcceptance() {
        // Default LuaParser() is ANDROLUA_5_3 and must accept gated constructs.
        assertEquals(
            "Chunk(Block[While(Id(ready):Block[Continue])])",
            renderShape(LuaParser().parse("while ready do continue end"))
        )
        assertEquals(
            "Chunk(Block[Return(Lambda(Id(value):Id(value)))])",
            renderShape(LuaParser().parse(LuaLexer("return lambda value: value")))
        )
        assertEquals(
            "Chunk(Block[Return(Array(Const(1),Const(2)))])",
            renderShape(LuaParser().parse("return [1, 2]"))
        )
        assertEquals(
            "Chunk(Block[Local(Id(value)=Const(1))])",
            renderShape(LuaParser().parse("local \$value = 1"))
        )
    }

    @Test
    fun nestedGatedConstructsStillFailUnderPlainLua53() {
        listOf(
            "while ready do when ok print(1) end",
            "do switch x do case 1 then continue end end",
            "return (lambda v: [v, lambda x: x])(1)",
            "local \$handler = lambda view: view:getId()"
        ).forEach { source ->
            parse(LuaVersion.ANDROLUA_5_3, source)
            assertParseFails(LuaVersion.LUA_5_3, source)
            assertParseFails(LuaVersion.LUA_5_4, source)
        }
    }

    @Test
    fun corpusCoversAllAndroLuaOnlyKeywordSurfaces() {
        val surfaces = gatedConstructCases.map { it.surface }.toSet()
        assertEquals(
            setOf(
                "continue",
                "when",
                "switch",
                "lambda",
                "array",
                "dollar_local"
            ),
            surfaces
        )
        assertTrue(gatedConstructCases.size >= 12, "expected dense gating corpus")
    }

    @Test
    fun explicitLua53ParserDoesNotEnableAndroLuaKeywordSyntax() {
        // Mirror the existing policy smoke test with denser keyword coverage.
        listOf(
            "while ready do continue end",
            "when ready print(1) else print(2)",
            "switch value do case 1 then print(1) end",
            "return lambda value: value",
            "return [1, 2]",
            "local \$value = 1",
            "\$value = 1"
        ).forEach { source ->
            assertParseFails(LuaVersion.LUA_5_3, source)
        }
    }

    private data class GatedConstructCase(
        val name: String,
        val surface: String,
        val source: String,
        val expectedShape: String
    )

    private companion object {
        val plainVersions = listOf(LuaVersion.LUA_5_3, LuaVersion.LUA_5_4)

        val gatedConstructCases = listOf(
            GatedConstructCase(
                name = "continue in while",
                surface = "continue",
                source = "while ready do continue end",
                expectedShape = "Chunk(Block[While(Id(ready):Block[Continue])])"
            ),
            GatedConstructCase(
                name = "continue in repeat",
                surface = "continue",
                source = "repeat continue until done",
                expectedShape = "Chunk(Block[Repeat(Block[Continue]:Id(done))])"
            ),
            GatedConstructCase(
                name = "continue in numeric for",
                surface = "continue",
                source = "for i = 1, 3 do continue end",
                expectedShape = "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(3),null:Block[Continue])])"
            ),
            GatedConstructCase(
                name = "when assignment",
                surface = "when",
                source = "when ready target = 1 else target = 2",
                expectedShape = "Chunk(Block[When(Id(ready)?Assign(Id(target)=Const(1)):Assign(Id(target)=Const(2)))])"
            ),
            GatedConstructCase(
                name = "when call",
                surface = "when",
                source = "when ready print(1) else print(2)",
                expectedShape = "Chunk(Block[When(Id(ready)?CallStmt(Call(Id(print):Const(1))):CallStmt(Call(Id(print):Const(2))))])"
            ),
            GatedConstructCase(
                name = "empty switch",
                surface = "switch",
                source = "switch value do end",
                expectedShape = "Chunk(Block[Switch(Id(value):)])"
            ),
            GatedConstructCase(
                name = "switch with case and default",
                surface = "switch",
                source = "switch value do case 1 then print(1) default print(2) end",
                expectedShape = "Chunk(Block[Switch(Id(value):Case(Const(1):Block[CallStmt(Call(Id(print):Const(1)))]),Default(Block[CallStmt(Call(Id(print):Const(2)))])])"
            ),
            GatedConstructCase(
                name = "colon lambda",
                surface = "lambda",
                source = "return lambda value: value",
                expectedShape = "Chunk(Block[Return(Lambda(Id(value):Id(value)))])"
            ),
            GatedConstructCase(
                name = "arrow lambda",
                surface = "lambda",
                source = "return lambda value -> value",
                expectedShape = "Chunk(Block[Return(Lambda(Id(value):Id(value)))])"
            ),
            GatedConstructCase(
                name = "fat arrow lambda",
                surface = "lambda",
                source = "return lambda(value) => value + 1",
                expectedShape = "Chunk(Block[Return(Lambda(Id(value):Binary(+,Id(value),Const(1))))])"
            ),
            GatedConstructCase(
                name = "array constructor",
                surface = "array",
                source = "return [1, 2]",
                expectedShape = "Chunk(Block[Return(Array(Const(1),Const(2)))])"
            ),
            GatedConstructCase(
                name = "empty array constructor",
                surface = "array",
                source = "return []",
                expectedShape = "Chunk(Block[Return(Array())])"
            ),
            GatedConstructCase(
                name = "dollar local",
                surface = "dollar_local",
                source = "local \$value = 1",
                expectedShape = "Chunk(Block[Local(Id(value)=Const(1))])"
            ),
            GatedConstructCase(
                name = "nested lambda and array",
                surface = "lambda",
                source = "return lambda value: [value]",
                expectedShape = "Chunk(Block[Return(Lambda(Id(value):Array(Id(value))))])"
            )
        )
    }
}
