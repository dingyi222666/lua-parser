package parser.lexer

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.comments
import parser.parse
import parser.renderShape
import parser.returnExpression

class LuaLexerLiteralCommentTddTest {

    @Test
    fun tokenizesLua53NumericLiteralForms() {
        assertTokenCases(
            LexerCase("decimal zero", "0", token(LuaTokenTypes.NUMBER, "0")),
            LexerCase("decimal integer", "42", token(LuaTokenTypes.NUMBER, "42")),
            LexerCase("decimal fraction", "3.1415", token(LuaTokenTypes.NUMBER, "3.1415")),
            LexerCase("leading dot fraction", ".5", token(LuaTokenTypes.NUMBER, ".5")),
            LexerCase("trailing dot fraction", "5.", token(LuaTokenTypes.NUMBER, "5.")),
            LexerCase("positive decimal exponent", "1e3", token(LuaTokenTypes.NUMBER, "1e3")),
            LexerCase("signed decimal exponent", "1E-3", token(LuaTokenTypes.NUMBER, "1E-3")),
            LexerCase("explicit positive exponent", "6.02e+23", token(LuaTokenTypes.NUMBER, "6.02e+23")),
            LexerCase("lowercase hex integer", "0x2a", token(LuaTokenTypes.NUMBER, "0x2a")),
            LexerCase("uppercase hex integer", "0X2A", token(LuaTokenTypes.NUMBER, "0X2A")),
            LexerCase("hex digits include a through f", "0xff", token(LuaTokenTypes.NUMBER, "0xff")),
            LexerCase("hex binary exponent", "0x1p4", token(LuaTokenTypes.NUMBER, "0x1p4")),
            LexerCase("hex fraction with signed exponent", "0x1.8p+2", token(LuaTokenTypes.NUMBER, "0x1.8p+2")),
            LexerCase("hex fraction without leading integer part", "0x.8p-1", token(LuaTokenTypes.NUMBER, "0x.8p-1"))
        )
    }

    @Test
    fun tokenizesShortStringsAndLua53Escapes() {
        assertTokenCases(
            LexerCase("empty double quoted string", "\"\"", token(LuaTokenTypes.STRING, "\"\"")),
            LexerCase("empty single quoted string", "''", token(LuaTokenTypes.STRING, "''")),
            LexerCase("plain double quoted text", "\"hello\"", token(LuaTokenTypes.STRING, "\"hello\"")),
            LexerCase("plain single quoted text", "'hello'", token(LuaTokenTypes.STRING, "'hello'")),
            LexerCase("escaped quote in double string", "\"say \\\"hi\\\"\"", token(LuaTokenTypes.STRING, "\"say \\\"hi\\\"\"")),
            LexerCase("escaped quote in single string", "'it\\'s'", token(LuaTokenTypes.STRING, "'it\\'s'")),
            LexerCase("escaped slash", "\"c:\\\\temp\"", token(LuaTokenTypes.STRING, "\"c:\\\\temp\"")),
            LexerCase("standard control escapes", "\"\\a\\b\\f\\n\\r\\t\\v\"", token(LuaTokenTypes.STRING, "\"\\a\\b\\f\\n\\r\\t\\v\"")),
            LexerCase("decimal escape sequence", "\"letter\\065\"", token(LuaTokenTypes.STRING, "\"letter\\065\"")),
            LexerCase("hex escape sequence", "\"letter\\x41\"", token(LuaTokenTypes.STRING, "\"letter\\x41\"")),
            LexerCase("unicode escape sequence", "\"letter\\u{41}\"", token(LuaTokenTypes.STRING, "\"letter\\u{41}\"")),
            LexerCase("z escape skips following whitespace", "\"trim\\z  \n spaces\"", token(LuaTokenTypes.STRING, "\"trim\\z  \n spaces\""))
        )
    }

