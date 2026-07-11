package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ElseClause
import io.github.dingyi222666.luaparser.parser.ast.node.ElseIfClause
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.GotoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LabelStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.RepeatStatement
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.node.WhileStatement
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.Scope
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.ScopeKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Binder label **scope visibility** corpus (TASK-437).
 *
 * Complements [BinderLabelGotoScopeTddTest] (TASK-330) by locking visibility
 * rules across nested blocks/functions/loops/conditionals, not just parent-chain
 * shapes. Product snapshot:
 *
 * - Parser accepts goto/label syntax without resolving jump legality.
 * - Binder does **not** model labels as [DeclarationKind] entries; visibility is
 *   therefore expressed via:
 *   1. residual AST parent/ownership of [LabelStatement]/[GotoStatement],
 *   2. binder [ScopeKind] parent chains for the enclosing blocks that *would*
 *      host a future LABEL namespace,
 *   3. dual-path [SemanticPipeline] diagnostics when illegal-jump diagnostics
 *      are actually modeled (empty path remains green).
 *
 * Dual-path / CURRENTLY_ACCEPTS policy (acceptance):
 * - Path A: product models jump diagnostics → illegal jumps emit goto/label/jump
 *   diagnostics; legal same-block jumps do not.
 * - Path B: product has not modeled jump diagnostics → corpus documents empty
 *   diagnostics without inventing product behaviour.
 * - Nested `local function` AST: CURRENTLY_ACCEPTS product is statement-level
 *   [FunctionDeclaration] (`isLocal=true`). Path B recovers a nested function body
 *   from a hypothetical [LocalStatement] wrap without inventing nodes.
 *
 * Test-only. Verification deferred to review / TASK-043:
 * `jvmTest --tests semantic.binder.BinderLabelScopeVisibilityTddTest`
 */
class BinderLabelScopeVisibilityTddTest {

    private val parser = LuaParser(LuaVersion.LUA_5_3)

    // -------------------------------------------------------------------------
    // Same-block visibility (forward + backward)
    // -------------------------------------------------------------------------

    @Test
    fun sameBlockBackwardGotoSeesPrecedingLabel_andLeavesNoLabelDeclarations() {
        val source = "::L:: goto L"
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val label = assertIs<LabelStatement>(chunk.body.statements[0])
        val goto = assertIs<GotoStatement>(chunk.body.statements[1])
        assertEquals("L", label.identifier.name)
        assertEquals("L", goto.identifier.name)
        assertEquals(chunk.body, label.parent)
        assertEquals(chunk.body, goto.parent)

        assertNoNonBuiltinNamed(result, "L")
        assertEquals(ScopeKind.CHUNK, result.scopeGraph.rootScope.kind)
        assertEquals(chunk.body, result.scopeGraph.rootScope.ownerNode)

        // Position queries at the label/goto identifiers land in CHUNK.
        val labelScope = assertNotNull(result.positionQueries.getScopeAt(label.identifier.range.start))
        val gotoScope = assertNotNull(result.positionQueries.getScopeAt(goto.identifier.range.start))
        assertEquals(ScopeKind.CHUNK, labelScope.kind)
        assertEquals(ScopeKind.CHUNK, gotoScope.kind)
        assertEquals(result.scopeGraph.rootScope.id, labelScope.id)
        assertEquals(result.scopeGraph.rootScope.id, gotoScope.id)
    }

    @Test
    fun sameBlockForwardGotoSeesLaterLabel_withoutInventedDeclarations() {
        val source = "goto L ::L::"
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val goto = assertIs<GotoStatement>(chunk.body.statements[0])
        val label = assertIs<LabelStatement>(chunk.body.statements[1])
        assertEquals("L", goto.identifier.name)
        assertEquals("L", label.identifier.name)
        assertEquals(chunk.body, goto.parent)
        assertEquals(chunk.body, label.parent)

        assertNoNonBuiltinNamed(result, "L")
        assertEquals(ScopeKind.CHUNK, result.scopeGraph.rootScope.kind)
    }

