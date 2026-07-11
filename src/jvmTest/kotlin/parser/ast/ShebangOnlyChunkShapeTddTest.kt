package parser.ast

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.assertParseFails
import parser.parse
import parser.renderShape

/**
 * Parser shebang-only edge AST corpus (TASK-358).
 *
 * Locks the product surface for chunks whose sole significant content is a leading
 * `#!` shebang (and closely related empty / comment-only edges):
 *
 * - Leading `#!` at buffer offset 0 becomes a non-doc [CommentStatement].
 * - A shebang-only source still parses as a valid chunk (not empty, not rejected).
 * - Mid-file / indented `#!` is not shebang and fails strict parse.
 * - Shared [parser.renderShape] vocabulary: `Comment(line:#!/...)`.
 *
 * Test-only; production parser/lexer changes are out of scope unless review re-scopes.
 */
class ShebangOnlyChunkShapeTddTest {

    private val plainVersions = listOf(
        LuaVersion.LUA_5_3,
        LuaVersion.LUA_5_4,
        LuaVersion.ANDROLUA_5_3
    )

    // --- pure shebang-only chunks ----------------------------------------------------

    @Test
    fun shebangOnlyWithTrailingNewlineIsValidChunkShape() {
        val source = "#!/usr/bin/env lua\n"
        val chunk = parse(LuaVersion.LUA_5_3, source)

        assertEquals(1, chunk.body.statements.size)
        assertNull(chunk.body.returnStatement)

        val shebang = assertIs<CommentStatement>(chunk.body.statements.single())
        assertFalse(shebang.isDocComment)
        assertEquals("#!/usr/bin/env lua", shebang.comment.trimEnd())
        assertSame(chunk.body, shebang.parent)
        assertEquals(1, shebang.range.start.line)
        assertEquals(1, shebang.range.start.column)
        assertTrue(shebang.range.end.column >= shebang.range.start.column)

        assertEquals(
            "Chunk(Block[Comment(line:#!/usr/bin/env lua)])",
            renderShape(chunk)
        )
    }

    @Test
    fun shebangOnlyWithoutTrailingNewlineIsValidChunkShape() {
        val source = "#!/usr/bin/env lua"
        val chunk = parse(LuaVersion.LUA_5_3, source)

        val shebang = assertIs<CommentStatement>(chunk.body.statements.single())
        assertFalse(shebang.isDocComment)
        assertEquals("#!/usr/bin/env lua", shebang.comment.trimEnd())
        assertNull(chunk.body.returnStatement)
        assertEquals(
            "Chunk(Block[Comment(line:#!/usr/bin/env lua)])",
            renderShape(chunk)
        )
    }

    @Test
    fun minimalShebangOnlyBangIsAcceptedAsCommentShape() {
        // Product: SHEBANG_CONTENT requires `#` at offset 0 and next char `!`;
        // the rest of the line may be empty.
        val chunk = parse(LuaVersion.LUA_5_3, "#!")
        val shebang = assertIs<CommentStatement>(chunk.body.statements.single())
        assertFalse(shebang.isDocComment)
        assertEquals("#!", shebang.comment.trimEnd())
        assertEquals("Chunk(Block[Comment(line:#!)])", renderShape(chunk))
    }

    @Test
    fun shebangOnlyWithSpacesInPathKeepsFullLineText() {
        val source = "#!/usr/bin/env lua -i\n"
        val chunk = parse(LuaVersion.LUA_5_3, source)
        val shebang = assertIs<CommentStatement>(chunk.body.statements.single())
        assertEquals("#!/usr/bin/env lua -i", shebang.comment.trimEnd())
        assertEquals(
            "Chunk(Block[Comment(line:#!/usr/bin/env lua -i)])",
            renderShape(chunk)
        )
    }

    @Test
    fun shebangOnlyWithCrLfTerminatorIsValidChunkShape() {
        val chunk = parse(LuaVersion.LUA_5_3, "#!/usr/bin/lua\r\n")
        val shebang = assertIs<CommentStatement>(chunk.body.statements.single())
        assertFalse(shebang.isDocComment)
        assertEquals("#!/usr/bin/lua", shebang.comment.trimEnd())
        assertNull(chunk.body.returnStatement)
        assertEquals(
            "Chunk(Block[Comment(line:#!/usr/bin/lua)])",
            renderShape(chunk)
        )
    }

    // --- shebang + whitespace / comment-only companions ------------------------------

    @Test
    fun shebangFollowedByBlankLinesOnlyKeepsSingleCommentStatement() {
        val source = "#!/usr/bin/env lua\n\n\n"
        val chunk = parse(LuaVersion.LUA_5_3, source)

        assertEquals(1, chunk.body.statements.size)
        assertIs<CommentStatement>(chunk.body.statements.single())
        assertNull(chunk.body.returnStatement)
        assertEquals(
            "Chunk(Block[Comment(line:#!/usr/bin/env lua)])",
            renderShape(chunk)
        )
    }