    @Test
    fun tokenizesLongStringsCommentsDocCommentsAndShebangs() {
        assertTokenCases(
            LexerCase("empty long string", "[[]]", token(LuaTokenTypes.LONG_STRING, "[[]]")),
            LexerCase("multiline long string", "[[alpha\nbeta]]", token(LuaTokenTypes.LONG_STRING, "[[alpha\nbeta]]")),
            LexerCase("equals delimited long string", "[=[alpha [[ beta ]]]=]", token(LuaTokenTypes.LONG_STRING, "[=[alpha [[ beta ]]]=]")),
            LexerCase("nested equals delimiter long string", "[==[outer [=[inner]=] outer]==]", token(LuaTokenTypes.LONG_STRING, "[==[outer [=[inner]=] outer]==]")),
            LexerCase("short line comment", "-- line\nname", token(LuaTokenTypes.SHORT_COMMENT, "-- line"), token(LuaTokenTypes.NAME, "name")),
            LexerCase("plain block comment", "--[[block]] name", token(LuaTokenTypes.BLOCK_COMMENT, "--[[block]]"), token(LuaTokenTypes.NAME, "name")),
            LexerCase("equals delimited block comment", "--[=[block [=[ nested ]=] text]=] name", token(LuaTokenTypes.BLOCK_COMMENT, "--[=[block [=[ nested ]=] text]=]"), token(LuaTokenTypes.NAME, "name")),
            LexerCase("single doc comment", "--- doc\nlocal", token(LuaTokenTypes.DOC_COMMENT, "--- doc"), token(LuaTokenTypes.LOCAL, "local")),
            LexerCase("continued doc comment", "--- first\n--- second\nlocal", token(LuaTokenTypes.DOC_COMMENT, "--- first\n--- second"), token(LuaTokenTypes.LOCAL, "local")),
            LexerCase("shebang only at file start", "#!/usr/bin/env lua\nreturn", token(LuaTokenTypes.SHEBANG_CONTENT, "#!/usr/bin/env lua"), token(LuaTokenTypes.RETURN, "return")),
            LexerCase("hash after file start is getn", "return #items", token(LuaTokenTypes.RETURN, "return"), token(LuaTokenTypes.GETN, "#"), token(LuaTokenTypes.NAME, "items")),
            LexerCase("shebang after whitespace is not shebang", " #!/usr/bin/env lua", token(LuaTokenTypes.GETN, "#"), token(LuaTokenTypes.NOT, "!"), token(LuaTokenTypes.DIV, "/"), token(LuaTokenTypes.NAME, "usr"), token(LuaTokenTypes.DIV, "/"), token(LuaTokenTypes.NAME, "bin"), token(LuaTokenTypes.DIV, "/"), token(LuaTokenTypes.NAME, "env"), token(LuaTokenTypes.NAME, "lua")),
            LexerCase("comments do not swallow following operators", "-- line\nx = 1", token(LuaTokenTypes.SHORT_COMMENT, "-- line"), token(LuaTokenTypes.NAME, "x"), token(LuaTokenTypes.ASSIGN, "="), token(LuaTokenTypes.NUMBER, "1"))
        )
    }

    @Test
    fun tokenizesAndroLuaExtensionKeywordsOnlyWhenEnabled() {
        val source = "switch case default continue when lambda"

        assertContentEquals(
            listOf(
                token(LuaTokenTypes.SWITCH, "switch"),
                token(LuaTokenTypes.CASE, "case"),
                token(LuaTokenTypes.DEFAULT, "default"),
                token(LuaTokenTypes.CONTINUE, "continue"),
                token(LuaTokenTypes.WHEN, "when"),
                token(LuaTokenTypes.LAMBDA, "lambda")
            ),
            significantTokens(source, supportAndroLuaKeywords = true)
        )
        assertContentEquals(
            listOf(
                token(LuaTokenTypes.NAME, "switch"),
                token(LuaTokenTypes.NAME, "case"),
                token(LuaTokenTypes.NAME, "default"),
                token(LuaTokenTypes.NAME, "continue"),
                token(LuaTokenTypes.NAME, "when"),
                token(LuaTokenTypes.NAME, "lambda")
            ),
            significantTokens(source, supportAndroLuaKeywords = false)
        )
    }

    @Test
    fun reportsMalformedLiteralBoundaries() {
        assertMalformedCases(
            MalformedCase("unterminated double quoted string", "\"unterminated"),
            MalformedCase("single quoted string cannot contain bare newline", "'line\nbreak'"),
            MalformedCase("mismatched long string delimiter", "[=[mismatch]==]"),
            MalformedCase("unterminated long string delimiter", "[=[unterminated"),
            MalformedCase("hex literal requires digits", "0x"),
            MalformedCase("decimal exponent requires digits", "1e"),
            MalformedCase("hex exponent requires digits", "0x1p"),
            MalformedCase("invalid short string escape", "\"bad\\q\""),
            MalformedCase("invalid hex escape digits", "\"bad\\xG0\"")
        )
    }

    @Test
    fun parserPreservesResourceBackedLiteralAndCommentShapes() {
        val source = loadLexerFixture("literal_comment_corpus.lua")
        val expectedShape = loadLexerFixture("literal_comment_corpus.shape.txt").trimEnd()
        val chunk = parse(LuaVersion.LUA_5_3, source)

        assertEquals(expectedShape, renderShape(chunk).trimEnd())
        assertEquals(9, chunk.body.statements.size)

        val comments = chunk.comments()
        assertEquals(4, comments.size)
        assertContentEquals(listOf(false, false, true, false), comments.map { it.isDocComment })
        assertContentEquals(
            listOf("#!/usr/bin/env lua", "-- line comment", "---@class LexerFixture", "--[=[ block comment ]=]"),
            comments.map { it.comment.trimEnd() }
        )
    }

