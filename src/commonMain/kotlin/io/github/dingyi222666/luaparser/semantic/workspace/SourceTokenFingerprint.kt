package io.github.dingyi222666.luaparser.semantic.workspace

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range

/** One lexical index per revision; complete range hashes need no AST/type traversal. */
internal class SourceTokenFingerprint(source: String) {
    private val text = source.removePrefix("\uFEFF")
    private val lineStarts = buildList {
        add(0)
        text.forEachIndexed { index, char ->
            if (char == '\n' || char == '\r' && text.getOrNull(index + 1) != '\n') add(index + 1)
        }
    }
    private val starts: IntArray
    private val prefix: LongArray
    private val powers: LongArray

    init {
        val offsets = mutableListOf<Int>()
        val hashes = mutableListOf(0L)
        val weights = mutableListOf(1L)
        val lexer = LuaLexer(text)
        while (true) {
            val kind = lexer.nextToken()
            if (kind == LuaTokenTypes.EOF) break
            if (kind in TRIVIA) continue
            offsets += lexer.index
            var tokenHash = kind.ordinal.toLong() + 1
            lexer.tokenText.forEach { char -> tokenHash = tokenHash * BASE + char.code }
            hashes += hashes.last() * BASE + tokenHash
            weights += weights.last() * BASE
        }
        starts = offsets.toIntArray()
        prefix = hashes.toLongArray()
        powers = weights.toLongArray()
    }

    fun hash(range: Range): String {
        val first = lowerBound(offset(range.start))
        val end = lowerBound(offset(range.end))
        return (prefix[end] - prefix[first] * powers[end - first]).toULong().toString(16)
    }

    private fun offset(position: Position): Int {
        val line = position.line - 1
        require(line in lineStarts.indices) { "Source range outside revision: $position" }
        return (lineStarts[line] + position.column - 1).coerceAtMost(text.length)
    }

    private fun lowerBound(offset: Int): Int {
        var low = 0
        var high = starts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (starts[middle] < offset) low = middle + 1 else high = middle
        }
        return low
    }

    private companion object {
        const val BASE = 1_000_003L
        val TRIVIA = setOf(LuaTokenTypes.WHITE_SPACE, LuaTokenTypes.NEW_LINE,
            LuaTokenTypes.SHORT_COMMENT, LuaTokenTypes.BLOCK_COMMENT, LuaTokenTypes.DOC_COMMENT,
            LuaTokenTypes.SHEBANG_CONTENT)
    }
}
