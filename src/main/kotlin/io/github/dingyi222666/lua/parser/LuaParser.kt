package io.github.dingyi222666.lua.parser

import io.github.dingyi222666.lua.parser.ast.node.*
import io.github.dingyi222666.lua.lexer.LuaLexer
import io.github.dingyi222666.lua.lexer.LuaTokenTypes
import io.github.dingyi222666.lua.parser.util.equalsMore
import io.github.dingyi222666.lua.parser.util.require
import java.io.InputStream
import java.io.Reader
import java.io.StringReader
import kotlin.properties.Delegates

/**
 * @author: dingyi
 * @date: 2023/2/2
 * @description:
 **/
class LuaParser {

    private var lexer by Delegates.notNull<LuaLexer>()

    private var currentToken = LuaTokenTypes.WHITE_SPACE
    private var lastToken = LuaTokenTypes.WHITE_SPACE
    private var cacheText: String? = null

    var ignoreWarningMessage = true

    fun parse(source: InputStream): ChunkNode {
        return parse(source.bufferedReader())
    }

    fun parse(reader: Reader): ChunkNode {
        val lexer = LuaLexer(reader)
        return parse(lexer)
    }

    fun parse(source: String): ChunkNode {
        return parse(StringReader(source))
    }

    private fun parse(lexer: LuaLexer): ChunkNode {
        this.lexer = lexer
        return parseChunk()
    }

    private inline fun <T> consume(crossinline func: (LuaTokenTypes) -> T): T {
        advance()
        val needConsume = func.invoke(currentToken)
        if (needConsume is Boolean && !needConsume) {
            lexer.yypushback(lexer.yylength())
        }
        return needConsume
    }

    private inline fun peek(): LuaTokenTypes {
        return peek { it }
    }

    private inline fun <T> peek(crossinline func: (LuaTokenTypes) -> T): T {
        advance()
        val result = func.invoke(currentToken)
        lexer.yypushback(lexer.yylength())
        cacheText = null
        return result
    }

    private inline fun peekToken(tokenTypes: LuaTokenTypes): Boolean {
        return peek { it == tokenTypes }
    }


    private fun advance(): LuaTokenTypes {
        var advanceToken: LuaTokenTypes
        while (true) {
            advanceToken = lexer.advance() ?: LuaTokenTypes.EOF
            cacheText = null
            when (advanceToken) {
                LuaTokenTypes.WHITE_SPACE, LuaTokenTypes.NEW_LINE -> continue
                else -> break
            }
        }
        lastToken = currentToken
        currentToken = advanceToken
        return currentToken
    }

    private fun lexerText(nextToken: Boolean = false): String {
        if (nextToken) {
            advance()
        }
        return cacheText ?: lexer.yytext().apply {
            cacheText = this
        }
    }

