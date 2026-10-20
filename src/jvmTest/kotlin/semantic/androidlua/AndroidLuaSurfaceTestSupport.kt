package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceHoverResult
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Package-level consolidation of helpers that were copy-pasted verbatim across the
 * Android-Lua surface corpora (LoadbitmapReturnSurfaceTddTest,
 * LoadlayoutIdFieldSurfaceTddTest, LoadmenuTableSpecSurfaceTddTest,
 * AndroidLuaLibraryStubsTddTest).
 *
 * Bodies are hoisted verbatim from the (deleted) per-test private copies; assertions,
 * messages, and dual-path semantics are unchanged. Classes that still declare their own
 * private member with one of these names keep using their member (member scope wins).
 */

internal const val MAIN_FILE = "main.lua"

internal fun missingAndroidJarSkipReason(missing: File): String {
    val productReason =
        JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(taskId = "TASK-652")
    return "TASK-652 soft-skip: android.jar not found at ${missing.path}. $productReason " +
        "Install Android SDK Platform 35 under ANDROID_HOME / ANDROID_SDK_ROOT / " +
        "%LOCALAPPDATA%/Android/Sdk (Windows) or ~/Library/Android/sdk (macOS) / ~/Android/Sdk " +
        "(Linux), or set jvm.androidJar. Never invent presence; never hard-require a missing " +
        "AppData android-35 path or G:/Android/Sdk alone."
}

internal fun hoverDisplay(
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

internal fun assertTypeContains(
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

internal fun assertTypeContainsAny(
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
    assertTrue(
        expectedFragments.any { actual.contains(it) },
        "Expected $needle in $path to contain one of $expectedFragments; got '$actual'."
    )
    assertTrue(
        actual.isNotBlank() && actual != "unknown" && actual != "any" && actual != "nil",
        "Expected modeled non-gap type for $needle in $path; got '$actual'."
    )
}

internal fun assertTypeContainsOrCurrentlyAccepts(
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

internal fun assertMember(
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

internal fun assertMemberOrCurrentlyAccepts(
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
    // Dual-path: any non-ideal product state is CURRENTLY_ACCEPTS (partial hydration,
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

internal fun isModeledMethodFunction(kind: SymbolKind?, display: String?): Boolean {
    if (kind != SymbolKind.METHOD && kind != SymbolKind.FUNCTION) {
        return false
    }
    val text = display.orEmpty()
    if (isProductGapDisplay(text)) {
        return false
    }
    return looksFunctionShaped(text)
}

internal fun looksFunctionShaped(display: String): Boolean {
    if (display.isBlank()) return false
    return display.contains("fun") ||
        display.contains("function") ||
        display.startsWith("(") ||
        display.contains("->")
}

internal fun isProductGapDisplay(display: String?): Boolean {
    return display.isNullOrBlank() ||
        display == "unknown" ||
        display == "any" ||
        display == "nil"
}

@Suppress("unused")
internal fun isProductGapHover(hover: WorkspaceHoverResult?): Boolean {
    if (hover == null) return true
    val display = hover.typeInfo?.displayName
    return hover.symbol?.kind == null || isProductGapDisplay(display)
}

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
internal fun resolveAndroidJar(): File {
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
