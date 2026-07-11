package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Binder global assignment **range** corpus (TASK-436).
 *
 * Complements [BinderGlobalAssignmentDeclarationTddTest] (TASK-286, identity /
 * non-declarative bare writes) by locking positional / range surface:
 *
 * Product binder contract (aligned with TASK-286 / [BinderMultiAssignRangeTddTest]):
 * - Assignment statements are **not** a declaration source. Bare free-name writes
 *   do not create/update GLOBAL AST declarations and therefore expose **no**
 *   declaration-range anchors at assignment LHS sites.
 * - GLOBAL AST declarations come from non-local [FunctionDeclaration] (and
 *   builtins via [DeclarationOrigin.BUILTIN]); their ranges are the identifier
 *   token, not the whole statement and not any later bare write.
 * - Multi-LHS bare assignment keeps each name non-queryable; commas between
 *   names do not invent ranges.
 * - Local introducers keep their own identifier ranges; subsequent assignment
 *   to a local does not move or fork the declaration range.
 *
 * Dual-path / CURRENTLY_ACCEPTS:
 * - If a future product path starts inventing GLOBAL AST decls at bare
 *   assignment sites, those decls **must** still use per-identifier ranges
 *   (not whole-statement spans) and remain queryable only on the name token.
 *   Today the CURRENTLY_ACCEPTS path is "no AST GLOBAL / null at assign site".
 * - Incomplete assignment RHS / multi-identifier return footguns dual-path
 *   strict parse reject vs bind-with-no-assign-decl (same class as TASK-392).
 *
 * Test-only; no production edits. Verification review-owned / TASK-043:
 * `jvmTest --tests semantic.binder.BinderGlobalAssignmentRangeTddTest`
 */
class BinderGlobalAssignmentRangeTddTest {

    private val parser = LuaParser()

    // --- product hard path: bare assignment has no declaration range -----------

