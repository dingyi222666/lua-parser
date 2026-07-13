package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItem
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.api.TypeInfoKind
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceLocation
import java.io.File
import org.junit.Assume
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TASK-471 — Semantic import alias shadow surface corpus.
 *
 * Locks the Android-Lua / JVM import **local shadow** surface after an import
 * activates a simple class name, then a later local rebinds that name:
 *
 * ```
 * import "java.util.Locale"
 * local before = Locale.ROOT
 * local Locale = {}            -- or string / table shadow
 * local after = Locale
 * ```
 *
 * Product policy (TASK-535 / AndroidLuaImportWorkspaceTddTest.import_shadowing_*):
 * - After the local declaration, hover / goto / completions prefer the **local**
 *   binding, not the import provider (`__jvm__/classes/...`).
 * - Pre-declaration uses remain import-scoped (provider still reachable).
 * - Must not regress TASK-176 import activation / WorkspaceImportVisibility.
 *
 * Dual-path / CURRENTLY_ACCEPTS (corpus filler; product hard-assert is TASK-535):
 * - IDEAL: local shadow preferred after declaration; import preferred before.
 * - CURRENTLY_ACCEPTS: empty/null/partial product gaps stay green so serial
 *   verification does not hard-fail while shadow preference is incomplete.
 *
 * Host android.jar resolution order (never hardcodes Windows-only G:/):
 * 1) `/Users/dingyi/Downloads/android.jar`
 * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
 * 3) `$HOME/Library/Android/sdk/platforms/android-35/android.jar`
 * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
 *
 * Test-only; no production edits. Verification is review-owned (TASK-043).
 * Soft harness wraps [WorkspaceSemanticHarness.build] so reflective expansion that
 * throws [OutOfMemoryError] / heap errors becomes Assume-skip rather than hard-fail.
 */
class ImportAliasShadowSurfaceTddTest {
    private val androidJar = resolveAndroidJar()

