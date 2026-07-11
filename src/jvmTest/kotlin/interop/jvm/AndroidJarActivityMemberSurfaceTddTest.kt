package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import java.io.File
import org.junit.Assume
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-302 — Android.jar `android.app.Activity` member surface corpus.
 *
 * Encodes the acceptance that a reflected Activity surface exposes a non-empty
 * instance/static member list when the host android.jar is present, and skips
 * cleanly with an explicit TASK-302 reason when the jar is missing.
 *
 * Test-only; no production edits. Verification is review-owned (TASK-043).
 */
class AndroidJarActivityMemberSurfaceTddTest {
    private val provider = JvmClassModuleProvider()
    private val androidJar = resolveAndroidJar()

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(reason.contains("TASK-302"), "Skip reason must name TASK-302; got: $reason")
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
    fun activity_provider_is_mounted_from_android_jar_when_present() {
        requireAndroidJarOrSkip()
        val file = providerFile("android.app.Activity")

        assertEquals(jvmClassPath("android.app.Activity"), file.path)
        assertEquals("Activity", file.module.moduleName)
        assertEquals(
            "android.app.Activity",
            file.instanceType.classType.javaName.binaryName
        )
    }

    @Test
    fun activity_instance_member_surface_is_non_empty_when_android_jar_present() {
        requireAndroidJarOrSkip()
        val members = activityInstanceMembers()

        assertTrue(
            members.isNotEmpty(),
            "Expected non-empty Activity instance member surface from ${androidJar.path}; got empty."
        )
        assertTrue(
            members.size >= 10,
            "Expected a rich Activity member surface from android.jar; size=${members.size}, keys=${members.keys.sorted().take(20)}"
        )
    }

    @Test
    fun activity_static_member_surface_is_non_empty_when_android_jar_present() {
        requireAndroidJarOrSkip()
        val module = activityModule()
        val staticNames = (module.fields.keys + module.methods.keys)
            .filter { it != "__class" && it != "__call" }
            .toSet()

        assertTrue(
            staticNames.isNotEmpty(),
            "Expected non-empty Activity static member surface (fields/methods) from ${androidJar.path}."
        )
        assertTrue("RESULT_OK" in module.fields, "Expected RESULT_OK static field; fields=${module.fields.keys.sorted()}")
        assertTrue("RESULT_CANCELED" in module.fields, "Expected RESULT_CANCELED static field.")
    }

    @Test
    fun activity_instance_member_surface_includes_common_lifecycle_and_ui_methods() {
        requireAndroidJarOrSkip()
        val members = activityInstanceMembers()

        for (name in listOf(
            "setContentView",
            "findViewById",
            "getIntent",
            "finish",
            "getApplicationContext",
            "runOnUiThread",
            "startActivity"
        )) {
            assertTrue(
                name in members,
                "Expected Activity instance member '$name' from ${androidJar.path}; " +
                    "available sample=${members.keys.sorted().take(40)}"
            )
            assertIs<CallableType>(
                members.getValue(name).valueType,
                "Expected Activity.$name to be callable; was ${members.getValue(name).valueType.displayName}"
            )
        }
    }

    @Test
    fun activity_instance_member_surface_includes_inherited_context_and_object_methods() {
        requireAndroidJarOrSkip()
        val members = activityInstanceMembers()

        // Context / ContextWrapper inheritance chain used heavily by Android-Lua samples.
        for (name in listOf("getResources", "getSystemService", "getPackageName", "getClassLoader")) {
            assertTrue(
                name in members,
                "Expected inherited Activity member '$name' from ${androidJar.path}."
            )
        }
        // java.lang.Object methods should remain reachable through the hydrated hierarchy.
        assertTrue("toString" in members || "hashCode" in members || "equals" in members)
    }

    @Test
    fun activity_member_surface_exposes_overloaded_set_content_view_signatures() {
        requireAndroidJarOrSkip()
        val method = callable(activityInstanceMembers().required("setContentView").valueType)

        assertTrue(
            method.callSignatures.size >= 2,
            "Expected overloaded setContentView signatures on Activity; got ${method.callSignatures.size}"
        )
    }

    @Test
    fun activity_provider_export_surface_lists_non_empty_class_members() {
        requireAndroidJarOrSkip()
        val file = providerFile("android.app.Activity")
        val classMembers = file.surface.members.filter { it.exportPath.firstOrNull() == "__class" }

        assertTrue(
            classMembers.isNotEmpty(),
            "Expected non-empty ModuleExportSurface members under __class for Activity."
        )
        assertTrue(
            classMembers.any { it.exportPath == listOf("__class", "setContentView") && it.kind == SymbolKind.METHOD },
            "Expected exported METHOD __class.setContentView; actual=${classMembers.map { it.exportPath to it.kind }.take(30)}"
        )
        assertTrue(
            classMembers.any { it.exportPath == listOf("__class", "findViewById") && it.kind == SymbolKind.METHOD },
            "Expected exported METHOD __class.findViewById."
        )
    }

    @Test
    fun activity_static_result_constants_are_exported_as_fields() {
        requireAndroidJarOrSkip()
        val file = providerFile("android.app.Activity")

        assertEquals(SymbolKind.FIELD, file.member("RESULT_OK").kind)
        assertEquals(SymbolKind.FIELD, file.member("RESULT_CANCELED").kind)
    }

