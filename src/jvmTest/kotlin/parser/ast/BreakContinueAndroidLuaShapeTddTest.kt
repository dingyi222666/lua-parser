package parser.ast

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.BreakStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ContinueStatement
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import parser.assertParseFails
import parser.firstStatement
import parser.parse
import parser.renderShape

/**
 * Android-Lua break / continue shape corpus (TASK-318).
 *
 * Acceptance:
 * - AndroLua break/continue version-gated shapes parse under [LuaVersion.ANDROLUA_5_3].
 * - Plain Lua 5.3 (and 5.4) reject `continue` statement forms when required.
 * - Test-only; no product edits.
 *
 * Shapes use the shared [parser.renderShape] vocabulary:
 * - `Break` for [BreakStatement]
 * - `Continue` for [ContinueStatement]
 */
class BreakContinueAndroidLuaShapeTddTest {

    @Test
    fun androLuaParsesContinueShapesAcrossLoopBodies() {
        continueShapeCases.forEach { case ->
            val chunk = parse(LuaVersion.ANDROLUA_5_3, case.source)
            assertEquals(case.expectedShape, renderShape(chunk), case.name)
        }
    }

    @Test
    fun androLuaParsesBreakShapesAcrossLoopBodiesAndSwitch() {
        breakShapeCases.forEach { case ->
            val chunk = parse(LuaVersion.ANDROLUA_5_3, case.source)
            assertEquals(case.expectedShape, renderShape(chunk), case.name)
        }
    }

    @Test
    fun androLuaParsesMixedBreakAndContinueShapes() {
        mixedShapeCases.forEach { case ->
            val chunk = parse(LuaVersion.ANDROLUA_5_3, case.source)
            assertEquals(case.expectedShape, renderShape(chunk), case.name)
        }
    }

    @Test
    fun androLuaExposesTypedBreakAndContinueNodes() {
        val whileContinue = parse(LuaVersion.ANDROLUA_5_3, "while ready do continue end")
            .firstStatement<WhileStatement>()
        assertIs<ContinueStatement>(whileContinue.body.statements.single())

        val whileBreak = parse(LuaVersion.ANDROLUA_5_3, "while ready do break end")
            .firstStatement<WhileStatement>()
        assertIs<BreakStatement>(whileBreak.body.statements.single())

        val repeatContinue = parse(LuaVersion.ANDROLUA_5_3, "repeat continue until done")
            .firstStatement<RepeatStatement>()
        assertIs<ContinueStatement>(repeatContinue.body.statements.single())

        val numericContinue = parse(LuaVersion.ANDROLUA_5_3, "for i = 1, 3 do continue end")
            .firstStatement<ForNumericStatement>()
        assertIs<ContinueStatement>(numericContinue.body.statements.single())

        val genericContinue = parse(LuaVersion.ANDROLUA_5_3, "for k, v in pairs(t) do continue end")
            .firstStatement<ForGenericStatement>()
        assertIs<ContinueStatement>(genericContinue.body.statements.single())

        val doContinue = parse(LuaVersion.ANDROLUA_5_3, "do continue end")
            .firstStatement<DoStatement>()
        assertIs<ContinueStatement>(doContinue.body.statements.single())

        val switch = parse(
            LuaVersion.ANDROLUA_5_3,
            "switch value do case 1 then break default continue end"
        ).firstStatement<SwitchStatement>()
        val caseCause = assertIs<CaseCause>(switch.causes[0])
        val defaultCause = assertIs<DefaultCause>(switch.causes[1])
        assertIs<BreakStatement>(caseCause.body.statements.single())
        assertIs<ContinueStatement>(defaultCause.body.statements.single())
    }

    @Test
    fun plainLua53And54RejectContinueStatementForms() {
        continueOnlySources.forEach { source ->
            parse(LuaVersion.ANDROLUA_5_3, source)
            assertParseFails(LuaVersion.LUA_5_3, source)
            assertParseFails(LuaVersion.LUA_5_4, source)
        }
    }

    @Test
    fun plainLuaStillAcceptsBreakInAllLoopBodies() {
        breakOnlySources.forEach { source ->
            plainVersions.forEach { version ->
                val chunk = parse(version, source)
                assertTrue(
                    renderShape(chunk).contains("Break"),
                    "expected Break under $version for: $source"
                )
            }
            val androShape = renderShape(parse(LuaVersion.ANDROLUA_5_3, source))
            plainVersions.forEach { version ->
                assertEquals(
                    androShape,
                    renderShape(parse(version, source)),
                    "break shape must be version-portable: $source under $version"
                )
            }
        }
    }

    @Test
    fun continueAsIdentifierIsPortableUnderPlainLua() {
        // Outside AndroLua mode, AndroLua-only keywords demote to NAME.
        val names = listOf("switch", "case", "default", "continue", "when", "lambda")
        val source = "return ${names.joinToString()}"
        val expected =
            "Chunk(Block[Return(Id(switch),Id(case),Id(default),Id(continue),Id(when),Id(lambda))])"

        plainVersions.forEach { version ->
            assertEquals(expected, renderShape(parse(version, source)), version.name)
        }
    }

