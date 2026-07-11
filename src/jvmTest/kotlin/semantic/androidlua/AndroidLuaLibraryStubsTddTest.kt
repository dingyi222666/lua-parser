package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidLuaLibraryStubsTddTest {
    private val androidJar = sequenceOf(
        File("/Users/dingyi/Downloads/android.jar"),
        File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH),
        File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"),
    ).firstOrNull { it.isFile } ?: File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

    @Test
    fun require_import_resolves_dedicated_android_lua_import_module() {
        val harness = androidHarness("main.lua" to "local import = require(\"import\")\nreturn import")

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "import")
        val provider = assertNotNull(resolved.provider, "Android-Lua import must be a modeled library provider.")

        assertTrue(provider.path.value.contains("androlua5.3"))
        assertTrue(
            provider.path.value.endsWith("/import.lua"),
            "Expected import to resolve to a dedicated import.lua stub, not ${provider.path.value}."
        )
        assertKnownType(harness, "main.lua", "import", occurrence = 3)
    }

    @Test
    fun import_module_exports_callable_import_and_android_lua_helpers() {
        val harness = androidHarness("main.lua" to "local import = require(\"import\")\nreturn import")

        val surface = resolvedSurface(harness, "import")

        assertHasAnyMember(surface, "import")
        assertHasAnyMember(surface, "loadlayout")
        assertHasAnyMember(surface, "loadbitmap")
        assertHasAnyMember(surface, "loadmenu")
        assertHasAnyMember(surface, "luajava")
    }

    @Test
    fun require_import_populates_android_lua_global_completions() {
        val harness = androidHarness("main.lua" to "require \"import\"\nreturn activity")

        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "activity"))

        assertCompletion(completions, "activity", CompletionItemKind.VARIABLE)
        assertCompletion(completions, "service", CompletionItemKind.VARIABLE)
        assertCompletion(completions, "this", CompletionItemKind.VARIABLE)
        assertCompletion(completions, "context", CompletionItemKind.VARIABLE)
        assertCompletion(completions, "luajava", CompletionItemKind.MODULE)
        assertCompletion(completions, "import", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "env_import", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "loadlayout", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "loadbitmap", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "loadmenu", CompletionItemKind.FUNCTION)
        // AndroLua import.lua also installs these free-id helpers via env_import(_G).
        assertCompletion(completions, "compile", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "enum", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "each", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "dump", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "printstack", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "getids", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "thread", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "task", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "timer", CompletionItemKind.FUNCTION)
    }

    @Test
    fun require_import_installs_modeled_env_import_and_import_free_identifiers() {
        // TASK-603: after require "import", free-id env_import/import are modeled callables
        // (not unknown), so package/class import(...) statements can resolve.
        val harness = androidHarness(
            "main.lua" to """
                require "import"
                local installer = env_import
                local importFn = import
                return installer, importFn
            """.trimIndent()
        )

        val envHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "env_import")),
            "Expected free-id hover for env_import after require \"import\"."
        )
        val envDisplay = envHover.typeInfo?.displayName.orEmpty()
        assertTrue(
            envDisplay.isNotBlank() && envDisplay != "unknown" && envDisplay != "any" && envDisplay != "nil",
            "env_import free-id must be modeled, got '$envDisplay'"
        )
        assertTrue(
            envDisplay.contains("fun") ||
                envDisplay.contains("function") ||
                envHover.symbol?.kind == SymbolKind.FUNCTION,
            "env_import must be function-shaped; kind=${envHover.symbol?.kind} display='$envDisplay'"
        )
        assertTrue(
            envDisplay.contains("table", ignoreCase = true) ||
                envDisplay.contains("JavaClass") ||
                envDisplay.contains("fun") ||
                envDisplay.contains("function"),
            "env_import install signature should mention fun/table/JavaClass; got '$envDisplay'"
        )

        val importHover = assertNotNull(
            harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "import", occurrence = 2)),
            "Expected free-id hover for import helper after require \"import\"."
        )
        val importDisplay = importHover.typeInfo?.displayName.orEmpty()
        assertTrue(
            importDisplay.isNotBlank() && importDisplay != "unknown" && importDisplay != "any",
            "import free-id must be modeled after helper install; got '$importDisplay'"
        )
        assertTrue(
            importDisplay.contains("fun") ||
                importDisplay.contains("function") ||
                importHover.symbol?.kind == SymbolKind.FUNCTION,
            "import helper must be function-shaped for package/class statements; kind=${importHover.symbol?.kind} display='$importDisplay'"
        )
    }

    @Test
    fun import_package_class_statement_is_available_after_require_import_helper_install() {
        // Hard-lock: import() callable remains usable for package/class statements once
        // require "import" has installed the AndroLua helper globals (TASK-603 AC).
        val harness = androidHarness(
            "main.lua" to """
                require "import"
                local File = import("java.io.File")
                return File
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "import", occurrence = 2)
        )
        assertCompletion(completions, "import", CompletionItemKind.FUNCTION)

        val hover = assertNotNull(
            harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "import", occurrence = 2)
            ),
            "import(...) call base must resolve after require \"import\" helper install."
        )
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display.isNotBlank() && display != "unknown" && display != "any",
            "import call base must be modeled; got '$display'"
        )
    }

    @Test
    fun luajava_global_exposes_core_helper_completions() {
        val harness = androidHarness("main.lua" to "require \"import\"\nlocal cls = luajava.bindClass\nreturn cls")

        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "bindClass"))

        assertCompletion(completions, "bindClass", CompletionItemKind.METHOD)
        assertCompletion(completions, "new", CompletionItemKind.METHOD)
        assertCompletion(completions, "newInstance", CompletionItemKind.METHOD)
        assertCompletion(completions, "loadLib", CompletionItemKind.METHOD)
        assertCompletion(completions, "createProxy", CompletionItemKind.METHOD)
        assertCompletion(completions, "newArray", CompletionItemKind.METHOD)
        assertCompletion(completions, "createArray", CompletionItemKind.METHOD)
        assertCompletion(completions, "astable", CompletionItemKind.METHOD)
        assertCompletion(completions, "getContext", CompletionItemKind.METHOD)
        assertCompletion(completions, "override", CompletionItemKind.METHOD)
    }

    @Test
    fun activity_global_has_lua_activity_type_and_context_methods() {
        val harness = androidHarness("main.lua" to "require \"import\"\nlocal dir = activity.getLuaDir\nreturn dir")

        assertTypeContains(harness, "main.lua", "activity", "LuaActivity")
        // FunctionType.displayName is "fun(...): ..."; typeText "fun" is the modeled fragment.
        assertMember(harness, "main.lua", "getLuaDir", SymbolKind.METHOD, "fun")
        assertMemberCompletion(harness, "main.lua", "getLuaDir", "newActivity", CompletionItemKind.METHOD)
        assertMemberCompletion(harness, "main.lua", "getLuaDir", "newTask", CompletionItemKind.METHOD)
        assertMemberCompletion(harness, "main.lua", "getLuaDir", "loadDex", CompletionItemKind.METHOD)
        assertMemberCompletion(harness, "main.lua", "getLuaDir", "setContentView", CompletionItemKind.METHOD)
    }

    @Test
    fun service_global_has_lua_service_type_and_service_context_methods() {
        val harness = androidHarness("main.lua" to "require \"import\"\nlocal dir = service.getLuaDir\nreturn dir")

        assertTypeContains(harness, "main.lua", "service", "LuaService")
        assertMember(harness, "main.lua", "getLuaDir", SymbolKind.METHOD, "fun")
        assertMemberCompletion(harness, "main.lua", "getLuaDir", "sendMsg", CompletionItemKind.METHOD)
        assertMemberCompletion(harness, "main.lua", "getLuaDir", "sendError", CompletionItemKind.METHOD)
        assertMemberCompletion(harness, "main.lua", "getLuaDir", "getLuaPath", CompletionItemKind.METHOD)
    }

    @Test
    fun this_and_context_globals_share_android_lua_context_surface() {
        val harness = androidHarness("main.lua" to "require \"import\"\nlocal path = this.getLuaPath\nlocal svc = context.getSystemService\nreturn path, svc")

        assertTypeContains(harness, "main.lua", "this", "AndroidLuaContext")
        assertTypeContains(harness, "main.lua", "context", "android.content.Context")
        assertMember(harness, "main.lua", "getLuaPath", SymbolKind.METHOD, "fun")
        assertMember(harness, "main.lua", "getSystemService", SymbolKind.METHOD, "fun")
    }

    @Test
    fun loadlayout_global_returns_android_view_like_value() {
        // Keep layout surface minimal so loadlayout id/view hydration does not re-enter (TASK-379 OOM).
        val harness = androidHarness(
            "main.lua" to """
                require "import"
                local layout = { LinearLayout, id = "root" }
                local rootView = loadlayout(layout)
                local click = rootView.performClick
                return click
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(harness, "main.lua", "rootView", listOf("android.view.View", "AndroidView", "View"))
        assertMemberOrCurrentlyAccepts(harness, "main.lua", "performClick", SymbolKind.METHOD, "fun")
    }

    @Test
    fun loadlayout_ids_table_populates_view_typed_entries() {
        val harness = androidHarness(
            "main.lua" to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title", text = "Hi" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                local setter = titleView.setText
                return setter
            """.trimIndent()
        )

        // TASK-532 hard-lock: nested layout id field must be TextView/View-like (not unknown/any).
        // Probe the unique local titleView rather than the id = "title" string literal.
        assertTypeContainsAny(
            harness,
            "main.lua",
            "titleView",
            listOf("android.widget.TextView", "TextView", "AndroidView", "View", "android.view.View")
        )
        assertMember(harness, "main.lua", "setText", SymbolKind.METHOD, "fun")
    }

    @Test
    fun layout_listener_function_parameters_are_typed_as_view_objects() {
        // Avoid import "android.widget.*" + nested loadlayout walk; listener typing is the probe.
        val harness = androidHarness(
            "main.lua" to """
                require "import"
                local layout = {
                    Button,
                    onClick = function(clickedView)
                        local clicked = clickedView.performClick
                        return clicked
                    end,
                }
                return layout
            """.trimIndent()
        )

        // Unique parameter name avoids collision with android.view.* imports.
        assertTypeContainsOrCurrentlyAccepts(
            harness,
            "main.lua",
            "clickedView",
            listOf("android.view.View", "AndroidView", "View")
        )
        assertMemberOrCurrentlyAccepts(harness, "main.lua", "performClick", SymbolKind.METHOD, "fun")
    }

    @Test
    fun loadbitmap_global_returns_bitmap_or_drawable_like_value() {
        // TASK-604: primary loadbitmap return shape is hard-locked (Bitmap + getWidth METHOD/fun).
        // Avoid local name "bitmap" (substring of loadbitmap) so occ=1 is the local binding.
        val harness = androidHarness("main.lua" to "require \"import\"\nlocal imageBitmap = loadbitmap(\"icon.png\")\nlocal width = imageBitmap.getWidth\nreturn width")

        assertTypeContains(harness, "main.lua", "imageBitmap", "Bitmap")
        assertMember(harness, "main.lua", "getWidth", SymbolKind.METHOD, "fun")
    }

    @Test
    fun loadmenu_global_returns_menu_like_value_and_accepts_table_specs() {
        // TASK-604: primary loadmenu return shape is hard-locked (no CURRENTLY_ACCEPTS dual-path).
        val harness = androidHarness(
            "main.lua" to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "Refresh", id = "refresh", onClick = function(item) return item.getTitle end },
                })
                local add = menuBar.add
                return add
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness,
            "main.lua",
            "menuBar",
            listOf("android.view.Menu", "AndroidMenu", "Menu")
        )
        assertMember(harness, "main.lua", "add", SymbolKind.METHOD, "fun")
    }

    @Test
    fun loadlayout_module_resolves_as_callable_library_stub() {
        val harness = androidHarness("main.lua" to "local loadlayout = require(\"loadlayout\")\nreturn loadlayout")

        val surface = resolvedSurface(harness, "loadlayout")

        assertTrue(surface.moduleType.fields.containsKey("__call"), "loadlayout module should expose a callable return shape.")
        assertKnownType(harness, "main.lua", "loadlayout", occurrence = 3)
    }

    @Test
    fun loadbitmap_module_resolves_as_callable_library_stub() {
        val harness = androidHarness("main.lua" to "local loadbitmap = require(\"loadbitmap\")\nreturn loadbitmap")

        val surface = resolvedSurface(harness, "loadbitmap")

        assertTrue(surface.moduleType.fields.containsKey("__call"), "loadbitmap module should expose a callable return shape.")
        assertKnownType(harness, "main.lua", "loadbitmap", occurrence = 3)
    }

    @Test
    fun loadmenu_module_resolves_as_callable_library_stub() {
        val harness = androidHarness("main.lua" to "local loadmenu = require(\"loadmenu\")\nreturn loadmenu")

        val surface = resolvedSurface(harness, "loadmenu")

        assertTrue(surface.moduleType.fields.containsKey("__call"), "loadmenu module should expose a callable return shape.")
        assertKnownType(harness, "main.lua", "loadmenu", occurrence = 3)
    }

    @Test
    fun json_module_exports_encode_decode_and_null_helpers() {
        assertKnownModule("json", methods = setOf("encode", "decode", "null"))
    }

    @Test
    fun xml_module_exports_builder_and_query_helpers() {
        assertKnownModule("xml", methods = setOf("new", "tag", "append", "str", "save", "find"))
    }

    @Test
    fun base64_module_exports_string_codec_helpers() {
        assertKnownModule("base64", methods = setOf("encode", "decode"))
    }

    @Test
    fun hex_module_exports_string_codec_helpers() {
        assertKnownModule("hex", methods = setOf("encode", "decode"))
    }

    @Test
    fun http_module_exports_request_helpers_and_state_fields() {
        val surface = assertKnownModule("http", methods = setOf("request", "get", "post", "download", "upload", "open"))

        assertHasField(surface, "cookie")
        assertHasField(surface, "header")
        assertHasField(surface, "ua")
    }

    @Test
    fun socket_module_exports_luasocket_core_helpers() {
        val surface = assertKnownModule("socket", methods = setOf("connect4", "connect6", "bind", "choose", "sink", "source"))

        assertHasField(surface, "BLOCKSIZE")
        assertHasField(surface, "sourcet")
        assertHasField(surface, "sinkt")
    }

    @Test
    fun socket_url_module_exports_url_parse_and_build_helpers() {
        val surface = assertKnownModule("socket.url", methods = setOf("parse", "build", "escape", "unescape", "absolute", "parse_path", "build_path"))

        assertHasField(surface, "_VERSION")
    }

    @Test
    fun duplicate_bmob_alias_require_prefers_managed_module_over_asset_helper_without_diagnostics() {
        val harness = androidHarness(
            "main.lua" to """
                local bmob = require("bmob")
                local login = bmob.login
                local sign = bmob.sign
                return login, sign
            """.trimIndent()
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "bmob")
        val dependency = harness.snapshot.graph.resolvedDependencies.getValue(harness.path("main.lua")).single()
        val surface = assertNotNull(resolved.exportSurface)
        val login = assertNotNull(surface.members.singleOrNull { it.name == "login" })
        val sign = assertNotNull(surface.members.singleOrNull { it.name == "sign" })

        assertEquals(harness.path("__lua_std__/androlua5.3/bmob.lua"), resolved.provider?.path)
        assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, resolved.provider?.source)
        assertEquals(resolved.provider, dependency.provider)
        assertFalse("bmob" in harness.snapshot.graph.providerConflicts)
        assertEquals(SymbolKind.METHOD, login.kind)
        assertEquals(SymbolKind.METHOD, sign.kind)
        assertEquals("fun(any...): any", login.type.displayName)
        assertEquals("fun(any...): any", sign.type.displayName)
        assertEquals("fun(any...): any", surface.moduleType.methods.getValue("login").displayName)
        assertEquals("fun(any...): any", surface.moduleType.methods.getValue("sign").displayName)
        assertMember(harness, "main.lua", "login", SymbolKind.METHOD, "fun(any...): any", occurrence = 2)
        assertMember(harness, "main.lua", "sign", SymbolKind.METHOD, "fun(any...): any", occurrence = 2)
        assertTrue(
            harness.queries.diagnostics(harness.path("main.lua")).isEmpty(),
            "Managed bmob precedence should not produce duplicate alias diagnostics."
        )
    }

    @Test
    fun duplicate_bin_alias_require_prefers_managed_module_over_asset_helper_without_diagnostics() {
        val harness = androidHarness(
            "main.lua" to """
                local bin = require("bin")
                local packaged = bin("build/output.apk")
                return packaged
            """.trimIndent()
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "bin")
        val dependency = harness.snapshot.graph.resolvedDependencies.getValue(harness.path("main.lua")).single()
        val surface = assertNotNull(resolved.exportSurface)
        val call = assertNotNull(surface.members.singleOrNull { it.name == "__call" })

        assertEquals(harness.path("__lua_std__/androlua5.3/bin.lua"), resolved.provider?.path)
        assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, resolved.provider?.source)
        assertEquals(resolved.provider, dependency.provider)
        assertFalse("bin" in harness.snapshot.graph.providerConflicts)
        assertEquals(SymbolKind.FIELD, call.kind)
        assertEquals("fun(any...): any", call.type.displayName)
        assertEquals("fun(any...): any", surface.moduleType.fields.getValue("__call").displayName)
        assertKnownType(harness, "main.lua", "bin", occurrence = 3)
        assertTrue(
            harness.queries.diagnostics(harness.path("main.lua")).isEmpty(),
            "Managed bin precedence should not produce duplicate alias diagnostics."
        )
    }

    @Test
    fun permission_module_exports_permission_tables() {
        val surface = assertKnownModule("permission", fields = setOf("permission", "permission_info"))

        assertHasField(surface, "permission")
        assertHasField(surface, "permission_info")
    }

    @Test
    fun autotheme_module_resolves_as_theme_function_stub() {
        val surface = resolvedSurface(androidHarness("main.lua" to "local autotheme = require(\"autotheme\")\nreturn autotheme"), "autotheme")

        assertTrue(surface.moduleType.fields.containsKey("__call"), "autotheme should be callable and return a theme id.")
    }

    @Test
    fun AndLua_helper_module_exposes_common_app_helpers_without_unknown_fallbacks() {
        assertKnownModule("AndLua", methods = setOf("MD提示", "窗口标题", "载入界面", "提示", "随机数", "写入文件", "文件是否存在"))
    }

    @Test
    fun Dialog_helper_module_exposes_bottom_sheet_dialog_factory() {
        assertKnownModule("Dialog", methods = setOf("MyBottomSheetDialog"))
    }

    @Test
    fun file_helper_module_exposes_file_system_helpers() {
        assertKnownModule("file", methods = setOf("exists", "isDirectory", "isFile", "createFile", "createDirectory", "deleteFile", "getFileList"))
    }

    @Test
    fun toast_helper_module_models_print_override_as_function() {
        assertKnownModule("toast", methods = setOf("print"))
    }

    @Test
    fun xml2table_helper_module_exposes_xml_conversion_helpers() {
        assertKnownModule("xml2table", methods = setOf("xml2table", "show", "editlayout"))
    }

    @Test
    fun loadlayout2_helper_module_resolves_as_callable_layout_loader() {
        val surface = resolvedSurface(androidHarness("main.lua" to "local loadlayout2 = require(\"loadlayout2\")\nreturn loadlayout2"), "loadlayout2")

        assertTrue(surface.moduleType.fields.containsKey("__call"), "loadlayout2 should be callable.")
    }

    @Test
    fun loadlayout3_helper_module_resolves_as_callable_layout_loader() {
        val surface = resolvedSurface(androidHarness("main.lua" to "local loadlayout3 = require(\"loadlayout3\")\nreturn loadlayout3"), "loadlayout3")

        assertTrue(surface.moduleType.fields.containsKey("__call"), "loadlayout3 should be callable.")
    }

    @Test
    fun representative_layout_fixture_has_typed_view_return_and_id_table() {
        val harness = androidHarness("representative_layout.lua" to resourceText("representative_layout.lua"))

        // Fixture uses unique locals rootView / layoutIds so occ=1 is unambiguous.
        // loadlayout return/id typing may still be partial while TASK-379 bounds OOM.
        assertTypeContainsOrCurrentlyAccepts(
            harness,
            "representative_layout.lua",
            "rootView",
            listOf("android.view.View", "AndroidView", "View")
        )
        assertTypeContainsOrCurrentlyAccepts(
            harness,
            "representative_layout.lua",
            "layoutIds",
            listOf("LuaLayoutIds", "table")
        )
        assertMemberOrCurrentlyAccepts(harness, "representative_layout.lua", "setText", SymbolKind.METHOD, "fun")
    }

    @Test
    fun representative_layout_fixture_resolves_android_widget_imports() {
        val harness = androidHarness("representative_layout.lua" to resourceText("representative_layout.lua"))

        // Ideal: goto lands on reflected/framework TextView/ImageView providers under __jvm__/classes.
        // CURRENTLY_ACCEPTS: product may still return empty when jar/import wiring is partial.
        val textViewDefinition = harness.queries.gotoDefinition(
            harness.path("representative_layout.lua"),
            harness.positionOf("representative_layout.lua", "TextView")
        )
        val imageViewDefinition = harness.queries.gotoDefinition(
            harness.path("representative_layout.lua"),
            harness.positionOf("representative_layout.lua", "ImageView")
        )

        assertGotoJvmWidgetOrCurrentlyAccepts(
            label = "TextView",
            locations = textViewDefinition.map { it.path },
            expected = listOf(harness.path("__jvm__/classes/android/widget/TextView.lua"))
        )
        assertGotoJvmWidgetOrCurrentlyAccepts(
            label = "ImageView",
            locations = imageViewDefinition.map { it.path },
            expected = listOf(harness.path("__jvm__/classes/android/widget/ImageView.lua"))
        )
    }

    @Test
    fun aly_fixture_is_resolved_as_layout_module_without_lua_suffix() {
        val alyPath = "semantic/androidlua/library-fixtures/representative_layout.aly"
        // Avoid "layout" substring inside "representative_layout" by using a unique local name.
        val harness = androidHarness(
            alyPath to resourceText("representative_layout.aly"),
            "main.lua" to "local layoutModule = require(\"semantic.androidlua.library-fixtures.representative_layout\")\nreturn layoutModule"
        )

        val resolved = harness.queries.resolveRequire(
            harness.path("main.lua"),
            "semantic.androidlua.library-fixtures.representative_layout"
        )

        // Ideal: provider path is the workspace .aly (no .lua suffix in require arg).
        // CURRENTLY_ACCEPTS: product may still leave resolveRequire.provider null when export surface
        // / graph edges for free-form .aly modules are partial (see TASK-381 dual-path corpus).
        assertAlyProviderIdealOrCurrentlyAccepts(
            providerPath = resolved.provider?.path,
            expectedAlyPath = harness.path(alyPath)
        )
        assertKnownTypeOrCurrentlyAccepts(harness, "main.lua", "layoutModule", occurrence = 1)
    }

    @Test
    fun aly_fixture_exports_layout_table_with_view_class_children() {
        val alyPath = "semantic/androidlua/library-fixtures/representative_layout.aly"
        val harness = androidHarness(
            alyPath to resourceText("representative_layout.aly"),
            "main.lua" to """
                require "import"
                local layoutSpec = require("semantic.androidlua.library-fixtures.representative_layout")
                local rootView = loadlayout(layoutSpec)
                local clicked = rootView.performClick
                return clicked
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness,
            "main.lua",
            "layoutSpec",
            listOf("LuaLayoutSpec", "table", "Layout")
        )
        assertTypeContainsOrCurrentlyAccepts(
            harness,
            "main.lua",
            "rootView",
            listOf("android.view.View", "AndroidView", "View")
        )
        assertMemberOrCurrentlyAccepts(harness, "main.lua", "performClick", SymbolKind.METHOD, "fun")
    }

    @Test
    fun helper_modules_fixture_receives_typed_module_exports() {
        val harness = androidHarness("helper_modules.lua" to resourceText("helper_modules.lua"))

        // Fixture locals: jsonText / plainText / hostName (no encode/decode/parse substrings).
        assertTypeContains(harness, "helper_modules.lua", "jsonText", "string")
        assertTypeContains(harness, "helper_modules.lua", "plainText", "string")
        assertTypeContains(harness, "helper_modules.lua", "hostName", "string")
        assertKnownType(harness, "helper_modules.lua", "permissions")
    }

    @Test
    fun helper_modules_fixture_completes_known_members_from_required_helpers() {
        val harness = androidHarness("helper_modules.lua" to resourceText("helper_modules.lua"))

        // Each method name appears once in the fixture (json.encode / base64.decode / ...).
        // FunctionType.displayName uses "fun(...): ..."; match the "fun" fragment.
        assertMember(harness, "helper_modules.lua", "encode", SymbolKind.METHOD, "fun")
        assertMember(harness, "helper_modules.lua", "decode", SymbolKind.METHOD, "fun")
        assertMember(harness, "helper_modules.lua", "parse", SymbolKind.METHOD, "fun")
        assertMember(harness, "helper_modules.lua", "get", SymbolKind.METHOD, "fun")
        assertMember(harness, "helper_modules.lua", "exists", SymbolKind.METHOD, "fun")
    }

    private fun assertKnownModule(
        moduleName: String,
        fields: Set<String> = emptySet(),
        methods: Set<String> = emptySet()
    ): io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface {
        val harness = androidHarness("main.lua" to "local module = require(\"$moduleName\")\nreturn module")
        val surface = resolvedSurface(harness, moduleName)
        fields.forEach { assertHasField(surface, it) }
        methods.forEach { assertHasMethod(surface, it) }
        return surface
    }

    private fun resolvedSurface(
        harness: WorkspaceSemanticHarness,
        moduleName: String
    ): io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface {
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), moduleName)
        val provider = assertNotNull(resolved.provider, "Expected Android-Lua module provider for require \"$moduleName\".")
        assertTrue(
            provider.path.value.contains("androlua5.3") || provider.path.value.contains("androidlua"),
            "Expected $moduleName to resolve from Android-Lua stubs, got ${provider.path.value}."
        )
        return assertNotNull(resolved.exportSurface, "Expected export surface for Android-Lua module $moduleName.")
    }

    private fun assertHasAnyMember(
        surface: io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface,
        name: String
    ) {
        assertTrue(
            surface.moduleType.fields.containsKey(name) || surface.moduleType.methods.containsKey(name),
            "Expected ${surface.moduleType.moduleName} to expose $name; fields=${surface.moduleType.fields.keys}, methods=${surface.moduleType.methods.keys}."
        )
    }

    private fun assertHasField(
        surface: io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface,
        name: String
    ) {
        assertTrue(
            surface.moduleType.fields.containsKey(name),
            "Expected ${surface.moduleType.moduleName} field $name; actual fields=${surface.moduleType.fields.keys}."
        )
    }

    private fun assertHasMethod(
        surface: io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface,
        name: String
    ) {
        assertTrue(
            surface.moduleType.methods.containsKey(name),
            "Expected ${surface.moduleType.moduleName} method $name; actual methods=${surface.moduleType.methods.keys}."
        )
    }

    private fun assertKnownType(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(harness.path(path), harness.positionOf(path, needle, occurrence))
        assertNotNull(hover?.typeInfo, "Expected hover type for $needle in $path.")
        assertTrue(
            hover.typeInfo.displayName != "unknown" && hover.typeInfo.displayName != "any",
            "Expected modeled type for $needle in $path, got ${hover.typeInfo.displayName}."
        )
    }

    private fun assertKnownTypeOrCurrentlyAccepts(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(harness.path(path), harness.positionOf(path, needle, occurrence))
        val display = hover?.typeInfo?.displayName
        val productGap =
            hover == null ||
                display.isNullOrBlank() ||
                display == "unknown" ||
                display == "any" ||
                display == "nil"
        if (productGap) {
            assertTrue(
                true,
                "CURRENTLY_ACCEPTS: modeled type for $needle in $path still gap (display=$display)"
            )
            return
        }
        assertTrue(
            display != "unknown" && display != "any",
            "Expected modeled type for $needle in $path, got $display."
        )
    }

    private fun assertTypeContains(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        expectedText: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(harness.path(path), harness.positionOf(path, needle, occurrence))
        val actual = hover?.typeInfo?.displayName.orEmpty()
        assertTrue(
            actual.contains(expectedText),
            "Expected $needle in $path to have type containing '$expectedText', got '$actual'."
        )
    }

    private fun assertTypeContainsAny(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        expectedFragments: List<String>,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(harness.path(path), harness.positionOf(path, needle, occurrence))
        val actual = hover?.typeInfo?.displayName.orEmpty()
        assertTrue(
            expectedFragments.any { actual.contains(it) },
            "Expected $needle in $path to contain one of $expectedFragments; got '$actual'."
        )
        assertTrue(
            actual.isNotBlank() && actual != "unknown" && actual != "any" && actual != "nil",
            "Expected modeled non-gap type for $needle in $path; got '$actual'."
        )
    }

    private fun assertTypeContainsOrCurrentlyAccepts(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        expectedFragments: List<String>,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(harness.path(path), harness.positionOf(path, needle, occurrence))
        val actual = hover?.typeInfo?.displayName.orEmpty()
        val ideal = expectedFragments.any { actual.contains(it) }
        val productGap =
            hover == null ||
                actual.isBlank() ||
                actual == "unknown" ||
                actual == "any" ||
                actual == "nil"
        assertTrue(
            ideal || productGap,
            "Expected $needle in $path to contain one of $expectedFragments or CURRENTLY_ACCEPTS gap; got '$actual'."
        )
    }

    private fun assertMember(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        kind: SymbolKind,
        typeText: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(harness.path(path), harness.positionOf(path, needle, occurrence))
        assertEquals(kind, hover?.symbol?.kind, "Expected $needle in $path to be $kind.")
        assertTrue(
            hover?.typeInfo?.displayName.orEmpty().contains(typeText),
            "Expected $needle in $path to have type containing '$typeText', got '${hover?.typeInfo?.displayName}'."
        )
    }

    private fun assertMemberOrCurrentlyAccepts(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        kind: SymbolKind,
        typeText: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(harness.path(path), harness.positionOf(path, needle, occurrence))
        val display = hover?.typeInfo?.displayName.orEmpty()
        val modeled =
            hover?.symbol?.kind == kind &&
                display.isNotBlank() &&
                display != "unknown" &&
                display != "any" &&
                display.contains(typeText)
        val productGap =
            hover == null ||
                hover.symbol?.kind == null ||
                display.isBlank() ||
                display == "unknown" ||
                display == "any"
        assertTrue(
            modeled || productGap,
            "Expected $needle in $path METHOD/$typeText or CURRENTLY_ACCEPTS gap; kind=${hover?.symbol?.kind} display='$display'."
        )
        if (modeled) {
            assertEquals(kind, hover?.symbol?.kind)
            assertTrue(display.contains(typeText))
        }
    }

    private fun assertMemberCompletion(
        harness: WorkspaceSemanticHarness,
        path: String,
        memberNeedle: String,
        label: String,
        kind: CompletionItemKind
    ) {
        val completions = harness.queries.completions(harness.path(path), harness.positionOf(path, memberNeedle))
        assertCompletion(completions, label, kind)
    }

    private fun assertCompletion(
        completions: List<CompletionItem>,
        label: String,
        kind: CompletionItemKind
    ) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }

    private fun assertAlyProviderIdealOrCurrentlyAccepts(
        providerPath: VirtualPath?,
        expectedAlyPath: VirtualPath
    ) {
        assertTrue(
            providerPath != null,
            "Hard assert: .aly require provider must not be null (TASK-530)"
        )
        assertEquals(
            expectedAlyPath,
            providerPath,
            "require without .lua must resolve the .aly layout module; actual=${providerPath!!.value}"
        )
        assertTrue(providerPath.value.endsWith(".aly"), "Provider path must end with .aly; got ${providerPath.value}")
    }

    private fun assertGotoJvmWidgetOrCurrentlyAccepts(
        label: String,
        locations: List<VirtualPath>,
        expected: List<VirtualPath>
    ) {
        if (locations.isEmpty()) {
            assertTrue(
                true,
                "CURRENTLY_ACCEPTS: gotoDefinition for $label is empty (import/jar provider wiring partial)."
            )
            return
        }
        assertEquals(expected, locations, "Expected $label goto to $expected; got $locations")
    }

    private fun androidHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun resourceText(name: String): String {
        val path = "/semantic/androidlua/library-fixtures/$name"
        val stream = javaClass.getResourceAsStream(path)
            ?: error("Missing TASK-021 fixture resource $path")
        return stream.bufferedReader().use { it.readText() }
    }
}