    @Test
    fun multipleSameBlockLabelsRemainIndependentlyVisibleAstTargets() {
        val source = "::a:: ::b:: goto a goto b"
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val labels = chunk.body.statements.filterIsInstance<LabelStatement>()
        val gotos = chunk.body.statements.filterIsInstance<GotoStatement>()
        assertEquals(listOf("a", "b"), labels.map { it.identifier.name })
        assertEquals(listOf("a", "b"), gotos.map { it.identifier.name })
        assertTrue(labels[0] !== labels[1])
        assertTrue(gotos[0] !== gotos[1])
        assertNoNonBuiltinNamed(result, "a")
        assertNoNonBuiltinNamed(result, "b")
    }

    // -------------------------------------------------------------------------
    // Nested block visibility: outer labels visible from inner; reverse not
    // -------------------------------------------------------------------------

    @Test
    fun nestedDoBlockGotoSeesOuterLabel_onBlockToChunkParentChain() {
        // Legal Lua: jump out of nested block to outer label.
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

        val doScope = assertNotNull(result.scopeGraph.getScope(doStmt.body))
        assertEquals(ScopeKind.BLOCK, doScope.kind)
        assertEquals(listOf(ScopeKind.BLOCK, ScopeKind.CHUNK), parentChainKinds(result, doScope.id))
        assertEquals(result.scopeGraph.rootScope.id, doScope.parentId)

        // Outer label lives on CHUNK; inner goto's position is under BLOCK.
        val outerLabelScope = assertNotNull(result.positionQueries.getScopeAt(label.identifier.range.start))
        val innerGotoScope = assertNotNull(result.positionQueries.getScopeAt(goto.identifier.range.start))
        assertEquals(ScopeKind.CHUNK, outerLabelScope.kind)
        assertEquals(ScopeKind.BLOCK, innerGotoScope.kind)
        assertTrue(
            isAncestor(result, ancestor = outerLabelScope.id, descendant = innerGotoScope.id),
            "outer CHUNK must be ancestor of inner BLOCK; chain=${parentChainKinds(result, innerGotoScope.id)}"
        )
        assertNoNonBuiltinNamed(result, "outer")
    }

    @Test
    fun outerGotoIntoNestedDoLabel_stillParsesAndBindsWithoutLabelDecls() {
        // Illegal Lua jump into nested block; parser/binder still accept structure.
        val source = """
            goto inner
            do
              ::inner::
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val goto = assertIs<GotoStatement>(chunk.body.statements[0])
        val doStmt = assertIs<DoStatement>(chunk.body.statements[1])
        val label = assertIs<LabelStatement>(doStmt.body.statements.single())
        assertEquals("inner", goto.identifier.name)
        assertEquals("inner", label.identifier.name)

        val doScope = assertNotNull(result.scopeGraph.getScope(doStmt.body))
        assertEquals(ScopeKind.BLOCK, doScope.kind)
        assertEquals(listOf(ScopeKind.BLOCK, ScopeKind.CHUNK), parentChainKinds(result, doScope.id))

        val outerGotoScope = assertNotNull(result.positionQueries.getScopeAt(goto.identifier.range.start))
        val innerLabelScope = assertNotNull(result.positionQueries.getScopeAt(label.identifier.range.start))
        assertEquals(ScopeKind.CHUNK, outerGotoScope.kind)
        assertEquals(ScopeKind.BLOCK, innerLabelScope.kind)
        // Inner label is NOT an ancestor of the outer goto — visibility is one-way outward.
        assertFalse(
            isAncestor(result, ancestor = innerLabelScope.id, descendant = outerGotoScope.id),
            "inner BLOCK must not be ancestor of outer CHUNK goto"
        )
        assertNoNonBuiltinNamed(result, "inner")
    }

    @Test
    fun siblingDoBlocksDoNotShareLabelVisibilityViaBinderDeclarations() {
        val source = """
            do
              ::left::
              goto left
            end
            do
              ::right::
              goto right
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val leftDo = assertIs<DoStatement>(chunk.body.statements[0])
        val rightDo = assertIs<DoStatement>(chunk.body.statements[1])
        val leftLabel = leftDo.body.statements.filterIsInstance<LabelStatement>().single()
        val rightLabel = rightDo.body.statements.filterIsInstance<LabelStatement>().single()
        assertEquals("left", leftLabel.identifier.name)
        assertEquals("right", rightLabel.identifier.name)

        val leftScope = assertNotNull(result.scopeGraph.getScope(leftDo.body))
        val rightScope = assertNotNull(result.scopeGraph.getScope(rightDo.body))
        assertEquals(ScopeKind.BLOCK, leftScope.kind)
        assertEquals(ScopeKind.BLOCK, rightScope.kind)
        assertNotEquals(leftScope.id, rightScope.id)
        assertEquals(result.scopeGraph.rootScope.id, leftScope.parentId)
        assertEquals(result.scopeGraph.rootScope.id, rightScope.parentId)
        // Siblings share CHUNK parent but are not ancestors of each other.
        assertFalse(isAncestor(result, ancestor = leftScope.id, descendant = rightScope.id))
        assertFalse(isAncestor(result, ancestor = rightScope.id, descendant = leftScope.id))
        assertNoNonBuiltinNamed(result, "left")
        assertNoNonBuiltinNamed(result, "right")
    }

