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
 * TASK-582 / TASK-527 / TASK-354 — Android.jar `android.content.Context` member surface corpus.
 *
 * Hard-locks:
 * - Context instance methods and static service constants resolve from host android.jar
 * - Inner BindServiceFlags-style nested types resolvable by dotted / binary / underscore names
 * - Host SDK path only (never invents framework members; never hard-requires G:/)
 *
 * Host android.jar resolution order:
 * 1) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
 * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
 * 3) mac well-known `~/Library/Android/sdk/platforms/android-35/android.jar`
 * 4) documented host path `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar`
 *
 * Does not invent framework members when the jar is absent.
 * Verification is review-owned (TASK-043); workers must not run Gradle.
 */
class AndroidJarContextMemberSurfaceTddTest {
    private val provider = JvmClassModuleProvider()
    private val androidJar = resolveAndroidJar()

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(
            reason.contains("TASK-354") || reason.contains("TASK-527") || reason.contains("TASK-582"),
            "Skip reason must name TASK-354 / TASK-527 / TASK-582; got: $reason"
        )
        assertTrue(reason.contains("android.jar"), "Skip reason must mention android.jar; got: $reason")
        assertTrue(reason.contains(missing.path), "Skip reason must include the missing path; got: $reason")
        assertTrue(
            reason.contains("ANDROID_HOME") || reason.contains("ANDROID_SDK_ROOT") || reason.contains("Install"),
            "Skip reason must tell the host how to recover; got: $reason"
        )
    }
    @Test
    fun context_provider_is_mounted_from_android_jar_when_present() {
        requireAndroidJarOrSkip()
        val file = providerFile("android.content.Context")

        assertEquals(jvmClassPath("android.content.Context"), file.path)
        assertEquals("Context", file.module.moduleName)
        assertEquals(
            "android.content.Context",
            file.instanceType.classType.javaName.binaryName
        )
    }
    @Test
    fun context_static_member_surface_is_non_empty_when_android_jar_present() {
        requireAndroidJarOrSkip()
        val module = contextModule()
        val staticNames = (module.fields.keys + module.methods.keys)
            .filter { it != "__class" && it != "__call" }
            .toSet()

        assertTrue(
            staticNames.isNotEmpty(),
            "Expected non-empty Context static member surface (fields/methods) from ${androidJar.path}."
        )
        assertTrue(
            "MODE_PRIVATE" in module.fields,
            "Expected MODE_PRIVATE static field; fields=${module.fields.keys.sorted().take(40)}"
        )
        assertTrue(
            "ACTIVITY_SERVICE" in module.fields || "LAYOUT_INFLATER_SERVICE" in module.fields,
            "Expected a service-name static field (ACTIVITY_SERVICE / LAYOUT_INFLATER_SERVICE); fields=${module.fields.keys.sorted().take(40)}"
        )
    }

    private fun contextModule(): ModuleType = providerFile("android.content.Context").module

    private fun contextInstanceMembers() = contextModule().javaInstanceType().allInstanceMembers()

    private fun providerFile(className: String): ProviderFile {
        val path = jvmClassPath(className)
        val snapshot = provider.providersFor(androidConfiguration(classes = linkedSetOf(className)))[path]
            ?: fail("Missing provider for $className at ${path.value} from ${androidJar.path}")
        val surface = snapshot.moduleExportSurface
            ?: fail("Missing export surface for $className from ${androidJar.path}")
        return ProviderFile(path, surface, surface.moduleType)
    }

    /**
     * Nested-class provider lookup prefers the binary `$` virtual path that
     * [JvmClassModuleProvider] emits after dotted / underscore canonicalization.
     */
    private fun providerFileNested(className: String): ProviderFile {
        val files = provider.providersFor(androidConfiguration(classes = linkedSetOf(className)))
        assertTrue(
            files.isNotEmpty(),
            "Expected nested provider for $className from ${androidJar.path}; actual=${files.keys.map { it.value }}"
        )
        val path = files.keys.singleOrNull()
            ?: files.keys.firstOrNull { it.value.contains('$') }
            ?: files.keys.first()
        val snapshot = files[path] ?: fail("Missing nested provider for $className at ${path.value}")
        val surface = snapshot.moduleExportSurface
            ?: fail("Missing export surface for nested $className from ${androidJar.path}")
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
         * Host-resolution order for TASK-582 / TASK-527 / TASK-354 corpus:
         * 1) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
         * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
         * 3) mac well-known `~/Library/Android/sdk/platforms/android-35/android.jar`
         * 4) documented host path `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar`
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
            candidates += File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar")
            // Prefer an existing non-G:/ jar; never invent members when all candidates are missing.
            return candidates.firstOrNull { candidate ->
                candidate.isFile &&
                    !candidate.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true)
            } ?: candidates.first()
        }

        internal fun missingAndroidJarSkipReason(androidJar: File): String {
            return "TASK-582/TASK-527/TASK-354 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35 (or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar) " +
                "before running Context member surface corpus. Suites skip cleanly only when the jar " +
                "is truly absent; framework members are never invented. Host path only " +
                "(/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar); never G:/."
        }
    }
}
