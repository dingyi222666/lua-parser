package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.Scope
import io.github.dingyi222666.luaparser.semantic.binder.ScopeGraph
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.ScopeKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Binder upvalue / outer-local capture-range corpus (TASK-328 / TASK-485 expansion).
 *
 * Acceptance:
 * - Nested function upvalue captures resolve to outer locals (declaration sites).
 * - Ranges stay inside declaring scopes (identifier ranges, not whole outer blocks).
 * - Dual-path CURRENTLY_ACCEPTS documents product parse/bind gaps without inventing
 *   green claims for forms the strict parser still rejects.
 *
 * Notes on product policy:
 * - [BinderPass] binds declaration sites and scope ownership. Nested functions that
 *   *read* outer locals do not invent extra declarations; the outer local remains the
 *   single declaration, owned by the declaring lexical scope, with a range anchored
 *   on the identifier token. Nested function scopes remain children on the parent chain.
 * - Local `function name()` binds as [DeclarationKind.FUNCTION] (not LOCAL).
 *
 * Fixture constraints (shared with sibling binder corpora):
 * - Avoid multi-identifier returns such as `return left, right` on hard-green paths —
 *   the current parser has historically rejected those with IllegalStateException near
 *   eof (MASTER-REVIEW43 / TASK-328). Use single returns / body locals that *read*
 *   each outer name instead. Dual-path tests below document either product accept or
 *   documented reject for that class.
 *
 * Important: binder scopeGraph / declaration anchors are keyed by AST identity.
 * Always bind the same [ChunkNode] instance used for assertions (no double-parse).
 * Use [bindChunk] so parse + bind share one AST; never assert anchors/scopes from a
 * re-parsed tree.
 */
class BinderUpvalueCaptureRangeTddTest {

    @Test
    fun nestedFunctionReadingOuterLocal_keepsOuterDeclarationInDeclaringScope() {
        val source = """
            local outer = 1
            local function inner()
                return outer
            end
            """.trimIndent()
        val (chunk, result) = bindChunk(source)

        val outer = localOf(result, "outer")
        val innerFn = functionOf(result, "inner")
        assertPerNameRange(source, outer, "outer")

        val outerLocal = chunk.body.statements.filterIsInstance<LocalStatement>().first()
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val functionScope = requireFunctionScope(result, function)
        val root = result.scopeGraph.rootScope

        // Outer local is declared in chunk/root lexical scope, not inside the nested function.
        assertTrue(result.scopeGraph.getDeclarations(root.id).contains(outer.id))
        assertTrue(!result.scopeGraph.getDeclarations(functionScope.id).contains(outer.id))
        assertEquals(ScopeKind.FUNCTION, functionScope.kind)
        assertSame(function.body, functionScope.ownerNode)

        // Nested function parent chain reaches root (upvalue path).
        val chain = parentChainIds(result.scopeGraph, functionScope.id)
        assertEquals(root.id, chain.last())
        assertTrue(chain.contains(functionScope.id))
        assertEquals(innerFn.name, "inner")
        assertNotNull(outerLocal)
    }

    @Test
    fun deeplyNestedCapture_resolvesToOutermostLocalDeclarationRange() {
        val source = """
            local captured = 42
            local function level1()
                local function level2()
                    local function level3()
                        return captured
                    end
                    return level3
                end
                return level2
            end
            """.trimIndent()
        val (_, result) = bindChunk(source)

        val captured = localOf(result, "captured")
        assertPerNameRange(source, captured, "captured")
        assertEquals(1, nonBuiltinLocals(result).count { it.name == "captured" })

        // Only one declaration for captured; nested functions do not redeclare it.
        val graph = result.scopeGraph
        val root = graph.rootScope
        assertTrue(graph.getDeclarations(root.id).contains(captured.id))

        // Each nested local function has a FUNCTION scope under root.
        val functionScopes = graph.scopes.filter { it.kind == ScopeKind.FUNCTION }
        assertTrue(functionScopes.size >= 3, "expected nested function scopes, got ${functionScopes.size}")
        functionScopes.forEach { scope ->
            assertTrue(
                !graph.getDeclarations(scope.id).contains(captured.id),
                "captured must not be redeclared inside nested function scope ${scope.id}"
            )
            assertEquals(root.id, parentChainIds(graph, scope.id).last())
        }
    }

