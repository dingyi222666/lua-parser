package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSemanticFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Syntax-recovery diagnostics must survive the workspace parse.
 *
 * `LuaParser.parseWorkspaceSnippet` used to record recovery diagnostics on a throwaway inner
 * parser and then discard it, so `recoveryDiagnostics` was permanently empty for every workspace
 * parse. Anything that wanted parse errors — LSP diagnostics above all — had no choice but to
 * re-parse the same buffer. Keeping them on [WorkspaceSemanticFile] makes the workspace parse the
 * single parse.
 */
class WorkspaceRecoveryDiagnosticsTddTest {

    private val path = VirtualPath.of("/main.lua")

    private fun semanticFileFor(source: String): WorkspaceSemanticFile =
        LuaWorkspaceEngine()
            .build(LuaWorkspaceInput(files = mapOf(path to source)))
            .snapshot.files.getValue(path).semanticFile!!

    @Test
    fun `incomplete source surfaces recovery diagnostics on the semantic file`() {
        val diagnostics = semanticFileFor("local x =\n").recoveryDiagnostics
        assertEquals(1, diagnostics.size, "expected the workspace parse to keep its recovery diagnostics")
        assertTrue(
            diagnostics.single().message.contains("expected"),
            "unexpected message: ${diagnostics.single().message}"
        )
    }

    @Test
    fun `unclosed constructs surface recovery diagnostics`() {
        listOf(
            "function f(\n",
            "local t = {\n",
            "if x then\n"
        ).forEach { source ->
            assertTrue(
                semanticFileFor(source).recoveryDiagnostics.isNotEmpty(),
                "expected recovery diagnostics for: ${source.trim()}"
            )
        }
    }

    @Test
    fun `stray top-level terminators report at their real position`() {
        // The LSP used to parse these strictly, which threw and anchored the error at 1:1.
        // Recovery reports the position the terminator actually occupies.
        mapOf(
            "local x = 1\nend\n" to 2,
            "return 1\nend\n" to 2,
            "end\n" to 1,
            "until x\n" to 1
        ).forEach { (source, expectedLine) ->
            val diagnostics = semanticFileFor(source).recoveryDiagnostics
            assertEquals(1, diagnostics.size, "source=${source.trim()}")
            assertEquals(
                expectedLine,
                diagnostics.single().range.start.line,
                "wrong error line for ${source.trim().replace("\n", "\\n")}"
            )
        }
    }

    @Test
    fun `recovery failure still reports a diagnostic`() {
        // `return 1` closes the chunk, so recovery itself gives up on the trailing `end`. The
        // engine must not degrade that into a silently clean file.
        val diagnostics = semanticFileFor("return 1\nend\n").recoveryDiagnostics
        assertTrue(diagnostics.isNotEmpty(), "a failed recovery must stay visible as a diagnostic")
    }

    @Test
    fun `well-formed source reports no recovery diagnostics`() {
        assertEquals(emptyList(), semanticFileFor("local x = 1\nreturn x\n").recoveryDiagnostics)
    }

    @Test
    fun `incremental update refreshes recovery diagnostics`() {
        val engine = LuaWorkspaceEngine()
        val built = engine.build(LuaWorkspaceInput(files = mapOf(path to "local x = 1\nreturn x\n")))
        assertEquals(emptyList(), built.snapshot.files.getValue(path).semanticFile!!.recoveryDiagnostics)

        val broken = engine.update(built.snapshot, WorkspaceDelta(upserts = mapOf(path to "local x =\n")))
        assertTrue(
            broken.snapshot.files.getValue(path).semanticFile!!.recoveryDiagnostics.isNotEmpty(),
            "expected the edit to surface a fresh recovery diagnostic"
        )

        val repaired = engine.update(broken.snapshot, WorkspaceDelta(upserts = mapOf(path to "local x = 1\nreturn x\n")))
        assertEquals(
            emptyList(),
            repaired.snapshot.files.getValue(path).semanticFile!!.recoveryDiagnostics,
            "expected the stale diagnostic to clear once the syntax error is fixed"
        )
    }
}
