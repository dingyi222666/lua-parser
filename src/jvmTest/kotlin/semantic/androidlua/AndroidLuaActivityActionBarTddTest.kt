package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import semantic.support.WorkspaceSemanticHarness
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidLuaActivityActionBarTddTest {
    private val androidJar = File(
        requireNotNull(JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()) {
            "android.jar is required for Android-Lua ActionBar regression"
        }
    )

    @Test
    fun activity_action_bar_exposes_java_methods_and_bean_properties() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "activity.actionBar.\nactivity.actionBar.hide()\nactivity.actionBar.show()",
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine(
                classModuleProvider = JvmClassModuleProvider(deferPackageMemberReflection = true),
                configuration = JvmWorkspaceConfiguration(androidJar = androidJar.path)
            )
        )
        val labels = harness.queries.completions(
            harness.path("main.lua"),
            Position(1, "activity.actionBar.".length + 1)
        )
        assertTrue(labels.any { it.label == "hide" && it.kind == CompletionItemKind.METHOD }, labels.toString())
        assertTrue(labels.any { it.label == "show" && it.kind == CompletionItemKind.METHOD }, labels.toString())
        assertTrue(labels.any { it.label == "elevation" }, labels.toString())
        val receiver = harness.queries.completions(harness.path("main.lua"), Position(1, 2))
        assertTrue(receiver.any { it.label == "activity" && it.kind == CompletionItemKind.VARIABLE }, receiver.toString())
        assertTrue(receiver.none { it.label == "hide" }, "Receiver caret must stay lexical: $receiver")
    }
}
