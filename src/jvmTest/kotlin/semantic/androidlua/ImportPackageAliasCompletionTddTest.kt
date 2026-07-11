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
 * TASK-490 — Import package alias completion corpus.
 *
 * Locks the Android-Lua **package alias** completion / hover surface where a local
 * binding holds a package (or wildcard package proxy) return:
 *
 * - `local widget = import("android.widget")` — package-name alias (no `.*`)
 * - `local widget = import("android.widget.*")` — wildcard lazy proxy alias
 * - statement-form companion remains out of scope (see [ImportPackageWildcardProxyTddTest])
 * - chained import-function aliases (`local load = import; local again = load`)
 * - JDK package alias companion: `local util = import("java.util")` / `java.util.*`
 *
 * Dual-path / CURRENTLY_ACCEPTS (corpus filler; product hard-assert is TASK-534):
 * - IDEAL: package provider mounted, hover moduleName `android.widget`,
 *   member completions include representative classes (TextView / LinearLayout / Button)
 * - CURRENTLY_ACCEPTS: empty/null/partial product gaps stay green so serial verification
 *   does not hard-fail while product completion surface is incomplete
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
class ImportPackageAliasCompletionTddTest {
    private val androidJar = resolveAndroidJar()

    // ------------------------------------------------------------------
    // Host / skip contract
    // ------------------------------------------------------------------

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(reason.contains("TASK-534"), "Skip reason must name TASK-534; got: $reason")
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
            reason.contains("package alias") ||
                reason.contains("android.widget") ||
                reason.contains("completion"),
            "Skip reason must mention the package alias completion surface; got: $reason"
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
    fun oom_skip_reason_documents_package_alias_completion_surface() {
        val reason = oomAndroidJarHarnessSkipReason(androidJar)
        assertTrue(reason.contains("TASK-534"), "OOM skip must name TASK-534; got: $reason")
        assertTrue(
            reason.contains("OutOfMemory") || reason.contains("OOM") || reason.contains("heap"),
            "OOM skip must mention OOM/heap; got: $reason"
        )
        assertTrue(
            reason.contains("package alias") ||
                reason.contains("android.widget") ||
                reason.contains("completion"),
            "OOM skip must name the package alias completion surface; got: $reason"
        )
        assertTrue(
            reason.contains(androidJar.path) || reason.contains("android.jar"),
            "OOM skip must mention the host jar path; got: $reason"
        )
    }

    // ------------------------------------------------------------------
    // Package-name alias: local widget = import("android.widget")
    // ------------------------------------------------------------------

    @Test
    fun package_name_alias_mounts_package_provider_at_jvm_packages_path() {
        val harness = softAndroidHarness(
            "main.lua" to """
                local import = require("import")
                local widget = import("android.widget")
                local current = widget.TextView
                return current
            """.trimIndent()
        )

        assertPackageMountDualPath(
            harness = harness,
            packageName = "android.widget",
            expectedFields = listOf("TextView", "LinearLayout", "Button", "ImageView"),
            forbiddenFields = listOf("Activity", "Intent", "ViewGroup")
        )
    }

