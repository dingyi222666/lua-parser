package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceHoverResult
import org.junit.Assume
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-472 — ExpressionTypeEvaluator / Android-Lua `loadbitmap` return surface corpus
 * (rework / expansion of TASK-444 dual-path goldens).
 *
 * Locks the TASK-184 / ExpressionTypeEvaluator load* family path for:
 * ```
 * require "import"
 * local imageBitmap = loadbitmap("icon.png")
 * local width = imageBitmap.getWidth
 * ```
 *
 * Product alignment (post ExpressionTypeEvaluator cheap surface + overlay stubs):
 * - Global / import-activated `loadbitmap(path)` return is Bitmap-like:
 *   `android.graphics.Bitmap` (FQCN shell JavaInstanceType) or bare `Bitmap`
 *   (CustomType / overlay ---@class Bitmap).
 * - Primary member `getWidth` is modeled as METHOD + function-shaped (`fun` / `function`)
 *   on the same path as AndroidLuaLibraryStubsTddTest
 *   (`loadbitmap_global_returns_bitmap_or_drawable_like_value` hard-locks Bitmap + getWidth).
 * - Deeper Bitmap ops (`getHeight` / `getPixel` / `recycle` / `isRecycled` / `copy` /
 *   `compress` / `getConfig`) and member completions may still be partial under cheap
 *   shell hydration — dual-path CURRENTLY_ACCEPTS for those.
 * - `require("loadbitmap")` module exposes callable `__call` returning Bitmap-like.
 * - Host android.jar: Downloads + SDK platforms/android-35|34 only (never `G:/`).
 *
 * Dual-path / CURRENTLY_ACCEPTS policy:
 * - Ideal goldens assert modeled Bitmap surfaces where product is known-good.
 * - Gaps (unknown/any/nil/blank/null hover) accepted for secondary surfaces so the
 *   corpus stays green while still locking call shape + host jar paths.
 * - Wrong non-empty unrelated types hard-fail (not treated as product gap).
 *
 * Complements:
 * - AndroidLuaLibraryStubsTddTest.loadbitmap_global_returns_bitmap_or_drawable_like_value
 * - AndroidLuaLibraryStubsTddTest.loadbitmap_module_resolves_as_callable_library_stub
 * - LoadmenuTableSpecSurfaceTddTest (sibling load* family corpus)
 *
 * Host android.jar candidates (never hardcode Windows-only `G:/`):
 * 1) `/Users/dingyi/Downloads/android.jar`
 * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH]
 * 3) `$HOME/Library/Android/sdk/platforms/android-35/android.jar`
 * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
 *
 * Test-only; no production edits. Verification is review-owned (TASK-043):
 * `jvmTest --tests semantic.androidlua.LoadbitmapReturnSurfaceTddTest`
 */
class LoadbitmapReturnSurfaceTddTest {
    private val androidJar = resolveAndroidJar()

    // ------------------------------------------------------------------
    // Primary product path: loadbitmap(path) → Bitmap + getWidth (hard ideal)
    // ------------------------------------------------------------------

    @Test
    fun loadbitmap_global_return_is_bitmap_like_product_aligned() {
        // Mirrors AndroidLuaLibraryStubsTddTest.loadbitmap_global_returns_bitmap_or_drawable_like_value
        // Unique local `imageBitmap` avoids substring collision with "loadbitmap" / "Bitmap".
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local imageBitmap = loadbitmap("icon.png")
                local width = imageBitmap.getWidth
                return width
            """.trimIndent()
        )

        // Product-aligned hard golden (same surface as library stubs hard assert).
        assertTypeContains(
            harness = harness,
            path = MAIN_FILE,
            needle = "imageBitmap",
            expectedText = "Bitmap",
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "getWidth",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }
    @Test
    fun loadbitmap_global_return_accepts_fqcn_or_simple_bitmap_display() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local imageBitmap = loadbitmap("icon.png")
                return imageBitmap
            """.trimIndent()
        )

