package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Visibility gating for binder declarations.
 *
 * - A `local` declared by a LocalStatement becomes visible only after the whole statement
 *   ends (`visibleFrom` = statement end, Lua: a local's scope begins at the first statement
 *   after its declaration). The RHS of `local x = x + 1` must therefore resolve to the
 *   OUTER `x`, and `local x = 1` must not offer `x` on its own initializer.
 * - AST-invented chunk globals (`cfg = { title = "x" }` written after a function that reads
 *   `cfg`) are position-independent: Lua globals live in the environment table, so a
 *   bare-name write anywhere in the chunk resolves reads anywhere in the file.
 */
class BinderDeclarationVisibilityTddTest {

    @Test
    fun selfReferencingLocalInitializerResolvesRhsToOuterDeclaration() {
        val source = """
            local x = 1
            do local x = x + 1 end
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        // Occurrences of `x`: 1 = outer declaration, 2 = inner declaration, 3 = RHS read.
        val rhsRead = assertNotNull(
            model.getSymbolAt(positionOf(source, "x", occurrence = 3)),
            "RHS `x` of `local x = x + 1` must resolve to a declaration"
        )
        assertEquals("x", rhsRead.name)
        assertEquals(
            positionOf(source, "x", occurrence = 1),
            rhsRead.range?.start,
            "RHS `x` must bind to the outer x: the inner local is not yet in scope " +
                "while its own LocalStatement is being evaluated"
        )
    }

    @Test
    fun declaredLocalIsNotOfferedInsideItsOwnInitializer() {
        // Caret ON the initializer token itself (`local x = 10|0`): the new local's scope
        // begins after the statement, so the initializer still resolves to the outer
        // binding and must not offer x. (A caret at the very END of the initializer is the
        // legacy end-of-statement position where the local IS offered — see
        // LegacySemanticAnalyzerCompatibilityTest for that pinned contract.)
        val source = "local x = 100"
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        val completions = model.getCompletionsAt(positionOf(source, "100"))
        assertTrue(
            completions.none { it.label == "x" },
            "the local being declared must not be offered inside its own initializer; " +
                "actual=${completions.map { it.label }}"
        )
    }

    @Test
    fun shadowedLocalResolvesToInnerDeclarationAfterItsStatementCompletes() {
        val source = """
            local x = 1
            do local x = 2
                print(x)
            end
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        // Occurrences of `x`: 1 = outer declaration, 2 = inner declaration, 3 = read in print.
        val read = assertNotNull(model.getSymbolAt(positionOf(source, "x", occurrence = 3)))
        assertEquals(
            positionOf(source, "x", occurrence = 2),
            read.range?.start,
            "once the inner LocalStatement has completed, the inner x is visible and must " +
                "shadow the outer x again (visibleFrom must not hide it past its statement)"
        )
    }

    @Test
    fun singleCharacterSelfReferencingInitializerResolvesRhsToOuterDeclaration() {
        // `local x = x` is the anchor's worst case: the single-char RHS identifier's read
        // position (range.start) coincides exactly with the legacy end-of-statement caret
        // (end.column - 1). The read must still resolve to the OUTER x instead of
        // self-binding to the not-yet-initialized new local.
        val source = """
            local x = 1
            do local x = x end
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        // Occurrences of `x`: 1 = outer declaration, 2 = inner declaration, 3 = RHS read.
        val rhsRead = assertNotNull(
            model.getSymbolAt(positionOf(source, "x", occurrence = 3)),
            "RHS `x` of `local x = x` must resolve to a declaration"
        )
        assertEquals("x", rhsRead.name)
        assertEquals(
            positionOf(source, "x", occurrence = 1),
            rhsRead.range?.start,
            "single-char RHS `x` must bind to the outer x even though it sits on the " +
                "legacy end-of-statement caret of its own initializer"
        )
    }

    @Test
    fun singleCharacterSelfReferenceFlagsOnlyTheInnerLocalAsUnused() {
        // The self-binding counterpart at the diagnostics surface: the RHS read credits
        // the OUTER x, so the unused-local INFO lands on the INNER declaration. Pre-fix
        // the read self-bound the inner local (marking it used) and the OUTER x was
        // falsely reported unused instead.
        val source = """
            local x = 1
            do local x = x end
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        val unused = model.getDiagnostics().filter { it.message == "Unused local 'x'." }
        assertEquals(
            1,
            unused.size,
            "exactly one unused-local INFO expected; actual=${model.getDiagnostics().map { it.message }}"
        )
        assertEquals(
            positionOf(source, "x", occurrence = 2),
            unused.single().range?.start,
            "the outer x stays used by the RHS read; only the inner x is reported unused"
        )
    }

    @Test
    fun bareGlobalAssignmentAfterFunctionResolvesReadsInsideIt() {
        val source = """
            local function show() return cfg.title end
            cfg = { title = "x" }
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        // Occurrences of `cfg`: 1 = read inside show(), 2 = bare-write declaration site.
        val cfgRead = assertNotNull(
            model.getSymbolAt(positionOf(source, "cfg", occurrence = 1)),
            "`cfg` read inside show() must resolve even though the global is declared later"
        )
        assertEquals("cfg", cfgRead.name)
        assertEquals(
            positionOf(source, "cfg", occurrence = 2),
            cfgRead.range?.start,
            "the read must bind to the AST global invented by the later `cfg = ...` write"
        )
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
