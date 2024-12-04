package io.github.dingyi222666.luaparser.lexer

import io.github.dingyi222666.luaparser.util.TrieTree


class LuaLexer(
    private val source: CharSequence
) : Iterator<Pair<LuaTokenTypes, String>> {

    private val bufferLen = source.length;

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
            isWhitespace(ch) -> {
                var chLocal = '\t'
                while (offset + tokenLength < bufferLen && chatAtOrNull(offset + tokenLength)
                        ?.also {
                            chLocal = it
                        }?.let {
                            isWhitespace(it)
                        } == true
                ) {
                    if (chLocal == '\r' || chLocal == '\n') {
                        break
                    }
                    tokenLength++
                }
                LuaTokenTypes.WHITE_SPACE
            }

            isIdentifierStart(ch) -> scanIdentifier(ch)
            isPrimeDigit(ch) -> scanNumber(ch)
            ch == '\n' -> LuaTokenTypes.NEW_LINE
            ch == '\r' -> {
                scanNewline()
                LuaTokenTypes.NEW_LINE
            }

            ch == ';' -> LuaTokenTypes.SEMI
            ch == '(' -> LuaTokenTypes.LPAREN
            ch == ')' -> LuaTokenTypes.RPAREN
            ch == '[' -> {
                val next = chatAtOrNull() ?: return LuaTokenTypes.RBRACK

                if (next != '=' && next != '[') {
                    return LuaTokenTypes.LBRACK
                }


                return scanLongString()

            }

            ch == ']' -> LuaTokenTypes.RBRACK
            ch == '{' -> LuaTokenTypes.LCURLY
            ch == '}' -> LuaTokenTypes.RCURLY
            ch == ',' -> LuaTokenTypes.COMMA
            ch == '!' -> LuaTokenTypes.NOT
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
            ch == '~' -> LuaTokenTypes.BIT_TILDE
            ch == '&' -> LuaTokenTypes.BIT_AND
            ch == '|' -> LuaTokenTypes.BIT_OR
            ch == '>' -> scanTwoOperator(
                LuaTokenTypes.GT,
                LuaTokenTypes.GE, '='
            )

            ch == '<' -> scanTwoOperator(
                LuaTokenTypes.LT,
                LuaTokenTypes.LE, '='
            )

            ch == '.' -> {
                val next = chatAtOrNull() ?: return LuaTokenTypes.DOT

                when {
                    isPrimeDigit(next) -> {
                        scanPrimeDigit()
                        LuaTokenTypes.NUMBER
                    }

                    next == '.' -> {
                        tokenLength++
                        LuaTokenTypes.CONCAT
                    }

                    else -> LuaTokenTypes.DOT
                }


            }

            ch == '"' || ch == '\'' -> scanString(ch)
            ch == '#' -> LuaTokenTypes.GETN
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
        return n?.token ?: LuaTokenTypes.NAME
    }


    private fun scanString(start: Char): LuaTokenTypes {
        var finish = false

        while (offset + tokenLength < bufferLen) {
            val ch = charAt()
            when (ch) {
                start -> {
                    finish = true
                    break
                }
                // escape
                '\\' -> {
                    val next = charAt(offset + tokenLength + 1)

                    when (next) {
                        'a', 'b', 'f', 'n', 'r', 't', 'v', '\'', '"', '\\', '\n', '\r' -> {
                            tokenLength++
                        }

                        'z' -> {
                            tokenLength += 2
                        }

                        'x' -> {
                            tokenLength += 3
                        }
                    }
                }

                '\n', '\r' -> throw IllegalStateException("Unfinished string at <$tokenLine, ${tokenColumn}>")

            }

            tokenLength++
        }

        if (!finish) {
            throw IllegalStateException("Unfinished string at <$tokenLine, ${tokenColumn}>")
        }

        tokenLength++

        return LuaTokenTypes.STRING
    }


    private fun scanComment(): LuaTokenTypes {
        if (tokenLength + offset == bufferLen) {
            return LuaTokenTypes.SHORT_COMMENT
        }

        val next = charAt()

        when (next) {
            '[' -> {
                scanLongString()
                return LuaTokenTypes.BLOCK_COMMENT
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
                        println()
                        break
                    }
                }
                
                return LuaTokenTypes.DOC_COMMENT
            }
            else -> {
                // Regular comment
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
        }
    }

    private fun scanLongString(): LuaTokenTypes {
        tokenLength++
        val skipCount = scanLongStringSkipComment()

        while (offset + tokenLength < bufferLen && charAt() != ']') {
            tokenLength++
        }

        tokenLength++

        if (scanLongStringSkipComment() != skipCount) {
            throw IllegalStateException("Unfinished long string at <$tokenLine, ${tokenColumn}>")
        }

        // add \]
        tokenLength++

        return LuaTokenTypes.LONG_STRING
    }

    private fun scanLongStringSkipComment(): Int {
        var count = 0

        while (offset + tokenLength < bufferLen && charAt() == '=') {
            tokenLength++
            count++
        }

        return count
    }

    private fun scanDIV(): LuaTokenTypes {
        val next = charAt()

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

    @Suppress("SameReturnValue")
    private fun scanNumber(char: Char): LuaTokenTypes {
        if (tokenLength + offset == bufferLen) {
            // The number is the last token in the buffer
            return LuaTokenTypes.NUMBER
        }

        // check hex number

        var ch = char

        if (ch == '0' && charAt() == 'x') {
            tokenLength++

        }

        scanDigit()

        if (offset + tokenLength == bufferLen) {
            // if the number is the last token, return it
            return LuaTokenTypes.NUMBER
        }

        ch = charAt()

        if (ch != '.') {
            // not a decimal point
            return LuaTokenTypes.NUMBER
        }

        try {
            throwIfNeeded()
        } catch (e: IllegalStateException) {
            return LuaTokenTypes.BAD_CHARACTER
        }

        scanDigit()

        return LuaTokenTypes.NUMBER
    }

    private fun scanDigit() {
        while (offset + tokenLength < bufferLen && isDigit(
                charAt()
            )
        ) {
            tokenLength++
        }
    }

    private fun scanPrimeDigit() {
        while (offset + tokenLength < bufferLen && isPrimeDigit(
                charAt(offset + tokenLength)
            )
        ) {
            tokenLength++
        }
    }

    fun pushBack(length: Int) {
        require(length <= tokenLength) { "pushBack length too large" }
        tokenLength -= length
    }

    private fun throwIfNeeded() {
        require(offset + tokenLength < bufferLen) {
            "Token too long"
        }
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
            }

        }

        private fun isDigit(c: Char): Boolean {
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