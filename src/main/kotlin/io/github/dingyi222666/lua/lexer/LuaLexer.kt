package io.github.dingyi222666.lua.lexer

import io.github.dingyi222666.lua.util.TrieTree


class TestLuaLexer(
    private val source: CharSequence
) : Iterator<Pair<LuaTokenTypesTest, String>> {


    private val bufferLen = source.length;

    private var offset = 0

    var tokenType: LuaTokenTypesTest = LuaTokenTypesTest.WHITE_SPACE
        private set

    var tokenLine = 0
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

    fun nextToken(): LuaTokenTypesTest {
        return nextTokenInternal().also { tokenType = it }
    }

    private fun nextTokenInternal(): LuaTokenTypesTest {
        run {
            var r = false
            for (i in offset until offset + tokenLength) {
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
            return LuaTokenTypesTest.EOF
        }
        val ch = source[offset]
        tokenLength = 1

        //
        if (ch == '\n') {
            return LuaTokenTypesTest.NEW_LINE
        } else if (ch == '\r') {
            scanNewline()
            return LuaTokenTypesTest.NEW_LINE
        } else if (ch == ';') {
            // semicolon
            return LuaTokenTypesTest.SEMI
        } else if (ch == '(') {
            // left paren
            return LuaTokenTypesTest.LPAREN
        } else if (ch == ')') {
            // right paren
            return LuaTokenTypesTest.RPAREN
        } else if (ch == '[') {
            // left bracket
            return LuaTokenTypesTest.LBRACK
        } else if (ch == ']') {
            // right bracket
            return LuaTokenTypesTest.RBRACK
        } else if (ch == '{') {
            // left brace
            return LuaTokenTypesTest.LCURLY
        } else if (ch == '}') {
            // right brace
            return LuaTokenTypesTest.RCURLY
        } else if (ch == ',') {
            // comma
            return LuaTokenTypesTest.COMMA
        } else if (ch == '!') {
            // not
            // androlua+ grammar
            return LuaTokenTypesTest.NOT
        } else if (ch == '+') {
            return scanTwoOperator(LuaTokenTypesTest.PLUS, LuaTokenTypesTest.ADD_ASSIGN, '=')
        } else if (ch == '-') {
            return scanTwoOperator(LuaTokenTypesTest.MINUS, LuaTokenTypesTest.SUB_ASSIGN, '=')
        } else if (ch == '*') {
            return scanTwoOperator(LuaTokenTypesTest.MULT, LuaTokenTypesTest.MUL_ASSIGN, '=')
        } else if (ch == '/') {
            return scanDIV()
        } else if (ch == '=') {
            return scanTwoOperator(LuaTokenTypesTest.ASSIGN, LuaTokenTypesTest.EQ, '=')
        } else if (isWhitespace(ch)) {
            var chLocal = '\t'
            while (offset + tokenLength < bufferLen && isWhitespace(charAt(offset + tokenLength).also {
                    chLocal = it
                })) {
                if (chLocal == '\r' || chLocal == '\n') {
                    break
                }
                tokenLength++
            }
            return LuaTokenTypesTest.WHITE_SPACE

        } else if (isIdentifierStart(ch)) {
            // identifier or keyword
            return scanIdentifier(ch)
        } else if (isPrimeDigit(ch)) {
            // number
            return scanNumber(ch)
        }

        return LuaTokenTypesTest.BAD_CHARACTER
    }

    private fun scanIdentifier(char: Char): LuaTokenTypesTest {
        var ch = char
        var n: TrieTree.Node<LuaTokenTypesTest>? = keywords.root.map.get(ch)
        while (offset + tokenLength < bufferLen && isIdentifierPart(charAt(offset + tokenLength).also { ch = it })) {
            tokenLength++
            n = n?.map?.get(ch)
        }
        return n?.token ?: LuaTokenTypesTest.NAME
    }


    private fun scanDIV(): LuaTokenTypesTest {
        val next = charAt()

        return when (next) {
            '=' -> {
                tokenLength++
                LuaTokenTypesTest.DIV_ASSIGN
            }

            '/' -> {
                tokenLength++
                scanTwoOperator(LuaTokenTypesTest.DOUBLE_DIV, LuaTokenTypesTest.DOUBLE_DIV_ASSIGN, '=')
            }

            else ->
                LuaTokenTypesTest.DIV

        }
    }

    private fun scanTwoOperator(
        first: LuaTokenTypesTest,
        second: LuaTokenTypesTest,
        operator: Char
    ): LuaTokenTypesTest {
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
    private fun scanNumber(char: Char): LuaTokenTypesTest {
        if (tokenLength + offset == bufferLen) {
            // The number is the last token in the buffer
            return LuaTokenTypesTest.NUMBER
        }

        // check hex number

        var ch = char

        if (ch == '0') {
            if (charAt() == 'x') {
                tokenLength++
            }
        }

        scanDigit()

        if (offset + tokenLength == bufferLen) {
            // if the number is the last token, return it
            return LuaTokenTypesTest.NUMBER
        }

        ch = charAt()

        if (ch != '.') {
            // not a decimal point
            return LuaTokenTypesTest.NUMBER
        }

        throwIfNeeded()

        scanDigit()

        return LuaTokenTypesTest.NUMBER
    }

    private fun scanDigit() {
        while (offset + tokenLength < bufferLen && isDigit(charAt())) {
            tokenLength++;
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

    companion object {
        val keywords = TrieTree<LuaTokenTypesTest>()

        init {
            keywords.put("and", LuaTokenTypesTest.AND)
            keywords.put("or", LuaTokenTypesTest.OR)
            keywords.put("default", LuaTokenTypesTest.DEFAULT)
            keywords.put("switch", LuaTokenTypesTest.SWITCH)
            keywords.put("if", LuaTokenTypesTest.IF)
            keywords.put("break", LuaTokenTypesTest.BREAK)
            keywords.put("else", LuaTokenTypesTest.ELSE)
            keywords.put("while", LuaTokenTypesTest.WHILE)
            keywords.put("do", LuaTokenTypesTest.DO)
            keywords.put("return", LuaTokenTypesTest.RETURN)
            keywords.put("for", LuaTokenTypesTest.FOR)
            keywords.put("function", LuaTokenTypesTest.FUNCTION)
            keywords.put("local", LuaTokenTypesTest.LOCAL)
            keywords.put("true", LuaTokenTypesTest.TRUE)
            keywords.put("false", LuaTokenTypesTest.FALSE)
            keywords.put("nil", LuaTokenTypesTest.NIL)
            keywords.put("continue", LuaTokenTypesTest.CONTINUE)
            keywords.put("not", LuaTokenTypesTest.NOT)
            keywords.put("in", LuaTokenTypesTest.IN)
            keywords.put("then", LuaTokenTypesTest.THEN)
            keywords.put("end", LuaTokenTypesTest.END)
            keywords.put("repeat", LuaTokenTypesTest.REPEAT)
            keywords.put("elseif", LuaTokenTypesTest.ELSEIF)
            keywords.put("until", LuaTokenTypesTest.UNTIL)
            keywords.put("goto", LuaTokenTypesTest.GOTO)
            keywords.put("case", LuaTokenTypesTest.CASE)
            keywords.put("when", LuaTokenTypesTest.WHEN)
        }

        private fun isDigit(c: Char): Boolean {
            return ((c in '0'..'9') || (c in 'A'..'F') || (c in 'a'..'f'))
        }

        private fun isPrimeDigit(c: Char): Boolean {
            return (c in '0'..'9')
        }

        private fun isWhitespace(c: Char): Boolean {
            return (c == '\t' || c == ' ' || c == '\u000c' || c == '\n' || c == '\r')
        }


        private fun isIdentifierStart(c: Char): Boolean {

            return (c in 'a'..'z') || (c in 'A'..'Z') || (c == '_') ||
                    (c == '$') || (c >= '\u0080')
        }

        private fun isIdentifierPart(c: Char): Boolean {
            return isIdentifierStart(c) || (c in '0'..'9')
        }

    }

    override fun hasNext(): Boolean {
        return offset + tokenLength < bufferLen
    }

    override fun next(): Pair<LuaTokenTypesTest, String> {
        val currentToken = nextToken()

        return Pair(currentToken, tokenText.toString())
    }

}


enum class LuaTokenTypesTest {
    SHEBANG_CONTENT,
    NEW_LINE,
    WHITE_SPACE,
    BAD_CHARACTER,

    ADD_ASSIGN,
    SUB_ASSIGN,
    MUL_ASSIGN,
    DIV_ASSIGN,
    AND_ASSIGN,
    OR_ASSIGN,
    XOR_ASSIGN,
    MOD_ASSIGN,
    LSHIFT_ASSIGN,
    RSHIFT_ASSIGN,
    URSHIFT_ASSIGN,
    DOUBLE_DIV_ASSIGN,

    NAME,
    NUMBER,
    PLUS,
    DOT,
    MINUS,
    LBRACK,
    ASSIGN,
    RBRACK,
    GETN,
    NOT,
    GT,
    LT,
    BIT_TILDE,
    MULT,
    MOD,
    DIV,
    LPAREN,
    RPAREN,
    LCURLY,
    RCURLY,
    COMMA,
    SEMI,
    COLON,
    EXP,
    BIT_AND,
    BIT_OR,
    STRING,
    LONG_STRING,
    CONCAT,
    IN,
    IF,
    OR,
    DO,
    EQ,
    SHEBANG,
    NE,
    GE,
    BIT_RTRT,
    LE,
    BIT_LTLT,
    DOUBLE_DIV,
    DOUBLE_COLON,
    AND,
    SHORT_COMMENT,
    ELLIPSIS,
    END,
    NIL,
    LEF,
    MEAN,
    FOR,
    DOC_COMMENT,
    ELSE,
    GOTO,
    CASE,
    TRUE,
    THEN,
    BLOCK_COMMENT,
    BREAK,
    LOCAL,
    FALSE,
    UNTIL,
    WHILE,
    RETURN,
    REPEAT,
    ELSEIF,
    CONTINUE,
    SWITCH,
    DEFAULT,
    FUNCTION,
    LABEL,
    WHEN,
    LAMBDA,
    EOF
}