package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentItem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AndroLua project scripts share one Lua global environment: main.lua imports
 * `mods.dingyi` (defines the global `FileUtil = {}` + `FileUtil.saveBitmap = function`)
 * and then `mods.util`, whose code reads `FileUtil.saveBitmap(...)` — with NO require /
 * import edge between the two mods. The workspace resolver now falls back to other
 * project files' chunk-level globals so the runtime-visible global resolves for
 * diagnostics, hover, and member completion.
 */
class LspWorkspaceSharedGlobalTddTest {

    @Test
    fun cross_file_global_read_is_not_reported_unresolved() {
        val service = workspaceService()
        openProject(service)
        val diagnostics = diagnostics(service, "file:///workspace/mods/util.lua")

        val fileUtilWarnings = diagnostics.filter {
            codeOf(it) == "checker.global.unresolved" && it.message.contains("FileUtil")
        }
        assertTrue(
            fileUtilWarnings.isEmpty(),
            "runtime-shared global FileUtil must resolve; got ${fileUtilWarnings.map { it.message }}"
        )
    }

    @Test
    fun cross_file_global_member_completion_lists_defining_file_members() {
        val service = workspaceService()
        openProject(service)
        val uri = "file:///workspace/mods/util.lua"

        // Line 3: `  local i,e=pcall(function()FileUtil.saveBitmap(icon,...)` — caret on
        // `saveBitmap` (0-based characters 37..46).
        val labels = service.completion(uri, 3, 42).items.map { it.label }
        assertTrue(
            "saveBitmap" in labels,
            "FileUtil. surface must offer members defined in mods/dingyi.lua; got $labels"
        )
    }

    @Test
    fun unknown_globals_still_report_unresolved() {
        val service = workspaceService()
        openProject(service)
        val diagnostics = diagnostics(service, "file:///workspace/mods/util.lua")

        assertTrue(
            diagnostics.any {
                codeOf(it) == "checker.global.unresolved" && it.message.contains("brokenHelper")
            },
            "the shared-global fallback must not silence genuinely unknown globals"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun openProject(service: LuaLanguageService) {
        FILES.forEach { (path, source) ->
            val uri = "file:///workspace/$path"
            service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source.trimIndent())))
        }
    }

    private fun diagnostics(service: LuaLanguageService, uri: String): List<Diagnostic> =
        service.diagnosticsForUri(uri).diagnostics

    private companion object {
        private val FILES = listOf(
            "main.lua" to """
                require "import"
                import "mods.dingyi"
                import "mods.util"
            """,
            "mods/dingyi.lua" to """
                require "import"
                FileUtil={}
                FileUtil.saveBitmap=function(bitmap,path)
                end
            """,
            "mods/util.lua" to """
                require "import"
                local function getAppIcon(p)
                  local icon = p
                  local i,e=pcall(function()FileUtil.saveBitmap(icon,"/sdcard/Icon/"..i) end)
                  if not i then print(e) end
                  brokenHelper()
                end
                return getAppIcon
            """
        )
    }
}
