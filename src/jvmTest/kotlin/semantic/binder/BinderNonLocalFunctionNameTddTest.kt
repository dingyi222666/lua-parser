package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Binder non-local `function name()` scoping corpus (TDD):
 *
 * 1. A non-local `function helper() end` written inside a nested block (do/end) is a
 *    chunk-level (root scope) introducer — after the block ends, `helper` must still
 *    resolve (hover / getSymbolAt non-null at the later usage).
 * 2. `local x = 1` followed by `function x() end` re-binds the visible local: the
 *    function site records a declaration on the local's symbol instead of minting a
 *    phantom GLOBAL peer symbol.
 */
class BinderNonLocalFunctionNameTddTest {

    @Test
    fun nonLocalFunctionInsideDoBlockIsRootedAtChunkScope() {
        val source = """
            do function helper() end end
            return helper
        """.trimIndent()
        val (chunk, result) = bindChunk(source)

        val helperDeclarations = result.declarationIndex.declarations.filter {
            it.name == "helper" && it.origin != DeclarationOrigin.BUILTIN
        }
        assertEquals(
            1,
            helperDeclarations.size,
            "expected a single helper declaration; actual=${helperDeclarations.map { it.kind }}"
        )
        val helperDeclaration = helperDeclarations.single()

        // The declaration must live in the root (chunk) scope, not the do-block scope.
        val rootScope = result.scopeGraph.rootScope
        assertTrue(
            helperDeclaration.id in rootScope.declarationIds,
            "non-local function declared inside do/end must be recorded in the root scope"
        )
        val doStatement = chunk.body.statements.filterIsInstance<DoStatement>().single()
        val blockScope = assertNotNull(
            result.scopeGraph.getScope(doStatement.body),
            "do block scope must exist"
        )
        assertTrue(
            helperDeclaration.id !in blockScope.declarationIds,
            "do-block scope must not own the non-local function declaration"
        )
    }

    @Test
    fun helperResolvesByHoverAfterTheDoBlockEnds() {
        val source = """
            do function helper() end end
            return helper
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        val symbol = assertNotNull(
            model.getSymbolAt(positionOf(source, "helper", occurrence = 2)),
            "helper usage after the do block must resolve to the function declared inside it"
        )
        assertEquals("helper", symbol.name)
    }

    @Test
    fun localFunctionRebindDoesNotMintPhantomGlobalSymbol() {
        val source = "local x = 1\nfunction x() end"
        val (chunk, result) = bindChunk(source)

        val xDeclarations = result.declarationIndex.declarations.filter {
            it.name == "x" && it.origin != DeclarationOrigin.BUILTIN
        }
        assertTrue(
            xDeclarations.none { it.kind == DeclarationKind.GLOBAL },
            "`function x()` after `local x` must not create a GLOBAL declaration; " +
                "actual=${xDeclarations.map { it.kind }}"
        )

        // One shared symbol for x: the local plus the function-site declaration.
        val xSymbols = result.declarationIndex.symbols.filter { it.name == "x" }
        val xSymbolShapes = xSymbols.map { symbol ->
            symbol.declarationIds.map { id -> result.declarationIndex.getDeclaration(id)?.kind }
        }
        assertEquals(
            1,
            xSymbols.size,
            "local x and `function x()` must share one symbol; actual=$xSymbolShapes"
        )
        val symbol = xSymbols.single()
        assertEquals(
            listOf(DeclarationKind.LOCAL, DeclarationKind.FUNCTION),
            symbol.declarationIds.map { result.declarationIndex.getDeclaration(it)?.kind },
            "`function x()` must re-bind the local symbol with a FUNCTION-site declaration"
        )

        // The function-site declaration anchors at this AST identifier.
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val functionDeclaration = xDeclarations.single { it.kind == DeclarationKind.FUNCTION }
        assertTrue(
            functionDeclaration.anchorNode === function.identifier,
            "FUNCTION declaration anchor must be the `function x()` identifier"
        )
        val localStatement = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val localDeclaration = xDeclarations.single { it.kind == DeclarationKind.LOCAL }
        assertTrue(
            localDeclaration.anchorNode === localStatement.init.single(),
            "LOCAL declaration anchor must be the `local x` identifier"
        )
    }

    // --- helpers ---------------------------------------------------------------

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
