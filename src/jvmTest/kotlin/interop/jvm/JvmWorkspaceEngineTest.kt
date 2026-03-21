package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmWorkspaceEngineTest {
    @Test
    fun metadata_classes_are_mounted_as_workspace_module_providers() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current",
            metadata = mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Arrays"),
            engine = JvmWorkspaceEngine()
        )

        val lookup = harness.queries.lookupModule("Arrays")
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "Arrays")
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "asList"))
        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "asList"))

        assertEquals(harness.path("__jvm__/classes/java/util/Arrays.lua"), lookup.provider?.path)
        assertEquals(lookup.provider?.path, resolved.provider?.path)
        assertNotNull(hover?.symbol)
        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertTrue(completions.any { it.label == "asList" })
        assertTrue(resolved.exportSurface?.moduleType?.methods.orEmpty().containsKey("asList"))
    }

    @Test
    fun androlua_import_metadata_resolves_simple_class_names_through_default_prefixes() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local String = require(\"String\")\nlocal current = String.__class.length\nreturn current",
            metadata = mapOf(JvmClassModuleProvider.IMPORTS_METADATA_KEY to "String"),
            engine = JvmWorkspaceEngine()
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "String")
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "__class"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "__class"))

        assertEquals(harness.path("__jvm__/classes/java/lang/String.lua"), resolved.provider?.path)
        assertEquals("java.lang.String", hover?.typeInfo?.displayName)
        assertEquals(harness.path("__jvm__/classes/java/lang/String.lua"), definitions.single().path)
    }

    @Test
    fun androlua_import_metadata_resolves_nested_androlua_classes_via_underscore_aliases() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local OnClickListener = require(\"OnClickListener\")\nreturn OnClickListener",
            metadata = mapOf(
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "OnClickListener",
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar",
                JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "android.view.View"
            ),
            engine = JvmWorkspaceEngine()
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "OnClickListener")
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "OnClickListener", 3))

        assertEquals(harness.path("__jvm__/classes/android/view/View\$OnClickListener.lua"), resolved.provider?.path)
        assertEquals(harness.path("__jvm__/classes/android/view/View\$OnClickListener.lua"), definitions.single().path)
    }

    @Test
    fun source_import_exposes_class_name_as_visible_module_symbol() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "import \"java.lang.String\"\nlocal current = String.__class.length\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "String", 2))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "String", 2))
        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "current"))

        assertEquals(SymbolKind.MODULE, hover?.symbol?.kind)
        assertEquals("String", hover?.symbol?.name)
        assertEquals("String", hover?.typeInfo?.displayName)
        assertEquals(harness.path("__jvm__/classes/java/lang/String.lua"), definitions.single().path)
        assertTrue(completions.any { it.label == "String" })
    }

    @Test
    fun source_import_member_usage_resolves_to_reflected_provider_members() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "import \"java.util.Locale\"\nlocal current = Locale.getDefault\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "getDefault"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "getDefault"))
        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "getDefault"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/java/util/Locale.lua"), definitions.single().path)
        assertTrue(references.any { it.path == harness.path("main.lua") })
        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/util/Locale.lua") })
    }

    @Test
    fun source_import_identifier_references_include_provider_and_usage_sites() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "import \"java.lang.String\"\nlocal first = String\nlocal second = String.__class\nreturn second",
            engine = JvmWorkspaceEngine()
        )

        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "String", 2))
        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/lang/String.lua") })
        assertEquals(2, references.count { it.path == harness.path("main.lua") })
    }

    @Test
    fun wildcard_source_import_exposes_matching_class_symbols_used_in_file() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "import \"java.util.*\"\nlocal current = Locale.getDefault\nreturn Locale",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "Locale", 2))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "Locale", 2))
        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "current"))

        assertEquals(SymbolKind.MODULE, hover?.symbol?.kind)
        assertEquals("Locale", hover?.symbol?.name)
        assertEquals(harness.path("__jvm__/classes/java/util/Locale.lua"), definitions.single().path)
        assertTrue(completions.any { it.label == "Locale" })
    }

    @Test
    fun unresolved_short_name_falls_back_to_android_lua_default_import_prefixes() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local current = Locale.getDefault\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "Locale"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "Locale"))
        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "getDefault"))

        assertEquals(SymbolKind.MODULE, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/java/util/Locale.lua"), definitions.single().path)
        assertTrue(completions.any { it.label == "getDefault" })
    }

    @Test
    fun wildcard_import_accumulates_package_prefixes_for_later_unqualified_android_lua_resolution() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "import \"android.widget.*\"\nlocal current = TextView.BufferType\nreturn current",
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar"
            ),
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))
        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "current"))

        assertEquals(SymbolKind.MODULE, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/android/widget/TextView.lua"), definitions.single().path)
        assertTrue(completions.any { it.label == "TextView" })
    }

    @Test
    fun table_wildcard_import_accumulates_package_prefixes_for_later_unqualified_android_lua_resolution() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local import = require(\"import\")\nlocal loaded = import({ \"android.widget.*\" })\nlocal current = TextView.BufferType\nreturn current and loaded",
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar"
            ),
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))
        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "current"))

        assertEquals(SymbolKind.MODULE, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/android/widget/TextView.lua"), definitions.single().path)
        assertTrue(completions.any { it.label == "TextView" })
    }

    @Test
    fun dynamic_wildcard_import_exposes_android_lua_style_package_modules() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local import = require(\"import\")\nlocal widget = import(\"android.widget.*\")\nlocal current = widget.TextView.BufferType\nreturn current",
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar"
            ),
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))
        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "widget"))

        assertEquals(SymbolKind.FIELD, hover?.symbol?.kind)
        assertEquals("TextView", hover?.symbol?.name)
        assertEquals(harness.path("__jvm__/classes/android/widget/TextView.lua"), definitions.single().path)
        assertTrue(completions.any { it.label == "TextView" })
    }

    @Test
    fun dex_prefixed_dynamic_wildcard_import_exposes_android_lua_style_package_modules() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local import = require(\"import\")\nlocal widget = import(\"plugin.dex:android.widget.*\")\nlocal current = widget.TextView.BufferType\nreturn current",
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar"
            ),
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))
        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "widget"))

        assertEquals(SymbolKind.FIELD, hover?.symbol?.kind)
        assertEquals("TextView", hover?.symbol?.name)
        assertEquals(harness.path("__jvm__/classes/android/widget/TextView.lua"), definitions.single().path)
        assertTrue(completions.any { it.label == "TextView" })
    }

    @Test
    fun realiased_dynamic_wildcard_import_exposes_android_lua_style_package_modules() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local import = require(\"import\")\nlocal load = import\nlocal again = load\nlocal widget = again \"android.widget.*\"\nlocal current = widget.TextView.BufferType\nreturn current",
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar"
            ),
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))
        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "widget"))

        assertEquals(SymbolKind.FIELD, hover?.symbol?.kind)
        assertEquals("TextView", hover?.symbol?.name)
        assertEquals(harness.path("__jvm__/classes/android/widget/TextView.lua"), definitions.single().path)
        assertTrue(completions.any { it.label == "TextView" })
    }

    @Test
    fun short_string_dynamic_wildcard_import_exposes_android_lua_style_package_modules() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local import = require \"import\"\nlocal widget = import \"android.widget.*\"\nlocal current = widget.TextView.BufferType\nreturn current",
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar"
            ),
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))
        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "widget"))

        assertEquals(SymbolKind.FIELD, hover?.symbol?.kind)
        assertEquals("TextView", hover?.symbol?.name)
        assertEquals(harness.path("__jvm__/classes/android/widget/TextView.lua"), definitions.single().path)
        assertTrue(completions.any { it.label == "TextView" })
    }

    @Test
    fun dynamic_wildcard_import_member_references_include_class_provider_and_usage_sites() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local import = require(\"import\")\nlocal widget = import(\"android.widget.*\")\nlocal current = widget.TextView\nreturn widget.TextView",
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar"
            ),
            engine = JvmWorkspaceEngine()
        )

        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))

        assertTrue(references.any { it.path == harness.path("__jvm__/classes/android/widget/TextView.lua") })
        assertEquals(2, references.count { it.path == harness.path("main.lua") })
    }

    @Test
    fun realiased_dynamic_wildcard_import_member_references_include_class_provider_and_usage_sites() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local import = require(\"import\")\nlocal load = import\nlocal again = load\nlocal widget = again \"android.widget.*\"\nlocal current = widget.TextView\nreturn widget.TextView",
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar"
            ),
            engine = JvmWorkspaceEngine()
        )

        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))

        assertTrue(references.any { it.path == harness.path("__jvm__/classes/android/widget/TextView.lua") })
        assertEquals(2, references.count { it.path == harness.path("main.lua") })
    }

    @Test
    fun wildcard_source_import_mounts_multiple_package_classes_without_identifier_heuristics() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "import \"java.util.*\"\nreturn Locale and Currency",
            engine = JvmWorkspaceEngine()
        )

        val localeDefinition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "Locale"))
        val currencyDefinition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "Currency"))
        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "Currency"))

        assertEquals(harness.path("__jvm__/classes/java/util/Locale.lua"), localeDefinition.single().path)
        assertEquals(harness.path("__jvm__/classes/java/util/Currency.lua"), currencyDefinition.single().path)
        assertTrue(completions.any { it.label == "Locale" })
        assertTrue(completions.any { it.label == "Currency" })
    }

    @Test
    fun require_import_alias_resolves_dynamic_import_targets() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local import = require(\"import\")\nlocal Locale = import(\"java.util.Locale\")\nlocal current = Locale.getDefault\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "getDefault"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "getDefault"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/java/util/Locale.lua"), definitions.single().path)
    }


    @Test
    fun realiased_require_import_helper_resolves_dynamic_import_targets() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local import = require(\"import\")\nlocal load = import\nlocal again = load\nlocal Locale = again(\"java.util.Locale\")\nlocal current = Locale.getDefault\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "getDefault"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "getDefault"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/java/util/Locale.lua"), definitions.single().path)
    }


    @Test
    fun duplicated_require_import_short_string_call_alias_still_resolves_android_classes() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local import = require \"import\"\nlocal Context = import \"android.content.Context\"\nlocal current = Context.WINDOW_SERVICE\nreturn current",
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar"
            ),
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "WINDOW_SERVICE"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "WINDOW_SERVICE"))

        assertEquals(SymbolKind.FIELD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/android/content/Context.lua"), definitions.single().path)
    }

    @Test
    fun realiased_bind_class_helper_resolves_reflected_android_types() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local bindClass = luajava.bindClass\nlocal bind = bindClass\nlocal again = bind\nlocal Context = again \"android.content.Context\"\nlocal current = Context.WINDOW_SERVICE\nreturn current",
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar"
            ),
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "WINDOW_SERVICE"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "WINDOW_SERVICE"))

        assertEquals(SymbolKind.FIELD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/android/content/Context.lua"), definitions.single().path)
    }

    @Test
    fun realiased_new_instance_helper_resolves_reflected_jvm_instance_members() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local newInstance = luajava.newInstance\nlocal create = newInstance\nlocal builder = create(\"java.lang.StringBuilder\")\nlocal current = builder.append\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "append"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "append"))
        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "append"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/java/lang/StringBuilder.lua"), definitions.single().path)
        assertTrue(references.any { it.path == harness.path("main.lua") })
        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/lang/StringBuilder.lua") })
    }

    @Test
    fun short_string_call_new_instance_helper_resolves_reflected_jvm_instance_members() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local builder = luajava.newInstance \"java.lang.StringBuilder\"\nlocal current = builder.append\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "append"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "append"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/java/lang/StringBuilder.lua"), definitions.single().path)
    }

    @Test
    fun realiased_create_proxy_helper_resolves_reflected_jvm_interface_members() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local createProxy = luajava.createProxy\nlocal create = createProxy\nlocal proxy = create(\"java.lang.Runnable\", {})\nlocal current = proxy.run\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "run"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "run"))
        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "run"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/java/lang/Runnable.lua"), definitions.single().path)
        assertTrue(references.any { it.path == harness.path("main.lua") })
        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/lang/Runnable.lua") })
    }

    @Test
    fun multi_interface_create_proxy_helper_resolves_reflected_jvm_interface_members() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local proxy = luajava.createProxy(\"java.lang.Runnable\", \"java.util.Comparator\", {})\nlocal current = proxy.compare\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "compare"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "compare"))
        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "compare"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/java/util/Comparator.lua"), definitions.single().path)
        assertTrue(references.any { it.path == harness.path("main.lua") })
        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/util/Comparator.lua") })
    }

    @Test
    fun realiased_load_lib_helper_resolves_reflected_jvm_static_members() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local loadLib = luajava.loadLib\nlocal load = loadLib\nlocal currentTimeMillis = load(\"java.lang.System\", \"currentTimeMillis\")\nlocal current = System.currentTimeMillis\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "currentTimeMillis", occurrence = 3))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "currentTimeMillis", occurrence = 3))
        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "currentTimeMillis", occurrence = 3))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/java/lang/System.lua"), definitions.single().path)
        assertTrue(references.any { it.path == harness.path("main.lua") })
        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/lang/System.lua") })
    }

    @Test
    fun short_string_realiased_create_proxy_helper_resolves_reflected_jvm_interface_members() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local createProxy = luajava.createProxy\nlocal create = createProxy\nlocal proxy = create \"java.lang.Runnable\", {}\nlocal current = proxy.run\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "run"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "run"))
        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "run"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/java/lang/Runnable.lua"), definitions.single().path)
        assertTrue(references.any { it.path == harness.path("main.lua") })
        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/lang/Runnable.lua") })
    }

    @Test
    fun short_string_realiased_load_lib_helper_resolves_reflected_jvm_static_members() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local loadLib = luajava.loadLib\nlocal load = loadLib\nlocal currentTimeMillis = load \"java.lang.System\", \"currentTimeMillis\"\nlocal current = System.currentTimeMillis\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "currentTimeMillis", occurrence = 3))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "currentTimeMillis", occurrence = 3))
        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "currentTimeMillis", occurrence = 3))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/java/lang/System.lua"), definitions.single().path)
        assertTrue(references.any { it.path == harness.path("main.lua") })
        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/lang/System.lua") })
    }

    @Test
    fun constructor_style_calls_on_imported_jvm_modules_resolve_reflected_instance_members() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "import \"java.lang.StringBuilder\"\nlocal builder = StringBuilder()\nlocal current = builder.append\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "append"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "append"))
        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "append"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/java/lang/StringBuilder.lua"), definitions.single().path)
        assertTrue(references.any { it.path == harness.path("main.lua") })
        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/lang/StringBuilder.lua") })
    }

    @Test
    fun dex_prefixed_import_helper_resolves_android_types() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local import = require(\"import\")\nlocal Context = import(\"plugin.dex:android.content.Context\")\nlocal current = Context.WINDOW_SERVICE\nreturn current",
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar"
            ),
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "WINDOW_SERVICE"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "WINDOW_SERVICE"))

        assertEquals(SymbolKind.FIELD, hover?.symbol?.kind)
        assertEquals(harness.path("__jvm__/classes/android/content/Context.lua"), definitions.single().path)
    }

    @Test
    fun import_resolution_prefers_existing_platform_classes_and_ignores_unknown_entries() {
        val provider = JvmClassModuleProvider()

        val requested = provider.requestedClasses(
            mapOf(
                JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Locale,missing.DoesNotExist",
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "String\nDefinitelyMissing"
            )
        )
        val providers = provider.providersFor(
            mapOf(
                JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Locale,missing.DoesNotExist",
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "String\nDefinitelyMissing"
            )
        )

        assertTrue("java.util.Locale" in requested)
        assertTrue("java.lang.String" in requested)
        assertTrue(providers.keys.any { it.value == "__jvm__/classes/java/util/Locale.lua" })
        assertTrue(providers.keys.any { it.value == "__jvm__/classes/java/lang/String.lua" })
        assertTrue(providers.keys.none { it.value.contains("DoesNotExist") })
    }


    @Test
    fun workspace_configuration_serializes_classpath_android_jar_and_imports_to_metadata() {
        val metadata = JvmWorkspaceConfiguration(
            classes = linkedSetOf("java.util.Locale"),
            androluaImports = listOf("String"),
            classpathEntries = listOf("libs/example.jar", "libs/second.jar"),
            androidJar = "platforms/android-34/android.jar",
            importPrefixes = listOf("java.lang", "android.widget")
        ).applyToMetadata(emptyMap())

        assertEquals("java.util.Locale", metadata[JvmClassModuleProvider.CLASSES_METADATA_KEY])
        assertEquals("String", metadata[JvmClassModuleProvider.IMPORTS_METADATA_KEY])
        assertEquals("libs/example.jar\nlibs/second.jar", metadata[JvmWorkspaceConfiguration.CLASSPATH_METADATA_KEY])
        assertEquals("platforms/android-34/android.jar", metadata[JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY])
        assertEquals("java.lang\nandroid.widget", metadata[JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY])
    }

    @Test
    fun workspace_configuration_reads_metadata_and_includes_android_jar_in_effective_classpath() {
        val configuration = JvmWorkspaceConfiguration.fromMetadata(
            mapOf(
                JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Locale",
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "String",
                JvmWorkspaceConfiguration.CLASSPATH_METADATA_KEY to "libs/example.jar\nlibs/second.jar",
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "platforms/android-34/android.jar",
                JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "java.lang\nandroid.widget"
            )
        )

        assertEquals(linkedSetOf("java.util.Locale"), configuration.classes)
        assertEquals(listOf("String"), configuration.androluaImports)
        assertEquals(listOf("libs/example.jar", "libs/second.jar"), configuration.classpathEntries)
        assertEquals("platforms/android-34/android.jar", configuration.androidJar)
        assertEquals(listOf("java.lang", "android.widget"), configuration.importPrefixes)
        assertEquals(
            listOf("libs/example.jar", "libs/second.jar", "platforms/android-34/android.jar"),
            configuration.effectiveClasspathEntries()
        )
    }

    @Test
    fun workspace_engine_configuration_mounts_classes_without_metadata_entries() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local Locale = require(\"Locale\")\nreturn Locale",
            engine = JvmWorkspaceEngine(
                configuration = JvmWorkspaceConfiguration(
                    classes = linkedSetOf("java.util.Locale")
                )
            )
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "Locale")

        assertEquals(harness.path("__jvm__/classes/java/util/Locale.lua"), resolved.provider?.path)
    }

    @Test
    fun provider_honors_custom_import_prefixes_from_configuration() {
        val provider = JvmClassModuleProvider()
        val requested = provider.requestedClasses(
            JvmWorkspaceConfiguration(
                androluaImports = listOf("BigDecimal"),
                importPrefixes = listOf("java.math")
            )
        )

        assertEquals(linkedSetOf("java.math.BigDecimal"), requested)
    }
}
