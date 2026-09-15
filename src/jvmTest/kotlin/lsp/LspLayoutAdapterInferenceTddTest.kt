package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * loadlayout id + adapter inference: `loadlayout({... id = "poplist" ... adapter =
 * LuaMultiAdapter(activity, {...}) ...}, tab)` binds `tab.poplist` to the view AND —
 * because loadlayout applies `adapter=` through the matching setter and reading the
 * property back returns the constructed value — `tab.poplist.adapter` must type as the
 * LuaMultiAdapter instance, so `.clear()` / `.add{...}` resolve from the bundled
 * runtime reflection.
 */
class LspLayoutAdapterInferenceTddTest {

    @Test
    fun id_view_member_completion_offers_adapter_methods() {
        val service = service()
        val uri = open(service)

        // Line 12: `tab.poplist.adapter.clear()` — caret on `clear` (0-based chars 20..24).
        val labels = service.completion(uri, 12, 22).items.map { it.label }
        assertTrue("clear" in labels, "adapter surface must offer clear; got $labels")
        assertTrue("add" in labels, "adapter surface must offer add; got $labels")
        assertTrue("addAll" in labels, "adapter surface must offer addAll; got $labels")
    }

    @Test
    fun id_view_hover_keeps_view_class_surface() {
        val service = service()
        val uri = open(service)

        // Hover on `poplist` in `tab.poplist.adapter.clear()` (0-based chars 4..10).
        val hover = service.hover(HoverParams(document(uri), org.eclipse.lsp4j.Position(12, 6)))
        val markup = hoverMarkup(hover)
        assertTrue(
            markup.contains("ListView") || markup.contains("poplist"),
            "tab.poplist hover must keep the view surface; got: $markup"
        )
    }

    @Test
    fun demo_configuration_keeps_runtime_adapters_reachable_and_capital_properties_resolvable() {
        // ListView surfaces need a real android.jar; soft-skip without a host SDK.
        val platforms = java.io.File(
            System.getProperty("user.home"),
            "Library/Android/sdk/platforms"
        )
        val androidJar = platforms.listFiles()
            ?.filter { it.name.startsWith("android-") }
            ?.sortedByDescending { it.name }
            ?.firstNotNullOfOrNull { dir -> dir.resolve("android.jar").takeIf { it.isFile } }
            ?: return
        val service = service()
        // The Monaco demo sends jvm.importPrefixes WITHOUT com.androlua; the bundled
        // runtime package must stay reachable anyway (prefix union, not replacement).
        io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService(service).didChangeConfiguration(
            org.eclipse.lsp4j.DidChangeConfigurationParams(
                mapOf(
                    "jvm.androidJar" to androidJar.path,
                    "jvm.importPrefixes" to listOf(
                        "java.lang", "java.util", "android.app", "android.content",
                        "android.view", "android.view.View", "android.widget"
                    ),
                    "androlua.imports" to listOf("Activity", "View", "TextView")
                )
            )
        )
        val uri = open(service)

        val labels = service.completion(uri, 12, 22).items.map { it.label }
        assertTrue("clear" in labels, "configured prefixes must not sever runtime adapters; got $labels")

        // AndroLua accepts the capital-first property spelling (runtime luajava matches
        // bean properties case-insensitively); `tab.poplist.OnItemClickListener = {...}`
        // must not diagnose as an unknown Java member.
        val diagnostics = service.diagnosticsForUri(uri).diagnostics
        val memberMissing = diagnostics.filter {
            val code = it.code
            val codeText = when {
                code == null -> ""
                code.isLeft -> code.left.orEmpty()
                else -> code.right.toString()
            }
            codeText == "checker.member.missing"
        }
        assertTrue(
            memberMissing.isEmpty(),
            "no member-missing diagnostics expected on the layout sample; got ${memberMissing.map { it.message }}"
        )
    }

    // --- helpers -----------------------------------------------------------------

    private fun document(uri: String) = org.eclipse.lsp4j.TextDocumentIdentifier(uri)

    private fun hoverMarkup(hover: org.eclipse.lsp4j.Hover?): String {
        val contents = hover?.contents ?: return ""
        return when {
            contents.isLeft -> contents.left?.toString().orEmpty()
            else -> contents.right?.value.orEmpty()
        }
    }

    private fun open(service: LuaLanguageService): String {
        val uri = "file:///workspace/popup.lua"
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, SOURCE.trimIndent())))
        return uri
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
        private val SOURCE = """
            require "import"
            import "android.widget.*"
            import "android.view.*"

            local tab = {}
            local pop = PopupWindow(activity)
            pop.setContentView(loadlayout({
              ListView;
              layout_height = "-1";
              id = "poplist";
              adapter = LuaMultiAdapter(activity, { { } });
            }, tab))
            tab.poplist.adapter.clear()
        """.trimIndent()
    }
}