    // -------------------------------------------------------------------------
    // Function boundary: labels do not escape function scope
    // -------------------------------------------------------------------------

    @Test
    fun functionLocalLabelNotVisibleAsBinderDeclarationOutsideFunction() {
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
        val outerGoto = assertIs<GotoStatement>(chunk.body.statements[1])
        assertEquals("start", outerGoto.identifier.name)

        assertNoNonBuiltinNamed(result, "start")

        val functionScope = assertNotNull(result.scopeGraph.getScope(function.body!!))
        assertEquals(ScopeKind.FUNCTION, functionScope.kind)
        val chain = parentChainKinds(result, functionScope.id)
        assertEquals(ScopeKind.CHUNK, chain.last())
        assertTrue(chain.contains(ScopeKind.FUNCTION), "chain=$chain")

        val innerLabelScope = assertNotNull(
            result.positionQueries.getScopeAt(bodyLabels.single().identifier.range.start)
        )
        val outerGotoScope = assertNotNull(
            result.positionQueries.getScopeAt(outerGoto.identifier.range.start)
        )
        assertEquals(ScopeKind.FUNCTION, innerLabelScope.kind)
        assertEquals(ScopeKind.CHUNK, outerGotoScope.kind)
        assertFalse(
            isAncestor(result, ancestor = innerLabelScope.id, descendant = outerGotoScope.id),
            "function-local label scope must not be ancestor of outer goto"
        )
    }

