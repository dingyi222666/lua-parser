package io.github.dingyi222666.luaparser.sample

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/** Shares the parser's lexer, including AndroLua syntax and multiline delimiters. */
class LuaHighlightLanguage : EmptyLanguage() {
    private val analyzer = LuaHighlightAnalyzer()
    private val pairs = SymbolPairMatch.DefaultSymbolPairs()

    override fun getAnalyzeManager(): AnalyzeManager = analyzer
    override fun getSymbolPairs() = pairs
    override fun destroy() = analyzer.destroy()

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
        val lexer = LuaLexer(content.getLine(line).subSequence(0, column))
        var depth = 0
        while (true) {
            when (lexer.nextToken()) {
                LuaTokenTypes.EOF -> return depth.coerceAtLeast(0) * 4
                LuaTokenTypes.DO, LuaTokenTypes.THEN, LuaTokenTypes.FUNCTION,
                LuaTokenTypes.REPEAT, LuaTokenTypes.LCURLY -> depth++
                LuaTokenTypes.ELSE -> if (depth == 0) depth++
                LuaTokenTypes.END, LuaTokenTypes.UNTIL, LuaTokenTypes.RCURLY -> depth--
                else -> Unit
            }
        }
    }
}

internal class LuaHighlightAnalyzer : SimpleAnalyzeManager<Unit>() {
    private data class Token(
        val type: LuaTokenTypes, val offset: Int, val length: Int,
        val line: Int, val column: Int
    )

    private var previousSource = ""
    private var previousTokens = emptyList<Token>()

    override fun analyze(text: StringBuilder, delegate: Delegate<Unit>): Styles? =
        highlight(text.toString()) { delegate.isCancelled }

    internal fun highlight(input: String, cancelled: () -> Boolean = { false }): Styles? {
        val source = input.removePrefix("\uFEFF")
        var prefix = 0
        val commonLength = minOf(source.length, previousSource.length)
        while (prefix < commonLength && source[prefix] == previousSource[prefix]) prefix++
        val tokens = ArrayList<Token>()
        // Restart before the token touching the edit. A delimiter or identifier
        // can grow across the old boundary, including a multiline string opener.
        previousTokens.takeWhile { it.offset + it.length < prefix }.forEach(tokens::add)
        val lexer = LuaLexer(source)
        tokens.lastOrNull()?.let {
            lexer.restoreTo(it.offset, it.length, it.line, it.column)
        }
        while (!cancelled()) {
            val type = lexer.nextToken()
            if (type == LuaTokenTypes.EOF) break
            tokens += Token(type, lexer.index, lexer.tokenLength, lexer.tokenLine, lexer.tokenColumn)
        }
        if (cancelled()) return null
        val spans = MappedSpans.Builder()
        spans.addIfNeeded(0, 0, EditorColorScheme.TEXT_NORMAL.toLong())
        tokens.forEach {
            spans.addIfNeeded(it.line - 1, it.column, color(it.type).toLong())
        }
        spans.determine(lexer.tokenLine - 1)
        previousSource = source
        previousTokens = tokens
        return Styles(spans.build())
    }

    private fun color(type: LuaTokenTypes): Int = when (type) {
        LuaTokenTypes.SHORT_COMMENT, LuaTokenTypes.BLOCK_COMMENT, LuaTokenTypes.DOC_COMMENT,
        LuaTokenTypes.SHEBANG_CONTENT -> EditorColorScheme.COMMENT
        LuaTokenTypes.STRING, LuaTokenTypes.LONG_STRING, LuaTokenTypes.NUMBER -> EditorColorScheme.LITERAL
        LuaTokenTypes.NAME, LuaTokenTypes.WHITE_SPACE, LuaTokenTypes.NEW_LINE,
        LuaTokenTypes.BAD_CHARACTER -> EditorColorScheme.TEXT_NORMAL
        LuaTokenTypes.AND, LuaTokenTypes.OR, LuaTokenTypes.NOT, LuaTokenTypes.IN,
        LuaTokenTypes.IF, LuaTokenTypes.DO, LuaTokenTypes.END, LuaTokenTypes.NIL,
        LuaTokenTypes.FOR, LuaTokenTypes.ELSE, LuaTokenTypes.GOTO, LuaTokenTypes.CASE,
        LuaTokenTypes.TRUE, LuaTokenTypes.THEN, LuaTokenTypes.BREAK, LuaTokenTypes.LOCAL,
        LuaTokenTypes.FALSE, LuaTokenTypes.UNTIL, LuaTokenTypes.WHILE, LuaTokenTypes.RETURN,
        LuaTokenTypes.REPEAT, LuaTokenTypes.ELSEIF, LuaTokenTypes.CONTINUE,
        LuaTokenTypes.SWITCH, LuaTokenTypes.DEFAULT, LuaTokenTypes.FUNCTION,
        LuaTokenTypes.WHEN, LuaTokenTypes.LAMBDA -> EditorColorScheme.KEYWORD
        else -> EditorColorScheme.OPERATOR
    }
}