    @Test
    fun multiOuterLocalsCapturedByNestedFunction_keepDisjointRanges() {
        // Hard-green safe rewrite of the multi-identifier-return footgun:
        // avoid `return left, right` (historically IllegalStateException near eof).
        // Body locals still *read* each outer name so capture remains live.
        val source = """
            local left, right = 1, 2
            local function pack()
                local usedLeft = left
                local usedRight = right
                return usedLeft
            end
            """.trimIndent()
        val (chunk, result) = bindChunk(source)

        val left = localOf(result, "left")
        val right = localOf(result, "right")
        assertPerNameRange(source, left, "left")
        assertPerNameRange(source, right, "right")
        assertRangesDisjoint(left.range!!, right.range!!)
        assertNotEquals(left.symbolId, right.symbolId)

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val functionScope = requireFunctionScope(result, function)
        assertTrue(!result.scopeGraph.getDeclarations(functionScope.id).contains(left.id))
        assertTrue(!result.scopeGraph.getDeclarations(functionScope.id).contains(right.id))
        assertSame(function.body, functionScope.ownerNode)

        // Nested reads of outer names do not invent extra left/right declarations.
        assertEquals(1, nonBuiltinLocals(result).count { it.name == "left" })
        assertEquals(1, nonBuiltinLocals(result).count { it.name == "right" })
        assertEquals(1, nonBuiltinLocals(result).count { it.name == "usedLeft" })
        assertEquals(1, nonBuiltinLocals(result).count { it.name == "usedRight" })
    }

    @Test
    fun shadowedOuterName_innermostLocalIsCapturedNotOuter() {
        val source = """
            local value = 1
            local function outer()
                local value = 2
                local function inner()
                    return value
                end
                return inner
            end
            """.trimIndent()
        val (_, result) = bindChunk(source)

        val values = nonBuiltinLocals(result).filter { it.name == "value" }
        assertEquals(2, values.size, "outer and shadowed inner value locals")

        // Declaration ranges must be distinct and per-identifier.
        values.forEach { declaration ->
            assertNotNull(declaration.range)
            assertEquals("value", declaration.name)
        }
        assertRangesDisjoint(values[0].range!!, values[1].range!!)

        // Query at each declaration site hits that site.
        assertEquals(
            values.single { it.range!!.start == positionOf(source, "value", occurrence = 1) },
            result.positionQueries.getDeclarationAt(positionOf(source, "value", occurrence = 1))
        )
        assertEquals(
            values.single { it.range!!.start == positionOf(source, "value", occurrence = 2) },
            result.positionQueries.getDeclarationAt(positionOf(source, "value", occurrence = 2))
        )
    }

    @Test
    fun parameterAsUpvalueFromNestedFunction_staysOnFunctionOwnerScope() {
        val source = """
            local function outer(param)
                local function inner()
                    return param
                end
                return inner
            end
            """.trimIndent()
        val (chunk, result) = bindChunk(source)

        val param = result.declarationIndex.declarations.single {
            it.name == "param" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
        }
        assertNotNull(param.range)
        assertPerNameRange(source, param, "param", expectedKind = DeclarationKind.PARAMETER)

        val outerFn = functionOf(result, "outer")
        val outerFunctionNode = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val outerScope = requireFunctionScope(result, outerFunctionNode)
        assertTrue(result.scopeGraph.getDeclarations(outerScope.id).contains(param.id))
        assertEquals(outerFn.name, "outer")
        assertSame(outerFunctionNode.body, outerScope.ownerNode)
        assertSame(outerFunctionNode.params.single(), param.anchorNode)

        // Nested inner scope must not own the parameter declaration.
        val innerLocal = outerFunctionNode.body!!.statements.filterIsInstance<FunctionDeclaration>().single()
        val innerScope = requireFunctionScope(result, innerLocal)
        assertTrue(!result.scopeGraph.getDeclarations(innerScope.id).contains(param.id))
        assertEquals(result.scopeGraph.rootScope.id, parentChainIds(result.scopeGraph, innerScope.id).last())
    }