    @Test
    fun shebangThenShortCommentOnlyShapesAsTwoLineComments() {
        val source = "#!/usr/bin/env lua\n-- trailing note\n"
        val chunk = parse(LuaVersion.LUA_5_3, source)

        assertEquals(2, chunk.body.statements.size)
        val shebang = assertIs<CommentStatement>(chunk.body.statements[0])
        val note = assertIs<CommentStatement>(chunk.body.statements[1])
        assertFalse(shebang.isDocComment)
        assertFalse(note.isDocComment)
        assertEquals("#!/usr/bin/env lua", shebang.comment.trimEnd())
        assertEquals("-- trailing note", note.comment.trimEnd())
        assertEquals(
            "Chunk(Block[Comment(line:#!/usr/bin/env lua);Comment(line:-- trailing note)])",
            renderShape(chunk)
        )
    }

    @Test
    fun shebangThenDocCommentOnlyKeepsDocFlagOnlyOnDocComment() {
        val source = "#!/usr/bin/env lua\n---@meta\n"
        val chunk = parse(LuaVersion.LUA_5_3, source)

        val shebang = assertIs<CommentStatement>(chunk.body.statements[0])
        val doc = assertIs<CommentStatement>(chunk.body.statements[1])
        assertFalse(shebang.isDocComment)
        assertTrue(doc.isDocComment)
        assertEquals(
            "Chunk(Block[Comment(line:#!/usr/bin/env lua);Comment(doc:---@meta)])",
            renderShape(chunk)
        )
    }

    // --- contrast with empty / executable companions ---------------------------------

    @Test
    fun emptySourceIsEmptyChunkNotShebang() {
        val empty = parse(LuaVersion.LUA_5_3, "")
        assertTrue(empty.body.statements.isEmpty())
        assertNull(empty.body.returnStatement)
        assertEquals("Chunk(Block[])", renderShape(empty))
    }

    @Test
    fun shebangOnlyDiffersFromEmptyAndFromShebangPlusStatement() {
        val only = renderShape(parse(LuaVersion.LUA_5_3, "#!/usr/bin/env lua\n"))
        val empty = renderShape(parse(LuaVersion.LUA_5_3, ""))
        val withLocal = renderShape(
            parse(LuaVersion.LUA_5_3, "#!/usr/bin/env lua\nlocal x = 1")
        )

        assertEquals("Chunk(Block[Comment(line:#!/usr/bin/env lua)])", only)
        assertEquals("Chunk(Block[])", empty)
        assertEquals(
            "Chunk(Block[Comment(line:#!/usr/bin/env lua);Local(Id(x)=Const(1))])",
            withLocal
        )
        assertTrue(only != empty)
        assertTrue(only != withLocal)
    }

    @Test
    fun shebangOnlyThenReturnKeepsReturnOnBlockNotStatementList() {
        val chunk = parse(LuaVersion.LUA_5_3, "#!/usr/bin/env lua\nreturn 1")
        assertEquals(1, chunk.body.statements.size)
        assertIs<CommentStatement>(chunk.body.statements.single())
        val ret = assertIs<ReturnStatement>(chunk.body.returnStatement!!)
        assertEquals("Return(Const(1))", renderShape(ret))
        assertEquals(
            "Chunk(Block[Comment(line:#!/usr/bin/env lua);Return(Const(1))])",
            renderShape(chunk)
        )
    }

    @Test
    fun shebangOnlyDoesNotInventLocalOrCallStatements() {
        val chunk = parse(LuaVersion.LUA_5_3, "#!/bin/lua\n")
        assertEquals(1, chunk.body.statements.size)
        assertIs<CommentStatement>(chunk.body.statements.single())
        assertTrue(chunk.body.statements.none { it is LocalStatement })
        assertNull(chunk.body.returnStatement)
    }

    // --- rejection edges (not shebang-only leading) ----------------------------------

    @Test
    fun midFileShebangIsRejectedByStrictParse() {
        // Mid-file #! is unary length + `!`, not SHEBANG_CONTENT.
        assertParseFails(LuaVersion.LUA_5_3, "print(1)\n#!/usr/bin/env lua")
        assertParseFails(LuaVersion.LUA_5_3, "local x = 1\n#!")
        plainVersions.forEach { version ->
            assertParseFails(version, "return\n#!/usr/bin/env lua")
        }
    }

    @Test
    fun leadingWhitespaceBeforeHashIsNotShebangAndFailsStrictParse() {
        // Product: shebang requires `#` at buffer offset 0.
        assertParseFails(LuaVersion.LUA_5_3, " #!/usr/bin/env lua")
        assertParseFails(LuaVersion.LUA_5_3, "\t#!/usr/bin/env lua")
        assertParseFails(LuaVersion.LUA_5_3, "\n#!/usr/bin/env lua")
    }

