package io.github.dingyi222666.parser.lua.ast


import io.github.dingyi222666.lua.parser.antlr.LuaBaseVisitor
import io.github.dingyi222666.lua.parser.antlr.LuaLexer
import io.github.dingyi222666.lua.parser.antlr.LuaParser
import io.github.dingyi222666.parser.lua.ast.node.*
import io.github.dingyi222666.parser.lua.util.require
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream


/**
 * @author: dingyi
 * @date: 2021/10/7 10:50
 * @description: convert cst to ast
 **/
class ASTGenerator {


    fun generate(inputCode: String): ChunkNode {
        val targetLexer = LuaLexer(CharStreams.fromString(inputCode))
        val targetParser = LuaParser(CommonTokenStream(targetLexer))
        return CSTVisitor().visitChunk(targetParser.chunk())
    }

    class CSTVisitor : LuaBaseVisitor<ASTNode>() {


        private fun getStringContent(text: String): String? {
            //char or default
            return if (text[0] == '"' || text[0] == '\'') {
                text.substring(1, text.length - 1)
            } else { //[[
                var startLength = 1
                var nowChar: Char
                while (true) {
                    nowChar = text[startLength + 1]
                    if (nowChar == '=' || nowChar == '[') {
                        startLength++
                    } else {
                        break
                    }
                }
                text.substring(startLength + 1, text.length - startLength - 1)
            }
        }

        private fun getStringContent(string: LuaParser.StringContext?): String {
            return string?.let { string ->
                return when {
                    string.CHARSTRING() != null -> ({
                        string.CHARSTRING()?.text?.let { getStringContent(it) }
                    }).toString()

                    string.LONGSTRING() != null -> ({
                        string.LONGSTRING()?.text?.let { getStringContent(it) }
                    }).toString()

                    string.NORMALSTRING() != null -> ({
                        string.NORMALSTRING()?.text?.let { getStringContent(it) }
                    }).toString()

                    else -> ""
                }
            } ?: ""
        }

        override fun visitChunk(ctx: LuaParser.ChunkContext): ChunkNode {
            val chunkNode = ChunkNode()
            ctx.findBlock()?.let {
                chunkNode.body = setParent(visitBlock(it), chunkNode)

            }
            return chunkNode
        }


        override fun visitLaststat(ctx: LuaParser.LaststatContext): StatementNode {
            TODO("")
        }


        private fun visitAttrNameList(ctx: LuaParser.AttnamelistContext): List<ASTNode> {
            val result = mutableListOf<ASTNode>()
            ctx.NAME().forEach {
                result.add(
                    Identifier(
                        name = it.text
                    )
                )
            }
            return result
        }


        override fun visitExp(ctx: LuaParser.ExpContext): ExpressionNode {
            return when {
                ctx.findNumber() != null -> {
                    val numberContext = ctx.findNumber().require()
                    when {
                        numberContext.INT() != null -> {
                            ConstantsNode(
                                type = ConstantsNode.TYPE.INTERGER,
                                value = numberContext.INT()?.text ?: "0"
                            )
                        }

                        numberContext.HEX() != null -> {
                            ConstantsNode(
                                type = ConstantsNode.TYPE.INTERGER,
                                value = numberContext.INT()?.text ?: "0"
                            )
                        }

                        numberContext.FLOAT() != null -> {
                            ConstantsNode(
                                type = ConstantsNode.TYPE.FLOAT,
                                value = numberContext.FLOAT()?.text ?: "0"
                            )
                        }

                        numberContext.HEX_FLOAT() != null -> {
                            ConstantsNode(
                                type = ConstantsNode.TYPE.FLOAT,
                                value = numberContext.HEX_FLOAT()?.text ?: "0"
                            )
                        }

                        else -> ConstantsNode.NIL
                    }

                }

                ctx.findString() != null -> {
                    ConstantsNode(
                        type = ConstantsNode.TYPE.STRING,
                        value = getStringContent(ctx.findString())
                    )
                }

                ctx.text == "nil" -> ConstantsNode.NIL

                else -> ConstantsNode(
                    type = ConstantsNode.TYPE.UNKNOWN,
                    value = ctx.text
                )
            }
        }

        override fun visitLocalVarListStat(ctx: LuaParser.LocalVarListStatContext): StatementNode {
            val result = LocalStatement()

            //TODO: a<const> lua54 support

            //attr name
            ctx.findAttnamelist()?.let {
                result.variables.addAll(visitAttrNameList(it))
            }

            ctx.findExplist()?.findExp()?.let {
                it.forEach {
                    result.init.add(visitExp(it))
                }
            }
            return result
        }

        private fun visitStat(ctx: LuaParser.StatContext): StatementNode {
            return when (ctx) {
                is LuaParser.LocalVarListStatContext -> visitLocalVarListStat(ctx)
                else -> LocalStatement()
            }
        }

        override fun visitBlock(ctx: LuaParser.BlockContext): BlockNode {
            val blockNode = BlockNode()

            ctx.findStat().forEach {
                blockNode.addStatement(setParent(visitStat(it), blockNode))
            }

            // blockNode.returnStatement = visitLaststat(ctx.findLaststat().require())


            return blockNode
        }

        private fun <T : ASTNode> setParent(current: T, parent: ASTNode): T {
            current.parent = parent
            return current
        }
    }

}