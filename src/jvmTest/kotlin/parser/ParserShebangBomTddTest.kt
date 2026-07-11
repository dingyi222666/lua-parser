package parser

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Parser-level corpus for leading `#!` shebang and UTF-8 BOM tolerance (TASK-255 / TASK-547 / TASK-548).
 *
 * ## Product locks
 *
 * ### Leading shebang (`#!` at buffer offset 0)
 * - Accepted as non-doc [CommentStatement]; following statements still parse.
 * - Mid-file `#!` remains invalid (strict parse fails).
 * - Statement positions after the shebang stay usable (1-based, non-empty ranges).
 * - [LocalStatement] start column follows the first name node when the product marks
 *   location after consuming `local` (not always keyword column 1).
 *
 * ### CRLF after shebang — TASK-548
 * - Dedicated NEW_LINE path consumes CRLF (`\r\n`) as one logical line advance
 *   (same as LF-only). After `#!...\r\n`, the following statement is visual line 2.
 * - Shebang-only and shebang+return position ranges stay 1-based and non-empty.
 *
 * ### Leading UTF-8 BOM (`U+FEFF`) — TASK-547
 * - Lexer strips a single leading `U+FEFF` before tokenization so BOM is invisible.
 * - BOM + keyword / callee parses cleanly (`local`, `print` not glued with BOM).
 * - BOM + shebang yields the same shebang [CommentStatement] semantics as shebang-at-offset-0.
 * - Non-leading (mid-buffer) BOM is unchanged (still identifier-start when present mid-source).
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
        assertUsableRange(shebang.range)

        val local = assertIs<LocalStatement>(chunk.body.statements[1])
        assertEquals(2, local.range.start.line)
        val valueName = assertIs<Identifier>(local.init.single())
        assertEquals("value", valueName.name)
        assertLocalStatementStartColumnUsable(local, valueName)
        assertUsableRange(local.range)

        val call = assertIs<CallStatement>(chunk.body.statements[2])
        assertEquals(3, call.range.start.line)
        assertEquals(1, call.range.start.column)
        val expression = assertIs<CallExpression>(call.expression)
        assertEquals("print", assertIs<Identifier>(expression.base).name)
        assertUsableRange(call.range)

        assertEquals(
            "Chunk(Block[Comment(line:#!/usr/bin/env lua);Local(Id(value)=Const(1));CallStmt(Call(Id(print):Id(value)))])",
            renderShape(chunk).trimEnd()
        )
    }

    @Test
    fun leadingShebangWithCrLfKeepsFollowingStatementPositionsUsable() {
        val chunk = parse(
            LuaVersion.LUA_5_3,
            "#!/usr/bin/lua\r\nreturn 42"
        )

        assertEquals(1, chunk.body.statements.size)
        val shebang = assertIs<CommentStatement>(chunk.body.statements[0])
        assertEquals("#!/usr/bin/lua", shebang.comment.trimEnd())
        assertEquals(1, shebang.range.start.line)
        assertUsableRange(shebang.range)

        val ret = assertIs<ReturnStatement>(chunk.body.returnStatement)
        // TASK-548: CRLF after shebang is one logical newline → return is visual line 2.
        assertEquals(
            2,
            ret.range.start.line,
            "return after shebang+CRLF should be visual line 2; was ${ret.range.start.line}"
        )
        assertEquals(1, ret.range.start.column)
        val value = assertIs<ConstantNode>(ret.arguments.single())
        assertEquals("42", value.rawValue.toString())
        assertUsableRange(ret.range)
    }

    @Test
    fun leadingUtf8BomStripsSoLocalKeywordParsesCleanly() {
        val source = "${bom}local ready = true\nprint(ready)"

        // TASK-547: leading BOM is stripped; `local` remains a keyword → clean parse.
        val chunk = parse(LuaVersion.LUA_5_3, source)

        val local = assertIs<LocalStatement>(
            chunk.body.statements.first { it is LocalStatement }
        )
        val readyName = assertIs<Identifier>(local.init.single())
        assertEquals("ready", readyName.name)
        assertLocalStatementStartColumnUsable(local, readyName)
        assertUsableRange(local.range)

        val printCalls = chunk.body.statements.mapNotNull { stmt ->
            val call = stmt as? CallStatement ?: return@mapNotNull null
            val expr = call.expression as? CallExpression ?: return@mapNotNull null
            val base = expr.base as? Identifier ?: return@mapNotNull null
            if (base.name == "print") call else null
        }
        assertTrue(
            printCalls.isNotEmpty(),
            "clean parse should keep print(ready) after BOM-stripped local; statements=" +
                chunk.body.statements.map { it::class.simpleName }
        )
        val call = printCalls.last()
        assertEquals(2, call.range.start.line)
        assertEquals("print", assertIs<Identifier>(assertIs<CallExpression>(call.expression).base).name)
        assertUsableRange(call.range)
    }

    @Test
    fun leadingUtf8BomPlusShebangRecognizedAsShebang() {
        val source = "${bom}#!/usr/bin/env lua\nlocal value = 7\nreturn value"

        // TASK-547: leading BOM stripped so `#!` is at effective offset 0 → SHEBANG_CONTENT.
        val chunk = parse(LuaVersion.LUA_5_3, source)

        val shebang = assertIs<CommentStatement>(chunk.body.statements[0])
        assertFalse(shebang.isDocComment)
        assertEquals("#!/usr/bin/env lua", shebang.comment.trimEnd())
        assertEquals(1, shebang.range.start.line)
        assertEquals(1, shebang.range.start.column)
        assertUsableRange(shebang.range)

        val locals = chunk.body.statements.filterIsInstance<LocalStatement>()
        assertEquals(
            1,
            locals.size,
            "BOM+shebang should keep `local value`; statements=" +
                chunk.body.statements.map { it::class.simpleName }
        )
        val local = locals.single()
        assertEquals(2, local.range.start.line)
        val valueName = assertIs<Identifier>(local.init.single())
        assertEquals("value", valueName.name)
        assertLocalStatementStartColumnUsable(local, valueName)
        assertUsableRange(local.range)

        val ret = assertNotNull(chunk.body.returnStatement)
        assertEquals(3, ret.range.start.line)
        assertUsableRange(ret.range)
    }

    @Test
    fun leadingBomStripsSoCalleeNameIsCleanPrint() {
        // TASK-547: BOM + print → invisible strip to "print", not glued "﻿print".
        val chunk = parse(LuaVersion.LUA_5_3, "${bom}print(1)")

        val call = assertIs<CallStatement>(chunk.body.statements.single())
        assertEquals(1, call.range.start.line)
        assertEquals(1, call.range.start.column)

        val expression = assertIs<CallExpression>(call.expression)
        val callee = assertIs<Identifier>(expression.base)
        assertEquals("print", callee.name)
        assertEquals(1, callee.range.start.line)
        assertEquals(1, callee.range.start.column)
        assertUsableRange(callee.range)

        val arg = assertIs<ConstantNode>(expression.arguments.single())
        assertEquals("1", arg.rawValue.toString())
        assertTrue(arg.range.start.column > callee.range.start.column)
        assertUsableRange(arg.range)
    }

    @Test
    fun shebangOnlyAtFileStartStillRejectedWhenNotLeading() {
        // Mid-file #! remains invalid Lua (hash is unary length, ! is not).
        assertParseFails(LuaVersion.LUA_5_3, "print(1)\n#!/usr/bin/env lua")
    }

    @Test
    fun shebangAndBomCorpusIsStableAcrossLuaVersions() {
        val shebangSource = "#!/usr/bin/env lua\nlocal x = 1"
        val bomLocalSource = "${bom}local x = 1"
        val bomShebangSource = "${bom}#!/usr/bin/env lua\nlocal x = 1"
        val versions = listOf(
            LuaVersion.LUA_5_3,
            LuaVersion.LUA_5_4,
            LuaVersion.ANDROLUA_5_3
        )

        for (version in versions) {
            val chunk = runCatching { parse(version, shebangSource) }.getOrElse {
                fail("version=$version shebang source failed: ${it.message}")
            }
            val locals = chunk.body.statements.filterIsInstance<LocalStatement>()
            assertEquals(1, locals.size, "version=$version shebang expected one local")
            val local = locals.single()
            val name = assertIs<Identifier>(local.init.single())
            assertEquals("x", name.name)
            assertLocalStatementStartColumnUsable(local, name)
            assertUsableRange(local.range)

            // BOM-prefixed sources: TASK-547 clean parse on all supported versions.
            val bomLocalChunk = runCatching { parse(version, bomLocalSource) }.getOrElse {
                fail("version=$version BOM+local should parse cleanly: ${it.message}")
            }
            val bomLocals = bomLocalChunk.body.statements.filterIsInstance<LocalStatement>()
            assertEquals(1, bomLocals.size, "version=$version BOM+local expected one local")
            val bomLocal = bomLocals.single()
            val bomName = assertIs<Identifier>(bomLocal.init.single())
            assertEquals("x", bomName.name)
            assertLocalStatementStartColumnUsable(bomLocal, bomName)
            assertUsableRange(bomLocal.range)

            val bomShebangChunk = runCatching { parse(version, bomShebangSource) }.getOrElse {
                fail("version=$version BOM+shebang should parse cleanly: ${it.message}")
            }
            val shebang = assertIs<CommentStatement>(bomShebangChunk.body.statements[0])
            assertFalse(shebang.isDocComment, "version=$version BOM+shebang should be non-doc comment")
            assertEquals("#!/usr/bin/env lua", shebang.comment.trimEnd())
            assertUsableRange(shebang.range)

            val bomShebangLocals =
                bomShebangChunk.body.statements.filterIsInstance<LocalStatement>()
            assertEquals(
                1,
                bomShebangLocals.size,
                "version=$version BOM+shebang expected one local"
            )
            val recoveredLocal = bomShebangLocals.single()
            val recoveredName = assertIs<Identifier>(recoveredLocal.init.single())
            assertEquals("x", recoveredName.name)
            assertLocalStatementStartColumnUsable(recoveredLocal, recoveredName)
            assertUsableRange(recoveredLocal.range)
        }
    }

    /**
     * LocalStatement start column is product-dependent (keyword column 1 vs first name).
     * Lock usability + consistency with the first init identifier when ranges share a line.
     */
    private fun assertLocalStatementStartColumnUsable(local: LocalStatement, firstName: Identifier) {
        assertTrue(local.range.start.column >= 1, "local start column must be >= 1")
        assertTrue(firstName.range.start.column >= 1, "name start column must be >= 1")
        if (local.range.start.line == firstName.range.start.line) {
            assertTrue(
                local.range.start.column == 1 ||
                    local.range.start.column == firstName.range.start.column,
                "local start column ${local.range.start.column} should be 1 or name column ${firstName.range.start.column}"
            )
        }
    }

    private fun assertUsableRange(range: Range) {
        assertUsableRange(
            range.start.line,
            range.start.column,
            range.end.line,
            range.end.column
        )
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
}
