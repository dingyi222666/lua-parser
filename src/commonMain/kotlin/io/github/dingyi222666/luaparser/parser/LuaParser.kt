package io.github.dingyi222666.luaparser.parser

import io.github.dingyi222666.luaparser.lexer.LuaLexer
import io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
import io.github.dingyi222666.luaparser.lexer.WrapperLuaLexer
import io.github.dingyi222666.luaparser.parser.ast.node.*
import io.github.dingyi222666.luaparser.semantic.symbol.Scope
import io.github.dingyi222666.luaparser.util.equalsMore
import io.github.dingyi222666.luaparser.util.requireNotNull
import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.properties.Delegates

/**
 * @author: dingyi
 * @date: 2023/2/2
 * @description:
 **/
class LuaParser {

    private var lexer by Delegates.notNull<WrapperLuaLexer>()

    private var currentToken = io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.WHITE_SPACE
    private var lastToken = io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.WHITE_SPACE
    private var cacheText: String? = null
    private val locations = ArrayDeque<Position>()
    private val scopes = ArrayDeque<Scope>()

    var ignoreWarningMessage = true

    fun parse(source: String): ChunkNode {
        return parse(io.github.dingyi222666.luaparser.lexer.LuaLexer(source))
    }

    private fun parse(lexer: io.github.dingyi222666.luaparser.lexer.LuaLexer): ChunkNode {
        reset()
        this.lexer = WrapperLuaLexer(lexer)
        val chunk = parseChunk()
        this.lexer.close()
        return chunk
    }


    fun reset() {
        currentToken = io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.WHITE_SPACE
        lastToken = io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.WHITE_SPACE
        cacheText = null
        locations.clear()
        scopes.clear()
    }

    private inline fun <T> consume(crossinline func: (io.github.dingyi222666.luaparser.lexer.LuaTokenTypes) -> T): T {
        advance()
        val needConsume = func.invoke(currentToken)
        if (needConsume is Boolean && !needConsume) {
            lexer.pushback(lexer.length())
        }
        return needConsume
    }

    private fun peek(): io.github.dingyi222666.luaparser.lexer.LuaTokenTypes {
        return peek { it }
    }

    private inline fun <T> peek(crossinline func: (io.github.dingyi222666.luaparser.lexer.LuaTokenTypes) -> T): T {
        advance()
        val result = func.invoke(currentToken)
        lexer.pushback(lexer.length())
        cacheText = null
        return result
    }

    private fun peekToken(tokenTypes: io.github.dingyi222666.luaparser.lexer.LuaTokenTypes): Boolean {
        return peek { it == tokenTypes }
    }

    private fun ignoreToken(advanceToken: io.github.dingyi222666.luaparser.lexer.LuaTokenTypes): Boolean {
        return when (advanceToken) {
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.WHITE_SPACE, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.NEW_LINE -> true
            //TODO: collect comment to scope
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.BLOCK_COMMENT, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DOC_COMMENT,
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.SHORT_COMMENT -> true

            else -> false
        }
    }

    private fun advance(): io.github.dingyi222666.luaparser.lexer.LuaTokenTypes {
        var advanceToken: io.github.dingyi222666.luaparser.lexer.LuaTokenTypes
        while (true) {
            advanceToken = lexer.advance()
            cacheText = null
            if (ignoreToken(advanceToken)) {
                continue
            } else break

        }
        lastToken = currentToken
        currentToken = advanceToken
        return currentToken
    }

    private fun lexerText(nextToken: Boolean = false): String {
        if (nextToken) {
            advance()
        }
        return cacheText ?: lexer.text().apply {
            cacheText = this
        }
    }

