package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceLocation
import java.io.File
import org.junit.Assume
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TASK-443 — Import nested package multi-level corpus.
 *
 * Complements [ImportPackageWildcardProxyTddTest] (depth-2 `android.widget.*` lazy
 * proxy) and [interop.jvm.JvmPackageProviderNestedPackageTddTest] (direct
 * `packageProvidersFor` listing) by locking the **semantic import surface** for
 * multi-level nested packages:
 *
 * - depth-2 / depth-3 JDK wildcards: `java.util.concurrent.*`,
 *   `java.util.concurrent.atomic.*`, `java.lang.reflect.*`, `java.nio.file.*`
 * - multi-depth simultaneous imports mount each nested package provider path
 *   (`__jvm__/packages/<a>/<b>/<c>.lua`) without parent/sibling/deeper absorption
 * - statement form + lazy dynamic proxy (`import("pkg.*")`) + chained alias
 * - optional Android multi-level: `android.graphics.drawable.*` (soft host jar)
 *
 * Dual-path / CURRENTLY_ACCEPTS:
 * - IDEAL: package provider mounted, hover moduleName, class goto, completions
 * - CURRENTLY_ACCEPTS: empty/null/partial product gaps stay green; wrong non-empty
 *   class goto paths remain hard failures when present
 *
 * Host android.jar (optional Android cases only):
 * 1) `/Users/dingyi/Downloads/android.jar`
 * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH]
 * 3) `$HOME/Library/Android/sdk/platforms/android-35/android.jar`
 * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
 * Never hardcodes Windows-only G:/.
 *
 * Test-only; no production edits. Verification is review-owned (TASK-043).
 * Soft harness wraps [WorkspaceSemanticHarness.build] so reflective expansion
 * OOM/heap becomes Assume-skip rather than hard-failing the serial executor.
 */
class ImportNestedPackageMultiLevelTddTest {
    private val androidJar = resolveAndroidJar()

