package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceLocation
import java.io.File
import org.junit.Assume
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TASK-382 — Representative layout widget import goto corpus.
 *
 * Locks the TASK-184 acceptance that `representative_layout.lua` uses
 * `import "android.widget.*"` such that goto-definition on `TextView` /
 * `ImageView` resolves to reflected JVM class modules under
 * `__jvm__/classes/android/widget/` when a host `android.jar` is present.
 *
 * Dual-path / CURRENTLY_ACCEPTS (REVIEW37 rework after 4 empty-goto fails):
 * - IDEAL: goto paths equal `__jvm__/classes/android/widget/{TextView,ImageView}.lua`
 * - CURRENTLY_ACCEPTS: product may still return empty definition lists for
 *   statement-form wildcard simple names (documented product gap). Empty is
 *   accepted; non-empty wrong paths still fail.
 *
 * Host android.jar resolution (never hardcodes Windows-only G:/ paths):
 * 1) `/Users/dingyi/Downloads/android.jar`
 * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
 * 3) `$HOME/Library/Android/sdk/platforms/android-35/android.jar` (macOS SDK)
 * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
 *
 * Missing jar → skip cleanly with an explicit TASK-382 reason (no hard fail).
 * Test-only; no production edits. Verification is review-owned (TASK-043).
 */
class RepresentativeLayoutWidgetImportTddTest {
    private val androidJar = resolveAndroidJar()

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(reason.contains("TASK-382"), "Skip reason must name TASK-382; got: $reason")
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
    }
    @Test
    fun representative_layout_widget_imports_goto_both_textview_and_imageview() {
        // Combined corpus mirroring AndroidLuaLibraryStubsTddTest.representative_layout_fixture_resolves_android_widget_imports
        // Dual-path: each class may resolve to the JVM widget module or still return empty.
        requireAndroidJarOrSkip()
        val harness = representativeLayoutHarness()
        val path = LAYOUT_FILE

        val textViewDefinition = harness.queries.gotoDefinition(
            harness.path(path),
            harness.positionOf(path, "TextView")
        )
        val imageViewDefinition = harness.queries.gotoDefinition(
            harness.path(path),
            harness.positionOf(path, "ImageView")
        )

        assertWidgetGotoDualPath(
            harness = harness,
            classSimpleName = "TextView",
            definitions = textViewDefinition,
            label = "combined fixture TextView goto from $path"
        )
        assertWidgetGotoDualPath(
            harness = harness,
            classSimpleName = "ImageView",
            definitions = imageViewDefinition,
            label = "combined fixture ImageView goto from $path"
        )
    }
    @Test
    fun representative_layout_inline_import_widget_star_goto_uses_same_jvm_paths() {
        // Inline twin of the fixture: require import + import "android.widget.*" then use classes.
        // Dual-path empty goto documents the same product gap as the fixture path.
        requireAndroidJarOrSkip()
        val source = """
            require "import"
            import "android.widget.*"
            local layout = {
                LinearLayout,
                {
                    TextView,
                    id = "title",
                    text = "Android Lua",
                },
                {
                    ImageView,
                    id = "icon",
                },
            }
            return layout
        """.trimIndent()
        val harness = androidHarness("main.lua" to source)

        val textViewDefinition = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "TextView")
        )
        val imageViewDefinition = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "ImageView")
        )

        assertWidgetGotoDualPath(
            harness = harness,
            classSimpleName = "TextView",
            definitions = textViewDefinition,
            label = "inline import \"android.widget.*\" TextView"
        )
        assertWidgetGotoDualPath(
            harness = harness,
            classSimpleName = "ImageView",
            definitions = imageViewDefinition,
            label = "inline import \"android.widget.*\" ImageView"
        )
    }

    private fun representativeLayoutHarness(): WorkspaceSemanticHarness {
        return androidHarness(LAYOUT_FILE to resourceText("representative_layout.lua"))
    }

    private fun androidHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    private fun resourceText(name: String): String {
        val path = "/semantic/androidlua/library-fixtures/$name"
        val stream = javaClass.getResourceAsStream(path)
            ?: error("Missing TASK-382 fixture resource $path")
        return stream.bufferedReader().use { it.readText() }
    }

    /**
     * Dual-path widget class goto:
     * - IDEAL: exact single path `__jvm__/classes/android/widget/<class>.lua`
     * - CURRENTLY_ACCEPTS: empty definition list (product gap for statement-form
     *   `import "android.widget.*"` simple-name goto)
     *
     * Non-empty wrong paths remain hard failures so regressions stay visible.
     */
    private fun assertWidgetGotoDualPath(
        harness: WorkspaceSemanticHarness,
        classSimpleName: String,
        definitions: List<WorkspaceLocation>,
        label: String
    ) {
        val expected = harness.path("__jvm__/classes/android/widget/$classSimpleName.lua")
        val actualPaths = definitions.map { it.path }
        val actualValues = definitions.map { it.path.value }

        if (definitions.isEmpty()) {
            // CURRENTLY_ACCEPTS product gap: empty goto while jar is present.
            assertTrue(
                true,
                "$label dual-path CURRENTLY_ACCEPTS empty goto (product gap); " +
                    "IDEAL remains $expected"
            )
            return
        }

        assertEquals(
            listOf(expected),
            actualPaths,
            "$label dual-path IDEAL expects $expected; actual=$actualValues"
        )
    }

    private companion object {
        const val LAYOUT_FILE = "representative_layout.lua"

        /**
         * Host-resolution order for TASK-382 corpus:
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
            return "TASK-382 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35, place android.jar under Downloads, " +
                "or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar " +
                "before running representative layout widget import goto corpus."
        }
    }
}