    @Test
    fun loneHashAtFileStartIsNotShebang() {
        // `#` alone is GETN / unary length, not SHEBANG_CONTENT without `!`.
        assertParseFails(LuaVersion.LUA_5_3, "#")
        assertParseFails(LuaVersion.LUA_5_3, "# /usr/bin/env lua\n")
    }

    // --- version portability + combined corpus ---------------------------------------

    @Test
    fun shebangOnlyShapesAreStableAcrossLuaVersions() {
        val cases = listOf(
            "#!" to "Chunk(Block[Comment(line:#!)])",
            "#!/usr/bin/env lua" to "Chunk(Block[Comment(line:#!/usr/bin/env lua)])",
            "#!/usr/bin/env lua\n" to "Chunk(Block[Comment(line:#!/usr/bin/env lua)])",
            "#!/usr/bin/lua\r\n" to "Chunk(Block[Comment(line:#!/usr/bin/lua)])",
            "#!/usr/bin/env lua\n\n" to "Chunk(Block[Comment(line:#!/usr/bin/env lua)])",
            "#!/usr/bin/env lua\n-- note" to
                "Chunk(Block[Comment(line:#!/usr/bin/env lua);Comment(line:-- note)])"
        )

        cases.forEach { (source, expected) ->
            plainVersions.forEach { version ->
                val chunk = parse(version, source)
                assertEquals(
                    expected,
                    renderShape(chunk),
                    "shape mismatch for `$source` under $version"
                )
                val first = assertIs<CommentStatement>(chunk.body.statements.first())
                assertFalse(first.isDocComment, "shebang must stay non-doc under $version")
            }
        }
    }

    @Test
    fun corpusCoversShebangOnlyEdgeFamiliesTogether() {
        assertCaseShapes(
            "minimal shebang" to (
                "#!" to "Chunk(Block[Comment(line:#!)])"
            ),
            "env shebang no newline" to (
                "#!/usr/bin/env lua" to
                    "Chunk(Block[Comment(line:#!/usr/bin/env lua)])"
            ),
            "env shebang with newline" to (
                "#!/usr/bin/env lua\n" to
                    "Chunk(Block[Comment(line:#!/usr/bin/env lua)])"
            ),
            "shebang with args" to (
                "#!/usr/bin/env lua -i\n" to
                    "Chunk(Block[Comment(line:#!/usr/bin/env lua -i)])"
            ),
            "shebang then blanks" to (
                "#!/bin/sh\n\n" to
                    "Chunk(Block[Comment(line:#!/bin/sh)])"
            ),
            "shebang then short comment" to (
                "#!/usr/bin/env lua\n-- only" to
                    "Chunk(Block[Comment(line:#!/usr/bin/env lua);Comment(line:-- only)])"
            ),
            "shebang then bare return" to (
                "#!/usr/bin/env lua\nreturn" to
                    "Chunk(Block[Comment(line:#!/usr/bin/env lua);Return()])"
            ),
            "shebang then local" to (
                "#!/usr/bin/env lua\nlocal ready = true" to
                    "Chunk(Block[Comment(line:#!/usr/bin/env lua);Local(Id(ready)=Const(true))])"
            ),
            "empty contrast" to (
                "" to "Chunk(Block[])"
            )
        )
    }

    @Test
    fun shebangOnlyExposesUsableRangeAndParentLinks() {
        val chunk = parse(LuaVersion.LUA_5_3, "#!/usr/bin/env lua\n")
        val shebang = assertIs<CommentStatement>(chunk.body.statements.single())

        assertSame(chunk, chunk.body.parent)
        assertSame(chunk.body, shebang.parent)
        assertTrue(shebang.range.start.line >= 1)
        assertTrue(shebang.range.start.column >= 1)
        assertTrue(shebang.range.end.line >= shebang.range.start.line)
        assertTrue(
            shebang.range.end.line > shebang.range.start.line ||
                shebang.range.end.column >= shebang.range.start.column
        )
        assertEquals(1, shebang.range.start.line)
        assertEquals(1, shebang.range.start.column)
    }

    private fun assertCaseShapes(vararg cases: Pair<String, Pair<String, String>>) {
        val failures = cases.mapNotNull { (name, case) ->
            val (source, expectedShape) = case
            runCatching {
                assertEquals(
                    expectedShape,
                    renderShape(parse(LuaVersion.LUA_5_3, source)),
                    name
                )
            }.exceptionOrNull()?.let { failure ->
                "$name\nsource: ${source.replace("\n", "\\n").replace("\r", "\\r")}\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }
}
