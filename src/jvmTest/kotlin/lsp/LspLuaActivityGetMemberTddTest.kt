package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android-Lua runtime exposes `LuaActivity.get(name)` (reads a Lua global from the
 * activity state) — the user project calls `activity.get("_taskFinlshFunction")(data, 1)`.
 * The overlay stub models `LuaActivity` as an `AndroidLuaContext` + android.app.Activity
 * reflection surface, neither of which has a plain `get`, so the member checker fired
 * `checker.member.missing` on every call site. The stub now declares the runtime helper,
 * and this corpus pins both the diagnostic silence and the completion visibility.
 */
class LspLuaActivityGetMemberTddTest {

    @Test
    fun activity_get_call_site_is_not_reported_as_unknown_java_member() {
        val service = service()
        val diagnostics = openAndGetDiagnostics(service, "luaactivity-get.lua", CALL_SITES)

        val memberMissing = diagnostics.filter { codeOf(it) == "checker.member.missing" }
        assertTrue(
            memberMissing.isEmpty(),
            "runtime helper activity.get must not be diagnosed; got ${memberMissing.map { it.message }}"
        )
    }

    @Test
    fun get_is_offered_on_activity_member_completion_surface() {
        val service = service()
        val source = CALL_SITES.trimIndent()
        val uri = "file:///workspace/luaactivity-get-completion.lua"
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))

        // Caret on `get` in `activity.get("x")` on line 1 (0-based characters 9..11).
        for (col in listOf(9, 11)) {
            val labels = service.completion(uri, 1, col).items.map { it.label }
            assertTrue("get" in labels, "col=$col activity. surface must offer runtime helper get; got ${labels.take(30)}")
        }
    }

    @Test
    fun bundled_runtime_reflection_surfaces_real_members_beyond_stub_fields() {
        val service = service()
        val source = CALL_SITES.trimIndent()
        val uri = "file:///workspace/luaactivity-reflection.lua"
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))

        // The bundled com.androlua.LuaActivity class reflects the real surface: runtime
        // helpers (set/call/showToast) AND inherited android.app.Activity methods
        // (getPackageManager) all come from reflection, not hand-written stubs.
        val labels = service.completion(uri, 1, 11).items.map { it.label }.toSet()
        assertTrue("set" in labels, "reflected surface must offer runtime set; got ${labels.take(40)}")
        assertTrue("showToast" in labels, "reflected surface must offer runtime showToast; got ${labels.take(40)}")
        assertTrue(
            "getPackageManager" in labels,
            "reflected surface must offer inherited framework getPackageManager; got ${labels.take(40)}"
        )
    }

    @Test
    fun unrelated_unknown_members_are_still_diagnosed() {
        val service = service()
        val diagnostics = openAndGetDiagnostics(service, "luaactivity-bogus.lua", BOGUS_MEMBER)

        assertTrue(
            diagnostics.any { codeOf(it) == "checker.member.missing" && it.message.contains("noSuchRuntimeHelper") },
            "the stub fix must not silence genuinely unknown members; got ${diagnostics.map { it.message }}"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun codeOf(diagnostic: Diagnostic): String? {
        val code = diagnostic.code ?: return null
        return if (code.isLeft) code.left else code.right?.toString()
    }

    private fun openAndGetDiagnostics(
        service: LuaLanguageService,
        name: String,
        source: String
    ): List<Diagnostic> {
        val uri = "file:///workspace/$name"
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source.trimIndent())))
        return service.diagnosticsForUri(uri).diagnostics
    }

    private fun service(): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
        }
    }

    private companion object {
        /** AndroLua idiom: fetch a global from the activity Lua state and call it. */
        private val CALL_SITES = """
            require "import"
            activity.get("x")
            local fin = activity.get("_taskFinlshFunction")
        """.trimIndent()

        private val BOGUS_MEMBER = """
            require "import"
            local broken = activity.noSuchRuntimeHelper()
        """.trimIndent()
    }
}