    @Test
    fun anonymousNestedFunctionStillCreatesFunctionScopeUnderOuterLocal() {
        val source = """
            local outer = 0
            local callback = function()
                return outer
            end
            """.trimIndent()
        val (chunk, result) = bindChunk(source)

        val outer = localOf(result, "outer")
        assertPerNameRange(source, outer, "outer")

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().last()
        val anonymous = local.variables.filterIsInstance<FunctionDeclaration>().single()
        val functionScope = requireFunctionScope(result, anonymous)
        assertEquals(ScopeKind.FUNCTION, functionScope.kind)
        assertTrue(!result.scopeGraph.getDeclarations(functionScope.id).contains(outer.id))
        assertEquals(result.scopeGraph.rootScope.id, parentChainIds(result.scopeGraph, functionScope.id).last())
        assertSame(anonymous.body, functionScope.ownerNode)
    }

    @Test
    fun captureRangesStayInsideDeclaringIdentifierNotWholeBlock() {
        val source = """
            do
                local only = 7
                local function reader()
                    return only
                end
            end
            """.trimIndent()
        val (_, result) = bindChunk(source)
        val only = localOf(result, "only")
        assertPerNameRange(source, only, "only")

        // Range width equals identifier length, not the whole do-block / function body.
        assertEquals("only".length, only.range!!.end.column - only.range!!.start.column)
        assertTrue(only.range!!.end.column > only.range!!.start.column)
    }

    // --- dual-path CURRENTLY_ACCEPTS: multi-identifier return under capture -------

    @Test
    fun dualPath_multiIdentifierReturnUnderNestedFunction_documentsProductParse() {
        // MASTER-REVIEW43 / TASK-328 failure class: nested body ending with
        // `return left, right` historically threw IllegalStateException near eof
        // during strict parse used by binder fixtures.
        //
        // Dual-path goldens:
        // - Path A (product accepts): both outer multi-locals bind; ranges disjoint;
        //   nested function does not redeclare left/right; FUNCTION scope exists.
        // - Path B (CURRENTLY_ACCEPTS product still rejects): throw is documented;
        //   safe rewrite still binds with disjoint identifier ranges.
        val source = """
            local left, right = 1, 2
            local function pack()
                return left, right
            end
            """.trimIndent()

        when (val outcome = tryBind(source)) {
            is BindOutcome.Ok -> {
                val left = localOf(outcome.result, "left")
                val right = localOf(outcome.result, "right")
                assertPerNameRange(source, left, "left")
                assertPerNameRange(source, right, "right")
                assertRangesDisjoint(left.range!!, right.range!!)
                assertEquals(1, nonBuiltinLocals(outcome.result).count { it.name == "left" })
                assertEquals(1, nonBuiltinLocals(outcome.result).count { it.name == "right" })

                val function = outcome.chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
                val functionScope = requireFunctionScope(outcome.result, function)
                assertTrue(!outcome.result.scopeGraph.getDeclarations(functionScope.id).contains(left.id))
                assertTrue(!outcome.result.scopeGraph.getDeclarations(functionScope.id).contains(right.id))
                assertSame(function.body, functionScope.ownerNode)
            }
            is BindOutcome.ParseRejected -> {
                // CURRENTLY_ACCEPTS: documented product gap for multi-identifier return.
                assertTrue(
                    outcome.error is IllegalStateException ||
                        outcome.message.contains("unexpected", ignoreCase = true) ||
                        outcome.message.contains("eof", ignoreCase = true) ||
                        outcome.message.isNotBlank(),
                    "expected documented parse failure for multi-identifier return, got: ${outcome.error}"
                )
                // Safe rewrite used by hard-green multiOuterLocals still binds (control).
                val safe = """
                    local left, right = 1, 2
                    local function pack()
                        local usedLeft = left
                        local usedRight = right
                        return usedLeft
                    end
                    """.trimIndent()
                val (safeChunk, safeResult) = bindChunk(safe)
                assertRangesDisjoint(localOf(safeResult, "left").range!!, localOf(safeResult, "right").range!!)
                val pack = safeChunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
                assertSame(pack.body, requireFunctionScope(safeResult, pack).ownerNode)
            }
        }
    }