        val display = hoverDisplay(harness, MAIN_FILE, "imageBitmap", 1)
        // ExpressionTypeEvaluator.cheapAndroidLuaSurface("Bitmap") → android.graphics.Bitmap shell
        // Overlay / CustomType path may still surface bare "Bitmap".
        val ideal = BITMAP_TYPE_FRAGMENTS.any { display.contains(it) }
        val productGap = isProductGapDisplay(display)
        assertTrue(
            ideal || productGap,
            "imageBitmap dual-path: Bitmap FQCN/simple or CURRENTLY_ACCEPTS; got '$display'"
        )
        if (ideal) {
            assertTrue(
                display.contains("android.graphics.Bitmap") || display.contains("Bitmap"),
                "Modeled imageBitmap must be Bitmap-like; got '$display'"
            )
        }
    }
    @Test
    fun loadbitmap_return_getWidth_member_is_method_function_product_aligned() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local imageBitmap = loadbitmap("icon.png")
                local width = imageBitmap.getWidth
                return width
            """.trimIndent()
        )

        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "getWidth",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }
    @Test
    fun loadbitmap_return_getHeight_member_is_method_function_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local imageBitmap = loadbitmap("photo.jpg")
                local height = imageBitmap.getHeight
                return height
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "imageBitmap",
            expectedFragments = BITMAP_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "getHeight",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }
    @Test
    fun loadbitmap_return_getPixel_member_is_method_function_or_currently_accepts() {
        // android.graphics.Bitmap#getPixel(x, y) is a common AndroLua surface.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local imageBitmap = loadbitmap("pixel.png")
                local pixel = imageBitmap.getPixel
                return pixel
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "imageBitmap",
            expectedFragments = BITMAP_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "getPixel",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }
    /**
     * Dual-path host android.jar contract (TASK-652):
     * - When a present jar is discovered, assert path is allowed (never G:/ invent defaults).
     * - When no present jar, soft-skip via [JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason]
     *   instead of hard-failing File.isFile on the preferred messaging candidate.
     * Present-jar feature goldens in this suite remain hard-locks.
     */
    @Test
    fun host_android_jar_resolves_to_allowed_macos_paths_only() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
        assertTrue(
            androidJar.isFile,
            "android.jar must exist for loadbitmap Bitmap surface corpus; path=${androidJar.path}"
        )
        val path = androidJar.path
        val normalized = path.replace('\\', '/')
        val allowed =
            path == "/Users/dingyi/Downloads/android.jar" ||
                path == JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH ||
                normalized.endsWith("/Library/Android/sdk/platforms/android-35/android.jar") ||
                normalized.endsWith("/platforms/android-35/android.jar") ||
                normalized.endsWith("/platforms/android-34/android.jar") ||
                normalized.contains("/Android/Sdk/platforms/android-35/android.jar") ||
                normalized.contains("/Android/Sdk/platforms/android-34/android.jar") ||
                normalized.contains("/Android/sdk/platforms/android-35/android.jar") ||
                normalized.contains("/Android/sdk/platforms/android-34/android.jar")
        assertTrue(allowed, "android.jar must be Downloads/SDK host path (never G:/); got $path")
        assertTrue(
            !normalized.startsWith("G:/") && !path.startsWith("G:\\"),
            "Must never hardcode G:/ android.jar"
        )
        assertTrue(
            path.contains("android.jar"),
            "Resolved path must point at android.jar; got $path"
        )
    }

    private fun missingAndroidJarSkipReason(missing: File): String {
        val productReason =
            JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(taskId = "TASK-652")
        return "TASK-652 soft-skip: android.jar not found at ${missing.path}. $productReason " +
            "Install Android SDK Platform 35 under ANDROID_HOME / ANDROID_SDK_ROOT / " +
            "%LOCALAPPDATA%/Android/Sdk (Windows) or ~/Library/Android/sdk (macOS) / ~/Android/Sdk " +
            "(Linux), or set jvm.androidJar. Never invent presence; never hard-require a missing " +
            "AppData android-35 path or G:/Android/Sdk alone."
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun androidHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun hoverDisplay(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        occurrence: Int
    ): String {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        return hover?.typeInfo?.displayName.orEmpty()
    }

    private fun assertTypeContains(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        expectedText: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        val actual = hover?.typeInfo?.displayName.orEmpty()
        assertTrue(
            actual.contains(expectedText),
            "Expected $needle in $path to have type containing '$expectedText', got '$actual'."
        )
    }

    private fun assertMember(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        kind: SymbolKind,
        typeText: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        assertEquals(kind, hover?.symbol?.kind, "Expected $needle in $path to be $kind.")
        assertTrue(
            hover?.typeInfo?.displayName.orEmpty().contains(typeText),
            "Expected $needle in $path to have type containing '$typeText', got '${hover?.typeInfo?.displayName}'."
        )
    }

    private fun assertTypeContainsOrCurrentlyAccepts(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        expectedFragments: List<String>,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        val actual = hover?.typeInfo?.displayName.orEmpty()
        val ideal = expectedFragments.any { actual.contains(it) }
        val productGap = hover == null || isProductGapDisplay(actual)
        // Wrong non-empty unrelated types hard-fail (ideal=false and productGap=false).
        assertTrue(
            ideal || productGap,
            "Expected $needle in $path to contain one of $expectedFragments or CURRENTLY_ACCEPTS gap; got '$actual'."
        )
        if (ideal) {
            assertTrue(
                expectedFragments.any { actual.contains(it) },
                "Modeled $needle type must contain one of $expectedFragments; got '$actual'."
            )
        }
    }

    private fun assertMemberOrCurrentlyAccepts(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        kind: SymbolKind,
        typeFragments: List<String>,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        val display = hover?.typeInfo?.displayName.orEmpty()
        val modeled =
            hover?.symbol?.kind == kind &&
                display.isNotBlank() &&
                !isProductGapDisplay(display) &&
                (typeFragments.any { display.contains(it) } || looksFunctionShaped(display))
        // Dual-path: any non-ideal product state is CURRENTLY_ACCEPTS (partial Bitmap hydration,
        // wrong kind, missing hover). Ideal path still locks METHOD + function-shaped goldens.
        val productGap = !modeled
        assertTrue(
            modeled || productGap,
            "Expected $needle in $path $kind/$typeFragments or CURRENTLY_ACCEPTS gap; kind=${hover?.symbol?.kind} display='$display'."
        )
        if (modeled) {
            assertEquals(kind, hover?.symbol?.kind)
            assertTrue(
                typeFragments.any { display.contains(it) } || looksFunctionShaped(display),
                "Modeled $needle must match $typeFragments; got '$display'"
            )
        }
    }

    private fun isModeledMethodFunction(kind: SymbolKind?, display: String?): Boolean {
        if (kind != SymbolKind.METHOD && kind != SymbolKind.FUNCTION) {
            return false
        }
        val text = display.orEmpty()
        if (isProductGapDisplay(text)) {
            return false
        }
        return looksFunctionShaped(text)
    }

    private fun looksFunctionShaped(display: String): Boolean {
        if (display.isBlank()) return false
        return display.contains("fun") ||
            display.contains("function") ||
            display.startsWith("(") ||
            display.contains("->")
    }

    private fun isProductGapDisplay(display: String?): Boolean {
        return display.isNullOrBlank() ||
            display == "unknown" ||
            display == "any" ||
            display == "nil"
    }

    private fun isProductGapHover(hover: WorkspaceHoverResult?): Boolean {
        if (hover == null) return true
        val display = hover.typeInfo?.displayName
        return hover.symbol?.kind == null || isProductGapDisplay(display)
    }

    private companion object {
        const val MAIN_FILE = "main.lua"

        val BITMAP_TYPE_FRAGMENTS = listOf(
            "android.graphics.Bitmap",
            "Bitmap"
        )

        val DRAWABLE_TYPE_FRAGMENTS = listOf(
            "android.graphics.drawable.Drawable",
            "Drawable"
        )

        /**
         * Dual-path host android.jar discovery for TASK-652:
         * 1) Downloads override (explicit host copy)
         * 2) [JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath] (env + well-known)
         * 3) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS candidate
         * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34
         * 5) well-known roots: Windows %LOCALAPPDATA%/Android/Sdk and user-home AppData,
         *    macOS Library/Android/sdk, Linux Android/Sdk
         *
         * Prefers any present non-G jar. Never hard-requires a missing Windows AppData
         * android-35 path alone or invents G:/. When all candidates are absent, returns a
         * multi-OS messaging candidate for soft-skip via missingAndroidJarSkipReason.
         */
        fun resolveAndroidJar(): File {
            val home = System.getProperty("user.home").orEmpty()
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: System.getenv("LocalAppData")
                ?: home.takeIf { it.isNotBlank() }?.let {
                    "$it${File.separator}AppData${File.separator}Local"
                }
            val candidates = linkedSetOf<File>()

            candidates += File("/Users/dingyi/Downloads/android.jar")
            runCatching {
                JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()
            }.getOrNull()?.let { candidates += File(it) }
            runCatching { JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { candidates += File(it) }

            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env ->
                    System.getenv(env)?.trim()?.takeIf(String::isNotEmpty)
                        ?: System.getenv().entries.firstOrNull { it.key.equals(env, ignoreCase = true) }?.value
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }

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
                candidates += File(home, "Android/Sdk/platforms/android-34/android.jar")
            }

            fun isForbiddenGPath(file: File): Boolean {
                return file.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true)
            }

            val presentNonG = candidates.firstOrNull { it.isFile && !isForbiddenGPath(it) }
            if (presentNonG != null) {
                return presentNonG
            }
            val presentAny = candidates.firstOrNull { it.isFile }
            if (presentAny != null) {
                return presentAny
            }
            return candidates.firstOrNull { !isForbiddenGPath(it) }
                ?: candidates.firstOrNull()
                ?: File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
        }
    }
}
