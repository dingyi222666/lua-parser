package semantic.workspace

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LuaWorkspaceQueryFacadeTest {
    @Test
    fun signature_help_reports_callable_signatures_and_active_parameter_through_workspace_queries() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "---@param value number\n---@param label string\nlocal function render(value, label)\n  return label\nend\nlocal current = render(1, \"hi\")\nreturn current"
        )

        val signatureHelp = harness.queries.signatureHelp(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "\"hi\"")
        )

        assertNotNull(signatureHelp)
        assertEquals("fun(value: number, label: string): string", signatureHelp.signatures.single().label)
        assertEquals(listOf("value: number", "label: string"), signatureHelp.signatures.single().parameters.map { it.label })
        assertEquals(0, signatureHelp.activeSignature)
        assertEquals(1, signatureHelp.activeParameter)
    }

    @Test
    fun signature_help_advances_to_next_parameter_when_cursor_is_between_arguments() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "---@param value number\n---@param label string\nlocal function render(value, label)\n  return label\nend\nlocal current = render(1,  \"hi\")\nreturn current"
        )

        val signatureHelp = harness.queries.signatureHelp(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "\"hi\"")
                .copy(column = harness.positionOf("main.lua", "\"hi\"").column - 1)
        )

        assertNotNull(signatureHelp)
        assertEquals(1, signatureHelp.activeParameter)
    }

    @Test
    fun signature_help_keeps_first_parameter_active_before_first_argument() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "---@param value number\n---@param label string\nlocal function render(value, label)\n  return label\nend\nlocal current = render(1, \"hi\")\nreturn current"
        )

        val firstArgument = harness.positionOf("main.lua", "1")
        val signatureHelp = harness.queries.signatureHelp(
            harness.path("main.lua"),
            firstArgument.copy(column = firstArgument.column - 1)
        )

        assertNotNull(signatureHelp)
        assertEquals(0, signatureHelp.activeParameter)
    }

    @Test
    fun signature_help_preserves_generic_type_parameter_labels_after_type_resolution() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "---@generic T\n---@param value T\n---@return T\nlocal function identity(value)\n  return value\nend\nlocal current = identity(1)\nreturn current"
        )

        val signatureHelp = harness.queries.signatureHelp(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "1")
        )

        assertNotNull(signatureHelp)
        assertEquals("fun<T>(value: T): T", signatureHelp.signatures.single().label)
        assertEquals(listOf("value: T"), signatureHelp.signatures.single().parameters.map { it.label })
        assertEquals(0, signatureHelp.activeParameter)
    }

    @Test
    fun signature_help_supports_short_string_calls_with_trailing_arguments() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "---@param className string\n---@param memberName string\n---@return fun(): string\nlocal function loadLib(className, memberName)\n  return function()\n    return memberName\n  end\nend\nlocal current = loadLib \"java.util.Locale\", \"getDefault\"\nreturn current"
        )

        val signatureHelp = harness.queries.signatureHelp(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "\"getDefault\"")
        )

        assertNotNull(signatureHelp)
        assertEquals("fun(className: string, memberName: string): fun(): string", signatureHelp.signatures.single().label)
        assertEquals(listOf("className: string", "memberName: string"), signatureHelp.signatures.single().parameters.map { it.label })
        assertEquals(1, signatureHelp.activeParameter)
    }

    @Test
    fun signature_help_accounts_for_method_receiver_offset() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local box = {}\n---@param self table\n---@param value number\n---@param label string\nfunction box:render(value, label)\n  return label\nend\nlocal current = box:render(1, \"hi\")\nreturn current"
        )

        val firstArgumentHelp = harness.queries.signatureHelp(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "1")
        )
        val secondArgumentHelp = harness.queries.signatureHelp(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "\"hi\"")
        )

        assertNotNull(firstArgumentHelp)
        assertEquals(listOf("self: table", "value: number", "label: string"), firstArgumentHelp.signatures.single().parameters.map { it.label })
        assertEquals(1, firstArgumentHelp.activeParameter)
        assertNotNull(secondArgumentHelp)
        assertEquals(2, secondArgumentHelp.activeParameter)
    }

    @Test
    fun document_highlights_collect_local_symbol_occurrences_within_the_same_file() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local value = 1\nlocal copy = value\nreturn value + copy"
        )

        val highlights = harness.queries.documentHighlights(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "value", occurrence = 2)
        )

        assertEquals(3, highlights.size)
        assertTrue(highlights.all { it.path == harness.path("main.lua") })
        assertEquals(listOf(1, 2, 3), highlights.map { it.range.start.line }.sorted())
    }

    @Test
    fun document_highlights_include_provider_definition_for_imported_members() {
        val harness = WorkspaceSemanticHarness.Companion.build(
            "main.lua" to "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn Arrays.asList",
            metadata = mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Arrays"),
            engine = JvmWorkspaceEngine()
        )

        val highlights = harness.queries.documentHighlights(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "asList", occurrence = 1)
        )

        assertTrue(highlights.any { it.path == harness.path("__jvm__/classes/java/util/Arrays.lua") })
        assertEquals(2, highlights.count { it.path == harness.path("main.lua") })
    }

    @Test
    fun declaration_prefers_local_binding_sites_over_read_usages() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local value = 1\nlocal copy = value\nreturn value + copy"
        )

        val declaration = harness.queries.declaration(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "value", occurrence = 3)
        )

        assertEquals(1, declaration.size)
        assertEquals(harness.path("main.lua"), declaration.single().path)
        assertEquals(1, declaration.single().range.start.line)
    }

    @Test
    fun declaration_resolves_provider_members_for_new_instance_backed_class_values() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local builder = luajava.newInstance \"java.lang.StringBuilder\"\nlocal current = builder.append\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val declaration = harness.queries.declaration(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "append")
        )
        val references = harness.queries.references(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "append")
        )

        assertEquals(1, declaration.size)
        assertEquals(harness.path("__jvm__/classes/java/lang/StringBuilder.lua"), declaration.single().path)
        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/lang/StringBuilder.lua") })
        assertTrue(references.any { it.path == harness.path("main.lua") })
    }

    @Test
    fun declaration_resolves_provider_members_for_multi_interface_create_proxy_values() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local proxy = luajava.createProxy(\"java.lang.Runnable\", \"java.util.Comparator\", {})\nlocal current = proxy.compare\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val declaration = harness.queries.declaration(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "compare")
        )
        val references = harness.queries.references(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "compare")
        )

        assertEquals(1, declaration.size)
        assertEquals(harness.path("__jvm__/classes/java/util/Comparator.lua"), declaration.single().path)
        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/util/Comparator.lua") })
        assertTrue(references.any { it.path == harness.path("main.lua") })
    }

    @Test
    fun declaration_resolves_provider_members_for_constructor_style_imported_jvm_class_values() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "import \"java.lang.StringBuilder\"\nlocal builder = StringBuilder()\nlocal current = builder.append\nreturn current",
            engine = JvmWorkspaceEngine()
        )

        val declaration = harness.queries.declaration(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "append")
        )
        val references = harness.queries.references(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "append")
        )

        assertEquals(1, declaration.size)
        assertEquals(harness.path("__jvm__/classes/java/lang/StringBuilder.lua"), declaration.single().path)
        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/lang/StringBuilder.lua") })
        assertTrue(references.any { it.path == harness.path("main.lua") })
    }

    @Test
    fun diagnostics_module_lookup_require_hover_and_references_flow_through_workspace_semantics() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = 1, run = function() return true end }",
            "main.lua" to "---@return string\nlocal function render()\n  local dep = require(\"dep\")\n  local current = dep.value\n  return dep.value\nend"
        )

        val depPath = harness.path("dep.lua")
        val mainPath = harness.path("main.lua")
        val diagnostics = harness.queries.diagnostics(mainPath)
        val module = harness.queries.lookupModule("dep")
        val resolved = harness.queries.resolveRequire(mainPath, "dep")
        val completions = harness.queries.completions(mainPath, harness.positionOf("main.lua", "value", occurrence = 1))
        val hover = harness.queries.hover(mainPath, harness.positionOf("main.lua", "value", occurrence = 1))
        val definitions = harness.queries.gotoDefinition(mainPath, harness.positionOf("main.lua", "value", occurrence = 1))
        val references = harness.queries.references(mainPath, harness.positionOf("main.lua", "value", occurrence = 1))

        assertTrue(diagnostics.any { it.code == "checker.function.return.typeMismatch" })
        assertNotNull(module.provider)
        assertEquals(depPath, module.provider.path)
        assertEquals(depPath, resolved.provider?.path)
        assertTrue(resolved.exportSurface?.moduleType is ModuleType)
        assertTrue(completions.any { it.label == "value" })
        assertTrue(completions.any { it.label == "run" })
        assertEquals("1", hover?.typeInfo?.displayName)
        assertEquals(listOf(depPath), definitions.map { it.path })
        assertTrue(references.any { it.path == depPath })
        assertTrue(references.any { it.path == mainPath })
    }

    @Test
    fun require_members_use_provider_pipeline_binder_types_without_rewriting_export_surface() {
        val harness = WorkspaceSemanticHarness.build(
            "a_main.lua" to "local provider = require(\"z_provider\")\nlocal result = provider.convert(\"value\")\nreturn result",
            "z_provider.lua" to "local M = {}\n---@param value string\n---@return number\nfunction M.convert(value)\n  return missing(value)\nend\nreturn M"
        )

        val mainPath = harness.path("a_main.lua")
        val providerPath = harness.path("z_provider.lua")
        val memberHover = harness.queries.hover(mainPath, harness.positionOf("a_main.lua", "convert"))
        val resultHover = harness.queries.hover(mainPath, harness.positionOf("a_main.lua", "result"))
        val structuralType = harness.snapshot.files.getValue(providerPath)
            .moduleExportSurface
            ?.members
            ?.single { it.exportPath == listOf("convert") }
            ?.type

        assertEquals("fun(value: unknown): unknown", structuralType?.displayName)
        assertEquals("fun(value: string): number", memberHover?.typeInfo?.displayName)
        assertEquals("number", resultHover?.typeInfo?.displayName)
    }

    @Test
    fun builtin_overlay_modules_resolve_for_require_queries() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local math = require(\"math\")\nlocal current = math.abs",
            standardLibraryOverlayVersion = LuaVersion.LUA_5_4
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "math")
        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "abs"))

        assertEquals(harness.path("__lua_std__/5.4/math.lua"), resolved.provider?.path)
        assertNotNull(hover?.symbol)
    }

    @Test
    fun unresolved_dynamic_and_shadowed_require_fall_back_cleanly() {
        val unresolved = WorkspaceSemanticHarness.build(
            "main.lua" to "local dep = require(name)\nlocal other = require(\"missing\")"
        )
        val shadowed = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to "local require = function() return { value = \"shadow\" } end\nlocal dep = require(\"dep\")\nlocal current = dep.value"
        )

        assertEquals(null, unresolved.queries.resolveRequire(unresolved.path("main.lua"), "missing").provider)
        assertEquals(null, unresolved.queries.resolveRequire(unresolved.path("main.lua"), unresolved.positionOf("main.lua", "missing"))?.provider)
        assertEquals(emptyList(), unresolved.queries.gotoDefinition(unresolved.path("main.lua"), unresolved.positionOf("main.lua", "missing")))

        val definitions = shadowed.queries.gotoDefinition(shadowed.path("main.lua"), shadowed.positionOf("main.lua", "value", occurrence = 2))
        assertEquals(null, shadowed.queries.resolveRequire(shadowed.path("main.lua"), shadowed.positionOf("main.lua", "dep", occurrence = 2))?.provider)
        assertFalse(definitions.any { it.path == shadowed.path("dep.lua") })
    }

    @Test
    fun resolve_require_only_reports_modules_for_real_builtin_call_sites() {
        val noRequire = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to "local current = 1"
        )
        val shadowed = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to "local require = function() return { value = \"shadow\" } end\nlocal dep = require(\"dep\")"
        )

        assertEquals(null, noRequire.queries.resolveRequire(noRequire.path("main.lua"), "dep").provider)
        assertEquals(null, shadowed.queries.resolveRequire(shadowed.path("main.lua"), shadowed.positionOf("main.lua", "dep", occurrence = 2))?.provider)
    }

    @Test
    fun goto_definition_resolves_direct_require_call_to_provider_module() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to "local dep = require(\"dep\")\nreturn dep"
        )

        val definition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "dep"))

        assertEquals(listOf(harness.path("dep.lua")), definition.map { it.path })
    }

    @Test
    fun goto_definition_resolves_locals_initialized_from_builtin_require() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to "local dep = require(\"dep\")\nreturn dep"
        )

        val definition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "dep", occurrence = 3))

        assertEquals(listOf(harness.path("dep.lua")), definition.map { it.path })
    }

    @Test
    fun goto_definition_keeps_nested_jvm_require_aliases_pointing_at_provider_modules() {
        val harness = WorkspaceSemanticHarness.Companion.build(
            "main.lua" to "local OnClickListener = require(\"OnClickListener\")\nreturn OnClickListener",
            metadata = mapOf(
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "OnClickListener",
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to "G:/Android/Sdk/platforms/android-35/android.jar",
                JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "android.view.View"
            ),
            engine = JvmWorkspaceEngine()
        )

        val definition = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "OnClickListener", occurrence = 3))

        assertEquals(listOf(harness.path("__jvm__/classes/android/view/View\$OnClickListener.lua")), definition.map { it.path })
    }

    @Test
    fun imported_symbol_queries_yield_to_visible_local_declarations() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "import \"java.lang.String\"\nlocal String = 1\nlocal current = String\nreturn String"
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "String", occurrence = 3))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "String", occurrence = 3))
        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "String", occurrence = 3))

        assertEquals("1", hover?.typeInfo?.displayName)
        assertEquals(listOf(harness.path("main.lua")), definitions.map { it.path })
        assertTrue(references.all { it.path == harness.path("main.lua") })
    }

    @Test
    fun imported_symbol_references_only_include_semantic_usages_and_respect_shadowing() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "import \"java.lang.String\"\nlocal before = String\nlocal String = 1\nlocal after = String\nreturn before",
            engine = JvmWorkspaceEngine()
        )

        val references = harness.queries.references(harness.path("main.lua"), harness.positionOf("main.lua", "String", occurrence = 2))

        assertTrue(references.any { it.path == harness.path("__jvm__/classes/java/lang/String.lua") })
        assertEquals(1, references.count { it.path == harness.path("main.lua") })
        assertFalse(references.any { it.path == harness.path("main.lua") && it.range.start.line == 3 })
        assertFalse(references.any { it.path == harness.path("main.lua") && it.range.start.line == 4 })
    }

    @Test
    fun imported_symbol_shadowing_prevents_fallback_to_dynamic_resolver() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "local String = 1\nreturn String",
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "String", occurrence = 2))
        val definitions = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "String", occurrence = 2))

        assertEquals("1", hover?.typeInfo?.displayName)
        assertEquals(listOf(harness.path("main.lua")), definitions.map { it.path })
    }

    @Test
    fun nested_require_alias_exports_flow_through_workspace_definition_and_references() {
        val harness = WorkspaceSemanticHarness.build(
            "dep.lua" to "local M = {}\nlocal alias = M\nalias.nested = {}\nlocal branch = alias.nested\nbranch.value = 1\nreturn alias",
            "main.lua" to "local dep = require(\"dep\")\nlocal first = dep\nlocal second = first.nested\nlocal current = second.value\nreturn second.value"
        )

        val depPath = harness.path("dep.lua")
        val mainPath = harness.path("main.lua")
        val definition = harness.queries.gotoDefinition(mainPath, harness.positionOf("main.lua", "value", occurrence = 1))
        val references = harness.queries.references(mainPath, harness.positionOf("main.lua", "value", occurrence = 1))
        val hover = harness.queries.hover(mainPath, harness.positionOf("main.lua", "value", occurrence = 1))

        assertEquals(listOf(depPath), definition.map { it.path })
        assertEquals("1", hover?.typeInfo?.displayName)
        assertTrue(references.any { it.path == depPath })
        assertEquals(2, references.count { it.path == mainPath })
    }

    @Test
    fun cyclic_require_exports_remain_queryable_through_workspace_semantics() {
        val harness = WorkspaceSemanticHarness.build(
            "a.lua" to "local b = require(\"b\")\nlocal M = { value = b.value }\nreturn M",
            "b.lua" to "local a = require(\"a\")\nlocal M = { value = a.value }\nreturn M",
            "main.lua" to "local a = require(\"a\")\nlocal current = a.value"
        )

        val exportHover = harness.queries.hover(harness.path("a.lua"), harness.positionOf("a.lua", "value", occurrence = 1))
        val refs = harness.queries.references(harness.path("a.lua"), harness.positionOf("a.lua", "value", occurrence = 1))
        val def = harness.queries.gotoDefinition(harness.path("main.lua"), harness.positionOf("main.lua", "value"))

        assertEquals(SymbolKind.FIELD, exportHover?.symbol?.kind)
        assertTrue(refs.count { it.path == harness.path("a.lua") } >= 1)
        assertEquals(harness.path("a.lua"), def.single().path)
    }
}