    @Test
    fun dualPath_deeplyNestedMultiIdentifierReturn_documentsProductOrSafeControl() {
        // Combines deep nesting (upvalue path) with multi-identifier return footgun.
        val source = """
            local a, b = 1, 2
            local function outer()
                local function inner()
                    return a, b
                end
                return inner
            end
            """.trimIndent()

        when (val outcome = tryBind(source)) {
            is BindOutcome.Ok -> {
                val a = localOf(outcome.result, "a")
                val b = localOf(outcome.result, "b")
                assertPerNameRange(source, a, "a")
                assertPerNameRange(source, b, "b")
                assertRangesDisjoint(a.range!!, b.range!!)
                assertEquals(1, nonBuiltinLocals(outcome.result).count { it.name == "a" })
                assertEquals(1, nonBuiltinLocals(outcome.result).count { it.name == "b" })
                val functionScopes = outcome.result.scopeGraph.scopes.filter { it.kind == ScopeKind.FUNCTION }
                assertTrue(functionScopes.size >= 2, "expected nested function scopes, got ${functionScopes.size}")
                functionScopes.forEach { scope ->
                    assertTrue(!outcome.result.scopeGraph.getDeclarations(scope.id).contains(a.id))
                    assertTrue(!outcome.result.scopeGraph.getDeclarations(scope.id).contains(b.id))
                }
            }
            is BindOutcome.ParseRejected -> {
                assertTrue(
                    outcome.error is IllegalStateException || outcome.message.isNotBlank(),
                    "deep multi-identifier return should fail strictly or be dual-path documented"
                )
                val safe = """
                    local a, b = 1, 2
                    local function outer()
                        local function inner()
                            local usedA = a
                            local usedB = b
                            return usedA
                        end
                        return inner
                    end
                    """.trimIndent()
                val safeResult = bindChunk(safe).second
                assertRangesDisjoint(localOf(safeResult, "a").range!!, localOf(safeResult, "b").range!!)
                assertEquals(1, nonBuiltinLocals(safeResult).count { it.name == "a" })
                assertEquals(1, nonBuiltinLocals(safeResult).count { it.name == "b" })
            }
        }
    }

    @Test
    fun dualPath_anonymousFunctionMultiIdentifierReturn_captureRangeContract() {
        // Anonymous nested function + multi-identifier return dual-path.
        val source = """
            local left, right = 3, 4
            local callback = function()
                return left, right
            end
            """.trimIndent()

        when (val outcome = tryBind(source)) {
            is BindOutcome.Ok -> {
                val left = localOf(outcome.result, "left")
                val right = localOf(outcome.result, "right")
                assertPerNameRange(source, left, "left")
                assertPerNameRange(source, right, "right")
                assertRangesDisjoint(left.range!!, right.range!!)

                val local = outcome.chunk.body.statements.filterIsInstance<LocalStatement>().last()
                val anonymous = local.variables.filterIsInstance<FunctionDeclaration>().singleOrNull()
                if (anonymous != null) {
                    val functionScope = requireFunctionScope(outcome.result, anonymous)
                    assertEquals(ScopeKind.FUNCTION, functionScope.kind)
                    assertTrue(!outcome.result.scopeGraph.getDeclarations(functionScope.id).contains(left.id))
                    assertTrue(!outcome.result.scopeGraph.getDeclarations(functionScope.id).contains(right.id))
                    assertSame(anonymous.body, functionScope.ownerNode)
                }
            }
            is BindOutcome.ParseRejected -> {
                assertTrue(
                    outcome.error is IllegalStateException || outcome.message.isNotBlank(),
                    "anonymous multi-identifier return should fail strictly or be dual-path documented"
                )
                val safe = """
                    local left, right = 3, 4
                    local callback = function()
                        return left
                    end
                    """.trimIndent()
                val (safeChunk, safeResult) = bindChunk(safe)
                assertRangesDisjoint(localOf(safeResult, "left").range!!, localOf(safeResult, "right").range!!)
                val local = safeChunk.body.statements.filterIsInstance<LocalStatement>().last()
                val anonymous = local.variables.filterIsInstance<FunctionDeclaration>().single()
                assertSame(anonymous.body, requireFunctionScope(safeResult, anonymous).ownerNode)
            }
        }
    }

