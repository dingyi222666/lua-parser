package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class EditorMemberRegressionTest {
    @Test
    fun reference_queries_cannot_restore_rejected_constructor_members() {
        val source = """
            local broken = luajava.newInstance('java.io.File', false)
            broken.
        """.trimIndent()
        val harness = WorkspaceSemanticHarness.build("main.lua" to source, engine = JvmWorkspaceEngine())
        val labels = harness.queries.completions(harness.path("main.lua"), Position(2, 8)).map { it.label }
        assertTrue("delete" !in labels && "exists" !in labels, labels.toString())
        assertEquals("unknown", harness.queries.hover(harness.path("main.lua"), Position(2, 1))?.typeInfo?.displayName)
    }

    @Test
    fun a_shadowed_import_function_never_uses_the_java_import_bridge() {
        val source = """
            local function import(name) return { customMember = 1 } end
            local value = import('java.io.File')
            value.
        """.trimIndent()
        val harness = WorkspaceSemanticHarness.build("main.lua" to source, engine = JvmWorkspaceEngine())
        val labels = harness.queries.completions(harness.path("main.lua"), Position(3, 7)).map { it.label }
        assertTrue("customMember" in labels && "delete" !in labels, labels.toString())
    }

    @Test
    fun native_loader_spelling_never_overrides_lua_bindings_or_invalid_calls() {
        val source = """
            require 'import'
            local function loadbitmap(path) return 'local bitmap' end
            local loader = { loadmenu = function() return 42 end }
            local image = loadbitmap('file')
            local menu = loader.loadmenu()
            local badLayout = loadlayout(false)
            return image, menu, badLayout
        """.trimIndent()
        val harness = WorkspaceSemanticHarness.build("main.lua" to source, engine = JvmWorkspaceEngine())
        val image = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "image", 2))
        assertTrue(image?.typeInfo?.displayName.orEmpty().contains("local bitmap"), image.toString())
        val menu = harness.queries.hover(harness.path("main.lua"), harness.positionOf("main.lua", "menu", 4))
        assertTrue(menu?.typeInfo?.displayName.orEmpty().contains("42"), menu.toString())
        assertEquals("unknown", harness.queries.hover(harness.path("main.lua"),
            harness.positionOf("main.lua", "badLayout", 2))?.typeInfo?.displayName)
    }

    @Test
    fun rejected_java_calls_never_gain_constructor_or_fluent_return_types() {
        val source = """
            import 'java.io.File'
            import 'java.lang.StringBuilder'
            import 'java.lang.Math'
            local badFile = File(42)
            local badLength = StringBuilder().setLength('bad')
            local badFloor = Math.floor('bad')
            local badRunnable = luajava.newInstance('java.lang.Runnable')
            local goodBuilder = StringBuilder().append('ok')
            return badFile, badLength, badFloor, badRunnable, goodBuilder
        """.trimIndent()
        val harness = WorkspaceSemanticHarness.build("main.lua" to source, engine = JvmWorkspaceEngine())
        for (name in listOf("badFile", "badLength", "badFloor", "badRunnable")) {
            assertEquals("unknown", harness.queries.hover(harness.path("main.lua"),
                harness.positionOf("main.lua", name, 2))?.typeInfo?.displayName, name)
        }
        assertTrue(harness.queries.hover(harness.path("main.lua"),
            harness.positionOf("main.lua", "goodBuilder", 2))?.typeInfo?.displayName.orEmpty().contains("StringBuilder"))
    }

    @Test
    fun unannotated_function_return_still_reports_unknown_java_members() {
        val source = """
            import "java.lang.StringBuilder"
            function read()
                return StringBuilder().missingMember()
            end
        """.trimIndent()
        val harness = WorkspaceSemanticHarness.build("main.lua" to source, engine = JvmWorkspaceEngine())
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        assertTrue(diagnostics.any { it.message.contains("Unknown Java member") && it.message.contains("missingMember") },
            "skipping unconstrained return checks must keep expression diagnostics: $diagnostics")
    }

    @Test
    fun missing_runtime_class_keeps_the_reflected_framework_parent() {
        val source = """
            ---@class HostHelpers
            ---@field luaDir string
            ---@class MissingHost: HostHelpers|java.util.ArrayList
            ---@java-class fixture.AbsentHost
            ---@type MissingHost
            local host
            host.
        """.trimIndent()
        val harness = WorkspaceSemanticHarness.build("main.lua" to source, engine = JvmWorkspaceEngine())
        val labels = harness.queries.completions(harness.path("main.lua"), Position(7, 6)).map { it.label }
        assertTrue("clear" in labels && "add" in labels && "luaDir" in labels,
            "unloadable runtime must keep both framework and documented members: $labels")
    }

    @Test
    fun gradient_drawable_newline_dot_before_remaining_chain_offers_members() {
        val source = """
            import "android.graphics.drawable.GradientDrawable"
            local roundDrawable=GradientDrawable()
            .setShape(GradientDrawable.RECTANGLE)
            .setCornerRadius(16)
            .setColor(0xffffffff)
            .
            .setStroke(2,0x1f000000)
        """.trimIndent()
        val harness = WorkspaceSemanticHarness.build("main.lua" to source, engine = JvmWorkspaceEngine())
        val labels = harness.queries.completions(harness.path("main.lua"), Position(6, 2)).map { it.label }
        assertTrue("setStroke" in labels, "GradientDrawable members missing: $labels")
        assertTrue("setColor" in labels, "GradientDrawable receiver lost: $labels")
    }

    @Test
    fun activity_action_bar_property_and_nested_members_resolve() {
        val source = "require 'import'\nactivity.actionBar.hide()\nactivity.actionBar.elevation=1"
        val harness = WorkspaceSemanticHarness.build("main.lua" to source, engine = JvmWorkspaceEngine())
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        assertTrue(diagnostics.none { it.message.contains("Unknown Java member") }, diagnostics.toString())
        val labels = harness.queries.completions(harness.path("main.lua"), Position(2, 20)).map { it.label }
        assertTrue("hide" in labels && "show" in labels, "ActionBar member surface missing: $labels")
    }
}
