package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import java.io.File
import org.junit.Assume
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-211 — Android.jar package enumeration depth corpus.
 *
 * Covers nested packages used by Android-Lua / LuaJava samples
 * (`android.os`, `android.net`, `android.util`, `android.graphics`,
 * `android.graphics.drawable`, `android.graphics.drawable.shapes`,
 * `android.content.pm`, `android.content.res`, `android.app.job`, …).
 *
 * When the host android.jar is missing, jar-dependent tests skip with an
 * explicit TASK-211 reason rather than failing hard or returning silently.
 */
class AndroidJarPackageEnumerationTddTest {
    private val provider = JvmClassModuleProvider()
    private val androidJar = resolveAndroidJar()

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(reason.contains("TASK-211"), "Skip reason must name TASK-211; got: $reason")
        assertTrue(reason.contains("android.jar"), "Skip reason must mention android.jar; got: $reason")
        assertTrue(reason.contains(missing.path), "Skip reason must include the missing path; got: $reason")
        assertTrue(
            reason.contains("ANDROID_HOME") || reason.contains("ANDROID_SDK_ROOT") || reason.contains("Install"),
            "Skip reason must tell the host how to recover; got: $reason"
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
    }

    @Test
    fun depth1_android_os_wildcard_enumerates_build_from_samples() {
        requireAndroidJarOrSkip()
        val packageModule = packageModuleFor("android.os.*")

        assertEquals("android.os", packageModule.moduleName)
        assertTrue("Build" in packageModule.fields, fieldsHint("android.os", packageModule))
        assertTrue("Handler" in packageModule.fields, fieldsHint("android.os", packageModule))
        assertFalse("VERSION" in packageModule.fields, "Inner class VERSION must not appear as a top-level package field.")
    }

    @Test
    fun depth1_android_net_wildcard_enumerates_uri_from_samples() {
        requireAndroidJarOrSkip()
        val packageModule = packageModuleFor("android.net.*")

        assertEquals("android.net", packageModule.moduleName)
        assertTrue("Uri" in packageModule.fields, fieldsHint("android.net", packageModule))
        assertFalse("Builder" in packageModule.fields, "Inner Uri.Builder must not appear as a top-level package field.")
    }

    @Test
    fun depth1_android_util_wildcard_enumerates_typed_value_from_samples() {
        requireAndroidJarOrSkip()
        val packageModule = packageModuleFor("android.util.*")

        assertEquals("android.util", packageModule.moduleName)
        assertTrue("TypedValue" in packageModule.fields, fieldsHint("android.util", packageModule))
        assertTrue("SparseArray" in packageModule.fields, fieldsHint("android.util", packageModule))
    }

    @Test
    fun depth1_android_graphics_wildcard_enumerates_color_but_not_drawable_subpackage_classes() {
        requireAndroidJarOrSkip()
        val packageModule = packageModuleFor("android.graphics.*")

        assertEquals("android.graphics", packageModule.moduleName)
        assertTrue("Color" in packageModule.fields, fieldsHint("android.graphics", packageModule))
        assertTrue("Bitmap" in packageModule.fields, fieldsHint("android.graphics", packageModule))
        assertTrue("Paint" in packageModule.fields, fieldsHint("android.graphics", packageModule))
        // Nested package classes live under android.graphics.drawable, not the parent package.
        assertFalse(
            "ColorDrawable" in packageModule.fields,
            "android.graphics.* must not recurse into android.graphics.drawable; ColorDrawable should be absent."
        )
        assertFalse(
            "RectShape" in packageModule.fields,
            "android.graphics.* must not recurse into android.graphics.drawable.shapes."
        )
    }

    @Test
    fun depth2_android_graphics_drawable_wildcard_enumerates_color_drawable() {
        requireAndroidJarOrSkip()
        val packageModule = packageModuleFor("android.graphics.drawable.*")

        assertEquals("android.graphics.drawable", packageModule.moduleName)
        assertTrue("ColorDrawable" in packageModule.fields, fieldsHint("android.graphics.drawable", packageModule))
        assertTrue("GradientDrawable" in packageModule.fields, fieldsHint("android.graphics.drawable", packageModule))
        // Deeper shapes package is not part of this depth-2 enumeration.
        assertFalse(
            "RectShape" in packageModule.fields,
            "android.graphics.drawable.* must not recurse into shapes subpackage."
        )
        assertFalse(
            "Color" in packageModule.fields,
            "Parent package class Color must not leak into drawable enumeration."
        )
    }