    @Test
    fun parserKeepsLiteralTokenTextOnAstConstants() {
        val chunk = parse(LuaVersion.LUA_5_3, loadLexerFixture("literal_comment_corpus.lua"))

        val decimal = localConstantAt(chunk, 3)
        val hex = localConstantAt(chunk, 4)
        val short = localConstantAt(chunk, 5)
        val single = localConstantAt(chunk, 6)
        val long = localConstantAt(chunk, 7)

        assertEquals(ConstantNode.TYPE.INTERGER, decimal.constantType)
        assertEquals(42, decimal.intOf())
        assertEquals("0x2a", hex.rawValue)
        assertEquals(ConstantNode.TYPE.STRING, short.constantType)
        assertEquals("\"line\\nfeed\"", short.rawValue)
        assertEquals("'quote\\'s'", single.rawValue)
        assertEquals("[=[alpha\nbeta]=]", long.rawValue)
    }

    @Test
    fun parserUsesLexerLiteralBoundariesForCallsAndConcat() {
        assertEquals(
            "Binary(..,Const(\"a\"),Const(\"b\"))",
            renderShape(parse(LuaVersion.LUA_5_3, "return \"a\" .. \"b\"").returnExpression())
        )
        assertEquals(
            "Call(StringCall(Id(print):Const('hello')):)",
            renderShape(parse(LuaVersion.LUA_5_3, "return print 'hello'").returnExpression())
        )
        assertEquals(
            "Const([=[line\ntext]=])",
            renderShape(parse(LuaVersion.LUA_5_3, "return [=[line\ntext]=]").returnExpression())
        )
    }

    private fun assertTokenCases(vararg cases: LexerCase) {
        val failures = cases.mapNotNull { case ->
            runCatching {
                assertContentEquals(case.expected, significantTokens(case.source), case.name)
            }.exceptionOrNull()?.let { failure ->
                "${case.name}\nsource: ${case.source}\n${failure.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    private fun assertMalformedCases(vararg cases: MalformedCase) {
        val failures = cases.mapNotNull { case ->
            runCatching {
                val result = runCatching { significantTokens(case.source) }

                result.onSuccess { tokens ->
                    assertTrue(
                        tokens.any { it.type == LuaTokenTypes.BAD_CHARACTER },
                        "Expected a BAD_CHARACTER token or lexer exception for ${case.name}, but got $tokens"
                    )
                }.onFailure { failure ->
                    assertTrue(
                        failure is IllegalStateException || failure is IllegalArgumentException,
                        "Expected a lexer literal exception for ${case.name}, but got ${failure::class.simpleName}: ${failure.message}"
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

    private fun significantTokens(source: String, supportAndroLuaKeywords: Boolean = true): List<TokenSnapshot> {
        val lexer = LuaLexer(source, supportAndroLuaKeywords = supportAndroLuaKeywords)
        val result = mutableListOf<TokenSnapshot>()

        while (true) {
            val type = lexer.nextToken()
            if (type == LuaTokenTypes.EOF) {
                return result
            }
            if (type != LuaTokenTypes.WHITE_SPACE && type != LuaTokenTypes.NEW_LINE) {
                result.add(token(type, normalizeTokenText(type, lexer.tokenText.toString())))
            }
        }
    }

    private fun normalizeTokenText(type: LuaTokenTypes, text: String): String {
        return when (type) {
            LuaTokenTypes.SHORT_COMMENT,
            LuaTokenTypes.DOC_COMMENT,
            LuaTokenTypes.SHEBANG_CONTENT -> text.trimEnd('\r', '\n')
            // Long-bracket spans keep embedded newlines in token text. Windows CRLF
            // checkouts must still match LF-only goldens for LONG_STRING / BLOCK_COMMENT
            // without changing product lexer preservation of explicit \r in inline sources.
            LuaTokenTypes.LONG_STRING,
            LuaTokenTypes.BLOCK_COMMENT -> normalizeLineEndings(text)
            else -> text
        }
    }

    private fun normalizeLineEndings(text: String): String {
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun localConstantAt(chunk: io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode, statementIndex: Int): ConstantNode {
        return assertIs<ConstantNode>(assertIs<LocalStatement>(chunk.body.statements[statementIndex]).variables.single())
    }

    private fun loadLexerFixture(name: String): String {
        val path = "/parser/tdd/lexer/$name"
        val raw = checkNotNull(javaClass.getResourceAsStream(path)) {
            "Missing lexer TDD fixture: $path"
        }.bufferedReader().use { it.readText() }
        // Windows checkouts / resource streams may deliver CRLF; normalize to LF so
        // multiline long-string AST rawValue goldens stay stable (TASK-634).
        return normalizeLineEndings(raw)
    }

    private class LexerCase(
        val name: String,
        val source: String,
        vararg expected: TokenSnapshot
    ) {
        val expected: List<TokenSnapshot> = expected.toList()
    }

    private data class MalformedCase(
        val name: String,
        val source: String
    )

    private data class TokenSnapshot(
        val type: LuaTokenTypes,
        val text: String
    )

    private companion object {
        fun token(type: LuaTokenTypes, text: String): TokenSnapshot = TokenSnapshot(type, text)
    }
}
