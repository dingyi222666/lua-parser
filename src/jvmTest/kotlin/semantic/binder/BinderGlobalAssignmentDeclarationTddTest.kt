package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.Position
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
 * Product binder contract (aligned with [BinderPassDeclarationTest] /
 * [BinderMultiAssignRangeTddTest]):
 * - Assignment statements are **not** a declaration source. Bare free-name
 *   writes do not create/update GLOBAL AST declarations.
 * - GLOBAL AST declarations come from non-local [FunctionDeclaration] (and
 *   builtins via [DeclarationOrigin.BUILTIN]).
 * - Cross-chunk identity for free global **names** that *are* declared
 *   (functions / builtins) is stable as name + VALUE namespace + GLOBAL kind.
 * - Assignment sites remain non-queryable as declaration anchors.
 *
 * Test-only; verification via
 * `jvmTest --tests semantic.binder.BinderGlobalAssignmentDeclarationTddTest`.
 */
class BinderGlobalAssignmentDeclarationTddTest {

    private val parser = LuaParser()

    // --- product: bare assignment is not a declaration source ----------------

    @Test
    fun bareSingleAssignment_doesNotCreateGlobalAstDeclaration() {
        val source = "config = 1"
        val result = bind(source)

        assertNoAstGlobal(result, "config")
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "config")))
        assertNull(result.positionQueries.getSymbolAt(positionOf(source, "config")))
    }

    @Test
    fun bareAssignmentWithNilRhs_doesNotCreateGlobalDeclaration() {
        val source = "flag = nil"
        val result = bind(source)

        assertNoAstGlobal(result, "flag")
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "flag")))
    }

    @Test
    fun multiLhsBareAssignment_doesNotCreateGlobalPerBareIdentifier() {
        val source = "alpha, beta = 1, 2"
        val result = bind(source)

        assertNoAstGlobal(result, "alpha")
        assertNoAstGlobal(result, "beta")
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "alpha")))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "beta")))
    }

    @Test
    fun unbalancedMultiLhsBareAssignment_stillDoesNotCreateGlobals() {
        val source = "u, v, w = 1"
        val result = bind(source)

        listOf("u", "v", "w").forEach { name ->
            assertNoAstGlobal(result, name)
            assertNull(result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }
    }

    @Test
    fun nestedBlockBareAssignment_doesNotCreateChunkGlobalOrLocal() {
        val source = """
            do
                shared = 42
            end
            """.trimIndent()
        val result = bind(source)

        assertNoAstGlobal(result, "shared")
        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "shared" && it.kind == DeclarationKind.LOCAL
            }
        )
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "shared")))
    }

    @Test
    fun bareAssignmentInsideFunction_doesNotCreateGlobalWhenNameIsFree() {
        val source = """
            function host()
                freeGlobal = true
            end
            """.trimIndent()
        val result = bind(source)

        assertNoAstGlobal(result, "freeGlobal")
        // `host` remains the GLOBAL from FunctionDeclaration.
        val host = globalOf(result, "host")
        assertEquals(DeclarationKind.GLOBAL, host.kind)
        assertEquals(DeclarationOrigin.AST, host.origin)
    }

    // --- product: repeated bare writes still invent no global symbol ---------

    @Test
    fun repeatedBareAssignments_doNotInventGlobalSymbol() {
        val source = """
            counter = 1
            counter = 2
            counter = 3
            """.trimIndent()
        val result = bind(source)

        assertTrue(
            astGlobalsNamed(result, "counter").isEmpty(),
            "Repeated bare writes must not invent GLOBAL AST decls under product binder"
        )
        assertEquals(0, nonBuiltinValueSymbolsNamed(result, "counter").size)

        for (occurrence in 1..3) {
            assertNull(
                result.positionQueries.getDeclarationAt(
                    positionOf(source, "counter", occurrence = occurrence)
                )
            )
        }
    }

    @Test
    fun secondBareAssignment_doesNotDuplicateOrCreateGlobalSymbol() {
        val source = """
            mode = "a"
            mode = "b"
            """.trimIndent()
        val result = bind(source)

        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "mode", occurrence = 1)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "mode", occurrence = 2)))
        assertEquals(0, nonBuiltinValueSymbolsNamed(result, "mode").size)
    }

    // --- product: global function is the GLOBAL introducer; assign is not ----

    @Test
    fun globalFunctionThenBareAssignment_keepsOnlyFunctionGlobalNoAssignDecl() {
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

        // Bare assignment is not a declaration site; identity stays the function GLOBAL.
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "render", occurrence = 2)))
        assertEquals(1, nonBuiltinValueSymbolsNamed(result, "render").size)
        assertEquals(
            functionDecl.symbolId,
            result.declarationIndex.getPrimaryDeclaration(
                requireNotNull(functionDecl.symbolId)
            )?.symbolId
        )
    }

    @Test
    fun bareAssignmentThenGlobalFunction_onlyFunctionCreatesGlobalSymbol() {
        val source = """
            draw = nil
            function draw()
            end
            """.trimIndent()
        val result = bind(source)

        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "draw", occurrence = 1)))
        val functionSite = result.positionQueries.getDeclarationAt(positionOf(source, "draw", occurrence = 2))
        assertNotNull(functionSite)
        assertEquals(DeclarationKind.GLOBAL, functionSite.kind)
        assertEquals(DeclarationOrigin.AST, functionSite.origin)
        assertEquals(1, nonBuiltinValueSymbolsNamed(result, "draw").size)
        assertEquals(1, astGlobalsNamed(result, "draw").size)
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
    fun memberAssignment_doesNotCreateGlobalForMemberOrBase() {
        val source = "tableField.x = 1"
        val result = bind(source)

        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "x" && it.kind == DeclarationKind.GLOBAL && it.origin != DeclarationOrigin.BUILTIN
            },
            "Member assignment must not create a free GLOBAL for the field name"
        )
        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "tableField" && it.kind == DeclarationKind.GLOBAL && it.origin == DeclarationOrigin.AST
            },
            "Member base is not a bare global declaration source"
        )
    }

    @Test
    fun indexAssignment_doesNotCreateGlobalForIndexKeyOrBase() {
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
    fun builtinNameBareAssignment_doesNotForkPeerSymbolOrDeclareAtAssignSite() {
        val source = "print = function() end"
        val result = bind(source)

        val symbols = result.declarationIndex.getSymbols("print", DeclarationNamespace.VALUE)
        assertEquals(1, symbols.size, "Builtin global must keep a single VALUE symbol for print")
        val symbol = symbols.single()
        val decls = result.declarationIndex.getDeclarations(symbol.id)
        assertTrue(decls.any { it.origin == DeclarationOrigin.BUILTIN })
        // Assignment does not invent an AST declaration hit on the write site.
        // Builtin ranges come from overlay docs (if any) and are not the chunk source.
        val atAssign = result.positionQueries.getDeclarationAt(positionOf(source, "print"))
        if (atAssign != null) {
            // Only acceptable if the hit is still the same builtin symbol (doc range coincidence).
            assertEquals(symbol.id, atAssign.symbolId)
            assertTrue(atAssign.origin == DeclarationOrigin.BUILTIN)
        }
        assertTrue(
            decls.none { it.origin == DeclarationOrigin.AST },
            "Bare assignment must not add an AST peer declaration for builtin print"
        )
    }

    // --- global function ranges / mixed sources ------------------------------

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
        assertEquals(positionOf(source, "settings"), declaration.range!!.start)
        assertEquals(declaration, result.positionQueries.getDeclarationAt(positionOf(source, "settings")))
    }

    @Test
    fun multiGlobalFunctions_declarationIndexListsInSourceOrder() {
        val source = """
            function zed() end
            function alpha() end
            function mid() end
            """.trimIndent()
        val result = bind(source)
        val names = astGlobals(result).map { it.name }
        assertEquals(listOf("zed", "alpha", "mid"), names.filter { it in setOf("zed", "alpha", "mid") })
    }

    @Test
    fun mixedLocalAndBareGlobal_onlyLocalDeclaresBareWriteDoesNot() {
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
        assertEquals(0, nonBuiltinValueSymbolsNamed(result, "onlyGlobal").size)
        assertNoAstGlobal(result, "onlyGlobal")
    }

    // --- cross-chunk identity stability for product globals ------------------

    @Test
    fun crossChunkSameGlobalFunctionName_stableKindNamespaceAndNameIdentity() {
        val left = bind("function sharedConfig() end")
        val right = bind("function sharedConfig() end")

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
    fun crossChunkBareAssignments_remainNonDeclarativeInEachChunk() {
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

        assertEquals(0, nonBuiltinValueSymbolsNamed(chunkA, "g").size)
        assertEquals(0, nonBuiltinValueSymbolsNamed(chunkB, "g").size)
        assertNoAstGlobal(chunkA, "g")
        assertNoAstGlobal(chunkB, "g")
    }

    @Test
    fun crossChunkDistinctGlobalFunctions_doNotCollideByIdentityKey() {
        val left = bind("function alpha() end")
        val right = bind("function beta() end")

        assertNotEquals(identityKey(globalOf(left, "alpha")), identityKey(globalOf(right, "beta")))
        assertEquals(
            identityKey(globalOf(left, "alpha")),
            identityKey(globalOf(bind("function alpha() end"), "alpha"))
        )
    }

    @Test
    fun crossChunkGlobalFunctionAndBareAssignment_functionIsDeclarativeAssignIsNot() {
        val functionChunk = bind("function service() end")
        val assignChunk = bind("service = nil")

        val functionDecl = functionChunk.declarationIndex.declarations.single {
            it.name == "service" && it.origin != DeclarationOrigin.BUILTIN &&
                it.kind in setOf(DeclarationKind.GLOBAL, DeclarationKind.FUNCTION)
        }
        assertEquals(DeclarationKind.GLOBAL, functionDecl.kind)
        assertEquals(DeclarationNamespace.VALUE, functionDecl.kind.namespace)

        // Bare assignment chunk has no AST GLOBAL for the free name under product binder.
        assertNoAstGlobal(assignChunk, "service")
        assertNull(
            assignChunk.positionQueries.getDeclarationAt(positionOf("service = nil", "service"))
        )
    }

    @Test
    fun crossChunkBuiltinGlobalName_stableValueIdentityKey() {
        val left = bind("local x = 1")
        val right = bind("local y = 2")

        val leftPrint = left.declarationIndex.declarations.single {
            it.name == "print" && it.origin == DeclarationOrigin.BUILTIN
        }
        val rightPrint = right.declarationIndex.declarations.single {
            it.name == "print" && it.origin == DeclarationOrigin.BUILTIN
        }

        assertEquals(leftPrint.name, rightPrint.name)
        assertEquals(leftPrint.kind.namespace, rightPrint.kind.namespace)
        assertEquals(DeclarationNamespace.VALUE, leftPrint.kind.namespace)
        // Builtin print may be FUNCTION or GLOBAL depending on overlay docs; kind may differ
        // by seed path, but VALUE namespace + name is the stable free-global identity key.
        assertEquals(leftPrint.name, rightPrint.name)
        assertEquals(
            Pair(leftPrint.name, DeclarationNamespace.VALUE),
            Pair(rightPrint.name, rightPrint.kind.namespace)
        )
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
