package parser

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Parser-level corpus for leading `#!` shebang and UTF-8 BOM tolerance (TASK-255).
 *
 * Contract under test:
 * - A leading shebang is accepted only at file start and does not break the following chunk.
 * - A leading UTF-8 BOM (`U+FEFF`) is tolerated and does not break the following chunk
 *   (including BOM + shebang combinations common in editor-saved scripts).
 * - Positions after BOM/shebang remain usable for diagnostics (1-based line/column,
 *   non-empty ranges, subsequent statements on the expected line).
 *
 * Test-only; production lexer/parser changes are out of scope unless review re-scopes.
 * BOM cases may currently be red if the lexer does not yet strip `U+FEFF`.
 */
class ParserShebangBomTddTest {

    /** UTF-8 BOM as a Kotlin char (`U+FEFF`). */
    private val bom = "﻿"

    @Test
    fun leadingShebangDoesNotBreakFollowingStatements() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            "#!/usr/bin/env lua\nlocal value = 1\nprint(value)"
        )

        assertEquals(3, chunk.body.statements.size)

        val shebang = assertIs<CommentStatement>(chunk.body.statements[0])
        assertFalse(shebang.isDocComment)
        assertEquals("#!/usr/bin/env lua", shebang.comment.trimEnd())
        assertEquals(1, shebang.range.start.line)
        assertEquals(1, shebang.range.start.column)
        assertUsableRange(
            shebang.range.start.line,
            shebang.range.start.column,
            shebang.range.end.line,
            shebang.range.end.column
        )

        val local = assertIs<LocalStatement>(chunk.body.statements[1])
        assertEquals(2, local.range.start.line)
        assertEquals(1, local.range.start.column)
        assertEquals("value", assertIs<Identifier>(local.init.single()).name)
        assertUsableRange(
            local.range.start.line,
            local.range.start.column,
            local.range.end.line,
            local.range.end.column
        )

        val call = assertIs<CallStatement>(chunk.body.statements[2])
        assertEquals(3, call.range.start.line)
        assertEquals(1, call.range.start.column)
        val expression = assertIs<CallExpression>(call.expression)
        assertEquals("print", assertIs<Identifier>(expression.base).name)
        assertUsableRange(
            call.range.start.line,
            call.range.start.column,
            call.range.end.line,
            call.range.end.column
        )

        assertEquals(
            "Chunk(Block[Comment(line:#!/usr/bin/env lua);Local(Id(value)=Const(1));CallStmt(Call(Id(print):Id(value)))])",
            renderShape(chunk).trimEnd()
        )
    }

    @Test
    fun leadingShebangWithCrLfKeepsFollowingStatementPositions() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            "#!/usr/bin/lua\r\nreturn 42"
        )

        assertEquals(1, chunk.body.statements.size)
        val shebang = assertIs<CommentStatement>(chunk.body.statements[0])
        assertEquals("#!/usr/bin/lua", shebang.comment.trimEnd())
        assertEquals(1, shebang.range.start.line)

        val ret = assertIs<ReturnStatement>(chunk.body.returnStatement)
        assertEquals(2, ret.range.start.line)
        assertEquals(1, ret.range.start.column)
        val value = assertIs<ConstantNode>(ret.arguments.single())
        assertEquals("42", value.rawValue.toString())
        assertUsableRange(
            ret.range.start.line,
            ret.range.start.column,
            ret.range.end.line,
            ret.range.end.column
        )
    }

    @Test
    fun leadingUtf8BomDoesNotBreakFollowingStatements() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            "${bom}local ready = true\nprint(ready)"
        )

        // BOM is not a statement; following code is a normal chunk.
        assertEquals(2, chunk.body.statements.size)

        val local = assertIs<LocalStatement>(chunk.body.statements[0])
        assertEquals(1, local.range.start.line)
        // BOM is tolerated as invisible prefix; first statement remains column 1 for diagnostics.
        assertEquals(1, local.range.start.column)
        assertEquals("ready", assertIs<Identifier>(local.init.single()).name)
        assertUsableRange(
            local.range.start.line,
            local.range.start.column,
            local.range.end.line,
            local.range.end.column
        )

        val call = assertIs<CallStatement>(chunk.body.statements[1])
        assertEquals(2, call.range.start.line)
        assertEquals(1, call.range.start.column)
        assertUsableRange(
            call.range.start.line,
            call.range.start.column,
            call.range.end.line,
            call.range.end.column
        )

        assertEquals(
            "Chunk(Block[Local(Id(ready)=Const(true));CallStmt(Call(Id(print):Id(ready)))])",
            renderShape(chunk).trimEnd()
        )
    }

    @Test
    fun leadingUtf8BomPlusShebangDoesNotBreakFollowingStatements() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            "${bom}#!/usr/bin/env lua\nlocal value = 7\nreturn value"
        )

        assertEquals(2, chunk.body.statements.size)

        val shebang = assertIs<CommentStatement>(chunk.body.statements[0])
        assertFalse(shebang.isDocComment)
        assertEquals("#!/usr/bin/env lua", shebang.comment.trimEnd())
        assertEquals(1, shebang.range.start.line)
        assertEquals(1, shebang.range.start.column)
        assertUsableRange(
            shebang.range.start.line,
            shebang.range.start.column,
            shebang.range.end.line,
            shebang.range.end.column
        )

        val local = assertIs<LocalStatement>(chunk.body.statements[1])
        assertEquals(2, local.range.start.line)
        assertEquals(1, local.range.start.column)
        assertEquals("value", assertIs<Identifier>(local.init.single()).name)
        assertUsableRange(
            local.range.start.line,
            local.range.start.column,
            local.range.end.line,
            local.range.end.column
        )

        val ret = assertIs<ReturnStatement>(chunk.body.returnStatement)
        assertEquals(3, ret.range.start.line)
        assertEquals(1, ret.range.start.column)
        assertUsableRange(
            ret.range.start.line,
            ret.range.start.column,
            ret.range.end.line,
            ret.range.end.column
        )

        assertEquals(
            "Chunk(Block[Comment(line:#!/usr/bin/env lua);Local(Id(value)=Const(7));Return(Id(value))])",
            renderShape(chunk).trimEnd()
        )
    }

    @Test
    fun bomOnlyPrefixKeepsSimpleCallPositionsUsable() {
        val chunk = parse(LuaVersion.LUA_5_3, "${bom}print(1)")

        val call = assertIs<CallStatement>(chunk.body.statements.single())
        assertEquals(1, call.range.start.line)
        assertEquals(1, call.range.start.column)

        val expression = assertIs<CallExpression>(call.expression)
        val callee = assertIs<Identifier>(expression.base)
        assertEquals("print", callee.name)
        assertEquals(1, callee.range.start.line)
        assertEquals(1, callee.range.start.column)
        assertUsableRange(
            callee.range.start.line,
            callee.range.start.column,
            callee.range.end.line,
            callee.range.end.column
        )

        val arg = assertIs<ConstantNode>(expression.arguments.single())
        assertEquals("1", arg.rawValue.toString())
        assertTrue(arg.range.start.column > callee.range.start.column)
        assertUsableRange(
            arg.range.start.line,
            arg.range.start.column,
            arg.range.end.line,
            arg.range.end.column
        )
    }

    @Test
    fun shebangOnlyAtFileStartStillRejectedWhenNotLeading() {
        // Mid-file #! remains invalid Lua (hash is unary length, ! is not).
        assertParseFails(LuaVersion.LUA_5_3, "print(1)\n#!/usr/bin/env lua")
    }

    @Test
    fun shebangAndBomCorpusIsStableAcrossLuaVersions() {
        val sources = listOf(
            "#!/usr/bin/env lua\nlocal x = 1",
            "${bom}local x = 1",
            "${bom}#!/usr/bin/env lua\nlocal x = 1"
        )
        val versions = listOf(
            LuaVersion.LUA_5_3,
            LuaVersion.LUA_5_4,
            LuaVersion.ANDROLUA_5_3
        )

        for (version in versions) {
            for (source in sources) {
                val chunk = runCatching { parse(version, source) }.getOrElse {
                    fail("version=$version source=${source.escapeForMessage()} failed: ${it.message}")
                }
                val locals = chunk.body.statements.filterIsInstance<LocalStatement>()
                assertEquals(
                    1,
                    locals.size,
                    "version=$version source=${source.escapeForMessage()} expected one local"
                )
                val local = locals.single()
                assertEquals(1, local.range.start.column)
                assertUsableRange(
                    local.range.start.line,
                    local.range.start.column,
                    local.range.end.line,
                    local.range.end.column
                )
            }
        }
    }

    private fun assertUsableRange(startLine: Int, startColumn: Int, endLine: Int, endColumn: Int) {
        assertTrue(startLine >= 1, "start line must be >= 1, was $startLine")
        assertTrue(startColumn >= 1, "start column must be >= 1, was $startColumn")
        assertTrue(endLine >= startLine, "end line $endLine < start line $startLine")
        assertTrue(
            endLine > startLine || endColumn >= startColumn,
            "end column $endColumn < start column $startColumn on line $startLine"
        )
    }

    private fun String.escapeForMessage(): String =
        replace("﻿", "<BOM>")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
}