    @Test
    fun depth3_android_graphics_drawable_shapes_wildcard_enumerates_shape_classes() {
        requireAndroidJarOrSkip()
        val packageModule = packageModuleFor("android.graphics.drawable.shapes.*")

        assertEquals("android.graphics.drawable.shapes", packageModule.moduleName)
        assertTrue("Shape" in packageModule.fields, fieldsHint("android.graphics.drawable.shapes", packageModule))
        assertTrue("RectShape" in packageModule.fields, fieldsHint("android.graphics.drawable.shapes", packageModule))
        assertTrue("OvalShape" in packageModule.fields, fieldsHint("android.graphics.drawable.shapes", packageModule))
        assertFalse(
            "ColorDrawable" in packageModule.fields,
            "Sibling package class ColorDrawable must not appear under shapes enumeration."
        )
    }

    @Test
    fun depth2_android_content_pm_wildcard_enumerates_package_manager() {
        requireAndroidJarOrSkip()
        val packageModule = packageModuleFor("android.content.pm.*")

        assertEquals("android.content.pm", packageModule.moduleName)
        assertTrue("PackageManager" in packageModule.fields, fieldsHint("android.content.pm", packageModule))
        assertTrue("PackageInfo" in packageModule.fields, fieldsHint("android.content.pm", packageModule))
        assertFalse(
            "Context" in packageModule.fields,
            "Parent package class Context must not leak into content.pm enumeration."
        )
    }

    @Test
    fun depth2_android_content_res_wildcard_enumerates_resources() {
        requireAndroidJarOrSkip()
        val packageModule = packageModuleFor("android.content.res.*")

        assertEquals("android.content.res", packageModule.moduleName)
        assertTrue("Resources" in packageModule.fields, fieldsHint("android.content.res", packageModule))
        assertTrue("AssetManager" in packageModule.fields, fieldsHint("android.content.res", packageModule))
        assertFalse(
            "Theme" in packageModule.fields,
            "Inner Resources.Theme must not appear as a top-level package field."
        )
    }

    @Test
    fun depth2_android_app_job_wildcard_enumerates_job_scheduler() {
        requireAndroidJarOrSkip()
        val packageModule = packageModuleFor("android.app.job.*")

        assertEquals("android.app.job", packageModule.moduleName)
        assertTrue("JobScheduler" in packageModule.fields, fieldsHint("android.app.job", packageModule))
        assertTrue("JobInfo" in packageModule.fields, fieldsHint("android.app.job", packageModule))
        assertTrue("JobService" in packageModule.fields, fieldsHint("android.app.job", packageModule))
        assertFalse(
            "Activity" in packageModule.fields,
            "Parent package class Activity must not leak into app.job enumeration."
        )
    }

    @Test
    fun sample_depth1_packages_used_by_android_lua_fixtures_enumerate_expected_classes() {
        requireAndroidJarOrSkip()

        val widget = packageModuleFor("android.widget.*")
        assertTrue("TextView" in widget.fields, fieldsHint("android.widget", widget))
        assertTrue("Button" in widget.fields, fieldsHint("android.widget", widget))

        val view = packageModuleFor("android.view.*")
        assertTrue("View" in view.fields, fieldsHint("android.view", view))
        assertTrue("ViewGroup" in view.fields, fieldsHint("android.view", view))

        val app = packageModuleFor("android.app.*")
        assertTrue("Activity" in app.fields, fieldsHint("android.app", app))

        val content = packageModuleFor("android.content.*")
        assertTrue("Context" in content.fields, fieldsHint("android.content", content))
        // Nested pm/res classes are not direct members of android.content.
        assertFalse("PackageManager" in content.fields)
        assertFalse("Resources" in content.fields)
    }

