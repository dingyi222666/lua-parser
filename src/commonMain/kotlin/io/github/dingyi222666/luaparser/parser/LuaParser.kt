package io.github.dingyi222666.luaparser.parser


import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import io.github.dingyi222666.luaparser.lexer.WrapperLuaLexer
import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.util.equalsMore
import io.github.dingyi222666.luaparser.util.requireNotNull
import kotlin.math.max
import kotlin.properties.Delegates

data class LuaParseResult(
    val chunk: ChunkNode,
    val recoveryDiagnostics: List<LuaParserRecoveryDiagnostic>,
)

data class LuaParserRecoveryDiagnostic(
    val message: String,
    val range: Range,
)

/**
 * Compact short-call syntax keeps the first string/table argument in the specialized
 * call node and any comma continuation on the outer call:
 *
 * `target "first", second` -> `CallExpression(StringCallExpression(target, "first"), second)`.
 *
 * Use these helpers when consumers need the semantic callee or full argument list.
 */
fun CallExpression.isCompactShortCall(): Boolean {
    return base is StringCallExpression || base is TableCallExpression
}

fun CallExpression.compactCallBase(): ExpressionNode {
    return when (val compactBase = base) {
        is StringCallExpression -> compactBase.base
        is TableCallExpression -> compactBase.base
        else -> compactBase
    }
}

fun CallExpression.compactCallArguments(): List<ExpressionNode> {
    return when (val compactBase = base) {
        is StringCallExpression -> compactBase.arguments + arguments
        is TableCallExpression -> compactBase.arguments + arguments
        else -> arguments
    }
}

/**
 * @author: dingyi
 * @date: 2023/2/2
 * @description:
 **/
