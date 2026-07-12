package io.github.dingyi222666.luaparser.lexer

import io.github.dingyi222666.luaparser.util.TrieTree
import kotlin.jvm.JvmOverloads


class LuaLexer @JvmOverloads constructor(
    source: CharSequence,
    private val supportAndroLuaKeywords: Boolean = true
) : Iterator<Pair<LuaTokenTypes, String>> {

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

    private var tokenType: LuaTokenTypes =
        LuaTokenTypes.WHITE_SPACE

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
        return nextTokenInternal().also { tokenType = it }
    }

    private fun nextTokenInternal(): LuaTokenTypes {
        run {
            var r = false
            for (i in offset..<offset + tokenLength) {
                val ch = charAt(i)
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
            ch == '!' -> scanTwoOperator(
                LuaTokenTypes.NOT,
                LuaTokenTypes.NE, '='
            )
            ch == '+' -> scanTwoOperator(
                LuaTokenTypes.PLUS,
                LuaTokenTypes.ADD_ASSIGN, '='
            )

            ch == '*' -> scanTwoOperator(
                LuaTokenTypes.MULT,
                LuaTokenTypes.MUL_ASSIGN, '='
            )

            ch == '/' -> scanDIV()
            ch == '=' -> scanTwoOperator(
                LuaTokenTypes.ASSIGN,
                LuaTokenTypes.EQ, '='
            )

            ch == '^' -> LuaTokenTypes.EXP
            ch == '%' -> LuaTokenTypes.MOD
            ch == '~' -> scanTwoOperator(
                LuaTokenTypes.BIT_TILDE,
                LuaTokenTypes.NE, '='
            )
            // AndroLua / Android-Lua accept C-style `&&` as logical and (file.lua).
            // Bare `&` remains bitwise AND.
            ch == '&' -> scanTwoOperator(
                LuaTokenTypes.BIT_AND,
                LuaTokenTypes.AND, '&'
            )
            // AndroLua / Android-Lua accept C-style `||` as logical or.
            // Bare `|` remains bitwise OR.
            ch == '|' -> scanTwoOperator(
                LuaTokenTypes.BIT_OR,
                LuaTokenTypes.OR, '|'
            )
            ch == '>' -> scanAngleOperator(
                single = LuaTokenTypes.GT,
                assign = LuaTokenTypes.GE,
                repeated = LuaTokenTypes.BIT_RTRT,
                operator = '>'
            )

            ch == '<' -> scanAngleOperator(
                single = LuaTokenTypes.LT,
                assign = LuaTokenTypes.LE,
                repeated = LuaTokenTypes.BIT_LTLT,
                operator = '<'
            )

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

                    isPrimeDigit(next) -> scanNumberStartingWithDot()

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
            ch == ':' -> scanTwoOperator(
                LuaTokenTypes.COLON,
                LuaTokenTypes.DOUBLE_COLON, ':'
            )

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
                charAt(offset + tokenLength).also { ch = it })
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

            '\n' -> {
                tokenLength += 2
                true
            }

            '\r' -> {
                tokenLength += if (nextOffset + 1 < bufferLen && source[nextOffset + 1] == '\n') 3 else 2
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
                true
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
        return true
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
                while (offset + tokenLength < bufferLen) {
                    val ch = charAt()
                    if (ch == '\n' || ch == '\r') {
                        if (ch == '\r' && offset + tokenLength + 1 < bufferLen && source[offset + tokenLength + 1] == '\n') {
                            tokenLength += 2
                        } else {
                            tokenLength++
                        }
                        break
                    }
                    tokenLength++
                }
                
                // Look for continuation lines
                while (offset + tokenLength < bufferLen) {
                    var pos = offset + tokenLength
                    
                    // Skip whitespace at start of line
                    while (pos < bufferLen && source[pos] != '\n' && source[pos] != '\r' && isNotNewLineWhiteSpace(source[pos])) {
                        pos++
                    }
                    
                    // Check for doc comment continuation (---)
                    if (pos + 2 < bufferLen &&
                        source[pos] == '-' && 
                        source[pos + 1] == '-' &&
                        source[pos + 2] == '-') {
                        
                        pos += 3
                        
                        // Include this line in token
                        while (pos < bufferLen) {
                            if (source[pos] == '\n' || source[pos] == '\r') {
                                if (source[pos] == '\r' && pos + 1 < bufferLen && source[pos + 1] == '\n') {
                                    pos += 2
                                } else {
                                    pos++
                                }
                                break
                            }
                            pos++
                        }
                        
                        tokenLength = pos - offset
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
        var nestedDepth = 0
        // First complete close delimiter whose equals level differs from [equalsCount].
        // Used only when the matching close is never found: end the BAD span there so
        // trailing source stays lexable (TASK-595 / TASK-619). Applies both to pure
        // level-mismatch forms and to unclosed bodies that contain full lower/higher
        // closes (e.g. [======[L6 ]=====] open). Must not early-exit on mismatch
        // while scanning — a well-formed body may embed lower/higher closes before
        // the true same-level terminator (e.g. [=[keep ]==] still]=]).
        var firstMismatchEnd = -1
        while (cursor < bufferLen) {
            if (tokenType == LuaTokenTypes.BLOCK_COMMENT &&
                longBracketEqualsCount(cursor) == equalsCount &&
                (nestedDepth > 0 || hasOuterCloseAfterNestedLongBracket(cursor, equalsCount))
            ) {
                nestedDepth++
                cursor += equalsCount + 2
                continue
            }

            val closeLength = longBracketCloseLength(cursor, equalsCount)
            if (closeLength > 0) {
                if (nestedDepth > 0) {
                    nestedDepth--
                    cursor += closeLength
                    continue
                }
                tokenLength = cursor + closeLength - offset
                return tokenType
            }

            if (nestedDepth == 0 && firstMismatchEnd < 0) {
                val mismatchCloseLength = anyLongBracketCloseLength(cursor)
                if (mismatchCloseLength > 0) {
                    firstMismatchEnd = cursor + mismatchCloseLength
                }
            }
            cursor++
        }

        if (firstMismatchEnd > offset) {
            tokenLength = firstMismatchEnd - offset
            return LuaTokenTypes.BAD_CHARACTER
        }

        tokenLength = bufferLen - offset
        return LuaTokenTypes.BAD_CHARACTER
    }

    private fun hasOuterCloseAfterNestedLongBracket(nestedStart: Int, equalsCount: Int): Boolean {
        var cursor = nestedStart + equalsCount + 2
        var depth = 1
        while (cursor < bufferLen) {
            if (longBracketEqualsCount(cursor) == equalsCount) {
                depth++
                cursor += equalsCount + 2
                continue
            }

            val closeLength = longBracketCloseLength(cursor, equalsCount)
            if (closeLength > 0) {
                depth--
                cursor += closeLength
                if (depth == 0) {
                    return hasLongBracketCloseAtOrAfter(cursor, equalsCount)
                }
                continue
            }

            cursor++
        }

        return false
    }

    private fun hasLongBracketCloseAtOrAfter(start: Int, equalsCount: Int): Boolean {
        var cursor = start
        while (cursor < bufferLen) {
            if (longBracketCloseLength(cursor, equalsCount) > 0) {
                return true
            }
            cursor++
        }
        return false
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

    /**
     * Length of a complete long-bracket close delimiter at [start] (`]` `=`* `]`),
     * regardless of equals level. Returns -1 when [start] is not a full close.
     * Used for level-mismatch recovery so a wrong-level close ends the bad span
     * without consuming the remainder of the file (TASK-595).
     */
    private fun anyLongBracketCloseLength(start: Int): Int {
        if (start >= bufferLen || source[start] != ']') {
            return -1
        }

        var cursor = start + 1
        var equalsCount = 0
        while (cursor < bufferLen && source[cursor] == '=') {
            cursor++
            equalsCount++
        }

        return if (cursor < bufferLen && source[cursor] == ']') equalsCount + 2 else -1
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
        while (offset + tokenLength < bufferLen) {
            val ch = charAt()
            if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && offset + tokenLength + 1 < bufferLen && source[offset + tokenLength + 1] == '\n') {
                    tokenLength += 2
                } else {
                    tokenLength++
                }
                break
            }
            tokenLength++
        }
        return LuaTokenTypes.SHORT_COMMENT
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
            val leadingDigits = scanHexDigits()
            var trailingDigits = 0

            if (offset + tokenLength < bufferLen && charAt() == '.') {
                tokenLength++
                trailingDigits = scanHexDigits()
            }

            if (leadingDigits + trailingDigits == 0) {
                return LuaTokenTypes.BAD_CHARACTER
            }

            return if (scanHexExponentIfPresent()) LuaTokenTypes.NUMBER else LuaTokenTypes.BAD_CHARACTER
        }

        scanDecimalDigits()

        if (offset + tokenLength < bufferLen &&
            charAt() == '.' &&
            chatAtOrNull(offset + tokenLength + 1) != '.'
        ) {
            tokenLength++
            scanDecimalDigits()
        }

        return if (scanDecimalExponentIfPresent()) LuaTokenTypes.NUMBER else LuaTokenTypes.BAD_CHARACTER
    }

    private fun scanNumberStartingWithDot(): LuaTokenTypes {
        scanDecimalDigits()
        return if (scanDecimalExponentIfPresent()) LuaTokenTypes.NUMBER else LuaTokenTypes.BAD_CHARACTER
    }

    private fun scanDecimalDigits(): Int {
        var count = 0
        while (offset + tokenLength < bufferLen && isPrimeDigit(charAt())) {
            tokenLength++
            count++
        }
        return count
    }

    private fun scanHexDigits(): Int {
        var count = 0
        while (offset + tokenLength < bufferLen && isHexDigit(charAt())) {
            tokenLength++
            count++
        }
        return count
    }

    private fun scanDecimalExponentIfPresent(): Boolean {
        if (offset + tokenLength >= bufferLen) {
            return true
        }

        val ch = charAt()
        if (ch != 'e' && ch != 'E') {
            return true
        }

        tokenLength++
        scanExponentSign()
        return scanDecimalDigits() > 0
    }

    private fun scanHexExponentIfPresent(): Boolean {
        if (offset + tokenLength >= bufferLen) {
            return true
        }

        val ch = charAt()
        if (ch != 'p' && ch != 'P') {
            return true
        }

        tokenLength++
        scanExponentSign()
        return scanDecimalDigits() > 0
    }

    private fun scanExponentSign() {
        if (offset + tokenLength < bufferLen && (charAt() == '+' || charAt() == '-')) {
            tokenLength++
        }
    }

    fun pushBack(length: Int) {
        require(length <= tokenLength) { "pushBack length too large" }
        tokenLength -= length
    }

    private fun scanNewline() {
        if (offset + tokenLength < bufferLen && charAt(offset + tokenLength) == '\n') {
            tokenLength++
        }
    }


    private fun charAt(i: Int): Char {
        return source[i]
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

        val keywords = TrieTree<LuaTokenTypes>()

        init {
            run {
                keywords.put(
                    "and",
                    LuaTokenTypes.AND
                )
                keywords.put(
                    "or",
                    LuaTokenTypes.OR
                )
                keywords.put(
                    "default",
                    LuaTokenTypes.DEFAULT
                )
                keywords.put(
                    "switch",
                    LuaTokenTypes.SWITCH
                )
                keywords.put(
                    "if",
                    LuaTokenTypes.IF
                )
                keywords.put(
                    "break",
                    LuaTokenTypes.BREAK
                )
                keywords.put(
                    "else",
                    LuaTokenTypes.ELSE
                )
                keywords.put(
                    "while",
                    LuaTokenTypes.WHILE
                )
                keywords.put(
                    "do",
                    LuaTokenTypes.DO
                )
                keywords.put(
                    "return",
                    LuaTokenTypes.RETURN
                )
                keywords.put(
                    "for",
                    LuaTokenTypes.FOR
                )
                keywords.put(
                    "function",
                    LuaTokenTypes.FUNCTION
                )
                keywords.put(
                    "local",
                    LuaTokenTypes.LOCAL
                )
                keywords.put(
                    "true",
                    LuaTokenTypes.TRUE
                )
                keywords.put(
                    "false",
                    LuaTokenTypes.FALSE
                )
                keywords.put(
                    "nil",
                    LuaTokenTypes.NIL
                )
                keywords.put(
                    "continue",
                    LuaTokenTypes.CONTINUE
                )
                keywords.put(
                    "not",
                    LuaTokenTypes.NOT
                )
                keywords.put(
                    "in",
                    LuaTokenTypes.IN
                )
                keywords.put(
                    "then",
                    LuaTokenTypes.THEN
                )
                keywords.put(
                    "end",
                    LuaTokenTypes.END
                )
                keywords.put(
                    "repeat",
                    LuaTokenTypes.REPEAT
                )
                keywords.put(
                    "elseif",
                    LuaTokenTypes.ELSEIF
                )
                keywords.put(
                    "until",
                    LuaTokenTypes.UNTIL
                )
                keywords.put(
                    "goto",
                    LuaTokenTypes.GOTO
                )
                keywords.put(
                    "case",
                    LuaTokenTypes.CASE
                )
                keywords.put(
                    "when",
                    LuaTokenTypes.WHEN
                )
                keywords.put(
                    "lambda",
                    LuaTokenTypes.LAMBDA
                )
            }

        }

        private fun isDigit(c: Char): Boolean {
            return ((c in '0'..'9') || (c in 'A'..'F') || (c in 'a'..'f'))
        }

        private fun isHexDigit(c: Char): Boolean {
            return isDigit(c)
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

    override fun hasNext(): Boolean {
        return offset + tokenLength < bufferLen
    }

    override fun next(): Pair<LuaTokenTypes, String> {
        val currentToken = nextToken()

        return Pair(currentToken, tokenText.toString())
    }

}


enum class LuaTokenTypes {
    SHEBANG_CONTENT, NEW_LINE, WHITE_SPACE, BAD_CHARACTER,

    ADD_ASSIGN, SUB_ASSIGN, MUL_ASSIGN, DIV_ASSIGN,

    /* AND_ASSIGN,
     OR_ASSIGN,
     XOR_ASSIGN,
     MOD_ASSIGN,
     LSHIFT_ASSIGN,
     RSHIFT_ASSIGN,
     URSHIFT_ASSIGN,*/
    DOUBLE_DIV_ASSIGN,

    NAME, NUMBER, PLUS, DOT, MINUS, LBRACK, ASSIGN, RBRACK, GETN, NOT, GT, LT, BIT_TILDE, MULT, MOD, DIV, LPAREN, RPAREN, LCURLY, RCURLY, COMMA, SEMI, COLON, EXP, BIT_AND, BIT_OR, STRING, LONG_STRING, CONCAT, IN, IF, OR, DO, EQ, SHEBANG, NE, GE, BIT_RTRT, LE, BIT_LTLT, DOUBLE_DIV, DOUBLE_COLON, AND, SHORT_COMMENT, ELLIPSIS, END, NIL, LEF, MEAN, FOR, DOC_COMMENT, ELSE, GOTO, CASE, TRUE, THEN, BLOCK_COMMENT, BREAK, LOCAL, FALSE, UNTIL, WHILE, RETURN, REPEAT, ELSEIF, CONTINUE, SWITCH, DEFAULT, FUNCTION, LABEL, WHEN, LAMBDA, EOF
}
