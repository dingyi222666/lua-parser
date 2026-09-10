package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import semantic.support.WorkspaceSemanticHarness
import java.io.File
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AndroLua layout-table completions: inside `loadlayout({...}, ids)` tables the caret on a
 * property-key position must suggest the enclosing view class's Lua property keys
 * (loadlayout applies property `k` as `view.setCap(k)(value)`), not lexical globals.
 * The `id = "name"` entries must keep registering typed view fields on the ids table.
 */
class LuaLayoutCompletionTddTest {
    private val androidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

    @Test
    fun list_view_table_suggests_adapter_and_layout_keys() {
        Assume.assumeTrue(androidJar.isFile)
        val harness = harness(
            "main.lua" to """
                local tab = {}
                local view = loadlayout({
                  LinearLayout,
                  id = "root",
                  {
                    ListView,
                    id = "poplist",
                    adap
                  },
                }, tab)
                return view
            """.trimIndent()
        )

        val labels = completionLabels(harness, "adap")
        assertTrue("adapter" in labels, "Expected ListView 'adapter' property; actual: $labels")
        assertTrue("id" in labels, "Expected 'id' special key; actual: $labels")
        assertTrue("layout_width" in labels, "Expected LayoutParams keys; actual: $labels")
        assertFalse("textSize" in labels, "TextView-only keys must not appear on ListView; actual: $labels")
        assertFalse("print" in labels, "Lexical globals must not flood layout key context; actual: $labels")
    }

    @Test
    fun text_view_table_suggests_text_keys() {
        Assume.assumeTrue(androidJar.isFile)
        val harness = harness(
            "main.lua" to """
                local tab = {}
                local view = loadlayout({
                  LinearLayout,
                  {
                    TextView,
                    textCol
                  },
                }, tab)
                return view
            """.trimIndent()
        )

        val labels = completionLabels(harness, "textCol")
        assertTrue("textColor" in labels, "Expected TextView 'textColor' property; actual: $labels")
        assertTrue("textSize" in labels, "Expected TextView 'textSize' property; actual: $labels")
        assertFalse("adapter" in labels, "ListView-only keys must not appear on TextView; actual: $labels")
    }

    @Test
    fun ids_table_registers_layout_id_fields() {
        Assume.assumeTrue(androidJar.isFile)
        val harness = harness(
            "main.lua" to """
                local tab = {}
                local view = loadlayout({
                  LinearLayout,
                  id = "root",
                  {
                    ListView,
                    id = "poplist",
                  },
                }, tab)
                tab.poplist
                return view
            """.trimIndent()
        )

        val labels = completionLabels(harness, "poplist", occurrence = 2)
        assertTrue("poplist" in labels, "Expected ids-table member 'poplist'; actual: $labels")
        assertTrue("root" in labels, "Expected ids-table member 'root'; actual: $labels")
    }

    @Test
    fun non_layout_table_keeps_regular_completions() {
        Assume.assumeTrue(androidJar.isFile)
        val harness = harness(
            "main.lua" to """
                local config = {
                  option
                }
                return config
            """.trimIndent()
        )

        val labels = completionLabels(harness, "option")
        assertTrue("print" in labels, "Plain table context must keep lexical completions; actual: $labels")
        assertFalse("layout_width" in labels, "Layout keys must not leak into plain tables; actual: $labels")
    }

    @Test
    fun aly_layout_file_gets_property_completions() {
        Assume.assumeTrue(androidJar.isFile)
        val harness = WorkspaceSemanticHarness.build(
            "popup.aly" to """
                {
                  LinearLayout,
                  layout_width = "-1",
                  id = "rootv",
                  {
                    TextView,
                    textCol
                  },
                }
            """.trimIndent(),
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path
            ),
            engine = JvmWorkspaceEngine()
        )

