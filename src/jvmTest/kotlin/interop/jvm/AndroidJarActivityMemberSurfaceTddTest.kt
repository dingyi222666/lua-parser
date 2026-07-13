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
 * TASK-527 / TASK-302 — Android.jar `android.app.Activity` member surface corpus.
 *
 * Encodes the acceptance that a reflected Activity surface exposes a non-empty
 * instance/static member list when the host android.jar is present, and skips
 * cleanly with an explicit TASK-527/TASK-302 reason when the jar is missing.
 *
 * Host android.jar resolution (never hard-requires G:/; Downloads only via explicit metadata):
 * 1) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
 * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
 * 3) mac well-known `~/Library/Android/sdk/platforms/android-35/android.jar`
 *
 * Does not invent framework members when the jar is absent.
 * Verification is review-owned (TASK-043); workers must not run Gradle.
 */
class AndroidJarActivityMemberSurfaceTddTest {
    private val provider = JvmClassModuleProvider()
    private val androidJar = resolveAndroidJar()

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(
            reason.contains("TASK-302") || reason.contains("TASK-527"),
            "Skip reason must name TASK-302 or TASK-527; got: $reason"
        )
        assertTrue(reason.contains("android.jar"), "Skip reason must mention android.jar; got: $reason")
        assertTrue(reason.contains(missing.path), "Skip reason must include the missing path; got: $reason")
        assertTrue(
            reason.contains("ANDROID_HOME") || reason.contains("ANDROID_SDK_ROOT") || reason.contains("Install"),
            "Skip reason must tell the host how to recover; got: $reason"
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
         * Host-resolution order for TASK-527 / TASK-302 corpus:
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
            return "TASK-527/TASK-302 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35 (or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar) " +
                "before running Activity member surface corpus. Suites skip cleanly only when the jar " +
                "is truly absent; framework members are never invented."
        }
    }
}
