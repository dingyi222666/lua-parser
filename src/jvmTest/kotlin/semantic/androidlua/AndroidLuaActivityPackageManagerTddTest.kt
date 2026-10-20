package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import semantic.support.WorkspaceSemanticHarness
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidLuaActivityPackageManagerTddTest {
    private val androidJar = File(
        requireNotNull(JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()) {
            "android.jar is required for Android-Lua reflection tests"
        }
    )

    @Test
    fun lua_activity_inherits_package_manager_and_installed_package_list_surface() {
        val harness = androidHarness(
            "main.lua" to """
                require "import"
                local pm = activity.getPackageManager();
                local packages = pm.getInstalledPackages(0)
                local applist = luajava.astable(packages)--获取本机所有安装程序信息table
                local first = applist[1]
                local packageName = first.packageName
                return pm, packages, applist, first, packageName
            """.trimIndent()
        )

        val activityMembers = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "getPackageManager")
        )
        assertTrue(
            activityMembers.any { it.label == "getPackageManager" && it.kind == CompletionItemKind.METHOD },
            "LuaActivity must inherit android.content.Context members through android.app.Activity: $activityMembers"
        )
        assertTrue(
            activityMembers.any { it.label == "getLuaDir" && it.kind == CompletionItemKind.METHOD },
            "LuaActivity must retain AndroidLuaContext members: $activityMembers"
        )

        assertHoverContains(harness, "pm", "android.content.pm.PackageManager", occurrence = 2)
        assertHoverContains(harness, "packages", "java.util.List", occurrence = 2)
        assertHoverContains(harness, "first", "android.content.pm.PackageInfo", occurrence = 2)
        assertEquals(
            "string",
            harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "packageName", occurrence = 2)
            )?.typeInfo?.displayName
        )

        val invalidMembers = harness.queries.diagnostics(harness.path("main.lua"))
            .filter { diagnostic ->
                diagnostic.message.contains("getPackageManager") ||
                    diagnostic.message.contains("getInstalledPackages") ||
                    diagnostic.message.contains("packageName")
            }
        assertTrue(invalidMembers.isEmpty(), "Unexpected activity/package-manager diagnostics: $invalidMembers")
    }

    private fun androidHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        require(androidJar.isFile) { "android.jar is required: ${androidJar.path}" }
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine(
                configuration = JvmWorkspaceConfiguration(androidJar = androidJar.path)
            )
        )
    }

    private fun assertHoverContains(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int
    ) {
        val displayName = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )?.typeInfo?.displayName.orEmpty()
        assertTrue(expected in displayName, "Expected '$needle' type to contain '$expected', got '$displayName'")
    }
}
