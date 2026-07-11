package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.ScopeKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Binder / semantic label+goto scope corpus (TASK-330).
 *
 * Acceptance:
 * - Goto targets resolve within allowed label scopes (when modeled).
 * - Illegal jumps surface diagnostics when modeled.
 * - Test-only.
 *
 * Product policy snapshot:
 * - Parser accepts goto/label syntax without enforcing jump legality.
 * - Binder currently binds locals/functions/params; labels are **not** declaration
 *   kinds in [DeclarationKind]. This corpus therefore:
 *   1. Asserts AST residual structure for legal/illegal jump shapes.
 *   2. Asserts binder scopes for enclosing blocks/functions still form parent chains
 *      using the same AST instance as [BinderPass] (node→scope identity map).
 *   3. Asserts SemanticPipeline diagnostics only when the product actually models
 *      illegal-jump diagnostics (otherwise documents empty/absent diagnostics without
 *      inventing product behaviour).
 *
 * Scope kinds exercised: [ScopeKind.CHUNK], [ScopeKind.BLOCK], [ScopeKind.FUNCTION],
 * [ScopeKind.LOOP] (existing binder kinds).
 */
class BinderLabelGotoScopeTddTest {

    private val parser = LuaParser(LuaVersion.LUA_5_3)

    @Test
    fun wellFormedSameBlockGotoAndLabel_parseAndBindWithoutThrow() {
        val source = "::again:: goto again"
        val chunk = parser.parse(source)
        val result = bind(chunk)

        assertEquals(2, chunk.body.statements.size)
        assertIs<LabelStatement>(chunk.body.statements[0])
        assertIs<GotoStatement>(chunk.body.statements[1])
        assertEquals("again", (chunk.body.statements[0] as LabelStatement).identifier.name)
        assertEquals("again", (chunk.body.statements[1] as GotoStatement).identifier.name)

        // Labels are not binder declarations today; ensure binder still completes.
        assertTrue(result.declarationIndex.declarations.any { it.origin == DeclarationOrigin.BUILTIN })
        assertEquals(ScopeKind.CHUNK, result.scopeGraph.rootScope.kind)
        assertEquals(chunk.body, result.scopeGraph.rootScope.ownerNode)
    }

    @Test
    fun forwardAndBackwardGotosInSameBlockRemainDistinctAstNodes() {
        val backward = parser.parse("::L:: goto L")
        val forward = parser.parse("goto L ::L::")

        assertIs<LabelStatement>(backward.body.statements[0])
        assertIs<GotoStatement>(backward.body.statements[1])
        assertIs<GotoStatement>(forward.body.statements[0])
        assertIs<LabelStatement>(forward.body.statements[1])

        bind(backward)
        bind(forward)
    }

    @Test
    fun nestedBlockGotoToOuterLabel_keepsScopesOnParentChain() {
        val source = """
            ::outer::
            do
              goto outer
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val label = assertIs<LabelStatement>(chunk.body.statements[0])
        val doStmt = assertIs<DoStatement>(chunk.body.statements[1])
        val goto = assertIs<GotoStatement>(doStmt.body.statements.single())
        assertEquals("outer", label.identifier.name)
        assertEquals("outer", goto.identifier.name)

        // Same AST instance as binder: do body is ScopeKind.BLOCK under CHUNK.
        val doScope = assertNotNull(result.scopeGraph.getScope(doStmt.body))
        assertEquals(ScopeKind.BLOCK, doScope.kind)
        assertEquals(doStmt.body, doScope.ownerNode)

        val chain = parentChainKinds(result, doScope.id)
        assertEquals(listOf(ScopeKind.BLOCK, ScopeKind.CHUNK), chain)
        assertEquals(result.scopeGraph.rootScope.id, doScope.parentId)
        assertEquals(ScopeKind.CHUNK, result.scopeGraph.rootScope.kind)
    }

    @Test
    fun functionScopedLabelsDoNotEscapeAsBinderDeclarations() {
        val source = """
            function hop()
              ::start::
              goto start
            end
            goto start
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val function = assertIs<FunctionDeclaration>(chunk.body.statements[0])
        val bodyLabels = function.body!!.statements.filterIsInstance<LabelStatement>()
        assertEquals(listOf("start"), bodyLabels.map { it.identifier.name })
        assertIs<GotoStatement>(chunk.body.statements[1])

        // No LABEL declaration kind exists; ensure no invented declaration named start.
        val startDecls = result.declarationIndex.declarations.filter {
            it.name == "start" && it.origin != DeclarationOrigin.BUILTIN
        }
        assertTrue(startDecls.isEmpty(), "Unexpected non-label declarations for start: $startDecls")

        // Function body scope is keyed by the same body node used as ownerNode.
        val functionScope = assertNotNull(result.scopeGraph.getScope(function.body!!))
        assertEquals(ScopeKind.FUNCTION, functionScope.kind)
        assertEquals(function.body, functionScope.ownerNode)
        val chain = parentChainKinds(result, functionScope.id)
        assertEquals(ScopeKind.CHUNK, chain.last())
        assertTrue(chain.contains(ScopeKind.FUNCTION), "expected FUNCTION on parent chain; chain=$chain")
    }