    @Test
    fun multi_depth_package_providers_are_isolated_and_keyed_by_package_path() {
        requireAndroidJarOrSkip()
        val providers = provider.packageProvidersFor(
            importTargets = listOf(
                "android.os.*",
                "android.graphics.drawable.*",
                "android.graphics.drawable.shapes.*",
                "android.content.pm.*"
            ),
            configuration = androidConfiguration()
        )

        val expectedPaths = listOf(
            "__jvm__/packages/android/os.lua",
            "__jvm__/packages/android/graphics/drawable.lua",
            "__jvm__/packages/android/graphics/drawable/shapes.lua",
            "__jvm__/packages/android/content/pm.lua"
        ).map(VirtualPath::of)

        expectedPaths.forEach { path ->
            assertTrue(
                path in providers,
                "Expected package provider $path; actual: ${providers.keys.map { it.value }}"
            )
        }

        val os = assertNotNull(providers[VirtualPath.of("__jvm__/packages/android/os.lua")]?.moduleExportSurface?.moduleType)
        val drawable = assertNotNull(
            providers[VirtualPath.of("__jvm__/packages/android/graphics/drawable.lua")]?.moduleExportSurface?.moduleType
        )
        val shapes = assertNotNull(
            providers[VirtualPath.of("__jvm__/packages/android/graphics/drawable/shapes.lua")]?.moduleExportSurface?.moduleType
        )
        val pm = assertNotNull(
            providers[VirtualPath.of("__jvm__/packages/android/content/pm.lua")]?.moduleExportSurface?.moduleType
        )

        assertTrue("Build" in os.fields)
        assertTrue("ColorDrawable" in drawable.fields)
        assertTrue("RectShape" in shapes.fields)
        assertTrue("PackageManager" in pm.fields)

        // Cross-depth isolation: no package module should absorb another depth's exclusive classes.
        assertFalse("ColorDrawable" in os.fields)
        assertFalse("Build" in drawable.fields)
        assertFalse("PackageManager" in shapes.fields)
        assertFalse("RectShape" in pm.fields)
    }

    @Test
    fun nested_package_enumeration_skips_inner_classes_at_every_depth() {
        requireAndroidJarOrSkip()

        val os = packageModuleFor("android.os.*")
        assertTrue("Build" in os.fields)
        assertFalse("VERSION" in os.fields)
        assertFalse("VERSION_CODES" in os.fields)

        val net = packageModuleFor("android.net.*")
        assertTrue("Uri" in net.fields)
        assertFalse("Builder" in net.fields)

        val res = packageModuleFor("android.content.res.*")
        assertTrue("Resources" in res.fields)
        assertFalse("Theme" in res.fields)
        assertFalse("NotFoundException" in res.fields)

        val job = packageModuleFor("android.app.job.*")
        assertTrue("JobInfo" in job.fields)
        assertFalse("Builder" in job.fields)
    }