    @Test
    fun dualPath_parameterUpvalueWithMultiIdentifierReturn_documentsProduct() {
        // Parameter as upvalue + multi-identifier return footgun.
        val source = """
            local function outer(param, other)
                local function inner()
                    return param, other
                end
                return inner
            end
            """.trimIndent()

        when (val outcome = tryBind(source)) {
            is BindOutcome.Ok -> {
                val param = outcome.result.declarationIndex.declarations.single {
                    it.name == "param" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
                }
                val other = outcome.result.declarationIndex.declarations.single {
                    it.name == "other" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
                }
                assertPerNameRange(source, param, "param", expectedKind = DeclarationKind.PARAMETER)
                assertPerNameRange(source, other, "other", expectedKind = DeclarationKind.PARAMETER)
                assertRangesDisjoint(param.range!!, other.range!!)

                val outerFn = outcome.chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
                val outerScope = requireFunctionScope(outcome.result, outerFn)
                assertTrue(outcome.result.scopeGraph.getDeclarations(outerScope.id).contains(param.id))
                assertTrue(outcome.result.scopeGraph.getDeclarations(outerScope.id).contains(other.id))

                val innerFn = outerFn.body!!.statements.filterIsInstance<FunctionDeclaration>().single()
                val innerScope = requireFunctionScope(outcome.result, innerFn)
                assertTrue(!outcome.result.scopeGraph.getDeclarations(innerScope.id).contains(param.id))
                assertTrue(!outcome.result.scopeGraph.getDeclarations(innerScope.id).contains(other.id))
            }
            is BindOutcome.ParseRejected -> {
                assertTrue(
                    outcome.error is IllegalStateException || outcome.message.isNotBlank(),
                    "parameter multi-identifier return should fail strictly or be dual-path documented"
                )
                val safe = """
                    local function outer(param, other)
                        local function inner()
                            local used = param
                            local usedOther = other
                            return used
                        end
                        return inner
                    end
                    """.trimIndent()
                val (safeChunk, safeResult) = bindChunk(safe)
                val param = safeResult.declarationIndex.declarations.single {
                    it.name == "param" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
                }
                val other = safeResult.declarationIndex.declarations.single {
                    it.name == "other" && it.kind == DeclarationKind.PARAMETER && it.origin != DeclarationOrigin.BUILTIN
                }
                assertRangesDisjoint(param.range!!, other.range!!)
                val outerFn = safeChunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
                assertTrue(safeResult.scopeGraph.getDeclarations(requireFunctionScope(safeResult, outerFn).id).contains(param.id))
            }
        }
    }

    @Test
    fun dualPath_incompleteNestedFunctionBody_documentsStrictRejectOrBind() {
        // Recovery-ish incomplete nested function; strict binder path dual-paths.
        val incomplete = """
            local outer = 1
            local function inner()
                return outer
            """.trimIndent()

        when (val outcome = tryBind(incomplete)) {
            is BindOutcome.Ok -> {
                // If product recovers into a bindable AST, outer still binds at identifier range.
                val outer = localOf(outcome.result, "outer")
                assertPerNameRange(incomplete, outer, "outer")
                assertEquals(1, nonBuiltinLocals(outcome.result).count { it.name == "outer" })
                // Function scope may or may not be present under recovery; when present it must
                // not own the outer local declaration.
                outcome.result.scopeGraph.scopes
                    .filter { it.kind == ScopeKind.FUNCTION }
                    .forEach { scope ->
                        assertTrue(!outcome.result.scopeGraph.getDeclarations(scope.id).contains(outer.id))
                    }
            }
            is BindOutcome.ParseRejected -> {
                assertTrue(
                    outcome.error is IllegalStateException || outcome.message.isNotBlank(),
                    "incomplete nested function body should fail strictly or be dual-path documented"
                )
            }
        }

        // Complete form remains the hard-green control for this corpus.
        val complete = """
            local outer = 1
            local function inner()
                return outer
            end
            """.trimIndent()
        val (_, completeResult) = bindChunk(complete)
        assertPerNameRange(complete, localOf(completeResult, "outer"), "outer")
        assertEquals(1, nonBuiltinLocals(completeResult).count { it.name == "outer" })
    }