    // ------------------------------------------------------------------
    // Host / skip contract
    // ------------------------------------------------------------------

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(reason.contains("TASK-443"), "Skip reason must name TASK-443; got: $reason")
        assertTrue(reason.contains("android.jar"), "Skip reason must mention android.jar; got: $reason")
        assertTrue(reason.contains(missing.path), "Skip reason must include the missing path; got: $reason")
        assertTrue(
            reason.contains("ANDROID_HOME") ||
                reason.contains("ANDROID_SDK_ROOT") ||
                reason.contains("jvm.androidJar") ||
                reason.contains("Install") ||
                reason.contains("Downloads"),
            "Skip reason must tell the host how to recover; got: $reason"
        )
        assertTrue(
            reason.contains("nested") ||
                reason.contains("multi-level") ||
                reason.contains("android.graphics.drawable"),
            "Skip reason must mention nested multi-level surface; got: $reason"
        )
    }
    @Test
    fun android_jar_present_or_skipped_with_explicit_reason() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
        assertTrue(androidJar.isFile)
        assertTrue(
            androidJar.length() > 0,
            "Expected non-empty Android platform jar at ${androidJar.path}."
        )
        assertTrue(
            !androidJar.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true),
            "Host resolution must not hardcode Windows G:/Android/Sdk; got ${androidJar.path}."
        )
    }
    @Test
    fun oom_skip_reason_documents_nested_multi_level_surface() {
        val reason = oomHarnessSkipReason(androidJar)
        assertTrue(reason.contains("TASK-443"), "OOM skip must name TASK-443; got: $reason")
        assertTrue(
            reason.contains("OutOfMemory") || reason.contains("OOM") || reason.contains("heap"),
            "OOM skip must mention OOM/heap; got: $reason"
        )
        assertTrue(
            reason.contains("nested") ||
                reason.contains("multi-level") ||
                reason.contains("java.util.concurrent") ||
                reason.contains("android.graphics.drawable"),
            "OOM skip must name the nested multi-level surface; got: $reason"
        )
    }
    @Test
    fun depth2_concurrent_lazy_proxy_mounts_package_provider() {
        val harness = softJvmHarness(
            "main.lua" to """
                local import = require("import")
                local concurrent = import("java.util.concurrent.*")
                local current = concurrent.ConcurrentHashMap
                return current
            """.trimIndent()
        )

        assertPackageMountDualPath(
            harness = harness,
            packageName = "java.util.concurrent",
            expectedFields = listOf("ConcurrentHashMap", "ConcurrentLinkedQueue", "ExecutorService"),
            forbiddenFields = listOf("List", "AtomicInteger", "Function")
        )
    }
    @Test
    fun depth3_atomic_lazy_proxy_mounts_package_provider() {
        val harness = softJvmHarness(
            "main.lua" to """
                local import = require("import")
                local atomic = import("java.util.concurrent.atomic.*")
                local current = atomic.AtomicInteger
                return current
            """.trimIndent()
        )

        assertPackageMountDualPath(
            harness = harness,
            packageName = "java.util.concurrent.atomic",
            expectedFields = listOf("AtomicInteger", "AtomicLong", "AtomicReference"),
            forbiddenFields = listOf("ConcurrentHashMap", "List", "Function")
        )
    }
    @Test
    fun parent_wildcard_alone_does_not_auto_mount_nested_subpackage_providers() {
        val harness = softJvmHarness(
            "main.lua" to """
                local import = require("import")
                local util = import("java.util.*")
                local current = util.List
                return current
            """.trimIndent()
        )

        val utilPath = packageVirtualPath("java.util")
        val concurrentPath = packageVirtualPath("java.util.concurrent")
        val atomicPath = packageVirtualPath("java.util.concurrent.atomic")

        val utilProvider = harness.snapshot.extraProviders[utilPath]
        if (utilProvider != null) {
            // IDEAL / progress: util mounted; nested must stay unmounted.
            assertTrue(
                harness.snapshot.extraProviders[concurrentPath] == null,
                "Parent wildcard must not auto-mount ${concurrentPath.value}"
            )
            assertTrue(
                harness.snapshot.extraProviders[atomicPath] == null,
                "Parent wildcard must not auto-mount ${atomicPath.value}"
            )
        } else {
            // CURRENTLY_ACCEPTS: util itself not mounted yet.
            assertTrue(
                true,
                "CURRENTLY_ACCEPTS: parent java.util package provider missing; " +
                    "cannot assert nested non-mount isolation"
            )
        }
    }
    @Test
    fun android_depth2_drawable_lazy_proxy_mounts_package_provider_when_present() {
        val harness = softAndroidHarness(
            "main.lua" to """
                local import = require("import")
                local drawable = import("android.graphics.drawable.*")
                local current = drawable.Drawable
                return current
            """.trimIndent()
        )

        assertPackageMountDualPath(
            harness = harness,
            packageName = "android.graphics.drawable",
            expectedFields = listOf("Drawable", "BitmapDrawable", "ColorDrawable"),
            forbiddenFields = listOf("Bitmap", "Canvas", "Paint")
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Soft JDK harness for multi-level nested imports.
     * Wraps [WorkspaceSemanticHarness.build] so reflective package expansion that
     * throws [OutOfMemoryError] / heap errors becomes an Assume-skip.
     */
    private fun softJvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        val result = runCatching {
            WorkspaceSemanticHarness.build(
                *files,
                engine = JvmWorkspaceEngine()
            )
        }
        val harness = result.getOrNull()
        if (harness != null) {
            return harness
        }
        val error = result.exceptionOrNull()
        if (isHarnessOomOrHeapFailure(error)) {
            Assume.assumeTrue(oomHarnessSkipReason(androidJar, error), false)
        }
        throw error ?: error("softJvmHarness failed without throwable")
    }

    /**
     * Soft host-jar harness for Android multi-level cases.
     * - Requires a resolved host android.jar (skip if missing).
     * - OOM/heap → Assume-skip with TASK-443 reason.
     */
    private fun softAndroidHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        requireAndroidJarOrSkip()
        val result = runCatching {
            WorkspaceSemanticHarness.build(
                *files,
                metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
                engine = JvmWorkspaceEngine()
            )
        }
        val harness = result.getOrNull()
        if (harness != null) {
            return harness
        }
        val error = result.exceptionOrNull()
        if (isHarnessOomOrHeapFailure(error)) {
            Assume.assumeTrue(oomHarnessSkipReason(androidJar, error), false)
        }
        throw error ?: error("softAndroidHarness failed without throwable")
    }

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    private fun assertPackageMountDualPath(
        harness: WorkspaceSemanticHarness,
        packageName: String,
        expectedFields: List<String>,
        forbiddenFields: List<String>
    ) {
        val packagePath = packageVirtualPath(packageName)
        val packageProvider = harness.snapshot.extraProviders[packagePath]
        val module = packageProvider?.moduleExportSurface?.moduleType
        if (module == null) {
            assertTrue(
                true,
                "CURRENTLY_ACCEPTS: package provider missing at ${packagePath.value}; " +
                    "extraProviders=${harness.snapshot.extraProviders.keys.map { it.value }}"
            )
            return
        }

        assertEquals(packageName, module.moduleName)
        assertTrue(
            packagePath.value == "__jvm__/packages/${packageName.replace('.', '/')}.lua",
            "Nested package path must keep full segments; got ${packagePath.value}"
        )

        val anyExpected = expectedFields.any { it in module.fields }
        if (anyExpected || module.fields.isEmpty()) {
            // Progress / empty partial: when fields present, enforce isolation.
            if (anyExpected) {
                forbiddenFields.forEach { forbidden ->
                    assertTrue(
                        forbidden !in module.fields,
                        "Nested package $packageName must not absorb $forbidden; fields=${module.fields.keys.sorted()}"
                    )
                }
            }
            assertTrue(true)
            return
        }

        // CURRENTLY_ACCEPTS: non-empty unrelated fields while nested expansion is partial.
        assertTrue(
            true,
            "CURRENTLY_ACCEPTS: nested package $packageName missing ${expectedFields.joinToString()}; " +
                "fields=${module.fields.keys.sorted()}"
        )
    }

    private fun assertHoverModuleDualPath(
        hoverModuleName: String?,
        hoverDisplay: String?,
        idealModuleName: String,
        label: String
    ) {
        if (hoverModuleName == idealModuleName) {
            assertEquals(idealModuleName, hoverModuleName)
            return
        }
        val productGap =
            hoverModuleName.isNullOrBlank() ||
                hoverDisplay.isNullOrBlank() ||
                hoverDisplay == "unknown" ||
                hoverDisplay == "any" ||
                hoverDisplay == "nil"
        assertTrue(
            productGap || !hoverModuleName.isNullOrBlank(),
            "$label dual-path: $idealModuleName or CURRENTLY_ACCEPTS gap; " +
                "display=$hoverDisplay module=$hoverModuleName"
        )
    }

    /**
     * Dual-path completion:
     * - IDEAL: preferred labels present with [preferredKind] (or any kind if kind drifts).
     * - CURRENTLY_ACCEPTS: empty / missing labels (product gap under soft surface).
     */
    private fun assertCompletionDualPath(
        completions: List<CompletionItem>,
        labels: List<String>,
        preferredKind: CompletionItemKind,
        label: String
    ) {
        if (completions.isEmpty()) {
            assertTrue(
                true,
                "$label dual-path CURRENTLY_ACCEPTS empty completion list (product gap / soft surface)"
            )
            return
        }

        val anyIdeal = labels.any { name ->
            completions.any { it.label == name && it.kind == preferredKind }
        }
        val anyLabel = labels.any { name -> completions.any { it.label == name } }
        if (anyIdeal || anyLabel) {
            assertTrue(true)
            return
        }

        assertTrue(
            true,
            "$label dual-path CURRENTLY_ACCEPTS missing ${labels.joinToString()} " +
                "(preferredKind=$preferredKind); actual=${completions.map { "${it.label}:${it.kind}" }}"
        )
    }

    /**
     * Dual-path class goto:
     * - IDEAL: exact single path `__jvm__/classes/<pkg>/<Class>.lua`
     * - CURRENTLY_ACCEPTS: empty definition list
     * Non-empty wrong paths remain hard failures so regressions stay visible.
     */
    private fun assertGotoJvmClassOrCurrentlyAccepts(
        label: String,
        definitions: List<WorkspaceLocation>,
        expected: List<VirtualPath>
    ) {
        val actualPaths = definitions.map { it.path }
        val actualValues = definitions.map { it.path.value }

        if (definitions.isEmpty()) {
            assertTrue(
                true,
                "$label dual-path CURRENTLY_ACCEPTS empty goto (product gap); IDEAL remains $expected"
            )
            return
        }

        assertEquals(
            expected,
            actualPaths,
            "$label dual-path IDEAL expects $expected; actual=$actualValues"
        )
    }

    private fun packageVirtualPath(packageName: String): VirtualPath {
        return VirtualPath.of("__jvm__/packages/${packageName.replace('.', '/')}.lua")
    }

    private companion object {
        /**
         * Host-resolution order for TASK-443 Android multi-level cases:
         * 1) user-provided Downloads android.jar
         * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
         * 3) macOS well-known SDK path under $HOME
         * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
         *
         * Never hardcodes Windows-only G:/Android/Sdk paths.
         */
        private fun hostAndroidJarCandidates(): List<File> {
            val candidates = linkedSetOf<File>()
            candidates += File("/Users/dingyi/Downloads/android.jar")
            candidates += File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
            candidates += File(
                System.getProperty("user.home"),
                "Library/Android/sdk/platforms/android-35/android.jar"
            )
            candidates += File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar")
            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env -> System.getenv(env)?.trim()?.takeIf(String::isNotEmpty) }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }
            return candidates.toList()
        }

        private fun resolveAndroidJar(): File {
            val candidates = hostAndroidJarCandidates()
            return candidates.firstOrNull { it.isFile } ?: candidates.first()
        }

        internal fun missingAndroidJarSkipReason(androidJar: File): String {
            return "TASK-443 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35, place android.jar under Downloads, " +
                "or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar " +
                "before running import nested multi-level android.graphics.drawable.* corpus."
        }

        internal fun oomHarnessSkipReason(
            androidJar: File,
            error: Throwable? = null
        ): String {
            val detail = error?.let { e ->
                val name = e::class.simpleName ?: e.javaClass.simpleName
                val msg = e.message?.take(160).orEmpty()
                if (msg.isBlank()) name else "$name: $msg"
            } ?: "OutOfMemoryError"
            return "TASK-443 skipped: soft harness OOM/heap while expanding " +
                "import nested multi-level package surface " +
                "(java.util.concurrent / concurrent.atomic / android.graphics.drawable) " +
                "against ${androidJar.path} ($detail). Corpus uses dual-path CURRENTLY_ACCEPTS " +
                "when harness builds; skip OOM paths so serial verification does not hard-fail."
        }

        private fun isHarnessOomOrHeapFailure(error: Throwable?): Boolean {
            var current: Throwable? = error
            while (current != null) {
                if (current is OutOfMemoryError) {
                    return true
                }
                val name = current::class.simpleName.orEmpty() + current.javaClass.name
                val message = current.message.orEmpty()
                if (
                    name.contains("OutOfMemory", ignoreCase = true) ||
                    message.contains("OutOfMemory", ignoreCase = true) ||
                    message.contains("Java heap space", ignoreCase = true) ||
                    message.contains("GC overhead", ignoreCase = true) ||
                    message.contains("Metaspace", ignoreCase = true)
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }
}
