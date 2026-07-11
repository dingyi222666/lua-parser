package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.ScopeKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Binder colon-method / implicit-self policy corpus (TASK-329).
 *
 * Acceptance:
 * - Colon-method sugar binds implicit self **per policy**.
 * - Dot methods do not invent self.
 * - Test-only.
 *
 * Product policy snapshot (DeclarationBinder / LuaParser):
 * - Parser does **not** inject a synthetic `self` AST parameter for `function obj:method()`.
 * - Explicit `self` in the parameter list is bound as a normal PARAMETER.
 * - Colon methods are bound as [DeclarationKind.METHOD]; dotted methods as METHOD with
 *   `.` indexer; bare functions as FUNCTION/GLOBAL.
 * - Therefore: colon sugar alone does **not** invent a binder PARAMETER named `self`.
 *   Call-site signature help / TypeResolver may still model a self receiver later
 *   (out of binder scope).
 *
 * Critical: [BinderPass.bind] keys [scopeGraph] and declaration anchors by AST **identity**.
 * Always bind the same [ChunkNode] used for assertions — never re-parse after bind.
 * [bindChunk] is the only helper: one parse → one bind(chunk) → return that pair.
 */
class BinderFunctionSelfParamTddTest {

    @Test
    fun colonMethodWithoutExplicitSelf_doesNotInventSelfParameter() {
        val (chunk, result) = bindChunk("function obj:render(value) return value end")
        val function = functionDecl(chunk)

        val method = result.declarationIndex.declarations.single {
            it.name == "render" && it.kind == DeclarationKind.METHOD && it.origin != DeclarationOrigin.BUILTIN
        }
        assertEquals("render", method.name)

        val member = assertIs<MemberExpression>(function.identifier)
        assertEquals(":", member.indexer)
        assertEquals("render", member.identifier.name)

        // AST params are only the written ones — no synthetic self.
        assertEquals(listOf("value"), function.params.map { it.name })
        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "self" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
            },
            "Colon method without explicit self must not invent a binder PARAMETER named self"
        )
        assertEquals(
            1,
            result.declarationIndex.declarations.count {
                it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
            }
        )
        val valueParam = result.declarationIndex.declarations.single {
            it.name == "value" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
        }
        assertNotNull(valueParam.range)
        assertEquals("value", assertIs<Identifier>(valueParam.anchorNode).name)
        assertTrue(
            function.params[0] === valueParam.anchorNode,
            "value PARAMETER anchor must be the Identifier from the bound function.params list"
        )
    }

    @Test
    fun colonMethodWithExplicitSelf_bindsSelfParameter() {
        val (chunk, result) = bindChunk("function obj:render(self, value) return self, value end")
        val function = functionDecl(chunk)

        assertEquals(listOf("self", "value"), function.params.map { it.name })
        assertEquals(2, function.params.size)

        val selfParam = result.declarationIndex.declarations.single {
            it.name == "self" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
        }
        val valueParam = result.declarationIndex.declarations.single {
            it.name == "value" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
        }
        assertNotNull(selfParam.range, "explicit self PARAMETER must have a range")
        assertNotNull(valueParam.range, "value PARAMETER must have a range")
        assertEquals("self", assertIs<Identifier>(selfParam.anchorNode).name)
        assertEquals("value", assertIs<Identifier>(valueParam.anchorNode).name)

        // Same AST instance: anchors must be the param Identifier nodes from this function.
        assertTrue(
            function.params[0] === selfParam.anchorNode,
            "self PARAMETER anchor must be function.params[0] from the same bound ChunkNode " +
                "(param@${System.identityHashCode(function.params[0])} " +
                "anchor@${System.identityHashCode(selfParam.anchorNode)})"
        )
        assertTrue(
            function.params[1] === valueParam.anchorNode,
            "value PARAMETER anchor must be function.params[1] from the same bound ChunkNode"
        )
    }

    @Test
    fun dotMethodDoesNotInventSelf() {
        val (chunk, result) = bindChunk("function obj.render(value) return value end")
        val function = functionDecl(chunk)

        val member = assertIs<MemberExpression>(function.identifier)
        assertEquals(".", member.indexer)

        assertEquals(listOf("value"), function.params.map { it.name })
        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "self" && it.origin != DeclarationOrigin.BUILTIN
            },
            "Dot method must not invent self"
        )
        assertEquals(
            1,
            result.declarationIndex.declarations.count {
                it.name == "render" && it.kind == DeclarationKind.METHOD && it.origin != DeclarationOrigin.BUILTIN
            }
        )
        val valueParam = result.declarationIndex.declarations.single {
            it.name == "value" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
        }
        assertTrue(
            function.params[0] === valueParam.anchorNode,
            "dot method value PARAMETER anchor must match bound AST param"
        )
    }

    @Test
    fun bareFunctionAndLocalFunctionDoNotInventSelf() {
        val sources = listOf(
            "function render(value) return value end",
            "local function render(value) return value end"
        )

        sources.forEach { source ->
            val (_, result) = bindChunk(source)
            assertTrue(
                result.declarationIndex.declarations.none {
                    it.name == "self" && it.origin != DeclarationOrigin.BUILTIN
                },
                "Bare/local function must not invent self for: $source"
            )
            assertEquals(
                1,
                result.declarationIndex.declarations.count {
                    it.name == "value" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
                }
            )
        }
    }

    @Test
    fun nestedColonMethod_policyMatchesTopLevel() {
        val source = """
            local t = {}
            function t:method(a)
              return a
            end
            """.trimIndent()
        val (chunk, result) = bindChunk(source)
        val function = functionDecl(chunk)
        assertEquals(":", assertIs<MemberExpression>(function.identifier).indexer)
        assertEquals(listOf("a"), function.params.map { it.name })
        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "self" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
            }
        )
        assertEquals(
            1,
            result.declarationIndex.declarations.count {
                it.name == "method" && it.kind == DeclarationKind.METHOD && it.origin != DeclarationOrigin.BUILTIN
            }
        )
        val aParam = result.declarationIndex.declarations.single {
            it.name == "a" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
        }
        assertTrue(function.params[0] === aParam.anchorNode)
    }

    @Test
    fun methodBodyScopeExistsForColonAndDotForms() {
        listOf(
            "function obj:render() end",
            "function obj.render() end"
        ).forEach { source ->
            val (chunk, result) = bindChunk(source)
            val function = functionDecl(chunk)
            val body = assertNotNull(function.body, "function body must exist for: $source")

            // Prefer identity map lookup; fall back to ownerNode scan so failures diagnose clearly.
            val scope = result.scopeGraph.getScope(body)
                ?: result.scopeGraph.scopes.firstOrNull { it.ownerNode === body }
            assertNotNull(
                scope,
                "function body FUNCTION scope must exist for same AST instance: $source " +
                    "(scopes=${result.scopeGraph.scopes.map { "${it.kind}@${System.identityHashCode(it.ownerNode)}" }})"
            )
            assertEquals(ScopeKind.FUNCTION, scope.kind, "body scope kind for: $source")
            assertTrue(
                body === scope.ownerNode,
                "scope.ownerNode must be the same BlockNode instance as function.body for: $source"
            )
        }
    }

    @Test
    fun explicitSelfOnlyWhenWrittenEvenWithExtraParams() {
        val (_, colon) = bindChunk("function o:m(x, y) end")
        assertEquals(
            listOf("x", "y"),
            colon.declarationIndex.declarations
                .filter { it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN }
                .map { it.name }
        )

        val (chunkWithSelf, withSelf) = bindChunk("function o:m(self, x, y) end")
        assertEquals(
            listOf("self", "x", "y"),
            withSelf.declarationIndex.declarations
                .filter { it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN }
                .map { it.name }
        )
        val function = functionDecl(chunkWithSelf)
        val selfParam = withSelf.declarationIndex.declarations.single {
            it.name == "self" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
        }
        assertTrue(function.params[0] === selfParam.anchorNode)
    }

    // --- helpers --------------------------------------------------------------

    private fun functionDecl(chunk: ChunkNode): FunctionDeclaration =
        chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()

    /**
     * Parse once and [BinderPass.bind] that same [ChunkNode].
     *
     * Callers that need AST identity (anchorNode / getScope / ownerNode) **must** use this
     * pair — never re-parse after bind, and never bind a different tree than the one inspected.
     */
    private fun bindChunk(source: String): Pair<ChunkNode, BinderPassResult> {
        // Fresh parser per source: no shared mutable lexer state across cases.
        val chunk = LuaParser().parse(source)
        val comments = CommentAttachPass().attach(chunk)
        // BinderPass.bind(chunk, …) — single AST only (no source re-parse path).
        val result = BinderPass().bind(chunk, comments)

        // Guard: root scope must be keyed by this chunk.body instance.
        assertTrue(
            chunk.body === result.scopeGraph.rootScope.ownerNode,
            "BinderPass root scope ownerNode must be the bound chunk.body (single-AST contract)"
        )
        return chunk to result
    }
}