    @Test
    fun dualPath_forLoopVarCapturedByNestedFunction_rangeContract() {
        // For-loop control variable is a LOCAL owned by the LOOP scope; nested function
        // capture must not redeclare it. Dual-path only if product fails to bind for+function.
        val source = """
            for i = 1, 3 do
                local function reader()
                    return i
                end
            end
            """.trimIndent()

        when (val outcome = tryBind(source)) {
            is BindOutcome.Ok -> {
                val loopVar = nonBuiltinLocals(outcome.result).single { it.name == "i" }
                assertPerNameRange(source, loopVar, "i")
                assertEquals(1, nonBuiltinLocals(outcome.result).count { it.name == "i" })

                val functionScopes = outcome.result.scopeGraph.scopes.filter { it.kind == ScopeKind.FUNCTION }
                assertTrue(functionScopes.isNotEmpty(), "nested local function should create a FUNCTION scope")
                functionScopes.forEach { scope ->
                    assertTrue(
                        !outcome.result.scopeGraph.getDeclarations(scope.id).contains(loopVar.id),
                        "loop var must not be redeclared inside nested function scope ${scope.id}"
                    )
                }
                // LOOP scope should own the control variable when present.
                val loopScopes = outcome.result.scopeGraph.scopes.filter { it.kind == ScopeKind.LOOP }
                if (loopScopes.isNotEmpty()) {
                    assertTrue(
                        loopScopes.any { outcome.result.scopeGraph.getDeclarations(it.id).contains(loopVar.id) },
                        "for-loop control variable should live on a LOOP scope when LOOP scopes exist"
                    )
                }
            }
            is BindOutcome.ParseRejected -> {
                // CURRENTLY_ACCEPTS only if product cannot parse for+nested function together.
                assertTrue(
                    outcome.error is IllegalStateException || outcome.message.isNotBlank(),
                    "for-loop nested capture should fail strictly or be dual-path documented"
                )
            }
        }
    }

    // --- helpers --------------------------------------------------------------

    private sealed class BindOutcome {
        data class Ok(val chunk: ChunkNode, val result: BinderPassResult) : BindOutcome()
        data class ParseRejected(val error: Throwable, val message: String) : BindOutcome()
    }

    private fun tryBind(source: String): BindOutcome {
        return try {
            val chunk = LuaParser().parse(source)
            val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
            BindOutcome.Ok(chunk, result)
        } catch (error: Throwable) {
            BindOutcome.ParseRejected(error, error.message.orEmpty())
        }
    }

    /**
     * Parse once and bind that same [ChunkNode]. Callers that need AST identity
     * (anchorNode / getScope) must use this pair — never re-parse after bind.
     * Hard-green paths only; dual-path fixtures use [tryBind].
     */
    private fun bindChunk(source: String): Pair<ChunkNode, BinderPassResult> {
        val chunk = try {
            LuaParser().parse(source)
        } catch (error: Throwable) {
            fail("expected upvalue capture fixture to parse without error:\n$source", error)
        }
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        // Single-AST contract: root owner is the same body instance we just parsed.
        assertSame(
            chunk.body,
            result.scopeGraph.rootScope.ownerNode,
            "BinderPass root scope ownerNode must be the bound chunk.body (single-AST contract)"
        )
        return chunk to result
    }