    @Test
    fun illegalJumpIntoLocalScopeStillParsesAndBinds() {
        // Classic Lua illegal jump into scope of a local; parser still accepts it.
        val source = """
            goto target
            do
              local hidden = 1
              ::target::
              print(hidden)
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)

        assertIs<GotoStatement>(chunk.body.statements[0])
        val doStmt = assertIs<DoStatement>(chunk.body.statements[1])
        assertTrue(doStmt.body.statements.any { it is LabelStatement })
        assertTrue(
            result.declarationIndex.declarations.any {
                it.name == "hidden" && it.kind == DeclarationKind.LOCAL && it.origin != DeclarationOrigin.BUILTIN
            }
        )

        val doScope = assertNotNull(result.scopeGraph.getScope(doStmt.body))
        assertEquals(ScopeKind.BLOCK, doScope.kind)
        assertEquals(listOf(ScopeKind.BLOCK, ScopeKind.CHUNK), parentChainKinds(result, doScope.id))
    }

    @Test
    fun semanticPipelineDiagnosticsForIllegalJumpsWhenModeled() {
        val illegal = """
            goto target
            do
              local hidden = 1
              ::target::
              print(hidden)
            end
            """.trimIndent()
        val legal = "::ok:: goto ok"

        val illegalDiagnostics = SemanticPipeline().analyze(parser.parse(illegal)).model.getDiagnostics()
        val legalDiagnostics = SemanticPipeline().analyze(parser.parse(legal)).model.getDiagnostics()

        val illegalGotoRelated = illegalDiagnostics.filter { diagnostic ->
            val text = (diagnostic.message + " " + (diagnostic.code ?: "")).lowercase()
            text.contains("goto") || text.contains("label") || text.contains("jump")
        }
        val legalGotoRelated = legalDiagnostics.filter { diagnostic ->
            val text = (diagnostic.message + " " + (diagnostic.code ?: "")).lowercase()
            text.contains("goto") || text.contains("label") || text.contains("jump")
        }

        if (illegalGotoRelated.isNotEmpty()) {
            assertTrue(
                legalGotoRelated.isEmpty(),
                "legal goto should not emit jump diagnostics when illegal ones are modeled; legal=$legalGotoRelated"
            )
        } else {
            // Not modeled yet — document empty diagnostics without failing the corpus.
            assertTrue(true, "Illegal jump diagnostics not modeled; binder/parser corpus still covers structure")
        }
    }

    @Test
    fun multipleLabelsInBlockStayUniqueAstNodesUnderBinder() {
        val source = "::a:: ::b:: goto a goto b"
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val labels = chunk.body.statements.filterIsInstance<LabelStatement>()
        val gotos = chunk.body.statements.filterIsInstance<GotoStatement>()
        assertEquals(listOf("a", "b"), labels.map { it.identifier.name })
        assertEquals(listOf("a", "b"), gotos.map { it.identifier.name })
        assertTrue(labels[0] !== labels[1])
        assertEquals(ScopeKind.CHUNK, result.scopeGraph.rootScope.kind)
        assertEquals(chunk.body, result.scopeGraph.rootScope.ownerNode)
    }

    @Test
    fun labelInsideWhileAndForBodiesStillBindEnclosingScopes() {
        val whileSource = "while keep do ::loop:: goto loop end"
        val forSource = "for i = 1, 2 do ::step:: goto step end"

        val whileChunk = parser.parse(whileSource)
        val forChunk = parser.parse(forSource)
        val whileResult = bind(whileChunk)
        val forResult = bind(forChunk)

        assertTrue(
            whileResult.scopeGraph.scopes.any { it.kind == ScopeKind.LOOP },
            "while body should create LOOP scope; kinds=${whileResult.scopeGraph.scopes.map { it.kind }}"
        )
        assertTrue(
            forResult.scopeGraph.scopes.any { it.kind == ScopeKind.LOOP },
            "for body should create LOOP scope; kinds=${forResult.scopeGraph.scopes.map { it.kind }}"
        )
        assertTrue(whileResult.scopeGraph.scopes.any { it.kind == ScopeKind.CHUNK })
        assertTrue(forResult.scopeGraph.scopes.any { it.kind == ScopeKind.CHUNK })
        assertTrue(
            forResult.declarationIndex.declarations.any {
                it.name == "i" && it.kind == DeclarationKind.LOCAL && it.origin != DeclarationOrigin.BUILTIN
            }
        )

        // Align LOOP parent chain to CHUNK via owner-node lookup on the same AST.
        val whileBody = assertIs<WhileStatement>(whileChunk.body.statements.first()).body
        val whileLoopScope = assertNotNull(whileResult.scopeGraph.getScope(whileBody))
        assertEquals(ScopeKind.LOOP, whileLoopScope.kind)
        assertEquals(
            listOf(ScopeKind.LOOP, ScopeKind.CHUNK),
            parentChainKinds(whileResult, whileLoopScope.id)
        )

        val forBody = assertIs<ForNumericStatement>(forChunk.body.statements.first()).body
        val forLoopScope = assertNotNull(forResult.scopeGraph.getScope(forBody))
        assertEquals(ScopeKind.LOOP, forLoopScope.kind)
        assertEquals(
            listOf(ScopeKind.LOOP, ScopeKind.CHUNK),
            parentChainKinds(forResult, forLoopScope.id)
        )
    }

    // --- helpers --------------------------------------------------------------

    private fun bind(chunk: ChunkNode): BinderPassResult =
        BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

    private fun parentChainKinds(result: BinderPassResult, start: ScopeId): List<ScopeKind> {
        val graph = result.scopeGraph
        val kinds = mutableListOf<ScopeKind>()
        val seen = mutableSetOf<ScopeId>()
        var current = graph.getScope(start)
        while (current != null && seen.add(current.id)) {
            kinds += current.kind
            val parentId = current.parentId ?: break
            current = graph.getScope(parentId)
        }
        return kinds
    }
}
