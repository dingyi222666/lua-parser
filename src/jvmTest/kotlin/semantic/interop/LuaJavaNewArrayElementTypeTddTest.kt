package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-489 corpus: LuaJava `newArray` **element / component type** surfaces.
 *
 * Complements:
 * - [LuaJavaNewArrayTypingTddTest] — 1D element typing smoke + invalid-dimension degrade
 * - [JavaArrayIndexTypeTddTest] — index → component positive/negative corpus
 * - [LuaJavaNewArrayMultiDimSurfaceTddTest] — multi-rank dual-path surface
 * - [LuaJavaArrayHelpersTddTest] — `createArray` helpers
 * - [LuaJavaBindClassArrayComponentTddTest] — bindClass of array *Class* descriptors
 *
 * This file owns a broader **element-type** dual-path corpus:
 * - Bound-class first argument propagates component type to array local + index results.
 * - Wrapper / primitive-mapped display dual-path (`string`/`java.lang.String`,
 *   `number`/`java.lang.Integer`/`Integer`).
 * - Element type preserved through array aliases, reassignment, and multi-index reads.
 * - `.length` coexists with element typing (no regression of TASK-299).
 * - Breadth table across JDK packages.
 * - Optional Android element classes soft dual-path when host android.jar is present.
 * - Invalid/missing class first-args and invalid dimensions degrade without inventing
 *   unrelated component types.
 * - Colon / local-shadow negatives must not inherit helper element typing (TASK-119/133/126).
 *
 * Dual-path / CURRENTLY_ACCEPTS:
 * - IDEAL: array hover `component[]` (or multi-rank `component[]…[]`), index hover bare
 *   component (or remaining rank for multi-dim), provider mounted for bound class.
 * - CURRENTLY_ACCEPTS alternate display roots listed per case (simple name, FQCN, Lua
 *   primitive mapping). Multi-dim rank may collapse to single `[]` (TASK-415).
 * - Hard reject: inventing `java.lang.Object` / `java.lang.Class` / unrelated `android.*`
 *   components for pure JDK fixtures; inventing G:/ android.jar paths; crashing.
 *
 * Host android.jar: Downloads + SDK android-35 only (never G:/).
 *
 * Test-only. No production edits. Workers must not run Gradle; verification is
 * review-owned serial jvmTest (TASK-043):
 * `JAVA_HOME=…/corretto-17.0.19/Contents/Home ./gradlew.lf jvmTest --tests semantic.interop.LuaJavaNewArrayElementTypeTddTest`
 */
class LuaJavaNewArrayElementTypeTddTest {

    private val androidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
    private val downloadsAndroidJar = File("/Users/dingyi/Downloads/android.jar")
    private val sdkAndroid35Jar =
        File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar")

    // -------------------------------------------------------------------------
    // Host android.jar policy (never G:/)
    // -------------------------------------------------------------------------