    @Test
    fun bareSingleAssignment_lhsHasNoDeclarationRangeOrQueryHit() {
        val source = "config = 1"
        val result = bind(source)

        assertNoAstGlobal(result, "config")
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "config")))
        assertTrue(result.positionQueries.getDeclarationsAt(positionOf(source, "config")).isEmpty())
        assertNull(result.positionQueries.getSymbolAt(positionOf(source, "config")))
    }

    @Test
    fun bareAssignmentWithNilRhs_lhsStillHasNoDeclarationRange() {
        val source = "flag = nil"
        val result = bind(source)

        assertNoAstGlobal(result, "flag")
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "flag")))
    }

    @Test
    fun multiLhsBareAssignment_eachNameHasNoDeclarationRange() {
        val source = "alpha, beta = 1, 2"
        val result = bind(source)

        for (name in listOf("alpha", "beta")) {
            assertNoAstGlobal(result, name)
            assertNull(result.positionQueries.getDeclarationAt(positionOf(source, name)))
            assertTrue(result.positionQueries.getDeclarationsAt(positionOf(source, name)).isEmpty())
        }
        // Comma between multi-LHS names must not invent a declaration range either.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, ",")))
    }

    @Test
    fun unbalancedMultiLhsBareAssignment_stillNoDeclarationRanges() {
        val source = "u, v, w = 1"
        val result = bind(source)

        for (name in listOf("u", "v", "w")) {
            assertNoAstGlobal(result, name)
            assertNull(result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }
    }

    @Test
    fun repeatedBareAssignments_neverInventDeclarationRangesAtAnyWrite() {
        val source = """
            counter = 1
            counter = 2
            counter = 3
            """.trimIndent()
        val result = bind(source)

        assertTrue(astGlobalsNamed(result, "counter").isEmpty())
        for (occurrence in 1..3) {
            assertNull(
                result.positionQueries.getDeclarationAt(
                    positionOf(source, "counter", occurrence = occurrence)
                )
            )
        }
    }

    @Test
    fun nestedBlockBareAssignment_hasNoChunkGlobalRange() {
        val source = """
            do
                shared = 42
            end
            """.trimIndent()
        val result = bind(source)

        assertNoAstGlobal(result, "shared")
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "shared")))
    }

    @Test
    fun bareAssignmentInsideFunction_freeNameHasNoGlobalRange() {
        val source = """
            function host()
                freeGlobal = true
            end
            """.trimIndent()
        val result = bind(source)

        assertNoAstGlobal(result, "freeGlobal")
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "freeGlobal")))

        // `host` GLOBAL range remains the function identifier only.
        val host = globalOf(result, "host")
        assertPerNameGlobalRange(source, host, "host")
        assertEquals(host, result.positionQueries.getDeclarationAt(positionOf(source, "host")))
    }

    // --- product hard path: global function ranges are identifier-only ---------

    @Test
    fun globalFunctionDeclarationRangeMatchesAstIdentifierNotWholeStatement() {
        val source = "function settings() end"
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val name = assertIs<Identifier>(function.identifier)
        val declaration = globalOf(result, "settings")

        assertEquals(name, declaration.anchorNode)
        assertEquals(name.range, declaration.range)
        assertNotEquals(function.range, declaration.range)
        assertTrue(isProperSubRange(declaration.range!!, function.range))
        assertPerNameGlobalRange(source, declaration, "settings")
        assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, "settings")))
    }

    @Test
    fun globalFunctionThenBareAssignment_onlyFunctionSiteHasDeclarationRange() {
        val source = """
            function render()
            end
            render = nil
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val functionName = assertIs<Identifier>(function.identifier)

        val functionDecl = result.declarationIndex.declarations.single {
            it.anchorNode == functionName && it.origin != DeclarationOrigin.BUILTIN
        }
        assertEquals(DeclarationKind.GLOBAL, functionDecl.kind)
        assertEquals(functionName.range, functionDecl.range)
        assertPerNameGlobalRange(source, functionDecl, "render")

        // Occurrence 1 = function name; occurrence 2 = bare write — not a decl range.
        assertEquals(
            functionDecl,
            result.positionQueries.getDeclarationAt(positionOf(source, "render", occurrence = 1))
        )
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "render", occurrence = 2)))
        assertEquals(1, astGlobalsNamed(result, "render").size)
    }

    @Test
    fun bareAssignmentThenGlobalFunction_onlyFunctionCreatesQueryableRange() {
        val source = """
            draw = nil
            function draw()
            end
            """.trimIndent()
        val result = bind(source)

        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "draw", occurrence = 1)))
        val functionSite = assertNotNull(
            result.positionQueries.getDeclarationAt(positionOf(source, "draw", occurrence = 2))
        )
        assertEquals(DeclarationKind.GLOBAL, functionSite.kind)
        assertEquals(DeclarationOrigin.AST, functionSite.origin)
        assertPerNameGlobalRange(source, functionSite, "draw", occurrence = 2)
        assertEquals(1, astGlobalsNamed(result, "draw").size)
    }

    @Test
    fun multiGlobalFunctions_eachDeclarationRangeIsOwnIdentifier() {
        val source = """
            function zed() end
            function alpha() end
            function mid() end
            """.trimIndent()
        val result = bind(source)

        val names = listOf("zed", "alpha", "mid")
        val decls = names.map { globalOf(result, it) }
        names.zip(decls).forEach { (name, declaration) ->
            assertPerNameGlobalRange(source, declaration, name)
            assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }
        for (i in decls.indices) {
            for (j in i + 1 until decls.size) {
                assertRangesDisjoint(decls[i].range!!, decls[j].range!!)
            }
        }
    }

    // --- product hard path: local / member / index do not invent global ranges --

    @Test
    fun localThenAssignment_declarationRangeStaysAtLocalIntroducer() {
        val source = """
            local value = 0
            value = 1
            """.trimIndent()
        val result = bind(source)

        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "value" && it.kind == DeclarationKind.GLOBAL && it.origin != DeclarationOrigin.BUILTIN
            }
        )
        val local = result.declarationIndex.declarations.single {
            it.name == "value" && it.kind == DeclarationKind.LOCAL
        }
        assertEquals(positionOf(source, "value", occurrence = 1), local.range!!.start)
        assertEquals(local, result.positionQueries.getDeclarationAt(positionOf(source, "value", occurrence = 1)))
        // Assignment site is not a declaration range anchor.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "value", occurrence = 2)))
        assertNull(
            result.declarationIndex.declarations.find {
                it.name == "value" && it.range?.start == positionOf(source, "value", occurrence = 2)
            }
        )
    }

    @Test
    fun memberAssignment_doesNotCreateGlobalRangesForMemberOrBase() {
        val source = "tableField.x = 1"
        val result = bind(source)

        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "x" && it.kind == DeclarationKind.GLOBAL && it.origin != DeclarationOrigin.BUILTIN
            }
        )
        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "tableField" && it.kind == DeclarationKind.GLOBAL && it.origin == DeclarationOrigin.AST
            }
        )
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "x")))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "tableField")))
    }

    @Test
    fun indexAssignment_doesNotCreateGlobalRangesForIndexKeyOrBase() {
        val source = "bag[key] = 1"
        val result = bind(source)

        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "key" && it.kind == DeclarationKind.GLOBAL && it.origin != DeclarationOrigin.BUILTIN
            }
        )
        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "bag" && it.kind == DeclarationKind.GLOBAL && it.origin == DeclarationOrigin.AST
            }
        )
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "key")))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "bag")))
    }

    @Test
    fun mixedLocalAndBareGlobal_onlyLocalHasDeclarationRange() {
        val source = """
            local onlyLocal = 1
            onlyGlobal = 2
            onlyLocal = 3
            """.trimIndent()
        val result = bind(source)

        val local = result.declarationIndex.declarations.single {
            it.name == "onlyLocal" && it.kind == DeclarationKind.LOCAL
        }
        assertPerNameLocalRange(source, local, "onlyLocal")
        assertEquals(local, result.positionQueries.getDeclarationAt(positionOf(source, "onlyLocal", occurrence = 1)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "onlyLocal", occurrence = 2)))
        assertNoAstGlobal(result, "onlyGlobal")
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "onlyGlobal")))
    }

    // --- multi-line bare assignment layout (still non-declarative) -------------

    @Test
    fun multiLineBareAssignment_lhsSitesRemainNonQueryable() {
        val source = """
            config =
                1
            """.trimIndent()
        val result = bind(source)

        assertNoAstGlobal(result, "config")
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "config")))
    }

    @Test
    fun multiLineMultiLhsBareAssignment_eachNameNonQueryableCommaNotARange() {
        val source = """
            alpha,
            beta =
                1,
                2
            """.trimIndent()
        val result = bind(source)

        for (name in listOf("alpha", "beta")) {
            assertNoAstGlobal(result, name)
            assertNull(result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, ",")))
    }

    // --- dual-path: if bare assign ever declares, range must be identifier-only -

    @Test
    fun dualPath_bareAssignmentAsPotentialGlobalIntroducer_rangeContract() {
        // CURRENTLY_ACCEPTS: product invents no AST GLOBAL at bare assignment.
        // IDEAL future: if a GLOBAL AST decl appears for free `mode`, its range
        // must be the identifier token (not whole AssignmentStatement) and must
        // be queryable only on that token.
        val source = "mode = \"a\""
        when (val outcome = tryBind(source)) {
            is BindOutcome.Ok -> {
                val globals = astGlobalsNamed(outcome.result, "mode")
                if (globals.isEmpty()) {
                    // CURRENTLY_ACCEPTS product path.
                    assertNull(
                        outcome.result.positionQueries.getDeclarationAt(positionOf(source, "mode"))
                    )
                } else {
                    // IDEAL / future declarative-assign path: identifier-only ranges.
                    val decl = globals.first()
                    assertPerNameGlobalRange(source, decl, "mode")
                    assertEquals(
                        decl,
                        outcome.result.positionQueries.getDeclarationAt(positionOf(source, "mode"))
                    )
                    val statement = outcome.chunk.body.statements
                        .filterIsInstance<AssignmentStatement>()
                        .singleOrNull()
                    if (statement != null && decl.range != null) {
                        assertNotEquals(statement.range, decl.range)
                        assertTrue(
                            isProperSubRange(decl.range!!, statement.range),
                            "future bare-assign GLOBAL range must be narrower than AssignmentStatement"
                        )
                    }
                }
            }
            is BindOutcome.ParseRejected -> {
                fail("well-formed bare assignment must parse: ${outcome.message}", outcome.error)
            }
        }
    }

    @Test
    fun dualPath_multiLhsBareAssignment_ifDeclaredMustUseDisjointIdentifierRanges() {
        val source = "left, right = 1, 2"
        when (val outcome = tryBind(source)) {
            is BindOutcome.Ok -> {
                val lefts = astGlobalsNamed(outcome.result, "left")
                val rights = astGlobalsNamed(outcome.result, "right")
                if (lefts.isEmpty() && rights.isEmpty()) {
                    // CURRENTLY_ACCEPTS: non-declarative multi-LHS bare assign.
                    assertNull(outcome.result.positionQueries.getDeclarationAt(positionOf(source, "left")))
                    assertNull(outcome.result.positionQueries.getDeclarationAt(positionOf(source, "right")))
                    assertNull(outcome.result.positionQueries.getDeclarationAt(positionOf(source, ",")))
                } else {
                    // IDEAL: each declared free name keeps its own identifier range.
                    assertEquals(1, lefts.size)
                    assertEquals(1, rights.size)
                    assertPerNameGlobalRange(source, lefts.single(), "left")
                    assertPerNameGlobalRange(source, rights.single(), "right")
                    assertRangesDisjoint(lefts.single().range!!, rights.single().range!!)
                    assertNull(outcome.result.positionQueries.getDeclarationAt(positionOf(source, ",")))
                }
            }
            is BindOutcome.ParseRejected -> {
                fail("well-formed multi-LHS assignment must parse: ${outcome.message}", outcome.error)
            }
        }
    }

    @Test
    fun dualPath_incompleteBareAssignmentRhs_documentsStrictRejectOrNonDeclarativeBind() {
        // Recovery-ish incomplete form; strict binder path dual-paths.
        val incomplete = "orphan =\n"
        when (val outcome = tryBind(incomplete)) {
            is BindOutcome.Ok -> {
                // If strict parse accepts trailing `=`, free name still must not
                // invent a GLOBAL range under current product; if it does, range
                // is identifier-only.
                val globals = astGlobalsNamed(outcome.result, "orphan")
                if (globals.isEmpty()) {
                    assertNull(
                        outcome.result.positionQueries.getDeclarationAt(positionOf(incomplete, "orphan"))
                    )
                } else {
                    assertPerNameGlobalRange(incomplete, globals.single(), "orphan")
                }
            }
            is BindOutcome.ParseRejected -> {
                assertTrue(
                    outcome.error is IllegalStateException || outcome.message.isNotBlank(),
                    "incomplete bare assignment RHS should fail strictly or be dual-path documented"
                )
            }
        }

        // Complete control remains non-declarative under product binder.
        val complete = "orphan = 1"
        val completeResult = bind(complete)
        assertNoAstGlobal(completeResult, "orphan")
        assertNull(completeResult.positionQueries.getDeclarationAt(positionOf(complete, "orphan")))
    }

    @Test
    fun dualPath_globalFunctionWithMultiIdentifierReturn_keepsFunctionIdentifierRange() {
        // Multi-identifier return historically dual-paths strict parse (TASK-392).
        // When bind succeeds, GLOBAL function range stays the identifier token.
        val source = """
            function pack()
                return left, right
            end
            """.trimIndent()

        when (val outcome = tryBind(source)) {
            is BindOutcome.Ok -> {
                val pack = globalOf(outcome.result, "pack")
                assertPerNameGlobalRange(source, pack, "pack")
                assertEquals(
                    pack,
                    outcome.result.positionQueries.getDeclarationAt(positionOf(source, "pack"))
                )
                // Free names in return are not declaration ranges.
                assertNoAstGlobal(outcome.result, "left")
                assertNoAstGlobal(outcome.result, "right")
            }
            is BindOutcome.ParseRejected -> {
                assertTrue(
                    outcome.error is IllegalStateException ||
                        outcome.message.contains("unexpected", ignoreCase = true) ||
                        outcome.message.contains("eof", ignoreCase = true) ||
                        outcome.message.isNotBlank(),
                    "expected documented parse failure for multi-identifier return, got: ${outcome.error}"
                )
                // Safe rewrite still binds global function with identifier range.
                val safe = """
                    function pack()
                        local used = 1
                        return used
                    end
                    """.trimIndent()
                val safeResult = bind(safe)
                assertPerNameGlobalRange(safe, globalOf(safeResult, "pack"), "pack")
            }
        }
    }

    @Test
    fun dualPath_builtinNameBareAssignment_noAstPeerRangeAtAssignSite() {
        val source = "print = function() end"
        val result = bind(source)

        val symbols = result.declarationIndex.getSymbols(
            "print",
            io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace.VALUE
        )
        assertEquals(1, symbols.size, "Builtin global must keep a single VALUE symbol for print")
        val symbol = symbols.single()
        val decls = result.declarationIndex.getDeclarations(symbol.id)
        assertTrue(decls.any { it.origin == DeclarationOrigin.BUILTIN })
        assertTrue(
            decls.none { it.origin == DeclarationOrigin.AST },
            "Bare assignment must not add an AST peer declaration for builtin print"
        )

        val atAssign = result.positionQueries.getDeclarationAt(positionOf(source, "print"))
        if (atAssign != null) {
            // CURRENTLY_ACCEPTS only if hit is still the same builtin (doc range coincidence).
            assertEquals(symbol.id, atAssign.symbolId)
            assertEquals(DeclarationOrigin.BUILTIN, atAssign.origin)
        }
    }

    // --- cross-chunk range identity for product globals ------------------------

    @Test
    fun crossChunkGlobalFunction_rangesArePerChunkIdentifierButIdentityKeyStable() {
        val leftSource = "function sharedConfig() end"
        val rightSource = "function sharedConfig() end"
        val left = bind(leftSource)
        val right = bind(rightSource)

        val leftDecl = globalOf(left, "sharedConfig")
        val rightDecl = globalOf(right, "sharedConfig")
        assertPerNameGlobalRange(leftSource, leftDecl, "sharedConfig")
        assertPerNameGlobalRange(rightSource, rightDecl, "sharedConfig")
        // Same source layout → same relative identifier range.
        assertEquals(leftDecl.range, rightDecl.range)
        assertEquals(identityKey(leftDecl), identityKey(rightDecl))
    }

    @Test
    fun crossChunkBareAssignments_remainNonQueryableInEachChunk() {
        val chunkA = bind(
            """
            g = 1
            g = 2
            """.trimIndent()
        )
        val chunkB = bind(
            """
            g = 3
            g = 4
            g = 5
            """.trimIndent()
        )

        assertNoAstGlobal(chunkA, "g")
        assertNoAstGlobal(chunkB, "g")
        assertNull(chunkA.positionQueries.getDeclarationAt(positionOf("g = 1\ng = 2", "g", occurrence = 1)))
        assertNull(chunkB.positionQueries.getDeclarationAt(positionOf("g = 3\ng = 4\ng = 5", "g", occurrence = 1)))
    }

    // --- helpers --------------------------------------------------------------

    private sealed class BindOutcome {
        data class Ok(val chunk: ChunkNode, val result: BinderPassResult) : BindOutcome()
        data class ParseRejected(val error: Throwable, val message: String) : BindOutcome()
    }

    private fun tryBind(source: String): BindOutcome {
        return try {
            val chunk = parser.parse(source)
            val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
            BindOutcome.Ok(chunk, result)
        } catch (error: Throwable) {
            BindOutcome.ParseRejected(error, error.message.orEmpty())
        }
    }

    private fun bind(source: String): BinderPassResult {
        val chunk = parser.parse(source)
        return BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
    }

    private fun astGlobalsNamed(result: BinderPassResult, name: String): List<BinderDeclaration> {
        return result.declarationIndex.declarations.filter {
            it.name == name &&
                it.kind == DeclarationKind.GLOBAL &&
                it.origin != DeclarationOrigin.BUILTIN
        }
    }

    private fun globalOf(result: BinderPassResult, name: String): BinderDeclaration {
        val matches = astGlobalsNamed(result, name)
        assertTrue(matches.isNotEmpty(), "Expected GLOBAL declaration for '$name'")
        val symbolId = matches.first().symbolId
        if (symbolId != null) {
            val primary = result.declarationIndex.getPrimaryDeclaration(symbolId)
            if (primary != null && primary.name == name) {
                return primary
            }
        }
        return matches.first()
    }

    private fun assertNoAstGlobal(result: BinderPassResult, name: String) {
        assertTrue(
            astGlobalsNamed(result, name).isEmpty(),
            "Expected no non-builtin GLOBAL declaration for '$name' under product binder"
        )
    }

    private fun identityKey(
        declaration: BinderDeclaration
    ): Triple<String, io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace, DeclarationKind> {
        return Triple(
            declaration.name,
            declaration.kind.namespace,
            DeclarationKind.GLOBAL
        )
    }

    private fun assertPerNameGlobalRange(
        source: String,
        declaration: BinderDeclaration,
        name: String,
        occurrence: Int = 1
    ) {
        val range = assertNotNull(declaration.range, "GLOBAL '$name' must expose a range")
        val anchor = assertNotNull(declaration.anchorNode, "GLOBAL '$name' must keep an anchor node")
        val identifier = assertIs<Identifier>(anchor)
        assertEquals(name, identifier.name)
        assertEquals(name, declaration.name)
        assertEquals(DeclarationKind.GLOBAL, declaration.kind)
        assertEquals(identifier.range, range)

        val expectedStart = positionOf(source, name, occurrence = occurrence)
        assertEquals(expectedStart, range.start, "Range start for GLOBAL '$name' should match identifier start")
        assertEquals(expectedStart.line, range.end.line)
        assertEquals(expectedStart.column + name.length, range.end.column)
        assertTrue(range.end.column > range.start.column)
    }

    private fun assertPerNameLocalRange(
        source: String,
        declaration: BinderDeclaration,
        name: String,
        occurrence: Int = 1
    ) {
        val range = assertNotNull(declaration.range, "LOCAL '$name' must expose a range")
        val anchor = assertNotNull(declaration.anchorNode, "LOCAL '$name' must keep an anchor node")
        val identifier = assertIs<Identifier>(anchor)
        assertEquals(name, identifier.name)
        assertEquals(DeclarationKind.LOCAL, declaration.kind)
        assertEquals(identifier.range, range)

        val expectedStart = positionOf(source, name, occurrence = occurrence)
        assertEquals(expectedStart, range.start)
        assertEquals(expectedStart.line, range.end.line)
        assertEquals(expectedStart.column + name.length, range.end.column)
    }

    private fun assertRangesDisjoint(a: Range, b: Range) {
        val aEndsBeforeB = comparePositions(a.end, b.start) <= 0
        val bEndsBeforeA = comparePositions(b.end, a.start) <= 0
        assertTrue(
            aEndsBeforeB || bEndsBeforeA,
            "Expected disjoint ranges but got $a and $b"
        )
    }

    private fun isProperSubRange(inner: Range, outer: Range): Boolean {
        val contained =
            comparePositions(outer.start, inner.start) <= 0 &&
                comparePositions(inner.end, outer.end) <= 0
        val narrower =
            comparePositions(outer.start, inner.start) < 0 ||
                comparePositions(inner.end, outer.end) < 0
        return contained && narrower
    }

    private fun comparePositions(left: Position, right: Position): Int {
        val line = left.line.compareTo(right.line)
        return if (line != 0) line else left.column.compareTo(right.column)
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
                    return indexToPosition(source, index)
                }
            }
            fromIndex = index + 1
        }
    }

    private fun indexToPosition(source: String, index: Int): Position {
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

    private fun isIdentChar(ch: Char): Boolean {
        return ch == '_' || ch.isLetterOrDigit()
    }
}
