package parser.ast

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionOperator
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.TableKeyString
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.VarargLiteral
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AstShapeTddTest {

    @Test
    fun statementFixtureExposesStableStatementShapesParentsAndRanges() {
        val source = loadAstShapeFixture("statements.lua")
        val chunk = parse(source)
        val statements = chunk.body.statements

        assertContentEquals(
            listOf(
                "CommentStatement",
                "LabelStatement",
                "LocalStatement",
                "DoStatement",
                "WhileStatement",
                "RepeatStatement",
                "ForNumericStatement",
                "ForGenericStatement",
                "FunctionDeclaration",
                "FunctionDeclaration",
                "CallStatement",
                "GotoStatement"
            ),
            statements.map { it.javaClass.simpleName }
        )
        assertIs<ReturnStatement>(chunk.body.returnStatement)

        val comment = statement<CommentStatement>(chunk, 0)
        assertFalse(comment.isDocComment)
        assertEquals("-- lead statement comment", comment.comment.trim())
        assertRangeText(source, comment, "-- lead statement comment")
        assertParent(chunk.body, comment)

        val label = statement<LabelStatement>(chunk, 1)
        assertEquals("entry", label.identifier.name)
        assertRangeText(source, label, "::entry::")
        assertParent(label, label.identifier)

        val local = statement<LocalStatement>(chunk, 2)
        assertContentEquals(listOf("total", "label"), local.init.map { it.name })
        assertContentEquals(listOf("Const(0)", "Const(\"start\")"), local.variables.map(::shape))
        assertRangeText(source, local, "local total, label = 0, \"start\"")
        assertParent(local, local.init.first())
        assertParent(local, local.variables.first())

        val doStatement = statement<DoStatement>(chunk, 3)
        assertEquals("Assign(Id(total)=Binary(+,Id(total),Const(1)))", shape(doStatement.body.statements.single()))
        assertRangeText(source, doStatement, "do\n    total = total + 1\nend")
        assertParent(doStatement, doStatement.body)

        val whileStatement = statement<WhileStatement>(chunk, 4)
        assertEquals("Binary(<,Id(total),Const(3))", shape(whileStatement.condition))
        assertEquals("Assign(Id(total)=Binary(+,Id(total),Const(1)))", shape(whileStatement.body.statements.single()))
        assertParent(whileStatement, whileStatement.condition)
        assertParent(whileStatement, whileStatement.body)

        val repeatStatement = statement<RepeatStatement>(chunk, 5)
        assertEquals("Binary(==,Id(total),Const(1))", shape(repeatStatement.condition))
        assertEquals("Assign(Id(total)=Binary(-,Id(total),Const(1)))", shape(repeatStatement.body.statements.single()))

        val numericFor = statement<ForNumericStatement>(chunk, 6)
        assertEquals("i", numericFor.variable.name)
        assertEquals("Const(1)", shape(numericFor.start))
        assertEquals("Const(3)", shape(numericFor.end))
        assertEquals("Const(1)", shape(assertNotNull(numericFor.step)))
        assertParent(numericFor, numericFor.variable)
        assertParent(numericFor, numericFor.body)

        val genericFor = statement<ForGenericStatement>(chunk, 7)
        assertContentEquals(listOf("key", "value"), genericFor.variables.map { it.name })
        assertContentEquals(listOf("Call(Id(pairs):Id(items))", "Id(next)"), genericFor.iterators.map(::shape))
        assertEquals("CallStmt(Call(Id(consume):Id(key),Id(value)))", shape(genericFor.body.statements.single()))
        assertParent(genericFor, genericFor.body)

        val globalFunction = statement<FunctionDeclaration>(chunk, 8)
        assertFalse(globalFunction.isLocal)
        assertEquals("Member(Id(mod).run)", shape(assertNotNull(globalFunction.identifier)))
        assertContentEquals(listOf("self", "..."), globalFunction.params.map { it.name })
        assertEquals("Return(Id(self),Vararg)", shape(globalFunction.body!!.returnStatement!!))

        val localFunction = statement<FunctionDeclaration>(chunk, 9)
        assertTrue(localFunction.isLocal)
        assertEquals("finish", assertIs<Identifier>(localFunction.identifier).name)
        assertContentEquals(listOf("value"), localFunction.params.map { it.name })
        assertRangeText(source, localFunction, "local function finish(value)\n    return value\nend")

        val call = statement<CallStatement>(chunk, 10)
        assertEquals("Call(Id(finish):Id(total))", shape(call.expression))
        assertParent(call, call.expression)

        val goto = statement<GotoStatement>(chunk, 11)
        assertEquals("entry", goto.identifier.name)
        assertRangeText(source, goto, "goto entry")

        val returnStatement = assertNotNull(chunk.body.returnStatement)
        assertContentEquals(listOf("Id(total)", "Id(label)"), returnStatement.arguments.map(::shape))
        assertRangeText(source, returnStatement, "return total, label")
        assertParent(chunk.body, returnStatement)
    }

    @Test
    fun expressionFixtureExposesStableExpressionShapesParentsAndRanges() {
        val source = loadAstShapeFixture("expressions.lua")
        val chunk = parse(source)
        val locals = chunk.body.statements.mapIndexed { index, _ -> statement<LocalStatement>(chunk, index) }

        val arithmetic = localValue<BinaryExpression>(locals[0])
        assertEquals(ExpressionOperator.ADD, arithmetic.operator)
        assertEquals("Id(a)", shape(assertNotNull(arithmetic.left)))
        assertEquals("Binary(*,Id(b),Unary(-,Binary(^,Id(c),Const(2))))", shape(assertNotNull(arithmetic.right)))
        assertRangeText(source, arithmetic, "a + b * -c ^ 2")

        val multiply = assertIs<BinaryExpression>(arithmetic.right)
        val unary = assertIs<UnaryExpression>(multiply.right)
        val exponent = assertIs<BinaryExpression>(unary.arg)
        assertEquals(ExpressionOperator.BIT_EXP, exponent.operator)
        assertParent(unary, unary.arg)

        val chained = localValue<MemberExpression>(locals[1])
        assertEquals("Member(Index(Member(Id(root).child)[Id(index)]).name)", shape(chained))
        assertEquals("name", chained.identifier.name)
        assertIs<IndexExpression>(chained.base)
        assertRangeText(source, chained, "root.child[index].name")

        val callResult = localValue<CallExpression>(locals[2])
        assertEquals("Call(Member(Id(object):method):Const(1),Const(\"two\"),Call(Id(fn):))", shape(callResult))
        assertEquals("method", assertIs<MemberExpression>(callResult.base).identifier.name)
        assertEquals(3, callResult.arguments.size)
        assertRangeText(source, callResult, "object:method(1, \"two\", fn())")

        val lambda = localValue<LambdaDeclaration>(locals[3])
        assertContentEquals(listOf("x", "y"), lambda.params.map { it.name })
        assertEquals("Binary(or,Binary(and,Id(x),Id(y)),Const(nil))", shape(lambda.expression))
        assertRangeText(source, lambda, "lambda (x, y): x and y or nil")

        val array = localValue<io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression>(locals[4])
        assertContentEquals(listOf("Const(1)", "Id(item)", "Call(Id(call):)"), array.values.map(::shape))
        assertRangeText(source, array, "[1, item, call()]")

        val returnStatement = assertNotNull(chunk.body.returnStatement)
        assertContentEquals(
            listOf("Id(arithmetic)", "Id(chained)", "Id(callResult)", "Id(lambdaValue)", "Id(arrayValue)"),
            returnStatement.arguments.map(::shape)
        )
    }

    @Test
    fun tableFunctionAndCommentFixtureExposesStableAstShapesParentsAndRanges() {
        val source = loadAstShapeFixture("tables-functions-comments.lua")
        val chunk = parse(source)

        val docComment = statement<CommentStatement>(chunk, 0)
        assertTrue(docComment.isDocComment)
        assertEquals("--- doc for module", docComment.comment.trim())
        assertRangeText(source, docComment, "--- doc for module")

        val moduleLocal = statement<LocalStatement>(chunk, 1)
        val moduleTable = localValue<TableConstructorExpression>(moduleLocal)
        assertEquals(4, moduleTable.fields.size)
        assertRangeText(source, moduleTable, "{\n    name = \"demo\",\n    [key] = value,\n    child = { enabled = true },\n    42,\n}")

        val nameField = assertIs<TableKeyString>(moduleTable.fields[0])
        assertEquals("Id(name)", shape(nameField.key))
        assertEquals("Const(\"demo\")", shape(nameField.value))
        assertEquals("TableKeyString(Id(name)=Const(\"demo\"))", shape(nameField))

        val computedField = assertIs<TableKey>(moduleTable.fields[1])
        assertEquals("Id(key)", shape(computedField.key))
        assertEquals("Id(value)", shape(computedField.value))
        assertEquals("TableKey(Id(key)=Id(value))", shape(computedField))

        val childField = assertIs<TableKeyString>(moduleTable.fields[2])
        val childTable = assertIs<TableConstructorExpression>(childField.value)
        assertEquals("TableKeyString(Id(enabled)=Const(true))", shape(childTable.fields.single()))

        val arrayField = assertIs<TableKey>(moduleTable.fields[3])
        assertEquals("Const(1)", shape(arrayField.key))
        assertEquals("Const(42)", shape(arrayField.value))

        val regularComment = statement<CommentStatement>(chunk, 2)
        assertFalse(regularComment.isDocComment)
        assertEquals("-- regular comment", regularComment.comment.trim())
        assertRangeText(source, regularComment, "-- regular comment")

        val method = statement<FunctionDeclaration>(chunk, 3)
        assertFalse(method.isLocal)
        assertEquals("Member(Id(module):create)", shape(assertNotNull(method.identifier)))
        assertContentEquals(listOf("first", "..."), method.params.map { it.name })
        assertRangeText(
            source,
            method,
            "function module:create(first, ...)\n    local packed = { first, ... }\n    return self.name, packed\nend"
        )

        val methodBody = assertNotNull(method.body)
        val packedLocal = assertIs<LocalStatement>(methodBody.statements.single())
        assertEquals("packed", packedLocal.init.single().name)
        val packedTable = localValue<TableConstructorExpression>(packedLocal)
        assertContentEquals(
            listOf("TableKey(Const(1)=Id(first))", "TableKey(Const(2)=Vararg)"),
            packedTable.fields.map(::shape)
        )

        val methodReturn = assertNotNull(methodBody.returnStatement)
        assertContentEquals(listOf("Member(Id(self).name)", "Id(packed)"), methodReturn.arguments.map(::shape))

        val returnStatement = assertNotNull(chunk.body.returnStatement)
        assertEquals("Id(module)", shape(returnStatement.arguments.single()))
    }

    @Test
    fun clonePreservesSwitchAndIfClauseShapesRangesAndParents() {
        val switchCaseBody = blockWith(comment("case body").withCloneRange(4, 9, 4, 20))
            .withCloneRange(4, 5, 5, 8)
        val defaultBody = blockWith(comment("default body").withCloneRange(6, 9, 6, 23))
            .withCloneRange(6, 5, 7, 8)
        val switch = SwitchStatement().withCloneRange(1, 1, 8, 4, bad = true).apply {
            condition = id("mode").withCloneRange(1, 8, 1, 12)
            causes.add(
                CaseCause().withCloneRange(2, 5, 5, 8).apply {
                    conditions.add(intConst(1).withCloneRange(2, 10, 2, 11))
                    body = switchCaseBody
                }
            )
            causes.add(
                DefaultCause().withCloneRange(5, 5, 7, 8).apply {
                    body = defaultBody
                }
            )
        }

        val switchClone = switch.clone()

        assertCloneMetadata(switch, switchClone)
        assertEquals("Id(mode)", shape(switchClone.condition))
        assertEquals(2, switchClone.causes.size)
        val clonedCase = assertIs<CaseCause>(switchClone.causes[0])
        val clonedDefault = assertIs<DefaultCause>(switchClone.causes[1])
        assertCloneMetadata(switch.causes[0], clonedCase)
        assertCloneMetadata(switch.causes[1], clonedDefault)
        assertCloneMetadata(switchCaseBody, clonedCase.body)
        assertCloneMetadata(defaultBody, clonedDefault.body)
        assertEquals("Const(1)", shape(clonedCase.conditions.single()))
        assertParent(switchClone, switchClone.condition)
        assertParent(switchClone, clonedCase)
        assertParent(switchClone, clonedDefault)
        assertParent(clonedCase, clonedCase.conditions.single())
        assertParent(clonedCase, clonedCase.body)
        assertParent(clonedDefault, clonedDefault.body)
        assertParent(clonedCase.body, clonedCase.body.statements.single())

        val ifBody = blockWith(comment("if body").withCloneRange(12, 9, 12, 17)).withCloneRange(12, 5, 13, 8)
        val elseifBody = blockWith(comment("elseif body").withCloneRange(14, 9, 14, 21)).withCloneRange(14, 5, 15, 8)
        val elseBody = blockWith(comment("else body").withCloneRange(16, 9, 16, 19)).withCloneRange(16, 5, 17, 8)
        val ifStatement = IfStatement().withCloneRange(10, 1, 18, 4, bad = true).apply {
            causes.add(
                IfClause().withCloneRange(10, 1, 13, 8).apply {
                    condition = id("first").withCloneRange(10, 4, 10, 9)
                    body = ifBody
                }
            )
            causes.add(
                ElseIfClause().withCloneRange(13, 1, 15, 8).apply {
                    condition = id("second").withCloneRange(13, 8, 13, 14)
                    body = elseifBody
                }
            )
            causes.add(
                ElseClause().withCloneRange(15, 1, 17, 8).apply {
                    body = elseBody
                }
            )
        }

        val ifClone = ifStatement.clone()

        assertCloneMetadata(ifStatement, ifClone)
        assertEquals(3, ifClone.causes.size)
        assertEquals("IfClause", ifClone.causes[0].javaClass.simpleName)
        val clonedElseIf = assertIs<ElseIfClause>(ifClone.causes[1])
        val clonedElse = assertIs<ElseClause>(ifClone.causes[2])
        assertEquals("Id(first)", shape(ifClone.causes[0].condition))
        assertEquals("Id(second)", shape(clonedElseIf.condition))
        assertCloneMetadata(ifStatement.causes[0], ifClone.causes[0])
        assertCloneMetadata(ifStatement.causes[1], clonedElseIf)
        assertCloneMetadata(ifStatement.causes[2], clonedElse)
        assertCloneMetadata(elseBody, clonedElse.body)
        assertParent(ifClone, ifClone.causes[0])
        assertParent(ifClone, clonedElseIf)
        assertParent(ifClone, clonedElse)
        assertParent(ifClone.causes[0], ifClone.causes[0].condition)
        assertParent(clonedElseIf, clonedElseIf.condition)
        assertParent(clonedElse, clonedElse.body)
    }

    @Test
    fun clonePreservesCallExpressionVariantsRangesAndParents() {
        val stringCall = StringCallExpression().withCloneRange(1, 1, 1, 16, bad = true).apply {
            base = id("require").withCloneRange(1, 1, 1, 8)
            arguments.add(stringConst("module").withCloneRange(1, 9, 1, 16))
        }
        val tableCall = TableCallExpression().withCloneRange(2, 1, 2, 18).apply {
            base = id("import").withCloneRange(2, 1, 2, 7)
            arguments.add(
                TableConstructorExpression().withCloneRange(2, 8, 2, 18).apply {
                    fields.add(
                        TableKeyString().withCloneRange(2, 10, 2, 16).apply {
                            key = id("name").withCloneRange(2, 10, 2, 14)
                            value = id("value").withCloneRange(2, 17, 2, 22)
                        }
                    )
                }
            )
        }
        val nestedCall = CallExpression().withCloneRange(3, 1, 3, 24).apply {
            base = stringCall
            arguments.add(tableCall)
        }

        val stringClone = stringCall.clone()
        val tableClone = tableCall.clone()
        val nestedClone = nestedCall.clone()

        assertIs<StringCallExpression>(stringClone)
        assertIs<TableCallExpression>(tableClone)
        assertCloneMetadata(stringCall, stringClone)
        assertCloneMetadata(tableCall, tableClone)
        assertParent(stringClone, stringClone.base)
        assertParent(stringClone, stringClone.arguments.single())
        assertParent(tableClone, tableClone.base)
        assertParent(tableClone, tableClone.arguments.single())
        assertIs<StringCallExpression>(nestedClone.base)
        assertIs<TableCallExpression>(nestedClone.arguments.single())
        assertParent(nestedClone, nestedClone.base)
        assertParent(nestedClone, nestedClone.arguments.single())

        val callStatement = CallStatement().withCloneRange(4, 1, 4, 20).apply {
            expression = tableCall
        }
        val callStatementClone = callStatement.clone()
        assertIs<TableCallExpression>(callStatementClone.expression)
        assertParent(callStatementClone, callStatementClone.expression)
    }

    @Test
    fun clonePreservesTableKeyVariantsCommentMetadataAndParents() {
        val table = TableConstructorExpression().withCloneRange(1, 1, 4, 2, bad = true).apply {
            fields.add(
                TableKeyString().withCloneRange(2, 5, 2, 19).apply {
                    key = id("name").withCloneRange(2, 5, 2, 9)
                    value = stringConst("demo").withCloneRange(2, 12, 2, 18)
                }
            )
            fields.add(
                TableKey().withCloneRange(3, 5, 3, 18).apply {
                    key = id("computed").withCloneRange(3, 6, 3, 14)
                    value = id("value").withCloneRange(3, 18, 3, 23)
                }
            )
        }
        val docComment = CommentStatement().withCloneRange(5, 1, 5, 16, bad = true).apply {
            comment = "--- doc comment"
            isDocComment = true
        }

        val tableClone = table.clone()
        val docCommentClone = docComment.clone()

        assertCloneMetadata(table, tableClone)
        assertEquals(2, tableClone.fields.size)
        val clonedStringKey = assertIs<TableKeyString>(tableClone.fields[0])
        val clonedComputedKey = assertIs<TableKey>(tableClone.fields[1])
        assertCloneMetadata(table.fields[0], clonedStringKey)
        assertCloneMetadata(table.fields[1], clonedComputedKey)
        assertEquals("TableKeyString(Id(name)=Const(\"demo\"))", shape(clonedStringKey))
        assertEquals("TableKey(Id(computed)=Id(value))", shape(clonedComputedKey))
        assertParent(tableClone, clonedStringKey)
        assertParent(tableClone, clonedComputedKey)
        assertParent(clonedStringKey, clonedStringKey.key)
        assertParent(clonedStringKey, clonedStringKey.value)
        assertParent(clonedComputedKey, clonedComputedKey.key)
        assertParent(clonedComputedKey, clonedComputedKey.value)

        assertCloneMetadata(docComment, docCommentClone)
        assertTrue(docCommentClone.isDocComment)
        assertEquals("--- doc comment", docCommentClone.comment)
    }

    @Test
    fun clonePreservesTopLevelChunkAndBlockMetadataAndParents() {
        val chunk = ChunkNode().withCloneRange(1, 1, 5, 4, bad = true)
        val body = BlockNode().withCloneRange(1, 1, 5, 4, bad = true).apply {
            statements.add(comment("top statement").withCloneRange(2, 5, 2, 21, bad = true))
            returnStatement = ReturnStatement().withCloneRange(4, 5, 4, 18).apply {
                arguments.add(id("result").withCloneRange(4, 12, 4, 18))
            }
        }
        chunk.body = body
        body.parent = chunk
        body.statements.single().parent = body
        body.returnStatement!!.parent = body

        val clone = chunk.clone()

        assertCloneMetadata(chunk, clone)
        assertCloneMetadata(body, clone.body)
        assertParent(clone, clone.body)
        val clonedStatement = assertIs<CommentStatement>(clone.body.statements.single())
        assertCloneMetadata(body.statements.single(), clonedStatement)
        assertParent(clone.body, clonedStatement)
        val clonedReturn = assertNotNull(clone.body.returnStatement)
        assertCloneMetadata(body.returnStatement!!, clonedReturn)
        assertParent(clone.body, clonedReturn)
        assertEquals("Return(Id(result))", shape(clonedReturn))
        assertParent(clonedReturn, clonedReturn.arguments.single())
    }

    @Test
    fun clonePreservesRecoveryPlaceholderExpressionIdentityStateAndParentIsolation() {
        val local = LocalStatement().withCloneRange(1, 1, 1, 14).apply {
            val placeholder = ExpressionNode.Companion.ExpressionNodeSupport()
                .withCloneRange(1, 13, 1, 13, bad = true)
            variables.add(placeholder)
            init.add(id("value").withCloneRange(1, 7, 1, 12))
            placeholder.parent = this
            init.single().parent = this
        }
        val originalPlaceholder = local.variables.single()

        val directClone = originalPlaceholder.clone()
        val localClone = local.clone()
        val clonedPlaceholder = localClone.variables.single()

        assertNotSame(originalPlaceholder, directClone)
        assertNotSame(ExpressionNode.EMPTY, directClone)
        assertNotSame(originalPlaceholder, clonedPlaceholder)
        assertNotSame(ExpressionNode.EMPTY, clonedPlaceholder)
        assertCloneMetadata(originalPlaceholder, directClone)
        assertCloneMetadata(originalPlaceholder, clonedPlaceholder)
        assertParent(local, originalPlaceholder)
        assertParent(localClone, clonedPlaceholder)
    }

    @Test
    fun clonePreservesElseClauseParserPlaceholderConditionState() {
        val source = """
            if ready then
                return ready
            else
                return fallback
            end
        """.trimIndent()
        val ifStatement = statement<IfStatement>(parse(source), 0)
        val elseClause = assertIs<ElseClause>(ifStatement.causes[1])
        val originalCondition = elseClause.condition.withCloneRange(3, 1, 3, 5, bad = true)

        val ifClone = ifStatement.clone()
        val clonedElse = assertIs<ElseClause>(ifClone.causes[1])

        assertNotSame(originalCondition, clonedElse.condition)
        assertNotSame(ExpressionNode.EMPTY, clonedElse.condition)
        assertEquals(originalCondition::class, clonedElse.condition::class)
        assertCloneMetadata(originalCondition, clonedElse.condition)
        assertParent(ifClone, clonedElse)
        assertParent(elseClause, originalCondition)
        assertParent(clonedElse, clonedElse.condition)
    }

    private fun parse(source: String): ChunkNode {
        return LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false).parse(source)
    }

    private inline fun <reified T : StatementNode> statement(chunk: ChunkNode, index: Int): T {
        return assertIs<T>(chunk.body.statements[index])
    }

    private inline fun <reified T : ExpressionNode> localValue(statement: LocalStatement, index: Int = 0): T {
        return assertIs<T>(statement.variables[index])
    }

    private fun assertParent(parent: BaseASTNode, child: BaseASTNode) {
        assertSame(parent, child.parent, "Unexpected parent for ${shape(child)}")
    }

    private fun assertCloneMetadata(original: BaseASTNode, clone: BaseASTNode) {
        assertEquals(original.range, clone.range, "Clone range mismatch for ${shape(original)}")
        assertEquals(original.bad, clone.bad, "Clone bad flag mismatch for ${shape(original)}")
    }

    private fun <T : BaseASTNode> T.withCloneRange(
        startLine: Int,
        startColumn: Int,
        endLine: Int,
        endColumn: Int,
        bad: Boolean = false
    ): T = apply {
        range = Range(Position(startLine, startColumn), Position(endLine, endColumn))
        this.bad = bad
    }

    private fun id(name: String): Identifier = Identifier(name)

    private fun intConst(value: Int): ConstantNode = ConstantNode(ConstantNode.TYPE.INTERGER, value)

    private fun stringConst(value: String): ConstantNode = ConstantNode(ConstantNode.TYPE.STRING, "\"$value\"")

    private fun comment(text: String): CommentStatement = CommentStatement().apply {
        comment = "-- $text"
    }

    private fun blockWith(statement: StatementNode): BlockNode = BlockNode().apply {
        statements.add(statement)
        statement.parent = this
    }

    private fun assertRangeText(source: String, node: BaseASTNode, text: String, occurrence: Int = 1) {
        val expected = rangeOf(source, text, occurrence)
        assertEquals(
            expected,
            node.range,
            "Unexpected range for ${shape(node)} from fixture text '$text' occurrence $occurrence"
        )
    }

    private fun rangeOf(source: String, text: String, occurrence: Int): Range {
        val startOffset = nthIndexOf(source, text, occurrence)
        return Range(positionAt(source, startOffset), positionAt(source, startOffset + text.length))
    }

    private fun nthIndexOf(source: String, text: String, occurrence: Int): Int {
        require(occurrence >= 1) { "occurrence must be positive" }
        var fromIndex = 0
        repeat(occurrence) { index ->
            val found = source.indexOf(text, fromIndex)
            check(found >= 0) { "Missing occurrence ${index + 1} of fixture text: $text" }
            fromIndex = found + text.length
            if (index == occurrence - 1) {
                return found
            }
        }
        error("unreachable")
    }

    private fun positionAt(source: String, offset: Int): Position {
        var line = 1
        var column = 1
        for (index in 0 until offset) {
            if (source[index] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return Position(line, column)
    }

    private fun shape(node: BaseASTNode): String {
        return when (node) {
            is ChunkNode -> "Chunk(${shape(node.body)})"
            is BlockNode -> buildString {
                append("Block[")
                append(node.statements.joinToString(";") { shape(it) })
                node.returnStatement?.let {
                    if (node.statements.isNotEmpty()) append(';')
                    append(shape(it))
                }
                append(']')
            }
            is LocalStatement -> "Local(${node.init.joinToString(",") { shape(it) }}=${node.variables.joinToString(",") { shape(it) }})"
            is AssignmentStatement -> "Assign(${node.init.joinToString(",") { shape(it) }}=${node.variables.joinToString(",") { shape(it) }})"
            is ReturnStatement -> "Return(${node.arguments.joinToString(",") { shape(it) }})"
            is CallStatement -> "CallStmt(${shape(node.expression)})"
            is DoStatement -> "Do(${shape(node.body)})"
            is WhileStatement -> "While(${shape(node.condition)}:${shape(node.body)})"
            is RepeatStatement -> "Repeat(${shape(node.body)}:${shape(node.condition)})"
            is ForNumericStatement -> "ForNumeric(${shape(node.variable)}=${shape(node.start)},${shape(node.end)},${node.step?.let(::shape) ?: "null"}:${shape(node.body)})"
            is ForGenericStatement -> "ForGeneric(${node.variables.joinToString(",") { shape(it) }} in ${node.iterators.joinToString(",") { shape(it) }}:${shape(node.body)})"
            is FunctionDeclaration -> "Function(${node.identifier?.let(::shape)},params=${node.params.joinToString(",") { it.name }},local=${node.isLocal},${node.body?.let(::shape) ?: "null"})"
            is LambdaDeclaration -> "Lambda(${node.params.joinToString(",") { it.name }}:${shape(node.expression)})"
            is BinaryExpression -> "Binary(${node.operator},${shape(assertNotNull(node.left))},${shape(assertNotNull(node.right))})"
            is UnaryExpression -> "Unary(${node.operator},${shape(node.arg)})"
            is CallExpression -> "Call(${shape(node.base)}:${node.arguments.joinToString(",") { shape(it) }})"
            is MemberExpression -> "Member(${shape(node.base)}${node.indexer}${node.identifier.name})"
            is IndexExpression -> "Index(${shape(node.base)}[${shape(node.index)}])"
            is TableConstructorExpression -> "Table(${node.fields.joinToString(",") { shape(it) }})"
            is TableKeyString -> "TableKeyString(${shape(node.key)}=${shape(node.value)})"
            is TableKey -> "TableKey(${shape(node.key)}=${shape(node.value)})"
            is Identifier -> "Id(${node.name})"
            is ConstantNode -> "Const(${node.rawValue})"
            is VarargLiteral -> "Vararg"
            is CommentStatement -> "Comment(${if (node.isDocComment) "doc" else "line"}:${node.comment.trim()})"
            else -> node.javaClass.simpleName
        }
    }

    private fun loadAstShapeFixture(name: String): String {
        val path = "/parser/tdd/ast-shapes/$name"
        return checkNotNull(javaClass.getResourceAsStream(path)) {
            "Missing AST shape fixture: $path"
        }.bufferedReader().use { it.readText() }
    }
}
