package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Android platform jar reflection surface for AndroLua-style JVM class modules.
 *
 * Host android.jar resolution (never hardcodes Windows-only `G:/Android/Sdk`):
 * 1) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] / ANDROID_HOME / ANDROID_SDK_ROOT
 * 2) well-known macOS `$HOME/Library/Android/sdk/platforms/android-35/android.jar`
 * 3) explicit host path `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar`
 *
 * TASK-584 product lock: package wildcards for android.app/content/view/widget must
 * enumerate Activity/Context/View/TextView, skip inners, and stay empty when jar absent.
 *
 * Verification is review-owned (TASK-043):
 * `jvmTest --tests interop.jvm.AndroidJarReflectionTddTest`
 */
class AndroidJarReflectionTddTest {
    private val androidJar: File = resolveHostAndroidJar()

    @Test
    fun android_jar_path_is_available_for_task_019_reflection_tests() {
        assertAndroidJarPresent()
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
    fun jvm_android_jar_metadata_appends_android_jar_to_effective_classpath() {
        assertAndroidJarPresent()
        val configuration = androidConfiguration()

        assertEquals(listOf(androidJar.path), configuration.effectiveClasspathEntries())
    }

    @Test
    fun jvm_classpath_metadata_can_load_android_context_when_android_jar_is_on_configurable_classpath() {
        assertAndroidJarPresent()
        val requested = provider().requestedClasses(
            mapOf(
                JvmWorkspaceConfiguration.CLASSPATH_METADATA_KEY to androidJar.path,
                JvmClassModuleProvider.CLASSES_METADATA_KEY to "android.content.Context"
            )
        )

        assertEquals(linkedSetOf("android.content.Context"), requested)
    }

    @Test
    fun jvm_android_jar_metadata_can_load_android_text_view_explicit_class() {
        val providers = providersForClasses("android.widget.TextView")

        providers.assertProviderPath("android.widget.TextView")
        providers.assertModule("android.widget.TextView").assertClassName("android.widget.TextView")
    }

    @Test
    fun jvm_android_jar_metadata_can_load_android_view_explicit_class() {
        val providers = providersForClasses("android.view.View")

        providers.assertProviderPath("android.view.View")
        providers.assertModule("android.view.View").assertClassName("android.view.View")
    }

    @Test
    fun jvm_android_jar_metadata_can_load_android_context_explicit_class() {
        val providers = providersForClasses("android.content.Context")

        providers.assertProviderPath("android.content.Context")
        providers.assertModule("android.content.Context").assertClassName("android.content.Context")
    }

    @Test
    fun jvm_android_jar_metadata_can_load_android_activity_explicit_class() {
        val providers = providersForClasses("android.app.Activity")

        providers.assertProviderPath("android.app.Activity")
        providers.assertModule("android.app.Activity").assertClassName("android.app.Activity")
    }

    @Test
    fun android_text_view_provider_exposes_static_fields_from_android_jar() {
        val module = moduleFor("android.widget.TextView")

        assertTrue("AUTO_SIZE_TEXT_TYPE_NONE" in module.fields)
        assertTrue("AUTO_SIZE_TEXT_TYPE_UNIFORM" in module.fields)
    }

    @Test
    fun android_text_view_provider_exposes_instance_methods_from_android_jar_class_type() {
        val instanceType = moduleFor("android.widget.TextView").javaInstanceType()

        assertTrue("setText" in instanceType.allInstanceMembers())
        assertTrue("getText" in instanceType.allInstanceMembers())
    }

    @Test
    fun android_view_provider_exposes_static_constants_from_android_jar() {
        val module = moduleFor("android.view.View")

        assertTrue("VISIBLE" in module.fields)
        assertTrue("GONE" in module.fields)
    }

    @Test
    fun android_view_provider_exposes_static_methods_from_android_jar() {
        val module = moduleFor("android.view.View")

        assertTrue("generateViewId" in module.methods)
        assertTrue("inflate" in module.methods)
    }

    @Test
    fun android_view_provider_exposes_instance_members_from_android_jar_class_type() {
        val instanceType = moduleFor("android.view.View").javaInstanceType()

        assertTrue("setOnClickListener" in instanceType.allInstanceMembers())
        assertTrue("performClick" in instanceType.allInstanceMembers())
    }

    @Test
    fun android_context_provider_exposes_static_service_constants_from_android_jar() {
        val module = moduleFor("android.content.Context")

        assertTrue("WINDOW_SERVICE" in module.fields)
        assertTrue("LAYOUT_INFLATER_SERVICE" in module.fields)
    }

    @Test
    fun android_context_provider_exposes_instance_methods_from_android_jar_class_type() {
        val instanceType = moduleFor("android.content.Context").javaInstanceType()

        assertTrue("getResources" in instanceType.allInstanceMembers())
        assertTrue("getSystemService" in instanceType.allInstanceMembers())
    }

    @Test
    fun android_activity_provider_exposes_static_result_constants_from_android_jar() {
        val module = moduleFor("android.app.Activity")

        assertTrue("RESULT_OK" in module.fields)
        assertTrue("RESULT_CANCELED" in module.fields)
    }

    @Test
    fun android_activity_provider_exposes_instance_methods_from_android_jar_class_type() {
        val instanceType = moduleFor("android.app.Activity").javaInstanceType()

        assertTrue("setContentView" in instanceType.allInstanceMembers())
        assertTrue("findViewById" in instanceType.allInstanceMembers())
    }

    @Test
    fun android_text_view_inner_buffer_type_resolves_by_binary_name() {
        val providers = providersForClasses("android.widget.TextView\$BufferType")

        providers.assertProviderPath("android.widget.TextView\$BufferType")
        providers.assertModule("android.widget.TextView\$BufferType").assertClassName("android.widget.TextView\$BufferType")
    }

    @Test
    fun android_text_view_inner_buffer_type_resolves_by_dotted_name_candidate() {
        val requested = provider().requestedClasses(
            androidConfiguration(classes = linkedSetOf("android.widget.TextView.BufferType"))
        )

        assertEquals(linkedSetOf("android.widget.TextView\$BufferType"), requested)
    }

    @Test
    fun android_text_view_inner_buffer_type_resolves_by_underscore_alias() {
        val requested = provider().requestedClasses(
            androidConfiguration(classes = linkedSetOf("android.widget.TextView_BufferType"))
        )

        assertEquals(linkedSetOf("android.widget.TextView\$BufferType"), requested)
    }

    @Test
    fun android_view_inner_on_click_listener_resolves_by_short_import_prefix() {
        val requested = provider().requestedClasses(
            androidConfiguration(
                androluaImports = listOf("OnClickListener"),
                importPrefixes = listOf("android.view.View")
            )
        )

        assertEquals(linkedSetOf("android.view.View\$OnClickListener"), requested)
    }

    @Test
    fun android_view_inner_measure_spec_resolves_by_dotted_name_candidate() {
        val requested = provider().requestedClasses(
            androidConfiguration(classes = linkedSetOf("android.view.View.MeasureSpec"))
        )

        assertEquals(linkedSetOf("android.view.View\$MeasureSpec"), requested)
    }

    @Test
    fun android_activity_inner_screen_capture_callback_resolves_by_dotted_name_candidate() {
        val requested = provider().requestedClasses(
            androidConfiguration(classes = linkedSetOf("android.app.Activity.ScreenCaptureCallback"))
        )

        assertEquals(linkedSetOf("android.app.Activity\$ScreenCaptureCallback"), requested)
    }

    @Test
    fun android_context_inner_bind_service_flags_resolves_by_dotted_name_candidate() {
        val requested = provider().requestedClasses(
            androidConfiguration(classes = linkedSetOf("android.content.Context.BindServiceFlags"))
        )

        assertEquals(linkedSetOf("android.content.Context\$BindServiceFlags"), requested)
    }

    @Test
    fun android_app_wildcard_provider_enumerates_activity_from_android_jar() {
        val packageModule = packageModuleFor("android.app.*")

        assertEquals("android.app", packageModule.moduleName)
        assertTrue("Activity" in packageModule.fields, "Expected Activity in android.app.*; fields=${packageModule.fields.keys.sorted()}")
    }

    @Test
    fun android_content_wildcard_provider_enumerates_context_from_android_jar() {
        val packageModule = packageModuleFor("android.content.*")

        assertEquals("android.content", packageModule.moduleName)
        assertTrue("Context" in packageModule.fields, "Expected Context in android.content.*; fields=${packageModule.fields.keys.sorted()}")
    }

    @Test
    fun android_view_wildcard_provider_enumerates_view_from_android_jar() {
        val packageModule = packageModuleFor("android.view.*")

        assertEquals("android.view", packageModule.moduleName)
        assertTrue("View" in packageModule.fields, "Expected View in android.view.*; fields=${packageModule.fields.keys.sorted()}")
    }

    @Test
    fun android_widget_wildcard_provider_enumerates_text_view_from_android_jar() {
        val packageModule = packageModuleFor("android.widget.*")

        assertEquals("android.widget", packageModule.moduleName)
        assertTrue("TextView" in packageModule.fields, "Expected TextView in android.widget.*; fields=${packageModule.fields.keys.sorted()}")
    }

    @Test
    fun android_wildcard_provider_skips_inner_classes_from_package_enumeration() {
        val packageModule = packageModuleFor("android.widget.*")

        assertTrue("TextView" in packageModule.fields)
        assertTrue("BufferType" !in packageModule.fields)
        // Cross-check other Android-Lua packages: nested types stay off the package surface.
        val view = packageModuleFor("android.view.*")
        assertTrue("View" in view.fields)
        assertTrue("OnClickListener" !in view.fields)
        assertTrue("MeasureSpec" !in view.fields)

        val content = packageModuleFor("android.content.*")
        assertTrue("Context" in content.fields)
        assertTrue("BindServiceFlags" !in content.fields)

        val app = packageModuleFor("android.app.*")
        assertTrue("Activity" in app.fields)
        assertTrue("ScreenCaptureCallback" !in app.fields)
    }

    @Test
    fun package_wildcard_without_android_jar_does_not_invent_framework_members() {
        // Honest empty surface when reflective classpath has no android.jar: never invent Activity/Context/View/TextView.
        val configuration = JvmWorkspaceConfiguration(
            classpathEntries = emptyList(),
            androidJar = null,
            classes = emptySet()
        )
        val providers = JvmClassModuleProvider().packageProvidersFor(
            listOf("android.widget.*", "android.view.*", "android.content.*", "android.app.*"),
            // Empty environment + non-SDK userHome so discovery cannot silently mount a host jar.
            configuration.copy(
                // reflectionClasspathEntries consults env/well-known roots when androidJar is blank;
                // force a non-existing explicit classpath-only config via empty entries and a missing jar path
                // that is not auto-selected from Downloads. Use an explicit missing jar to skip discovery.
                androidJar = "/nonexistent/android-sdk/platforms/android-35/android.jar"
            )
        )
        assertTrue(
            providers.isEmpty(),
            "Missing android.jar must not invent package providers; got paths=${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun host_android_jar_four_package_wildcards_product_lock_enumerates_fixture_classes() {
        assertAndroidJarPresent()
        val targets = listOf(
            "android.app.*" to "Activity",
            "android.content.*" to "Context",
            "android.view.*" to "View",
            "android.widget.*" to "TextView"
        )
        targets.forEach { (importTarget, simpleName) ->
            val module = packageModuleFor(importTarget)
            assertTrue(
                simpleName in module.fields,
                "Host jar ${androidJar.path}: $importTarget must include $simpleName; fields=${module.fields.keys.sorted()}"
            )
        }
    }

    private fun androidHarness(
        vararg files: Pair<String, String>,
        metadata: Map<String, String>
    ): WorkspaceSemanticHarness {
        assertAndroidJarPresent()
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = metadata,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun provider(): JvmClassModuleProvider {
        assertAndroidJarPresent()
        return JvmClassModuleProvider()
    }

    private fun androidConfiguration(
        classes: Set<String> = emptySet(),
        androluaImports: List<String> = emptyList(),
        importPrefixes: List<String> = emptyList(),
        classpathEntries: List<String> = emptyList()
    ): JvmWorkspaceConfiguration {
        assertAndroidJarPresent()
        return JvmWorkspaceConfiguration(
            classpathEntries = classpathEntries,
            androidJar = androidJar.path,
            classes = classes,
            androluaImports = androluaImports,
            importPrefixes = importPrefixes
        )
    }

    private fun providersForClasses(vararg classNames: String): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
        return provider().providersFor(androidConfiguration(classes = classNames.toCollection(linkedSetOf())))
    }

    private fun moduleFor(className: String): ModuleType {
        return providersForClasses(className).assertModule(className)
    }

    private fun packageModuleFor(importTarget: String): ModuleType {
        val providers = provider().packageProvidersFor(listOf(importTarget), androidConfiguration())
        val packageName = importTarget.removeSuffix(".*")
        val path = VirtualPath.of("__jvm__/packages/${packageName.replace('.', '/')}.lua")
        return assertNotNull(
            providers[path]?.moduleExportSurface?.moduleType,
            "Expected Android wildcard provider for $importTarget from ${androidJar.path}."
        )
    }

    private fun Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>.assertProviderPath(className: String) {
        val path = VirtualPath.of("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in keys,
            "Expected reflected provider $path for $className from ${androidJar.path}; actual paths: ${keys.map { it.value }}."
        )
    }

    private fun Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>.assertModule(className: String): ModuleType {
        val path = VirtualPath.of("__jvm__/classes/${className.replace('.', '/')}.lua")
        return assertNotNull(
            this[path]?.moduleExportSurface?.moduleType,
            "Expected reflected module for $className at $path from ${androidJar.path}."
        )
    }

    private fun ModuleType.assertClassName(binaryName: String, canonicalName: String = binaryName.replace('$', '.')) {
        val javaName = javaClassType().javaName
        assertEquals(binaryName, javaName.binaryName)
        assertEquals(canonicalName, javaName.canonicalName)
    }

    private fun ModuleType.javaClassType(): JavaClassType = javaInstanceType().classType

    private fun ModuleType.javaInstanceType(): JavaInstanceType {
        val type = assertNotNull(fields["__class"], "Expected __class field on reflected module $moduleName.")
        return type as? JavaInstanceType
            ?: error("Expected __class on reflected module $moduleName to be JavaInstanceType, was ${type.displayName}.")
    }

    private fun assertAndroidJarPresent() {
        assertTrue(
            androidJar.isFile,
            "Android platform jar required at host SDK path ${androidJar.path}. " +
                "Install Android SDK Platform 35 under ANDROID_HOME/ANDROID_SDK_ROOT or " +
                "~/Library/Android/sdk (macOS) before running Android reflection TDD tests. " +
                "Never hardcode G:/Android/Sdk."
        )
    }

    companion object {
        /**
         * Host-local android.jar for reflection TDD.
         *
         * Prefer [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] discovery, then well-known
         * macOS SDK roots and the documented host path. Never hardcodes `G:/Android/Sdk`.
         */
        private fun resolveHostAndroidJar(): File {
            val home = System.getProperty("user.home").orEmpty()
            val candidates = buildList {
                add(File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH))
                if (home.isNotBlank()) {
                    add(File(home, "Library/Android/sdk/platforms/android-35/android.jar"))
                    add(File(home, "Android/Sdk/platforms/android-35/android.jar"))
                }
                add(File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"))
                System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let {
                    add(File(it, "platforms/android-35/android.jar"))
                }
                System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let {
                    add(File(it, "platforms/android-35/android.jar"))
                }
            }
            return candidates.firstOrNull { candidate ->
                candidate.isFile &&
                    !candidate.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true)
            } ?: candidates.first()
        }
    }
}