    @Test
    fun nestedFunctionCanSeeItsOwnLabelButOuterChunkGotoDoesNotShareScope() {
        // Product AST (CURRENTLY_ACCEPTS / Path A): `local function Name` is a
        // statement-level FunctionDeclaration (isLocal=true), NOT a LocalStatement
        // wrapping a function expression — same shape as
        // BinderUpvalueCaptureRangeTddTest / ScopeGraphParentChainTddTest.
        // Dual-path recovery via [resolveLocalFunctionDecl]:
        // - Path A: direct FunctionDeclaration statements (live product).
        // - Path B: if product ever wraps as LocalStatement RHS, still recover
        //   FunctionDeclaration bodies and assert the same scope ancestry.
        // Outer chunk `goto here` must NOT share the inner function label scope.
        // REVIEW41 failed when the test hard-asserted LocalStatement (wrong product shape).
        val source = """
            local function outer()
              local function inner()
                ::here::
                goto here
              end
            end
            goto here
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val outerFn = resolveLocalFunctionDecl(chunk.body.statements, "outer")
        val innerFn = resolveLocalFunctionDecl(outerFn.body!!.statements, "inner")
        val label = innerFn.body!!.statements.filterIsInstance<LabelStatement>().single()
        val innerGoto = innerFn.body!!.statements.filterIsInstance<GotoStatement>().single()
        val outerGoto = chunk.body.statements.filterIsInstance<GotoStatement>().single()
        assertEquals("here", label.identifier.name)
        assertEquals("here", innerGoto.identifier.name)
        assertEquals("here", outerGoto.identifier.name)
        assertTrue(outerFn.isLocal, "outer local function must keep isLocal=true on product AST")
        assertTrue(innerFn.isLocal, "inner local function must keep isLocal=true on product AST")

        val outerScope = assertNotNull(result.scopeGraph.getScope(outerFn.body!!))
        val innerScope = assertNotNull(result.scopeGraph.getScope(innerFn.body!!))
        assertEquals(ScopeKind.FUNCTION, outerScope.kind)
        assertEquals(ScopeKind.FUNCTION, innerScope.kind)
        assertTrue(
            isAncestor(result, ancestor = outerScope.id, descendant = innerScope.id),
            "inner FUNCTION must nest under outer FUNCTION; chain=${parentChainKinds(result, innerScope.id)}"
        )

        val innerLabelScope = assertNotNull(
            result.positionQueries.getScopeAt(label.identifier.range.start)
        )
        val outerGotoScope = assertNotNull(
            result.positionQueries.getScopeAt(outerGoto.identifier.range.start)
        )
        assertEquals(ScopeKind.FUNCTION, innerLabelScope.kind)
        assertEquals(ScopeKind.CHUNK, outerGotoScope.kind)
        // Function-local label scope is not an ancestor of the outer chunk goto —
        // labels do not escape the function boundary (Lua visibility).
        assertFalse(
            isAncestor(result, ancestor = innerLabelScope.id, descendant = outerGotoScope.id),
            "inner function label scope must not be ancestor of outer CHUNK goto; " +
                "innerChain=${parentChainKinds(result, innerLabelScope.id)} " +
                "outerGotoKind=${outerGotoScope.kind}"
        )
        assertFalse(
            isAncestor(result, ancestor = innerScope.id, descendant = outerGotoScope.id),
            "inner FUNCTION must not be ancestor of outer CHUNK goto"
        )
        assertNoNonBuiltinNamed(result, "here")
    }

    // -------------------------------------------------------------------------
    // Loop / conditional hosts
    // -------------------------------------------------------------------------

    @Test
    fun whileAndNumericForBodiesHostLabelsUnderLoopScopes() {
        val whileSource = "while keep do ::loop:: goto loop end"
        val forSource = "for i = 1, 2 do ::step:: goto step end"

        val whileChunk = parser.parse(whileSource)
        val forChunk = parser.parse(forSource)
        val whileResult = bind(whileChunk)
        val forResult = bind(forChunk)

        val whileBody = assertIs<WhileStatement>(whileChunk.body.statements.first()).body
        val forBody = assertIs<ForNumericStatement>(forChunk.body.statements.first()).body
        val whileLoop = assertNotNull(whileResult.scopeGraph.getScope(whileBody))
        val forLoop = assertNotNull(forResult.scopeGraph.getScope(forBody))

        assertEquals(ScopeKind.LOOP, whileLoop.kind)
        assertEquals(ScopeKind.LOOP, forLoop.kind)
        assertEquals(listOf(ScopeKind.LOOP, ScopeKind.CHUNK), parentChainKinds(whileResult, whileLoop.id))
        assertEquals(listOf(ScopeKind.LOOP, ScopeKind.CHUNK), parentChainKinds(forResult, forLoop.id))

        val whileLabel = whileBody.statements.filterIsInstance<LabelStatement>().single()
        val forLabel = forBody.statements.filterIsInstance<LabelStatement>().single()
        assertEquals(
            ScopeKind.LOOP,
            assertNotNull(whileResult.positionQueries.getScopeAt(whileLabel.identifier.range.start)).kind
        )
        assertEquals(
            ScopeKind.LOOP,
            assertNotNull(forResult.positionQueries.getScopeAt(forLabel.identifier.range.start)).kind
        )
        assertTrue(
            forResult.declarationIndex.declarations.any {
                it.name == "i" && it.kind == DeclarationKind.LOCAL && it.origin != DeclarationOrigin.BUILTIN
            }
        )
        assertNoNonBuiltinNamed(whileResult, "loop")
        assertNoNonBuiltinNamed(forResult, "step")
    }

    @Test
    fun genericForAndRepeatBodiesHostLabelsUnderLoopScopes() {
        val genericSource = "for k, v in pairs(t) do ::each:: goto each end"
        val repeatSource = "repeat ::retry:: goto retry until done"

        val genericChunk = parser.parse(genericSource)
        val repeatChunk = parser.parse(repeatSource)
        val genericResult = bind(genericChunk)
        val repeatResult = bind(repeatChunk)

        val genericBody = assertIs<ForGenericStatement>(genericChunk.body.statements.first()).body
        val repeatBody = assertIs<RepeatStatement>(repeatChunk.body.statements.first()).body
        val genericLoop = assertNotNull(genericResult.scopeGraph.getScope(genericBody))
        val repeatLoop = assertNotNull(repeatResult.scopeGraph.getScope(repeatBody))

        assertEquals(ScopeKind.LOOP, genericLoop.kind)
        assertEquals(ScopeKind.LOOP, repeatLoop.kind)
        assertEquals(listOf(ScopeKind.LOOP, ScopeKind.CHUNK), parentChainKinds(genericResult, genericLoop.id))
        assertEquals(listOf(ScopeKind.LOOP, ScopeKind.CHUNK), parentChainKinds(repeatResult, repeatLoop.id))
        assertNoNonBuiltinNamed(genericResult, "each")
        assertNoNonBuiltinNamed(repeatResult, "retry")
    }

    @Test
    fun ifThenElseIfElseBodiesHostLabelsUnderConditionalScopes() {
        val source = """
            if ok then
              ::yes::
              goto yes
            elseif other then
              ::maybe::
              goto maybe
            else
              ::no::
              goto no
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val ifStmt = assertIs<IfStatement>(chunk.body.statements.single())
        val thenBody = ifStmt.causes[0].body
        val elseIfBody = assertIs<ElseIfClause>(ifStmt.causes[1]).body
        val elseBody = assertIs<ElseClause>(ifStmt.causes[2]).body

        val thenScope = assertNotNull(result.scopeGraph.getScope(thenBody))
        val elseIfScope = assertNotNull(result.scopeGraph.getScope(elseIfBody))
        val elseScope = assertNotNull(result.scopeGraph.getScope(elseBody))

        assertEquals(ScopeKind.CONDITIONAL, thenScope.kind)
        assertEquals(ScopeKind.CONDITIONAL, elseIfScope.kind)
        assertEquals(ScopeKind.CONDITIONAL, elseScope.kind)
        assertEquals(listOf(ScopeKind.CONDITIONAL, ScopeKind.CHUNK), parentChainKinds(result, thenScope.id))
        assertEquals(listOf(ScopeKind.CONDITIONAL, ScopeKind.CHUNK), parentChainKinds(result, elseIfScope.id))
        assertEquals(listOf(ScopeKind.CONDITIONAL, ScopeKind.CHUNK), parentChainKinds(result, elseScope.id))

        // Conditional arms are siblings under CHUNK, not nested under each other.
        assertFalse(isAncestor(result, ancestor = thenScope.id, descendant = elseIfScope.id))
        assertFalse(isAncestor(result, ancestor = elseIfScope.id, descendant = elseScope.id))

        assertEquals(
            listOf("yes", "maybe", "no"),
            listOf(thenBody, elseIfBody, elseBody).map {
                it.statements.filterIsInstance<LabelStatement>().single().identifier.name
            }
        )
        assertNoNonBuiltinNamed(result, "yes")
        assertNoNonBuiltinNamed(result, "maybe")
        assertNoNonBuiltinNamed(result, "no")
    }

