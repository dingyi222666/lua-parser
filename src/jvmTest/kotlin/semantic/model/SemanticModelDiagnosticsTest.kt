package semantic.model

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.api.Symbol
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticModelDiagnosticsTest {

    @Test
    fun aggregatesSignatureAndReturnDiagnostics() {
        val harness = semanticModelHarness(
            """
            ---@param first string
            ---@param missing number
            ---@return string
            local function render(first)
                return 1, 2
            end
            """.trimIndent()
        )

        val diagnostics = harness.model.getDiagnostics()

        val renderSymbol = requireNotNull(
            harness.model.getSymbolAt(harness.positionOf("render"))
        )
        val renderType = requireNotNull(
            harness.model.getDeclaredType(renderSymbol)
        )

        assertTrue(diagnostics.any { it.code == "checker.function.signature.unknownParam" })
        assertTrue(diagnostics.any { it.code == "checker.function.return.typeMismatch" })
        assertTrue(diagnostics.any { it.code == "checker.function.return.extraValues" })
    }

    @Test
    fun preservesDeterministicSortOrderAndDeduplicatesExactDuplicates() {
        val harness = semanticModelHarness(
            """
            ---@method User:bad(optional?: string, required: number): string
            ---@overload fun(self: User, optional?: string, required: number): string
            ---@return string
            local function render()
                return 1
            end
            """.trimIndent()
        )

        val diagnostics = harness.model.getDiagnostics()

        // render is a chunk-level local function with zero callers: the wave-Z
        // dead-code pass adds an unused-local INFO alongside the two errors.
        assertEquals(3, diagnostics.size)
        assertEquals(
            // Unused-local INFO sorts between the two errors (deterministic order).
            listOf(
                "checker.function.signature.requiredAfterOptional",
                "checker.local.unused",
                "checker.function.return.typeMismatch"
            ),
            diagnostics.mapNotNull { it.code }
        )
    }

    @Test
    fun summaryCountsMatchAggregatedDiagnostics() {
        val result = SemanticPipeline().analyze(
            LuaParser().parse(
                """
                ---@return string
                local function render()
                    return 1
                end
                """.trimIndent()
            )
        )

        val diagnostics = result.model.getDiagnostics()
        assertEquals(diagnostics.size, result.summary.diagnosticCount)
        // The unused-local INFO (`render` has zero callers) is not an error.
        assertEquals(diagnostics.count { it.severity == DiagnosticSeverity.ERROR }, result.summary.errorCount)
    }
}