    private fun peekN(size: Int): io.github.dingyi222666.luaparser.lexer.LuaTokenTypes {
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

    private fun consumeToken(target: io.github.dingyi222666.luaparser.lexer.LuaTokenTypes): Boolean {
        return consume { token ->
            target == token
        }
    }

    private inline fun expectToken(target: io.github.dingyi222666.luaparser.lexer.LuaTokenTypes, crossinline messageBuilder: () -> String): Boolean {
        advance()
        if (currentToken != target) {
            error(messageBuilder())
        }
        return true
    }

    private fun error(message: String): Nothing = kotlin.error("(${lexer.line()},${lexer.column()}): " + message)

    private fun warning(message: String) {
        if (ignoreWarningMessage) {
            println("(${lexer.line()},${lexer.column()}): " + message + ". This error is ignored now.")
        } else error(message)
    }

    private fun markLocation() {
        //Exception("6").printStackTrace()
        locations.addFirst(
            Position(
                line = lexer.line(),
                column = max(lexer.column(), 1)
            )
        )
    }

    private fun <T : BaseASTNode> finishNode(node: T): T {
        val end = Position(
            line = lexer.line(),
            column = max(lexer.column() + lexer.length(), 1)
        )
        val start = locations.removeFirst()
        node.range = Range(start, end)
        return node
    }


    // chunk ::= block
    private fun parseChunk(): ChunkNode {
        markLocation()
        var chunkNode = ChunkNode()
        chunkNode.body = parseBlockNode()
        chunkNode = finishNode(chunkNode)
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.EOF) {
            "unexpected ${lexerText()} near '<eof>"
        }
        println(locations)
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
        while (!peekToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.EOF)) {
            val stat = when {
                consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LOCAL) -> {
                    if (consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.FUNCTION)) parseLocalFunctionDeclaration(blockNode)
                    else parseLocalVarList(blockNode)
                }

                consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.WHILE) -> parseWhileStatement(blockNode)
                consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.REPEAT) -> parseRepeatStatement(blockNode)
                consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.BREAK) -> {
                    // check the syntax when traversing the tree
                    markLocation()
                    BreakStatement()
                }

                consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.FOR) -> parseForStatement(blockNode)
                consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.FUNCTION) -> parseGlobalFunctionDeclaration(blockNode)
                consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.CONTINUE) -> {
                    // check the syntax when traversing the tree
                    markLocation()
                    ContinueStatement()
                }

                peekToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.WHEN) -> parseWhenStatement(blockNode)
                peekToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.IF) -> parseIfStatement(blockNode)
                peekToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.SWITCH) -> parseSwitchStatement(blockNode)
                consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.GOTO) -> parseGotoStatement(blockNode)
                consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DOUBLE_COLON) -> parseLabelStatement(blockNode)

                consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.SEMI) -> continue
                consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DO) -> parseDoStatement(blockNode)
                // function call, varlist = explist, $(localvarlist)
                peekToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.NAME) -> {
                    val name = peek { lexerText() }
                    if (name.startsWith('$'))
                        parseLocalVarList(blockNode)
                    else
                        parseExpStatement(blockNode)
                }

                peekToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RETURN) -> parseReturnStatement(blockNode)
                else -> break
            }

            finishNode(stat)

            stat.parent = blockNode
            if (stat is ReturnStatement) {
                blockNode.returnStatement = stat
            } else {
                blockNode.addStatement(stat)
            }

            // ;
            consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.SEMI)

            if (stat is ReturnStatement) {
                break
            }
        }


        if (parent != null) {
            blockNode.parent = parent
        }

        return finishNode(blockNode)
    }

    //    switch exp do {case explist [then] block} [default block] end
    private fun parseSwitchStatement(parent: BaseASTNode): SwitchStatement {
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.SWITCH) { "<switch> expected near ${lexerText(true)}" }
        markLocation()
        val result = SwitchStatement()
        val currentLine = lexer.line()
        result.parent = parent

        result.condition = parseExp(result)

        val findDoToken = consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DO)
        if (!findDoToken) {
            warning("The <do> expected near ${lexerText()}")
        }

        if (peekToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.CASE)) {
            result.causes.addAll(parseSwitchCaseList(result))
        }

        if (peekToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DEFAULT)) {
            result.causes.add(finishNode(parseSwitchDefaultCaseStatement(result)))
        }

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.END) { "<end> expected (to close 'switch' at line $currentLine) near ${lexerText()}" }

        return result
    }

    // [default block]
    private fun parseSwitchDefaultCaseStatement(parent: BaseASTNode): DefaultCause {
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DEFAULT) { "<case> expected near ${lexerText(true)}" }
        markLocation()
        val result = DefaultCause()
        result.parent = parent
        result.body = parseBlockNode(result)
        return result
    }

    // {case explist [then] block}
    private fun parseSwitchCaseList(parent: BaseASTNode): List<CaseCause> {
        val result = mutableListOf<CaseCause>()
        while (peekToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.CASE)) {
            result.add(finishNode(parseSwitchCaseStatement(parent)))
        }
        return result
    }

    //  case explist [then] block
    private fun parseSwitchCaseStatement(parent: BaseASTNode): CaseCause {
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.CASE) { "<case> expected near ${lexerText(true)}" }
        markLocation()
        val result = CaseCause()

        result.parent = parent
        result.conditions.addAll(parseExpList(result))

        consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.THEN)

        result.body = parseBlockNode(result)

        return result
    }

    //   retstat ::= return [explist] [‘;’]
    private fun parseReturnStatement(parent: BaseASTNode): ReturnStatement {
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RETURN) { "<return> expected near ${lexerText(true)}" }
        markLocation()
        val result = ReturnStatement()
        result.parent = parent

        while (true) {
            when (peek()) {
                io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.EOF, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.END -> return result
                io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.SEMI -> continue
                else -> break
            }
        }

        result.arguments.addAll(parseExpList(result))

        consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.SEMI)

        return result
    }

    //      when exp (varlist ‘=’ explist| functioncall) | [else (varlist ‘=’ explist | functioncall)]
    private fun parseWhenStatement(parent: BaseASTNode): WhenStatement {
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.WHEN) { "<when> expected near '${lexerText()}'" }
        markLocation()
        val result = WhenStatement()
        result.parent = parent

        result.condition = parseExp(result)

        result.ifCause = parseExpStatement(parent)

        if (!consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ELSE)) {
            return result
        }

        result.elseCause = parseExpStatement(parent)

        return result
    }

    //		if exp then block {elseif exp then block} [else block] end |
    private fun parseIfStatement(parent: BaseASTNode): IfStatement {
        markLocation()
        val result = IfStatement()
        val currentLine = lexer.line()
        result.parent = parent

        result.causes.add(finishNode(parseIfCause(result)))

        while (true) {
            val cause = when (peek()) {
                io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ELSEIF -> parseElseIfCause(parent)
                io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ELSE -> parseElseClause(parent)
                else -> break
            }
            result.causes.add(finishNode(cause))
        }

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.END) { "<end> expected (to close 'do' at line $currentLine) near ${lexerText(true)}" }

        return result
    }

    //       else block
    private fun parseElseClause(parent: BaseASTNode): IfClause {
        val result = ElseClause()
        result.parent = parent

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ELSE) { "<else> expected near '${lexerText()}'" }
        markLocation()
        result.condition = ExpressionNode.EMPTY
        result.body = parseBlockNode(result)

        return result
    }

    //       elseif exp then block
    private fun parseElseIfCause(parent: BaseASTNode): IfClause {
        val result = ElseIfClause()
        result.parent = parent

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ELSEIF) { "<elseif> expected near '${lexerText()}'" }
        markLocation()
        result.condition = parseExp(result)

        val findThenToken = consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.THEN)
        if (!findThenToken) {
            warning("The <then> expected near ${lexerText()}")
        }

        result.body = parseBlockNode(result)

        return result
    }

    //       if exp then block
    private fun parseIfCause(parent: BaseASTNode): IfClause {
        val result = IfClause()
        result.parent = parent

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.IF) { "<if> expected near '${lexerText()}'" }
        markLocation()
        result.condition = parseExp(result)

        val findThenToken = consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.THEN)
        if (!findThenToken) {
            warning("The <then> expected near ${lexerText()}")
        }

        result.body = parseBlockNode(result)

        return result
    }

    //  for Name ‘=’ exp ‘,’ exp [‘,’ exp] do block end |
    //             for namelist in explist do block end |
    private fun parseForStatement(parent: BaseASTNode): StatementNode {
        markLocation()
        //1. parse first name
        val name = parseName(parent)

        //2. check `=`

        return if (peek() == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ASSIGN) {
            parseForNumericStatement(name, parent)
        } else parseForGenericStatement(name, parent)
    }

    //             for namelist in explist do block end |
    private fun parseForGenericStatement(variable: Identifier, parent: BaseASTNode): ForGenericStatement {
        val result = ForGenericStatement()
        val currentLine = lexer.line()
        result.parent = parent
        result.variables.add(variable)

        val findComma = consume { it == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COMMA }

        if (findComma) {
            result.variables.addAll(parseNameList(result))
        }

        val findInToken = consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.IN)
        if (!findInToken) {
            warning("The <in> expected near '${lexerText()}'")
        }

        // expectToken(LuaTokenTypes.IN) { "<in> expected near '${lexerText()}'" }

        result.iterators.addAll(parseExpList(result))

        result.body = parseForBody(result)

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.END) { "<end> expected (to close 'for' at line $currentLine) near '${lexerText(true)}'" }

        return result
    }

    //  for Name ‘=’ exp ‘,’ exp [‘,’ exp] do block end |
    private fun parseForNumericStatement(variable: Identifier, parent: BaseASTNode): ForNumericStatement {
        val result = ForNumericStatement()
        val currentLine = lexer.line()
        result.variable = variable
        result.parent = parent

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ASSIGN) { "'=' expected near '${lexerText()}'" }

        result.start = parseExp(result)

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COMMA) { "',' expected near '${lexerText()}'" }

        result.end = parseExp(result)

        val findComma = consume { it == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COMMA }

        if (findComma) {
            result.step = parseExp(result)
        }

        result.body = parseForBody(result)

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.END) { "<end> expected (to close 'for' at line $currentLine) near ${lexerText(true)}" }

        return result
    }

    private fun parseForBody(parent: BaseASTNode): BlockNode {
        val findDoToken = consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DO)
        if (!findDoToken) {
            warning("The <do> expected near ${lexerText()}")
        }
        return parseBlockNode(parent)
    }

    //     goto Name |
    private fun parseGotoStatement(parent: BaseASTNode): GotoStatement {
        markLocation()
        val result = GotoStatement()
        result.parent = parent
        result.identifier = parseName(result)

        return result
    }


    //      label ::= ‘::’ Name ‘::’
    private fun parseLabelStatement(parent: BaseASTNode): LabelStatement {
        markLocation()
        val result = LabelStatement()
        result.parent = parent
        result.identifier = parseName(result)

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DOUBLE_COLON) { "'::' expected near '${lexerText()}'" }
        return result
    }

    //      function funcname funcbody |
    private fun parseGlobalFunctionDeclaration(parent: BaseASTNode): FunctionDeclaration {
        val result = FunctionDeclaration()
        markLocation()
        result.parent = parent
        var nameExp: ExpressionNode = parseName(parent)
        var parentNode = parent

        //   funcname ::= Name {‘.’ Name} [‘:’ Name]
        while (true) {
            nameExp = when (peek()) {
                // '.' NAME
                //  ':' NAME
                io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DOT, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COLON -> {
                    parseFieldSet(parentNode, nameExp)
                }

                else -> break
            }
            parentNode = finishNode(nameExp)
        }

        result.identifier = nameExp

        return parseFunctionBody(result, parent, lexer.line())
    }

    //		 repeat block until exp |
    private fun parseRepeatStatement(parent: BaseASTNode): RepeatStatement {
        markLocation()
        val result = RepeatStatement()
        result.parent = parent

        result.body = parseBlockNode(result)

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.UNTIL) { "'until' expected near '${lexerText()}'" }

        result.condition = parseExp(result)

        return result
    }

    //		 while exp do block end |
    private fun parseWhileStatement(parent: BaseASTNode): WhileStatement {
        markLocation()
        val result = WhileStatement()
        val currentLine = lexer.line()
        result.parent = parent
        result.condition = parseExp(result)

        val findDoToken = consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DO)
        if (!findDoToken) {
            warning("The <do> expected near ${lexerText(true)}")
        }

        result.body = parseBlockNode(result)

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.END) { "<end> expected (to close 'do' at line $currentLine) near ${lexerText(true)}" }

        return result
    }

    //  stat -> func | assignment
    private fun parseExpStatement(parent: BaseASTNode): StatementNode {
        peek { markLocation() }
        val suffix = parsePrefixExp(parent)

        val peekToken = peek()

        return if (suffix is Identifier || equalsMore(peekToken, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ASSIGN, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COMMA)) {
            parseAssignmentStatement(parent, suffix)
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
                expression = suffix as CallExpression
            }
        }
    }

    //  varlist ‘=’ explist |
    private fun parseAssignmentStatement(parent: BaseASTNode, base: ExpressionNode): AssignmentStatement {
        val initList = mutableListOf<ExpressionNode>()
        val result = AssignmentStatement()
        result.parent = parent
        base.parent = result
        initList.add(base)

        while (peek() == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COMMA) {
            // ,
            advance()
            initList.add(parseExp(result))
        }

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ASSIGN) { "'=' expected near '${lexerText()}'" }

        result.init.addAll(initList)
        result.variables.addAll(parseExpList(result))
        return result
    }

    // do block end |
    private fun parseDoStatement(parent: BaseASTNode): DoStatement {
        markLocation()
        val result = DoStatement()
        val currentLine = lexer.line()

        result.body = parseBlockNode(result)
        result.parent = parent

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.END) { "<end> expected (to close 'do' at line $currentLine) near ${lexerText()}" }

        return result
    }

    //      funcbody ::= ‘(’ [parlist] ‘)’ block end
    private fun parseFunctionBody(
        node: FunctionDeclaration,
        parent: BaseASTNode,
        currentLine: Int
    ): FunctionDeclaration {

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LPAREN) { "( expected near '${lexerText()}'" }


        val findRight = peek { it == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RPAREN }

        // empty arg
        if (!findRight) {
            node.params.addAll(parseNameList(parent))
        }

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RPAREN) { ") expected near '${lexerText()}'" }

        node.body = parseBlockNode(parent)

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.END) { "<end> expected (to close 'function' at line $currentLine) near ${lexerText()}" }

        return node
    }

    //		 local function Name funcbody
    private fun parseLocalFunctionDeclaration(parent: BaseASTNode): FunctionDeclaration {
        markLocation()
        val result = FunctionDeclaration()
        val currentLine = lexer.line()
        result.parent = parent
        result.isLocal = true

        val name = parseName(parent)

        result.identifier = name

        return parseFunctionBody(result, parent, currentLine)
    }

    private fun parseName(parent: BaseASTNode, supportDollarSymbol: Boolean = false): Identifier {
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.NAME) { "<name> expected near ${lexerText()}" }
        markLocation()
        var name = lexerText()
        if (name.startsWith('$')) {
            if (!supportDollarSymbol) {
                error("'$' is not allowed in name ${lexerText()}")
            }
            name = name.substring(1)
        }
        val identifier = Identifier(name)
        identifier.parent = parent
        return finishNode(identifier)
    }

    // namelist ::= Name {‘,’ Name}
    private fun parseNameList(parent: BaseASTNode, supportDollarSymbol: Boolean = false): List<Identifier> {
        val result = mutableListOf<Identifier>()

        result.add(parseName(parent, supportDollarSymbol = supportDollarSymbol))

        val hasComma = consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COMMA)
        if (!hasComma) {
            return result
        }
        var nameNode = parseName(parent)
        while (true) {
            result.add(nameNode)
            if (!consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COMMA)) break
            nameNode = parseName(parent)
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

    private fun binaryPrecedence(tokenTypes: io.github.dingyi222666.luaparser.lexer.LuaTokenTypes): Int {
        return when (tokenTypes) {
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.OR -> 1
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.AND -> 2
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LT, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.GT, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LE, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.GE, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.EQ, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.NE -> 3

            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.BIT_OR -> 4
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.BIT_TILDE -> 5
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.BIT_AND -> 6
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.BIT_LTLT, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.BIT_RTRT -> 7
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.CONCAT -> 8
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.PLUS, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.MINUS -> 9
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DOUBLE_DIV, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DIV, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.MOD, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.MULT -> 10

            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.EXP -> 12
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
        return parseFunctionBody(result, parent, lexer.line())
    }

    //
    private fun parseSubExp(parent: BaseASTNode, minPrecedence: Int): ExpressionNode {

        var precedence: Int

        val currentToken = peek {
            markLocation()
            it
        }
        var node: ExpressionNode
        node = when {

            equalsMore(
                currentToken, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.MINUS, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.GETN,
                io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.BIT_TILDE, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.NOT
            ) -> {
                // unary
                parseUnaryExpression(
                    parent
                )
            }

            // primary
            currentToken == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ELLIPSIS -> consume { VarargLiteral() }
            currentToken == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.NIL -> consume { ConstantNode.NIL.clone() }

            equalsMore(
                currentToken, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.FALSE, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.TRUE
            ) -> consume { ConstantNode(ConstantNode.TYPE.BOOLEAN, lexerText()) }

            equalsMore(
                currentToken, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LONG_STRING, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.STRING
            ) -> consume { ConstantNode(ConstantNode.TYPE.STRING, lexerText()) }

            currentToken == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.NUMBER -> consume {
                val lexerText = lexerText()
                if (lexerText.contains(".")) {
                    ConstantNode(ConstantNode.TYPE.FLOAT, lexerText)
                } else {
                    ConstantNode(ConstantNode.TYPE.INTERGER, lexerText)
                }
            }

            currentToken == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LAMBDA -> parseLambdaExp(parent)

            currentToken == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.FUNCTION -> consume {
                parseFunctionExp(parent)
            }

            currentToken == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LBRACK -> parseArrayConstructorExpression(parent)

            currentToken == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LCURLY -> parseTableConstructorExpression(parent)

            binaryPrecedence(currentToken).also {
                precedence = it
            } > 0 -> consume {

                precedence = binaryPrecedence(currentToken)
                val result = BinaryExpression().apply {
                    this.parent = parent
                    left = parent as ExpressionNode
                    operator = findExpressionOperator(lexerText()).requireNotNull()
                }

                precedence = binaryPrecedence(currentToken)
                result.right = parseSubExp(result, precedence)
                result
            }

            else -> parsePrefixExp(parent)

        }

        node = finishNode(node.requireNotNull())

        precedence = binaryPrecedence(peek())

        if (precedence <= 0) {
            node.parent = parent
            return node
        }

        while (precedence > minPrecedence) {
            advance()
            markLocation()
            node = BinaryExpression().apply {
                this.parent = parent
                left = node
                operator = findExpressionOperator(lexerText()).requireNotNull()
            }

            node.right = parseSubExp(node, precedence)
            precedence = binaryPrecedence(peek())
            finishNode(node)
        }

        if (node == parent) {
            error("unexpected symbol ${lexerText()} near ${lastToken.name.lowercase()}")
        }

        return node.requireNotNull()
    }

    //   arrayconstructor ::= '[' [explist] ']'
    private fun parseArrayConstructorExpression(parent: BaseASTNode): ArrayConstructorExpression {
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LBRACK) { "'[' expected near ${lexerText()}" }
        // markLocation()
        val result = ArrayConstructorExpression()
        result.parent = parent

        if (consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RBRACK)) {
            // empty token
            return result
        }

        result.values.addAll(parseExpList(parent))

        return finishNode(result)
    }

    //   lambdadef ::= lambda ( [parlist] | ['(' [parlist] ')'] ) (':'|'=>','->') exp
    private fun parseLambdaExp(parent: BaseASTNode): LambdaDeclaration {
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LAMBDA) { "<lambda> expected near ${lexerText()}" }
        markLocation()
        val result = LambdaDeclaration()
        result.parent = parent

        // ['(' [parlist] ')']
        (func@{
            if (!consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LPAREN)) return@func
            if (consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RPAREN)) return@func
            if (peekToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.NAME)) {
                result.params.addAll(parseNameList(result))
            }
            expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RPAREN) { "')' expected near ${lexerText()}" }
        }).invoke()

        // [parlist]
        if (peekToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.NAME)) {
            result.params.addAll(parseNameList(result))
        }

        (func@{
            if (consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COLON)) return@func
            if (consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.MINUS)) {
                expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.GT) { "'->' expected near ${lexerText()}" }
                return@func
            }
            if (consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ASSIGN)) {
                expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.GT) { "'=>' expected near ${lexerText()}" }
                return@func
            }
            error("':' expected near ${lexerText()}")
        }).invoke()

        result.expression = parseExp(result)
        return finishNode(result)
    }

    //  tableconstructor ::= ‘{’ [fieldlist] ‘}’
    private fun parseTableConstructorExpression(parent: BaseASTNode): TableConstructorExpression {
        val result = TableConstructorExpression()
        val currentLine = lexer.line()
        result.parent = parent
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LCURLY) { "'{' expected near ${lexerText()}" }
        //markLocation()
        if (consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RCURLY)) {
            // empty table
            return result
        }
        result.fields.addAll(parseFieldList(parent))
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RCURLY) { "'}' expected (to close '{' at line $currentLine) near ${lexerText()}" }

        return result
    }

    //  fieldlist ::= field {fieldsep field} [fieldsep]
    //  fieldsep ::= ‘,’ | ‘;’
    private fun parseFieldList(parent: BaseASTNode): List<TableKey> {
        val result = mutableListOf<TableKey>()

        val index = atomic(1)
        result.add(finishNode(parseField(parent, index).requireNotNull()))

        while (true) {
            // , :
            if (!equalsMore(peek(), io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COMMA, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.SEMI)) {
                break
            }
            advance()
            val fieldValue = parseField(parent, index) ?: break
            result.add(finishNode(fieldValue))
        }

        consume { equalsMore(it, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COMMA, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.SEMI) }

        return result
    }

    //  field ::= ‘[’ exp ‘]’ ‘=’ exp | Name ‘=’ exp | exp
    private fun parseField(parent: BaseASTNode, index: AtomicInt): TableKey? {
        when (peek()) {
            //  Name ‘=’ exp |
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.NAME -> {
                val peek = peekN(2)
                if (peek == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ASSIGN) {
                    return parseTableStringKey(parent)
                }
            }

            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LBRACK -> return parseTableKey(parent)
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RCURLY -> return null
            // exp |
            // It is possible to encounter '}',
            // and since we cannot tell if this is an expression, we use nullable return.
            else -> {}
        }

        markLocation()

        val result = TableKey()
        // int
        result.key = ConstantNode(ConstantNode.TYPE.INTERGER, index.getAndDecrement()).apply {
            range = Range(locations.first(), locations.first())
        }
        result.value = parseExp(parent)

        return result
    }

    //  ‘[’ exp ‘]’ ‘=’ exp
    private fun parseTableKey(parent: BaseASTNode): TableKey {
        val result = TableKey()
        result.parent = parent
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LBRACK) { "'[' expected near ${lexerText(true)}" }
        markLocation()
        result.key = parseExp(result)

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RBRACK) { "']' expected near ${lexerText(true)}" }
        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ASSIGN) { "'=' expected near ${lexerText(true)}" }

        result.value = parseExp(result)

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

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ASSIGN) { "'=' expected near ${lexerText(true)}" }

        result.value = parseExp(result)
        return result
    }

    //  primaryexp ::= NAME | '(' expr ')' *
    private fun parsePrimaryExp(parent: BaseASTNode): ExpressionNode {
        return when (peek()) {
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.NAME -> parseName(parent)
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LPAREN -> {
                advance()
                markLocation()
                val exp = parseExp(parent)
                expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RPAREN) { "')' expected near ${lexerText(true)}" }
                finishNode(exp)
            }

            else -> error("<expression> expected near ${lexerText(true)}")
        }
    }

    //  prefixExp ::= primaryexp { '.' fieldset | '[' exp ']' | ':' NAME funcargs | funcargs  }

    private fun parsePrefixExp(parent: BaseASTNode): ExpressionNode {
        var result = parsePrimaryExp(parent)

        var parentNode = parent

        while (true) {
            result = when (peek()) {
                // '.' fieldset*
                io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.DOT ->
                    parseFieldSet(parentNode, result)

                // [' exp ']'
                io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LBRACK ->
                    parseIndexExpression(parentNode, result)

                // funcargs
                io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LPAREN, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LCURLY, io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.STRING ->
                    parseCallExpression(parent, result)

                //  ':' NAME funcargs
                io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COLON -> {
                    val fieldSet = parseFieldSet(parent, result)
                    finishNode(fieldSet)
                    parseCallExpression(parent, fieldSet)
                }

                else -> break
            }
            finishNode(result)
            parentNode = result
        }

        return result

    }


    // funcargs -> '(' [ explist ] ') | tableconstructor | string
    private fun parseCallExpression(parent: BaseASTNode, base: ExpressionNode): CallExpression {
        val result = CallExpression()
        result.parent = parent
        result.base = base
        markLocation()
        // consume
        val isOnlyExpList = when (peek()) {
            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.STRING -> {
                result.base = parseStringCallExpression(result, base).let(::finishNode)
                false
            }

            io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LCURLY -> {
                result.base = parseTableCallExpression(result, base).let(::finishNode)
                false
            }

            else -> true
        }

        val findLeft = consume { it == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.LPAREN }
        if (!findLeft && !isOnlyExpList) {
            // empty left
            consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.SEMI)
            return result
        }

        val findRight = consume { it == io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RPAREN }

        if (findRight) {
            // empty arg
            return result
        }

        result.arguments.addAll(parseExpList(parent))

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RPAREN) { "')' expected near ${lexerText()}" }

        return result
    }

    private fun parseTableCallExpression(parent: BaseASTNode, base: ExpressionNode): TableCallExpression {
        markLocation()
        val result = TableCallExpression()
        result.parent = parent
        result.base = base

        // consume tab
        markLocation()
        result.arguments.add(finishNode(parseTableConstructorExpression(parent)))

        return result
    }

    private fun parseStringCallExpression(parent: BaseASTNode, base: ExpressionNode): StringCallExpression {
        val result = StringCallExpression()
        result.parent = parent
        result.base = base
        markLocation()

        // consume string
        result.arguments.add(parseExp(parent))

        return result
    }


    // [' exp ']'
    private fun parseIndexExpression(parent: BaseASTNode, base: ExpressionNode): IndexExpression {
        advance()
        markLocation()
        val result = IndexExpression()
        result.parent = parent
        result.base = base
        result.index = parseExp(result)

        expectToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.RBRACK) { "']' expected near ${lexerText()}" }

        return result
    }

    //  ['.' | ':'] NAME
    private fun parseFieldSet(parent: BaseASTNode, base: ExpressionNode): MemberExpression {
        val result = MemberExpression()
        advance()
        markLocation()
        result.indexer = lexerText()

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
        result.operator = findExpressionOperator(lexerText()).requireNotNull()
        result.arg = parseSubExp(result, 11);
        return result
    }


    // explist ::= exp {‘,’ exp}
    private fun parseExpList(parent: BaseASTNode): List<ExpressionNode> {
        val result = mutableListOf<ExpressionNode>()

        result.add(parseExp(parent))

        val hasComma = consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COMMA)
        if (!hasComma) {
            return result
        }

        var expNode = parseExp(parent)
        while (true) {
            result.add(expNode)
            if (!consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.COMMA)) break
            expNode = parseExp(parent)
        }

        return result
    }

    // local namelist [‘=’ explist]
    private fun parseLocalVarList(parent: BaseASTNode): LocalStatement {
        val localStatement = LocalStatement()
        localStatement.parent = parent
        markLocation()
        localStatement.init.addAll(parseNameList(localStatement, true))
        localStatement.init.forEach {
            it.isLocal = true
        }

        // '='
        if (!consumeToken(io.github.dingyi222666.luaparser.lexer.LuaTokenTypes.ASSIGN)) {
            return localStatement
        }

        // advance to exp list
        localStatement.variables.addAll(parseExpList(localStatement))

        return localStatement
    }

}