        val labels = harness.queries.completions(
            harness.path("popup.aly"),
            harness.positionOf("popup.aly", "textCol")
        ).map { it.label }
        assertTrue("textColor" in labels, "Expected TextView properties inside .aly file; actual: $labels")
        assertTrue("id" in labels, "Expected 'id' special key inside .aly file; actual: $labels")
        assertTrue("layout_width" in labels, "Expected LayoutParams keys inside .aly file; actual: $labels")
    }

    @Test
    fun metadata_extensions_add_custom_class_properties() {
        Assume.assumeTrue(androidJar.isFile)
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local tab = {}
                local view = loadlayout({
                  LinearLayout,
                  {
                    LuaRecyclerView,
                    refre
                  },
                }, tab)
                return view
            """.trimIndent(),
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path,
                "lua.layout.properties" to """
                    # project-defined custom views
                    LuaRecyclerView: refresh|pull-to-refresh callback(function), loadMore|load-more callback(function)
                """.trimIndent()
            ),
            engine = JvmWorkspaceEngine()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "refre")
        )
        val labels = completions.map { it.label }
        assertTrue("refresh" in labels, "Expected extended 'refresh' property; actual: $labels")
        assertTrue("loadMore" in labels, "Expected extended 'loadMore' property; actual: $labels")
        assertEquals(
            "pull-to-refresh callback(function)",
            completions.first { it.label == "refresh" }.detail,
            "Extended property detail must surface"
        )
    }

    @Test
    fun layout_value_string_literals_suggest_domains() {
        Assume.assumeTrue(androidJar.isFile)
        val harness = harness(
            "main.lua" to """
                local tab = {}
                local view = loadlayout({
                  LinearLayout,
                  layout_width = "wr",
                  orientation = "verti",
                  layout_marginTop = "8dp",
                  {
                    ListView,
                    id = "poplist",
                  },
                }, tab)
                return view
            """.trimIndent()
        )

        val widthValues = completionLabels(harness, "wr")
        assertTrue("wrap_content" in widthValues, "Expected wrap_content for layout_width; actual: $widthValues")
        assertTrue("match_parent" in widthValues, "Expected match_parent for layout_width; actual: $widthValues")
        assertTrue("50%w" in widthValues, "Expected percent widths; actual: $widthValues")

        val orientationValues = completionLabels(harness, "verti")
        assertTrue("vertical" in orientationValues, "Expected vertical; actual: $orientationValues")
        assertTrue("horizontal" in orientationValues, "Expected horizontal; actual: $orientationValues")
    }

    @Test
    fun require_and_import_strings_suggest_workspace_modules() {
        // Module-name completion is jar-independent; no android gate here.
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local u = require("ut
                import "mo
                return u
            """.trimIndent(),
            "mods/util.lua" to """
                return {}
            """.trimIndent(),
            engine = JvmWorkspaceEngine()
        )

        // The caret sits inside the unterminated require string ("ut"), not on a lexical
        // identifier — assert module names without lexical fallback masking a regression.
        val requireValues = completionLabels(harness, "ut")
        assertTrue("util" in requireValues, "Expected basename module 'util'; actual: $requireValues")
        assertTrue("mods.util" in requireValues, "Expected dotted module 'mods.util'; actual: $requireValues")

        val importValues = completionLabels(harness, "mo")
        assertTrue("util" in importValues, "Expected basename module 'util'; actual: $importValues")
        assertTrue("mods.util" in importValues, "Expected dotted module 'mods.util'; actual: $importValues")
    }

    @Test
    fun class_slot_caret_keeps_regular_completions() {
        Assume.assumeTrue(androidJar.isFile)
        val harness = harness(
            "main.lua" to """
                local tab = {}
                local view = loadlayout({
                  ListV
                }, tab)
                return view
            """.trimIndent()
        )

        val labels = completionLabels(harness, "ListV")
        assertFalse(
            "adapter" in labels,
            "Class-slot caret must not yield view properties; actual: $labels"
        )
        assertTrue(
            "print" in labels,
            "Class-slot caret must keep lexical completions; actual: $labels"
        )
    }

    @Test
    fun empty_and_mid_string_values_suggest_domains() {
        Assume.assumeTrue(androidJar.isFile)
        val source = """
            local tab = {}
            local view = loadlayout({
              LinearLayout,
              layout_width = "",
              layout_gravity = "",
              orientation = "verti",
              gravity = "left|",
            }, tab)
            return view
        """.trimIndent()
        val harness = harness("main.lua" to source)

        fun posInsideQuotes(keyName: String): io.github.dingyi222666.luaparser.parser.ast.node.Position {
            val keyIndex = source.indexOf(keyName)
            check(keyIndex >= 0) { "missing $keyName" }
            val openQuote = source.indexOf("\"", keyIndex) + 1
            var line = 1
            var column = 1
            for (i in 0 until openQuote) {
                if (source[i] == '\n') {
                    line++
                    column = 1
                } else {
                    column++
                }
            }
            return io.github.dingyi222666.luaparser.parser.ast.node.Position(line, column)
        }

        val widthValues = harness.queries
            .completions(harness.path("main.lua"), posInsideQuotes("layout_width"))
            .map { it.label }
        assertEquals(
            listOf("wrap", "fill", "match", "-1", "-2"),
            widthValues.take(5),
            "Expected AndroLua size constants first for layout_width; actual: $widthValues"
        )

        val gravityValues = harness.queries
            .completions(harness.path("main.lua"), posInsideQuotes("layout_gravity"))
            .map { it.label }
        assertTrue("center" in gravityValues, "Expected gravity tokens; actual: $gravityValues")

        val orientationValues = harness.queries
            .completions(harness.path("main.lua"), posInsideQuotes("orientation"))
            .map { it.label }
        assertTrue("vertical" in orientationValues && "horizontal" in orientationValues,
            "Expected orientation tokens; actual: $orientationValues")
    }

    private fun harness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = mapOf(
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path
            ),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun completionLabels(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ): List<String> {
        return harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        ).map { it.label }
    }
}
