package semantic.workspace

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidLuaImportWorkspaceTddTest {
    private val androidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

    @Test
    fun require_import_resolves_androlua_import_overlay_module() {
        val harness = jvmHarness(
            "main.lua" to "local import = require(\"import\")\nreturn import"
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "import")
        val definition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "import", occurrence = 2))
        val providerPath = harness.path("__lua_std__/androlua5.3/import.lua")
        val dependency = harness.snapshot.graph.resolvedDependencies.getValue(harness.path("main.lua")).single()

        assertEquals(providerPath, resolved.provider?.path)
        assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, resolved.provider?.source)
        assertEquals(providerPath, dependency.provider.path)
        assertEquals(listOf(providerPath), definition.map { it.path })
    }

    @Test
    fun require_import_position_query_resolves_import_callsite() {
        val harness = jvmHarness(
            "main.lua" to "local import = require(\"import\")\nreturn import"
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), harness.positionOf("main.lua", "import", occurrence = 1))

        assertEquals("import", resolved?.moduleName)
        assertEquals(harness.path("__lua_std__/androlua5.3/import.lua"), resolved?.provider?.path)
    }

    @Test
    fun require_import_prefers_workspace_provider_before_android_lua_global_fallback() {
        val harness = jvmHarness(
            "import.lua" to "return { custom = true }",
            "main.lua" to "local import = require(\"import\")\nreturn import.custom"
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "import")
        val dependency = harness.snapshot.graph.resolvedDependencies.getValue(harness.path("main.lua")).single()
        val providers = harness.snapshot.graph.providersByModuleName.getValue("import")

        assertEquals(harness.path("import.lua"), resolved.provider?.path)
        assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, resolved.provider?.source)
        assertEquals(harness.path("import.lua"), dependency.provider.path)
        assertTrue(providers.any { it.path == harness.path("__lua_std__/androlua5.3/import.lua") })
    }

    @Test
    fun require_import_symbol_is_builtin_function() {
        val harness = jvmHarness(
            "main.lua" to "local import = require(\"import\")\nreturn import"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "import", occurrence = 2))

        assertEquals(SymbolKind.FUNCTION, hover?.symbol?.kind)
        assertEquals("fun(...: any...): any", hover?.typeInfo?.displayName)
    }

    @Test
    fun direct_import_mounts_java_io_file_provider() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.File\"\nlocal current = File.separator\nreturn current"
        )

        assertProviderPath(harness, "java.io.File")
        assertEquals(harness.path("__jvm__/classes/java/io/File.lua"), harness.queries.lookupModule("File").provider?.path)
    }

    @Test
    fun dex_path_prefixed_import_reports_unsupported_prefix_without_losing_target() {
        val provider = JvmClassModuleProvider()
        val configuration = JvmWorkspaceConfiguration(
            androluaImports = listOf("dexPath:java.io.File")
        )

        val diagnostics = provider.importDiagnostics(configuration)
        val requested = provider.requestedClasses(configuration)

        assertEquals(linkedSetOf("java.io.File"), requested)
        assertEquals(1, diagnostics.size)
        assertEquals(JvmClassModuleProvider.UNSUPPORTED_PREFIXED_IMPORT_CODE, diagnostics.single().code)
        assertEquals("dexPath", diagnostics.single().pathPrefix)
        assertEquals("java.io.File", diagnostics.single().className)
        assertTrue(diagnostics.single().message.contains("dex", ignoreCase = true))
        assertTrue(diagnostics.single().message.contains("java.io.File"))
    }

    @Test
    fun ordinary_class_import_does_not_emit_prefixed_import_diagnostic() {
        val provider = JvmClassModuleProvider()
        val configuration = JvmWorkspaceConfiguration(
            androluaImports = listOf("java.io.File")
        )

        assertEquals(emptyList(), provider.importDiagnostics(configuration))
        assertEquals(linkedSetOf("java.io.File"), provider.requestedClasses(configuration))
        assertTrue(provider.providersFor(configuration).keys.any { it.value == "__jvm__/classes/java/io/File.lua" })
    }

    @Test
    fun direct_import_exposes_file_as_workspace_module_symbol() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.File\"\nlocal current = File.separator\nreturn current"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "File", occurrence = 2))

        assertEquals(SymbolKind.MODULE, hover?.symbol?.kind)
        assertEquals("File", hover?.typeInfo?.moduleName)
    }

    @Test
    fun direct_import_exposes_static_file_field() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.File\"\nlocal current = File.separator\nreturn current"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "separator"))

        assertEquals(SymbolKind.FIELD, hover?.symbol?.kind)
        assertEquals("string", hover?.typeInfo?.displayName)
    }

    @Test
    fun direct_import_static_field_definition_points_to_file_provider() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.File\"\nlocal current = File.separator\nreturn current"
        )

        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "separator"))

        assertEquals(listOf(harness.path("__jvm__/classes/java/io/File.lua")), definitions.map { it.path })
    }

    @Test
    fun direct_file_constructor_call_returns_file_class_instance() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.File\"\nlocal f = File(\"build.gradle.kts\")\nlocal current = f.exists\nreturn current"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "f", occurrence = 2))

        assertEquals("java.io.File", hover?.typeInfo?.displayName)
    }

    @Test
    fun direct_file_constructor_instance_method_is_queryable() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.File\"\nlocal f = File(\"build.gradle.kts\")\nlocal current = f.exists\nreturn current"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "exists"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "exists"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(listOf(harness.path("__jvm__/classes/java/io/File.lua")), definitions.map { it.path })
    }

    @Test
    fun direct_file_constructor_member_completions_include_instance_members() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.File\"\nlocal f = File(\"build.gradle.kts\")\nlocal current = f.exists\nreturn current"
        )

        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "exists"))

        assertCompletion(completions, "exists", CompletionItemKind.METHOD)
        assertCompletion(completions, "getName", CompletionItemKind.METHOD)
    }

    @Test
    fun direct_import_references_include_provider_and_usages() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.File\"\nlocal a = File.separator\nlocal b = File.pathSeparator\nreturn a .. b"
        )

        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "File", occurrence = 2))

        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/io/File.lua") })
        assertEquals(2, references.count { it.path == harness.path("main.lua") })
    }

    @Test
    fun direct_import_declaration_points_to_provider_module() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.File\"\nlocal current = File.separator\nreturn current"
        )

        val declaration = harness.queries.declaration(harness.path("main.lua"), harness.positionOf("main.lua", "File", occurrence = 2))

        assertEquals(listOf(harness.path("__jvm__/classes/java/io/File.lua")), declaration.map { it.path })
    }

    @Test
    fun java_io_wildcard_import_mounts_package_provider() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.*\"\nlocal current = File.separator\nreturn current"
        )

        val packageProvider = harness.snapshot.extraProviders[harness.path("__jvm__/packages/java/io.lua")]
        val module = assertNotNull(packageProvider?.moduleExportSurface?.moduleType)

        assertEquals("java.io", module.moduleName)
        assertTrue("File" in module.fields)
    }

    @Test
    fun java_io_wildcard_import_resolves_file_identifier() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.*\"\nlocal current = File.separator\nreturn current"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "File"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "File"))

        assertEquals(SymbolKind.MODULE, hover?.symbol?.kind)
        assertEquals(listOf(harness.path("__jvm__/classes/java/io/File.lua")), definitions.map { it.path })
    }

    @Test
    fun java_io_wildcard_import_exposes_static_file_member() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.*\"\nlocal current = File.separator\nreturn current"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "separator"))

        assertEquals(SymbolKind.FIELD, hover?.symbol?.kind)
        assertEquals("string", hover?.typeInfo?.displayName)
    }

    @Test
    fun wildcard_import_completion_includes_file_class() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.*\"\nlocal current = File.separator\nreturn current"
        )

        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "current"))

        assertCompletion(completions, "File", CompletionItemKind.MODULE)
    }

    @Test
    fun wildcard_package_module_completion_includes_multiple_java_io_classes() {
        val harness = jvmHarness(
            "main.lua" to "local import = require(\"import\")\nlocal ioPackage = import(\"java.io.*\")\nlocal current = ioPackage.File.separator\nreturn current"
        )

        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "File"))

        assertCompletion(completions, "File", CompletionItemKind.FIELD)
        assertCompletion(completions, "InputStream", CompletionItemKind.FIELD)
    }

    @Test
    fun wildcard_import_resolves_multiple_classes_from_same_package() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.*\"\nlocal a = File.separator\nlocal b = InputStream.nullInputStream\nreturn a"
        )

        val fileDefinition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "File"))
        val inputStreamDefinition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "InputStream"))

        assertEquals(listOf(harness.path("__jvm__/classes/java/io/File.lua")), fileDefinition.map { it.path })
        assertEquals(listOf(harness.path("__jvm__/classes/java/io/InputStream.lua")), inputStreamDefinition.map { it.path })
    }

    @Test
    fun two_wildcard_imports_resolve_classes_across_packages() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.*\"\nimport \"java.util.*\"\nlocal f = File.separator\nlocal l = Locale.ROOT\nreturn f"
        )

        val fileDefinition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "File"))
        val localeDefinition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "Locale"))

        assertEquals(listOf(harness.path("__jvm__/classes/java/io/File.lua")), fileDefinition.map { it.path })
        assertEquals(listOf(harness.path("__jvm__/classes/java/util/Locale.lua")), localeDefinition.map { it.path })
    }

    @Test
    fun two_wildcard_imports_expose_completions_across_packages() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.*\"\nimport \"java.util.*\"\nlocal f = File.separator\nlocal l = Locale.ROOT\nreturn f"
        )

        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "f"))

        assertCompletion(completions, "File", CompletionItemKind.MODULE)
        assertCompletion(completions, "Locale", CompletionItemKind.MODULE)
    }

    @Test
    fun import_require_alias_dynamic_call_resolves_java_util_locale() {
        val harness = jvmHarness(
            "main.lua" to "local import = require(\"import\")\nlocal Locale = import(\"java.util.Locale\")\nlocal current = Locale.ROOT\nreturn current"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "Locale", occurrence = 2))
        val definition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "ROOT"))

        assertEquals("Locale", hover?.typeInfo?.moduleName)
        assertEquals(listOf(harness.path("__jvm__/classes/java/util/Locale.lua")), definition.map { it.path })
    }

    @Test
    fun import_require_alias_dynamic_wildcard_returns_package_module() {
        val harness = jvmHarness(
            "main.lua" to "local import = require(\"import\")\nlocal util = import(\"java.util.*\")\nlocal current = util.Locale.ROOT\nreturn current"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "util", occurrence = 2))
        val definition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "Locale"))

        assertEquals("java.util", hover?.typeInfo?.moduleName)
        assertEquals(listOf(harness.path("__jvm__/classes/java/util/Locale.lua")), definition.map { it.path })
    }

    @Test
    fun chained_import_alias_dynamic_wildcard_returns_package_module() {
        val harness = jvmHarness(
            "main.lua" to "local import = require(\"import\")\nlocal load = import\nlocal again = load\nlocal ioPackage = again(\"java.io.*\")\nlocal current = ioPackage.File.separator\nreturn current"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "ioPackage", occurrence = 2))
        val definition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "File"))

        assertEquals("java.io", hover?.typeInfo?.moduleName)
        assertEquals(listOf(harness.path("__jvm__/classes/java/io/File.lua")), definition.map { it.path })
    }

    @Test
    fun import_table_argument_mounts_each_class_provider() {
        val harness = jvmHarness(
            "main.lua" to "local import = require(\"import\")\nlocal classes = import({ \"java.io.File\", \"java.util.Locale\" })\nlocal current = File.separator .. Locale.ROOT\nreturn classes"
        )

        assertProviderPath(harness, "java.io.File")
        assertProviderPath(harness, "java.util.Locale")
        // occurrence=2: first "Locale" is inside the string "java.util.Locale"
        assertEquals(
            harness.path("__jvm__/classes/java/util/Locale.lua"),
            harness.queries.gotoDefinition(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "Locale", occurrence = 2)
            ).single().path
        )
    }

    @Test
    fun import_array_return_type_is_array_when_multiple_targets_are_loaded() {
        val harness = jvmHarness(
            "main.lua" to "local import = require(\"import\")\nlocal classes = import({ \"java.io.File\", \"java.util.Locale\" })\nreturn classes"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "classes", occurrence = 2))

        assertTrue(hover?.typeInfo?.displayName.orEmpty().startsWith("Array<"))
    }

    @Test
    fun source_import_with_simple_name_uses_default_prefixes() {
        val harness = jvmHarness(
            "main.lua" to "import \"File\"\nlocal current = File.separator\nreturn current"
        )

        assertProviderPath(harness, "java.io.File")
        assertEquals(harness.path("__jvm__/classes/java/io/File.lua"), harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "File")).single().path)
    }

    @Test
    fun source_import_with_custom_metadata_prefix_resolves_simple_name() {
        val harness = jvmHarness(
            "main.lua" to "import \"BigDecimal\"\nlocal current = BigDecimal.ZERO\nreturn current",
            metadata = mapOf(JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "java.math")
        )

        assertProviderPath(harness, "java.math.BigDecimal")
        assertEquals(harness.path("__jvm__/classes/java/math/BigDecimal.lua"), harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "BigDecimal")).single().path)
    }

    @Test
    fun explicit_metadata_imports_expose_unimported_simple_class() {
        val harness = jvmHarness(
            "main.lua" to "local current = Locale.ROOT\nreturn current",
            metadata = mapOf(JvmClassModuleProvider.IMPORTS_METADATA_KEY to "Locale")
        )

        assertProviderPath(harness, "java.util.Locale")
        assertEquals(harness.path("__jvm__/classes/java/util/Locale.lua"), harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "Locale")).single().path)
    }

    @Test
    fun static_method_completion_is_available_for_imported_class_module() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.lang.System\"\nlocal current = System.currentTimeMillis\nreturn current"
        )

        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "currentTimeMillis"))

        assertCompletion(completions, "currentTimeMillis", CompletionItemKind.METHOD)
        assertCompletion(completions, "lineSeparator", CompletionItemKind.METHOD)
    }

    @Test
    fun static_method_hover_reports_method_symbol() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.lang.System\"\nlocal current = System.currentTimeMillis\nreturn current"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "currentTimeMillis"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertTrue(hover?.typeInfo?.displayName.orEmpty().contains("fun("))
    }

    @Test
    fun inner_class_dotted_name_import_resolves_binary_provider() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.util.Map.Entry\"\nlocal current = Entry.comparingByKey\nreturn current"
        )

        assertProviderPath(harness, "java.util.Map\$Entry")
        assertEquals(harness.path("__jvm__/classes/java/util/Map\$Entry.lua"), harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "Entry")).single().path)
    }

    @Test
    fun inner_class_binary_name_import_resolves_provider() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.util.Map\$Entry\"\nlocal current = Entry.comparingByValue\nreturn current"
        )

        assertProviderPath(harness, "java.util.Map\$Entry")
        assertEquals(harness.path("__jvm__/classes/java/util/Map\$Entry.lua"), harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "Entry")).single().path)
    }

    @Test
    fun inner_class_underscore_alias_import_resolves_provider() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.util.Map_Entry\"\nlocal current = Entry.comparingByKey\nreturn current"
        )

        assertProviderPath(harness, "java.util.Map\$Entry")
        assertEquals(harness.path("__jvm__/classes/java/util/Map\$Entry.lua"), harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "Entry")).single().path)
    }

    @Test
    fun inner_class_static_method_hover_uses_reflected_member() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.util.Map.Entry\"\nlocal current = Entry.comparingByKey\nreturn current"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "comparingByKey"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        val display = hover?.typeInfo?.displayName.orEmpty()
        // Product may render generic callables as fun<...> or fun(...).
        assertTrue(
            display.contains("fun(") || display.contains("fun<"),
            "Expected callable display for comparingByKey, got '$display'"
        )
    }


    @Test
    fun import_completion_surface_contains_direct_imported_classes() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.File\"\nimport \"java.util.Locale\"\nlocal current = File.separator\nreturn current"
        )

        val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "current"))

        assertCompletion(completions, "File", CompletionItemKind.MODULE)
        assertCompletion(completions, "Locale", CompletionItemKind.MODULE)
    }

    @Test
    fun import_definition_surface_handles_direct_class_and_static_member() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.util.Locale\"\nlocal current = Locale.ROOT\nreturn current"
        )

        val classDefinition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "Locale"))
        val memberDefinition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "ROOT"))

        assertEquals(listOf(harness.path("__jvm__/classes/java/util/Locale.lua")), classDefinition.map { it.path })
        assertEquals(listOf(harness.path("__jvm__/classes/java/util/Locale.lua")), memberDefinition.map { it.path })
    }

    @Test
    fun import_declaration_surface_matches_definition_for_imported_class() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.util.Locale\"\nlocal current = Locale.ROOT\nreturn current"
        )

        val definition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "Locale"))
        val declaration = harness.queries.declaration(harness.path("main.lua"), harness.positionOf("main.lua", "Locale"))

        assertEquals(definition.map { it.path }, declaration.map { it.path })
    }

    @Test
    fun import_document_highlights_include_provider_and_workspace_usages() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.util.Locale\"\nlocal first = Locale.ROOT\nlocal second = Locale.getDefault\nreturn first"
        )

        val highlights = harness.queries.documentHighlights(harness.path("main.lua"), harness.positionOf("main.lua", "Locale", occurrence = 2))

        assertTrue(highlights.any { it.path == harness.path("__jvm__/classes/java/util/Locale.lua") })
        assertEquals(2, highlights.count { it.path == harness.path("main.lua") })
    }

    @Test
    fun import_shadowing_keeps_local_symbols_preferred_after_declaration() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.File\"\nlocal before = File.separator\nlocal File = { separator = 1 }\nlocal after = File.separator\nreturn before + after"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "File", occurrence = 3))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "File", occurrence = 3))

        assertEquals("table", hover?.typeInfo?.displayName)
        assertEquals(listOf(harness.path("main.lua")), definitions.map { it.path })
    }

    @Test
    fun import_shadowing_does_not_remove_before_shadow_import_reference() {
        val harness = jvmHarness(
            "main.lua" to "import \"java.io.File\"\nlocal before = File.separator\nlocal File = { separator = 1 }\nlocal after = File.separator\nreturn before + after"
        )

        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "File", occurrence = 2))

        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/io/File.lua") })
        assertEquals(1, references.count { it.path == harness.path("main.lua") })
    }

    @Test
    fun bind_class_result_from_imported_target_has_provider_definition_for_instance_members() {
        val harness = jvmHarness(
            "main.lua" to "local StringBuilder = luajava.bindClass(\"java.lang.StringBuilder\")\nlocal builder = StringBuilder()\nlocal current = builder.append\nreturn current"
        )

        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "append"))

        assertEquals(listOf(harness.path("__jvm__/classes/java/lang/StringBuilder.lua")), definitions.map { it.path })
    }

    @Test
    fun bind_class_result_hover_reports_jvm_module_type() {
        val harness = jvmHarness(
            "main.lua" to "local StringBuilder = luajava.bindClass(\"java.lang.StringBuilder\")\nreturn StringBuilder"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "StringBuilder", occurrence = 2))

        assertEquals("StringBuilder", hover?.typeInfo?.moduleName)
    }

    @Test
    fun new_instance_result_exposes_instance_method_definition() {
        val harness = jvmHarness(
            "main.lua" to "local builder = luajava.newInstance(\"java.lang.StringBuilder\")\nlocal current = builder.append\nreturn current"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "append"))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "append"))

        assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
        assertEquals(listOf(harness.path("__jvm__/classes/java/lang/StringBuilder.lua")), definitions.map { it.path })
    }

    @Test
    fun load_lib_result_exposes_static_method_signature() {
        val harness = jvmHarness(
            "main.lua" to "local currentTimeMillis = luajava.loadLib(\"java.lang.System\", \"currentTimeMillis\")\nreturn currentTimeMillis"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "currentTimeMillis", occurrence = 2))

        assertTrue(hover?.typeInfo?.displayName.orEmpty().contains("fun("))
    }

    @Test
    fun android_jar_direct_text_view_import_resolves_when_present() {
        withAndroidHarness(
            "main.lua" to "import \"android.widget.TextView\"\nlocal current = TextView.AUTO_SIZE_TEXT_TYPE_NONE\nreturn current"
        ) { harness ->
            assertProviderPath(harness, "android.widget.TextView")
            assertEquals(harness.path("__jvm__/classes/android/widget/TextView.lua"), harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "TextView")).single().path)
        }
    }

    @Test
    fun android_jar_text_view_static_field_hover_when_present() {
        withAndroidHarness(
            "main.lua" to "import \"android.widget.TextView\"\nlocal current = TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM\nreturn current"
        ) { harness ->
            val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "AUTO_SIZE_TEXT_TYPE_UNIFORM"))

            assertEquals(SymbolKind.FIELD, hover?.symbol?.kind)
            assertEquals("number", hover?.typeInfo?.displayName)
        }
    }

    @Test
    fun android_jar_widget_wildcard_import_resolves_text_view_when_present() {
        withAndroidHarness(
            "main.lua" to "import \"android.widget.*\"\nlocal current = TextView.AUTO_SIZE_TEXT_TYPE_NONE\nreturn current"
        ) { harness ->
            assertEquals(harness.path("__jvm__/classes/android/widget/TextView.lua"), harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "TextView")).single().path)
            assertCompletion(harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "current")), "TextView", CompletionItemKind.MODULE)
        }
    }

    @Test
    fun android_jar_view_wildcard_import_resolves_view_when_present() {
        withAndroidHarness(
            "main.lua" to "import \"android.view.*\"\nlocal current = View.VISIBLE\nreturn current"
        ) { harness ->
            assertEquals(harness.path("__jvm__/classes/android/view/View.lua"), harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "View")).single().path)
            assertEquals(SymbolKind.FIELD, harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "VISIBLE"))?.symbol?.kind)
        }
    }

    @Test
    fun android_jar_wildcard_import_package_completion_when_present() {
        withAndroidHarness(
            "main.lua" to "local import = require(\"import\")\nlocal widget = import(\"android.widget.*\")\nlocal current = widget.TextView.AUTO_SIZE_TEXT_TYPE_NONE\nreturn current"
        ) { harness ->
            val completions = harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "TextView"))

            assertCompletion(completions, "TextView", CompletionItemKind.FIELD)
            assertCompletion(completions, "Button", CompletionItemKind.FIELD)
        }
    }

    @Test
    fun android_jar_inner_class_dotted_import_resolves_when_present() {
        withAndroidHarness(
            "main.lua" to "import \"android.widget.TextView.BufferType\"\nlocal current = BufferType.NORMAL\nreturn current"
        ) { harness ->
            assertProviderPath(harness, "android.widget.TextView\$BufferType")
            assertEquals(harness.path("__jvm__/classes/android/widget/TextView\$BufferType.lua"), harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "BufferType")).single().path)
        }
    }

    @Test
    fun android_jar_inner_class_underscore_import_resolves_when_present() {
        withAndroidHarness(
            "main.lua" to "import \"android.widget.TextView_BufferType\"\nlocal current = BufferType.SPANNABLE\nreturn current"
        ) { harness ->
            assertProviderPath(harness, "android.widget.TextView\$BufferType")
            assertEquals(harness.path("__jvm__/classes/android/widget/TextView\$BufferType.lua"), harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "BufferType")).single().path)
        }
    }

    @Test
    fun android_jar_view_inner_listener_simple_name_with_prefix_when_present() {
        withAndroidHarness(
            "main.lua" to "import \"OnClickListener\"\nlocal listener = OnClickListener\nreturn listener",
            metadata = mapOf(JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "android.view.View")
        ) { harness ->
            assertProviderPath(harness, "android.view.View\$OnClickListener")
            assertEquals(harness.path("__jvm__/classes/android/view/View\$OnClickListener.lua"), harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "OnClickListener")).single().path)
        }
    }

    @Test
    fun android_jar_create_proxy_listener_exposes_interface_members_when_present() {
        withAndroidHarness(
            "main.lua" to "local proxy = luajava.createProxy(\"android.view.View.OnClickListener\", {})\nlocal current = proxy.onClick\nreturn current"
        ) { harness ->
            val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "onClick"))
            val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "onClick"))

            assertEquals(SymbolKind.METHOD, hover?.symbol?.kind)
            assertEquals(listOf(harness.path("__jvm__/classes/android/view/View\$OnClickListener.lua")), definitions.map { it.path })
        }
    }

    @Test
    fun android_jar_discovery_without_metadata_mounts_text_view_when_present() {
        // Goal path (TASK-537): jvm.androidJar metadata unset; host SDK discovery must still mount.
        if (!androidJar.isFile) {
            println(
                "SKIP reason: " +
                    JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(taskId = "TASK-537")
            )
            return
        }
        // deliberately no metadata androidJar — host SDK discovery only
        val harness = jvmHarness(
            "main.lua" to "import \"android.widget.TextView\"\nlocal current = TextView.AUTO_SIZE_TEXT_TYPE_NONE\nreturn current"
        )
        assertProviderPath(harness, "android.widget.TextView")
        assertEquals(
            harness.path("__jvm__/classes/android/widget/TextView.lua"),
            harness.queries.gotoDefinition(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "TextView")
            ).single().path
        )
    }

    @Test
    fun android_jar_discovery_without_metadata_mounts_widget_wildcard_when_present() {
        if (!androidJar.isFile) {
            println(
                "SKIP reason: " +
                    JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(taskId = "TASK-537")
            )
            return
        }
        val harness = jvmHarness(
            "main.lua" to "import \"android.widget.*\"\nlocal current = TextView.AUTO_SIZE_TEXT_TYPE_NONE\nreturn current"
        )
        assertEquals(
            harness.path("__jvm__/classes/android/widget/TextView.lua"),
            harness.queries.gotoDefinition(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "TextView")
            ).single().path
        )
        assertCompletion(
            harness.queries.completions(harness.path("main.lua"), harness.positionOf("main.lua", "current")),
            "TextView",
            CompletionItemKind.MODULE
        )
    }

    @Test
    fun android_jar_soft_skip_reason_is_explicit_when_absent() {
        val reason = JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(
            environment = emptyMap(),
            userHome = File("/nonexistent/lua-parser-user-home"),
            localAppData = "/nonexistent/lua-parser-localappdata",
            taskId = "TASK-537"
        )
        assertTrue(reason.contains("TASK-537"))
        assertTrue(reason.contains("android.jar"))
        assertTrue(
            reason.contains(JvmWorkspaceConfiguration.ANDROID_HOME_ENV) ||
                reason.contains(JvmWorkspaceConfiguration.ANDROID_SDK_ROOT_ENV)
        )
    }

    private fun jvmHarness(
        vararg files: Pair<String, String>,
        metadata: Map<String, String> = emptyMap()
    ): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = metadata,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun androidHarness(
        vararg files: Pair<String, String>,
        metadata: Map<String, String> = emptyMap()
    ): WorkspaceSemanticHarness {
        val androidMetadata = metadata + (JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path)
        return jvmHarness(*files, metadata = androidMetadata)
    }

    private fun withAndroidHarness(
        vararg files: Pair<String, String>,
        metadata: Map<String, String> = emptyMap(),
        block: (WorkspaceSemanticHarness) -> Unit
    ) {
        if (!androidJar.isFile) {
            return
        }
        block(androidHarness(*files, metadata = metadata))
    }

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
    }

    private fun assertCompletion(
        completions: List<io.github.dingyi222666.luaparser.semantic.api.CompletionItem>,
        label: String,
        kind: CompletionItemKind
    ) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}."
        )
    }
}