    @Test
    fun nestedIfInsideLoopKeepsBreakAndContinueLeafShapes() {
        val source = """
            while ready do
              if skip then
                continue
              else
                break
              end
            end
        """.trimIndent()

        val chunk = parse(LuaVersion.ANDROLUA_5_3, source)
        assertEquals(
            "Chunk(Block[While(Id(ready):Block[If(Clause(Id(skip):Block[Continue]),Else(Block[Break]))])])",
            renderShape(chunk)
        )

        val whileStatement = chunk.firstStatement<WhileStatement>()
        val ifStatement = assertIs<IfStatement>(whileStatement.body.statements.single())
        assertIs<ContinueStatement>(ifStatement.causes[0].body.statements.single())
        assertIs<BreakStatement>(ifStatement.causes[1].body.statements.single())

        assertParseFails(LuaVersion.LUA_5_3, source)
        assertParseFails(LuaVersion.LUA_5_4, source)
    }

    @Test
    fun multiStatementLoopBodiesPreserveBreakContinueOrder() {
        val source = "for i = 1, n, 1 do print(i); continue; break end"
        val expected =
            "Chunk(Block[ForNumeric(Id(i)=Const(1),Id(n),Const(1):Block[CallStmt(Call(Id(print):Id(i)));Continue;Break])])"

        assertEquals(expected, renderShape(parse(LuaVersion.ANDROLUA_5_3, source)))

        val numeric = parse(LuaVersion.ANDROLUA_5_3, source).firstStatement<ForNumericStatement>()
        assertEquals(3, numeric.body.statements.size)
        assertIs<ContinueStatement>(numeric.body.statements[1])
        assertIs<BreakStatement>(numeric.body.statements[2])

        assertParseFails(LuaVersion.LUA_5_3, source)
        assertParseFails(LuaVersion.LUA_5_4, source)
    }

    @Test
    fun switchNestedInDoBlockGatesContinueButNotBreakAlone() {
        val withContinue = "do switch value do case 1 then continue default break end end"
        assertEquals(
            "Chunk(Block[Do(Block[Switch(Id(value):Case(Const(1):Block[Continue]),Default(Block[Break]))])])",
            renderShape(parse(LuaVersion.ANDROLUA_5_3, withContinue))
        )
        assertParseFails(LuaVersion.LUA_5_3, withContinue)
        assertParseFails(LuaVersion.LUA_5_4, withContinue)

        val breakOnly = "while ready do break end"
        plainVersions.forEach { version ->
            assertEquals(
                "Chunk(Block[While(Id(ready):Block[Break])])",
                renderShape(parse(version, breakOnly))
            )
        }
    }

    @Test
    fun defaultAndroLuaParserPolicyAcceptsContinue() {
        // Default LuaParser() is ANDROLUA_5_3.
        assertEquals(
            "Chunk(Block[While(Id(ready):Block[Continue])])",
            renderShape(LuaParser().parse("while ready do continue end"))
        )
        assertEquals(
            "Chunk(Block[While(Id(ready):Block[Break])])",
            renderShape(LuaParser().parse("while ready do break end"))
        )
    }

    @Test
    fun corpusSurfacesCoverBreakContinueFamilies() {
        val continueSurfaces = continueShapeCases.map { it.surface }.toSet()
        val breakSurfaces = breakShapeCases.map { it.surface }.toSet()
        val mixedSurfaces = mixedShapeCases.map { it.surface }.toSet()

        assertTrue("while" in continueSurfaces)
        assertTrue("repeat" in continueSurfaces)
        assertTrue("for_numeric" in continueSurfaces)
        assertTrue("for_generic" in continueSurfaces)
        assertTrue("do" in continueSurfaces)

        assertTrue("while" in breakSurfaces)
        assertTrue("switch" in breakSurfaces)

        assertTrue("loop_mixed" in mixedSurfaces)
        assertTrue("switch_mixed" in mixedSurfaces)

        assertTrue(continueShapeCases.size >= 5, "expected dense continue corpus")
        assertTrue(breakShapeCases.size >= 5, "expected dense break corpus")
        assertTrue(mixedShapeCases.size >= 3, "expected dense mixed corpus")
        assertTrue(continueOnlySources.size >= 6, "expected dense continue gating corpus")
    }

    private data class ShapeCase(
        val name: String,
        val surface: String,
        val source: String,
        val expectedShape: String
    )