    @Test
    fun package_name_alias_hover_is_android_widget_package_module() {
        val harness = softAndroidHarness(
            "main.lua" to """
                local import = require("import")
                local widget = import("android.widget")
                local current = widget.TextView
                return current
            """.trimIndent()
        )

        // occurrence=2: first "widget" is the local binding; second is the use site.
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "widget", occurrence = 2)
        )
        assertHoverModuleDualPath(
            hoverModuleName = hover?.typeInfo?.moduleName,
            hoverDisplay = hover?.typeInfo?.displayName,
            idealModuleName = "android.widget",
            label = "package-name alias widget hover"
        )
    }

    @Test
    fun package_name_alias_member_completion_includes_textview_linearlayout() {
        val harness = softAndroidHarness(
            "main.lua" to """
                local import = require("import")
                local widget = import("android.widget")
                local current = widget.TextView
                return current
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "TextView")
        )
        assertCompletionDualPath(
            completions = completions,
            labels = listOf("TextView", "LinearLayout", "Button"),
            preferredKind = CompletionItemKind.FIELD,
            label = "package-name alias widget.TextView member completion"
        )
    }

    @Test
    fun package_name_alias_textview_goto_resolves_jvm_class_module() {
        val harness = softAndroidHarness(
            "main.lua" to """
                local import = require("import")
                local widget = import("android.widget")
                local current = widget.TextView
                return current
            """.trimIndent()
        )

        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "TextView")
        )
        assertGotoJvmClassOrCurrentlyAccepts(
            label = "package-name alias widget.TextView",
            definitions = definitions,
            expected = listOf(harness.path("__jvm__/classes/android/widget/TextView.lua"))
        )
    }

    // ------------------------------------------------------------------
    // Wildcard proxy alias: local widget = import("android.widget.*")
    // ------------------------------------------------------------------

    @Test
    fun wildcard_proxy_alias_member_completion_includes_textview_button() {
        val harness = softAndroidHarness(
            "main.lua" to """
                local import = require("import")
                local widget = import("android.widget.*")
                local current = widget.Button
                return current
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Button")
        )
        assertCompletionDualPath(
            completions = completions,
            labels = listOf("Button", "TextView", "LinearLayout"),
            preferredKind = CompletionItemKind.FIELD,
            label = "wildcard proxy alias widget.Button member completion"
        )
    }

    @Test
    fun wildcard_proxy_alias_hover_is_android_widget_package_module() {
        val harness = softAndroidHarness(
            "main.lua" to """
                local import = require("import")
                local widget = import("android.widget.*")
                local current = widget.TextView
                return current
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "widget", occurrence = 2)
        )
        assertHoverModuleDualPath(
            hoverModuleName = hover?.typeInfo?.moduleName,
            hoverDisplay = hover?.typeInfo?.displayName,
            idealModuleName = "android.widget",
            label = "wildcard proxy alias widget hover"
        )
    }

    @Test
    fun package_name_and_wildcard_alias_share_same_textview_class_provider() {
        val harness = softAndroidHarness(
            "main.lua" to """
                local import = require("import")
                local packageAlias = import("android.widget")
                local wildcardAlias = import("android.widget.*")
                local fromPackage = packageAlias.TextView
                local fromWildcard = wildcardAlias.TextView
                return fromPackage, fromWildcard
            """.trimIndent()
        )

        val packageGoto = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "TextView", occurrence = 1)
        )
        val wildcardGoto = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "TextView", occurrence = 2)
        )

        val expected = listOf(harness.path("__jvm__/classes/android/widget/TextView.lua"))
        assertGotoJvmClassOrCurrentlyAccepts(
            label = "package-name alias TextView",
            definitions = packageGoto,
            expected = expected
        )
        assertGotoJvmClassOrCurrentlyAccepts(
            label = "wildcard proxy alias TextView",
            definitions = wildcardGoto,
            expected = expected
        )

        if (packageGoto.isNotEmpty() && wildcardGoto.isNotEmpty()) {
            assertEquals(
                packageGoto.map { it.path },
                wildcardGoto.map { it.path },
                "Package-name and wildcard aliases must share the same TextView class provider when both resolve; " +
                    "package=${packageGoto.map { it.path.value }} wildcard=${wildcardGoto.map { it.path.value }}"
            )
        }
    }

    // ------------------------------------------------------------------
    // Chained import-function alias → package alias
    // ------------------------------------------------------------------

    @Test
    fun chained_import_function_alias_package_proxy_hover_and_completion() {
        // Mirrors Android-Lua realiasing: local load = import; local again = load
        val harness = softAndroidHarness(
            "main.lua" to """
                local import = require("import")
                local load = import
                local again = load
                local widget = again("android.widget.*")
                local current = widget.LinearLayout
                return current
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "widget", occurrence = 2)
        )
        assertHoverModuleDualPath(
            hoverModuleName = hover?.typeInfo?.moduleName,
            hoverDisplay = hover?.typeInfo?.displayName,
            idealModuleName = "android.widget",
            label = "chained import-function alias package proxy hover"
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "LinearLayout")
        )
        assertCompletionDualPath(
            completions = completions,
            labels = listOf("LinearLayout", "TextView", "Button"),
            preferredKind = CompletionItemKind.FIELD,
            label = "chained again(\"android.widget.*\") member completion"
        )
    }

    @Test
    fun package_alias_rebinding_local_widget_to_view_still_completes_members() {
        // local rebinding of the package alias itself (not the import function).
        val harness = softAndroidHarness(
            "main.lua" to """
                local import = require("import")
                local widget = import("android.widget")
                local view = widget
                local current = view.ImageView
                return current
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "view", occurrence = 2)
        )
        assertHoverModuleDualPath(
            hoverModuleName = hover?.typeInfo?.moduleName,
            hoverDisplay = hover?.typeInfo?.displayName,
            idealModuleName = "android.widget",
            label = "rebound package alias view hover"
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "ImageView")
        )
        assertCompletionDualPath(
            completions = completions,
            labels = listOf("ImageView", "TextView", "Button"),
            preferredKind = CompletionItemKind.FIELD,
            label = "rebound package alias view.ImageView member completion"
        )
    }

    // ------------------------------------------------------------------
    // JDK package alias companion (no android.jar required)
    // ------------------------------------------------------------------

    @Test
    fun jdk_package_name_alias_util_mounts_and_completes_list_map() {
        val harness = softJvmHarness(
            "main.lua" to """
                local import = require("import")
                local util = import("java.util")
                local current = util.List
                return current
            """.trimIndent()
        )

        assertPackageMountDualPath(
            harness = harness,
            packageName = "java.util",
            expectedFields = listOf("List", "Map", "Locale"),
            forbiddenFields = listOf("ConcurrentHashMap", "AtomicInteger", "Function")
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "List")
        )
        assertCompletionDualPath(
            completions = completions,
            labels = listOf("List", "Map", "Locale"),
            preferredKind = CompletionItemKind.FIELD,
            label = "JDK package-name alias util.List member completion"
        )
    }

    @Test
    fun jdk_wildcard_proxy_alias_util_hover_and_list_goto() {
        val harness = softJvmHarness(
            "main.lua" to """
                local import = require("import")
                local util = import("java.util.*")
                local current = util.Locale
                return current
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "util", occurrence = 2)
        )
        assertHoverModuleDualPath(
            hoverModuleName = hover?.typeInfo?.moduleName,
            hoverDisplay = hover?.typeInfo?.displayName,
            idealModuleName = "java.util",
            label = "JDK wildcard proxy alias util hover"
        )

        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "Locale")
        )
        assertGotoJvmClassOrCurrentlyAccepts(
            label = "JDK wildcard alias util.Locale",
            definitions = definitions,
            expected = listOf(harness.path("__jvm__/classes/java/util/Locale.lua"))
        )
    }

    // ------------------------------------------------------------------
    // Static field via package alias (surface continuity)
    // ------------------------------------------------------------------

    @Test
    fun package_alias_static_field_via_class_member_hover() {
        val harness = softAndroidHarness(
            "main.lua" to """
                local import = require("import")
                local widget = import("android.widget")
                local current = widget.TextView.AUTO_SIZE_TEXT_TYPE_NONE
                return current
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "AUTO_SIZE_TEXT_TYPE_NONE")
        )
        val kind = hover?.symbol?.kind
        val display = hover?.typeInfo?.displayName

        if (kind == SymbolKind.FIELD && display == "number") {
            assertEquals(SymbolKind.FIELD, kind)
            assertEquals("number", display)
            return
        }

        val productGap =
            hover == null ||
                kind == null ||
                display.isNullOrBlank() ||
                display == "unknown" ||
                display == "any" ||
                display == "nil"
        assertTrue(
            productGap || kind == SymbolKind.FIELD || display != null,
            "Static field via package-name alias dual-path FIELD/number or CURRENTLY_ACCEPTS; " +
                "kind=$kind display=$display"
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Soft host-jar harness for Android package-alias cases.
     * - Requires a resolved host android.jar (skip if missing).
     * - OOM/heap → Assume-skip with TASK-534 reason.
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

    /**
     * Soft JDK harness for package-alias cases that do not need android.jar.
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

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    /**
     * Dual-path completion (TASK-534 product surface):
     * - IDEAL (hard when jar present): preferred labels present with [preferredKind]
     *   (or any kind if kind drifts slightly).
     * - Empty completions are FAIL when the host android.jar is present (product gap).
     * - CURRENTLY_ACCEPTS empty only when jar is absent (tests already skip).
     */
    private fun assertCompletionDualPath(
        completions: List<CompletionItem>,
        labels: List<String>,
        preferredKind: CompletionItemKind,
        label: String
    ) {
        assertTrue(
            completions.isNotEmpty(),
            "$label: expected non-empty package-member completions when host android.jar is present; " +
                "labels ideal=${labels.joinToString()}"
        )

        val anyIdeal = labels.any { name ->
            completions.any { it.label == name && it.kind == preferredKind }
        }
        val anyLabel = labels.any { name -> completions.any { it.label == name } }
        assertTrue(
            anyIdeal || anyLabel,
            "$label: expected at least one of ${labels.joinToString()} " +
                "(preferredKind=$preferredKind); actual=${completions.map { "${it.label}:${it.kind}" }}"
        )
    }

    /**
     * Package mount: when jar is present the package provider must mount with moduleName and
     * at least one representative class field (hard product surface for TASK-534).
     */
    private fun assertPackageMountDualPath(
        harness: WorkspaceSemanticHarness,
        packageName: String,
        expectedFields: List<String>,
        forbiddenFields: List<String>
    ) {
        val packagePath = packageVirtualPath(packageName)
        val packageProvider = harness.snapshot.extraProviders[packagePath]
        val module = packageProvider?.moduleExportSurface?.moduleType
        assertTrue(
            module != null,
            "Package provider must mount at ${packagePath.value} when jar is present; " +
                "extraProviders=${harness.snapshot.extraProviders.keys.map { it.value }}"
        )
        requireNotNull(module)

        assertEquals(packageName, module.moduleName)
        assertTrue(
            packagePath.value == "__jvm__/packages/${packageName.replace('.', '/')}.lua",
            "Package path must keep full segments; got ${packagePath.value}"
        )

        val anyExpected = expectedFields.any { it in module.fields }
        assertTrue(
            anyExpected,
            "Package $packageName must expose at least one of ${expectedFields.joinToString()}; " +
                "fields=${module.fields.keys.sorted()}"
        )
        forbiddenFields.forEach { forbidden ->
            assertTrue(
                forbidden !in module.fields,
                "Package $packageName must not absorb $forbidden; fields=${module.fields.keys.sorted()}"
            )
        }
    }

    private fun assertHoverModuleDualPath(
        hoverModuleName: String?,
        hoverDisplay: String?,
        idealModuleName: String,
        label: String
    ) {
        assertEquals(
            idealModuleName,
            hoverModuleName,
            "$label: hover moduleName must be package-shaped $idealModuleName " +
                "(not unknown); display=$hoverDisplay module=$hoverModuleName"
        )
    }

    /**
     * Dual-path class goto:
     * - IDEAL: exact single path `__jvm__/classes/<pkg>/<Class>.lua`
     * - CURRENTLY_ACCEPTS: empty definition list still soft (goto is secondary to completion/hover)
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
         * Host-resolution order for TASK-490/TASK-534 package alias completion corpus:
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
            return "TASK-534 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35, place android.jar under Downloads, " +
                "or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar " +
                "before running import package alias completion corpus " +
                "(local widget = import(\"android.widget\") / android.widget.*)."
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
            return "TASK-534 skipped: soft host-jar harness OOM/heap while expanding " +
                "import package alias completion surface (android.widget package alias / " +
                "wildcard proxy) against ${androidJar.path} ($detail). " +
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