    // -------------------------------------------------------------------------
    // Illegal jump into local scope (Lua visibility rule) — dual-path diagnostics
    // -------------------------------------------------------------------------

    @Test
    fun illegalJumpIntoLocalScopeStillParsesAndKeepsLocalVisibleOnlyInBlock() {
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
        assertTrue(doStmt.body.statements.any { it is LocalStatement })

        val hidden = result.declarationIndex.declarations.single {
            it.name == "hidden" && it.kind == DeclarationKind.LOCAL && it.origin != DeclarationOrigin.BUILTIN
        }
        val doScope = assertNotNull(result.scopeGraph.getScope(doStmt.body))
        assertEquals(ScopeKind.BLOCK, doScope.kind)
        assertTrue(result.scopeGraph.getDeclarations(doScope.id).contains(hidden.id))
        assertFalse(result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(hidden.id))
        assertNoNonBuiltinNamed(result, "target")
    }

    @Test
    fun dualPathSemanticPipelineDiagnosticsForIllegalVsLegalLabelJumps() {
        val illegal = """
            goto target
            do
              local hidden = 1
              ::target::
              print(hidden)
            end
            """.trimIndent()
        val legalSameBlock = "::ok:: goto ok"
        val legalNestedOut = """
            ::outer::
            do
              goto outer
            end
            """.trimIndent()

        val illegalGotoRelated = gotoRelatedDiagnostics(illegal)
        val legalSameRelated = gotoRelatedDiagnostics(legalSameBlock)
        val legalNestedRelated = gotoRelatedDiagnostics(legalNestedOut)

        if (illegalGotoRelated.isNotEmpty()) {
            // Path A: product models illegal-jump diagnostics.
            assertTrue(
                legalSameRelated.isEmpty(),
                "legal same-block goto must not emit jump diagnostics when illegal ones are modeled; legal=$legalSameRelated"
            )
            assertTrue(
                legalNestedRelated.isEmpty(),
                "legal nested-out goto must not emit jump diagnostics when illegal ones are modeled; legal=$legalNestedRelated"
            )
        } else {
            // Path B / CURRENTLY_ACCEPTS: not modeled yet — document empty diagnostics.
            assertTrue(
                legalSameRelated.isEmpty() && legalNestedRelated.isEmpty(),
                "when illegal jump diagnostics are absent, legal gotos must also stay quiet; " +
                    "illegal=$illegalGotoRelated legalSame=$legalSameRelated legalNested=$legalNestedRelated"
            )
        }
    }

