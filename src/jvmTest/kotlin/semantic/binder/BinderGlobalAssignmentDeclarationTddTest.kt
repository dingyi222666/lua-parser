package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.SymbolId
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Binder bare-global assignment declaration corpus (TASK-286).
 *
 * Acceptance:
 * - Bare global assignments create/update global decls consistently.
 * - Cross-chunk global identity is stable (same free name / VALUE namespace
 *   identity across independently bound chunks).
 *
 * Notes:
 * - Complements [BinderPassDeclarationTest] / [BinderMultiAssignRangeTddTest],
 *   which historically treated assignment as a non-declaration source. This
 *   corpus encodes the intended global-assignment declaration contract for
 *   free names (create on first write, update/link on later writes).
 * - Test-only; production defects surface as assertion failures (review-owned
 *   verification via
 *   `jvmTest --tests semantic.binder.BinderGlobalAssignmentDeclarationTddTest`).
 */
class BinderGlobalAssignmentDeclarationTddTest {

    private val parser = LuaParser()

    // --- create: first bare assignment introduces GLOBAL AST decl ------------

    @Test
    fun bareSingleAssignment_createsGlobalAstDeclarationWithIdentifierAnchor() {
        val source = "config = 1"
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val assignment = chunk.body.statements.filterIsInstance<AssignmentStatement>().single()
        val lhs = assertIs<Identifier>(assignment.init.single())

        val declaration = globalOf(result, "config")
        assertEquals(DeclarationKind.GLOBAL, declaration.kind)
        assertEquals(DeclarationOrigin.AST, declaration.origin)
        assertEquals(lhs, declaration.anchorNode)
        assertEquals(lhs.range, declaration.range)
        assertPerNameRange(source, declaration, "config")
        assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, "config")))
        assertEquals(declaration.symbolId, result.positionQueries.getSymbolAt(positionOf(source, "config"))?.id)
    }

    @Test
    fun bareAssignmentWithoutRhsValueStillCreatesGlobalDeclaration() {
        // Parser may still produce an assignment with empty variables list for
        // incomplete sources; prefer a well-formed assignment with nil RHS.
        val source = "flag = nil"
        val result = bind(source)
        val declaration = globalOf(result, "flag")
        assertEquals(DeclarationOrigin.AST, declaration.origin)
        assertNotNull(declaration.symbolId)
        assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, "flag")))
    }

    @Test
    fun multiLhsBareAssignment_createsGlobalPerBareIdentifier() {
        val source = "alpha, beta = 1, 2"
        val result = bind(source)

        val alpha = globalOf(result, "alpha")
        val beta = globalOf(result, "beta")

        assertPerNameRange(source, alpha, "alpha")
        assertPerNameRange(source, beta, "beta")
        assertRangesDisjoint(alpha.range!!, beta.range!!)
        assertNotEquals(alpha.id, beta.id)
        assertNotEquals(alpha.symbolId, beta.symbolId)
        assertEquals(alpha, result.positionQueries.getDeclarationAt(positionOf(source, "alpha")))
        assertEquals(beta, result.positionQueries.getDeclarationAt(positionOf(source, "beta")))
    }

    @Test
    fun unbalancedMultiLhsBareAssignment_stillCreatesGlobalForEveryLhsName() {
        val source = "u, v, w = 1"
        val result = bind(source)

        listOf("u", "v", "w").forEach { name ->
            val declaration = globalOf(result, name)
            assertEquals(DeclarationKind.GLOBAL, declaration.kind)
            assertEquals(DeclarationOrigin.AST, declaration.origin)
            assertPerNameRange(source, declaration, name)
        }
    }

    @Test
    fun nestedBlockBareAssignment_stillCreatesChunkGlobalNotLocal() {
        val source = """
            do
                shared = 42
            end
            """.trimIndent()
        val result = bind(source)

        val declaration = globalOf(result, "shared")
        assertEquals(DeclarationKind.GLOBAL, declaration.kind)
        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "shared" && it.kind == DeclarationKind.LOCAL
            }
        )
        assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, "shared")))
    }

    @Test
    fun bareAssignmentInsideFunction_createsGlobalWhenNameIsFree() {
        val source = """
            function host()
                freeGlobal = true
            end
            """.trimIndent()
        val result = bind(source)

        val free = globalOf(result, "freeGlobal")
        assertEquals(DeclarationKind.GLOBAL, free.kind)
        // `host` remains the function/global function declaration from FunctionDeclaration.
        assertTrue(
            result.declarationIndex.declarations.any {
                it.name == "host" && it.kind in setOf(DeclarationKind.GLOBAL, DeclarationKind.FUNCTION)
            }
        )
    }

    // --- update: later bare assignments keep one global symbol identity ------

    @Test
    fun repeatedBareAssignments_shareOneGlobalSymbolAndKeepPrimaryFirstWrite() {
        val source = """
            counter = 1
            counter = 2
            counter = 3
            """.trimIndent()
        val result = bind(source)

        val globals = astGlobalsNamed(result, "counter")
        assertTrue(globals.isNotEmpty(), "Expected at least one GLOBAL AST decl for counter")

        val symbolIds = globals.mapNotNull { it.symbolId }.toSet()
        assertEquals(1, symbolIds.size, "Repeated bare writes must update one global symbol, not invent peers")

        val symbolId = symbolIds.single()
        val symbol = assertNotNull(result.declarationIndex.getSymbol(symbolId))
        assertEquals("counter", symbol.name)
        assertEquals(DeclarationNamespace.VALUE, symbol.namespace)

        val primary = assertNotNull(result.declarationIndex.getPrimaryDeclaration(symbolId))
        // Primary stays the first write site (create), later writes update/link.
        assertEquals(positionOf(source, "counter", occurrence = 1), primary.range!!.start)

        // Every write site remains queryable as belonging to that global symbol.
        for (occurrence in 1..3) {
            val at = result.positionQueries.getDeclarationAt(positionOf(source, "counter", occurrence = occurrence))
            assertNotNull(at, "Missing declaration hit at write site #$occurrence")
            assertEquals(symbolId, at.symbolId)
            assertEquals("counter", at.name)
            assertEquals(DeclarationKind.GLOBAL, at.kind)
        }
    }

    @Test
    fun secondBareAssignment_updatesExistingGlobalWithoutDuplicatingSymbol() {
        val source = """
            mode = "a"
            mode = "b"
            """.trimIndent()
        val result = bind(source)

        val first = result.positionQueries.getDeclarationAt(positionOf(source, "mode", occurrence = 1))
        val second = result.positionQueries.getDeclarationAt(positionOf(source, "mode", occurrence = 2))
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first.symbolId, second.symbolId)
        assertEquals(DeclarationKind.GLOBAL, first.kind)
        assertEquals(DeclarationKind.GLOBAL, second.kind)

        val symbols = result.declarationIndex.getSymbols("mode", DeclarationNamespace.VALUE)
            .filter { symbol ->
                result.declarationIndex.getDeclarations(symbol.id).any {
                    it.origin != DeclarationOrigin.BUILTIN
                }
            }
        assertEquals(1, symbols.size)
    }

    @Test
    fun globalFunctionThenBareAssignment_linksToSameGlobalValueSymbol() {
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
        val assignDecl = result.positionQueries.getDeclarationAt(positionOf(source, "render", occurrence = 2))
        assertNotNull(assignDecl)
        assertEquals(DeclarationKind.GLOBAL, assignDecl.kind)
        assertEquals(functionDecl.symbolId, assignDecl.symbolId, "Bare update after global function keeps identity")
    }

    @Test
    fun bareAssignmentThenGlobalFunction_keepsSingleGlobalSymbolIdentity() {
        val source = """
            draw = nil
            function draw()
            end
            """.trimIndent()
        val result = bind(source)

        val firstWrite = result.positionQueries.getDeclarationAt(positionOf(source, "draw", occurrence = 1))
        val functionSite = result.positionQueries.getDeclarationAt(positionOf(source, "draw", occurrence = 2))
        assertNotNull(firstWrite)
        assertNotNull(functionSite)
        assertEquals(firstWrite.symbolId, functionSite.symbolId)
        assertEquals(1, nonBuiltinValueSymbolsNamed(result, "draw").size)
    }

    // --- non-create / non-global edges ---------------------------------------

    @Test
    fun localThenAssignment_doesNotCreateGlobalForShadowedName() {
        val source = """
            local value = 0
            value = 1
            """.trimIndent()
        val result = bind(source)

        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "value" && it.kind == DeclarationKind.GLOBAL && it.origin != DeclarationOrigin.BUILTIN
            },
            "Assignment to a local must not invent a GLOBAL declaration"
        )
        val local = result.declarationIndex.declarations.single {
            it.name == "value" && it.kind == DeclarationKind.LOCAL
        }
        // Declaration range remains the local introducer; assignment is not a new decl.
        assertEquals(positionOf(source, "value", occurrence = 1), local.range!!.start)
        assertNull(
            result.declarationIndex.declarations.find {
                it.name == "value" && it.range?.start == positionOf(source, "value", occurrence = 2)
            }
        )
    }

    @Test
    fun memberAssignment_doesNotCreateGlobalForMemberOrBaseUnlessBaseIsBareWrite() {
        val source = "tableField.x = 1"
        val result = bind(source)

        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "x" && it.kind == DeclarationKind.GLOBAL && it.origin != DeclarationOrigin.BUILTIN
            },
            "Member assignment must not create a free GLOBAL for the field name"
        )
        // Bare base is a read/write target of member form; it is not a bare identifier LHS write.
        // Contract: only bare Identifier LHS writes create/update globals.
        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "tableField" && it.kind == DeclarationKind.GLOBAL && it.origin == DeclarationOrigin.AST
            },
            "Member base is not a bare global assignment LHS"
        )
    }

    @Test
    fun indexAssignment_doesNotCreateGlobalForIndexKey() {
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
    }

    @Test
    fun builtinNameBareAssignment_updatesBuiltinGlobalSymbolRatherThanForkingPeer() {
        val source = "print = function() end"
        val result = bind(source)

        val symbols = result.declarationIndex.getSymbols("print", DeclarationNamespace.VALUE)
        assertEquals(1, symbols.size, "Builtin global update must keep a single VALUE symbol for print")
        val symbol = symbols.single()
        val decls = result.declarationIndex.getDeclarations(symbol.id)
        assertTrue(decls.any { it.origin == DeclarationOrigin.BUILTIN })
        // Update site is visible as a declaration belonging to that symbol (create/update).
        val atAssign = result.positionQueries.getDeclarationAt(positionOf(source, "print"))
        assertNotNull(atAssign)
        assertEquals(symbol.id, atAssign.symbolId)
    }

    // --- ranges / query consistency ------------------------------------------

    @Test
    fun bareGlobalDeclarationRangeMatchesAstIdentifierNotWholeStatement() {
        val source = "settings = true"
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val statement = chunk.body.statements.filterIsInstance<AssignmentStatement>().single()
        val lhs = assertIs<Identifier>(statement.init.single())
        val declaration = globalOf(result, "settings")

        assertEquals(lhs, declaration.anchorNode)
        assertEquals(lhs.range, declaration.range)
        assertNotEquals(statement.range, declaration.range)
        assertTrue(isProperSubRange(declaration.range!!, statement.range))
    }

    @Test
    fun multiBareGlobals_declarationIndexListsLhsInSourceOrder() {
        val source = "zed, alpha, mid = 1, 2, 3"
        val result = bind(source)
        val names = astGlobals(result).map { it.name }
        assertEquals(listOf("zed", "alpha", "mid"), names.filter { it in setOf("zed", "alpha", "mid") })
    }

    @Test
    fun mixedLocalAndBareGlobal_onlyBareNamesBecomeGlobals() {
        val source = """
            local onlyLocal = 1
            onlyGlobal = 2
            onlyLocal = 3
            """.trimIndent()
        val result = bind(source)

        assertEquals(1, result.declarationIndex.declarations.count {
            it.name == "onlyLocal" && it.kind == DeclarationKind.LOCAL
        })
        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "onlyLocal" && it.kind == DeclarationKind.GLOBAL
            }
        )
        assertEquals(1, nonBuiltinValueSymbolsNamed(result, "onlyGlobal").size)
        globalOf(result, "onlyGlobal")
    }

    // --- cross-chunk identity stability --------------------------------------

    @Test
    fun crossChunkSameBareGlobalName_stableKindNamespaceAndNameIdentity() {
        val left = bind("sharedConfig = 1")
        val right = bind("sharedConfig = 2")

        val leftDecl = globalOf(left, "sharedConfig")
        val rightDecl = globalOf(right, "sharedConfig")

        assertEquals(leftDecl.kind, rightDecl.kind)
        assertEquals(DeclarationKind.GLOBAL, leftDecl.kind)
        assertEquals(leftDecl.name, rightDecl.name)
        assertEquals(DeclarationNamespace.VALUE, leftDecl.kind.namespace)
        assertEquals(rightDecl.kind.namespace, leftDecl.kind.namespace)

        val leftSymbol = assertNotNull(leftDecl.symbolId?.let { left.declarationIndex.getSymbol(it) })
        val rightSymbol = assertNotNull(rightDecl.symbolId?.let { right.declarationIndex.getSymbol(it) })
        assertEquals(leftSymbol.name, rightSymbol.name)
        assertEquals(leftSymbol.namespace, rightSymbol.namespace)
        assertEquals(DeclarationNamespace.VALUE, leftSymbol.namespace)

        // Per-chunk symbol ids may differ (independent BinderPass), but the
        // cross-chunk identity key (name + VALUE namespace + GLOBAL kind) is stable.
        assertEquals(
            identityKey(leftDecl),
            identityKey(rightDecl),
            "Cross-chunk global identity key must be stable for the same free name"
        )
    }

    @Test
    fun crossChunkRepeatedWrites_eachChunkKeepsInternalSingleSymbolIdentity() {
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

        assertEquals(1, nonBuiltinValueSymbolsNamed(chunkA, "g").size)
        assertEquals(1, nonBuiltinValueSymbolsNamed(chunkB, "g").size)

        val a = globalOf(chunkA, "g")
        val b = globalOf(chunkB, "g")
        assertEquals(identityKey(a), identityKey(b))
    }

    @Test
    fun crossChunkDistinctBareGlobals_doNotCollideByIdentityKey() {
        val left = bind("alpha = 1")
        val right = bind("beta = 1")

        assertNotEquals(identityKey(globalOf(left, "alpha")), identityKey(globalOf(right, "beta")))
        assertEquals(
            identityKey(globalOf(left, "alpha")),
            identityKey(globalOf(bind("alpha = 99"), "alpha"))
        )
    }

    @Test
    fun crossChunkGlobalFunctionAndBareAssignment_shareStableIdentityKey() {
        val functionChunk = bind("function service() end")
        val assignChunk = bind("service = nil")

        val functionDecl = functionChunk.declarationIndex.declarations.single {
            it.name == "service" && it.origin != DeclarationOrigin.BUILTIN &&
                it.kind in setOf(DeclarationKind.GLOBAL, DeclarationKind.FUNCTION)
        }
        val assignDecl = globalOf(assignChunk, "service")

        assertEquals(
            Triple(functionDecl.name, DeclarationNamespace.VALUE, "GLOBAL_VALUE"),
            Triple(assignDecl.name, assignDecl.kind.namespace, "GLOBAL_VALUE")
        )
        // Free global name identity is name+VALUE regardless of function vs assignment introducer.
        assertEquals(functionDecl.name, assignDecl.name)
        assertEquals(DeclarationNamespace.VALUE, functionDecl.kind.namespace)
        assertEquals(DeclarationNamespace.VALUE, assignDecl.kind.namespace)
    }

    // --- helpers --------------------------------------------------------------

    private fun bind(source: String): BinderPassResult {
        val chunk = parser.parse(source)
        return BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
    }

    private fun astGlobals(result: BinderPassResult): List<BinderDeclaration> {
        return result.declarationIndex.declarations.filter {
            it.kind == DeclarationKind.GLOBAL && it.origin == DeclarationOrigin.AST
        }
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
        // Prefer primary/first declaration when multiple write sites exist.
        val symbolId = matches.first().symbolId
        if (symbolId != null) {
            val primary = result.declarationIndex.getPrimaryDeclaration(symbolId)
            if (primary != null && primary.name == name) {
                return primary
            }
        }
        return matches.first()
    }

    private fun nonBuiltinValueSymbolsNamed(result: BinderPassResult, name: String): List<SymbolId> {
        return result.declarationIndex.getSymbols(name, DeclarationNamespace.VALUE)
            .filter { symbol ->
                result.declarationIndex.getDeclarations(symbol.id).any {
                    it.origin != DeclarationOrigin.BUILTIN
                }
            }
            .map { it.id }
    }

    /**
     * Stable cross-chunk identity key for free globals: name + VALUE namespace + GLOBAL kind.
     * Independent BinderPass runs mint fresh numeric ids; identity is name-based.
     */
    private fun identityKey(declaration: BinderDeclaration): Triple<String, DeclarationNamespace, DeclarationKind> {
        return Triple(declaration.name, declaration.kind.namespace, DeclarationKind.GLOBAL)
    }

    private fun assertPerNameRange(source: String, declaration: BinderDeclaration, name: String) {
        val range = assertNotNull(declaration.range, "Declaration '$name' must expose a range")
        val anchor = assertNotNull(declaration.anchorNode, "Declaration '$name' must keep an anchor node")
        val identifier = assertIs<Identifier>(anchor)
        assertEquals(name, identifier.name)
        assertEquals(name, declaration.name)
        assertEquals(DeclarationKind.GLOBAL, declaration.kind)
        assertEquals(identifier.range, range)

        val expectedStart = positionOf(source, name)
        assertEquals(expectedStart, range.start, "Range start for '$name' should match identifier start")
        assertEquals(expectedStart.line, range.end.line)
        assertEquals(expectedStart.column + name.length, range.end.column)
        assertTrue(range.end.column > range.start.column)
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

    private fun isIdentChar(ch: Char): Boolean {
        return ch == '_' || ch.isLetterOrDigit()
    }
}
