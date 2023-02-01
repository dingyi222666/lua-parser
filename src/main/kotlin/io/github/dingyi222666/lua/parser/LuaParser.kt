package io.github.dingyi222666.lua.parser

import io.github.dingyi222666.lua.parser.ast.node.*
import io.github.dingyi222666.lua.lexer.LuaLexer
import io.github.dingyi222666.lua.lexer.LuaTokenTypes
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

    private fun advance(): LuaTokenTypes {
        var advanceToken: LuaTokenTypes
        while (true) {
            advanceToken = lexer.advance() ?: LuaTokenTypes.EOF
            when (advanceToken) {
                LuaTokenTypes.WHITE_SPACE, LuaTokenTypes.NEW_LINE -> continue
                else -> break
            }
        }
        lastToken = currentToken
        currentToken = advanceToken
        return currentToken
    }

    private fun lexerText() = lexer.yytext()

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
                    if (consumeToken(LuaTokenTypes.FUNCTION))
                        parseLocalFunctionDeclaration(blockNode)
                    else parseLocalVarList(blockNode)
                }

                else -> break
            }
            blockNode.addStatement(stat)

            // ;
            consumeToken(LuaTokenTypes.SEMI)
        }



        if (parent != null) {
            blockNode.parent = parent
        }

        return blockNode
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


    // exp ::=  nil | false | true | Numeral | LiteralString | ‘...’ | functiondef |
    //		 prefixexp | tableconstructor | exp binop exp | unop exp

    //  binop ::=  ‘+’ | ‘-’ | ‘*’ | ‘/’ | ‘//’ | ‘^’ | ‘%’ |
    //		 ‘&’ | ‘~’ | ‘|’ | ‘>>’ | ‘<<’ | ‘..’ |
    //		 ‘<’ | ‘<=’ | ‘>’ | ‘>=’ | ‘==’ | ‘~=’ |
    //		 and | or
    //
    //	unop ::= ‘-’ | not | ‘#’ | ‘~’
    private fun parseExp(parent: BaseASTNode): ExpressionNode {
        val node = when (advance()) {
            LuaTokenTypes.NIL -> ConstantsNode.NIL.copy()
            LuaTokenTypes.FALSE, LuaTokenTypes.TRUE -> ConstantsNode(ConstantsNode.TYPE.BOOLEAN, lexerText())
            LuaTokenTypes.LONG_STRING, LuaTokenTypes.STRING -> ConstantsNode(ConstantsNode.TYPE.STRING, lexerText())
            LuaTokenTypes.NUMBER -> {
                val text = lexerText()
                if (text.contains(".")) {
                    ConstantsNode(ConstantsNode.TYPE.FLOAT, text)
                } else {
                    ConstantsNode(ConstantsNode.TYPE.INTERGER, text)
                }
            }

            LuaTokenTypes.MINUS, LuaTokenTypes.GETN, LuaTokenTypes.BIT_TILDE, LuaTokenTypes.NOT -> parseUnaryExpression(
                parent
            )

            LuaTokenTypes.ELLIPSIS -> VarargLiteral()
            else -> error("unexpected symbol ${lexerText()} near ${lastToken.name.lowercase()}")
        }

        node.parent = parent

        return node
    }

    private fun parseUnaryExpression(parent: BaseASTNode): UnaryExpression {
        val result = UnaryExpression()
        result.parent = parent
        result.operator = ExpressionOperator.values().find { it.value == lexerText() }.require()
        result.arg = parseExp(result)
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

        // '='
        if (!consumeToken(LuaTokenTypes.ASSIGN)) {
            return localStatement
        }

        // advance to exp list
        localStatement.variables.addAll(parseExpList(localStatement))

        return localStatement
    }

}

