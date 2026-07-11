package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
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
 * Binder bare-global assignment declaration corpus (TASK-286 / TASK-558).
 *
 * Product binder contract (declarative free-name first-write, TASK-558):
 * - First bare free-name write (`config = 1`) invents an AST GLOBAL with
 *   **identifier-only** range (not the whole AssignmentStatement).
 * - Multi-LHS bare assignment creates per-name GLOBAL decls with disjoint
 *   identifier ranges; commas invent no ranges.
 * - Repeated writes re-use / resolve to the same GLOBAL symbol without forking
 *   ranges at later assign sites (later sites are write references, not new decls).
 * - Local introducers still win shadowing; member/index LHS stay non-GLOBAL.
 * - Non-local [FunctionDeclaration] remains a GLOBAL introducer; when a prior bare
 *   write already invented the free name, the function-site decl attaches to the
 *   same VALUE symbol (identity stays unified).
 *
 * Test-only; verification via
 * `jvmTest --tests semantic.binder.BinderGlobalAssignmentDeclarationTddTest`.
 */
class BinderGlobalAssignmentDeclarationTddTest {

    private val parser = LuaParser()

    // --- product: first bare free-name write invents AST GLOBAL -----------------

    @Test
    fun bareSingleAssignment_createsGlobalAstDeclarationAtIdentifier() {
        val source = "config = 1"
        val chunk = parser.parse(source)
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val statement = chunk.body.statements.filterIsInstance<AssignmentStatement>().single()
        val lhs = assertIs<Identifier>(statement.init.single())

        val decl = globalOf(result, "config")
        assertEquals(DeclarationKind.GLOBAL, decl.kind)
        assertEquals(DeclarationOrigin.AST, decl.origin)
        assertEquals(lhs, decl.anchorNode)
        assertEquals(lhs.range, decl.range)
        assertNotEquals(statement.range, decl.range)
        assertEquals(decl, result.positionQueries.getDeclarationAt(positionOf(source, "config")))
        assertNotNull(result.positionQueries.getSymbolAt(positionOf(source, "config")))
        assertEquals(1, nonBuiltinValueSymbolsNamed(result, "config").size)
    }

    @Test
    fun bareAssignmentWithNilRhs_createsGlobalDeclaration() {
        val source = "flag = nil"
        val result = bind(source)

        val decl = globalOf(result, "flag")
        assertEquals(DeclarationOrigin.AST, decl.origin)
        assertEquals(decl, result.positionQueries.getDeclarationAt(positionOf(source, "flag")))
    }

    @Test
    fun multiLhsBareAssignment_createsGlobalPerBareIdentifier() {
        val source = "alpha, beta = 1, 2"
        val result = bind(source)

        val alpha = globalOf(result, "alpha")
        val beta = globalOf(result, "beta")
        assertEquals(alpha, result.positionQueries.getDeclarationAt(positionOf(source, "alpha")))
        assertEquals(beta, result.positionQueries.getDeclarationAt(positionOf(source, "beta")))
        assertNotEquals(alpha.symbolId, beta.symbolId)
        assertEquals(1, astGlobalsNamed(result, "alpha").size)
        assertEquals(1, astGlobalsNamed(result, "beta").size)
    }

    @Test
    fun unbalancedMultiLhsBareAssignment_stillCreatesGlobalsPerName() {
        val source = "u, v, w = 1"
        val result = bind(source)

        listOf("u", "v", "w").forEach { name ->
            val decl = globalOf(result, name)
            assertEquals(decl, result.positionQueries.getDeclarationAt(positionOf(source, name)))
        }
    }

    @Test
    fun nestedBlockBareAssignment_createsChunkGlobalNotLocal() {
        val source = """
            do
                shared = 42
            end
            """.trimIndent()
        val result = bind(source)

        val decl = globalOf(result, "shared")
        assertEquals(DeclarationKind.GLOBAL, decl.kind)
        assertTrue(
            result.declarationIndex.declarations.none {
                it.name == "shared" && it.kind == DeclarationKind.LOCAL
            }
        )
        assertEquals(decl, result.positionQueries.getDeclarationAt(positionOf(source, "shared")))
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
        assertEquals(DeclarationOrigin.AST, free.origin)
        // `host` remains the GLOBAL from FunctionDeclaration.
        val host = globalOf(result, "host")
        assertEquals(DeclarationKind.GLOBAL, host.kind)
        assertEquals(DeclarationOrigin.AST, host.origin)
        assertNotEquals(host.symbolId, free.symbolId)
    }

    // --- product: repeated bare writes re-use same GLOBAL symbol ----------------