class LuaParser(
    private val luaVersion: LuaVersion = LuaVersion.ANDROLUA_5_3,
    private val errorRecovery: Boolean = true,
) {
    private var lexer by Delegates.notNull<WrapperLuaLexer>()

    private var currentToken = LuaTokenTypes.WHITE_SPACE
    private var lastToken = LuaTokenTypes.WHITE_SPACE
    private var tokenText: CharSequence? = null
    private val locations = ArrayDeque<Position>()
    /**
     * End of the last *consumed* significant token. finishNode / finishNodeSpanning use this
     * so ranges close on the construct itself rather than on a token that was only peeked
     * (and pushbacked) after the construct. Without this, peeks for binary ops / next
     * statements stretch prior node ranges (e.g. multi-line `function ... end` or a
     * single-line local table absorbing the following statement's start token).
     */
    private var lastCommittedEndPosition: Position = Position(line = 1, column = 1)
    private var recoverIncompleteWorkspaceSnippet = false
    private val mutableRecoveryDiagnostics = mutableListOf<LuaParserRecoveryDiagnostic>()

    var ignoreWarningMessage = true
    val recoveryDiagnostics: List<LuaParserRecoveryDiagnostic>
        get() = mutableRecoveryDiagnostics.toList()

    companion object {
        /**
         * TASK-553 recoverFirstStatementLineBreak call-site policy for explists.
         *
         * Assignment may recover the first RHS as a sibling when the next significant
         * token after `=` is a statement start after a line break (e.g. incomplete
         * `a =\nprint(a)`). Local and return keep first-RHS recovery off so multi-line
         * multi-RHS initializers/values match strict shapes. Later comma terms always
         * share keyword-only recovery (see parseExpList) and are not poisoned by
         * first-RHS assignment recovery (TASK-546 mechanics).
         */
        const val RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_ASSIGNMENT: Boolean = true
        const val RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_LOCAL: Boolean = false
        const val RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_RETURN: Boolean = false
        /** Default for non-assignment/local/return explists (for-in iterators, case, arrays). */
        const val RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_DEFAULT: Boolean = true
    }

    fun parse(source: String): ChunkNode {
        return parse(LuaLexer(source, supportAndroLuaKeywords = isAndroLua()))
    }

    fun parseWithDiagnostics(source: String): LuaParseResult {
        return parseWithDiagnostics(LuaLexer(source, supportAndroLuaKeywords = isAndroLua()))
    }

    fun parseWithDiagnostics(lexer: LuaLexer): LuaParseResult {
        val chunk = parse(lexer)
        return LuaParseResult(
            chunk = chunk,
            recoveryDiagnostics = recoveryDiagnostics
        )
    }

    fun parseWorkspaceSnippet(source: String): ChunkNode {
        return LuaParser(luaVersion, errorRecovery = true).also {
            it.ignoreWarningMessage = true
            it.recoverIncompleteWorkspaceSnippet = true
        }.parse(source)
    }

    fun parse(lexer: LuaLexer): ChunkNode {
        reset()
        this.lexer = WrapperLuaLexer(lexer, supportAndroLuaKeywords = isAndroLua())
        val chunk = parseChunk()
        // parseChunk always consumes EOF (strict or recovery drain); close after.
        this.lexer.close()
        return chunk
    }

    fun reset() {
        currentToken = LuaTokenTypes.WHITE_SPACE
        lastToken = LuaTokenTypes.WHITE_SPACE
        tokenText = null
        locations.clear()
        lastCommittedEndPosition = Position(line = 1, column = 1)
        mutableRecoveryDiagnostics.clear()
    }

    private inline fun <T> consume(crossinline func: (LuaTokenTypes) -> T): T {
        val savedEnd = lastCommittedEndPosition
        advance()
        val needConsume = func.invoke(currentToken)
        if (needConsume is Boolean && !needConsume) {
            lexer.pushback(lexer.length())
            // Rejected token is not part of the construct; restore prior committed end.
            lastCommittedEndPosition = savedEnd
        }
        return needConsume
    }

    private fun peek(): LuaTokenTypes {
        return peek { it }
    }

    private inline fun <T> peek(crossinline func: (LuaTokenTypes) -> T): T {
        val savedEnd = lastCommittedEndPosition
        advance()
        val result = func.invoke(currentToken)
        lexer.pushback(lexer.length())
        tokenText = null
        // Pushback requeues the token; restore the last truly-consumed end so finishNode
        // does not close on a merely-peeked following statement/expression token.
        lastCommittedEndPosition = savedEnd
        return result
    }

    private inline fun peekToken(tokenTypes: LuaTokenTypes): Boolean {
        return peek { it == tokenTypes }
    }

    private fun ignoreToken(advanceToken: LuaTokenTypes): Boolean {
        return when (advanceToken) {
            LuaTokenTypes.WHITE_SPACE, LuaTokenTypes.NEW_LINE
                -> true

            else -> false
        }
    }

    /**
     * Line/block/doc comments are statement-level AST nodes when they appear where a
     * statement is legal. Inside expressions/tables (and after `return`) they are trivia
     * and must not surface as `<expression>` / `<end>` failures for Android-Lua layout
     * tables and trailing end-of-line notes (TASK-610).
     */
    private fun isCommentToken(tokenTypes: LuaTokenTypes): Boolean {
        return when (tokenTypes) {
            LuaTokenTypes.SHORT_COMMENT,
            LuaTokenTypes.BLOCK_COMMENT,
            LuaTokenTypes.DOC_COMMENT -> true

            else -> false
        }
    }

    private fun skipCommentTokens() {
        while (isCommentToken(peek())) {
            advance()
        }
    }

    private fun advance(): LuaTokenTypes {
        var advanceToken: LuaTokenTypes
        while (true) {
            advanceToken = lexer.advance()
            tokenText = null
            if (ignoreToken(advanceToken)) {
                continue
            } else break
        }
        lastToken = currentToken
        currentToken = advanceToken
        // Commit end only for a real consume. peek()/consume-false pushback restore the
        // prior token's end via lastCommittedEndPosition before leaving those helpers.
        lastCommittedEndPosition = Position(
            line = lexer.line(),
            column = max(lexer.column() + lexer.length(), 1)
        )
        return currentToken
    }

    private fun lexerText(nextToken: Boolean = false): CharSequence {
        if (nextToken) {
            advance()
        }
        return tokenText ?: lexer.text().apply {
            tokenText = this
        }
    }

    private fun peekN(size: Int): LuaTokenTypes {
        var result = currentToken
        var currentSize = 0
        var backSize = 0
        while (currentSize < size) {
            result = lexer.advance().requireNotNull()
            backSize++
            if (ignoreToken(result)) {
                continue
            }
            currentSize++
        }

        lexer.back(backSize)

        return result
    }

    private inline fun consumeToken(target: LuaTokenTypes): Boolean {
        return consume { token ->
            target == token
        }
    }

    private inline fun expectToken(target: LuaTokenTypes, crossinline messageBuilder: () -> String): Boolean {
        advance()
        if (currentToken != target) {
            error(messageBuilder())
        }
        return true
    }

    private inline fun recoverToken(target: LuaTokenTypes, crossinline messageBuilder: () -> String): Boolean {
        if (consumeToken(target)) {
            return true
        }
        val message = messageBuilder()
        if (!errorRecovery) {
            error(message)
        }
        warning(message)
        return false
    }

    private fun hasLineBreakBeforeNextSignificantToken(): Boolean {
        return lexer.hasLineBreakBeforeNextSignificantToken()
    }

    private fun error(message: String): Nothing = kotlin.error("(${lexer.line()},${lexer.column()}): " + message)

    private fun warning(message: String) {
        warning(message, currentRecoveryRange())
    }

    private fun warning(message: String, range: Range) {
        if (errorRecovery && ignoreWarningMessage) {
            mutableRecoveryDiagnostics += LuaParserRecoveryDiagnostic(
                message = message,
                range = range
            )
        } else error(message)
    }

    private fun currentRecoveryRange(): Range {
        val start = Position(
            line = lexer.line(),
            column = max(lexer.column(), 1)
        )
        val tokenLength = max(lexer.length(), 1)
        return Range(
            start = start,
            end = Position(
                line = start.line,
                column = max(start.column + tokenLength, start.column + 1)
            )
        )
    }

    private fun recoveryRangeAfterCurrentToken(): Range {
        val start = Position(
            line = lexer.line(),
            column = max(lexer.column() + lexer.length(), 1)
        )
        return Range(
            start = start,
            end = Position(
                line = start.line,
                column = start.column + 1
            )
        )
    }

    private inline fun assertVersion(version: LuaVersion, crossinline messageBuilder: () -> String) {
        if (luaVersion != version) {
            error(messageBuilder())
        }
    }

    private fun isAndroLua(): Boolean {
        return luaVersion == LuaVersion.ANDROLUA_5_3
    }

    private fun isBlockTerminator(tokenTypes: LuaTokenTypes): Boolean {
        return when (tokenTypes) {
            LuaTokenTypes.EOF,
            LuaTokenTypes.END,
            LuaTokenTypes.UNTIL,
            LuaTokenTypes.ELSE,
            LuaTokenTypes.ELSEIF -> true

            LuaTokenTypes.CASE,
            LuaTokenTypes.DEFAULT -> isAndroLua()

            else -> false
        }
    }

    private fun isExpressionTerminator(tokenTypes: LuaTokenTypes): Boolean {
        return when (tokenTypes) {
            LuaTokenTypes.EOF,
            LuaTokenTypes.END,
            LuaTokenTypes.UNTIL,
            LuaTokenTypes.ELSE,
            LuaTokenTypes.ELSEIF,
            LuaTokenTypes.THEN,
            LuaTokenTypes.DO,
            LuaTokenTypes.CASE,
            LuaTokenTypes.DEFAULT,
            LuaTokenTypes.RPAREN,
            LuaTokenTypes.RBRACK,
            LuaTokenTypes.RCURLY,
            LuaTokenTypes.COMMA,
            LuaTokenTypes.ASSIGN,
            LuaTokenTypes.SEMI -> true

            else -> false
        }
    }

    private fun isStatementStart(tokenTypes: LuaTokenTypes): Boolean {
        return when (tokenTypes) {
            LuaTokenTypes.LOCAL,
            LuaTokenTypes.WHILE,
            LuaTokenTypes.REPEAT,
            LuaTokenTypes.BREAK,
            LuaTokenTypes.FOR,
            LuaTokenTypes.FUNCTION,
            LuaTokenTypes.CONTINUE,
            LuaTokenTypes.WHEN,
            LuaTokenTypes.IF,
            LuaTokenTypes.SWITCH,
            LuaTokenTypes.GOTO,
            LuaTokenTypes.DOUBLE_COLON,
            LuaTokenTypes.DO,
            LuaTokenTypes.NAME,
            // Parenthesized call statements: (f)(), (function() end)(), and
            // compact Android-Lua bodies like function(a)(root[v] or _G[v])(a) end
            LuaTokenTypes.LPAREN,
            LuaTokenTypes.RETURN -> true

            else -> false
        }
    }

    private fun isExpressionStart(tokenTypes: LuaTokenTypes): Boolean {
        return when (tokenTypes) {
            LuaTokenTypes.NAME,
            LuaTokenTypes.LPAREN,
            LuaTokenTypes.NIL,
            LuaTokenTypes.FALSE,
            LuaTokenTypes.TRUE,
            LuaTokenTypes.NUMBER,
            LuaTokenTypes.STRING,
            LuaTokenTypes.LONG_STRING,
            LuaTokenTypes.ELLIPSIS,
            LuaTokenTypes.FUNCTION,
            LuaTokenTypes.LCURLY,
            LuaTokenTypes.MINUS,
            LuaTokenTypes.BAD_CHARACTER,
            LuaTokenTypes.GETN,
            LuaTokenTypes.BIT_TILDE,
            LuaTokenTypes.NOT -> true

            LuaTokenTypes.LAMBDA,
            LuaTokenTypes.LBRACK -> isAndroLua()

            else -> false
        }
    }

    private fun emptyExpression(parent: BaseASTNode, bad: Boolean = false): ExpressionNode {
        return ExpressionNode.Companion.ExpressionNodeSupport().also {
            it.parent = parent
            it.bad = bad
        }
    }

    private fun missingExpression(parent: BaseASTNode): ExpressionNode {
        return emptyExpression(parent, bad = true)
    }

    private fun malformedTokenHead(text: CharSequence): CharSequence {
        val lineBreakIndex = text.indexOfFirst { it == '\r' || it == '\n' }
        if (lineBreakIndex <= 0) {
            return text
        }
        lexer.pushback(text.length - lineBreakIndex)
        tokenText = null
        return text.subSequence(0, lineBreakIndex)
    }

    private fun malformedLiteral(parent: BaseASTNode): ExpressionNode {
        val text = lexerText(true)
        warning("malformed token near $text")
        return ConstantNode(ConstantNode.TYPE.UNKNOWN, malformedTokenHead(text)).also {
            it.parent = parent
            it.bad = true
        }
    }

    private fun parseExpressionOrMissing(
        parent: BaseASTNode,
        recoverStatementStartAfterLineBreak: Boolean = true
    ): ExpressionNode {
        // Capture linebreak BEFORE any peek/pushback so WrapperLuaLexer.currentState
        // is not already sitting on the next NAME (which would look past it).
        val hasLineBreakBeforeExpression =
            recoverStatementStartAfterLineBreak && hasLineBreakBeforeNextSignificantToken()
        val token = peek()
        return if (errorRecovery &&
            hasLineBreakBeforeExpression &&
            shouldRecoverStatementStartAsMissingExpression(token)
        ) {
            missingExpression(parent)
        } else if (isExpressionStart(token)) {
            parseExp(parent)
        } else if (errorRecovery) {
            missingExpression(parent)
        } else {
            error("<expression> expected near ${lexerText(true)}")
        }
    }

    /**
     * After a line break, recover a following statement as a missing expression
     * (leave the token unconsumed for the outer block).
     *
     * Keyword/control statement starts always recover. A bare NAME recovers only
     * when it begins a call-shaped statement: name(, name{, name string, or a
     * member/method chain ending in a call (activity.setContentView(, view:setText()
     * so well-formed multi-line multi-RHS names and table array fields
     * ({ NL LinearLayout, ...}) parse as expressions while incomplete
     * a = NL print(a) / trailing-comma call NL activity.setContentView(view) keep
     * the following call as a sibling statement.
     */
    private fun shouldRecoverStatementStartAsMissingExpression(token: LuaTokenTypes): Boolean {
        if (isKeywordStatementStart(token)) {
            return true
        }
        if (token != LuaTokenTypes.NAME) {
            return false
        }
        // token is the just-peeked NAME (pushbacked). Walk member chains then call.
        return isCallShapedNameStatementStart()
    }

    /**
     * Lexer is positioned before a NAME. True when that NAME starts a call-shaped
     * statement: direct call (name(, name{, name string) or a . / : member chain
     * that ends in a call starter (activity.setContentView(, view:setText().
     * Restores the lexer with [WrapperLuaLexer.back].
     *
     * Used by call-arg trailing-comma recovery (TASK-551 / TASK-647) so incomplete
     * Android-Lua forms do not absorb later setContentView as an extra argument.
     */
    private fun isCallShapedNameStatementStart(): Boolean {
        var backSize = 0

        fun nextSignificant(): LuaTokenTypes {
            while (true) {
                val token = lexer.advance()
                backSize++
                if (token == LuaTokenTypes.EOF) {
                    return LuaTokenTypes.EOF
                }
                if (!ignoreToken(token)) {
                    return token
                }
            }
        }

        // Leading NAME (statement head).
        if (nextSignificant() != LuaTokenTypes.NAME) {
            lexer.back(backSize)
            return false
        }

        // Zero or more (.|:) NAME segments, then a call starter.
        while (true) {
            when (val next = nextSignificant()) {
                LuaTokenTypes.LPAREN,
                LuaTokenTypes.LCURLY,
                LuaTokenTypes.STRING,
                LuaTokenTypes.LONG_STRING -> {
                    lexer.back(backSize)
                    return true
                }

                LuaTokenTypes.DOT,
                LuaTokenTypes.COLON -> {
                    if (nextSignificant() != LuaTokenTypes.NAME) {
                        lexer.back(backSize)
                        return false
                    }
                    // Continue: more chain segments or the eventual call starter.
                }

                else -> {
                    lexer.back(backSize)
                    return false
                }
            }
        }
    }

    /** Statement-start tokens that cannot begin an expression after explist comma. */
    private fun isKeywordStatementStart(tokenTypes: LuaTokenTypes): Boolean {
        return when (tokenTypes) {
            LuaTokenTypes.LOCAL,
            LuaTokenTypes.WHILE,
            LuaTokenTypes.REPEAT,
            LuaTokenTypes.BREAK,
            LuaTokenTypes.FOR,
            LuaTokenTypes.FUNCTION,
            LuaTokenTypes.CONTINUE,
            LuaTokenTypes.WHEN,
            LuaTokenTypes.IF,
            LuaTokenTypes.SWITCH,
            LuaTokenTypes.GOTO,
            LuaTokenTypes.DOUBLE_COLON,
            LuaTokenTypes.DO,
            LuaTokenTypes.RETURN -> true

            else -> false
        }
    }

    private fun parseNameOrMissing(parent: BaseASTNode, supportDollarSymbol: Boolean = false): Identifier {
        return if (peekToken(LuaTokenTypes.NAME)) {
            parseName(parent, supportDollarSymbol)
        } else if (errorRecovery) {
            warning("<name> expected near ${lexerText()}")
            Identifier("").also {
                it.parent = parent
                it.bad = true
            }
        } else {
            error("<name> expected near ${lexerText(true)}")
        }
    }

    private fun parseAssignmentTargetOrMissing(parent: BaseASTNode): ExpressionNode {
        val hasLineBreakBeforeTarget = hasLineBreakBeforeNextSignificantToken()
        val token = peek()
        return if (errorRecovery && hasLineBreakBeforeTarget && isStatementStart(token)) {
            missingExpression(parent)
        } else if (isExpressionStart(token)) {
            parsePrefixExp(parent)
        } else if (errorRecovery) {
            missingExpression(parent)
        } else {
            error("<name> expected near ${lexerText(true)}")
        }
    }

    private fun parseStatementNameOrMissing(parent: BaseASTNode): Identifier {
        return if (peekToken(LuaTokenTypes.NAME)) {
            parseName(parent)
        } else if (errorRecovery) {
            warning("<name> expected near ${lexerText()}")
            Identifier("").also {
                it.parent = parent
                it.bad = true
            }
        } else {
            error("<name> expected near ${lexerText(true)}")
        }
    }

    private data class ParsedTableField(
        val field: TableKey,
        val implicitArrayField: Boolean,
    )

    private fun canStartTableArrayField(tokenTypes: LuaTokenTypes): Boolean {
        return binaryPrecedence(tokenTypes) > 0 ||
                equalsMore(
                    tokenTypes,
                    LuaTokenTypes.LPAREN,
                    LuaTokenTypes.LCURLY,
                    LuaTokenTypes.STRING,
                    LuaTokenTypes.LONG_STRING,
                    LuaTokenTypes.DOT,
                    LuaTokenTypes.COLON,
                    LuaTokenTypes.LBRACK
                )
    }

    private fun binaryLeftOperand(node: ExpressionNode): ExpressionNode {
        return if (node is CallExpression &&
            node.arguments.isEmpty() &&
            node.isCompactShortCall()
        ) {
            node.base
        } else {
            node
        }
    }

    private fun markLocation() {
        locations.addFirst(
            Position(
                line = lexer.line(),
                column = max(lexer.column(), 1)
            )
        )
    }

    private fun currentEndPosition(): Position {
        // Prefer the last committed (consumed) token end. WrapperLuaLexer.pushback leaves
        // currentState on the requeued peeked token, so reading lexer.line()/column() here
        // would incorrectly stretch ranges onto the following construct.
        return lastCommittedEndPosition
    }

    private fun <T : BaseASTNode> finishNode(node: T): T {
        val start = locations.removeFirst()
        node.range = Range(start, currentEndPosition())
        return node
    }

    /**
     * Finish a composite node whose source span begins at a child (left/base)
     * rather than at the operator / suffix token that triggered the node.
     * Does not touch the locations stack.
     */
    private fun <T : BaseASTNode> finishNodeSpanning(node: T, start: Position): T {
        node.range = Range(start, currentEndPosition())
        return node
    }



    // chunk ::= block
    private fun parseChunk(): ChunkNode {
        markLocation()
        var chunkNode = ChunkNode()
        chunkNode.body = parseBlockNode(chunkNode)
        chunkNode = finishNode(chunkNode)
        // Strict: hard-error on residual tokens. Recovery: record diagnostics and drain
        // so incomplete blocks (e.g. statements after retstat that were not attached)
        // never throw from parseWithDiagnostics / parseRecovering paths.
        if (!consumeToken(LuaTokenTypes.EOF)) {
            if (!errorRecovery) {
                // Keep historical message shape (closing quote omitted after <eof>).
                error("unexpected ${lexerText()} near '<eof>")
            }
            while (peek() != LuaTokenTypes.EOF) {
                warning("unexpected ${lexerText()} near '<eof>")
                advance()
            }
            consumeToken(LuaTokenTypes.EOF)
        }
        return chunkNode
    }


    //block ::= {stat} [retstat]
    //stat ::=  ‘;’ |
    //		 varlist ‘=’ explist |
    //		 functioncall |
    //		 label |
    //		 break |
    //       continue | (androlua+)
    //		 goto Name |
    //		 do block end |
    //		 while exp do block end |
    //		 repeat block until exp |
    //		 if exp then block {elseif exp then block} [else block] end |
    //		 for Name ‘=’ exp ‘,’ exp [‘,’ exp] do block end |
    //		 for namelist in explist do block end |
    //		 function funcname funcbody |
    //		 local function Name funcbody |
    //		 local attnamelist [‘=’ explist] |
    //       when exp (varlist ‘=’ explist| functioncall) | [else (varlist ‘=’ explist | functioncall)] |
    //       switch exp do {case explist [then] block} [default block] end
    private fun parseBlockNode(parent: BaseASTNode? = null): BlockNode {
        markLocation()
        val blockNode = BlockNode()

        while (true) {
            val nextToken = peek()
            if (isBlockTerminator(nextToken)) {
                if (shouldRecoverTopLevelBlockTerminator(parent, nextToken)) {
                    advance()
                    warning("unexpected ${lexerText()} near '<statement>'")
                    continue
                }
                break
            }

            val stat = when {
                consumeToken(LuaTokenTypes.LOCAL) -> {
                    // Mark at `local` so both `local x = ...` and `local function f()`
                    // span from the keyword (AstShape / LSP ranges).
                    markLocation()
                    if (consumeToken(LuaTokenTypes.FUNCTION)) parseLocalFunctionDeclaration(blockNode)
                    else parseLocalVarList(blockNode)
                }

                // After consumeToken the keyword is still current, so markLocation()
                // records the keyword start before any further advances.
                consumeToken(LuaTokenTypes.WHILE) -> {
                    markLocation()
                    parseWhileStatement(blockNode)
                }

                consumeToken(LuaTokenTypes.REPEAT) -> {
                    markLocation()
                    parseRepeatStatement(blockNode)
                }

                consumeToken(LuaTokenTypes.BREAK) -> {
                    markLocation()
                    BreakStatement()
                }

                consumeToken(LuaTokenTypes.FOR) -> {
                    markLocation()
                    parseForStatement(blockNode)
                }

                consumeToken(LuaTokenTypes.FUNCTION) -> {
                    markLocation()
                    parseGlobalFunctionDeclaration(blockNode)
                }

                consumeToken(LuaTokenTypes.CONTINUE) -> {
                    assertVersion(LuaVersion.ANDROLUA_5_3) {
                        "continue statement is only supported in androlua 5.3"
                    }
                    markLocation()
                    ContinueStatement()
                }

                consume {
                    it == LuaTokenTypes.SHORT_COMMENT
                            || it == LuaTokenTypes.BLOCK_COMMENT || it == LuaTokenTypes.SHEBANG_CONTENT
                } -> {
                    markLocation()
                    CommentStatement().apply {
                        comment = lexerText().toString()
                        isDocComment = false
                    }
                }

                consumeToken(LuaTokenTypes.DOC_COMMENT) -> {
                    markLocation()
                    CommentStatement().apply {
                        comment = lexerText().toString()
                        isDocComment = true
                    }
                }

                peekToken(LuaTokenTypes.WHEN) -> {
                    assertVersion(LuaVersion.ANDROLUA_5_3) {
                        "when statement is only supported in androlua 5.3"
                    }
                    parseWhenStatement(blockNode)
                }

                peekToken(LuaTokenTypes.IF) -> parseIfStatement(blockNode)
                peekToken(LuaTokenTypes.SWITCH) -> {
                    assertVersion(LuaVersion.ANDROLUA_5_3) {
                        "switch statement is only supported in androlua 5.3"
                    }
                    parseSwitchStatement(blockNode)
                }

                consumeToken(LuaTokenTypes.GOTO) -> {
                    markLocation()
                    parseGotoStatement(blockNode)
                }

                consumeToken(LuaTokenTypes.DOUBLE_COLON) -> {
                    markLocation()
                    parseLabelStatement(blockNode)
                }

                consumeToken(LuaTokenTypes.SEMI) -> continue

                consumeToken(LuaTokenTypes.DO) -> {
                    markLocation()
                    parseDoStatement(blockNode)
                }

                consumeToken(LuaTokenTypes.BAD_CHARACTER) -> {
                    val text = lexerText()
                    warning("malformed token near $text")
                    malformedTokenHead(text)
                    continue
                }
                // function call, varlist = explist, $(localvarlist)
                peekToken(LuaTokenTypes.NAME) -> {
                    val name = peek { lexerText() }
                    if (name.startsWith('$')) {
                        assertVersion(LuaVersion.ANDROLUA_5_3) {
                            "local variables with prefix $ are not supported in this version"
                        }
                        // Mark at `$name` token (peek leaves it current via pushback + re-peek).
                        peek { markLocation() }
                        parseLocalVarList(blockNode)
                    } else {
                        parseExpStatement(blockNode)
                    }
                }

                // Parenthesized prefix expressions used as call statements
                // (standard Lua + AndroLua loadlayout-style `function(a)(...)(a)end`).
                peekToken(LuaTokenTypes.LPAREN) -> parseExpStatement(blockNode)

                consumeToken(LuaTokenTypes.RETURN) -> {
                    markLocation()
                    parseReturnStatement(blockNode)
                }

                // AndroLua `.aly` layout files are bare table constructors loaded as
                // `return <table>`. Accept a top-level `{ ... }` as that return form so
                // external layout rows strict-parse without rewriting sources (TASK-610).
                peekToken(LuaTokenTypes.LCURLY) && parent is ChunkNode -> {
                    markLocation()
                    parseTopLevelTableReturn(blockNode)
                }

                else -> {
                    if (!errorRecovery) {
                        break
                    }
                    advance()
                    warning("unexpected ${lexerText()} near '<statement>'")
                    continue
                }
            }

            if (stat is CommentStatement) {
                // next token
                peek {
                    val node = finishNode(stat)
                    val visibleComment = stat.comment.trimEnd('\r', '\n')
                    val commentLines = visibleComment.split("\r\n", "\n", "\r")
                    val endLine = node.range.start.line + commentLines.lastIndex
                    val endColumn = if (commentLines.size == 1) {
                        node.range.start.column + commentLines.last().length
                    } else {
                        commentLines.last().length + 1
                    }

                    node.range = Range(node.range.start, Position(endLine, endColumn))
                }
            } else {
                finishNode(stat)
            }

            stat.parent = blockNode
            if (stat is ReturnStatement) {
                blockNode.returnStatement = stat
            } else {
                blockNode.addStatement(stat)
            }

            // ;
            consumeToken(LuaTokenTypes.SEMI)

            if (stat is ReturnStatement) {
                // Trailing end-of-line comments after `return` are trivia before
                // `end`/`else`/`until`/EOF — not a following statement (json.lua style).
                skipCommentTokens()
                if (!errorRecovery) {
                    break
                }
                // Recovery (TASK-641): keep residual following statement-starts as
                // siblings so incomplete return values / table fields do not leave
                // tokens for parseChunk EOF hard-errors
                // (e.g. `return { a = }\nprint(a)` → CallStmt sibling + diagnostics).
                // Block terminators still end the block so nested `end`/`else`/`until`
                // remain owned by the outer construct.
                val afterReturn = peek()
                if (isBlockTerminator(afterReturn)) {
                    break
                }
            }
        }


        if (parent != null) {
            blockNode.parent = parent
        }

        return finishNode(blockNode)
    }

    private fun shouldRecoverTopLevelBlockTerminator(parent: BaseASTNode?, token: LuaTokenTypes): Boolean {
        return recoverIncompleteWorkspaceSnippet &&
                errorRecovery &&
                parent is ChunkNode &&
                token != LuaTokenTypes.EOF
    }

    //    switch exp do {case explist [then] block} [default block] end
    private fun parseSwitchStatement(parent: BaseASTNode): SwitchStatement {
        expectToken(LuaTokenTypes.SWITCH) { "<switch> expected near ${lexerText(true)}" }
        markLocation()
        val result = SwitchStatement()
        val currentLine = lexer.line()
        result.parent = parent

        result.condition = parseExp(result)

        // AndroLua accepts both `switch exp do case ... end` and the compact
        // `switch exp case ... end` form used throughout Android-Lua assets
        // (main.lua / main2.lua / main13.lua / ThomeLua.lua).
        // Strict mode treats `do` as optional when the next token is case/default/end.
        // Recovery mode still emits `The <do> expected` for that compact form so the
        // missing-do recovery inventory and diagnostics expand suite stay green
        // (TASK-610 / recovery switch suite).
        val findDoToken = consumeToken(LuaTokenTypes.DO)
        if (!findDoToken) {
            val nextAfterCondition = peek()
            val optionalDoOk = isAndroLua() && equalsMore(
                nextAfterCondition,
                LuaTokenTypes.CASE,
                LuaTokenTypes.DEFAULT,
                LuaTokenTypes.END
            )
            if (!optionalDoOk) {
                if (!errorRecovery) {
                    error("The <do> expected near ${lexerText()}")
                }
                warning("The <do> expected near ${lexerText()}")
            } else if (errorRecovery) {
                // Compact AndroLua switch is legal under strict; recovery still records
                // the historical missing-do diagnostic for inventory determinism.
                warning("The <do> expected near ${lexerText()}")
            }
        }

        if (peekToken(LuaTokenTypes.CASE)) {
            result.causes.addAll(parseSwitchCaseList(result))
        }

        if (peekToken(LuaTokenTypes.DEFAULT)) {
            result.causes.add(finishNode(parseSwitchDefaultCaseStatement(result)))
        }

        if (!consumeToken(LuaTokenTypes.END)) {
            if (!errorRecovery) {
                error("<end> expected (to close 'switch' at line $currentLine) near ${lexerText()}")
            }
            warning("<end> expected (to close 'switch' at line $currentLine) near ${lexerText()}")
        }

        return result
    }

    // [default block]
    private fun parseSwitchDefaultCaseStatement(parent: BaseASTNode): DefaultCause {
        expectToken(LuaTokenTypes.DEFAULT) { "<default> expected near ${lexerText(true)}" }
        markLocation()
        val result = DefaultCause()
        result.parent = parent
        result.body = parseBlockNode(result)
        return result
    }

    // {case explist [then] block}
    private fun parseSwitchCaseList(parent: BaseASTNode): List<CaseCause> {
        val result = mutableListOf<CaseCause>()
        while (peekToken(LuaTokenTypes.CASE)) {
            result.add(finishNode(parseSwitchCaseStatement(parent)))
        }
        return result
    }

    //  case explist [then] block
    // Case body is a normal block terminated by case/default/end (see isBlockTerminator).
    // Shape: Case(conditions:Block[...]) nested under Switch(condition:cases) — no extra wrappers.
    private fun parseSwitchCaseStatement(parent: BaseASTNode): CaseCause {
        expectToken(LuaTokenTypes.CASE) { "<case> expected near ${lexerText(true)}" }
        markLocation()
        val result = CaseCause()

        result.parent = parent
        result.conditions.addAll(parseExpList(result))

        // optional then — record presence so AST2Lua can preserve optional-then fidelity
        result.hasThen = consumeToken(LuaTokenTypes.THEN)

        // Body statements only; CASE/DEFAULT/END stop the block so subsequent causes stay siblings.
        result.body = parseBlockNode(result)

        return result
    }

    //   retstat ::= return [explist] [‘;’]
    //
    // TASK-553 recoverFirst policy (return): first return value does not recover
    // call-shaped NAME after line break (same as local). Multi-line return values
    // are expressions; later terms share the keyword-only after-comma policy.
    // Caller already markLocation()'d at `return` and advanced past it.
    private fun parseReturnStatement(parent: BaseASTNode): ReturnStatement {
        val result = ReturnStatement()
        result.parent = parent

        val next = peek()
        if (isBlockTerminator(next) || next == LuaTokenTypes.SEMI) {
            consumeToken(LuaTokenTypes.SEMI)
            return result
        }

        result.arguments.addAll(
            parseExpList(
                result,
                recoverFirstStatementLineBreak = RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_RETURN
            )
        )

        consumeToken(LuaTokenTypes.SEMI)

        return result
    }

    //      when exp (varlist ‘=’ explist| functioncall) | [else (varlist ‘=’ explist | functioncall)]
    private fun parseWhenStatement(parent: BaseASTNode): WhenStatement {
        expectToken(LuaTokenTypes.WHEN) { "<when> expected near '${lexerText()}'" }
        markLocation()
        val result = WhenStatement()
        result.parent = parent

        result.condition = parseExp(result)

        result.ifCause = parseExpStatement(result)

        if (!consumeToken(LuaTokenTypes.ELSE)) {
            return result
        }

        result.elseCause = parseExpStatement(result)

        return result
    }

    //		if exp then block {elseif exp then block} [else block] end |
    //
    // TASK-598 if/elseif/else chain recovery product lock:
    // - Missing `then` on if/elseif still parses the clause body (block stops at
    //   elseif/else/end) and emits `The <then> expected`.
    // - Missing `end` emits `<end> expected (to close 'if' ...)` and keeps residual
    //   branch bodies inside the IfStatement; with a matching `end`, following
    //   statements remain outer-block siblings.
    // - Clause loop is bounded: only ELSEIF/ELSE advance the loop; after the first
    //   `else` clause further elseif/else stops so recovery cannot spin. Nested
    //   incomplete ifs stay bounded because each parseIfStatement owns its own
    //   clause loop and each branch body is a finite parseBlockNode.
    // - Incomplete conditions/bodies reuse shared expression recovery
    //   (ExpressionNodeSupport + statement-start after line-break policies).
    private fun parseIfStatement(parent: BaseASTNode): IfStatement {
        markLocation()
        val result = IfStatement()
        val currentLine = lexer.line()
        result.parent = parent

        result.causes.add(finishNode(parseIfCause(result)))

        var sawElse = false
        while (true) {
            when (peek()) {
                LuaTokenTypes.ELSEIF -> {
                    // elseif after else is illegal; stop so we can recover `end`
                    // / leave the unexpected token for the outer block.
                    if (sawElse) {
                        break
                    }
                    result.causes.add(finishNode(parseElseIfCause(result)))
                }

                LuaTokenTypes.ELSE -> {
                    if (sawElse) {
                        break
                    }
                    result.causes.add(finishNode(parseElseClause(result)))
                    sawElse = true
                }

                else -> break
            }
        }

        if (!consumeToken(LuaTokenTypes.END)) {
            if (!errorRecovery) {
                error("<end> expected (to close 'if' at line $currentLine) near ${lexerText()}")
            }
            warning("<end> expected (to close 'if' at line $currentLine) near ${lexerText()}")
            result.bad = true
        }

        if (errorRecovery && result.causes.any { it.bad }) {
            result.bad = true
        }

        return result
    }

    //       else block
    private fun parseElseClause(parent: BaseASTNode): IfClause {
        val result = ElseClause()
        result.parent = parent

        expectToken(LuaTokenTypes.ELSE) { "<else> expected near '${lexerText()}'" }
        markLocation()
        result.condition = emptyExpression(result)
        result.body = parseBlockNode(result)

        return result
    }

    //       elseif exp then block
    // TASK-598: missing `then` warns and still parses body; clause marked bad.
    // TASK-613: AndroLua optional `then` (same product rule as parseIfCause).
    // TASK-638: AndroLua recovery still records `The <then> expected` for structured
    // diagnostic goldens; strict mode continues to accept without marking the clause bad.
    private fun parseElseIfCause(parent: BaseASTNode): IfClause {
        val result = ElseIfClause()
        result.parent = parent

        expectToken(LuaTokenTypes.ELSEIF) { "<elseif> expected near '${lexerText()}'" }
        markLocation()
        result.condition = parseExp(result)

        val findThenToken = consumeToken(LuaTokenTypes.THEN)
        if (!findThenToken) {
            if (isAndroLua()) {
                // Optional `then` is product syntax under AndroLua strict mode.
                // Recovery still records the historical missing-then diagnostic so
                // expand/TDD goldens stay green without marking the clause bad.
                if (errorRecovery) {
                    warning("The <then> expected near ${lexerText()}")
                }
            } else if (!errorRecovery) {
                error("The <then> expected near ${lexerText()}")
            } else {
                warning("The <then> expected near ${lexerText()}")
                result.bad = true
            }
        }

        result.body = parseBlockNode(result)

        return result
    }

    //       if exp then block
    // TASK-598: missing `then` warns and still parses body; clause marked bad.
    // TASK-613: AndroLua/Android-Lua product syntax allows optional `then`
    // (asset-main scaleup/scaledown: `if actp.height<dp2px(50)\n else ... end`).
    // TASK-638: AndroLua recovery still records `The <then> expected` for structured
    // diagnostic goldens; strict mode continues to accept without marking the clause bad.
    private fun parseIfCause(parent: BaseASTNode): IfClause {
        val result = IfClause()
        result.parent = parent

        expectToken(LuaTokenTypes.IF) { "<if> expected near '${lexerText()}'" }
        markLocation()
        result.condition = parseExp(result)

        val findThenToken = consumeToken(LuaTokenTypes.THEN)
        if (!findThenToken) {
            if (isAndroLua()) {
                // Optional `then` is product syntax under AndroLua strict mode.
                // Recovery still records the historical missing-then diagnostic so
                // expand/TDD goldens stay green without marking the clause bad.
                if (errorRecovery) {
                    warning("The <then> expected near ${lexerText()}")
                }
            } else if (!errorRecovery) {
                error("The <then> expected near ${lexerText()}")
            } else {
                warning("The <then> expected near ${lexerText()}")
                result.bad = true
            }
        }

        result.body = parseBlockNode(result)

        return result
    }

    //  for Name ‘=’ exp ‘,’ exp [‘,’ exp] do block end |
    //             for namelist in explist do block end |
    // Caller already markLocation()'d at `for` and advanced past it.
    private fun parseForStatement(parent: BaseASTNode): StatementNode {
        //1. parse first name
        val name = parseStatementNameOrMissing(parent)

        //2. check `=`

        return if (peek() == LuaTokenTypes.ASSIGN) {
            parseForNumericStatement(name, parent)
        } else parseForGenericStatement(name, parent)
    }

    //             for namelist in explist do block end |
    private fun parseForGenericStatement(variable: Identifier, parent: BaseASTNode): ForGenericStatement {
        val result = ForGenericStatement()
        val currentLine = lexer.line()
        result.parent = parent
        variable.parent = result
        result.variables.add(variable)

        val findComma = consume { it == LuaTokenTypes.COMMA }

        if (findComma) {
            result.variables.addAll(parseNameList(result))
        }

        val findInToken = consumeToken(LuaTokenTypes.IN)
        if (!findInToken) {
            if (!errorRecovery) {
                error("The <in> expected near '${lexerText()}'")
            }
            warning("The <in> expected near '${lexerText()}'")
        }

        // expectToken(LuaTokenTypes.IN) { "<in> expected near '${lexerText()}'" }

        result.iterators.addAll(parseExpList(result))

        result.body = parseForBody(result)

        recoverToken(LuaTokenTypes.END) { "<end> expected (to close 'for' at line $currentLine) near '${lexerText()}'" }

        return result
    }

    //  for Name ‘=’ exp ‘,’ exp [‘,’ exp] do block end |
    private fun parseForNumericStatement(variable: Identifier, parent: BaseASTNode): ForNumericStatement {
        val result = ForNumericStatement()
        val currentLine = lexer.line()
        result.variable = variable
        variable.parent = result
        result.parent = parent

        expectToken(LuaTokenTypes.ASSIGN) { "'=' expected near '${lexerText()}'" }

        result.start = parseExpressionOrMissing(result)

        recoverToken(LuaTokenTypes.COMMA) { "',' expected near '${lexerText()}'" }

        result.end = parseExpressionOrMissing(result)

        val findComma = consume { it == LuaTokenTypes.COMMA }

        if (findComma) {
            result.step = parseExpressionOrMissing(result)
        }

        result.body = parseForBody(result)

        recoverToken(LuaTokenTypes.END) { "<end> expected (to close 'for' at line $currentLine) near ${lexerText()}" }

        return result
    }

    private fun parseForBody(parent: BaseASTNode): BlockNode {
        val findDoToken = consumeToken(LuaTokenTypes.DO)
        if (!findDoToken) {
            if (!errorRecovery) {
                error("The <do> expected near ${lexerText()}")
            }
            warning("The <do> expected near ${lexerText()}")
        }
        return parseBlockNode(parent)
    }

    //     goto Name |
    // Caller already markLocation()'d at `goto` and advanced past it.
    private fun parseGotoStatement(parent: BaseASTNode): GotoStatement {
        val result = GotoStatement()
        result.parent = parent
        result.identifier = parseStatementNameOrMissing(result)

        // Recovery residual (TASK-639 / GotoLabel suite): when a real NAME is absorbed as
        // the goto target, same-line leftover funcargs (e.g. `goto\nprint(1)` leaving `(1)`)
        // must not re-enter the statement loop as a sibling CallStmt. Strict mode leaves
        // those tokens so `goto\nprint(1)` still rejects. Empty/bad residual names keep
        // following tokens reachable as siblings.
        if (errorRecovery &&
            result.identifier.name.isNotEmpty() &&
            !result.identifier.bad
        ) {
            drainSameLineLeftoverFuncargsAfterGotoTarget()
        }

        return result
    }

    /**
     * Drain same-line call suffixes after an absorbed goto NAME so residual shapes stay
     * `Goto(Id(name))` without a sibling CallStmt for leftover `(...)` / `{...}` / string.
     */
    private fun drainSameLineLeftoverFuncargsAfterGotoTarget() {
        while (!hasLineBreakBeforeNextSignificantToken()) {
            when (peek()) {
                LuaTokenTypes.LPAREN,
                LuaTokenTypes.LCURLY,
                LuaTokenTypes.STRING,
                LuaTokenTypes.LONG_STRING -> {
                    // Throwaway base: parseCallExpression reparents its base; do not pass
                    // the real goto Identifier or its parent link is stolen.
                    val dummy = Identifier()
                    val discarded = parseCallExpression(dummy, dummy)
                    finishNodeSpanning(discarded, dummy.range.start)
                }

                else -> return
            }
        }
    }


    //      label ::= ‘::’ Name ‘::’
    // Caller already markLocation()'d at leading `::` and advanced past it.
    private fun parseLabelStatement(parent: BaseASTNode): LabelStatement {
        val result = LabelStatement()
        result.parent = parent
        result.identifier = parseStatementNameOrMissing(result)

        recoverToken(LuaTokenTypes.DOUBLE_COLON) { "'::' expected near '${lexerText()}'" }
        return result
    }

    //      function funcname funcbody |
    // Caller already markLocation()'d at `function` and advanced past it.
    private fun parseGlobalFunctionDeclaration(parent: BaseASTNode): FunctionDeclaration {
        val result = FunctionDeclaration()
        result.parent = parent
        var nameExp: ExpressionNode = parseStatementNameOrMissing(result)

        //   funcname ::= Name {‘.’ Name} [‘:’ Name]
        while (true) {
            val next = peek()
            nameExp = when (next) {
                // '.' NAME
                //  ':' NAME
                LuaTokenTypes.DOT, LuaTokenTypes.COLON -> {
                    val spanStart = nameExp.range.start
                    val field = parseFieldSet(result, nameExp)
                    finishNodeSpanning(field, spanStart)
                }

                else -> break
            }

            // [':' Name]
            if (next === LuaTokenTypes.COLON) {
                break
            }
        }

        result.identifier = nameExp

        return parseFunctionBody(result, result, lexer.line())
    }

    //		 repeat block until exp |
    // Caller already markLocation()'d at `repeat` and advanced past it.
    private fun parseRepeatStatement(parent: BaseASTNode): RepeatStatement {
        val result = RepeatStatement()
        result.parent = parent

        result.body = parseBlockNode(result)

        if (!consumeToken(LuaTokenTypes.UNTIL)) {
            if (!errorRecovery) {
                error("'until' expected near '${lexerText()}'")
            }
            warning("'until' expected near '${lexerText()}'")
            result.condition = missingExpression(result)
            return result
        }

        result.condition = parseExp(result)

        return result
    }

    //		 while exp do block end |
    // Caller already markLocation()'d at `while` and advanced past it.
    private fun parseWhileStatement(parent: BaseASTNode): WhileStatement {
        val result = WhileStatement()
        val currentLine = lexer.line()
        result.parent = parent
        result.condition = parseExp(result)

        val findDoToken = consumeToken(LuaTokenTypes.DO)
        if (!findDoToken) {
            if (!errorRecovery) {
                error("The <do> expected near ${lexerText()}")
            }
            warning("The <do> expected near ${lexerText()}")
        }

        result.body = parseBlockNode(result)

        if (!consumeToken(LuaTokenTypes.END)) {
            if (!errorRecovery) {
                error("<end> expected (to close 'do' at line $currentLine) near ${lexerText()}")
            }
            warning("<end> expected (to close 'do' at line $currentLine) near ${lexerText()}")
        }

        return result
    }

    //  stat -> func | assignment
    private fun parseExpStatement(parent: BaseASTNode): StatementNode {
        peek { markLocation() }
        val suffix = parsePrefixExp(parent)

        val peekToken = peek()

        return if (suffix is Identifier || equalsMore(peekToken, LuaTokenTypes.ASSIGN, LuaTokenTypes.COMMA)) {
            if (suffix is Identifier && !equalsMore(peekToken, LuaTokenTypes.ASSIGN, LuaTokenTypes.COMMA)) {
                if (!errorRecovery) {
                    parseAssignmentStatement(parent, suffix)
                }

                val recoveredExpression = if (binaryPrecedence(peekToken) > 0) {
                    parseSubExpTail(parent, suffix, 0)
                } else {
                    suffix
                }

                CallStatement().apply {
                    this.parent = parent
                    expression = CallExpression().apply {
                        this.parent = this@apply
                        base = recoveredExpression
                        base.parent = this
                        bad = true
                    }
                }
            } else {
                parseAssignmentStatement(parent, suffix)
            }
        } else {

            /*  if (suffix is Identifier) {
                  //The target is functioncall, but no match is made.
                  //Then it is an incorrect assignment
                  parseAssignmentStatement(parent, suffix)
              }*/

            // function call
            CallStatement().apply {
                this.parent = parent
                suffix.parent = this

                if (suffix !is CallExpression) {
                    if (!errorRecovery) {
                        error("The assignment statement is incorrect near ${lexerText()}")
                    }

                    expression = CallExpression().apply {
                        this.parent = this@apply
                        this.base = suffix
                        suffix.parent = this
                        bad = true
                    }
                } else {
                    expression = suffix
                    suffix.parent = this
                }


            }
        }
    }

    //  varlist ‘=’ explist |
    //
    // TASK-553 recoverFirst policy (assignment):
    // First RHS uses recoverFirstStatementLineBreak=true so incomplete
    //   a =
    //   print(a)
    // inserts ExpressionNodeSupport and keeps print as a sibling CallStmt.
    // Later multi-RHS terms still use the shared TASK-546 later-term policy
    // (keyword-only recovery after comma) and are not poisoned by first-RHS recovery.
    //
    // TASK-649 missing-`=` varlist residual (s011):
    //   a, b
    //   print(a)
    // After a comma, a bare NAME that is not continued as another target
    // (no `,` / `=` / `.` / `:` / `[` after it) is left unconsumed so the outer
    // block recovers it as CallStmt(Call(Id(b):)). The assignment keeps
    // ExpressionNodeSupport on the LHS and is marked bad — do not absorb the
    // following NAME as a second init target when `=` is missing.
    private fun parseAssignmentStatement(parent: BaseASTNode, base: ExpressionNode): AssignmentStatement {
        val initList = mutableListOf<ExpressionNode>()
        val result = AssignmentStatement()
        result.parent = parent
        base.parent = result
        initList.add(base)

        while (peek() == LuaTokenTypes.COMMA) {
            // ,
            advance()
            if (errorRecovery && peek() == LuaTokenTypes.ASSIGN) {
                initList.add(missingExpression(result))
                break
            }
            if (shouldRecoverResidualBareNameAfterAssignmentComma()) {
                initList.add(missingExpression(result))
                break
            }
            initList.add(parseAssignmentTargetOrMissing(result))
        }

        val hasAssign = recoverToken(LuaTokenTypes.ASSIGN) { "'=' expected near '${lexerText()}'" }

        result.init.addAll(initList)
        if (!hasAssign && errorRecovery) {
            result.bad = true
            return result
        }
        result.variables.addAll(
            parseExpList(
                result,
                recoverFirstStatementLineBreak = RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_ASSIGNMENT
            )
        )
        return result
    }

    /**
     * After `var,` under recovery, detect a bare NAME that cannot continue the
     * assignment varlist (not followed by `=` / `,` / member or index suffix).
     * Leave that NAME for outer statement recovery instead of absorbing it as
     * another LHS target when `=` is missing (`a, b\nprint(a)` →
     * Assign(Id(a),ExpressionNodeSupport=) + Call(Id(b):)).
     */
    private fun shouldRecoverResidualBareNameAfterAssignmentComma(): Boolean {
        if (!errorRecovery) {
            return false
        }
        if (peek() != LuaTokenTypes.NAME) {
            return false
        }
        val afterName = peekN(2)
        if (equalsMore(afterName, LuaTokenTypes.ASSIGN, LuaTokenTypes.COMMA)) {
            return false
        }
        if (equalsMore(
                afterName,
                LuaTokenTypes.DOT,
                LuaTokenTypes.COLON,
                LuaTokenTypes.LBRACK
            )
        ) {
            return false
        }
        return true
    }

    // do block end |
    // Caller already markLocation()'d at `do` and advanced past it.
    private fun parseDoStatement(parent: BaseASTNode): DoStatement {
        val result = DoStatement()
        val currentLine = lexer.line()

        result.body = parseBlockNode(result)
        result.parent = parent

        if (!consumeToken(LuaTokenTypes.END)) {
            if (!errorRecovery) {
                error("<end> expected (to close 'do' at line $currentLine) near ${lexerText()}")
            }
            warning("<end> expected (to close 'do' at line $currentLine) near ${lexerText()}")
        }

        return result
    }

    //      funcbody ::= ‘(’ [parlist] ‘)’ block end
    private fun parseFunctionBody(
        node: FunctionDeclaration,
        parent: BaseASTNode,
        currentLine: Int
    ): FunctionDeclaration {

        recoverToken(LuaTokenTypes.LPAREN) { "( expected near '${lexerText()}'" }


        val hasLineBreakBeforeFirstParameterToken = hasLineBreakBeforeNextSignificantToken()
        val firstParameterToken = peek()

        // empty arg
        if (firstParameterToken != LuaTokenTypes.RPAREN &&
            !isBlockTerminator(firstParameterToken) &&
            !(firstParameterToken == LuaTokenTypes.RETURN && hasLineBreakBeforeFirstParameterToken)
        ) {
            node.params.addAll(parseFunctionParameterList(node))
        }

        recoverToken(LuaTokenTypes.RPAREN) { ") expected near '${lexerText()}'" }

        node.body = parseBlockNode(node)

        recoverToken(LuaTokenTypes.END) { "<end> expected (to close 'function' at line $currentLine) near ${lexerText()}" }

        return node
    }

    private fun parseFunctionParameterList(parent: BaseASTNode): List<Identifier> {
        val result = mutableListOf<Identifier>()

        fun parseParameter(): Identifier {
            if (consumeToken(LuaTokenTypes.ELLIPSIS)) {
                return Identifier("...").also { it.parent = parent }
            }
            return parseNameOrMissing(parent)
        }

        result.add(parseParameter())

        while (consumeToken(LuaTokenTypes.COMMA)) {
            val parameter = parseParameter()
            result.add(parameter)
            if (parameter.name == "...") {
                break
            }
        }

        return result
    }

    //		 local function Name funcbody
    private fun parseLocalFunctionDeclaration(parent: BaseASTNode): FunctionDeclaration {
        // Caller already markLocation()'d at `local`.
        val result = FunctionDeclaration()
        val currentLine = lexer.line()
        result.parent = parent
        result.isLocal = true

        val name = parseStatementNameOrMissing(result)

        result.identifier = name

        return parseFunctionBody(result, result, currentLine)
    }

    private fun parseName(parent: BaseASTNode, supportDollarSymbol: Boolean = false): Identifier {
        expectToken(LuaTokenTypes.NAME) { "<name> expected near ${lexerText()}" }
        markLocation()
        var name = lexerText()
        if (name.startsWith('$')) {
            if (!supportDollarSymbol) {
                error("'$' is not allowed in name ${lexerText()}")
            }
            name = name.substring(1)
        }
        val identifier = Identifier(name.toString())
        identifier.parent = parent
        return finishNode(identifier)
    }

    // attrib ::= [‘<’ Name ‘>’]
    // Name attrib
    private fun parseAttribute(parent: BaseASTNode): AttributeIdentifier {
        val result = AttributeIdentifier()
        result.parent = parent

        // parse name
        val name = parseNameOrMissing(result)

        result.name = name.name
        result.bad = name.bad

        // check <
        if (!consumeToken(LuaTokenTypes.LT)) {
            return result
        }

        val attributeName = parseNameOrMissing(result)

        result.attributeName = attributeName.name
        result.bad = result.bad || attributeName.bad

        if (!consumeToken(LuaTokenTypes.GT)) {
            if (!errorRecovery) {
                error("'>' expected near ${lexerText()}")
            }
            warning("'>' expected near ${lexerText()}")
        }

        return result
    }

    // attnamelist ::=  Name attrib {‘,’ Name attrib}
    private fun parseAttrNameList(parent: BaseASTNode): List<AttributeIdentifier> {
        val result = mutableListOf<AttributeIdentifier>()

        result.add(parseAttribute(parent))

        val hasComma = consumeToken(LuaTokenTypes.COMMA)

        if (!hasComma) {
            return result
        }
        var nameNode = parseAttribute(parent)
        while (true) {
            result.add(nameNode)
            if (!consumeToken(LuaTokenTypes.COMMA)) break
            nameNode = parseAttribute(parent)
        }

        return result
    }

    // namelist ::= Name {‘,’ Name}
    private fun parseNameList(parent: BaseASTNode, supportDollarSymbol: Boolean = false): List<Identifier> {
        val result = mutableListOf<Identifier>()

        result.add(parseNameOrMissing(parent, supportDollarSymbol = supportDollarSymbol))

        val hasComma = consumeToken(LuaTokenTypes.COMMA)
        if (!hasComma) {
            return result
        }
        var nameNode = parseNameOrMissing(parent, supportDollarSymbol = supportDollarSymbol)
        while (true) {
            result.add(nameNode)
            if (!consumeToken(LuaTokenTypes.COMMA)) break
            nameNode = parseNameOrMissing(parent, supportDollarSymbol = supportDollarSymbol)
        }

        return result

    }


    //      exp ::= (unop exp | primary | prefixexp ) { binop exp }
    //
    //     primary ::= nil | false | true | Number | String | '...'
    //          | functiondef | tableconstructor | lambdadef | arrayconstructor
    //
    //
    private fun parseExp(parent: BaseASTNode): ExpressionNode {
        return parseSubExp(parent, 0).requireNotNull()
    }

    private fun binaryPrecedence(tokenTypes: LuaTokenTypes): Int {
        return when (tokenTypes) {
            LuaTokenTypes.OR -> 1
            LuaTokenTypes.AND -> 2
            LuaTokenTypes.LT, LuaTokenTypes.GT, LuaTokenTypes.LE, LuaTokenTypes.GE, LuaTokenTypes.EQ, LuaTokenTypes.NE -> 3

            LuaTokenTypes.BIT_OR -> 4
            LuaTokenTypes.BIT_TILDE -> 5
            LuaTokenTypes.BIT_AND -> 6
            LuaTokenTypes.BIT_LTLT, LuaTokenTypes.BIT_RTRT -> 7
            LuaTokenTypes.CONCAT -> 8
            LuaTokenTypes.PLUS, LuaTokenTypes.MINUS -> 9
            LuaTokenTypes.DOUBLE_DIV, LuaTokenTypes.DIV, LuaTokenTypes.MOD, LuaTokenTypes.MULT -> 10

            LuaTokenTypes.EXP -> 12
            else -> 0
        }
    }

    private fun isRightAssociative(tokenTypes: LuaTokenTypes): Boolean {
        return tokenTypes == LuaTokenTypes.CONCAT || tokenTypes == LuaTokenTypes.EXP
    }


    private fun findExpressionOperator(text: CharSequence): ExpressionOperator? {
        // Canonical operator spellings live on ExpressionOperator; Android-Lua also
        // uses C-style spellings that the lexer emits as the same token kinds
        // (`!=` as NE, `&&` as AND, `||` as OR, bare `!` as unary NOT).
        return when (text.toString()) {
            "!=", "~=" -> ExpressionOperator.NE
            "&&", "and" -> ExpressionOperator.AND
            "||", "or" -> ExpressionOperator.OR
            "!", "not" -> ExpressionOperator.NOT
            else -> ExpressionOperator.entries.find { it.value == text.toString() }
        }
    }


    //          functiondef ::= function funcbody
    private fun parseFunctionExp(parent: BaseASTNode): FunctionDeclaration {
        val result = FunctionDeclaration()
        result.parent = parent
        return parseFunctionBody(result, result, lexer.line())
    }

    //
    private fun parseSubExp(parent: BaseASTNode, minPrecedence: Int): ExpressionNode {
        val currentToken = peek {
            markLocation()
            it
        }
        var node: ExpressionNode = when {

            equalsMore(
                currentToken, LuaTokenTypes.MINUS, LuaTokenTypes.GETN,
                LuaTokenTypes.BIT_TILDE, LuaTokenTypes.NOT
            ) -> {
                // unary
                parseUnaryExpression(
                    parent
                )
            }

            // primary
            currentToken == LuaTokenTypes.ELLIPSIS -> consume { VarargLiteral() }
            currentToken == LuaTokenTypes.NIL -> consume { ConstantNode.NIL.clone() }

            equalsMore(
                currentToken, LuaTokenTypes.FALSE, LuaTokenTypes.TRUE
            ) -> consume { ConstantNode(ConstantNode.TYPE.BOOLEAN, lexerText()) }

            equalsMore(
                currentToken, LuaTokenTypes.LONG_STRING, LuaTokenTypes.STRING
            ) -> consume { ConstantNode(ConstantNode.TYPE.STRING, lexerText()) }

            currentToken == LuaTokenTypes.NUMBER -> consume {
                // Lua 5.3 number typing: not merely "contains '.'" —
                // hex floats (0x1.8p1 / 0x1p10), scientific (1e3), and
                // hex integers (0xFF / 0x1e) must be classified correctly.
                ConstantNode.fromNumberLexeme(lexerText())
            }

            currentToken == LuaTokenTypes.LAMBDA -> {
                assertVersion(LuaVersion.ANDROLUA_5_3) {
                    "lambda expression is only supported in androlua 5.3"
                }
                parseLambdaExp(parent)
            }

            currentToken == LuaTokenTypes.FUNCTION -> consume {
                parseFunctionExp(parent)
            }

            currentToken == LuaTokenTypes.LBRACK -> {
                assertVersion(LuaVersion.ANDROLUA_5_3) {
                    "array constructor is only supported in androlua 5.3"
                }
                parseArrayConstructorExpression(parent)
            }

            currentToken == LuaTokenTypes.LCURLY -> parseTableConstructorExpression(parent)

            currentToken == LuaTokenTypes.BAD_CHARACTER && errorRecovery -> malformedLiteral(parent)

            else -> {
                if (errorRecovery && isExpressionTerminator(currentToken)) {
                    missingExpression(parent)
                } else {
                    parsePrefixExp(parent)
                }
            }

        }

        node = finishNode(node.requireNotNull())
        return parseSubExpTail(parent, node, minPrecedence)
    }

    private fun parseSubExpTail(parent: BaseASTNode, initial: ExpressionNode, minPrecedence: Int): ExpressionNode {
        var node = initial
        while (true) {
            val operatorToken = peek()
            val precedence = binaryPrecedence(operatorToken)
            if (precedence <= 0 || precedence < minPrecedence) {
                node.parent = parent
                return node
            }

            advance()
            // Composite binary spans from the left operand, not the operator token.
            // Popping a markLocation() here would leave a dangling start on the stack
            // and would report the wrong start column for forms like `a + b * c`.
            val leftOperand = binaryLeftOperand(node)
            val spanStart = leftOperand.range.start
            node = BinaryExpression().apply {
                this.parent = parent
                left = leftOperand
                left?.parent = this
                operator = findExpressionOperator(lexerText()).requireNotNull()
            }

            val nextMinPrecedence = if (isRightAssociative(operatorToken)) precedence else precedence + 1
            val hasLineBreakBeforeRight = hasLineBreakBeforeNextSignificantToken()
            val rightToken = peek()
            node.right = if (errorRecovery && isExpressionTerminator(rightToken)) {
                missingExpression(node)
            } else if (errorRecovery && hasLineBreakBeforeRight && isStatementStart(rightToken)) {
                missingExpression(node)
            } else {
                parseSubExp(node, nextMinPrecedence)
            }
            finishNodeSpanning(node, spanStart)
        }
    }

    //   arrayconstructor ::= '[' [explist] ']'
    private fun parseArrayConstructorExpression(parent: BaseASTNode): ArrayConstructorExpression {
        expectToken(LuaTokenTypes.LBRACK) { "'[' expected near ${lexerText()}" }
        val result = ArrayConstructorExpression()
        result.parent = parent

        if (consumeToken(LuaTokenTypes.RBRACK)) {
            // empty token
            return result
        }

        if (isExpressionStart(peek())) {
            result.values.addAll(parseExpList(result))
        }
        recoverToken(LuaTokenTypes.RBRACK) { "']' expected near ${lexerText()}" }

        return result
    }

    //   lambdadef ::= lambda ( [parlist] | ['(' [parlist] ')'] ) (':'|'=>','->') exp
    private fun parseLambdaExp(parent: BaseASTNode): LambdaDeclaration {
        expectToken(LuaTokenTypes.LAMBDA) { "<lambda> expected near ${lexerText()}" }
        // Start was markLocation()'d by parseSubExp at the lambda keyword; do not re-mark.
        val result = LambdaDeclaration()
        result.parent = parent

        // ['(' [parlist] ')']
        (func@{
            if (!consumeToken(LuaTokenTypes.LPAREN)) return@func
            if (consumeToken(LuaTokenTypes.RPAREN)) return@func
            if (peekToken(LuaTokenTypes.NAME)) {
                result.params.addAll(parseNameList(result))
            }
            recoverToken(LuaTokenTypes.RPAREN) { "')' expected near ${lexerText()}" }
        }).invoke()

        // [parlist]
        if (peekToken(LuaTokenTypes.NAME)) {
            result.params.addAll(parseNameList(result))
        }

        (func@{
            if (consumeToken(LuaTokenTypes.COLON)) return@func
            if (consumeToken(LuaTokenTypes.MINUS)) {
                if (!consumeToken(LuaTokenTypes.GT)) {
                    if (!errorRecovery) {
                        error("'->' expected near ${lexerText()}")
                    }
                    warning("'->' expected near ${lexerText()}")
                }
                return@func
            }
            if (consumeToken(LuaTokenTypes.ASSIGN)) {
                if (!consumeToken(LuaTokenTypes.GT)) {
                    if (!errorRecovery) {
                        error("'=>' expected near ${lexerText()}")
                    }
                    warning("'=>' expected near ${lexerText()}")
                }
                return@func
            }
            if (!errorRecovery) {
                error("':' expected near ${lexerText()}")
            }
            warning("':' expected near ${lexerText()}")
        }).invoke()

        result.expression = if (errorRecovery && isExpressionTerminator(peek())) {
            missingExpression(result)
        } else {
            parseExp(result)
        }
        // Range finished by parseSubExp's finishNode.
        return result
    }

    //  tableconstructor ::= ‘{’ [fieldlist] ‘}’
    private fun parseTableConstructorExpression(parent: BaseASTNode): TableConstructorExpression {
        val result = TableConstructorExpression()
        val currentLine = lexer.line()
        result.parent = parent
        expectToken(LuaTokenTypes.LCURLY) { "'{' expected near ${lexerText()}" }
        // Start position is markLocation()'d by the caller:
        // - parseSubExp peeks `{` and marks before entering
        // - parseTableCallExpression marks before finishNode(this)
        // Do not markLocation() here or the locations stack desyncs.
        if (consumeToken(LuaTokenTypes.RCURLY)) {
            // empty table
            return result
        }
        result.fields.addAll(parseFieldList(result))
        recoverToken(LuaTokenTypes.RCURLY) { "'}' expected (to close '{' at line $currentLine) near ${lexerText()}" }

        return result
    }

    //  fieldlist ::= field {fieldsep field} [fieldsep]
    //  fieldsep ::= ‘,’ | ‘;’
    //
    // Comments between fields (common in Android-Lua layout tables, e.g.
    // `--android:drawingCacheQuality`) are trivia, not array-field expressions.
    private fun parseFieldList(parent: BaseASTNode): List<TableKey> {
        val result = mutableListOf<TableKey>()

        var index = 1
        skipCommentTokens()
        val firstField = parseField(parent, index)
        if (firstField == null) {
            skipCommentTokens()
            consume { equalsMore(it, LuaTokenTypes.COMMA, LuaTokenTypes.SEMI) }
            return result
        }
        if (firstField.implicitArrayField) {
            index++
        }
        result.add(finishNode(firstField.field))

        while (true) {
            // , / ; with optional comments before/after the separator
            skipCommentTokens()
            if (!equalsMore(peek(), LuaTokenTypes.COMMA, LuaTokenTypes.SEMI)) {
                break
            }
            advance()
            skipCommentTokens()
            val fieldValue = parseField(parent, index) ?: break
            if (fieldValue.implicitArrayField) {
                index++
            }
            result.add(finishNode(fieldValue.field))
        }

        skipCommentTokens()
        consume { equalsMore(it, LuaTokenTypes.COMMA, LuaTokenTypes.SEMI) }
        skipCommentTokens()

        return result
    }

    //  field ::= ‘[’ exp ‘]’ ‘=’ exp | Name ‘=’ exp | exp
    //
    // TASK-642 layout-table product lock:
    // Bare Name after `{` + newline is a valid array-field expression start
    // (LinearLayout / TextView / ...). parseExpressionOrMissing only inserts
    // ExpressionNodeSupport for keyword statement starts or call-shaped Name
    // after a line break; bare Name remains TableKey(Const(n)=Id(...)).
    private fun parseField(parent: BaseASTNode, index: Int): ParsedTableField? {
        skipCommentTokens()
        when (peek()) {
            //  Name ‘=’ exp |
            LuaTokenTypes.NAME -> {
                val peek = peekN(2)
                if (peek == LuaTokenTypes.ASSIGN) {
                    return ParsedTableField(parseTableStringKey(parent), implicitArrayField = false)
                }
                if (errorRecovery && peek != LuaTokenTypes.COMMA && peek != LuaTokenTypes.SEMI &&
                    peek != LuaTokenTypes.RCURLY && !canStartTableArrayField(peek)
                ) {
                    return ParsedTableField(parseTableStringKey(parent), implicitArrayField = false)
                }
            }

            LuaTokenTypes.LBRACK -> return ParsedTableField(parseTableKey(parent), implicitArrayField = false)
            LuaTokenTypes.EOF, LuaTokenTypes.RCURLY, LuaTokenTypes.RPAREN -> return null
            // Comments already skipped; remaining non-expression tokens end the field list.
            LuaTokenTypes.SHORT_COMMENT,
            LuaTokenTypes.BLOCK_COMMENT,
            LuaTokenTypes.DOC_COMMENT -> return null
            // exp |
            // It is possible to encounter '}',
            // and since we cannot tell if this is an expression, we use nullable return.
            else -> {}
        }

        markLocation()

        val result = TableKey()
        result.parent = parent
        // int
        result.key = ConstantNode(ConstantNode.TYPE.INTERGER, index).apply {
            range = Range(locations.first(), locations.first())
            this.parent = result
        }
        result.value = parseExpressionOrMissing(result)

        return ParsedTableField(result, implicitArrayField = true)
    }

    /**
     * Bare top-level table constructor (AndroLua `.aly` layout form).
     * Equivalent to the runtime `return <table>` wrap performed by alyloader.
     */
    private fun parseTopLevelTableReturn(parent: BaseASTNode): ReturnStatement {
        val result = ReturnStatement()
        result.parent = parent
        result.arguments.add(parseExp(result))
        return result
    }

    //  ‘[’ exp ‘]’ ‘=’ exp
    private fun parseTableKey(parent: BaseASTNode): TableKey {
        val result = TableKey()
        result.parent = parent
        expectToken(LuaTokenTypes.LBRACK) { "'[' expected near ${lexerText(true)}" }
        markLocation()
        result.key = parseExpressionOrMissing(result)

        val hasRightBracket = recoverToken(LuaTokenTypes.RBRACK) { "']' expected near ${lexerText()}" }
        var hasAssign = true
        if (!hasRightBracket && errorRecovery && peekToken(LuaTokenTypes.ASSIGN)) {
            advance()
        } else {
            hasAssign = recoverToken(LuaTokenTypes.ASSIGN) { "'=' expected near ${lexerText()}" }
        }
        result.bad = !hasRightBracket || !hasAssign

        result.value = parseExpressionOrMissing(result)

        return result
    }

    //   Name ‘=’ exp
    private fun parseTableStringKey(parent: BaseASTNode): TableKeyString {
        val result = TableKeyString()
        result.parent = parent

        val name = parseName(result)
        markLocation()
        //val nameIndex = lexer.yychar()
        result.key = name

        val hasAssign = recoverToken(LuaTokenTypes.ASSIGN) { "'=' expected near ${lexerText()}" }
        result.bad = !hasAssign

        result.value = parseExpressionOrMissing(result)
        return result
    }

    //  primaryexp ::= NAME | '(' expr ')' *
    private fun parsePrimaryExp(parent: BaseASTNode): ExpressionNode {
        return when (peek()) {
            LuaTokenTypes.NAME -> parseName(parent)
            LuaTokenTypes.LPAREN -> {
                advance()
                markLocation()
                val exp = parseExp(parent)
                recoverToken(LuaTokenTypes.RPAREN) { "')' expected near ${lexerText()}" }
                finishNode(exp)
            }

            else -> error("<expression> expected near ${lexerText(true)}")
        }
    }

    //  prefixExp ::= primaryexp { '.' fieldset | '[' exp ']' | ':' NAME funcargs | funcargs  }

    private fun parsePrefixExp(parent: BaseASTNode): ExpressionNode {
        var result = parsePrimaryExp(parent)

        var parentNode = parent
        var stopAfterRecovery = false

        while (true) {
            val spanStart = result.range.start
            result = when (peek()) {
                // '.' fieldset*
                LuaTokenTypes.DOT ->
                    parseFieldSet(parentNode, result).also {
                        if (errorRecovery && it.bad) {
                            stopAfterRecovery = true
                        }
                    }

                // [' exp ']'
                LuaTokenTypes.LBRACK ->
                    parseIndexExpression(parentNode, result)

                // funcargs
                LuaTokenTypes.LPAREN, LuaTokenTypes.LCURLY, LuaTokenTypes.STRING, LuaTokenTypes.LONG_STRING ->
                    parseCallExpression(parent, result)

                //  ':' NAME funcargs
                LuaTokenTypes.COLON -> {
                    val fieldSet = parseFieldSet(parent, result)

                    if (errorRecovery && fieldSet.bad) {
                        stopAfterRecovery = true
                        fieldSet
                    } else {
                        if (equalsMore(peek(), LuaTokenTypes.LPAREN, LuaTokenTypes.LCURLY, LuaTokenTypes.STRING, LuaTokenTypes.LONG_STRING)) {
                            // Finish the member node spanning from the base before wrapping in a call.
                            finishNodeSpanning(fieldSet, spanStart)
                            parseCallExpression(parent, fieldSet)
                        } else {
                            fieldSet
                        }
                    }

                }

                else -> break
            }
            // Composite prefix suffixes span from the base primary, not the '.' / '[' / call token.
            finishNodeSpanning(result, spanStart)
            parentNode = result
            if (stopAfterRecovery) {
                break
            }
        }

        return result

    }


    // funcargs -> '(' [ explist ] ') | tableconstructor | string
    private fun parseCallExpression(parent: BaseASTNode, base: ExpressionNode): CallExpression {
        val result = CallExpression()
        result.parent = parent
        result.base = base
        base.parent = result
        // CallExpression range is finished by the caller via finishNodeSpanning(base.range.start).
        // Do not markLocation() here — that would leave a dangling stack entry.
        // consume
        val isOnlyExpList = when (peek()) {
            LuaTokenTypes.STRING, LuaTokenTypes.LONG_STRING -> {
                result.base = parseStringCallExpression(result, base).let { stringCall ->
                    finishNodeSpanning(stringCall, base.range.start)
                }
                false
            }

            LuaTokenTypes.LCURLY -> {
                result.base = parseTableCallExpression(result, base).let { tableCall ->
                    finishNodeSpanning(tableCall, base.range.start)
                }
                false
            }

            else -> true
        }

        val findLeft = consume { it == LuaTokenTypes.LPAREN }
        val hasLeftParen = findLeft
        if (!findLeft && !isOnlyExpList) {
            if (consumeToken(LuaTokenTypes.COMMA)) {
                result.arguments.addAll(parseCallArgumentList(result))
            }
            consumeToken(LuaTokenTypes.SEMI)
            return result
        }

        if (!hasLeftParen) {
            result.bad = true
            return result
        }

        // empty arg list: f()
        // Capture line-break status BEFORE any peek/pushback. WrapperLuaLexer.pushback leaves
        // currentState on the peeked token, so hasLineBreakBeforeNextSignificantToken() would
        // otherwise look past the next name (e.g. activity) and miss the newline after '('.
        val hasLineBreakBeforeNext = hasLineBreakBeforeNextSignificantToken()
        val nextAfterLeftParen = peek()

        if (nextAfterLeftParen == LuaTokenTypes.RPAREN) {
            advance()
            return result
        }

        // Incomplete call recovery for forms like:
        //   view:setText(
        //   activity.setContentView(view)
        // If the next significant token starts a statement after a line break, mark the call
        // bad, emit `')' expected`, and leave that token unconsumed so the following
        // statement remains a sibling CallStmt rather than a nested argument.
        if (errorRecovery &&
            hasLineBreakBeforeNext &&
            isStatementStart(nextAfterLeftParen)
        ) {
            result.bad = true
            val nearText = peek {
                tokenText = null
                lexer.text()
            }
            warning("')' expected near $nearText")
            return result
        }

        if (isExpressionStart(nextAfterLeftParen)) {
            // Assignment/local RHS incomplete call (TASK-643 / LocalAssign REVIEW36B):
            // `a = factory(seed\nprint(a)` must not absorb bare `seed` as the call arg.
            // Leave that NAME unconsumed so the block recovers it as CallStmt(Call(Id(seed):))
            // and insert ExpressionNodeSupport for the missing arg list. Top-level /
            // statement call forms (`foo(a\nprint(a)`) keep the bare NAME arg (TASK-551).
            if (errorRecovery &&
                (parent is AssignmentStatement || parent is LocalStatement) &&
                nextAfterLeftParen == LuaTokenTypes.NAME &&
                shouldLeaveBareNameAsSiblingInsteadOfCallArg()
            ) {
                result.arguments.add(missingExpression(result))
                result.bad = true
            } else {
                // Call-arg recovery is stricter than assignment multi-RHS (TASK-551):
                // trailing comma + next-line statement-start must not absorb print/setContentView.
                result.arguments.addAll(parseCallArgumentList(result))
            }
        }

        if (!recoverToken(LuaTokenTypes.RPAREN) { "')' expected near ${lexerText()}" }) {
            if (errorRecovery) {
                result.bad = true
            }
        }

        return result
    }

    /**
     * After `(`, when the next significant token is a bare NAME that should not become a
     * call argument under assignment/local RHS recovery (TASK-643): there is a line break
     * after that NAME and the following token is a statement start (keyword or call-shaped
     * NAME). Leaves the NAME unconsumed for the outer block.
     *
     * Walks the lexer temporarily and restores with [WrapperLuaLexer.back].
     */
    private fun shouldLeaveBareNameAsSiblingInsteadOfCallArg(): Boolean {
        var backSize = 0
        var significant = 0
        var afterNameIsStatementStart = false

        while (significant < 2) {
            val token = lexer.advance()
            backSize++
            if (token == LuaTokenTypes.EOF) {
                lexer.back(backSize)
                return false
            }
            if (ignoreToken(token)) {
                continue
            }
            significant++
            if (significant == 1) {
                if (token != LuaTokenTypes.NAME) {
                    lexer.back(backSize)
                    return false
                }
                // Line break must sit between the bare NAME and the following statement so
                // well-formed same-line calls (and multi-line multi-arg forms) stay intact.
                if (!lexer.hasLineBreakBeforeNextSignificantToken()) {
                    lexer.back(backSize)
                    return false
                }
            } else {
                // significant == 2: first significant token after the bare NAME.
                if (isKeywordStatementStart(token)) {
                    afterNameIsStatementStart = true
                } else if (token == LuaTokenTypes.NAME) {
                    var nextAfterName = LuaTokenTypes.EOF
                    while (true) {
                        val n = lexer.advance()
                        backSize++
                        if (n == LuaTokenTypes.EOF) {
                            break
                        }
                        if (ignoreToken(n)) {
                            continue
                        }
                        nextAfterName = n
                        break
                    }
                    afterNameIsStatementStart = equalsMore(
                        nextAfterName,
                        LuaTokenTypes.LPAREN,
                        LuaTokenTypes.LCURLY,
                        LuaTokenTypes.STRING,
                        LuaTokenTypes.LONG_STRING
                    )
                }
            }
        }

        lexer.back(backSize)
        return afterNameIsStatementStart
    }

    /**
     * Call argument list recovery (TASK-551 / TASK-178 / TASK-647 lineage).
     *
     * Unlike assignment multi-RHS (TASK-546), call args treat a line-break + statement-start
     * after a trailing comma as the end of the call so following top-level / loop-body
     * statements remain siblings. Call-shaped NAME (`print(`, member chain
     * `activity.setContentView(`) recovers as a sibling; bare NAME RHS after comma still
     * parses as a later arg so well-formed multi-line calls like `foo(\n a,\n b\n)` stay
     * intact.
     */
    private fun parseCallArgumentList(parent: BaseASTNode): List<ExpressionNode> {
        val result = mutableListOf<ExpressionNode>()

        result.add(
            parseExpressionOrMissing(
                parent,
                recoverStatementStartAfterLineBreak = true
            )
        )

        while (consumeToken(LuaTokenTypes.COMMA)) {
            val hasLineBreakBeforeNextExpression = hasLineBreakBeforeNextSignificantToken()
            val nextExpressionToken = peek()

            if (errorRecovery) {
                // Trailing comma immediately before `)` / terminator / EOF: stop and let
                // the caller emit `')' expected` (or consume `)`).
                if (nextExpressionToken == LuaTokenTypes.RPAREN ||
                    isExpressionTerminator(nextExpressionToken) ||
                    nextExpressionToken == LuaTokenTypes.EOF
                ) {
                    break
                }

                // `foo(a,\nprint(x))` / `foo(a,\nlocal x = 1)`: do not absorb the next
                // statement as a call argument.
                if (hasLineBreakBeforeNextExpression &&
                    shouldRecoverStatementStartAsMissingExpression(nextExpressionToken)
                ) {
                    break
                }
            }

            result.add(
                parseExpressionOrMissing(
                    parent,
                    recoverStatementStartAfterLineBreak = false
                )
            )
        }

        return result
    }

    private fun parseTableCallExpression(parent: BaseASTNode, base: ExpressionNode): TableCallExpression {
        // Range finished by caller via finishNodeSpanning(base.range.start).
        val result = TableCallExpression()
        result.parent = parent
        result.base = base
        base.parent = result

        // Mark at '{' (next significant token) so the table constructor arg range is correct.
        peek { markLocation() }
        result.arguments.add(finishNode(parseTableConstructorExpression(result)))

        return result
    }

    private fun parseStringCallExpression(parent: BaseASTNode, base: ExpressionNode): StringCallExpression {
        // Range finished by caller via finishNodeSpanning(base.range.start).
        val result = StringCallExpression()
        result.parent = parent
        result.base = base
        base.parent = result

        // String-call arguments are a single string / long-string primary only.
        // Using parseExp here would keep consuming binary ops (e.g. `printer 'ready' or
        // fallback` wrongly became StringCall(..., Binary(or, 'ready', fallback))).
        // Mark at the string token so the ConstantNode range is the literal itself.
        peek { markLocation() }
        result.arguments.add(
            finishNode(
                consume {
                    ConstantNode(ConstantNode.TYPE.STRING, lexerText())
                }
            )
        )

        return result
    }


    // [' exp ']'
    private fun parseIndexExpression(parent: BaseASTNode, base: ExpressionNode): IndexExpression {
        advance()
        // Range finished by caller via finishNodeSpanning(base.range.start).
        // Do not markLocation() on '[' — that would report the wrong start.
        val result = IndexExpression()
        result.parent = parent
        result.base = base
        base.parent = result
        result.index = if (errorRecovery && peekToken(LuaTokenTypes.RBRACK)) {
            result.bad = true
            missingExpression(result)
        } else {
            parseExpressionOrMissing(result)
        }

        recoverToken(LuaTokenTypes.RBRACK) { "']' expected near ${lexerText()}" }

        return result
    }

    //  ['.' | ':'] NAME
    private fun parseFieldSet(parent: BaseASTNode, base: ExpressionNode): MemberExpression {
        val result = MemberExpression()
        advance()
        // Range finished by caller via finishNodeSpanning(base.range.start).
        // Capture indexer text while the '.' / ':' token is current; do not markLocation().
        result.indexer = lexerText().toString()

        result.base = base
        base.parent = result

        result.identifier = kotlin.runCatching {
            if (hasLineBreakBeforeNextSignificantToken()) {
                error("<name> expected near ${lexerText()}")
            }
            parseName(result)
        }
            .onFailure {
                if (!errorRecovery) {
                    throw it
                }
            }
            .getOrElse {
                result.bad = true
                Identifier("")
            }
        result.parent = parent
        return result
    }

    //	unop ::= ‘-’ | not | ‘#’ | ‘~’
    private fun parseUnaryExpression(parent: BaseASTNode): UnaryExpression {
        advance()
        val result = UnaryExpression()
        result.parent = parent
        result.operator = findExpressionOperator(lexerText()).requireNotNull()
        result.arg = if (errorRecovery && isExpressionTerminator(peek())) {
            missingExpression(result)
        } else {
            parseSubExp(result, 11)
        }
        return result
    }


    // explist ::= exp {‘,’ exp}
    //
    // Recovery policy lock (TASK-546 + TASK-553):
    //
    // recoverFirstStatementLineBreak is a *call-site* policy for the first RHS only:
    // - Assignment (true): incomplete `a =\nprint(a)` / `a, b =\nprint(a)` recover the
    //   first RHS as ExpressionNodeSupport and leave call-shaped / keyword statement
    //   starts as siblings. Bare multi-line NAME RHS (`a, b =\n x,\n y`) still parses
    //   as an expression because shouldRecoverStatementStartAsMissingExpression only
    //   treats call-shaped NAME as a statement start.
    // - Local (false): multi-line local initializers keep first RHS as expressions so
    //   well-formed `local a, b =\n x,\n y` matches strict under recovery. Incomplete
    //   first RHS after `local` still recovers for keyword/control starts (local/return/…)
    //   via isExpressionStart failure → missingExpression, but call-shaped NAME may be
    //   absorbed as the initializer (intentional asymmetry vs assignment).
    // - Return (false): same first-term policy as local (multi-line return values).
    //
    // Later terms (after comma) always pass recoverStatementStartAfterLineBreak=false
    // into parseExpressionOrMissing. After a comma, only *keyword/control* statement
    // starts after a line break force a missing later RHS (LOCAL/RETURN/IF/…). Bare
    // NAME / call-shaped NAME after comma+newline are valid expression starts and are
    // parsed as the next RHS — this is what keeps multi-RHS well-formed and prevents
    // first-RHS assignment recovery from poisoning later terms.
    //
    // WrapperLuaLexer.hasLineBreakBeforeNextSignificantToken looks before a pushbacked
    // next token so peeks no longer desync linebreak detection past a NAME RHS.
    private fun parseExpList(
        parent: BaseASTNode,
        recoverFirstStatementLineBreak: Boolean = RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_DEFAULT
    ): List<ExpressionNode> {
        val result = mutableListOf<ExpressionNode>()

        result.add(
            parseExpressionOrMissing(
                parent,
                recoverStatementStartAfterLineBreak = recoverFirstStatementLineBreak
            )
        )

        while (consumeToken(LuaTokenTypes.COMMA)) {
            val hasLineBreakBeforeNextExpression = hasLineBreakBeforeNextSignificantToken()
            val nextExpressionToken = peek()
            if (errorRecovery &&
                hasLineBreakBeforeNextExpression &&
                isKeywordStatementStart(nextExpressionToken)
            ) {
                // True gap after comma: next line starts a new statement (not a NAME RHS).
                result.add(missingExpression(parent))
                break
            }
            // Later terms: never use first-RHS statement-start recovery (TASK-546/553).
            result.add(parseExpressionOrMissing(parent, recoverStatementStartAfterLineBreak = false))
        }

        return result
    }

    // local namelist [‘=’ explist] (lua 5.3)
    // local attnamelist [‘=’ explist] (lua 5.4)
    //
    // TASK-553 recoverFirst policy (local): first initializer keeps
    // recoverFirstStatementLineBreak=false so multi-line multi-RHS locals match
    // strict shapes. Do not flip this to true — that reopens REVIEW16-style local
    // attribute / multi-line initializer absorption footguns relative to assignment.
    //
    // TASK-580 / TASK-183: when `=` is present but the first RHS is not an
    // expression start (e.g. `local value =\nreturn value`), emit a structured
    // recovery diagnostic anchored immediately after `=` rather than silently
    // inserting ExpressionNodeSupport. Following statement tokens stay unconsumed.
    private fun parseLocalVarList(parent: BaseASTNode): LocalStatement {
        // Caller already markLocation()'d at `local` (or `$name` local form).
        val localStatement = LocalStatement()
        localStatement.parent = parent

        localStatement.init.addAll(
            if (luaVersion === LuaVersion.LUA_5_4)
                parseAttrNameList(localStatement)
            else parseNameList(localStatement, luaVersion === LuaVersion.ANDROLUA_5_3)
        )
        if (errorRecovery && localStatement.init.any { it.bad }) {
            localStatement.bad = true
        }
        localStatement.init.forEach {
            it.isLocal = true
        }

        // '='
        if (!consumeToken(LuaTokenTypes.ASSIGN)) {
            return localStatement
        }

        // Capture the after-`=` range while ASSIGN is still the current token.
        // peeks in parseExpList move currentToken to the following significant
        // token without restoring it, so currentRecoveryRange() would mis-anchor
        // onto `return` / the next statement and fail the precise golden
        // Range(Position(1, 14), Position(1, 15)) for `local value =\nreturn value`.
        val missingInitializerRange = if (errorRecovery) {
            recoveryRangeAfterCurrentToken()
        } else {
            null
        }
        val firstRhsToken = peek()
        val missingFirstRhs = !isExpressionStart(firstRhsToken)
        if (errorRecovery && missingFirstRhs && missingInitializerRange != null) {
            // Structured diagnostic only — no stdout. parseExpressionOrMissing will
            // still insert ExpressionNodeSupport without consuming the next statement.
            warning("<expression> expected near =", missingInitializerRange)
            localStatement.bad = true
        }

        localStatement.variables.addAll(
            parseExpList(
                localStatement,
                recoverFirstStatementLineBreak = RECOVER_FIRST_RHS_STATEMENT_LINE_BREAK_LOCAL
            )
        )

        return localStatement
    }

}