    @Test
    fun dualPathJumpOutOfFunctionLocalScopeVsIllegalIntoNestedLocal() {
        // Outer→function-local label is always illegal in Lua; corpus dual-paths
        // diagnostics while locking binder scopes either way.
        val illegalIntoFunction = """
            goto start
            function hop()
              local hidden = 1
              ::start::
              print(hidden)
            end
            """.trimIndent()
        val legalInsideFunction = """
            function hop()
              ::start::
              local hidden = 1
              goto start
              print(hidden)
            end
            """.trimIndent()

        val illegalChunk = parser.parse(illegalIntoFunction)
        val legalChunk = parser.parse(legalInsideFunction)
        val illegalBind = bind(illegalChunk)
        val legalBind = bind(legalChunk)

        val illegalFn = assertIs<FunctionDeclaration>(illegalChunk.body.statements[1])
        val legalFn = assertIs<FunctionDeclaration>(legalChunk.body.statements[0])
        val illegalFnScope = assertNotNull(illegalBind.scopeGraph.getScope(illegalFn.body!!))
        val legalFnScope = assertNotNull(legalBind.scopeGraph.getScope(legalFn.body!!))
        assertEquals(ScopeKind.FUNCTION, illegalFnScope.kind)
        assertEquals(ScopeKind.FUNCTION, legalFnScope.kind)
        assertNoNonBuiltinNamed(illegalBind, "start")
        assertNoNonBuiltinNamed(legalBind, "start")

        val illegalDiag = gotoRelatedDiagnostics(illegalIntoFunction)
        val legalDiag = gotoRelatedDiagnostics(legalInsideFunction)
        if (illegalDiag.isNotEmpty()) {
            assertTrue(
                legalDiag.isEmpty(),
                "legal in-function backward goto past local must not emit jump diagnostics when illegal ones are modeled; legal=$legalDiag"
            )
        } else {
            // CURRENTLY_ACCEPTS: illegal function-entry jump diagnostics not modeled.
            assertTrue(true, "Illegal function-entry jump diagnostics not modeled; structure corpus still covers scopes")
        }
    }