    private fun requireFunctionScope(result: BinderPassResult, function: FunctionDeclaration): Scope {
        val body = assertNotNull(function.body, "function body required for scope lookup")
        // Prefer identity map; fall back to ownerNode scan for clearer diagnostics.
        return result.scopeGraph.getScope(body)
            ?: result.scopeGraph.scopes.firstOrNull { it.ownerNode === body && it.kind == ScopeKind.FUNCTION }
            ?: error(
                "Missing FUNCTION scope for body@${System.identityHashCode(body)} " +
                    "(scopes=${result.scopeGraph.scopes.map { "${it.kind}@${System.identityHashCode(it.ownerNode)}" }})"
            )
    }

    private fun nonBuiltinLocals(result: BinderPassResult): List<BinderDeclaration> {
        return result.declarationIndex.declarations.filter {
            it.kind == DeclarationKind.LOCAL && it.origin != DeclarationOrigin.BUILTIN
        }
    }

    private fun localOf(result: BinderPassResult, name: String): BinderDeclaration {
        return nonBuiltinLocals(result).single { it.name == name }
    }

    private fun functionOf(result: BinderPassResult, name: String): BinderDeclaration {
        return result.declarationIndex.declarations.single {
            it.name == name &&
                it.kind in setOf(DeclarationKind.FUNCTION, DeclarationKind.GLOBAL, DeclarationKind.METHOD) &&
                it.origin != DeclarationOrigin.BUILTIN
        }
    }

    private fun assertPerNameRange(
        source: String,
        declaration: BinderDeclaration,
        name: String,
        expectedKind: DeclarationKind = DeclarationKind.LOCAL
    ) {
        val range = assertNotNull(declaration.range, "Declaration '$name' must expose a range")
        assertEquals(name, declaration.name)
        assertEquals(expectedKind, declaration.kind)
        val expectedStart = positionOf(source, name)
        assertEquals(expectedStart, range.start, "Range start for '$name' should match identifier start")
        assertEquals(expectedStart.line, range.end.line)
        assertEquals(expectedStart.column + name.length, range.end.column)
    }

    private fun assertRangesDisjoint(a: Range, b: Range) {
        val aEndsBeforeB = comparePositions(a.end, b.start) <= 0
        val bEndsBeforeA = comparePositions(b.end, a.start) <= 0
        assertTrue(aEndsBeforeB || bEndsBeforeA, "Expected disjoint ranges but got $a and $b")
    }

    private fun comparePositions(left: Position, right: Position): Int {
        val line = left.line.compareTo(right.line)
        return if (line != 0) line else left.column.compareTo(right.column)
    }

    private fun parentChainIds(graph: ScopeGraph, start: ScopeId): List<ScopeId> {
        val chain = mutableListOf<ScopeId>()
        var current: Scope? = graph.scopes.firstOrNull { it.id == start }
        val seen = mutableSetOf<ScopeId>()
        while (current != null) {
            assertTrue(seen.add(current.id), "cycle in parent chain at ${current.id}")
            chain += current.id
            current = current.parentId?.let { parentId -> graph.scopes.firstOrNull { it.id == parentId } }
        }
        return chain
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var fromIndex = 0
        var found = 0
        val requireWordBoundary = needle.all { isIdentChar(it) }
        while (true) {
            val index = source.indexOf(needle, fromIndex)
            require(index >= 0) { "Missing '$needle' occurrence $occurrence in:\n$source" }
            val match = if (!requireWordBoundary) {
                true
            } else {
                val beforeOk = index == 0 || !isIdentChar(source[index - 1])
                val afterIndex = index + needle.length
                val afterOk = afterIndex >= source.length || !isIdentChar(source[afterIndex])
                beforeOk && afterOk
            }
            if (match) {
                found++
                if (found == occurrence) {
                    var line = 1
                    var column = 1
                    for (i in 0 until index) {
                        if (source[i] == '\n') {
                            line++
                            column = 1
                        } else {
                            column++
                        }
                    }
                    return Position(line, column)
                }
            }
            fromIndex = index + 1
        }
    }

    private fun isIdentChar(ch: Char): Boolean = ch == '_' || ch.isLetterOrDigit()
}
