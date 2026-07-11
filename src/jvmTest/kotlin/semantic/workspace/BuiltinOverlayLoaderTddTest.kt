package semantic.workspace

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import org.eclipse.lsp4j.InitializeParams
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltinOverlayLoaderTddTest {
    @Test
    fun default_androlua_builtin_overlay_loads_during_empty_workspace_initialization() {
        LuaLanguageService().initialize(InitializeParams())

        val result = JvmWorkspaceEngine(
            workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false) }
        ).build(LuaWorkspaceInput(emptyMap()))

        assertEquals(LuaVersion.ANDROLUA_5_3, result.snapshot.builtinOverlay.version)
        assertTrue(result.snapshot.files.isEmpty(), "Empty workspace should not create user document snapshots.")

        val socketUrlPath = VirtualPath.of("__lua_std__/androlua5.3/socket.url.lua")
        val socketUrl = assertNotNull(
            result.snapshot.builtinOverlay.providerModules[socketUrlPath],
            "Default AndroLua overlay should include the raw socket.url provider."
        )
        val surface = assertNotNull(socketUrl.file.moduleExportSurface)
        assertEquals("socket.url", socketUrl.moduleName)
        assertEquals("socket.url", surface.moduleType.moduleName)
        assertTrue("parse" in surface.moduleType.methods.keys)

        val parseAnchor = assertNotNull(
            socketUrl.file.documentFacts?.exportWriteAnchors?.singleOrNull { anchor ->
                anchor.rootIdentifier == "url" && anchor.accessPath == listOf("parse")
            },
            "socket.url must parse url.parse(source, defaultUrl); a reserved parameter like 'default' breaks this raw resource."
        )
        assertEquals("parse", parseAnchor.accessPath.single())
    }

    @Test
    fun standard_builtin_member_completion_and_hover_use_documented_details() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local math = require("math")
                local value = math.abs
                return value
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3,
            engine = JvmWorkspaceEngine(
                workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.LUA_5_3, errorRecovery = false) }
            )
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "abs")
        )
        val absCompletion = assertNotNull(completions.singleOrNull { it.label == "abs" })
        assertEquals(CompletionItemKind.FIELD, absCompletion.kind)
        assertEquals("fun(x: number): number", absCompletion.detail)

        val hover = assertNotNull(
            harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "abs")
            )
        )
        assertEquals(SymbolKind.FIELD, hover.symbol?.kind)
        assertEquals("fun(x: number): number", hover.typeInfo?.displayName)
    }

    @Test
    fun standard_builtin_member_details_preserve_generics_and_nested_multi_returns() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local table = require("table")
                local string = require("string")
                local remove = table.remove
                local gmatch = string.gmatch
                return remove, gmatch
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3,
            engine = JvmWorkspaceEngine(
                workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.LUA_5_3, errorRecovery = false) }
            )
        )

        val removeCompletion = assertNotNull(
            harness.queries.completions(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "remove", occurrence = 2)
            ).singleOrNull { it.label == "remove" }
        )
        assertTrue(removeCompletion.detail.orEmpty().contains("fun<V>(list: { [number]: V }, pos: number): V"))

        val gmatchHover = assertNotNull(
            harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "gmatch", occurrence = 2)
            )
        )
        assertEquals(SymbolKind.FIELD, gmatchHover.symbol?.kind)
        assertEquals("fun(s: string, pattern: string): fun(): string, table", gmatchHover.typeInfo?.displayName)
    }

    @Test
    fun androlua_luajava_helper_completion_and_hover_use_documented_details() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                require "import"
                local createProxy = luajava.createProxy
                local bindClass = luajava.bindClass
                return createProxy, bindClass
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3,
            engine = JvmWorkspaceEngine(
                workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false) }
            )
        )

        val createProxyCompletion = assertNotNull(
            harness.queries.completions(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "createProxy", occurrence = 2)
            ).singleOrNull { it.label == "createProxy" }
        )
        assertEquals(CompletionItemKind.METHOD, createProxyCompletion.kind)
        assertTrue(createProxyCompletion.detail.orEmpty().contains("interfaceNames: string"))
        assertTrue(createProxyCompletion.detail.orEmpty().contains("callbacks: { [string]: function }"))
        assertTrue(createProxyCompletion.detail.orEmpty().contains("JavaProxy"))

        val bindClassHover = assertNotNull(
            harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "bindClass", occurrence = 2)
            )
        )
        assertEquals(SymbolKind.METHOD, bindClassHover.symbol?.kind)
        assertEquals("fun(className: string): JavaClass<any>", bindClassHover.typeInfo?.displayName)
    }

    @Test
    fun androlua_luajava_field_completion_and_hover_use_documented_metadata() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                require "import"
                local loaded = luajava.loaded
                local imported = luajava.imported
                local ids = luajava.ids
                local luadir = luajava.luadir
                return loaded, imported, ids, luadir
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3,
            engine = JvmWorkspaceEngine(
                workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false) }
            )
        )

        val loadedCompletion = assertNotNull(
            harness.queries.completions(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "loaded", occurrence = 2)
            ).singleOrNull { it.label == "loaded" }
        )
        assertEquals(CompletionItemKind.FIELD, loadedCompletion.kind)
        assertEquals("{ [string]: JavaClass<any> }", loadedCompletion.detail)

        val importedHover = assertNotNull(
            harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "imported", occurrence = 2)
            )
        )
        assertEquals(SymbolKind.FIELD, importedHover.symbol?.kind)
        assertEquals("string[]", importedHover.typeInfo?.displayName)

        val idsHover = assertNotNull(
            harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "ids", occurrence = 2)
            )
        )
        assertEquals(SymbolKind.FIELD, idsHover.symbol?.kind)
        assertEquals("LuaLayoutIds", idsHover.typeInfo?.displayName)

        val luadirHover = assertNotNull(
            harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "luadir", occurrence = 2)
            )
        )
        assertEquals(SymbolKind.FIELD, luadirHover.symbol?.kind)
        assertEquals("string", luadirHover.typeInfo?.displayName)
    }

    @Test
    fun androlua_android_framework_full_name_aliases_and_overloads_reach_workspace_queries() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                require "import"
                import "android.view.*"
                import "android.view.View.OnClickListener"
                local listener = OnClickListener
                local addView = ViewGroup.__class.addView
                return listener, addView
            """.trimIndent(),
            standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
        )

        val canonicalListenerProvider = assertNotNull(
            harness.snapshot.graph.activeProviders["android.view.View.OnClickListener"]
        )
        val binaryListenerProvider = assertNotNull(
            harness.snapshot.graph.activeProviders["android.view.View\$OnClickListener"]
        )
        assertNotNull(harness.snapshot.builtinOverlay.providerModules[canonicalListenerProvider.path])
        assertNotNull(harness.snapshot.builtinOverlay.providerModules[binaryListenerProvider.path])

        val addViewCompletion = assertNotNull(
            harness.queries.completions(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "addView", occurrence = 2)
            ).singleOrNull { it.label == "addView" }
        )
        assertContains(addViewCompletion.detail.orEmpty(), "view: android.view.View")
        assertContains(addViewCompletion.detail.orEmpty(), "params: android.view.ViewGroup.LayoutParams")
    }
}