    private fun consumeToken(target: LuaTokenTypes): Boolean {
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

    private inline fun expectToken(array: Array<LuaTokenTypes>, crossinline messageBuilder: () -> String): Boolean {
        advance()
        if (currentToken !in array) {
            error(messageBuilder())
        }
        return true
    }

    private fun error(message: String): Nothing = kotlin.error("(${lexer.yyline()},${lexer.yycolumn()}): " + message)

    private fun warning(message: String) {
        if (ignoreWarningMessage) {
            System.err.println("(${lexer.yyline()},${lexer.yycolumn()}): " + message + ". This message is ignored now.")
        } else error(message)
    }


    // chunk ::= block
    private fun parseChunk(): ChunkNode {
        val chunkNode = ChunkNode()
        chunkNode.body = parseBlockNode()
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
    //		 local attnamelist [‘=’ explist]
    private fun parseBlockNode(parent: BaseASTNode? = null): BlockNode {
        val blockNode = BlockNode()
        while (!consumeToken(LuaTokenTypes.EOF)) {
            val stat = when {
                consumeToken(LuaTokenTypes.LOCAL) -> {
                    if (consumeToken(LuaTokenTypes.FUNCTION)) parseLocalFunctionDeclaration(blockNode)
                    else parseLocalVarList(blockNode)
                }

                consumeToken(LuaTokenTypes.WHILE) -> parseWhileStatement(blockNode)
                consumeToken(LuaTokenTypes.REPEAT) -> parseRepeatStatement(blockNode)
                consumeToken(LuaTokenTypes.BREAK) -> {
                    //TODO: Check is in loop
                    BreakStatement()
                }

                consumeToken(LuaTokenTypes.FOR) -> parseForStatement(blockNode)
                consumeToken(LuaTokenTypes.FUNCTION) -> parseGlobalFunctionDeclaration(blockNode)
                consumeToken(LuaTokenTypes.CONTINUE) -> {
                    ContinueStatement()
                }

                consumeToken(LuaTokenTypes.GOTO) -> parseGotoStatement(blockNode)
                consumeToken(LuaTokenTypes.DOUBLE_COLON) -> parseLabelStatement(blockNode)

                consumeToken(LuaTokenTypes.SEMI) -> continue
                consumeToken(LuaTokenTypes.DO) -> parseDoStatement(blockNode)
                peekToken(LuaTokenTypes.NAME) -> parseExpStatement(blockNode)
                // function call, varlist = explist
                else -> break // parseExpStatement(blockNode)
            }
            stat.parent = blockNode
            blockNode.addStatement(stat)

            // ;
            consumeToken(LuaTokenTypes.SEMI)
        }


        if (parent != null) {
            blockNode.parent = parent
        }

        return blockNode
    }


    //  for Name ‘=’ exp ‘,’ exp [‘,’ exp] do block end |
    //             for namelist in explist do block end |
    private fun parseForStatement(parent: BaseASTNode): StatementNode {

        //1. parse first name

        val name = parseName(parent)

        //2. check `=`

        return if (peek() == LuaTokenTypes.ASSIGN) {
            parseForNumericStatement(name, parent)
        } else parseForGenericStatement(name,parent)
    }

    //             for namelist in explist do block end |
    private fun parseForGenericStatement(variable: Identifier, parent: BaseASTNode): ForGenericStatement {
        val result = ForGenericStatement()
        result.parent = parent
        result.variables.add(variable)

        val findComma = consume { it == LuaTokenTypes.COMMA }

        if (findComma) {
            result.variables.addAll(parseNameList(result))
        }

        expectToken(LuaTokenTypes.IN) { "<in> expected near '${lexerText()}'" }

        result.iterators.addAll(parseExpList(result))

        result.body = parseForBody(result)

        return result
    }

    //  for Name ‘=’ exp ‘,’ exp [‘,’ exp] do block end |
    private fun parseForNumericStatement(variable: Identifier, parent: BaseASTNode): ForNumericStatement {
        val result = ForNumericStatement()
        result.variable = variable
        result.parent = parent

        expectToken(LuaTokenTypes.ASSIGN) { "'=' expected near '${lexerText()}'" }

        result.start = parseExp(result)

        expectToken(LuaTokenTypes.COMMA) { "',' expected near '${lexerText()}'" }

        result.end = parseExp(result)

        val findComma = consume { it == LuaTokenTypes.COMMA }

        if (findComma) {
            result.step = parseExp(result)
        }

        result.body = parseForBody(result)

        return result
    }

    private fun parseForBody(parent: BaseASTNode): BlockNode {
        val findDoToken = consumeToken(LuaTokenTypes.DO)
        if (!findDoToken) {
            warning("The <do> expected near ${lexerText()}")
        }
        return parseBlockNode(parent)
    }

    //     goto Name |
    private fun parseGotoStatement(parent: BaseASTNode): GotoStatement {
        val result = GotoStatement()
        result.parent = parent
        result.identifier = parseName(result)

        return result
    }


    //      label ::= ‘::’ Name ‘::’
    private fun parseLabelStatement(parent: BaseASTNode): LabelStatement {
        val result = LabelStatement()
        result.parent = parent
        result.identifier = parseName(result)

        expectToken(LuaTokenTypes.DOUBLE_COLON) { "'::' expected near '${lexerText()}'" }
        return result
    }

    //      function funcname funcbody |
    private fun parseGlobalFunctionDeclaration(parent: BaseASTNode): FunctionDeclaration {
        val result = FunctionDeclaration()
        result.parent = parent
        var nameExp: ExpressionNode = parseName(parent)
        var parentNode = parent

        //   funcname ::= Name {‘.’ Name} [‘:’ Name]
        while (true) {
            nameExp = when (peek()) {
                // '.' NAME
                //  ':' NAME
                LuaTokenTypes.DOT, LuaTokenTypes.COLON -> {
                    parseFieldSet(parentNode, nameExp)
                }

                else -> break
            }
            parentNode = nameExp
        }

        result.identifier = nameExp

        return parseFunctionBody(result, parent, lexer.yyline())
    }

    //		 repeat block until exp |
    private fun parseRepeatStatement(parent: BaseASTNode): RepeatStatement {
        val result = RepeatStatement()
        result.parent = parent

        result.body = parseBlockNode(result)

        expectToken(LuaTokenTypes.UNTIL) { "'until' expected near '${lexerText()}'" }

        result.condition = parseExp(result)

        return result
    }

    //		 while exp do block end |
    private fun parseWhileStatement(parent: BaseASTNode): WhileStatement {
        val result = WhileStatement()
        val currentLine = lexer.yyline()
        result.parent = parent
        result.condition = parseExp(result)

        val findDoToken = consumeToken(LuaTokenTypes.DO)
        if (!findDoToken) {
            warning("The <do> expected near ${lexerText(true)}")
        }

        result.body = parseBlockNode(result)

        expectToken(LuaTokenTypes.END) { "'end' expected (to close 'do' at line $currentLine) near ${lexerText(true)}" }

        return result
    }

    //  stat -> func | assignment
    private fun parseExpStatement(parent: BaseASTNode): StatementNode {

        val suffix = parsePrefixExp(parent)

        val peekToken = peek()
        return if (equalsMore(peekToken, LuaTokenTypes.ASSIGN, LuaTokenTypes.COMMA)) {
            parseAssignmentStatement(parent, suffix)
        } else {
            // function call
            CallStatement().apply {
                this.parent = parent
                expression = suffix as CallExpression
            }
        }
    }

    //  varlist ‘=’ explist |
    private fun parseAssignmentStatement(parent: BaseASTNode, base: ExpressionNode): AssignmentStatement {
        val initList = mutableListOf<ExpressionNode>()
        val result = AssignmentStatement()
        result.parent = parent


        while (peek() == LuaTokenTypes.COMMA) {
            // ,
            advance()
            initList.add(parseExp(result))
        }

        expectToken(LuaTokenTypes.ASSIGN) { "'=' expected near '${lexerText()}'" }

        initList.add(base)

        result.init.addAll(initList)
        result.variables.addAll(parseExpList(result))
        return result
    }

    // do block end |
    private fun parseDoStatement(parent: BaseASTNode): DoStatement {
        val result = DoStatement()
        val currentLine = lexer.yyline()

        result.body = parseBlockNode(result)
        result.parent = parent

        expectToken(LuaTokenTypes.END) { "'end' expected (to close 'do' at line $currentLine) near ${lexerText()}" }

        return result
    }

    //      funcbody ::= ‘(’ [parlist] ‘)’ block end
    private fun parseFunctionBody(
        node: FunctionDeclaration,
        parent: BaseASTNode,
        currentLine: Int
    ): FunctionDeclaration {

        expectToken(LuaTokenTypes.LPAREN) { "( expected near ${lexerText()}" }

        val findRight = peek { it == LuaTokenTypes.RPAREN }

        // empty arg
        if (!findRight) {
            node.params.addAll(parseNameList(parent))
        }

        expectToken(LuaTokenTypes.RPAREN) { ") expected near ${lexerText()}" }

        node.body = parseBlockNode(parent)

        expectToken(LuaTokenTypes.END) { "'end' expected (to close 'function' at line $currentLine) near ${lexerText()}" }

        return node
    }

    //		 local function Name funcbody
    private fun parseLocalFunctionDeclaration(parent: BaseASTNode): FunctionDeclaration {
        val result = FunctionDeclaration()
        val currentLine = lexer.yyline()
        result.parent = parent
        result.isLocal = true

        val name = parseName(parent)

        result.identifier = name


        return parseFunctionBody(result, parent, currentLine)
    }

    private fun parseName(parent: BaseASTNode): Identifier {
        expectToken(LuaTokenTypes.NAME) { "<name> expected near ${lexerText()}" }

        return Identifier(lexerText()).apply {
            this.parent = parent
        }

    }

    // namelist ::= Name {‘,’ Name}
    private fun parseNameList(parent: BaseASTNode): List<Identifier> {
        val result = mutableListOf<Identifier>()

        result.add(parseName(parent))

        val hasComma = consumeToken(LuaTokenTypes.COMMA)
        if (!hasComma) {
            return result
        }
        var nameNode = parseName(parent)
        while (true) {
            result.add(nameNode)
            if (!consumeToken(LuaTokenTypes.COMMA)) break
            nameNode = parseName(parent)
        }

        return result

    }


    //      exp ::= (unop exp | primary | prefixexp ) { binop exp }
    //
    //     primary ::= nil | false | true | Number | String | '...'
    //          | functiondef | tableconstructor
    //
    //
    private fun parseExp(parent: BaseASTNode): ExpressionNode {
        return parseSubExp(parent, 0).require()
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


    private fun findExpressionOperator(text: String): ExpressionOperator? {
        return ExpressionOperator.values().find {
            it.value == text
        }
    }


    //          functiondef ::= function funcbody
    private fun parseFunctionExp(parent: BaseASTNode): FunctionDeclaration {
        val result = FunctionDeclaration()
        return parseFunctionBody(result, parent, lexer.yyline())
    }


    //
    private fun parseSubExp(parent: BaseASTNode, minPrecedence: Int): ExpressionNode {

        var precedence: Int

        val currentToken = peek()
        var node: ExpressionNode
        node = when {

            equalsMore(
                currentToken, LuaTokenTypes.MINUS, LuaTokenTypes.GETN, LuaTokenTypes.BIT_TILDE, LuaTokenTypes.NOT
            ) -> {
                // unary
                parseUnaryExpression(
                    parent
                )
            }

            // primary
            currentToken == LuaTokenTypes.ELLIPSIS -> consume { VarargLiteral() }
            currentToken == LuaTokenTypes.NIL -> consume { ConstantNode.NIL.copy() }

            equalsMore(
                currentToken, LuaTokenTypes.FALSE, LuaTokenTypes.TRUE
            ) -> consume { ConstantNode(ConstantNode.TYPE.BOOLEAN, lexerText()) }

            equalsMore(
                currentToken, LuaTokenTypes.LONG_STRING, LuaTokenTypes.STRING
            ) -> consume { ConstantNode(ConstantNode.TYPE.STRING, lexerText()) }

            currentToken == LuaTokenTypes.NUMBER -> consume {
                val lexerText = lexerText()
                if (lexerText.contains(".")) {
                    ConstantNode(ConstantNode.TYPE.FLOAT, lexerText)
                } else {
                    ConstantNode(ConstantNode.TYPE.INTERGER, lexerText)
                }
            }

            currentToken == LuaTokenTypes.FUNCTION -> consume {
                parseFunctionExp(parent)
            }

            binaryPrecedence(currentToken).also {
                precedence = it
            } > 0 -> consume {

                precedence = binaryPrecedence(currentToken)
                val result = BinaryExpression().apply {
                    this.parent = parent
                    left = parent as ExpressionNode
                    operator = findExpressionOperator(lexerText())
                }

                precedence = binaryPrecedence(currentToken)
                result.right = parseSubExp(result, precedence)
                result
            }

            //TODO: table ....

            else -> parsePrefixExp(parent)
        }


        node = node.require()

        precedence = binaryPrecedence(peek())

        if (precedence <= 0) {
            node.parent = parent
            return node
        }

        while (precedence > minPrecedence) {

            advance()

            node = BinaryExpression().apply {
                this.parent = parent
                left = node
                operator = findExpressionOperator(lexerText())
            }

            node.right = parseSubExp(node, precedence)

            precedence = binaryPrecedence(peek())

        }

        if (node == parent) {
            error("unexpected symbol ${lexerText()} near ${lastToken.name.lowercase()}")
        }

        return node.require()
    }


    //  primaryexp ::= NAME | '(' expr ')' *
    private fun parsePrimaryExp(parent: BaseASTNode): ExpressionNode {
        return when (peek()) {
            LuaTokenTypes.NAME -> parseName(parent)
            LuaTokenTypes.LPAREN -> {
                advance()
                val exp = parseExp(parent)
                expectToken(LuaTokenTypes.RPAREN) { "')' expected near ${lexerText(true)}" }
                exp
            }

            else -> error("<expression> expected near ${lexerText(true)}")
        }
    }

    //  prefixExp ::= primaryexp { '.' fieldset | '[' exp ']' | ':' NAME funcargs | funcargs }

    private fun parsePrefixExp(parent: BaseASTNode): ExpressionNode {
        var result = parsePrimaryExp(parent)

        var parentNode = parent

        while (true) {
            result = when (peek()) {
                // '.' fieldset*
                LuaTokenTypes.DOT -> {
                    parseFieldSet(parentNode, result)
                }
                // [' exp ']'
                LuaTokenTypes.LBRACK -> {
                    parseIndexExpression(parentNode, result)
                }
                // funcargs
                LuaTokenTypes.LPAREN -> {
                    parseCallExpression(parent, result)
                }

                //  ':' NAME funcargs
                LuaTokenTypes.COLON -> {
                    val fieldSet = parseFieldSet(parent, result)
                    parseCallExpression(parent, fieldSet)
                }

                else -> break
            }
            parentNode = result
        }

        return result

    }

    // funcargs -> '(' [ explist ] ')'
    private fun parseCallExpression(parent: BaseASTNode, base: ExpressionNode): CallExpression {
        val result = CallExpression()
        result.parent = parent
        result.base = base

        // consume (
        advance()

        val findRight = consume { it == LuaTokenTypes.RPAREN }

        if (findRight) {
            // empty arg
            return result
        }

        result.arguments.addAll(parseExpList(parent))

        expectToken(LuaTokenTypes.RPAREN) { "')' expected near ${lexerText()}" }

        return result
    }

    // [' exp ']'
    private fun parseIndexExpression(parent: BaseASTNode, base: ExpressionNode): IndexExpression {
        advance()

        val result = IndexExpression()
        result.parent = parent
        result.base = base
        result.index = parseExp(parent)

        expectToken(LuaTokenTypes.RBRACK) { "']' expected near ${lexerText()}" }

        return result
    }

    //  ['.' | ':'] NAME
    private fun parseFieldSet(parent: BaseASTNode, base: ExpressionNode): MemberExpression {
        val result = MemberExpression()

        result.indexer = consume { lexerText() }

        result.base = base

        result.identifier = parseName(result)
        result.parent = parent
        return result
    }

    //	unop ::= ‘-’ | not | ‘#’ | ‘~’
    private fun parseUnaryExpression(parent: BaseASTNode): UnaryExpression {
        advance()
        val result = UnaryExpression()
        result.parent = parent
        result.operator = findExpressionOperator(lexerText()).require()
        result.arg = parseSubExp(result, 11);
        return result
    }


    // explist ::= exp {‘,’ exp}
    private fun parseExpList(parent: BaseASTNode): List<ExpressionNode> {
        val result = mutableListOf<ExpressionNode>()

        result.add(parseExp(parent))

        val hasComma = consumeToken(LuaTokenTypes.COMMA)
        if (!hasComma) {
            return result
        }

        var expNode = parseExp(parent)
        while (true) {
            result.add(expNode)
            if (!consumeToken(LuaTokenTypes.COMMA)) break
            expNode = parseExp(parent)
        }

        return result
    }

    // local namelist [‘=’ explist]
    private fun parseLocalVarList(parent: BaseASTNode): LocalStatement {
        val localStatement = LocalStatement()

        localStatement.parent = parent

        localStatement.init.addAll(parseNameList(localStatement))
        localStatement.init.forEach {
            it.isLocal = true
        }

        // '='
        if (!consumeToken(LuaTokenTypes.ASSIGN)) {
            return localStatement
        }

        // advance to exp list
        localStatement.variables.addAll(parseExpList(localStatement))

        return localStatement
    }

}

