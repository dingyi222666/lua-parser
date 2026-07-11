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
 * TASK-527 / TASK-371 — Android.jar `android.content.Intent` member surface corpus.
 *
 * Encodes the acceptance that a reflected Intent surface exposes a non-empty
 * instance/static member list when the host android.jar is present, and degrades
 * without throw (explicit TASK-527/TASK-371 skip reason) when the jar is missing.
 *
 * Host android.jar resolution (never hard-requires G:/; Downloads only via explicit metadata):
 * 1) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
 * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
 * 3) mac well-known `~/Library/Android/sdk/platforms/android-35/android.jar`
 *
 * Does not invent framework members when the jar is absent.
 * Verification is review-owned (TASK-043); workers must not run Gradle.
 */
class AndroidJarIntentMemberSurfaceTddTest {
    private val provider = JvmClassModuleProvider()
    private val androidJar = resolveAndroidJar()

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(
            reason.contains("TASK-371") || reason.contains("TASK-527"),
            "Skip reason must name TASK-371 or TASK-527; got: $reason"
        )
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
    fun intent_provider_is_mounted_from_android_jar_when_present() {
        requireAndroidJarOrSkip()
        val file = providerFile("android.content.Intent")

        assertEquals(jvmClassPath("android.content.Intent"), file.path)
        assertEquals("Intent", file.module.moduleName)
        assertEquals(
            "android.content.Intent",
            file.instanceType.classType.javaName.binaryName
        )
    }

    @Test
    fun intent_instance_member_surface_is_non_empty_when_android_jar_present() {
        requireAndroidJarOrSkip()
        val members = intentInstanceMembers()

        assertTrue(
            members.isNotEmpty(),
            "Expected non-empty Intent instance member surface from ${androidJar.path}; got empty."
        )
        assertTrue(
            members.size >= 10,
            "Expected a rich Intent member surface from android.jar; size=${members.size}, keys=${members.keys.sorted().take(20)}"
        )
    }

    @Test
    fun intent_static_member_surface_is_non_empty_when_android_jar_present() {
        requireAndroidJarOrSkip()
        val module = intentModule()
        val staticNames = (module.fields.keys + module.methods.keys)
            .filter { it != "__class" && it != "__call" }
            .toSet()

        assertTrue(
            staticNames.isNotEmpty(),
            "Expected non-empty Intent static member surface (fields/methods) from ${androidJar.path}."
        )
        assertTrue(
            "ACTION_VIEW" in module.fields || "ACTION_MAIN" in module.fields || "ACTION_SEND" in module.fields,
            "Expected ACTION_VIEW / ACTION_MAIN / ACTION_SEND static field; fields=${module.fields.keys.sorted().take(40)}"
        )
        assertTrue(
            "FLAG_ACTIVITY_NEW_TASK" in module.fields ||
                "FLAG_ACTIVITY_CLEAR_TOP" in module.fields ||
                "CATEGORY_DEFAULT" in module.fields,
            "Expected FLAG_ACTIVITY_* or CATEGORY_DEFAULT static field; fields=${module.fields.keys.sorted().take(40)}"
        )
    }

    @Test
    fun intent_instance_member_surface_includes_common_action_data_and_extra_methods() {
        requireAndroidJarOrSkip()
        val members = intentInstanceMembers()

        for (name in listOf(
            "getAction",
            "setAction",
            "getData",
            "setData",
            "getType",
            "setType",
            "getExtras",
            "putExtra",
            "hasExtra",
            "getStringExtra",
            "getIntExtra",
            "getBooleanExtra",
            "getComponent",
            "setComponent",
            "setClassName",
            "setPackage",
            "addFlags",
            "setFlags",
            "getFlags",
            "addCategory"
        )) {
            assertTrue(
                name in members,
                "Expected Intent instance member '$name' from ${androidJar.path}; " +
                    "available sample=${members.keys.sorted().take(40)}"
            )
            assertIs<CallableType>(
                members.getValue(name).valueType,
                "Expected Intent.$name to be callable; was ${members.getValue(name).valueType.displayName}"
            )
        }
    }