    private companion object {
        val plainVersions = listOf(LuaVersion.LUA_5_3, LuaVersion.LUA_5_4)

        val continueShapeCases = listOf(
            ShapeCase(
                name = "continue in while body",
                surface = "while",
                source = "while ready do continue end",
                expectedShape = "Chunk(Block[While(Id(ready):Block[Continue])])"
            ),
            ShapeCase(
                name = "continue in repeat body",
                surface = "repeat",
                source = "repeat continue until done",
                expectedShape = "Chunk(Block[Repeat(Block[Continue]:Id(done))])"
            ),
            ShapeCase(
                name = "continue in numeric for body",
                surface = "for_numeric",
                source = "for i = 1, 3 do continue end",
                expectedShape = "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(3),null:Block[Continue])])"
            ),
            ShapeCase(
                name = "continue in numeric for with step",
                surface = "for_numeric",
                source = "for i = 1, 10, 2 do continue end",
                expectedShape = "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(10),Const(2):Block[Continue])])"
            ),
            ShapeCase(
                name = "continue in generic for body",
                surface = "for_generic",
                source = "for k, v in pairs(t) do continue end",
                expectedShape = "Chunk(Block[ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[Continue])])"
            ),
            ShapeCase(
                name = "continue in do body",
                surface = "do",
                source = "do continue end",
                expectedShape = "Chunk(Block[Do(Block[Continue])])"
            ),
            ShapeCase(
                name = "continue after call in while",
                surface = "while",
                source = "while ready do tick(); continue end",
                expectedShape = "Chunk(Block[While(Id(ready):Block[CallStmt(Call(Id(tick):));Continue])])"
            )
        )

        val breakShapeCases = listOf(
            ShapeCase(
                name = "break in while body",
                surface = "while",
                source = "while ready do break end",
                expectedShape = "Chunk(Block[While(Id(ready):Block[Break])])"
            ),
            ShapeCase(
                name = "break in repeat body",
                surface = "repeat",
                source = "repeat break until done",
                expectedShape = "Chunk(Block[Repeat(Block[Break]:Id(done))])"
            ),
            ShapeCase(
                name = "break in numeric for body",
                surface = "for_numeric",
                source = "for i = 1, 3 do break end",
                expectedShape = "Chunk(Block[ForNumeric(Id(i)=Const(1),Const(3),null:Block[Break])])"
            ),
            ShapeCase(
                name = "break in generic for body",
                surface = "for_generic",
                source = "for k, v in pairs(t) do break end",
                expectedShape = "Chunk(Block[ForGeneric(Id(k),Id(v) in Call(Id(pairs):Id(t)):Block[Break])])"
            ),
            ShapeCase(
                name = "break in do body",
                surface = "do",
                source = "do break end",
                expectedShape = "Chunk(Block[Do(Block[Break])])"
            ),
            ShapeCase(
                name = "break in switch case",
                surface = "switch",
                source = "switch value do case 1 then break end",
                expectedShape = "Chunk(Block[Switch(Id(value):Case(Const(1):Block[Break]))])"
            ),
            ShapeCase(
                name = "break after call in while",
                surface = "while",
                source = "while ready do tick(); break end",
                expectedShape = "Chunk(Block[While(Id(ready):Block[CallStmt(Call(Id(tick):));Break])])"
            )
        )

        val mixedShapeCases = listOf(
            ShapeCase(
                name = "while body break then continue order",
                surface = "loop_mixed",
                source = "while ready do break; continue end",
                expectedShape = "Chunk(Block[While(Id(ready):Block[Break;Continue])])"
            ),
            ShapeCase(
                name = "repeat body continue then break order",
                surface = "loop_mixed",
                source = "repeat continue; break until done",
                expectedShape = "Chunk(Block[Repeat(Block[Continue;Break]:Id(done))])"
            ),
            ShapeCase(
                name = "switch case break default continue",
                surface = "switch_mixed",
                source = "switch value do case 1 then break default continue end",
                expectedShape = "Chunk(Block[Switch(Id(value):Case(Const(1):Block[Break]),Default(Block[Continue]))])"
            ),
            ShapeCase(
                name = "switch case continue default break",
                surface = "switch_mixed",
                source = "switch value do case 1 then continue default break end",
                expectedShape = "Chunk(Block[Switch(Id(value):Case(Const(1):Block[Continue]),Default(Block[Break]))])"
            ),
            ShapeCase(
                name = "nested do switch continue/break",
                surface = "switch_mixed",
                source = "do switch value do case 1 then continue default break end end",
                expectedShape = "Chunk(Block[Do(Block[Switch(Id(value):Case(Const(1):Block[Continue]),Default(Block[Break]))])])"
            )
        )

        val continueOnlySources = listOf(
            "while ready do continue end",
            "repeat continue until done",
            "for i = 1, 3 do continue end",
            "for i = 1, 10, 2 do continue end",
            "for k, v in pairs(t) do continue end",
            "do continue end",
            "while ready do tick(); continue end",
            "while ready do break; continue end",
            "switch value do case 1 then break default continue end",
            "do switch value do case 1 then continue default break end end"
        )

        val breakOnlySources = listOf(
            "while ready do break end",
            "repeat break until done",
            "for i = 1, 3 do break end",
            "for k, v in pairs(t) do break end",
            "do break end",
            "while ready do tick(); break end"
        )
    }
}
