package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import semantic.support.WorkspaceSemanticHarness
import java.io.File
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
        if (!androidJar.isFile) {
            return
        }
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
        if (!androidJar.isFile) {
            return
        }
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
        if (!androidJar.isFile) {
            return
        }
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
        if (!androidJar.isFile) {
            return
        }
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
        if (!androidJar.isFile) {
            return
        }
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
        if (!androidJar.isFile) {
            return
        }
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
