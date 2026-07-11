package semantic.model

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.api.TypeInfoKind
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Preferred hover-type collapse corpus for workspace/ReferenceQueries hover surface.
 *
 * Encodes acceptance for TASK-429 / TASK-601 (preferred hover product lock):
 * - Structural table-literal displays (`{...}`) collapse to coarse `table`.
 * - Symbol / declared types win over weak node types when not structural tables.
 * - Import MODULE typing (moduleName / MODULE kind) is preferred over unknown/any
 *   node evaluation of dynamic `import()` locals.
 * - Multi-import Array<> display is preferred over structural `T[]` form.
 * - Callable `fun(` symbol surfaces are preferred when primary lacks them.
 *
 * Exercises [io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade]
 * hover path (preferredHoverType) — the public surface that ReferenceQueries /
 * SemanticModel feed into for workspace-aware hover.
 *
 * Product lock lives in ReferenceQueries + workspace/LSP preferredHoverType.
 * Verification deferred to TASK-043 serial jvmTest.
 */
class ReferenceQueriesHoverPreferredTypeTddTest {

    // -------------------------------------------------------------------------
    // Table-literal collapse: structural `{...}` → coarse `table`
    // -------------------------------------------------------------------------

    @Test
    fun local_empty_table_literal_hover_collapses_to_table() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local bag = {}
                return bag
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "bag", occurrence = 2)
        )

        assertNotNull(hover, "Expected hover on bag usage")
        assertEquals(
            "table",
            hover.typeInfo?.displayName,
            "Structural empty table literal must collapse to coarse 'table'; got ${hover.typeInfo}"
        )
        assertFalse(
            hover.typeInfo?.displayName.orEmpty().startsWith("{"),
            "Hover must not expose structural table display starting with '{'"
        )
        assertEquals(TypeInfoKind.TABLE, hover.typeInfo?.kind)
    }

    @Test
    fun local_record_table_literal_hover_collapses_to_table_not_structural() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local record = { x = 1, y = "ok" }
                return record
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "record", occurrence = 2)
        )

        assertNotNull(hover)
        assertEquals(
            "table",
            hover.typeInfo?.displayName,
            "Fielded table literal must collapse; got ${hover.typeInfo?.displayName}"
        )
        assertFalse(hover.typeInfo?.displayName.orEmpty().startsWith("{"))
        assertEquals("table", hover.typeInfo?.detail)
        assertEquals(TypeInfoKind.TABLE, hover.typeInfo?.kind)
    }

    @Test
    fun nested_table_literal_assignment_hover_collapses_to_table() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local outer = { inner = { n = 1 } }
                return outer
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "outer", occurrence = 2)
        )

        assertNotNull(hover)
        assertEquals("table", hover.typeInfo?.displayName)
        assertFalse(hover.typeInfo?.displayName.orEmpty().startsWith("{"))
    }

    @Test
    fun table_literal_shadowing_imported_module_collapses_to_table_not_module() {
        val harness = jvmHarness(
            "main.lua" to """
                import "java.io.File"
                local before = File.separator
                local File = { separator = 1 }
                local after = File.separator
                return before + after
            """.trimIndent()
        )

        // occurrence 3: local File table shadow at usage after declaration
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "File", occurrence = 3)
        )

        assertNotNull(hover)
        assertEquals(
            "table",
            hover.typeInfo?.displayName,
            "Local table shadow of imported File must collapse to table; got ${hover.typeInfo}"
        )
        assertFalse(
            hover.typeInfo?.displayName.orEmpty().startsWith("{"),
            "Shadow table must not expose structural literal display"
        )
        // Must not keep MODULE kind from the earlier import once shadowed by table local.
        assertTrue(
            hover.symbol?.kind != SymbolKind.MODULE || hover.typeInfo?.kind == TypeInfoKind.TABLE,
            "Preferred type kind after table shadow should be TABLE (symbol=${hover.symbol?.kind}, type=${hover.typeInfo})"
        )
    }

    @Test
    fun table_literal_at_declaration_site_also_collapses_when_hovered() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local bag = { key = true }
                local use = bag
                return use
            """.trimIndent()
        )

        // Declaration identifier (first bag)
        val declHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "bag", occurrence = 1)
        )
        // Usage (second bag)
        val useHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "bag", occurrence = 2)
        )

        listOf(declHover to "declaration", useHover to "usage").forEach { (hover, label) ->
            assertNotNull(hover, "Expected hover at $label site")
            assertEquals(
                "table",
                hover.typeInfo?.displayName,
                "$label site must collapse table literal; got ${hover.typeInfo?.displayName}"
            )
            assertFalse(
                hover.typeInfo?.displayName.orEmpty().startsWith("{"),
                "$label site must not start with structural '{'"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Non-table symbol types stay preferred (no forced collapse)
    // -------------------------------------------------------------------------

    @Test
    fun number_literal_local_hover_keeps_number_not_table() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local count = 42
                return count
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "count", occurrence = 2)
        )

        assertNotNull(hover)
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display == "number" || display == "42" || display.contains("number"),
            "Number local must not collapse to table; got '$display'"
        )
        assertFalse(display.startsWith("{"))
        assertTrue(display != "table" || display.contains("number"))
    }

    @Test
    fun string_literal_local_hover_keeps_string_surface() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local label = "hello"
                return label
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "label", occurrence = 2)
        )

        assertNotNull(hover)
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display == "string" || display.contains("string") || display.contains("hello"),
            "String local must keep string-ish surface; got '$display'"
        )
        assertFalse(display.startsWith("{"))
        assertTrue(display != "table")
    }

    @Test
    fun function_local_hover_prefers_fun_shaped_symbol_type() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local function greet(name)
                  return name
                end
                return greet
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "greet", occurrence = 2)
        )

        assertNotNull(hover)
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display.contains("fun(") ||
                hover.symbol?.kind == SymbolKind.FUNCTION ||
                display.contains("function"),
            "Function local hover should prefer fun-shaped / FUNCTION surface; got display='$display' kind=${hover.symbol?.kind}"
        )
        assertFalse(display.startsWith("{"))
    }

    // -------------------------------------------------------------------------
    // Import MODULE preferred over weak node types
    // -------------------------------------------------------------------------

    @Test
    fun import_module_hover_prefers_module_type_with_module_name() {
        val harness = jvmHarness(
            "main.lua" to """
                import "java.util.Locale"
                local current = Locale.ROOT
                return current
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Locale", occurrence = 2)
        )

        assertNotNull(hover, "Expected hover on imported Locale usage")
        assertEquals(SymbolKind.MODULE, hover.symbol?.kind)
        val typeInfo = assertNotNull(hover.typeInfo)
        // MODULE surface: either moduleName set or MODULE kind / non-unknown display.
        assertTrue(
            !typeInfo.moduleName.isNullOrBlank() ||
                typeInfo.kind == TypeInfoKind.MODULE ||
                typeInfo.displayName.contains("Locale"),
            "Import MODULE hover must prefer module-bearing type; got $typeInfo"
        )
        assertFalse(
            typeInfo.displayName == "unknown" || typeInfo.displayName == "any",
            "Import MODULE must not collapse to bare unknown/any; got ${typeInfo.displayName}"
        )
        assertFalse(typeInfo.displayName.startsWith("{"))
    }

    @Test
    fun dynamic_import_call_local_hover_prefers_module_over_unknown_node() {
        val harness = jvmHarness(
            "main.lua" to """
                local import = require("import")
                local File = import("java.io.File")
                return File
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "File", occurrence = 2)
        )

        assertNotNull(hover)
        val typeInfo = assertNotNull(hover.typeInfo, "Expected typeInfo on import() local")
        assertTrue(
            !typeInfo.moduleName.isNullOrBlank() ||
                typeInfo.kind == TypeInfoKind.MODULE ||
                typeInfo.displayName.contains("File") ||
                hover.symbol?.kind == SymbolKind.MODULE,
            "import() local must prefer MODULE surface over unknown node type; got $typeInfo symbol=${hover.symbol}"
        )
        assertFalse(
            typeInfo.displayName == "unknown" && typeInfo.moduleName.isNullOrBlank(),
            "Must not leave bare unknown without moduleName when import resolved; got $typeInfo"
        )
    }

    @Test
    fun multi_import_array_display_is_preferred_over_structural_bracket_form() {
        val harness = jvmHarness(
            "main.lua" to """
                local import = require("import")
                local classes = import({ "java.io.File", "java.util.Locale" })
                return classes
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "classes", occurrence = 2)
        )

        assertNotNull(hover)
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display.startsWith("Array<"),
            "Multi-import must prefer Array<...> over structural T[] form; got '$display'"
        )
        // Structural union[] form is the non-preferred primary that preferredHoverType
        // collapses away when fallback starts with Array<.
        assertFalse(
            display.endsWith("[]") && !display.startsWith("Array<"),
            "Must not leave bare structural [] form when Array<> fallback exists; got '$display'"
        )
    }

    @Test
    fun require_import_callable_hover_prefers_fun_shaped_builtin() {
        val harness = jvmHarness(
            "main.lua" to """
                local import = require("import")
                return import
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "import", occurrence = 2)
        )

        assertNotNull(hover)
        assertEquals(SymbolKind.FUNCTION, hover.symbol?.kind)
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display.contains("fun("),
            "require('import') symbol hover must prefer fun( surface; got '$display'"
        )
    }

    @Test
    fun static_method_hover_prefers_fun_over_non_callable_primary() {
        val harness = jvmHarness(
            "main.lua" to """
                import "java.lang.System"
                local current = System.currentTimeMillis
                return current
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "currentTimeMillis")
        )

        assertNotNull(hover)
        assertEquals(SymbolKind.METHOD, hover.symbol?.kind)
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display.contains("fun(") || display.contains("fun<"),
            "Static method hover must prefer fun-shaped type; got '$display'"
        )
        assertFalse(display.startsWith("{"))
        assertTrue(display != "table")
    }

    // -------------------------------------------------------------------------
    // Matrix: table collapse coexists with MODULE preference in one file
    // -------------------------------------------------------------------------

    @Test
    fun mixed_file_table_and_import_module_use_distinct_preferred_types() {
        val harness = jvmHarness(
            "main.lua" to """
                import "java.util.Locale"
                local bag = { flag = true }
                local useBag = bag
                local useLocale = Locale.ROOT
                return useBag, useLocale
            """.trimIndent()
        )

        val bagHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "bag", occurrence = 2)
        )
        val localeHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Locale", occurrence = 2)
        )

        assertNotNull(bagHover)
        assertNotNull(localeHover)

        assertEquals(
            "table",
            bagHover.typeInfo?.displayName,
            "Table local in mixed file must still collapse; got ${bagHover.typeInfo}"
        )
        assertFalse(bagHover.typeInfo?.displayName.orEmpty().startsWith("{"))

        val localeType = assertNotNull(localeHover.typeInfo)
        assertTrue(
            !localeType.moduleName.isNullOrBlank() ||
                localeType.kind == TypeInfoKind.MODULE ||
                localeType.displayName.contains("Locale") ||
                localeHover.symbol?.kind == SymbolKind.MODULE,
            "Locale import in mixed file must keep MODULE preference; got $localeType"
        )
        assertTrue(localeType.displayName != "table")
        assertFalse(localeType.displayName.startsWith("{"))
    }

    @Test
    fun empty_table_vs_module_import_do_not_cross_contaminate_display() {
        val harness = jvmHarness(
            "main.lua" to """
                import "java.io.File"
                local empty = {}
                return empty, File
            """.trimIndent()
        )

        val emptyHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "empty", occurrence = 2)
        )
        val fileHover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "File", occurrence = 2)
        )

        assertEquals("table", emptyHover?.typeInfo?.displayName)
        assertNotNull(fileHover)
        assertTrue(
            fileHover.typeInfo?.displayName != "table" ||
                !fileHover.typeInfo?.moduleName.isNullOrBlank() ||
                fileHover.symbol?.kind == SymbolKind.MODULE,
            "File MODULE must not be collapsed solely as anonymous table; got ${fileHover.typeInfo} symbol=${fileHover.symbol}"
        )
    }

    // -------------------------------------------------------------------------
    // TASK-601: declared/inferred FunctionType/ClassType win over bare unknown
    // -------------------------------------------------------------------------

    @Test
    fun annotated_local_function_hover_prefers_function_type_not_unknown() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                ---@param name string
                ---@return string
                local function greet(name)
                  return name
                end
                return greet
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "greet", occurrence = 2)
        )

        assertNotNull(hover)
        val typeInfo = assertNotNull(hover.typeInfo)
        assertTrue(
            typeInfo.kind == TypeInfoKind.FUNCTION ||
                typeInfo.displayName.contains("fun(") ||
                typeInfo.displayName.contains("function"),
            "Annotated local function must surface FunctionType, not bare unknown; got $typeInfo"
        )
        assertFalse(
            typeInfo.displayName == "unknown" || typeInfo.displayName == "any",
            "Must not leave bare unknown/any when FunctionType is available; got ${typeInfo.displayName}"
        )
    }

    @Test
    fun emmy_class_local_hover_prefers_class_type_not_unknown() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                ---@class Point
                ---@field x number
                ---@field y number
                local Point = {}
                local p = Point
                return p
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Point", occurrence = 2)
        )

        assertNotNull(hover)
        val typeInfo = assertNotNull(hover.typeInfo)
        assertTrue(
            typeInfo.kind == TypeInfoKind.CLASS ||
                typeInfo.kind == TypeInfoKind.TABLE ||
                typeInfo.displayName.contains("Point") ||
                typeInfo.displayName == "table",
            "Emmy class local should prefer ClassType/table surface over bare unknown; got $typeInfo"
        )
        assertFalse(
            typeInfo.displayName == "unknown" && typeInfo.kind == TypeInfoKind.UNKNOWN,
            "Must not leave bare unknown when class/table surface exists; got $typeInfo"
        )
    }

    @Test
    fun java_import_class_hover_prefers_module_or_class_not_unknown() {
        val harness = jvmHarness(
            "main.lua" to """
                import "java.util.ArrayList"
                local list = ArrayList
                return list
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "ArrayList", occurrence = 2)
        )

        assertNotNull(hover, "Expected hover on imported ArrayList")
        val typeInfo = assertNotNull(hover.typeInfo)
        assertTrue(
            typeInfo.kind == TypeInfoKind.MODULE ||
                typeInfo.kind == TypeInfoKind.CLASS ||
                !typeInfo.moduleName.isNullOrBlank() ||
                typeInfo.displayName.contains("ArrayList"),
            "Imported Java class must prefer MODULE/ClassType; got $typeInfo"
        )
        assertFalse(
            typeInfo.displayName == "unknown" || typeInfo.displayName == "any",
            "Imported class must not collapse to bare unknown/any; got ${typeInfo.displayName}"
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
    }
}