    @Test
    fun intent_instance_member_surface_includes_uri_and_parcelable_helpers() {
        requireAndroidJarOrSkip()
        val members = intentInstanceMembers()

        for (name in listOf(
            "setDataAndType",
            "getScheme",
            "getDataString",
            "resolveActivity",
            "getParcelableExtra",
            "putExtras",
            "cloneFilter"
        )) {
            assertTrue(
                name in members,
                "Expected Intent instance member '$name' from ${androidJar.path}; " +
                    "available sample=${members.keys.sorted().take(40)}"
            )
        }
    }

    @Test
    fun intent_instance_member_surface_includes_object_methods() {
        requireAndroidJarOrSkip()
        val members = intentInstanceMembers()

        // java.lang.Object methods should remain reachable through the hydrated hierarchy.
        assertTrue(
            "toString" in members || "hashCode" in members || "equals" in members,
            "Expected Object methods on Intent instance surface; sample=${members.keys.sorted().take(40)}"
        )
    }

    @Test
    fun intent_member_surface_exposes_overloaded_put_extra_signatures() {
        requireAndroidJarOrSkip()
        val method = callable(intentInstanceMembers().required("putExtra").valueType)

        // Intent has many putExtra overloads (String/int/boolean/Parcelable/...).
        assertTrue(
            method.callSignatures.size >= 2,
            "Expected overloaded putExtra signatures on Intent; got ${method.callSignatures.size}"
        )
    }

    @Test
    fun intent_provider_export_surface_lists_non_empty_class_members() {
        requireAndroidJarOrSkip()
        val file = providerFile("android.content.Intent")
        val classMembers = file.surface.members.filter { it.exportPath.firstOrNull() == "__class" }

        assertTrue(
            classMembers.isNotEmpty(),
            "Expected non-empty ModuleExportSurface members under __class for Intent."
        )
        assertTrue(
            classMembers.any { it.exportPath == listOf("__class", "setAction") && it.kind == SymbolKind.METHOD },
            "Expected exported METHOD __class.setAction; actual=${classMembers.map { it.exportPath to it.kind }.take(30)}"
        )
        assertTrue(
            classMembers.any { it.exportPath == listOf("__class", "putExtra") && it.kind == SymbolKind.METHOD },
            "Expected exported METHOD __class.putExtra."
        )
    }

    @Test
    fun intent_static_action_and_flag_constants_are_exported_as_fields() {
        requireAndroidJarOrSkip()
        val file = providerFile("android.content.Intent")

        val actionField = sequenceOf("ACTION_VIEW", "ACTION_MAIN", "ACTION_SEND")
            .mapNotNull { name ->
                runCatching { file.member(name) }.getOrNull()?.let { name to it }
            }
            .firstOrNull()
        assertNotNull(
            actionField,
            "Expected at least one exported ACTION_* FIELD among ACTION_VIEW / ACTION_MAIN / ACTION_SEND."
        )
        assertEquals(SymbolKind.FIELD, actionField.second.kind)

        val flagOrCategory = sequenceOf(
            "FLAG_ACTIVITY_NEW_TASK",
            "FLAG_ACTIVITY_CLEAR_TOP",
            "CATEGORY_DEFAULT",
            "CATEGORY_LAUNCHER"
        ).mapNotNull { name ->
            runCatching { file.member(name) }.getOrNull()?.let { name to it }
        }.firstOrNull()
        assertNotNull(
            flagOrCategory,
            "Expected at least one FLAG_ACTIVITY_* / CATEGORY_* FIELD export."
        )
        assertEquals(SymbolKind.FIELD, flagOrCategory.second.kind)
    }

    @Test
    fun intent_constructor_surface_is_present_as_callable_module_field() {
        requireAndroidJarOrSkip()
        val module = intentModule()
        val constructor = module.fields["__call"]

        assertNotNull(constructor, "Expected __call constructor surface on Intent module.")
        assertIs<JavaClassType>(constructor)
    }

