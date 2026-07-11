package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltinOverlayLoaderTest {
    @Test
    fun lua53_overlay_preserves_emmylua_math_module_resource_details() {
        val lua53 = load(LuaVersion.LUA_5_3)
        val mathPath = lua53.providerModules.entries.first { it.value.moduleName == "math" }.key
        val sources = mutableMapOf<String, String>()
        BuiltinOverlayLoader.load(LuaVersion.LUA_5_3) { path, source ->
            sources[path.value] = source
            WorkspaceSnapshot.FileSnapshot()
        }

        val mathSource = sources.getValue(mathPath.value)
        assertContains(mathSource, "---@param x number")
        assertContains(mathSource, "function math.abs(x)")
    }

    @Test
    fun lua53_overlay_exposes_documented_member_type_details() {
        val lua53 = load(LuaVersion.LUA_5_3)
        val math = lua53.providerModules.values.first { it.moduleName == "math" }
        val surface = assertNotNull(math.file.moduleExportSurface)

        val abs = assertNotNull(surface.members.singleOrNull { it.name == "abs" })
        assertEquals(SymbolKind.FIELD, abs.kind)
        assertEquals("fun(x: number): number", abs.type.displayName)

        val huge = assertNotNull(surface.members.singleOrNull { it.name == "huge" })
        assertEquals(SymbolKind.FIELD, huge.kind)
        assertEquals("number", huge.type.displayName)

        val random = assertNotNull(surface.members.singleOrNull { it.name == "random" })
        assertContains(random.type.displayName, "fun(m: number, n: number): number")
        assertContains(random.type.displayName, "fun(): number")
    }

    @Test
    fun lua53_overlay_preserves_generic_and_nested_function_return_details() {
        val sources = mutableMapOf<String, String>()
        val lua53 = BuiltinOverlayLoader.load(LuaVersion.LUA_5_3) { path, source ->
            sources[path.value] = source
            WorkspaceSnapshot.FileSnapshot()
        }
        val globalsSource = sources.getValue("__lua_std__/5.3/_G.lua")
        assertContains(globalsSource, "---@generic V")
        assertContains(globalsSource, "---@return fun(tbl: table<number, V>):number, V")
        assertContains(globalsSource, "---@generic K, V")
        assertContains(globalsSource, "---@return fun(tbl: table<K, V>):K, V")

        val tableSurface = assertNotNull(lua53.providerModules.values.first { it.moduleName == "table" }.file.moduleExportSurface)
        val removeType = assertIs<OverloadedFunctionType>(assertNotNull(tableSurface.members.singleOrNull { it.name == "remove" }).type)
        val removePrimary = removeType.callSignatures.single { signature -> signature.parameters.size == 2 }
        assertEquals(listOf("V"), removePrimary.typeParameters.map { it.name })
        assertEquals("fun<V>(list: { [number]: V }, pos: number): V", removePrimary.displayName)

        val stringSurface = assertNotNull(lua53.providerModules.values.first { it.moduleName == "string" }.file.moduleExportSurface)
        val gmatch = assertIs<FunctionType>(assertNotNull(stringSurface.members.singleOrNull { it.name == "gmatch" }).type)
        val iterator = assertIs<FunctionType>(gmatch.returnType)
        val iteratorReturn = assertIs<MultiReturnType>(iterator.returnType)
        assertEquals(listOf("string", "table"), iteratorReturn.types.map { it.displayName })
        assertEquals("fun(): string, table", iterator.displayName)
    }

    @Test
    fun lua53_builtin_generic_returns_reach_completion_and_signature_help() {
        val path = VirtualPath.of("main.lua")
        val source = """
            local xs = { one = 1 }
            local item = pairs(xs)
            local indexed = ipairs({ 1, 2 })
        """.trimIndent()
        val result = LuaWorkspaceEngine().build(
            LuaWorkspaceInput(
                files = mapOf(path to source),
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        )
        val model = assertNotNull(result.snapshot.files.getValue(path).semanticFile).model

        val pairsCompletion = assertNotNull(model.getCompletionsAt(positionOf(source, "item")).singleOrNull { it.label == "pairs" })
        assertContains(assertNotNull(pairsCompletion.detail), "fun<K, V>(t: { [K]: V } | V[]): fun(tbl: { [K]: V }): K, V")

        val pairsSignature = assertNotNull(model.getSignatureHelpAt(positionOf(source, "xs)"))).signatures.single()
        assertContains(pairsSignature.label, "fun<K, V>(t: { [K]: V } | V[]): fun(tbl: { [K]: V }): K, V")
        assertEquals(listOf("t: { [K]: V } | V[]"), pairsSignature.parameters.map { it.label })

        val ipairsSignature = assertNotNull(model.getSignatureHelpAt(positionOf(source, "1, 2"))).signatures.single()
        assertContains(ipairsSignature.label, "fun<V>(t: { [number]: V } | V[]): fun(tbl: { [number]: V }): number, V")
    }

    @Test
    fun lua53_overlay_contains_bit32_and_lua54_does_not() {
        val lua53 = load(LuaVersion.LUA_5_3)
        val lua54 = load(LuaVersion.LUA_5_4)

        assertTrue("bit32" in lua53.globals.globalNames)
        assertFalse("bit32" in lua54.globals.globalNames)
        assertFalse(lua53.providerModules.values.any { it.moduleName == "bit32" })
        assertFalse(lua54.providerModules.values.any { it.moduleName == "bit32" })
    }

    @Test
    fun lua54_globals_contain_warn_and_lua53_does_not() {
        val lua53 = load(LuaVersion.LUA_5_3)
        val lua54 = load(LuaVersion.LUA_5_4)

        assertFalse("warn" in lua53.globals.globalNames)
        assertTrue("warn" in lua54.globals.globalNames)
    }

    @Test
    fun package_metadata_exposes_seeall_for_legacy_fallback() {
        val lua53 = load(LuaVersion.ANDROLUA_5_3)
        val lua54 = load(LuaVersion.LUA_5_4)

        assertTrue("seeall" in lua53.globals.moduleFieldNames.getValue("package"))
        assertTrue("seeall" in lua54.globals.moduleFieldNames.getValue("package"))
    }

    @Test
    fun androlua_overlay_exposes_android_lua_helper_globals() {
        val androlua = load(LuaVersion.ANDROLUA_5_3)
        val lua53 = load(LuaVersion.LUA_5_3)

        assertTrue("import" in androlua.globals.globalNames)
        assertTrue("loadbitmap" in androlua.globals.globalNames)
        assertTrue("loadlayout" in androlua.globals.globalNames)
        assertTrue("loadmenu" in androlua.globals.globalNames)
        assertFalse("import" in lua53.globals.globalNames)
        assertFalse("loadbitmap" in lua53.globals.globalNames)
        assertFalse("loadlayout" in lua53.globals.globalNames)
        assertFalse("loadmenu" in lua53.globals.globalNames)
    }

    @Test
    fun androlua_duplicate_helper_names_prefer_managed_modules_over_asset_helpers() {
        val sources = mutableMapOf<String, String>()
        val androlua = BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { path, source ->
            sources[path.value] = source
            WorkspaceSnapshot.FileSnapshot()
        }
        val bmobPath = androlua.providerModules.entries.single { it.value.moduleName == "bmob" }.key
        val binPath = androlua.providerModules.entries.single { it.value.moduleName == "bin" }.key
        val bmobSurface = assertNotNull(androlua.providerModules.getValue(bmobPath).file.moduleExportSurface)
        val binSurface = assertNotNull(androlua.providerModules.getValue(binPath).file.moduleExportSurface)

        assertEquals("__lua_std__/androlua5.3/bmob.lua", bmobPath.value)
        assertEquals("__lua_std__/androlua5.3/bin.lua", binPath.value)
        assertContains(sources.getValue(bmobPath.value), "Module model for resources/lua/bmob.lua.")
        assertContains(sources.getValue(binPath.value), "Module model for resources/lua/bin.lua.")
        assertFalse("Asset helper model for assets/bmob.lua." in sources.getValue(bmobPath.value))
        assertFalse("Asset helper model for assets/bin.lua." in sources.getValue(binPath.value))

        val login = assertNotNull(bmobSurface.members.singleOrNull { it.name == "login" })
        assertEquals(SymbolKind.METHOD, login.kind)
        assertEquals("fun(any...): any", login.type.displayName)
        assertEquals("fun(any...): any", bmobSurface.moduleType.methods.getValue("login").displayName)
        val sign = assertNotNull(bmobSurface.members.singleOrNull { it.name == "sign" })
        assertEquals(SymbolKind.METHOD, sign.kind)
        assertEquals("fun(any...): any", sign.type.displayName)
        assertEquals("fun(any...): any", bmobSurface.moduleType.methods.getValue("sign").displayName)

        val call = assertNotNull(binSurface.members.singleOrNull { it.name == "__call" })
        assertEquals(SymbolKind.FIELD, call.kind)
        assertEquals("fun(any...): any", call.type.displayName)
        assertEquals("fun(any...): any", binSurface.moduleType.fields.getValue("__call").displayName)
    }

    @Test
    fun androlua_duplicate_asset_helpers_do_not_export_shadow_aliases() {
        val androlua = load(LuaVersion.ANDROLUA_5_3)
        val moduleNames = androlua.providerModules.values.map { it.moduleName }

        assertEquals(1, moduleNames.count { it == "bmob" })
        assertEquals(1, moduleNames.count { it == "bin" })
        assertFalse("helpers.bmob" in moduleNames)
        assertFalse("helpers.bin" in moduleNames)
        assertFalse("assets.bmob" in moduleNames)
        assertFalse("assets.bin" in moduleNames)
    }

    @Test
    fun androlua_luajava_overlay_preserves_documented_helper_signatures() {
        val androlua = load(LuaVersion.ANDROLUA_5_3)
        val luajava = androlua.providerModules.values.first { it.moduleName == "luajava" }
        val surface = assertNotNull(luajava.file.moduleExportSurface)

        val bindClass = assertNotNull(surface.members.singleOrNull { it.name == "bindClass" })
        assertEquals(SymbolKind.METHOD, bindClass.kind)
        assertEquals("fun(className: string): JavaClass<any>", bindClass.type.displayName)

        val createProxy = assertNotNull(surface.members.singleOrNull { it.name == "createProxy" })
        assertEquals(SymbolKind.METHOD, createProxy.kind)
        assertContains(createProxy.type.displayName, "fun(interfaceNames: string, callbacks: { [string]: function }): JavaProxy")
        assertContains(createProxy.type.displayName, "fun(interfaceName1: string, interfaceName2: string, callbacks: { [string]: function }): JavaProxy")

        val getContext = assertNotNull(surface.members.singleOrNull { it.name == "getContext" })
        assertEquals(SymbolKind.METHOD, getContext.kind)
        assertEquals("fun(): AndroidLuaContext", getContext.type.displayName)
    }

    @Test
    fun androlua_luajava_overlay_preserves_documented_field_metadata() {
        val androlua = load(LuaVersion.ANDROLUA_5_3)
        val luajava = androlua.providerModules.values.first { it.moduleName == "luajava" }
        val surface = assertNotNull(luajava.file.moduleExportSurface)

        val loaded = assertNotNull(surface.members.singleOrNull { it.name == "loaded" })
        assertEquals(SymbolKind.FIELD, loaded.kind)
        assertEquals("{ [string]: JavaClass<any> }", loaded.type.displayName)
        assertIs<TableType>(surface.moduleType.fields.getValue("loaded"))

        val imported = assertNotNull(surface.members.singleOrNull { it.name == "imported" })
        assertEquals(SymbolKind.FIELD, imported.kind)
        assertEquals("string[]", imported.type.displayName)
        assertIs<ArrayType>(surface.moduleType.fields.getValue("imported"))

        val ids = assertNotNull(surface.members.singleOrNull { it.name == "ids" })
        assertEquals(SymbolKind.FIELD, ids.kind)
        assertEquals("LuaLayoutIds", ids.type.displayName)
        assertEquals("LuaLayoutIds", surface.moduleType.fields.getValue("ids").displayName)

        val luadir = assertNotNull(surface.members.singleOrNull { it.name == "luadir" })
        assertEquals(SymbolKind.FIELD, luadir.kind)
        assertEquals("string", luadir.type.displayName)
        assertEquals("string", surface.moduleType.fields.getValue("luadir").displayName)
    }

    @Test
    fun androlua_import_provider_reuses_documented_luajava_module_surface() {
        val androlua = load(LuaVersion.ANDROLUA_5_3)
        val importProvider = androlua.providerModules.values.first { it.moduleName == "import" }
        val importSurface = assertNotNull(importProvider.file.moduleExportSurface)
        val luajavaType = assertIs<ModuleType>(importSurface.moduleType.fields.getValue("luajava"))
        val globalLuajava = assertIs<ModuleType>(
            assertNotNull(androlua.globals.file.moduleExportSurface)
                .moduleType
                .fields
                .getValue("luajava")
        )

        assertEquals("{ [string]: JavaClass<any> }", luajavaType.fields.getValue("loaded").displayName)
        assertEquals("string[]", luajavaType.fields.getValue("imported").displayName)
        assertEquals("LuaLayoutIds", luajavaType.fields.getValue("ids").displayName)
        assertEquals("string", luajavaType.fields.getValue("luadir").displayName)
        assertEquals("fun(className: string): JavaClass<any>", luajavaType.methods.getValue("bindClass").displayName)
        assertContains(luajavaType.methods.getValue("createProxy").displayName, "interfaceNames: string")
        assertContains(luajavaType.methods.getValue("createProxy").displayName, "JavaProxy")
        assertEquals("import", globalLuajava.moduleName)
        assertEquals("luajava", globalLuajava.displayName)
        assertEquals("fun(className: string): JavaClass<any>", importSurface.moduleType.methods.getValue("bindClass").displayName)
        assertEquals("{ [string]: JavaClass<any> }", importSurface.moduleType.fields.getValue("loaded").displayName)
    }

    @Test
    fun androlua_overlay_keeps_distinct_version_and_virtual_paths() {
        val androlua = load(LuaVersion.ANDROLUA_5_3)
        val lua53 = load(LuaVersion.LUA_5_3)

        assertEquals(LuaVersion.ANDROLUA_5_3, androlua.version)
        assertEquals(LuaVersion.LUA_5_3, lua53.version)
        assertContains(androlua.globals.path.value, "__lua_std__/androlua5.3/_G.lua")
        assertContains(lua53.globals.path.value, "__lua_std__/5.3/_G.lua")
        assertNotEquals(androlua.globals.metadataFingerprint, lua53.globals.metadataFingerprint)
    }

    @Test
    fun androlua_overlay_loads_android_framework_resource_class_providers() {
        val androlua = load(LuaVersion.ANDROLUA_5_3)
        val lua53 = load(LuaVersion.LUA_5_3)

        assertFalse(lua53.providerModules.keys.any { it.value.startsWith("__jvm__/classes/android/") })

        val textView = assertNotNull(androlua.providerModules[VirtualPath.of("__jvm__/classes/android/widget/TextView.lua")])
        assertEquals("TextView", textView.moduleName)
        val textViewSurface = assertNotNull(textView.file.moduleExportSurface)
        val textViewClass = assertIs<JavaInstanceType>(textViewSurface.moduleType.fields.getValue("__class"))
        assertEquals("android.widget.TextView", textViewClass.javaName.canonicalName)
        assertFalse("__call" in textViewSurface.moduleType.fields)
        assertEquals("android.view.View", assertNotNull(textViewClass.classType.superClass).javaName.canonicalName)
        assertTrue("setVisibility" in textViewClass.allInstanceMembers())
        assertEquals("integer", textViewSurface.moduleType.fields.getValue("AUTO_SIZE_TEXT_TYPE_NONE").displayName)
        val nestedBufferType = assertIs<ModuleType>(textViewSurface.moduleType.fields.getValue("BufferType"))
        val nestedBufferTypeClass = assertIs<JavaInstanceType>(nestedBufferType.fields.getValue("__class"))
        assertEquals("android.widget.TextView.BufferType", nestedBufferTypeClass.javaName.canonicalName)
        val setTextType = assertNotNull(textViewSurface.members.singleOrNull { it.exportPath == listOf("__class", "setText") }).type.displayName
        assertContains(setTextType, "text:")
        assertContains(setTextType, "string")
        assertContains(setTextType, "number")
        assertContains(setTextType, "JavaObject")

        val bufferType = assertNotNull(androlua.providerModules[VirtualPath.of("__jvm__/classes/android/widget/TextView\$BufferType.lua")])
        assertEquals("BufferType", bufferType.moduleName)
        val bufferTypeClass = assertIs<JavaInstanceType>(
            assertNotNull(bufferType.file.moduleExportSurface).moduleType.fields.getValue("__class")
        )
        assertEquals("android.widget.TextView.BufferType", bufferTypeClass.javaName.canonicalName)
    }

    @Test
    fun androlua_overlay_preserves_android_framework_method_overloads_and_constructors() {
        val androlua = load(LuaVersion.ANDROLUA_5_3)

        val viewGroup = assertNotNull(androlua.providerModules[VirtualPath.of("__jvm__/classes/android/view/ViewGroup.lua")])
        val viewGroupSurface = assertNotNull(viewGroup.file.moduleExportSurface)
        val addView = assertIs<OverloadedFunctionType>(
            assertNotNull(viewGroupSurface.members.singleOrNull { it.exportPath == listOf("__class", "addView") }).type
        )
        assertEquals(2, addView.callSignatures.size)
        assertTrue(addView.callSignatures.any { signature -> signature.parameters.map { it.name } == listOf("view") })
        assertTrue(addView.callSignatures.any { signature -> signature.parameters.map { it.name } == listOf("view", "params") })

        val layoutParams = assertNotNull(
            androlua.providerModules[VirtualPath.of("__jvm__/classes/android/widget/LinearLayout\$LayoutParams.lua")]
        )
        val layoutParamsSurface = assertNotNull(layoutParams.file.moduleExportSurface)
        val layoutParamsClass = assertIs<JavaClassType>(layoutParamsSurface.moduleType.fields.getValue("__call"))
        assertEquals(2, layoutParamsClass.constructors.overloads.size)
        assertTrue(layoutParamsClass.callSignatures.any { signature ->
            signature.parameters.map { it.name } == listOf("width", "height") &&
                signature.returnType.displayName == "android.widget.LinearLayout.LayoutParams"
        })
        assertTrue(layoutParamsClass.callSignatures.any { signature ->
            signature.parameters.map { it.name } == listOf("width", "height", "weight") &&
                signature.returnType.displayName == "android.widget.LinearLayout.LayoutParams"
        })
        val layoutParamsInstance = assertIs<JavaInstanceType>(layoutParamsSurface.moduleType.fields.getValue("__class"))
        assertEquals(
            "android.view.ViewGroup.MarginLayoutParams",
            assertNotNull(layoutParamsInstance.classType.superClass).javaName.canonicalName
        )
        assertFalse("new" in layoutParamsInstance.allInstanceMembers())
    }

    @Test
    fun androlua_overlay_loads_android_framework_resource_package_providers() {
        val androlua = load(LuaVersion.ANDROLUA_5_3)

        val widgetPackage = assertNotNull(androlua.providerModules[VirtualPath.of("__jvm__/packages/android/widget.lua")])
        assertEquals("android.widget", widgetPackage.moduleName)
        val widgetSurface = assertNotNull(widgetPackage.file.moduleExportSurface)
        val textView = assertIs<ModuleType>(widgetSurface.moduleType.fields.getValue("TextView"))
        val toast = assertIs<ModuleType>(widgetSurface.moduleType.fields.getValue("Toast"))
        assertEquals("TextView", textView.moduleName)
        assertEquals("Toast", toast.moduleName)
        assertIs<JavaInstanceType>(textView.fields.getValue("__class"))

        val animationPackage = assertNotNull(androlua.providerModules[VirtualPath.of("__jvm__/packages/android/view/animation.lua")])
        val animationSurface = assertNotNull(animationPackage.file.moduleExportSurface)
        assertIs<ModuleType>(animationSurface.moduleType.fields.getValue("Animation"))
        assertIs<ModuleType>(animationSurface.moduleType.fields.getValue("TranslateAnimation"))

        val supportingPackage = assertNotNull(androlua.providerModules[VirtualPath.of("__jvm__/packages/android/os.lua")])
        val supportingSurface = assertNotNull(supportingPackage.file.moduleExportSurface)
        assertIs<ModuleType>(supportingSurface.moduleType.fields.getValue("Bundle"))
        assertIs<ModuleType>(supportingSurface.moduleType.fields.getValue("Build"))
    }

    @Test
    fun androlua_overlay_verifies_manifest_entries_into_deterministic_android_models() {
        val result = LuaWorkspaceEngine().build(
            LuaWorkspaceInput(
                files = mapOf(
                    VirtualPath.of("main.lua") to """
                        import "android.widget.*"
                        import "android.view.View.OnClickListener"
                        local title = TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM
                        local listener = OnClickListener
                    """.trimIndent()
                ),
                standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3
            )
        )

        assertEquals(
            VirtualPath.of("__jvm__/packages/android/widget.lua"),
            result.snapshot.graph.activeProviders["android.widget"]?.path
        )
        assertEquals(
            VirtualPath.of("__jvm__/classes/android/widget/TextView.lua"),
            result.snapshot.graph.activeProviders["TextView"]?.path
        )
        val canonicalListenerProvider = assertNotNull(result.snapshot.graph.activeProviders["android.view.View.OnClickListener"])
        val canonicalListenerClass = assertIs<JavaInstanceType>(
            assertNotNull(
                result.snapshot.builtinOverlay.providerModules[canonicalListenerProvider.path]
            ).file.moduleExportSurface?.moduleType?.fields?.get("__class")
        )
        assertEquals("android.view.View.OnClickListener", canonicalListenerClass.javaName.canonicalName)
        val binaryListenerProvider = assertNotNull(result.snapshot.graph.activeProviders["android.view.View\$OnClickListener"])
        val binaryListenerClass = assertIs<JavaInstanceType>(
            assertNotNull(
                result.snapshot.builtinOverlay.providerModules[binaryListenerProvider.path]
            ).file.moduleExportSurface?.moduleType?.fields?.get("__class")
        )
        assertEquals("android.view.View.OnClickListener", binaryListenerClass.javaName.canonicalName)
        val listenerSurface = assertNotNull(
            result.snapshot.builtinOverlay.providerModules[VirtualPath.of("__jvm__/classes/android/view/View\$OnClickListener.lua")]
        ).file.moduleExportSurface
        val listenerClass = assertIs<JavaInstanceType>(assertNotNull(listenerSurface).moduleType.fields.getValue("__class"))
        assertEquals("android.view.View.OnClickListener", listenerClass.javaName.canonicalName)
    }

    private fun load(version: LuaVersion) = BuiltinOverlayLoader.load(version) { _, _ -> WorkspaceSnapshot.FileSnapshot() }

    private fun positionOf(source: String, needle: String): io.github.dingyi222666.luaparser.parser.ast.node.Position {
        val index = source.indexOf(needle)
        check(index >= 0) { "Missing '$needle'." }
        var line = 1
        var column = 1
        for (i in 0 until index) {
            if (source[i] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return io.github.dingyi222666.luaparser.parser.ast.node.Position(line, column)
    }
}
