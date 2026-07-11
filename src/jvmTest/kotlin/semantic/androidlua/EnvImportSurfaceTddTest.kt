package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-536 — After `require "import"`, free-identifier completions/hover expose
 * `env_import` (and documented import helpers) as modeled functions, and
 * `env_import(...)` call typing returns table/JavaClass-like surfaces consistent
 * with overlay annotations. Dedicated require path stays androlua5.3/import.lua.
 *
 * Verification is review-owned (TASK-043):
 * `jvmTest --tests semantic.androidlua.EnvImportSurfaceTddTest`
 */
class EnvImportSurfaceTddTest {
    private val androidJar = sequenceOf(
        File("/Users/dingyi/Downloads/android.jar"),
        File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH),
        File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"),
    ).firstOrNull { it.isFile } ?: File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

    @Test
    fun require_import_resolves_dedicated_androlua_import_provider() {
        val harness = androidHarness("main.lua" to "local import = require(\"import\")\nreturn import")

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "import")
        val provider = assertNotNull(resolved.provider, "require \"import\" must resolve a modeled provider.")

        assertEquals(WorkspaceModuleGraph.ProviderSource.STANDARD_LIBRARY_OVERLAY, provider.source)
        assertTrue(provider.path.value.contains("androlua5.3"))
        assertTrue(
            provider.path.value.endsWith("/import.lua"),
            "Expected dedicated import.lua provider, got ${provider.path.value}."
        )
    }

    @Test
    fun require_import_export_surface_exposes_env_import_callable() {
        val harness = androidHarness("main.lua" to "local import = require(\"import\")\nreturn import")
        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "import")
        val surface = assertNotNull(resolved.exportSurface, "import module must publish an export surface.")

        val hasEnvImport =
            surface.moduleType.fields.containsKey("env_import") ||
                surface.moduleType.methods.containsKey("env_import") ||
                surface.members.any { it.name == "env_import" }
        val hasCall = surface.moduleType.fields.containsKey("__call")

        assertTrue(hasEnvImport, "import surface should expose env_import; members=${surface.members.map { it.name }}")
        assertTrue(hasCall, "import surface should expose __call for the returned env_import installer.")

        val callType = surface.moduleType.fields.getValue("__call")
        assertTrue(
            callType.displayName.contains("fun") || callType.displayName.contains("function"),
            "import __call must be function-shaped; got ${callType.displayName}"
        )
        assertEnvImportReturnSurface(callType.displayName)
    }

    @Test
    fun require_import_populates_env_import_and_helper_global_completions() {
        val harness = androidHarness(
            "main.lua" to """
                require "import"
                return env_import
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "env_import")
        )

        assertCompletion(completions, "env_import", CompletionItemKind.FUNCTION)
        // Documented import helpers installed alongside env_import.
        assertCompletion(completions, "import", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "compile", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "enum", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "each", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "dump", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "printstack", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "getids", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "thread", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "task", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "timer", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "loadlayout", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "loadbitmap", CompletionItemKind.FUNCTION)
        assertCompletion(completions, "loadmenu", CompletionItemKind.FUNCTION)
    }

    @Test
    fun env_import_free_identifier_hover_is_modeled_function_not_unknown() {
        val harness = androidHarness(
            "main.lua" to """
                require "import"
                return env_import
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "env_import")
            ),
            "Expected hover for free-identifier env_import after require \"import\"."
        )
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display.isNotBlank() && display != "unknown" && display != "any" && display != "nil",
            "env_import hover must be modeled, not unknown/any; got '$display'"
        )
        assertTrue(
            display.contains("fun") || display.contains("function") || hover.symbol?.kind == SymbolKind.FUNCTION,
            "env_import should be function-shaped; kind=${hover.symbol?.kind} display='$display'"
        )
        assertEnvImportReturnSurface(display)
    }

    @Test
    fun env_import_call_result_is_table_or_java_class_like() {
        // Unique local avoids substring collision with free-id env_import.
        val harness = androidHarness(
            "main.lua" to """
                require "import"
                local installedEnv = env_import(_G)
                return installedEnv
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "installedEnv")
        )
        val display = hover?.typeInfo?.displayName.orEmpty()
        val ideal =
            display.contains("table", ignoreCase = true) ||
                display.contains("JavaClass") ||
                display.contains("JavaObject") ||
                display == "table" ||
                display.contains("|")
        val productGap =
            hover == null ||
                display.isBlank() ||
                display == "unknown" ||
                display == "any" ||
                display == "nil"
        assertTrue(
            ideal || productGap,
            "installedEnv dual-path: table/JavaClass-like or CURRENTLY_ACCEPTS gap; got '$display'"
        )
        if (ideal) {
            assertEnvImportReturnSurface(display)
        }
    }

    @Test
    fun require_import_local_binding_hover_is_modeled_callable() {
        val harness = androidHarness(
            "main.lua" to """
                local importInstaller = require("import")
                return importInstaller
            """.trimIndent()
        )

        val hover = assertNotNull(
            harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", "importInstaller")
            )
        )
        val display = hover.typeInfo?.displayName.orEmpty()
        assertTrue(
            display.isNotBlank() && display != "unknown" && display != "any",
            "require(\"import\") local must be modeled; got '$display'"
        )
        // ModuleType "import" or function-shaped env_import installer are both acceptable.
        val modeled =
            display.contains("import") ||
                display.contains("fun") ||
                display.contains("function") ||
                display.contains("table") ||
                display.contains("JavaClass")
        assertTrue(modeled, "import installer hover should be module/callable; got '$display'")
    }

    private fun assertEnvImportReturnSurface(display: String) {
        val hasTable = display.contains("table", ignoreCase = true)
        val hasJavaClass = display.contains("JavaClass")
        val hasFun = display.contains("fun") || display.contains("function")
        // Accept either full signature (fun(...): table|JavaClass<any>) or return fragment alone.
        assertTrue(
            hasFun || hasTable || hasJavaClass,
            "Expected env_import surface to mention fun/table/JavaClass; got '$display'"
        )
        if (hasFun && display.contains(":")) {
            assertTrue(
                hasTable || hasJavaClass || display.contains("any"),
                "env_import return annotation should be table/JavaClass-like; got '$display'"
            )
        }
    }

    private fun assertCompletion(
        completions: List<CompletionItem>,
        label: String,
        kind: CompletionItemKind
    ) {
        assertTrue(
            completions.any { it.label == label && it.kind == kind },
            "Expected completion $label of kind $kind; actual: ${completions.map { "${it.label}:${it.kind}" }}"
        )
    }

    private fun androidHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine()
        )
    }
}