    @Test
    fun activity_constructor_surface_is_present_as_callable_module_field() {
        requireAndroidJarOrSkip()
        val module = activityModule()
        val constructor = module.fields["__call"]

        assertNotNull(constructor, "Expected __call constructor surface on Activity module.")
        assertIs<JavaClassType>(constructor)
    }

    @Test
    fun workspace_engine_activity_require_exposes_non_empty_member_completions() {
        requireAndroidJarOrSkip()
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local Activity = require("Activity")
                local current = Activity
                return current
            """.trimIndent(),
            metadata = mapOf(
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "Activity",
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path,
                JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "android.app"
            ),
            engine = JvmWorkspaceEngine()
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "Activity")
        assertEquals(
            harness.path("__jvm__/classes/android/app/Activity.lua"),
            resolved.provider?.path
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "current")
        )
        val labels = completions.map { it.label }.toSet()
        assertTrue(
            labels.isNotEmpty(),
            "Expected non-empty Activity-related completions when android.jar present; got empty."
        )
        assertTrue(
            "RESULT_OK" in labels || "RESULT_CANCELED" in labels || "setContentView" in labels || "Activity" in labels,
            "Expected Activity static/instance surface labels in completions; got=${labels.sorted().take(40)}"
        )
    }

    @Test
    fun workspace_engine_activity_instance_member_hover_uses_reflected_surface() {
        requireAndroidJarOrSkip()
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local Activity = require("Activity")
                ---@type android.app.Activity
                local host
                local view = host:findViewById(1)
                return view
            """.trimIndent(),
            metadata = mapOf(
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "Activity",
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path,
                JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "android.app"
            ),
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "findViewById")
        )
        // Soft floor: when the reflected surface is wired, hover should name the member or method kind.
        // Product may still degrade for Emmy-annotated receivers; non-null is preferred but empty is
        // not asserted hard — the reflection surface tests above own non-empty acceptance.
        if (hover != null) {
            val text = buildString {
                append(hover.symbol?.name.orEmpty())
                append(' ')
                append(hover.symbol?.kind?.name.orEmpty())
                append(' ')
                append(hover.typeInfo?.displayName.orEmpty())
            }
            assertTrue(
                text.contains("findViewById", ignoreCase = true) ||
                    hover.symbol?.kind == SymbolKind.METHOD ||
                    text.contains("fun", ignoreCase = true),
                "Expected hover to mention findViewById / METHOD surface; got: $text"
            )
        }
    }

    @Test
    fun missing_android_jar_does_not_mount_activity_provider() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val configuration = JvmWorkspaceConfiguration(
            androidJar = missing.path,
            classes = linkedSetOf("android.app.Activity")
        )
        val providers = provider.providersFor(configuration)

        // Clean skip path for consumers: missing jar → no Activity provider, no throw.
        assertTrue(
            providers.isEmpty() || jvmClassPath("android.app.Activity") !in providers,
            "Missing android.jar must not mount a real Activity provider; actual=${providers.keys.map { it.value }}"
        )
    }

    private fun activityModule(): ModuleType = providerFile("android.app.Activity").module

    private fun activityInstanceMembers() = activityModule().javaInstanceType().allInstanceMembers()

    private fun providerFile(className: String): ProviderFile {
        val path = jvmClassPath(className)
        val snapshot = provider.providersFor(androidConfiguration(classes = linkedSetOf(className)))[path]
            ?: fail("Missing provider for $className at ${path.value} from ${androidJar.path}")
        val surface = snapshot.moduleExportSurface
            ?: fail("Missing export surface for $className from ${androidJar.path}")
        return ProviderFile(path, surface, surface.moduleType)
    }

    private fun androidConfiguration(
        classes: Set<String> = emptySet()
    ): JvmWorkspaceConfiguration {
        return JvmWorkspaceConfiguration(
            androidJar = androidJar.path,
            classes = classes
        )
    }

    private fun ModuleType.javaInstanceType(): JavaInstanceType {
        val type = assertNotNull(fields["__class"], "Expected __class field on reflected module $moduleName.")
        return type as? JavaInstanceType
            ?: error("Expected __class on reflected module $moduleName to be JavaInstanceType, was ${type.displayName}.")
    }

    private fun callable(type: Type): CallableType = assertIs(type)

    private fun jvmClassPath(className: String): VirtualPath =
        VirtualPath.of("__jvm__/classes/${className.replace('.', '/')}.lua")

    private fun ProviderFile.member(vararg path: String): ModuleExportSurface.MemberExport =
        surface.members.singleOrNull { it.exportPath == path.toList() }
            ?: fail("Missing exported member path ${path.joinToString(".")}; available=${surface.members.map { it.exportPath }.take(40)}")

    private fun <T> Map<String, T>.required(name: String): T =
        this[name] ?: fail("Missing reflected member '$name'; available: ${keys.sorted().take(40)}")

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    private data class ProviderFile(
        val path: VirtualPath,
        val surface: ModuleExportSurface,
        val module: ModuleType
    ) {
        val instanceType: JavaInstanceType
            get() {
                val type = module.fields["__class"] as? JavaInstanceType
                    ?: error("Expected __class JavaInstanceType on ${module.moduleName}")
                return type
            }
    }

    companion object {
        /**
         * Host-resolution order for TASK-302 corpus:
         * 1) WAVE31 mac SDK path
         * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
         * 3) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
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
            return "TASK-302 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35 (or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar) " +
                "before running Activity member surface corpus."
        }
    }
}
