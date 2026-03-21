package semantic

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ArrayConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CaseCause
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DefaultCause
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IfClause
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ReturnStatement
import io.github.dingyi222666.luaparser.parser.ast.node.SwitchStatement
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableKey
import io.github.dingyi222666.luaparser.parser.ast.node.UnaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.WhenStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticPipelineIntegrationTest {

    @Test
    fun shadowing_visibility_and_position_queries() {
        val harness = integrationHarness(
            """
            local outer = 1
            local function render(seed)
                do
                    local outer = "inner"
                    local chosen = outer
                end
                local afterDo = outer
                local fromSeed = seed
            end
            """.trimIndent()
        )

        val chosenSymbol = harness.assertSymbol("outer", SymbolKind.LOCAL, harness.positionOf("outer", occurrence = 3))
        val afterDoSymbol = harness.assertSymbol("outer", SymbolKind.LOCAL, harness.positionOf("outer", occurrence = 4))
        val renderScope = assertNotNull(harness.snapshot.model.getScopeAt(harness.positionOf("afterDo")))
        val completions = harness.snapshot.model.getCompletionsAt(harness.positionOf("chosen")).map { it.label }

        assertEquals(harness.positionOf("outer", occurrence = 2), chosenSymbol.range?.start)
        assertEquals(harness.positionOf("outer", occurrence = 1), afterDoSymbol.range?.start)
        assertEquals(2, harness.snapshot.binder.declarationIndex.declarations.count { it.kind == DeclarationKind.LOCAL && it.name == "outer" })
        assertEquals(1, renderScope.symbols.count { it.name == "outer" })
        assertEquals(1, completions.count { it == "outer" })
        assertTrue("seed" in completions)
    }

    @Test
    fun class_fields_methods_and_member_surface() {
        val harness = integrationHarness(
            """
            ---@class Record
            ---@field id integer
            ---@class User: Record
            ---@field name string
            ---@method User:getName(): string
            ---@type User
            local user = {}
            local fieldName = user.name
            local methodName = user:getName()
            """.trimIndent()
        )

        val userDeclaration = harness.declaration("User", DeclarationKind.CLASS)
        val ownedDeclarations = harness.snapshot.binder.declarationIndex.getOwnedDeclarations(DeclarationOwner.Declaration(userDeclaration.id))
        val fieldSymbol = harness.assertSymbol("name", SymbolKind.FIELD, harness.positionOf("name", occurrence = 2))
        val methodSymbol = harness.assertSymbol("getName", SymbolKind.METHOD, harness.positionOf("getName", occurrence = 2))
        val declaredUserType = harness.snapshot.model.getDeclaredType(harness.assertSymbol("User", SymbolKind.CLASS, harness.positionOf("User", occurrence = 1)))
        val memberNames = harness.snapshot.model.getMembers(assertNotNull(declaredUserType)).map { it.name }
        val dotItems = harness.snapshot.model.getCompletionsAt(harness.positionOf("name", occurrence = 2))
        val colonItems = harness.snapshot.model.getCompletionsAt(harness.positionOf("getName", occurrence = 2))

        assertTrue(ownedDeclarations.any { it.kind == DeclarationKind.FIELD && it.name == "name" })
        assertTrue(ownedDeclarations.any { it.kind == DeclarationKind.METHOD && it.name == "getName" })
        assertEquals("string", fieldSymbol.type?.displayName)
        assertTrue(methodSymbol.type?.displayName?.contains("string") == true)
        assertEquals("string", harness.snapshot.model.getTypeAt(harness.localValue("fieldName"))?.displayName)
        assertEquals("string", harness.snapshot.model.getTypeAt(harness.localValue("methodName"))?.displayName)
        assertTrue("id" in memberNames)
        assertTrue("name" in memberNames)
        assertTrue("getName" in memberNames)
        assertTrue(dotItems.any { it.label == "name" })
        assertTrue(dotItems.any { it.label == "id" })
        assertTrue(colonItems.any { it.label == "getName" })
    }

    @Test
    fun applied_generics_and_inherited_members() {
        val harness = integrationHarness(
            """
            ---@class Base<T>
            ---@field value T
            ---@method Base:getValue(): T
            ---@class Box<T>: Base<T>
            ---@alias StringBox Box<string>
            ---@type StringBox
            local box = {}
            local fieldValue = box.value
            local methodValue = box:getValue()
            """.trimIndent()
        )

        val stringBoxDeclaration = harness.declaration("StringBox", DeclarationKind.TYPE_ALIAS)
        val boxDeclaration = harness.declaration("Box", DeclarationKind.CLASS)
        val boxOwnedDeclarations = harness.snapshot.binder.declarationIndex.getOwnedDeclarations(DeclarationOwner.Declaration(boxDeclaration.id))
        val boxType = assertNotNull(harness.snapshot.model.getTypeAt(harness.identifier("box")))
        val members = harness.snapshot.model.getMembers(boxType)
        val valueMember = assertNotNull(members.firstOrNull { it.name == "value" })
        val getValueMember = assertNotNull(members.firstOrNull { it.name == "getValue" })
        val colonItems = harness.snapshot.model.getCompletionsAt(harness.positionOf("getValue", occurrence = 2))

        assertTrue(stringBoxDeclaration.declaredType != null)
        assertTrue(boxOwnedDeclarations.any { it.kind == DeclarationKind.TYPE_PARAMETER && it.name == "T" })
        assertEquals("string", harness.snapshot.model.getTypeAt(harness.localValue("fieldValue"))?.displayName)
        assertEquals("string", harness.snapshot.model.getTypeAt(harness.localValue("methodValue"))?.displayName)
        assertEquals("string", valueMember.type?.displayName)
        assertTrue(getValueMember.type?.displayName?.contains("string") == true)
        assertEquals("getValue", colonItems.first().label)
        assertTrue(colonItems.first().detail?.contains("string") == true)
    }

    @Test
    fun ast_method_declarations_bind_as_methods_and_infer_callable_surface() {
        val harness = integrationHarness(
            """
            local box = {}
            ---@param self table
            ---@param value number
            ---@param label string
            function box:render(value, label)
                return label
            end
            local current = box:render(1, "hi")
            """.trimIndent()
        )

        val methodDeclaration = harness.declaration("render", DeclarationKind.METHOD)
        val methodSymbol = harness.assertSymbol("render", SymbolKind.METHOD, harness.positionOf("render", occurrence = 1))
        val methodType = harness.snapshot.model.getInferredType(methodSymbol)
        val currentType = harness.snapshot.model.getTypeAt(harness.localValue("current"))

        assertEquals("render", methodDeclaration.name)
        assertEquals("fun(self: table, value: number, label: string): string", methodType?.displayName)
        assertEquals("string", currentType?.displayName)
    }

    @Test
    fun ast_method_declarations_resolve_owning_function_for_body_inference() {
        val harness = integrationHarness(
            """
            local box = {}
            ---@param self table
            ---@param value number
            ---@param label string
            function box:render(value, label)
                return label
            end
            """.trimIndent()
        )

        val methodDeclaration = harness.declaration("render", DeclarationKind.METHOD)
        val ownedDeclarations = harness.snapshot.binder.declarationIndex.getOwnedDeclarations(DeclarationOwner.Declaration(methodDeclaration.id))

        assertEquals(listOf("value", "label"), ownedDeclarations.filter { it.kind == DeclarationKind.PARAMETER }.map { it.name })
    }

    @Test
    fun ast_method_declaration_inference_prefers_body_return_over_doc_return() {
        val harness = integrationHarness(
            """
            local box = {}
            ---@param self table
            ---@param value number
            ---@param label string
            ---@return number
            function box:render(value, label)
                return label
            end
            """.trimIndent()
        )

        val methodDeclaration = harness.declaration("render", DeclarationKind.METHOD)
        val methodSymbol = harness.assertSymbol("render", SymbolKind.METHOD, harness.positionOf("render", occurrence = 1))

        assertEquals("fun(self: table, value: number, label: string): string", harness.snapshot.model.getInferredType(methodSymbol)?.displayName)
        assertEquals("string", harness.snapshot.model.getTypeAt(harness.identifier("label", occurrence = 2))?.displayName)
        assertEquals(listOf("number"), methodDeclaration.documentation?.resolvedReturnTypes?.map { it.displayName })
    }

    @Test
    fun return_and_signature_diagnostics_flow_to_model_and_summary() {
        val harness = integrationHarness(
            """
            ---@param first string
            ---@param missing number
            ---@return string
            local function render(first)
                return 1, 2
            end
            """.trimIndent()
        )

        val checkerCodes = harness.snapshot.checker.diagnostics.mapNotNull { it.code }
        val modelDiagnostics = harness.snapshot.model.getDiagnostics()
        val modelCodes = harness.diagnosticCodes()

        assertEquals(
            setOf(
                "checker.function.signature.unknownParam",
                "checker.function.return.typeMismatch",
                "checker.function.return.extraValues"
            ),
            checkerCodes.toSet()
        )
        assertEquals(checkerCodes.toSet(), modelCodes.toSet())
        assertEquals(checkerCodes.size, modelCodes.size)
        assertEquals(harness.snapshot.result.summary.diagnosticCount, modelDiagnostics.size)
        assertEquals(harness.snapshot.result.summary.errorCount, modelDiagnostics.size)
        assertTrue(modelDiagnostics.any { it.message.contains("extra values") })
    }

    @Test
    fun doc_positions_and_completion_prerequisites() {
        val harness = integrationHarness(
            """
            ---@alias DisplayName string
            ---@class User
            ---@field id integer
            ---@method User:getName(): DisplayName
            ---@generic TValue
            ---@param item TValue
            ---@return TValue
            local function read(item)
                return item
            end

            ---@type User
            local user = {}
            local label = user:getName()
            local copy = read(label)
            """.trimIndent()
        )

        val readDeclaration = harness.declaration("read", DeclarationKind.FUNCTION)
        val ownedDeclarations = harness.snapshot.binder.declarationIndex.getOwnedDeclarations(DeclarationOwner.Declaration(readDeclaration.id))
        val functionOwnedTypeParameters = ownedDeclarations.filter { it.kind == DeclarationKind.TYPE_PARAMETER }
        val aliasSymbol = harness.assertSymbol("DisplayName", SymbolKind.TYPE_ALIAS, harness.positionOf("DisplayName", occurrence = 1))
        val classSymbol = harness.assertSymbol("User", SymbolKind.CLASS, harness.positionOf("User", occurrence = 1))
        val fieldSymbol = harness.assertSymbol("id", SymbolKind.FIELD, harness.positionOf("id"))
        val methodSymbol = harness.assertSymbol("getName", SymbolKind.METHOD, harness.positionOf("getName", occurrence = 1))
        val genericSymbol = harness.assertSymbol("TValue", SymbolKind.TYPE_ALIAS, harness.positionOf("TValue", occurrence = 1))
        val lexicalLabels = harness.snapshot.model.getCompletionsAt(harness.positionOf("return", occurrence = 2)).map { it.label }
        val memberLabels = harness.snapshot.model.getCompletionsAt(harness.positionOf("getName", occurrence = 2)).map { it.label }

        assertEquals(harness.positionOf("DisplayName", occurrence = 1), aliasSymbol.range?.start)
        assertEquals(harness.positionOf("User", occurrence = 1), classSymbol.range?.start)
        assertEquals(harness.positionOf("id"), fieldSymbol.range?.start)
        assertEquals(harness.positionOf("getName", occurrence = 1), methodSymbol.range?.start)
        assertEquals(harness.positionOf("TValue", occurrence = 1), genericSymbol.range?.start)
        assertEquals(1, functionOwnedTypeParameters.count { it.name == "TValue" })
        assertEquals("DisplayName", harness.snapshot.model.getTypeAt(harness.localValue("label"))?.displayName)
        assertEquals("TValue", harness.snapshot.model.getTypeAt(harness.localValue("copy"))?.displayName)
        assertTrue("item" in lexicalLabels)
        assertTrue("read" in lexicalLabels)
        assertTrue("getName" in memberLabels)
    }
}