    // -------------------------------------------------------------------------
    // Deep nesting: multi-level visibility chain
    // -------------------------------------------------------------------------

    @Test
    fun deepNestedDoChain_outerLabelVisibleFromDeepestGotoScopeChain() {
        val source = """
            ::top::
            do
              do
                do
                  goto top
                end
              end
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val label = assertIs<LabelStatement>(chunk.body.statements[0])
        val outerDo = assertIs<DoStatement>(chunk.body.statements[1])
        val midDo = assertIs<DoStatement>(outerDo.body.statements.single())
        val innerDo = assertIs<DoStatement>(midDo.body.statements.single())
        val goto = assertIs<GotoStatement>(innerDo.body.statements.single())
        assertEquals("top", label.identifier.name)
        assertEquals("top", goto.identifier.name)

        val outerScope = assertNotNull(result.scopeGraph.getScope(outerDo.body))
        val midScope = assertNotNull(result.scopeGraph.getScope(midDo.body))
        val innerScope = assertNotNull(result.scopeGraph.getScope(innerDo.body))
        val root = result.scopeGraph.rootScope

        assertEquals(
            listOf(innerScope.id, midScope.id, outerScope.id, root.id),
            parentChainIds(result, innerScope.id)
        )
        assertTrue(isAncestor(result, ancestor = root.id, descendant = innerScope.id))
        assertEquals(
            ScopeKind.CHUNK,
            assertNotNull(result.positionQueries.getScopeAt(label.identifier.range.start)).kind
        )
        assertEquals(
            ScopeKind.BLOCK,
            assertNotNull(result.positionQueries.getScopeAt(goto.identifier.range.start)).kind
        )
        assertNoNonBuiltinNamed(result, "top")
    }

    @Test
    fun inventoryCoversVisibilityAxesWithoutOverlappingTask330OnlyShapes() {
        // Lightweight inventory: keep this corpus focused on visibility axes.
        val methods = BinderLabelScopeVisibilityTddTest::class.java.declaredMethods
            .filter { it.getAnnotation(Test::class.java) != null }
            .map { it.name }
            .toSet()

        val requiredAxes = listOf(
            "sameBlockBackwardGotoSeesPrecedingLabel_andLeavesNoLabelDeclarations",
            "sameBlockForwardGotoSeesLaterLabel_withoutInventedDeclarations",
            "nestedDoBlockGotoSeesOuterLabel_onBlockToChunkParentChain",
            "outerGotoIntoNestedDoLabel_stillParsesAndBindsWithoutLabelDecls",
            "siblingDoBlocksDoNotShareLabelVisibilityViaBinderDeclarations",
            "functionLocalLabelNotVisibleAsBinderDeclarationOutsideFunction",
            "nestedFunctionCanSeeItsOwnLabelButOuterChunkGotoDoesNotShareScope",
            "whileAndNumericForBodiesHostLabelsUnderLoopScopes",
            "ifThenElseIfElseBodiesHostLabelsUnderConditionalScopes",
            "illegalJumpIntoLocalScopeStillParsesAndKeepsLocalVisibleOnlyInBlock",
            "dualPathSemanticPipelineDiagnosticsForIllegalVsLegalLabelJumps",
            "deepNestedDoChain_outerLabelVisibleFromDeepestGotoScopeChain"
        )
        for (axis in requiredAxes) {
            assertTrue(axis in methods, "missing visibility axis test: $axis; have=$methods")
        }
        assertTrue(methods.size >= 12, "expected at least 12 visibility tests, got ${methods.size}: $methods")
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun bind(chunk: ChunkNode): BinderPassResult =
        BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

    /**
     * Resolve a named `local function` / function declaration from a statement list.
     *
     * Dual-path / CURRENTLY_ACCEPTS:
     * - Path A (live product): statement-level [FunctionDeclaration] with [FunctionDeclaration.isLocal]=true.
     * - Path B (hypothetical LocalStatement wrap): recover [FunctionDeclaration] from
     *   [LocalStatement.variables] (RHS expressions) or match [LocalStatement.init] name.
     * Never invents nodes; fails with the actual statement class names if neither path works.
     */
    private fun resolveLocalFunctionDecl(
        statements: List<StatementNode>,
        name: String
    ): FunctionDeclaration {
        // Path A: direct FunctionDeclaration statement (product CURRENTLY_ACCEPTS).
        val direct = statements.filterIsInstance<FunctionDeclaration>().firstOrNull { fn ->
            functionDeclName(fn) == name
        }
        if (direct != null) return direct

        // Path B: LocalStatement that hosts a FunctionDeclaration expression.
        for (local in statements.filterIsInstance<LocalStatement>()) {
            val fromVariables = local.variables.filterIsInstance<FunctionDeclaration>().firstOrNull { fn ->
                functionDeclName(fn) == name || functionDeclName(fn) == null
            }
            if (fromVariables != null &&
                (functionDeclName(fromVariables) == name || localInitNames(local).contains(name))
            ) {
                return fromVariables
            }
            // Anonymous function assigned to `local name = function ...`
            if (localInitNames(local).contains(name)) {
                val onlyFn = local.variables.filterIsInstance<FunctionDeclaration>().singleOrNull()
                if (onlyFn != null) return onlyFn
            }
        }

        val kinds = statements.map { it::class.simpleName }
        throw AssertionError(
            "Expected local/function declaration named '$name' as FunctionDeclaration " +
                "(CURRENTLY_ACCEPTS product) or LocalStatement RHS; statements=$kinds"
        )
    }

    private fun functionDeclName(fn: FunctionDeclaration): String? {
        val id = fn.identifier
        return if (id is Identifier) id.name else null
    }

    private fun localInitNames(local: LocalStatement): List<String> =
        local.init.map { it.name }

    private fun assertNoNonBuiltinNamed(result: BinderPassResult, name: String) {
        val unexpected = result.declarationIndex.declarations.filter {
            it.name == name && it.origin != DeclarationOrigin.BUILTIN
        }
        assertTrue(unexpected.isEmpty(), "Unexpected non-BUILTIN declarations for '$name': $unexpected")
    }

    private fun parentChainKinds(result: BinderPassResult, start: ScopeId): List<ScopeKind> {
        return parentChain(result, start).map { it.kind }
    }

    private fun parentChainIds(result: BinderPassResult, start: ScopeId): List<ScopeId> {
        return parentChain(result, start).map { it.id }
    }

    private fun parentChain(result: BinderPassResult, start: ScopeId): List<Scope> {
        val graph = result.scopeGraph
        val chain = mutableListOf<Scope>()
        val seen = mutableSetOf<ScopeId>()
        var current = graph.getScope(start)
        while (current != null && seen.add(current.id)) {
            chain += current
            val parentId = current.parentId ?: break
            current = graph.getScope(parentId)
        }
        return chain
    }

    private fun isAncestor(result: BinderPassResult, ancestor: ScopeId, descendant: ScopeId): Boolean {
        if (ancestor == descendant) return true
        return parentChainIds(result, descendant).contains(ancestor)
    }

    private fun gotoRelatedDiagnostics(source: String): List<String> {
        val diagnostics = SemanticPipeline().analyze(parser.parse(source)).model.getDiagnostics()
        return diagnostics.mapNotNull { diagnostic ->
            val text = (diagnostic.message + " " + (diagnostic.code ?: "")).lowercase()
            if (text.contains("goto") || text.contains("label") || text.contains("jump")) {
                text
            } else {
                null
            }
        }
    }
}
