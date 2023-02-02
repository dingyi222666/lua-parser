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

    private fun consume(func: (LuaTokenTypes) -> Boolean): Boolean {
        advance()
        val needConsume = func.invoke(currentToken)
        if (!needConsume) {
            lexer.yypushback(lexer.yylength())
        }
        return needConsume
    }

    private inline fun peek(): LuaTokenTypes {
        return peek { it }
    }

    private fun <T> peek(func: (LuaTokenTypes) -> T): T {
        advance()
        val result = func.invoke(currentToken)
        lexer.yypushback(lexer.yylength())
        cacheText = null
        return result
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

    private fun lexerText() = cacheText ?: lexer.yytext().apply {
        cacheText = this
    }

    private fun consumeToken(target: LuaTokenTypes): Boolean {
        return consume { token ->
            target == token
        }
    }

    private fun expectToken(target: LuaTokenTypes, messageBuilder: () -> String): Boolean {
        advance()
        if (currentToken != target) {
            error("(${lexer.yyline()},${lexer.yycolumn()}): " + messageBuilder())
        }
        return true
    }

    private fun error(message: String): Nothing = kotlin.error("(${lexer.yyline()},${lexer.yycolumn()}): " + message)

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

                consumeToken(LuaTokenTypes.BREAK) -> {
                    //TODO: Check is in loop
                    BreakStatement()
                }

                consumeToken(LuaTokenTypes.CONTINUE) -> {
                    ContinueStatement()
                }

                consumeToken(LuaTokenTypes.DO) -> parseDoStatement(blockNode)
                else -> break
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

    // do block end |
    private fun parseDoStatement(parent: BaseASTNode): DoStatement {
        val result = DoStatement()
        val currentLine = lexer.yyline()

        result.body = parseBlockNode(result)
        result.parent = parent

        expectToken(LuaTokenTypes.END) { "'end' expected (to close 'do' at line $currentLine) near ${lexerText()}" }

        return result
    }

    //		 local function Name funcbody
    private fun parseLocalFunctionDeclaration(parent: BaseASTNode): FunctionDeclaration {
        val result = FunctionDeclaration()
        val currentLine = lexer.yyline()
        result.parent = parent
        result.isLocal = true

        val name = parseName(parent)

        result.identifier = name

        expectToken(LuaTokenTypes.LPAREN) { "( expected near ${lexerText()}" }

        result.params.addAll(parseNameList(parent))

        expectToken(LuaTokenTypes.RPAREN) { ") expected near ${lexerText()}" }

        result.body = parseBlockNode(parent)

        expectToken(LuaTokenTypes.END) { "'end' expected (to close 'function' at line $currentLine) near ${lexerText()}" }

        return result
    }

    private fun parseName(parent: BaseASTNode): Identifier {
        val expectedName = { "<name> expected near ${lexerText()}" }

        expectToken(LuaTokenTypes.NAME, expectedName)

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


    //     exp ::= (unop exp | primary | prefixexp ) { binop exp }
    //
    //     primary ::= nil | false | true | Number | String | '...'
    //          | functiondef | tableconstructor
    //
    //     prefixexp ::= (Name | '(' exp ')' ) { '[' exp ']'
    //          | '.' Name | ':' Name args | args }
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


    private fun parseSubExp(parent: BaseASTNode, minPrecedence: Int): ExpressionNode {

        var precedence: Int

        currentToken = advance()
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
            currentToken == LuaTokenTypes.ELLIPSIS -> VarargLiteral()
            currentToken == LuaTokenTypes.NIL -> ConstantsNode.NIL.copy()

            equalsMore(
                currentToken, LuaTokenTypes.FALSE, LuaTokenTypes.TRUE
            ) -> ConstantsNode(ConstantsNode.TYPE.BOOLEAN, lexerText())

            equalsMore(
                currentToken, LuaTokenTypes.LONG_STRING, LuaTokenTypes.STRING
            ) -> ConstantsNode(ConstantsNode.TYPE.STRING, lexerText())

            currentToken == LuaTokenTypes.NUMBER -> {
                val lexerText = lexerText()
                if (lexerText.contains(".")) {
                    ConstantsNode(ConstantsNode.TYPE.FLOAT, lexerText)
                } else {
                    ConstantsNode(ConstantsNode.TYPE.INTERGER, lexerText)
                }
            }

            binaryPrecedence(currentToken).also {
                precedence = it
            } > 0 -> {
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

            //TODO: table / function call ....

            else -> error("unexpected symbol ${lexerText()} near ${lastToken.name.lowercase()}")
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


    //	unop ::= ‘-’ | not | ‘#’ | ‘~’
    private fun parseUnaryExpression(parent: BaseASTNode): UnaryExpression {
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