    @Test
    fun empty_or_unknown_nested_package_yields_no_provider() {
        requireAndroidJarOrSkip()
        val providers = provider.packageProvidersFor(
            importTargets = listOf(
                "android.does.not.exist.nested.*",
                "com.missing.sample.package.*"
            ),
            configuration = androidConfiguration()
        )

        assertTrue(
            providers.isEmpty(),
            "Expected no package providers for missing nested packages; actual: ${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun non_wildcard_nested_target_is_not_treated_as_package_provider() {
        requireAndroidJarOrSkip()
        val providers = provider.packageProvidersFor(
            importTargets = listOf(
                "android.os.Build",
                "android.net.Uri",
                "android.graphics.drawable.ColorDrawable"
            ),
            configuration = androidConfiguration()
        )

        assertTrue(
            providers.isEmpty(),
            "packageProvidersFor only handles wildcard package targets; actual: ${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun workspace_engine_mounts_nested_depth2_drawable_package_from_android_jar() {
        requireAndroidJarOrSkip()
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                import "android.graphics.drawable.*"
                local current = ColorDrawable
                return current
            """.trimIndent(),
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine()
        )

        val packagePath = harness.path("__jvm__/packages/android/graphics/drawable.lua")
        assertTrue(
            packagePath in harness.snapshot.extraProviders,
            "Expected nested package provider $packagePath; actual: ${harness.snapshot.extraProviders.keys.map { it.value }}"
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "current")
        )
        assertTrue(
            completions.any { it.label == "ColorDrawable" },
            "Expected ColorDrawable completion from nested drawable package; actual: ${completions.map { it.label }}"
        )
    }

    @Test
    fun workspace_engine_mounts_nested_depth3_shapes_package_from_android_jar() {
        requireAndroidJarOrSkip()
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                import "android.graphics.drawable.shapes.*"
                local shape = RectShape
                return shape
            """.trimIndent(),
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine()
        )

        val packagePath = harness.path("__jvm__/packages/android/graphics/drawable/shapes.lua")
        assertTrue(
            packagePath in harness.snapshot.extraProviders,
            "Expected depth-3 package provider $packagePath; actual: ${harness.snapshot.extraProviders.keys.map { it.value }}"
        )

        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "RectShape")
        )
        assertTrue(
            definitions.any { it.path == harness.path("__jvm__/classes/android/graphics/drawable/shapes/RectShape.lua") },
            "Expected gotoDefinition to nested RectShape class provider; actual: ${definitions.map { it.path.value }}"
        )
    }

    @Test
    fun workspace_engine_mounts_sample_os_and_net_packages_from_android_jar() {
        requireAndroidJarOrSkip()
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                import "android.os.*"
                import "android.net.*"
                local sdk = Build
                local uri = Uri
                return sdk, uri
            """.trimIndent(),
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine()
        )

        assertTrue(harness.path("__jvm__/packages/android/os.lua") in harness.snapshot.extraProviders)
        assertTrue(harness.path("__jvm__/packages/android/net.lua") in harness.snapshot.extraProviders)

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "sdk")
        )
        assertTrue(completions.any { it.label == "Build" }, "Expected Build from android.os.*; got ${completions.map { it.label }}")
        assertTrue(completions.any { it.label == "Uri" }, "Expected Uri from android.net.*; got ${completions.map { it.label }}")
    }

    @Test
    fun parent_and_nested_package_enumerations_do_not_share_fields() {
        requireAndroidJarOrSkip()
        val parent = packageModuleFor("android.content.*")
        val pm = packageModuleFor("android.content.pm.*")
        val res = packageModuleFor("android.content.res.*")

        assertTrue("Context" in parent.fields)
        assertTrue("PackageManager" in pm.fields)
        assertTrue("Resources" in res.fields)

        assertFalse("PackageManager" in parent.fields)
        assertFalse("Resources" in parent.fields)
        assertFalse("Context" in pm.fields)
        assertFalse("Context" in res.fields)
        assertFalse("PackageManager" in res.fields)
        assertFalse("Resources" in pm.fields)
    }

    private fun packageModuleFor(importTarget: String): ModuleType {
        val providers = provider.packageProvidersFor(listOf(importTarget), androidConfiguration())
        val packageName = importTarget.removeSuffix(".*")
        val path = VirtualPath.of("__jvm__/packages/${packageName.replace('.', '/')}.lua")
        return assertNotNull(
            providers[path]?.moduleExportSurface?.moduleType,
            "Expected Android nested package provider for $importTarget from ${androidJar.path}; " +
                "actual providers: ${providers.keys.map { it.value }}"
        )
    }

    private fun androidConfiguration(): JvmWorkspaceConfiguration {
        return JvmWorkspaceConfiguration(androidJar = androidJar.path)
    }

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    private fun fieldsHint(packageName: String, module: ModuleType): String {
        return "Expected nested package $packageName from ${androidJar.path}; fields=${module.fields.keys.sorted()}"
    }

    companion object {
        /**
         * Host-resolution order for TASK-211 corpus:
         * 1) WAVE21C mac SDK path from worker prompt
         * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
         * 3) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35/android.jar
         */
        private fun resolveAndroidJar(): File {
            val candidates = linkedSetOf<File>()
            candidates += File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar")
            candidates += File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env -> System.getenv(env)?.trim()?.takeIf(String::isNotEmpty) }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }
            return candidates.firstOrNull { it.isFile } ?: candidates.first()
        }

        internal fun missingAndroidJarSkipReason(androidJar: File): String {
            return "TASK-211 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35 (or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar) " +
                "before running nested package enumeration depth corpus."
        }
    }
}
