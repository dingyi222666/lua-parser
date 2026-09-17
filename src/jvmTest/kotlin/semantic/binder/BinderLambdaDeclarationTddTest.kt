package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.LambdaDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.ScopeKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Binder lambda declaration corpus (TDD):
 *
 * A lambda expression (`local fn = lambda value: value * 2`) introduces a FUNCTION scope
 * and binds each parameter as a PARAMETER declaration, mirroring how function
 * declarations bind parameters. Hovering / getSymbolAt on the lambda's parameter must
 * resolve to that parameter instead of falling through to an outer same-name local.
 */
class BinderLambdaDeclarationTddTest {

    @Test
    fun lambdaParameterBindsAsParameterDeclarationInsideFunctionScope() {
        val source = """
            local value = 1
            local fn = lambda value: value * 2
        """.trimIndent()
        val (chunk, result) = bindChunk(source)

        val lambda = assertIsLambdaInitializer(chunk)

        val parameterDeclarations = result.declarationIndex.declarations.filter {
            it.name == "value" && it.kind == DeclarationKind.PARAMETER &&
                it.origin != DeclarationOrigin.BUILTIN
        }
        assertEquals(
            1,
            parameterDeclarations.size,
            "lambda parameter must bind as a PARAMETER declaration; " +
                "value declarations=${result.declarationIndex.declarations
                    .filter { it.name == "value" }.map { it.kind }}"
        )
        val parameterDeclaration = parameterDeclarations.single()
        assertTrue(
            parameterDeclaration.anchorNode === lambda.params.single(),
            "PARAMETER anchor must be the lambda's own parameter Identifier"
        )

        // The lambda owns a FUNCTION scope holding the parameter declaration.
        val lambdaScope = assertNotNull(
            result.scopeGraph.scopes.firstOrNull { it.ownerNode === lambda },
            "lambda must introduce a scope owned by its LambdaDeclaration node; " +
                "scopes=${result.scopeGraph.scopes.map { it.kind }}"
        )
        assertEquals(ScopeKind.FUNCTION, lambdaScope.kind)
        assertTrue(
            parameterDeclaration.id in lambdaScope.declarationIds,
            "lambda FUNCTION scope must hold the parameter declaration"
        )
    }

    @Test
    fun lambdaParameterResolvesByHoverInsteadOfOuterLocal() {
        val source = """
            local value = 1
            local fn = lambda value: value * 2
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        // Occurrence 2 of `value` is the lambda parameter (occurrence 1 is `local value`).
        val symbol = assertNotNull(
            model.getSymbolAt(positionOf(source, "value", occurrence = 2)),
            "hover on the lambda parameter must resolve"
        )
        assertEquals("value", symbol.name)
        assertEquals(
            SymbolKind.PARAMETER,
            symbol.kind,
            "hover on the lambda parameter must surface the parameter, not the outer local"
        )
    }

    @Test
    fun lambdaBodyUsageResolvesToParameter() {
        val source = """
            local value = 1
            local fn = lambda value: value * 2
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        // Occurrence 3 of `value` is the body usage inside the lambda expression.
        val symbol = assertNotNull(
            model.getSymbolAt(positionOf(source, "value", occurrence = 3)),
            "lambda body usage must resolve inside the lambda FUNCTION scope"
        )
        assertEquals(
            SymbolKind.PARAMETER,
            symbol.kind,
            "lambda body `value` must resolve to the parameter, not the outer local"
        )
    }

    // --- helpers ---------------------------------------------------------------

    private fun assertIsLambdaInitializer(chunk: ChunkNode): LambdaDeclaration {
        val localStatement = chunk.body.statements.filterIsInstance<LocalStatement>().single {
            it.variables.singleOrNull() is LambdaDeclaration
        }
        return assertIsLambda(localStatement.variables.single())
    }

    private fun assertIsLambda(node: io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode): LambdaDeclaration =
        node as? LambdaDeclaration ?: error("Expected LambdaDeclaration, got $node")

    /**
     * Parse once and [BinderPass.bind] that same [ChunkNode] (single-AST contract).
     */
    private fun bindChunk(source: String): Pair<ChunkNode, BinderPassResult> {
        val chunk = LuaParser().parse(source)
        val comments = CommentAttachPass().attach(chunk)
        val result = BinderPass().bind(chunk, comments)

        assertTrue(
            chunk.body === result.scopeGraph.rootScope.ownerNode,
            "BinderPass root scope ownerNode must be the bound chunk.body (single-AST contract)"
        )
        return chunk to result
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var fromIndex = 0
        var matchIndex = -1
        repeat(occurrence) {
            matchIndex = source.indexOf(needle, fromIndex)
            require(matchIndex >= 0) { "Needle '$needle' occurrence $occurrence not found" }
            fromIndex = matchIndex + needle.length
        }
        val lineStart = source.lastIndexOf('\n', startIndex = matchIndex).let { if (it < 0) 0 else it + 1 }
        val line = source.substring(0, matchIndex).count { it == '\n' } + 1
        return Position(line, matchIndex - lineStart + 1)
    }
}
