package io.github.dingyi222666.luaparser.lexer

import io.github.dingyi222666.luaparser.util.TrieTree
import kotlin.jvm.JvmOverloads


class LuaLexer @JvmOverloads constructor(
    source: CharSequence,
    private val supportAndroLuaKeywords: Boolean = true
) {

    /**
     * Source buffer with a single leading UTF-8 BOM (`U+FEFF`) stripped when present.
     * Editors/Android buffers often ship a BOM; stripping keeps shebang (`#!` at offset 0)
     * and identifiers (`local`/`print`) from gluing the BOM into a NAME token.
     * Mid-buffer `U+FEFF` is left unchanged.
     */
    private val source: CharSequence =
        if (source.isNotEmpty() && source[0] == BOM_CHAR) {
            source.subSequence(1, source.length)
        } else {
            source
        }

    private val bufferLen = this.source.length;

    private var offset = 0

    // Note: no cached last-token-type field — the type is returned per call and
    // the wrapper (WrapperLuaLexer.LexerState.type) is the single source of truth.

    var tokenLine = 1
        private set
    var tokenColumn = 0
        private set
    var index = 0
        private set

    var tokenLength = 0
        private set

    val tokenText: CharSequence
        get() {
            return source.subSequence(index, index + tokenLength)
        }

    fun hasLineBreakBeforeNextSignificantToken(fromIndex: Int): Boolean {
        var offset = fromIndex
        while (offset < bufferLen) {
            when (val ch = source[offset]) {
                ' ', '\t', '\u000C' -> offset++
                '\r', '\n' -> return true
                else -> return isWhitespace(ch)
            }
        }
        return false
    }

    fun nextToken(): LuaTokenTypes {
        var r = false
        for (i in offset..<offset + tokenLength) {
            val ch = source[i]
            if (ch == '\r') {
                r = true
                tokenLine++
                tokenColumn = 0
            } else if (ch == '\n') {
                if (r) {
                    r = false
                    continue
                }
                tokenLine++
                tokenColumn = 0
            } else {
                r = false
                tokenColumn++
            }
        }

        index += tokenLength
        offset += tokenLength

        if (offset >= bufferLen) {
            tokenLength = 0
            return LuaTokenTypes.EOF
        }

        val ch = source[offset]
        tokenLength = 1

        return when {
            // Non-newline whitespace only. `\r`/`\n` must not enter this branch or CRLF
            // becomes two tokens and the per-token line pass double-counts lines
            // (`\r` then `\n` each advance tokenLine because the CRLF latch resets).
            isNotNewLineWhiteSpace(ch) -> {
                while (offset + tokenLength < bufferLen &&
                    chatAtOrNull(offset + tokenLength)?.let { isNotNewLineWhiteSpace(it) } == true
                ) {
                    tokenLength++
                }
                LuaTokenTypes.WHITE_SPACE
            }

            isIdentifierStart(ch) -> scanIdentifier(ch)
            isPrimeDigit(ch) -> scanNumber(ch)
            // Dedicated NEW_LINE path: LF alone, CR alone, or CRLF as one logical newline.
            ch == '\n' -> LuaTokenTypes.NEW_LINE
            ch == '\r' -> {
                scanNewline()
                LuaTokenTypes.NEW_LINE
            }

            ch == ';' -> LuaTokenTypes.SEMI
            ch == '(' -> LuaTokenTypes.LPAREN
            ch == ')' -> LuaTokenTypes.RPAREN
            ch == '[' -> {
                val next = chatAtOrNull() ?: return LuaTokenTypes.LBRACK

                if (next != '=' && next != '[') {
                    return LuaTokenTypes.LBRACK
                }

                return scanLongString()

            }

            ch == ']' -> LuaTokenTypes.RBRACK
            ch == '{' -> LuaTokenTypes.LCURLY
            ch == '}' -> LuaTokenTypes.RCURLY
            ch == ',' -> LuaTokenTypes.COMMA
            // AndroLua / Android-Lua accept C-style `!=` as inequality (main2.lua).
            // Bare `!` remains unary NOT (shebang mid-file splits, C-style not).
            ch == '!' -> scanTwoOperator(LuaTokenTypes.NOT, LuaTokenTypes.NE, '=')
            ch == '+' -> scanTwoOperator(LuaTokenTypes.PLUS, LuaTokenTypes.ADD_ASSIGN, '=')
            ch == '*' -> scanTwoOperator(LuaTokenTypes.MULT, LuaTokenTypes.MUL_ASSIGN, '=')
            ch == '/' -> scanDIV()
            ch == '=' -> scanTwoOperator(LuaTokenTypes.ASSIGN, LuaTokenTypes.EQ, '=')
            ch == '^' -> LuaTokenTypes.EXP
            ch == '%' -> LuaTokenTypes.MOD
            ch == '~' -> scanTwoOperator(LuaTokenTypes.BIT_TILDE, LuaTokenTypes.NE, '=')
            // AndroLua / Android-Lua accept C-style `&&` as logical and (file.lua).
            // Bare `&` remains bitwise AND.
            ch == '&' -> scanTwoOperator(LuaTokenTypes.BIT_AND, LuaTokenTypes.AND, '&')
            // AndroLua / Android-Lua accept C-style `||` as logical or.
            // Bare `|` remains bitwise OR.
            ch == '|' -> scanTwoOperator(LuaTokenTypes.BIT_OR, LuaTokenTypes.OR, '|')
            ch == '>' -> scanAngleOperator(LuaTokenTypes.GT, LuaTokenTypes.GE, LuaTokenTypes.BIT_RTRT, '>')
            ch == '<' -> scanAngleOperator(LuaTokenTypes.LT, LuaTokenTypes.LE, LuaTokenTypes.BIT_LTLT, '<')

            ch == '.' -> {
                val next = chatAtOrNull() ?: return LuaTokenTypes.DOT

                when {
                    next == '.' -> {
                        if (chatAtOrNull(offset + tokenLength + 1) == '.') {
                            tokenLength += 2
                            LuaTokenTypes.ELLIPSIS
                        } else {
                            tokenLength++
                            LuaTokenTypes.CONCAT
                        }
                    }

                    isPrimeDigit(next) -> {
                        scanDigits { isPrimeDigit(it) }
                        if (scanDecimalExponentIfPresent()) LuaTokenTypes.NUMBER else LuaTokenTypes.BAD_CHARACTER
                    }

                    else -> LuaTokenTypes.DOT
                }


            }

            ch == '"' || ch == '\'' -> scanString(ch)
            ch == '#' -> {
                if (offset == 0 && chatAtOrNull() == '!') {
                    tokenLength++
                    while (offset + tokenLength < bufferLen) {
                        val shebangChar = charAt()
                        if (shebangChar == '\n' || shebangChar == '\r') {
                            break
                        }
                        tokenLength++
                    }
                    LuaTokenTypes.SHEBANG_CONTENT
                } else {
                    LuaTokenTypes.GETN
                }
            }
            ch == ':' -> scanTwoOperator(LuaTokenTypes.COLON, LuaTokenTypes.DOUBLE_COLON, ':')

            ch == '-' -> {
                val next = chatAtOrNull() ?: return LuaTokenTypes.MINUS
                when (next) {
                    '-' -> {
                        tokenLength++
                        scanComment()
                    }

                    '=' -> {
                        tokenLength++
                        LuaTokenTypes.SUB_ASSIGN
                    }

                    else -> LuaTokenTypes.MINUS
                }
            }

            else -> LuaTokenTypes.BAD_CHARACTER
        }

    }

    private fun scanIdentifier(char: Char): LuaTokenTypes {
        var ch = char
        var n: TrieTree.Node<LuaTokenTypes>? = keywords.root.map.get(ch)
        while (offset + tokenLength < bufferLen && isIdentifierPart(
                source[offset + tokenLength].also { ch = it })
        ) {
            tokenLength++
            n = n?.map?.get(ch)
        }
        val token = n?.token ?: LuaTokenTypes.NAME
        return if (supportAndroLuaKeywords || !isAndroLuaKeyword(token)) token else LuaTokenTypes.NAME
    }


    private fun scanString(start: Char): LuaTokenTypes {
        while (offset + tokenLength < bufferLen) {
            val ch = charAt()
            when (ch) {
                start -> {
                    tokenLength++
                    return LuaTokenTypes.STRING
                }
                // escape
                '\\' -> {
                    if (!scanStringEscape()) {
                        return LuaTokenTypes.BAD_CHARACTER
                    }
                }

                '\n', '\r' -> return LuaTokenTypes.BAD_CHARACTER

                else -> tokenLength++
            }
        }

        return LuaTokenTypes.BAD_CHARACTER
    }

    private fun scanStringEscape(): Boolean {
        val escapeOffset = offset + tokenLength
        val nextOffset = escapeOffset + 1
        if (nextOffset >= bufferLen) {
            tokenLength++
            return false
        }

        return when (val next = source[nextOffset]) {
            'a', 'b', 'f', 'n', 'r', 't', 'v', '\'', '"', '\\' -> {
                tokenLength += 2
                true
            }

            '\n', '\r' -> {
                // `\` followed by a line terminator: CRLF pairs are one logical escape.
                val paired = if (next == '\n') '\r' else '\n'
                tokenLength += if (nextOffset + 1 < bufferLen && source[nextOffset + 1] == paired) 3 else 2
                true
            }

            'z' -> {
                tokenLength += 2
                while (offset + tokenLength < bufferLen && isWhitespace(source[offset + tokenLength])) {
                    tokenLength++
                }
                true
            }

            'x' -> {
                if (nextOffset + 2 >= bufferLen ||
                    !isHexDigit(source[nextOffset + 1]) ||
                    !isHexDigit(source[nextOffset + 2])
                ) {
                    tokenLength = (nextOffset + 1 - offset).coerceAtMost(bufferLen - offset)
                    false
                } else {
                    tokenLength += 4
                    true
                }
            }

            'u' -> scanUnicodeEscape(nextOffset)

            in '0'..'9' -> {
                tokenLength += 2
                var digits = 1
                while (digits < 3 &&
                    offset + tokenLength < bufferLen &&
                    source[offset + tokenLength] in '0'..'9'
                ) {
                    tokenLength++
                    digits++
                }
                val value = source.substring(nextOffset, nextOffset + digits).toIntOrNull()
                value != null && value <= 0xFF
            }

            else -> {
                tokenLength += 2
                false
            }
        }
    }

    private fun scanUnicodeEscape(uOffset: Int): Boolean {
        var cursor = uOffset + 1
        if (cursor >= bufferLen || source[cursor] != '{') {
            tokenLength = (cursor - offset).coerceAtMost(bufferLen - offset)
            return false
        }

        cursor++
        val firstDigit = cursor
        while (cursor < bufferLen && isHexDigit(source[cursor])) {
            cursor++
        }

        if (cursor == firstDigit || cursor >= bufferLen || source[cursor] != '}') {
            tokenLength = (cursor - offset).coerceAtLeast(1).coerceAtMost(bufferLen - offset)
            return false
        }

        tokenLength = cursor + 1 - offset
        val value = source.substring(firstDigit, cursor).toLongOrNull(16)
        return value != null && value <= 0x10FFFF
    }


    private fun scanComment(): LuaTokenTypes {
        if (tokenLength + offset == bufferLen) {
            return LuaTokenTypes.SHORT_COMMENT
        }

        val next = charAt()

        when (next) {
            '[' -> {
                val longBracketStart = offset + tokenLength
                if (longBracketEqualsCount(longBracketStart) >= 0) {
                    return scanLongBracket(longBracketStart, LuaTokenTypes.BLOCK_COMMENT)
                }
                return scanShortComment()
            }
            '-' -> {
                // This is the third dash, so it's a doc comment
                tokenLength++

                // Scan first line content until newline
                tokenLength = lineRestEnd(offset + tokenLength) - offset

                // Look for continuation lines
                while (offset + tokenLength < bufferLen) {
                    var pos = offset + tokenLength

                    // Skip whitespace at start of line (`isNotNewLineWhiteSpace` already
                    // excludes the line terminators).
                    while (pos < bufferLen && isNotNewLineWhiteSpace(source[pos])) {
                        pos++
                    }

                    // Check for doc comment continuation (---)
                    if (pos + 2 < bufferLen &&
                        source[pos] == '-' &&
                        source[pos + 1] == '-' &&
                        source[pos + 2] == '-') {

                        // Include this line in token (through its line terminator)
                        tokenLength = lineRestEnd(pos + 3) - offset
                    } else {
                        break
                    }
                }

                return LuaTokenTypes.DOC_COMMENT
            }
            else -> {
                return scanShortComment()
            }
        }
    }

    private fun scanLongString(): LuaTokenTypes {
        if (longBracketEqualsCount(offset) < 0) {
            return LuaTokenTypes.LBRACK
        }

        return scanLongBracket(offset, LuaTokenTypes.LONG_STRING)
    }

    private fun scanLongBracket(longBracketStart: Int, tokenType: LuaTokenTypes): LuaTokenTypes {
        val equalsCount = longBracketEqualsCount(longBracketStart)
        if (equalsCount < 0) {
            return LuaTokenTypes.BAD_CHARACTER
        }

        var cursor = longBracketStart + equalsCount + 2
        // Scan until the exact same-level close is found. Must not early-exit on a
        // wrong-level close while scanning: a well-formed body may embed lower/higher
        // closes before the true same-level terminator (e.g. [=[keep ]==] still]=]).
        // BLOCK_COMMENT follows the exact same rule: real Lua block comments do NOT
        // nest, so a comment ends at the FIRST same-level close. An inner same-level
        // `[=*[` open inside a comment body is inert text, and
        // `--[[ x [[\n--]] code_here() ]]` must end the comment at `--]]` and leave
        // code_here() executable. The former BLOCK_COMMENT "nesting" heuristic
        // (same-level open + later outer close => treat the whole span as one
        // comment) silently changed program meaning and was also quadratic on
        // unclosed comments (every same-level open without an outer close re-scanned
        // the rest of the buffer). When no matching close exists (true unclosed or
        // pure level-mismatch), the BAD_CHARACTER span consumes through EOF so
        // lower-level close noise and trailing source are not re-lexed as
        // RBRACK/EQ/NAME/print(...) (TASK-633 remainder-consume contract for
        // parser.lexer long-bracket suites).
        while (cursor < bufferLen) {
            val closeLength = longBracketCloseLength(cursor, equalsCount)
            if (closeLength > 0) {
                tokenLength = cursor + closeLength - offset
                return tokenType
            }

            cursor++
        }

        tokenLength = bufferLen - offset
        return LuaTokenTypes.BAD_CHARACTER
    }

    private fun longBracketEqualsCount(start: Int): Int {
        if (start >= bufferLen || source[start] != '[') {
            return -1
        }

        var cursor = start + 1
        var equalsCount = 0
        while (cursor < bufferLen && source[cursor] == '=') {
            cursor++
            equalsCount++
        }

        return if (cursor < bufferLen && source[cursor] == '[') equalsCount else -1
    }

    private fun longBracketCloseLength(start: Int, equalsCount: Int): Int {
        if (start >= bufferLen || source[start] != ']') {
            return -1
        }

        var cursor = start + 1
        repeat(equalsCount) {
            if (cursor >= bufferLen || source[cursor] != '=') {
                return -1
            }
            cursor++
        }

        return if (cursor < bufferLen && source[cursor] == ']') equalsCount + 2 else -1
    }

    private fun scanShortComment(): LuaTokenTypes {
        tokenLength = lineRestEnd(offset + tokenLength) - offset
        return LuaTokenTypes.SHORT_COMMENT
    }

    /**
     * Index just past the end of the source line whose first character is at [start]:
     * consumes a trailing CRLF pair as one terminator, LF or CR alone as one, and
     * returns [bufferLen] when the line runs to EOF (no terminator).
     */
    private fun lineRestEnd(start: Int): Int {
        var pos = start
        while (pos < bufferLen) {
            when (source[pos]) {
                '\r' -> return if (pos + 1 < bufferLen && source[pos + 1] == '\n') pos + 2 else pos + 1
                '\n' -> return pos + 1
                else -> pos++
            }
        }
        return pos
    }

    private fun scanDIV(): LuaTokenTypes {
        val next = chatAtOrNull() ?: return LuaTokenTypes.DIV

        return when (next) {
            '=' -> {
                tokenLength++
                LuaTokenTypes.DIV_ASSIGN
            }

            '/' -> {
                tokenLength++
                scanTwoOperator(
                    LuaTokenTypes.DOUBLE_DIV,
                    LuaTokenTypes.DOUBLE_DIV_ASSIGN, '='
                )
            }

            else -> LuaTokenTypes.DIV

        }
    }

    private fun scanTwoOperator(
        first: LuaTokenTypes, second: LuaTokenTypes, operator: Char
    ): LuaTokenTypes {
        if (tokenLength + offset == bufferLen) {
            // The operator is the last token in the buffer
            return first
        }

        if (charAt() == operator) {
            tokenLength++
            return second
        }

        return first
    }

    private fun scanAngleOperator(
        single: LuaTokenTypes,
        assign: LuaTokenTypes,
        repeated: LuaTokenTypes,
        operator: Char
    ): LuaTokenTypes {
        if (tokenLength + offset == bufferLen) {
            return single
        }

        return when (charAt()) {
            '=' -> {
                tokenLength++
                assign
            }

            operator -> {
                tokenLength++
                repeated
            }

            else -> single
        }
    }

    @Suppress("SameReturnValue")
    private fun scanNumber(char: Char): LuaTokenTypes {
        if (char == '0' && offset + tokenLength < bufferLen && (charAt() == 'x' || charAt() == 'X')) {
            tokenLength++
            val leadingDigits = scanDigits { isHexDigit(it) }
            var trailingDigits = 0

            if (offset + tokenLength < bufferLen && charAt() == '.') {
                tokenLength++
                trailingDigits = scanDigits { isHexDigit(it) }
            }

            if (leadingDigits + trailingDigits == 0) {
                return LuaTokenTypes.BAD_CHARACTER
            }

            return if (scanHexExponentIfPresent()) LuaTokenTypes.NUMBER else LuaTokenTypes.BAD_CHARACTER
        }

        scanDigits { isPrimeDigit(it) }

        if (offset + tokenLength < bufferLen &&
            charAt() == '.' &&
            chatAtOrNull(offset + tokenLength + 1) != '.'
        ) {
            tokenLength++
            scanDigits { isPrimeDigit(it) }
        }

        return if (scanDecimalExponentIfPresent()) LuaTokenTypes.NUMBER else LuaTokenTypes.BAD_CHARACTER
    }

    /** Consume the maximal run of digits accepted by [isDigit] at the cursor; returns the count. */
    private fun scanDigits(isDigit: (Char) -> Boolean): Int {
        var count = 0
        while (offset + tokenLength < bufferLen && isDigit(charAt())) {
            tokenLength++
            count++
        }
        return count
    }

    /**
     * Shared exponent scan for decimal (`e`/`E`) and hex (`p`/`P`) number lexemes.
     * An absent exponent marker still yields a valid number; a present marker must
     * be followed by at least one digit (after an optional sign) or the lexeme is
     * malformed.
     */
    private fun scanExponentIfPresent(firstMarker: Char, secondMarker: Char): Boolean {
        if (offset + tokenLength >= bufferLen) {
            return true
        }

        val ch = charAt()
        if (ch != firstMarker && ch != secondMarker) {
            return true
        }

        tokenLength++
        scanExponentSign()
        return scanDigits { isPrimeDigit(it) } > 0
    }

    private fun scanDecimalExponentIfPresent(): Boolean = scanExponentIfPresent('e', 'E')

    private fun scanHexExponentIfPresent(): Boolean = scanExponentIfPresent('p', 'P')

    private fun scanExponentSign() {
        if (offset + tokenLength < bufferLen && (charAt() == '+' || charAt() == '-')) {
            tokenLength++
        }
    }

    fun pushBack(length: Int) {
        require(length <= tokenLength) { "pushBack length too large" }
        tokenLength -= length
    }

    /**
     * Restore the lexer to a captured token-start state: the exact cursor tuple
     * visible right after [nextToken] returned the token starting at [index].
     *
     * Field mapping (one assignment each, no logic):
     * - [index] -> [index] and -> [offset]. The `offset == index` invariant holds
     *   because both start at 0 and are only ever advanced together by
     *   `+= tokenLength` in [nextToken]; every field a restore needs is
     *   therefore captured by the [index]/[tokenLength]/[line]/[column] tuple
     *   (the same tuple a `LexerState` snapshot stores).
     * - [tokenLength] -> [tokenLength], [line] -> [tokenLine], [column] -> [tokenColumn].
     *
     * The token type needs no restore: it is returned per [nextToken] call and the
     * next call recomputes it.
     *
     * The next [nextToken] call re-counts newlines across the restored token span,
     * moves `index`/`offset` past it, and scans the following token — identical to
     * replaying from the original call. This is the primitive a deep-probe
     * snapshot/restore uses instead of history-based back-stepping.
     */
    fun restoreTo(index: Int, tokenLength: Int, line: Int, column: Int) {
        this.index = index
        offset = index
        this.tokenLength = tokenLength
        tokenLine = line
        tokenColumn = column
    }

    private fun scanNewline() {
        if (offset + tokenLength < bufferLen && source[offset + tokenLength] == '\n') {
            tokenLength++
        }
    }


    private fun charAt(): Char {
        return source[offset + tokenLength]
    }

    private fun chatAtOrNull(): Char? {
        return chatAtOrNull(offset + tokenLength)
    }

    private fun chatAtOrNull(i: Int): Char? {
        return if (i < bufferLen) source[i] else null
    }

    companion object {
        /** UTF-8 BOM character (`U+FEFF`). Leading occurrence is stripped from the source buffer. */
        private const val BOM_CHAR: Char = '\uFEFF'

        private val keywords = TrieTree<LuaTokenTypes>()

        init {
            // (spelling, token) pairs keep the keyword table dense; TrieTree insertion
            // order (and therefore prefix resolution) is unchanged.
            listOf(
                "and" to LuaTokenTypes.AND, "or" to LuaTokenTypes.OR,
                "default" to LuaTokenTypes.DEFAULT, "switch" to LuaTokenTypes.SWITCH,
                "if" to LuaTokenTypes.IF, "break" to LuaTokenTypes.BREAK,
                "else" to LuaTokenTypes.ELSE, "while" to LuaTokenTypes.WHILE,
                "do" to LuaTokenTypes.DO, "return" to LuaTokenTypes.RETURN,
                "for" to LuaTokenTypes.FOR, "function" to LuaTokenTypes.FUNCTION,
                "local" to LuaTokenTypes.LOCAL, "true" to LuaTokenTypes.TRUE,
                "false" to LuaTokenTypes.FALSE, "nil" to LuaTokenTypes.NIL,
                "continue" to LuaTokenTypes.CONTINUE, "not" to LuaTokenTypes.NOT,
                "in" to LuaTokenTypes.IN, "then" to LuaTokenTypes.THEN,
                "end" to LuaTokenTypes.END, "repeat" to LuaTokenTypes.REPEAT,
                "elseif" to LuaTokenTypes.ELSEIF, "until" to LuaTokenTypes.UNTIL,
                "goto" to LuaTokenTypes.GOTO, "case" to LuaTokenTypes.CASE,
                "when" to LuaTokenTypes.WHEN, "lambda" to LuaTokenTypes.LAMBDA,
            ).forEach { (spelling, tokenType) -> keywords.put(spelling, tokenType) }
        }

        private fun isHexDigit(c: Char): Boolean {
            return ((c in '0'..'9') || (c in 'A'..'F') || (c in 'a'..'f'))
        }

        private fun isPrimeDigit(c: Char): Boolean {
            return (c in '0'..'9')
        }

        private fun isWhitespace(c: Char): Boolean {
            return (c == '\n' || c == '\r' || c == '\t' || c == ' ' || c == '\u000c')
        }

        private fun isNotNewLineWhiteSpace(c: Char): Boolean {
            return (c == '\t' || c == ' ' || c == '\u000c')
        }

        private fun isIdentifierStart(c: Char): Boolean {
            return (c >= '\u0080') || (c in 'a'..'z') || (c in 'A'..'Z') || (c == '_') || (c == '$')
        }

        private fun isIdentifierPart(c: Char): Boolean {
            return (c in '0'..'9') || isIdentifierStart(c)
        }

        internal fun isAndroLuaKeyword(tokenType: LuaTokenTypes): Boolean {
            return when (tokenType) {
                LuaTokenTypes.CASE,
                LuaTokenTypes.CONTINUE,
                LuaTokenTypes.DEFAULT,
                LuaTokenTypes.LAMBDA,
                LuaTokenTypes.SWITCH,
                LuaTokenTypes.WHEN -> true

                else -> false
            }
        }

    }

}


enum class LuaTokenTypes {
    SHEBANG_CONTENT, NEW_LINE, WHITE_SPACE, BAD_CHARACTER,

    ADD_ASSIGN, SUB_ASSIGN, MUL_ASSIGN, DIV_ASSIGN, DOUBLE_DIV_ASSIGN,

    NAME, NUMBER, PLUS, DOT, MINUS, LBRACK, ASSIGN, RBRACK, GETN, NOT, GT, LT, BIT_TILDE, MULT, MOD, DIV, LPAREN, RPAREN, LCURLY, RCURLY, COMMA, SEMI, COLON, EXP, BIT_AND, BIT_OR, STRING, LONG_STRING, CONCAT, IN, IF, OR, DO, EQ, NE, GE, BIT_RTRT, LE, BIT_LTLT, DOUBLE_DIV, DOUBLE_COLON, AND, SHORT_COMMENT, ELLIPSIS, END, NIL, FOR, DOC_COMMENT, ELSE, GOTO, CASE, TRUE, THEN, BLOCK_COMMENT, BREAK, LOCAL, FALSE, UNTIL, WHILE, RETURN, REPEAT, ELSEIF, CONTINUE, SWITCH, DEFAULT, FUNCTION, WHEN, LAMBDA, EOF
}