private class SemanticPipelineIntegrationHarness(
    val source: String,
    val chunk: ChunkNode,
    val snapshot: io.github.dingyi222666.luaparser.semantic.SemanticPipelineSnapshot
) {
    fun positionOf(text: String, occurrence: Int = 1): Position {
        var index = -1
        repeat(occurrence) {
            index = source.indexOf(text, index + 1)
            check(index >= 0) { "Missing occurrence $occurrence of '$text'." }
        }

        var line = 1
        var column = 1
        for (i in 0 until index) {
            if (source[i] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return Position(line, column)
    }

    fun identifier(name: String, occurrence: Int = 1): Identifier {
        return nodes<Identifier>().filter { it.name == name }.elementAt(occurrence - 1)
    }

    fun localValue(name: String): ExpressionNode {
        val statement = nodes<LocalStatement>().first { local -> local.init.any { it.name == name } }
        val index = statement.init.indexOfFirst { it.name == name }
        return statement.variables[index]
    }

    fun diagnosticCodes(): List<String> = snapshot.model.getDiagnostics().mapNotNull { it.code }

    fun declaration(name: String, kind: DeclarationKind, occurrence: Int = 1): BinderDeclaration {
        return snapshot.binder.declarationIndex.declarations
            .filter { it.name == name && it.kind == kind }
            .elementAt(occurrence - 1)
    }

    fun assertSymbol(name: String, kind: SymbolKind, position: Position): Symbol {
        val symbol = assertNotNull(snapshot.model.getSymbolAt(position))
        assertEquals(name, symbol.name)
        assertEquals(kind, symbol.kind)
        return symbol
    }

    private inline fun <reified T : BaseASTNode> nodes(): List<T> {
        return buildList {
            visit(chunk) { node ->
                if (node is T) {
                    add(node)
                }
            }
        }
    }

    private fun visit(node: BaseASTNode, block: (BaseASTNode) -> Unit) {
        block(node)
        when (node) {
            is ChunkNode -> visit(node.body, block)
            is BlockNode -> {
                node.statements.forEach { visit(it, block) }
                node.returnStatement?.let { visit(it, block) }
            }

            is LocalStatement -> {
                node.init.forEach { visit(it, block) }
                node.variables.forEach { visit(it, block) }
            }

            is ReturnStatement -> node.arguments.forEach { visit(it, block) }
            is CallStatement -> visit(node.expression, block)
            is AssignmentStatement -> {
                node.init.forEach { visit(it, block) }
                node.variables.forEach { visit(it, block) }
            }

            is FunctionDeclaration -> {
                node.identifier?.let { visit(it, block) }
                node.params.forEach { visit(it, block) }
                node.body?.let { visit(it, block) }
            }

            is CallExpression -> {
                visit(node.base, block)
                node.arguments.forEach { visit(it, block) }
            }

            is MemberExpression -> {
                visit(node.base, block)
                visit(node.identifier, block)
            }

            is IndexExpression -> {
                visit(node.base, block)
                visit(node.index, block)
            }

            is BinaryExpression -> {
                node.left?.let { visit(it, block) }
                node.right?.let { visit(it, block) }
            }

            is UnaryExpression -> visit(node.arg, block)
            is TableConstructorExpression -> node.fields.forEach { visit(it, block) }
            is TableKey -> {
                runCatching { node.key }.getOrNull()?.let { visit(it, block) }
                visit(node.value, block)
            }

            is ArrayConstructorExpression -> node.values.forEach { visit(it, block) }
            is DoStatement -> visit(node.body, block)
            is WhileStatement -> {
                visit(node.condition, block)
                visit(node.body, block)
            }

            is RepeatStatement -> {
                visit(node.body, block)
                visit(node.condition, block)
            }

            is ForNumericStatement -> {
                visit(node.variable, block)
                visit(node.start, block)
                visit(node.end, block)
                node.step?.let { visit(it, block) }
                visit(node.body, block)
            }

            is ForGenericStatement -> {
                node.variables.forEach { visit(it, block) }
                node.iterators.forEach { visit(it, block) }
                visit(node.body, block)
            }

            is IfStatement -> node.causes.forEach { visit(it, block) }
            is IfClause -> {
                visit(node.condition, block)
                visit(node.body, block)
            }

            is ElseIfClause -> {
                visit(node.condition, block)
                visit(node.body, block)
            }

            is ElseClause -> visit(node.body, block)
            is SwitchStatement -> {
                visit(node.condition, block)
                node.causes.forEach { visit(it, block) }
            }

            is CaseCause -> {
                node.conditions.forEach { visit(it, block) }
                visit(node.body, block)
            }

            is DefaultCause -> visit(node.body, block)
            is WhenStatement -> {
                visit(node.condition, block)
                visit(node.ifCause, block)
                node.elseCause?.let { visit(it, block) }
            }

            is LambdaDeclaration -> {
                node.params.forEach { visit(it, block) }
                visit(node.expression, block)
            }
        }
    }
}

private fun integrationHarness(source: String): SemanticPipelineIntegrationHarness {
    val chunk = LuaParser().parse(source)
    val snapshot = SemanticPipeline().analyzeSnapshot(chunk)
    return SemanticPipelineIntegrationHarness(source, chunk, snapshot)
}
