package parser.ast

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTModifier
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class AstVisitorModifierTddTest {

    @Test
    fun visitorTraversesComputedAndImplicitTableKeys() {
        val chunk = parse(
            """
            local tableValue = {
                [dynamicKey] = dynamicValue,
                implicitValue,
            }
            """.trimIndent()
        )
        val identifiers = mutableListOf<String>()
        val constants = mutableListOf<Any>()
        val visitor = object : ASTVisitor<Unit> {
            override fun visitIdentifier(node: Identifier, value: Unit) {
                identifiers += node.name
            }

            override fun visitConstantNode(node: ConstantNode, value: Unit) {
                constants += node.rawValue
            }

            override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: Unit) = Unit
        }

        visitor.visitChunkNode(chunk, Unit)

        assertContains(identifiers, "dynamicKey")
        assertContains(constants, 1)
    }

    @Test
    fun modifierRewritesComputedAndImplicitTableKeys() {
        val chunk = parse(
            """
            local tableValue = {
                [dynamicKey] = dynamicValue,
                implicitValue,
            }
            """.trimIndent()
        )
        val modifier = object : ASTModifier<Unit> {
            override fun visitIdentifier(node: Identifier, value: Unit): Identifier {
                return if (node.name == "dynamicKey") {
                    Identifier("rewrittenKey")
                } else {
                    node
                }
            }

            override fun visitConstantNode(node: ConstantNode, value: Unit): ConstantNode {
                return if (node.constantType == ConstantNode.TYPE.INTERGER && node.rawValue == 1) {
                    ConstantNode(ConstantNode.TYPE.INTERGER, 42)
                } else {
                    node
                }
            }
        }

        modifier.visitChunkNode(chunk, Unit)
        val table = tableValue(chunk)
        val computedField = assertIs<TableKey>(table.fields[0])
        val implicitField = assertIs<TableKey>(table.fields[1])

        assertEquals("rewrittenKey", assertIs<Identifier>(computedField.key).name)
        assertSame(computedField, computedField.key.parent)
        assertEquals(42, assertIs<ConstantNode>(implicitField.key).rawValue)
        assertSame(implicitField, implicitField.key.parent)
    }

    @Test
    fun modifierWritesNumericForExpressionsBackToTheirOwnFields() {
        val chunk = parse(
            """
            for i = startValue, endValue, stepValue do
            end
            """.trimIndent()
        )
        val modifier = object : ASTModifier<Unit> {
            override fun visitIdentifier(node: Identifier, value: Unit): Identifier {
                return when (node.name) {
                    "startValue" -> Identifier("rewrittenStart")
                    "endValue" -> Identifier("rewrittenEnd")
                    "stepValue" -> Identifier("rewrittenStep")
                    else -> node
                }
            }
        }

        modifier.visitChunkNode(chunk, Unit)
        val numericFor = assertIs<ForNumericStatement>(chunk.body.statements.single())

        assertEquals("rewrittenStart", assertIs<Identifier>(numericFor.start).name)
        assertEquals("rewrittenEnd", assertIs<Identifier>(numericFor.end).name)
        assertEquals("rewrittenStep", assertIs<Identifier>(assertNotNull(numericFor.step)).name)
        assertSame(numericFor, numericFor.start.parent)
        assertSame(numericFor, numericFor.end.parent)
        assertSame(numericFor, numericFor.step!!.parent)
    }

    @Test
    fun modifierRestoresLambdaDeclarationParentLinksAfterRewrites() {
        val chunk = parse("local fn = lambda value: value")
        val modifier = object : ASTModifier<Unit> {
            override fun visitIdentifier(node: Identifier, value: Unit): Identifier {
                return if (node.name == "value") {
                    Identifier("rewrittenValue")
                } else {
                    node
                }
            }
        }

        modifier.visitChunkNode(chunk, Unit)
        val local = assertIs<LocalStatement>(chunk.body.statements.single())
        val lambda = assertIs<LambdaDeclaration>(local.variables.single())
        val param = lambda.params.single()
        val expression = assertIs<Identifier>(lambda.expression)

        assertEquals("rewrittenValue", param.name)
        assertEquals("rewrittenValue", expression.name)
        assertSame(lambda, param.parent)
        assertSame(lambda, expression.parent)
    }

    @Test
    fun modifierRestoresStringCallBaseParentLinkAfterRewrite() {
        val chunk = parse("target \"payload\"")
        val modifier = object : ASTModifier<Unit> {
            override fun visitIdentifier(node: Identifier, value: Unit): Identifier {
                return if (node.name == "target") {
                    Identifier("rewrittenTarget")
                } else {
                    node
                }
            }
        }

        modifier.visitChunkNode(chunk, Unit)
        val call = assertIs<CallStatement>(chunk.body.statements.single())
        val outerCall = assertIs<CallExpression>(call.expression)
        val stringCall = assertIs<StringCallExpression>(outerCall.base)
        val base = assertIs<Identifier>(stringCall.base)

        assertEquals("rewrittenTarget", base.name)
        assertSame(call, outerCall.parent)
        assertSame(outerCall, stringCall.parent)
        assertSame(stringCall, base.parent)
        assertSame(stringCall, stringCall.arguments.single().parent)
    }

    @Test
    fun modifierRestoresTableCallBaseParentLinkAfterRewrite() {
        val chunk = parse("target { key = value }")
        val modifier = object : ASTModifier<Unit> {
            override fun visitIdentifier(node: Identifier, value: Unit): Identifier {
                return if (node.name == "target") {
                    Identifier("rewrittenTarget")
                } else {
                    node
                }
            }
        }

        modifier.visitChunkNode(chunk, Unit)
        val call = assertIs<CallStatement>(chunk.body.statements.single())
        val outerCall = assertIs<CallExpression>(call.expression)
        val tableCall = assertIs<TableCallExpression>(outerCall.base)
        val base = assertIs<Identifier>(tableCall.base)

        assertEquals("rewrittenTarget", base.name)
        assertSame(call, outerCall.parent)
        assertSame(outerCall, tableCall.parent)
        assertSame(tableCall, base.parent)
        assertSame(tableCall, tableCall.arguments.single().parent)
    }

    @Test
    fun modifierRestoresBlockStatementParentLinksAfterReplacement() {
        val chunk = parse("local original = 1")
        val modifier = object : ASTModifier<Unit> {
            override fun visitLocalStatement(node: LocalStatement, value: Unit): LocalStatement {
                return LocalStatement().also {
                    it.init.add(Identifier("replacement"))
                    it.variables.add(ConstantNode(ConstantNode.TYPE.INTERGER, 2))
                }
            }
        }

        modifier.visitChunkNode(chunk, Unit)
        val replacement = assertIs<LocalStatement>(chunk.body.statements.single())

        assertEquals("replacement", replacement.init.single().name)
        assertSame(chunk.body, replacement.parent)
    }

    private fun parse(source: String): ChunkNode {
        return LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false).parse(source)
    }

    private fun tableValue(chunk: ChunkNode): TableConstructorExpression {
        val local = assertIs<LocalStatement>(chunk.body.statements.single())
        return assertIs<TableConstructorExpression>(local.variables.single())
    }
}