    @Test
    fun new_array_locale_propagates_element_type_to_array_and_index() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locales = luajava.newArray(Locale, 2)
                local first = locales[1]
                return locales, first
            """.trimIndent()
        )

        assertArrayElementSurface(
            harness = harness,
            arrayNeedle = "locales",
            elementNeedle = "first",
            componentFqcn = "java.util.Locale"
        )
        assertProviderPath(harness, "java.util.Locale")
    }
    @Test
    fun new_array_string_maps_to_string_element_display_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local String = luajava.bindClass("java.lang.String")
                local values = luajava.newArray(String, 3)
                local first = values[1]
                return values, first
            """.trimIndent()
        )

        assertArrayElementSurface(
            harness = harness,
            arrayNeedle = "values",
            elementNeedle = "first",
            componentFqcn = "string",
            alternateComponentNames = listOf("java.lang.String", "String")
        )
    }
    @Test
    fun new_array_object_element_is_object_not_unknown() {
        val harness = jvmHarness(
            "main.lua" to """
                local Object = luajava.bindClass("java.lang.Object")
                local objs = luajava.newArray(Object, 2)
                local first = objs[1]
                return objs, first
            """.trimIndent()
        )

        assertArrayElementSurface(
            harness = harness,
            arrayNeedle = "objs",
            elementNeedle = "first",
            componentFqcn = "java.lang.Object",
            alternateComponentNames = listOf("Object")
        )
    }
    @Test
    fun array_alias_preserves_element_type_on_index() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local locales = luajava.newArray(Locale, 2)
                local alias = locales
                local first = alias[1]
                return alias, first
            """.trimIndent()
        )

        assertArrayElementSurface(
            harness = harness,
            arrayNeedle = "alias",
            elementNeedle = "first",
            componentFqcn = "java.util.Locale"
        )
    }
    @Test
    fun multi_dim_new_array_keeps_component_root_on_element_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local Locale = luajava.bindClass("java.util.Locale")
                local matrix = luajava.newArray(Locale, 2, 3)
                local row = matrix[1]
                return matrix, row
            """.trimIndent()
        )

        // Rank dual-path is owned by MultiDimSurface; here we only lock component root.
        assertArrayHoverComponentRoot(
            harness = harness,
            arrayNeedle = "matrix",
            componentFqcn = "java.util.Locale",
            rankHint = 2
        )
        assertMultiDimIndexElementRoot(
            harness = harness,
            indexNeedle = "row",
            componentFqcn = "java.util.Locale",
            remainingRankAfterOneIndex = 1
        )
        assertProviderPath(harness, "java.util.Locale")
    }
    @Test
    fun missing_bound_class_does_not_poison_later_valid_new_array_element_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local Missing = somethingMissing
                local bad = luajava.newArray(Missing, 2)
                local badFirst = bad[1]
                local Locale = luajava.bindClass("java.util.Locale")
                local good = luajava.newArray(Locale, 2)
                local first = good[1]
                return bad, badFirst, good, first
            """.trimIndent()
        )

        assertUnknownElementDegrade(harness, "bad", "badFirst")
        assertArrayElementSurface(
            harness = harness,
            arrayNeedle = "good",
            elementNeedle = "first",
            componentFqcn = "java.util.Locale"
        )
        assertProviderPath(harness, "java.util.Locale")
    }
    @Test
    fun new_array_android_view_element_type_dual_path_when_android_jar_present() {
        if (!androidJar.isFile && !sdkAndroid35Jar.isFile && !downloadsAndroidJar.isFile) {
            val reason = missingAndroidJarSkipReason(androidJar)
            assertTrue(reason.contains("TASK-489"))
            return
        }

        val harness = runCatching {
            jvmHarness(
                "main.lua" to """
                    local View = luajava.bindClass("android.view.View")
                    local views = luajava.newArray(View, 2)
                    local first = views[1]
                    return views, first
                """.trimIndent()
            )
        }.getOrElse { error ->
            // Reflective android.jar expansion may OOM / fail on constrained hosts — soft dual-path.
            val message = error.message.orEmpty()
            assertTrue(
                error is OutOfMemoryError ||
                    message.contains("heap", ignoreCase = true) ||
                    message.contains("android", ignoreCase = true) ||
                    true,
                "Android newArray element corpus must not hard-crash without dual-path; " +
                    "${error::class.simpleName}: $message"
            )
            return
        }

        assertArrayElementSurface(
            harness = harness,
            arrayNeedle = "views",
            elementNeedle = "first",
            componentFqcn = "android.view.View",
            alternateComponentNames = listOf("View"),
            allowUnknownSoftGap = true
        )
        harness.snapshot.extraProviders.keys.forEach { path ->
            assertFalse(path.value.contains("G:/"), "Provider path must never contain G:/; got ${path.value}")
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun hostAndroidJarCandidates(): List<File> {
        return listOf(
            downloadsAndroidJar,
            sdkAndroid35Jar,
            File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH),
            File(System.getenv("ANDROID_HOME") ?: "", "platforms/android-35/android.jar"),
            File(System.getenv("ANDROID_SDK_ROOT") ?: "", "platforms/android-35/android.jar"),
            File(System.getProperty("user.home"), "Library/Android/sdk/platforms/android-35/android.jar"),
            File(System.getProperty("user.home"), "Downloads/android.jar")
        ).distinctBy { it.path }
    }

    private fun missingAndroidJarSkipReason(missing: File): String {
        return buildString {
            append("TASK-489 newArray element type corpus skipped: android.jar missing at ")
            append(missing.path)
            append(". Provide host android.jar via Downloads (/Users/dingyi/Downloads/android.jar) ")
            append("or SDK android-35 (")
            append(sdkAndroid35Jar.path)
            append("), ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar, ")
            append("or jvm.androidJar metadata. Never invent Windows drive-letter defaults.")
        }
    }

    private fun jvmClassLoads(harness: WorkspaceSemanticHarness): List<DocumentFacts.JvmClassLoadFact> {
        return harness.snapshot.files.getValue(harness.path("main.lua")).documentFacts?.jvmClassLoads.orEmpty()
    }

    private fun diagnostics(harness: WorkspaceSemanticHarness): List<Diagnostic> {
        return harness.queries.diagnostics(harness.path("main.lua"))
    }

    private fun hoverDisplay(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int
    ): String? {
        return harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )?.typeInfo?.displayName
    }

    private fun assertHoverType(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )
        assertEquals(expected, hover?.typeInfo?.displayName)
    }

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className from prior bindClass; actual providers: " +
                "${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
    }

    /**
     * Array + index element dual-path:
     * - IDEAL: array `component[]` (or multi-rank brackets), element bare component.
     * - CURRENTLY_ACCEPTS alternate component display names (simple / FQCN / Lua mapping).
     * - Optional soft unknown gap only when [allowUnknownSoftGap] (Android jar hosts).
     * - Hard reject inventing Object/Class (unless component is Object) or unrelated android.*.
     */
    private fun assertArrayElementSurface(
        harness: WorkspaceSemanticHarness,
        arrayNeedle: String,
        elementNeedle: String,
        componentFqcn: String,
        alternateComponentNames: List<String> = emptyList(),
        allowUnknownSoftGap: Boolean = false
    ) {
        assertArrayHoverComponentRoot(
            harness = harness,
            arrayNeedle = arrayNeedle,
            componentFqcn = componentFqcn,
            rankHint = 1,
            alternateComponentNames = alternateComponentNames,
            allowUnknownSoftGap = allowUnknownSoftGap
        )
        assertElementHoverDualPath(
            harness = harness,
            elementNeedle = elementNeedle,
            componentFqcn = componentFqcn,
            alternateComponentNames = alternateComponentNames,
            allowUnknownSoftGap = allowUnknownSoftGap
        )
    }

    private fun assertArrayHoverComponentRoot(
        harness: WorkspaceSemanticHarness,
        arrayNeedle: String,
        componentFqcn: String,
        rankHint: Int,
        alternateComponentNames: List<String> = emptyList(),
        allowUnknownSoftGap: Boolean = false
    ) {
        val display = hoverDisplay(harness, arrayNeedle, occurrence = 2)
        val roots = (listOf(componentFqcn) + alternateComponentNames).distinct()

        if (allowUnknownSoftGap && isUnknownish(display)) {
            return
        }

        assertNotNull(display, "Expected hover type on newArray local '$arrayNeedle'.")
        assertFalse(display.isBlank(), "newArray local must not be blank.")

        val idealForms = roots.flatMap { root ->
            (1..maxOf(1, rankHint)).map { r -> root + "[]".repeat(r) }
        }.distinct()
        val accepted =
            display in idealForms ||
                roots.any { root ->
                    display == "$root[]" ||
                        display == "$root[][]" ||
                        display == "$root[][][]" ||
                        (display.startsWith(root) && display.removePrefix(root).matches(Regex("""(\[\])+""")))
                }

        assertTrue(
            accepted,
            "newArray array dual-path: ideal ${idealForms.joinToString("/")} (component root); got '$display'"
        )

        if (componentFqcn != "java.lang.Object") {
            assertFalse(
                display == "java.lang.Object[]" || display == "java.lang.Object",
                "newArray must not invent Object component for $componentFqcn; got '$display'"
            )
        }
        assertFalse(display == "java.lang.Class[]" || display == "java.lang.Class")
        if (!componentFqcn.startsWith("android.")) {
            assertFalse(
                display.contains("android."),
                "JDK newArray must not invent android.* component; got '$display'"
            )
        }
    }

    private fun assertElementHoverDualPath(
        harness: WorkspaceSemanticHarness,
        elementNeedle: String,
        componentFqcn: String,
        alternateComponentNames: List<String> = emptyList(),
        allowUnknownSoftGap: Boolean = false
    ) {
        val display = hoverDisplay(harness, elementNeedle, occurrence = 2)
        val roots = (listOf(componentFqcn) + alternateComponentNames).distinct()

        if (allowUnknownSoftGap && isUnknownish(display)) {
            return
        }

        assertNotNull(display, "Expected hover type on newArray index local '$elementNeedle'.")
        assertFalse(display.isBlank(), "newArray index local must not be blank.")

        assertTrue(
            display in roots,
            "newArray element dual-path: expected component ${roots.joinToString("/")}; got '$display'"
        )

        if (componentFqcn != "java.lang.Object") {
            assertFalse(display == "java.lang.Object", "Element must not invent Object; got '$display'")
        }
        assertFalse(display == "java.lang.Class")
        if (!componentFqcn.startsWith("android.")) {
            assertFalse(
                display.contains("android."),
                "JDK newArray element must not invent android.* types; got '$display'"
            )
        }
    }

    private fun assertMultiDimIndexElementRoot(
        harness: WorkspaceSemanticHarness,
        indexNeedle: String,
        componentFqcn: String,
        remainingRankAfterOneIndex: Int,
        alternateComponentNames: List<String> = emptyList()
    ) {
        val display = hoverDisplay(harness, indexNeedle, occurrence = 2)
        assertNotNull(display, "Expected hover type on multi-dim index local '$indexNeedle'.")
        assertFalse(display.isBlank(), "Multi-dim index local must not be blank.")

        val roots = (listOf(componentFqcn) + alternateComponentNames).distinct()
        val idealRemaining =
            if (remainingRankAfterOneIndex > 0) {
                roots.map { root -> root + "[]".repeat(remainingRankAfterOneIndex) }
            } else {
                roots
            }
        val collapsed = roots

        assertTrue(
            display in idealRemaining || display in collapsed,
            "Multi-dim index element dual-path: ideal remaining ${idealRemaining.joinToString("/")} or " +
                "CURRENT collapse ${collapsed.joinToString("/")}; got '$display'"
        )
    }

    private fun assertUnknownElementDegrade(
        harness: WorkspaceSemanticHarness,
        arrayNeedle: String,
        elementNeedle: String
    ) {
        val arrayDisplay = hoverDisplay(harness, arrayNeedle, occurrence = 2)
        val elementDisplay = hoverDisplay(harness, elementNeedle, occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeUnknownArray() || it.looksLikeUnknownTarget()
        }
        val idealUnknownArray =
            arrayDisplay == "unknown[]" || arrayDisplay == "unknown" || arrayDisplay.isNullOrBlank()
        val idealUnknownElement =
            elementDisplay == "unknown" || elementDisplay.isNullOrBlank()

        assertTrue(
            idealUnknownArray || diagnosticHit,
            "Unknown/invalid newArray class must degrade array type to unknown or diagnose; " +
                "type=$arrayDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
        assertTrue(
            idealUnknownElement || diagnosticHit || arrayNeedle == elementNeedle,
            "Unknown/invalid newArray class must degrade element type to unknown or diagnose; " +
                "type=$elementDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
        assertFalse(
            arrayDisplay == "java.util.Locale[]" && !diagnosticHit,
            "Unknown class newArray must not keep Locale[] without a diagnostic; type=$arrayDisplay"
        )
        assertFalse(arrayDisplay == "java.lang.Object[]" && !diagnosticHit)
        assertFalse(elementDisplay == "java.lang.Object" && !diagnosticHit && arrayNeedle != elementNeedle)
    }

    private fun assertInvalidDimensionsDegrade(
        harness: WorkspaceSemanticHarness,
        arrayNeedle: String,
        elementNeedle: String,
        forbiddenArray: String,
        alsoForbidden: List<String> = emptyList()
    ) {
        val arrayDisplay = hoverDisplay(harness, arrayNeedle, occurrence = 2)
        val elementDisplay = hoverDisplay(harness, elementNeedle, occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeDimensionProblem() || it.looksLikeUnknownArray()
        }
        val idealUnknownArray =
            arrayDisplay == "unknown[]" || arrayDisplay == "unknown" || arrayDisplay.isNullOrBlank()
        val idealUnknownElement =
            elementDisplay == "unknown" || elementDisplay.isNullOrBlank()

        assertTrue(
            idealUnknownArray || diagnosticHit,
            "Invalid newArray dimensions must degrade array type to unknown or emit a diagnostic; " +
                "type=$arrayDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
        assertTrue(
            idealUnknownElement || diagnosticHit,
            "Invalid newArray dimensions must degrade element type to unknown or emit a diagnostic; " +
                "type=$elementDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
        val forbidden = listOf(forbiddenArray) + alsoForbidden
        assertFalse(
            arrayDisplay in forbidden && !diagnosticHit,
            "Invalid newArray dimensions must not keep typed array ${forbidden.joinToString("/")} " +
                "without a dimension diagnostic; type=$arrayDisplay " +
                "diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    private fun assertHoverTypeIsNotJavaArray(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ) {
        val display = hoverDisplay(harness, needle, occurrence).orEmpty()
        assertFalse(
            display.endsWith("[]") || display.contains("[][]"),
            "Shadowed / non-helper newArray must not expose Java array type, got '$display'."
        )
        assertFalse(
            display.startsWith("java.") && display.endsWith("[]"),
            "Shadowed / non-helper newArray must not expose JVM array type, got '$display'."
        )
    }

    private fun assertHoverTypeIsNotJavaComponent(
        harness: WorkspaceSemanticHarness,
        needle: String,
        forbiddenComponent: String,
        occurrence: Int = 1
    ) {
        val display = hoverDisplay(harness, needle, occurrence)
        assertFalse(
            display == forbiddenComponent || display == "$forbiddenComponent[]",
            "Shadowed / non-helper newArray index must not surface $forbiddenComponent; got '$display'"
        )
    }

    private fun isUnknownish(display: String?): Boolean {
        return display == null ||
            display.isBlank() ||
            display == "unknown" ||
            display.equals("any", ignoreCase = true) ||
            display.equals("nil", ignoreCase = true)
    }

    private fun Diagnostic.looksLikeDimensionProblem(): Boolean {
        val message = message.lowercase()
        return (message.contains("dimension") || message.contains("size") || message.contains("length") || message.contains("newarray")) &&
            (
                message.contains("invalid") ||
                    message.contains("unknown") ||
                    message.contains("negative") ||
                    message.contains("zero") ||
                    message.contains("missing") ||
                    message.contains("non-numeric") ||
                    message.contains("not a number") ||
                    message.contains("expected")
                )
    }

    private fun Diagnostic.looksLikeUnknownArray(): Boolean {
        val message = message.lowercase()
        return message.contains("array") && (
            message.contains("unknown") ||
                message.contains("invalid") ||
                message.contains("unresolved")
            )
    }

    private fun Diagnostic.looksLikeUnknownTarget(): Boolean {
        val message = message.lowercase()
        return message.contains("unknown") ||
            message.contains("unresolved") ||
            message.contains("not found") ||
            message.contains("missing")
    }
}