    @Test
    fun repeatedBareAssignments_reuseSingleGlobalSymbol() {
        val source = """
            counter = 1
            counter = 2
            counter = 3
            """.trimIndent()
        val result = bind(source)

        val globals = astGlobalsNamed(result, "counter")
        assertEquals(1, globals.size, "Only first bare write invents GLOBAL AST decl")
        assertEquals(1, nonBuiltinValueSymbolsNamed(result, "counter").size)

        val first = assertNotNull(
            result.positionQueries.getDeclarationAt(positionOf(source, "counter", occurrence = 1))
        )
        assertEquals(DeclarationKind.GLOBAL, first.kind)
        // Later sites are write references, not new decls.
        assertNull(
            result.positionQueries.getDeclarationAt(positionOf(source, "counter", occurrence = 2))
        )
        assertNull(
            result.positionQueries.getDeclarationAt(positionOf(source, "counter", occurrence = 3))
        )
    }

    @Test
    fun secondBareAssignment_doesNotDuplicateGlobalSymbol() {
        val source = """
            mode = "a"
            mode = "b"
            """.trimIndent()
        val result = bind(source)

        assertNotNull(result.positionQueries.getDeclarationAt(positionOf(source, "mode", occurrence = 1)))
        assertNull(result.positionQueries.getDeclarationAt(positionOf(source, "mode", occurrence = 2)))
        assertEquals(1, nonBuiltinValueSymbolsNamed(result, "mode").size)
        assertEquals(1, astGlobalsNamed(result, "mode").size)
    }

    // --- product: global function is GLOBAL introducer; assign reuses symbol ----

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

        // Bare assignment after function is not a new declaration site.
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
    fun bareAssignmentThenGlobalFunction_unifiesUnderSameGlobalSymbol() {
        val source = """
            draw = nil
            function draw()
            end
            """.trimIndent()
        val result = bind(source)

        val assignSite = assertNotNull(
            result.positionQueries.getDeclarationAt(positionOf(source, "draw", occurrence = 1))
        )
        val functionSite = assertNotNull(
            result.positionQueries.getDeclarationAt(positionOf(source, "draw", occurrence = 2))
        )
        assertEquals(DeclarationKind.GLOBAL, assignSite.kind)
        assertEquals(DeclarationKind.GLOBAL, functionSite.kind)
        assertEquals(DeclarationOrigin.AST, assignSite.origin)
        assertEquals(DeclarationOrigin.AST, functionSite.origin)
        // Same VALUE symbol; primary remains the first invent (bare write).
        assertEquals(assignSite.symbolId, functionSite.symbolId)
        assertEquals(1, nonBuiltinValueSymbolsNamed(result, "draw").size)
        assertEquals(2, astGlobalsNamed(result, "draw").size)
        val primary = result.declarationIndex.getPrimaryDeclaration(requireNotNull(assignSite.symbolId))
        assertEquals(assignSite.id, primary?.id)
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
    fun mixedLocalAndBareGlobal_localDeclaresAndBareWriteDeclaresSeparately() {
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
        val onlyGlobal = globalOf(result, "onlyGlobal")
        assertEquals(onlyGlobal, result.positionQueries.getDeclarationAt(positionOf(source, "onlyGlobal")))
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
    fun crossChunkBareAssignments_eachChunkInventsOwnDeclarativeGlobal() {
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
        assertEquals(1, astGlobalsNamed(chunkA, "g").size)
        assertEquals(1, astGlobalsNamed(chunkB, "g").size)
        assertEquals(identityKey(globalOf(chunkA, "g")), identityKey(globalOf(chunkB, "g")))
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
    fun crossChunkGlobalFunctionAndBareAssignment_bothDeclarativeWithStableIdentityKey() {
        val functionChunk = bind("function service() end")
        val assignChunk = bind("service = nil")

        val functionDecl = functionChunk.declarationIndex.declarations.single {
            it.name == "service" && it.origin != DeclarationOrigin.BUILTIN &&
                it.kind in setOf(DeclarationKind.GLOBAL, DeclarationKind.FUNCTION)
        }
        assertEquals(DeclarationKind.GLOBAL, functionDecl.kind)
        assertEquals(DeclarationNamespace.VALUE, functionDecl.kind.namespace)

        val assignDecl = globalOf(assignChunk, "service")
        assertEquals(DeclarationKind.GLOBAL, assignDecl.kind)
        assertEquals(
            identityKey(functionDecl),
            identityKey(assignDecl),
            "Cross-chunk free-global identity key stable across function vs bare invent"
        )
        assertEquals(
            assignDecl,
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