    @Test
    fun workspace_engine_intent_require_exposes_non_empty_member_completions() {
        requireAndroidJarOrSkip()
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local Intent = require("Intent")
                local current = Intent
                return current
            """.trimIndent(),
            metadata = mapOf(
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "Intent",
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path,
                JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "android.content"
            ),
            engine = JvmWorkspaceEngine()
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "Intent")
        assertEquals(
            harness.path("__jvm__/classes/android/content/Intent.lua"),
            resolved.provider?.path
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "current")
        )
        val labels = completions.map { it.label }.toSet()
        assertTrue(
            labels.isNotEmpty(),
            "Expected non-empty Intent-related completions when android.jar present; got empty."
        )
        assertTrue(
            "ACTION_VIEW" in labels ||
                "ACTION_MAIN" in labels ||
                "putExtra" in labels ||
                "setAction" in labels ||
                "Intent" in labels,
            "Expected Intent static/instance surface labels in completions; got=${labels.sorted().take(40)}"
        )
    }

    @Test
    fun workspace_engine_intent_instance_member_hover_uses_reflected_surface() {
        requireAndroidJarOrSkip()
        // Match the JVM instance-member corpus shape: construct via luajava.newInstance so the
        // receiver is a reflected Java instance, then hover the member identifier. Emmy ---@type
        // receivers alone can degrade hover away from METHOD.
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local host = luajava.newInstance("android.content.Intent")
                local current = host.setAction
                return current
            """.trimIndent(),
            metadata = mapOf(
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "Intent",
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path,
                JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "android.content"
            ),
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "setAction")
        )
        assertNotNull(
            hover,
            "Expected hover on Intent instance member setAction when android.jar present."
        )
        val text = buildString {
            append(hover.symbol?.name.orEmpty())
            append(' ')
            append(hover.symbol?.kind?.name.orEmpty())
            append(' ')
            append(hover.typeInfo?.displayName.orEmpty())
        }
        assertTrue(
            text.contains("setAction", ignoreCase = true) ||
                hover.symbol?.kind == SymbolKind.METHOD ||
                text.contains("fun", ignoreCase = true),
            "Expected hover to mention setAction / METHOD surface; got: $text"
        )
    }

    @Test
    fun missing_android_jar_does_not_mount_intent_provider() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val configuration = JvmWorkspaceConfiguration(
            androidJar = missing.path,
            classes = linkedSetOf("android.content.Intent")
        )
        val providers = provider.providersFor(configuration)

        // Clean skip path for consumers: missing jar → no Intent provider, no throw.
        assertTrue(
            providers.isEmpty() || jvmClassPath("android.content.Intent") !in providers,
            "Missing android.jar must not mount a real Intent provider; actual=${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun missing_android_jar_providers_for_does_not_throw() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val configuration = JvmWorkspaceConfiguration(
            androidJar = missing.path,
            classes = linkedSetOf("android.content.Intent")
        )

        val providers = runCatching { provider.providersFor(configuration) }
            .getOrElse { error ->
                fail("Absent android.jar must degrade without throw; got ${error::class.simpleName}: ${error.message}")
            }

        assertTrue(
            providers.isEmpty() || jvmClassPath("android.content.Intent") !in providers,
            "Absent jar must not surface an Intent provider; actual=${providers.keys.map { it.value }}"
        )
    }

    private fun intentModule(): ModuleType = providerFile("android.content.Intent").module

    private fun intentInstanceMembers() = intentModule().javaInstanceType().allInstanceMembers()

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
         * Host-resolution order for TASK-527 / TASK-371 corpus:
         * 1) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
         * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
         * 3) mac well-known `~/Library/Android/sdk/platforms/android-35/android.jar`
         *
         * Explicit Downloads jars are not auto-selected (metadata only). Never hardcodes
         * or hard-requires Windows-only G:/Android/Sdk paths.
         */
        private fun resolveAndroidJar(): File {
            val candidates = linkedSetOf<File>()
            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env -> System.getenv(env)?.trim()?.takeIf(String::isNotEmpty) }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }
            candidates += File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
            candidates += File(
                System.getProperty("user.home"),
                "Library/Android/sdk/platforms/android-35/android.jar"
            )
            // Prefer an existing jar; never invent members when all candidates are missing.
            return candidates.firstOrNull { it.isFile } ?: candidates.first()
        }

        internal fun missingAndroidJarSkipReason(androidJar: File): String {
            return "TASK-527/TASK-371 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35 (or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar) " +
                "before running Intent member surface corpus. Suites skip cleanly only when the jar " +
                "is truly absent; framework members are never invented."
        }
    }
}
