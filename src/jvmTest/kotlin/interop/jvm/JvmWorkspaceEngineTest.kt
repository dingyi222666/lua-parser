package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import org.junit.Assume
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.io.File
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

    /**
     * Soft-skip nested AndroLua View$OnClickListener hard-lock when no host android.jar is
     * discoverable. Never hard-requires a missing Windows AppData android-35 path alone,
     * never G:/. Map$Entry alias assertions stay above this guard (JDK-only).
     */
    private fun requireHostAndroidJarOrSoftSkip(androidJar: File) {
        if (!androidJar.isFile || androidJar.length() <= 0L) {
            Assume.assumeTrue(missingHostAndroidJarSoftSkipReason(androidJar), false)
        }
    }

    private fun missingHostAndroidJarSoftSkipReason(missing: File): String {
        val productReason = JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(taskId = "TASK-570")
        return "TASK-570 soft-skip: android.jar not found at ${missing.path}. $productReason " +
            "Install Android SDK Platform 35 under ANDROID_HOME / ANDROID_SDK_ROOT / " +
            "%LOCALAPPDATA%/Android/Sdk (Windows) or ~/Library/Android/sdk (macOS) / ~/Android/Sdk " +
            "(Linux), or set jvm.androidJar. Never invent presence; never hard-require a missing " +
            "AppData android-35 path or G:/Android/Sdk alone; never require macOS-only " +
            "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar on Windows CI."
    }

    /**
     * Host-local android.jar for nested AndroLua alias hard-locks (TASK-570 dual-path).
     *
     * Order:
     * 1) [JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath]
     * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH]
     * 3) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34
     * 4) well-known macOS / Windows LOCALAPPDATA / Linux SDK layouts
     * 5) documented mac host path when present
     *
     * Never hardcodes or hard-requires Windows-only G:/Android/Sdk. When all candidates are
     * missing, returns the preferred messaging candidate (may be absent); callers soft-skip
     * via [requireHostAndroidJarOrSoftSkip].
     */
    private fun resolveHostAndroidJar(): File {
        val candidates = linkedSetOf<File>()
        JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()
            ?.let { candidates += File(it) }
        candidates += File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

        sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
            .mapNotNull { env -> System.getenv(env)?.trim()?.takeIf(String::isNotEmpty) }
            .forEach { sdkRoot ->
                candidates += File(sdkRoot, "platforms/android-35/android.jar")
                candidates += File(sdkRoot, "platforms/android-34/android.jar")
            }

        val home = System.getProperty("user.home").orEmpty()
        val localAppData = System.getenv("LOCALAPPDATA")?.trim()?.takeIf(String::isNotEmpty)
        if (!localAppData.isNullOrBlank()) {
            candidates += File(localAppData, "Android/Sdk/platforms/android-35/android.jar")
            candidates += File(localAppData, "Android/Sdk/platforms/android-34/android.jar")
        }
        if (home.isNotBlank()) {
            candidates += File(home, "AppData/Local/Android/Sdk/platforms/android-35/android.jar")
            candidates += File(home, "AppData/Local/Android/Sdk/platforms/android-34/android.jar")
            candidates += File(home, "Library/Android/sdk/platforms/android-35/android.jar")
            candidates += File(home, "Library/Android/sdk/platforms/android-34/android.jar")
            candidates += File(home, "Android/Sdk/platforms/android-35/android.jar")
            candidates += File(home, "Android/sdk/platforms/android-35/android.jar")
        }
        // Documented mac host path (present on WAVE agents; never required on Windows).
        candidates += File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar")

        val resolved = candidates.firstOrNull { candidate ->
            candidate.isFile &&
                !candidate.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true)
        } ?: candidates.first()

        val normalized = resolved.path.replace('\\', '/')
        if (normalized.startsWith("G:/Android/Sdk", ignoreCase = true)) {
            val nonG = candidates.firstOrNull {
                it.isFile && !it.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true)
            }
            if (nonG != null) {
                return nonG
            }
        }
        return resolved
    }
}