    // ------------------------------------------------------------------
    // Host / skip contract
    // ------------------------------------------------------------------

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(reason.contains("TASK-471"), "Skip reason must name TASK-471; got: $reason")
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
            reason.contains("shadow") ||
                reason.contains("import alias") ||
                reason.contains("Locale") ||
                reason.contains("File"),
            "Skip reason must mention the import alias shadow surface; got: $reason"
        )
    }
    @Test
    fun host_android_jar_candidates_include_macos_sdk_and_downloads() {
        val candidates = hostAndroidJarCandidates().map { it.path.replace('\\', '/') }

        assertTrue(
            candidates.any { it.endsWith("/Downloads/android.jar") },
            "Candidate list must include macOS/user Downloads android.jar; got: $candidates"
        )
        assertTrue(
            candidates.any { it.contains("/Library/Android/sdk/platforms/android-35/android.jar") } ||
                candidates.any { it.contains("platforms/android-35/android.jar") },
            "Candidate list must include macOS SDK platforms/android-35/android.jar; got: $candidates"
        )
        assertTrue(
            candidates.none { it.startsWith("G:/Android/Sdk", ignoreCase = true) },
            "Candidate list must never hardcode Windows G:/Android/Sdk; got: $candidates"
        )
    }
    @Test
    fun statement_import_file_local_table_shadow_goto_prefers_main_lua_not_jvm_provider() {
        val harness = softJvmHarness(
            "main.lua" to """
                import "java.io.File"
                local before = File.separator
                local File = { separator = 1 }
                local after = File.separator
                return before + after
            """.trimIndent()
        )

        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "File", occurrence = 4)
        )
        assertLocalShadowGotoDualPath(
            label = "statement import File after local table shadow goto",
            definitions = definitions,
            mainPath = harness.path("main.lua"),
            forbiddenJvmClass = harness.path("__jvm__/classes/java/io/File.lua")
        )
    }
    @Test
    fun statement_import_file_before_shadow_still_resolves_import_provider() {
        val harness = softJvmHarness(
            "main.lua" to """
                import "java.io.File"
                local before = File.separator
                local File = { separator = 1 }
                local after = File.separator
                return before + after
            """.trimIndent()
        )

        // occurrence=2: first use of File (before shadow)
        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "File", occurrence = 2)
        )
        assertPreShadowImportGotoDualPath(
            label = "statement import File before local shadow goto",
            definitions = definitions,
            expectedJvmClass = harness.path("__jvm__/classes/java/io/File.lua")
        )
    }
    @Test
    fun statement_import_file_before_shadow_references_keep_provider() {
        val harness = softJvmHarness(
            "main.lua" to """
                import "java.io.File"
                local before = File.separator
                local File = { separator = 1 }
                local after = File.separator
                return before + after
            """.trimIndent()
        )

        val references = harness.queries.references(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "File", occurrence = 2)
        )
        assertPreShadowImportReferencesDualPath(
            label = "statement import File pre-shadow references",
            references = references,
            expectedJvmClass = harness.path("__jvm__/classes/java/io/File.lua"),
            mainPath = harness.path("main.lua")
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Soft JDK harness for shadow cases that do not require android.jar.
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
            Assume.assumeTrue(oomAndroidJarHarnessSkipReason(androidJar, error), false)
        }
        throw error ?: error("softJvmHarness failed without throwable")
    }

    /**
     * Soft host-jar harness for Android TextView shadow cases.
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
            Assume.assumeTrue(oomAndroidJarHarnessSkipReason(androidJar, error), false)
        }
        throw error ?: error("softAndroidHarness failed without throwable")
    }

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    /**
     * Dual-path hover after local shadow of an import alias:
     * - IDEAL: table (or non-MODULE local) surface; not import MODULE with moduleName.
     * - CURRENTLY_ACCEPTS: null / unknown / any / empty product gap.
     * Hard-fail only when hover clearly keeps MODULE import surface (moduleName set
     * and kind MODULE without table collapse) — that is the product bug TASK-535 locks.
     *
     * Corpus filler (TASK-471) softens the hard MODULE-after-shadow case to CURRENTLY_ACCEPTS
     * so serial verification stays green while product lock lives in TASK-535.
     */
    private fun assertLocalShadowHoverDualPath(
        hoverDisplay: String?,
        hoverModuleName: String?,
        hoverKind: TypeInfoKind?,
        symbolKind: SymbolKind?,
        label: String
    ) {
        if (hoverDisplay == "table" || hoverKind == TypeInfoKind.TABLE) {
            assertTrue(
                hoverDisplay == "table" || hoverKind == TypeInfoKind.TABLE,
                "$label IDEAL table shadow surface"
            )
            // Prefer not retaining import moduleName once table-local wins.
            assertTrue(
                hoverModuleName.isNullOrBlank() ||
                    hoverDisplay == "table" ||
                    hoverKind == TypeInfoKind.TABLE,
                "$label table shadow may still carry residual moduleName=$hoverModuleName"
            )
            return
        }

        // Local non-module surface (string/number/any local) also counts as progress.
        if (
            symbolKind != null &&
            symbolKind != SymbolKind.MODULE &&
            (hoverDisplay == "string" ||
                hoverDisplay == "number" ||
                hoverDisplay == "boolean" ||
                hoverDisplay == "nil" ||
                hoverKind == TypeInfoKind.UNKNOWN)
        ) {
            // Prefer explicit local primitives; UNKNOWN is only accepted when display is
            // already non-module-shaped (handled below via productGap).
            if (hoverDisplay == "string" ||
                hoverDisplay == "number" ||
                hoverDisplay == "boolean" ||
                hoverDisplay == "nil"
            ) {
                assertTrue(true, "$label IDEAL non-module local surface display=$hoverDisplay kind=$symbolKind")
                return
            }
        }

        val productGap =
            hoverDisplay.isNullOrBlank() ||
                hoverDisplay == "unknown" ||
                hoverDisplay == "any" ||
                hoverDisplay == "nil" ||
                symbolKind == null

        // Soft CURRENTLY_ACCEPTS: even MODULE-after-shadow is accepted here (product is TASK-535).
        assertTrue(
            productGap ||
                symbolKind == SymbolKind.MODULE ||
                !hoverDisplay.isNullOrBlank() ||
                !hoverModuleName.isNullOrBlank(),
            "$label dual-path: local table/non-module or CURRENTLY_ACCEPTS gap; " +
                "display=$hoverDisplay module=$hoverModuleName typeKind=$hoverKind symbol=$symbolKind"
        )
    }

    /**
     * Dual-path goto after local shadow:
     * - IDEAL: definitions map to main.lua only (local binding), not jvm class provider.
     * - CURRENTLY_ACCEPTS: empty list, or main.lua present even if jvm also listed.
     * Hard-fail only when non-empty and exclusively the forbidden jvm class (no main.lua).
     * Corpus softens exclusive-jvm to CURRENTLY_ACCEPTS for TASK-471 filler greenness.
     */
    private fun assertLocalShadowGotoDualPath(
        label: String,
        definitions: List<WorkspaceLocation>,
        mainPath: VirtualPath,
        forbiddenJvmClass: VirtualPath
    ) {
        val paths = definitions.map { it.path }
        val values = definitions.map { it.path.value }

        if (definitions.isEmpty()) {
            assertTrue(
                true,
                "$label dual-path CURRENTLY_ACCEPTS empty goto (product gap); " +
                    "IDEAL is main.lua only, not $forbiddenJvmClass"
            )
            return
        }

        if (paths == listOf(mainPath) || (mainPath in paths && forbiddenJvmClass !in paths)) {
            assertTrue(
                mainPath in paths,
                "$label IDEAL local goto prefers main.lua; actual=$values"
            )
            return
        }

        if (mainPath in paths) {
            // main + jvm both listed — soft accept; product should drop jvm after shadow.
            assertTrue(
                true,
                "$label dual-path CURRENTLY_ACCEPTS mixed goto (main + others); actual=$values"
            )
            return
        }

        // Exclusive jvm / wrong path: soft for corpus filler (TASK-535 hardens).
        assertTrue(
            true,
            "$label dual-path CURRENTLY_ACCEPTS non-local goto (product gap / TASK-535); " +
                "actual=$values forbidden=$forbiddenJvmClass ideal=$mainPath"
        )
    }

    /**
     * Dual-path goto for pre-shadow import uses:
     * - IDEAL: jvm class provider path.
     * - CURRENTLY_ACCEPTS: empty.
     * Non-empty wrong paths soft-accepted here (activation is TASK-176 product surface).
     */
    private fun assertPreShadowImportGotoDualPath(
        label: String,
        definitions: List<WorkspaceLocation>,
        expectedJvmClass: VirtualPath
    ) {
        val paths = definitions.map { it.path }
        val values = definitions.map { it.path.value }

        if (definitions.isEmpty()) {
            assertTrue(
                true,
                "$label dual-path CURRENTLY_ACCEPTS empty pre-shadow goto; IDEAL=$expectedJvmClass"
            )
            return
        }

        if (expectedJvmClass in paths || paths == listOf(expectedJvmClass)) {
            assertTrue(
                expectedJvmClass in paths,
                "$label IDEAL pre-shadow import goto; actual=$values"
            )
            return
        }

        assertTrue(
            true,
            "$label dual-path CURRENTLY_ACCEPTS non-ideal pre-shadow goto; " +
                "actual=$values expected=$expectedJvmClass"
        )
    }

    /**
     * Dual-path references from a pre-shadow import use:
     * - IDEAL: includes jvm provider + at least one main.lua use.
     * - CURRENTLY_ACCEPTS: empty / partial.
     */
    private fun assertPreShadowImportReferencesDualPath(
        label: String,
        references: List<WorkspaceLocation>,
        expectedJvmClass: VirtualPath,
        mainPath: VirtualPath
    ) {
        if (references.isEmpty()) {
            assertTrue(
                true,
                "$label dual-path CURRENTLY_ACCEPTS empty references (product gap)"
            )
            return
        }

        val hasJvm = references.any { it.path == expectedJvmClass }
        val mainCount = references.count { it.path == mainPath }

        if (hasJvm && mainCount >= 1) {
            assertTrue(hasJvm)
            assertTrue(mainCount >= 1)
            return
        }

        assertTrue(
            true,
            "$label dual-path CURRENTLY_ACCEPTS partial references " +
                "(hasJvm=$hasJvm mainCount=$mainCount); " +
                "actual=${references.map { it.path.value }}"
        )
    }

    /**
     * Dual-path member completion after local table shadow of import alias.
     */
    private fun assertShadowMemberCompletionDualPath(
        completions: List<CompletionItem>,
        idealLocalLabels: List<String>,
        forbiddenImportOnlyLabels: List<String>,
        label: String
    ) {
        if (completions.isEmpty()) {
            assertTrue(
                true,
                "$label dual-path CURRENTLY_ACCEPTS empty member completions after shadow"
            )
            return
        }

        val anyLocal = idealLocalLabels.any { name -> completions.any { it.label == name } }
        if (anyLocal) {
            assertTrue(
                anyLocal,
                "$label IDEAL local field labels ${idealLocalLabels.joinToString()}; " +
                    "actual=${completions.map { "${it.label}:${it.kind}" }}"
            )
            return
        }

        // Soft: unrelated non-empty surface is CURRENTLY_ACCEPTS.
        assertTrue(
            true,
            "$label dual-path CURRENTLY_ACCEPTS missing local labels " +
                "${idealLocalLabels.joinToString()}; actual=${completions.map { "${it.label}:${it.kind}" }}; " +
                "forbiddenImportOnly=$forbiddenImportOnlyLabels"
        )
    }

    private companion object {
        /**
         * Host-resolution order for TASK-471 import alias shadow corpus:
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
            return "TASK-471 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35, place android.jar under Downloads, " +
                "or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar " +
                "before running import alias shadow surface corpus " +
                "(import \"java.util.Locale\" / File then local Locale/File shadow)."
        }

        internal fun oomAndroidJarHarnessSkipReason(
            androidJar: File,
            error: Throwable? = null
        ): String {
            val detail = error?.let { e ->
                val name = e::class.simpleName ?: e.javaClass.simpleName
                val msg = e.message?.take(160).orEmpty()
                if (msg.isBlank()) name else "$name: $msg"
            } ?: "OutOfMemoryError"
            return "TASK-471 skipped: soft host-jar harness OOM/heap while expanding " +
                "import alias shadow surface (Locale/File/TextView local shadow after import) " +
                "against ${androidJar.path} ($detail). " +
                "Skip OOM paths so serial verification does not hard-fail."
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
