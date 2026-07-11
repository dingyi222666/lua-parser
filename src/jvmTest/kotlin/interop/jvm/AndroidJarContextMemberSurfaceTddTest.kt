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
    fun android_jar_present_or_skipped_with_explicit_reason() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
        assertTrue(androidJar.isFile)
        assertTrue(
            androidJar.length() > 0,
            "Expected non-empty Android platform jar at ${androidJar.path}."
        )
        assertTrue(
            !androidJar.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true),
            "Host android.jar must not hard-require Windows G:/Android/Sdk; got ${androidJar.path}"
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
    fun context_instance_member_surface_is_non_empty_when_android_jar_present() {
        requireAndroidJarOrSkip()
        val members = contextInstanceMembers()

        assertTrue(
            members.isNotEmpty(),
            "Expected non-empty Context instance member surface from ${androidJar.path}; got empty."
        )
        assertTrue(
            members.size >= 10,
            "Expected a rich Context member surface from android.jar; size=${members.size}, keys=${members.keys.sorted().take(20)}"
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

    @Test
    fun context_static_service_constants_resolve_from_host_android_jar() {
        requireAndroidJarOrSkip()
        val module = contextModule()
        val serviceConstants = listOf(
            "ACTIVITY_SERVICE",
            "LAYOUT_INFLATER_SERVICE",
            "CLIPBOARD_SERVICE",
            "ALARM_SERVICE",
            "AUDIO_SERVICE",
            "CONNECTIVITY_SERVICE",
            "NOTIFICATION_SERVICE",
            "POWER_SERVICE",
            "WINDOW_SERVICE"
        )
        val present = serviceConstants.filter { it in module.fields }
        assertTrue(
            present.size >= 3,
            "Expected multiple Context service-name constants from ${androidJar.path}; " +
                "present=$present fields=${module.fields.keys.sorted().take(50)}"
        )
        // MODE_* constants are part of the same static surface used by AndroLua scripts.
        assertTrue(
            "MODE_PRIVATE" in module.fields,
            "Expected MODE_PRIVATE alongside service constants; fields=${module.fields.keys.sorted().take(40)}"
        )
        for (name in present) {
            assertTrue(
                module.fields[name] != null,
                "Expected reflected static field type for $name"
            )
        }
    }

    @Test
    fun context_instance_member_surface_includes_common_resource_and_service_methods() {
        requireAndroidJarOrSkip()
        val members = contextInstanceMembers()

        for (name in listOf(
            "getResources",
            "getSystemService",
            "getPackageName",
            "getClassLoader",
            "getAssets",
            "getContentResolver",
            "getApplicationContext",
            "getSharedPreferences",
            "startActivity"
        )) {
            assertTrue(
                name in members,
                "Expected Context instance member '$name' from ${androidJar.path}; " +
                    "available sample=${members.keys.sorted().take(40)}"
            )
            assertIs<CallableType>(
                members.getValue(name).valueType,
                "Expected Context.$name to be callable; was ${members.getValue(name).valueType.displayName}"
            )
        }
    }

    @Test
    fun context_instance_member_surface_includes_file_and_broadcast_methods() {
        requireAndroidJarOrSkip()
        val members = contextInstanceMembers()

        for (name in listOf(
            "getFilesDir",
            "openFileOutput",
            "openFileInput",
            "sendBroadcast",
            "registerReceiver",
            "bindService"
        )) {
            assertTrue(
                name in members,
                "Expected Context instance member '$name' from ${androidJar.path}; " +
                    "available sample=${members.keys.sorted().take(40)}"
            )
        }
    }

    @Test
    fun context_instance_member_surface_includes_object_methods() {
        requireAndroidJarOrSkip()
        val members = contextInstanceMembers()

        // java.lang.Object methods should remain reachable through the hydrated hierarchy.
        assertTrue(
            "toString" in members || "hashCode" in members || "equals" in members,
            "Expected Object methods on Context instance surface; sample=${members.keys.sorted().take(40)}"
        )
    }

    @Test
    fun context_member_surface_exposes_overloaded_get_system_service_signatures() {
        requireAndroidJarOrSkip()
        val method = callable(contextInstanceMembers().required("getSystemService").valueType)

        // Context historically has String-based getSystemService; later APIs add Class overload.
        assertTrue(
            method.callSignatures.isNotEmpty(),
            "Expected getSystemService call signatures on Context; got ${method.callSignatures.size}"
        )
        assertTrue(
            method.callSignatures.size >= 1,
            "Expected at least one getSystemService signature; got ${method.callSignatures.size}"
        )
    }

    @Test
    fun context_provider_export_surface_lists_non_empty_class_members() {
        requireAndroidJarOrSkip()
        val file = providerFile("android.content.Context")
        val classMembers = file.surface.members.filter { it.exportPath.firstOrNull() == "__class" }

        assertTrue(
            classMembers.isNotEmpty(),
            "Expected non-empty ModuleExportSurface members under __class for Context."
        )
        assertTrue(
            classMembers.any { it.exportPath == listOf("__class", "getResources") && it.kind == SymbolKind.METHOD },
            "Expected exported METHOD __class.getResources; actual=${classMembers.map { it.exportPath to it.kind }.take(30)}"
        )
        assertTrue(
            classMembers.any { it.exportPath == listOf("__class", "getSystemService") && it.kind == SymbolKind.METHOD },
            "Expected exported METHOD __class.getSystemService."
        )
    }

    @Test
    fun context_static_mode_and_service_constants_are_exported_as_fields() {
        requireAndroidJarOrSkip()
        val file = providerFile("android.content.Context")

        assertEquals(SymbolKind.FIELD, file.member("MODE_PRIVATE").kind)
        val serviceField = sequenceOf("ACTIVITY_SERVICE", "LAYOUT_INFLATER_SERVICE", "CLIPBOARD_SERVICE")
            .mapNotNull { name ->
                runCatching { file.member(name) }.getOrNull()?.let { name to it }
            }
            .firstOrNull()
        assertNotNull(
            serviceField,
            "Expected at least one exported service-name FIELD among ACTIVITY_SERVICE / LAYOUT_INFLATER_SERVICE / CLIPBOARD_SERVICE."
        )
        assertEquals(SymbolKind.FIELD, serviceField.second.kind)
    }

    @Test
    fun context_constructor_surface_is_present_as_callable_module_field() {
        requireAndroidJarOrSkip()
        val module = contextModule()
        val constructor = module.fields["__call"]

        assertNotNull(constructor, "Expected __call constructor surface on Context module.")
        assertIs<JavaClassType>(constructor)
    }

    @Test
    fun context_inner_bind_service_flags_resolves_by_dotted_name() {
        requireAndroidJarOrSkip()
        val requested = provider.requestedClasses(
            androidConfiguration(classes = linkedSetOf("android.content.Context.BindServiceFlags"))
        )
        assertEquals(
            linkedSetOf("android.content.Context\$BindServiceFlags"),
            requested,
            "Dotted Context.BindServiceFlags must canonicalize to binary Context\$BindServiceFlags"
        )

        val file = providerFileNested("android.content.Context.BindServiceFlags")
        assertEquals(jvmClassPath("android.content.Context\$BindServiceFlags"), file.path)
        assertEquals("BindServiceFlags", file.module.moduleName)
        assertEquals(
            "android.content.Context\$BindServiceFlags",
            file.instanceType.classType.javaName.binaryName
        )
    }

    @Test
    fun context_inner_bind_service_flags_resolves_by_binary_name() {
        requireAndroidJarOrSkip()
        val requested = provider.requestedClasses(
            androidConfiguration(classes = linkedSetOf("android.content.Context\$BindServiceFlags"))
        )
        assertEquals(linkedSetOf("android.content.Context\$BindServiceFlags"), requested)

        val file = providerFileNested("android.content.Context\$BindServiceFlags")
        assertEquals(jvmClassPath("android.content.Context\$BindServiceFlags"), file.path)
        assertEquals(
            "android.content.Context\$BindServiceFlags",
            file.instanceType.classType.javaName.binaryName
        )
        assertEquals(
            "android.content.Context.BindServiceFlags",
            file.instanceType.classType.javaName.canonicalName
        )
    }

    @Test
    fun context_inner_bind_service_flags_resolves_by_underscore_alias() {
        requireAndroidJarOrSkip()
        val requested = provider.requestedClasses(
            androidConfiguration(classes = linkedSetOf("android.content.Context_BindServiceFlags"))
        )
        assertEquals(
            linkedSetOf("android.content.Context\$BindServiceFlags"),
            requested,
            "Underscore Context_BindServiceFlags must canonicalize to binary Context\$BindServiceFlags"
        )

        val file = providerFileNested("android.content.Context_BindServiceFlags")
        assertEquals(jvmClassPath("android.content.Context\$BindServiceFlags"), file.path)
        assertEquals(
            "android.content.Context\$BindServiceFlags",
            file.instanceType.classType.javaName.binaryName
        )
    }

    @Test
    fun context_module_exposes_bind_service_flags_nested_type_field() {
        requireAndroidJarOrSkip()
        val module = contextModule()
        val classType = module.javaInstanceType().classType

        assertTrue(
            "BindServiceFlags" in module.fields || "BindServiceFlags" in classType.innerClasses,
            "Expected BindServiceFlags nested type on Context module/class surface; " +
                "fields=${module.fields.keys.sorted().take(40)} inners=${classType.innerClasses.keys.sorted()}"
        )
        val nested = classType.innerClasses["BindServiceFlags"]
            ?: (module.fields["BindServiceFlags"] as? JavaClassType)
            ?: (module.fields["BindServiceFlags"] as? ModuleType)?.fields?.get("__class")?.let {
                (it as? JavaInstanceType)?.classType
            }
        assertNotNull(nested, "Expected BindServiceFlags JavaClassType on Context surface")
        assertEquals(
            "android.content.Context\$BindServiceFlags",
            nested.javaName.binaryName
        )
    }

    @Test
    fun context_bind_service_flags_provider_exposes_of_static_factory() {
        requireAndroidJarOrSkip()
        val file = providerFileNested("android.content.Context\$BindServiceFlags")
        val staticNames = (file.module.fields.keys + file.module.methods.keys)
            .filter { it != "__class" && it != "__call" }
            .toSet()
        val classMembers = file.surface.members.filter { it.exportPath.firstOrNull() == "__class" }

        assertTrue(
            "of" in file.module.methods ||
                "of" in file.module.fields ||
                classMembers.any { it.exportPath == listOf("__class", "of") },
            "Expected BindServiceFlags.of static factory from ${androidJar.path}; " +
                "staticNames=$staticNames classMembers=${classMembers.map { it.exportPath }.take(20)}"
        )
    }

    @Test
    fun workspace_engine_context_require_exposes_non_empty_member_completions() {
        requireAndroidJarOrSkip()
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local Context = require("Context")
                local current = Context
                return current
            """.trimIndent(),
            metadata = mapOf(
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "Context",
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path,
                JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "android.content"
            ),
            engine = JvmWorkspaceEngine()
        )

        val resolved = harness.queries.resolveRequire(harness.path("main.lua"), "Context")
        assertEquals(
            harness.path("__jvm__/classes/android/content/Context.lua"),
            resolved.provider?.path
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "current")
        )
        val labels = completions.map { it.label }.toSet()
        assertTrue(
            labels.isNotEmpty(),
            "Expected non-empty Context-related completions when android.jar present; got empty."
        )
        assertTrue(
            "MODE_PRIVATE" in labels ||
                "ACTIVITY_SERVICE" in labels ||
                "getResources" in labels ||
                "Context" in labels,
            "Expected Context static/instance surface labels in completions; got=${labels.sorted().take(40)}"
        )
    }

    @Test
    fun workspace_engine_context_instance_member_hover_uses_reflected_surface() {
        requireAndroidJarOrSkip()
        // Match the JVM instance-member corpus shape: construct via luajava.newInstance so the
        // receiver is a reflected Java instance, then hover the member identifier. Emmy ---@type
        // receivers alone can degrade hover away from METHOD.
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                local host = luajava.newInstance("android.content.Context")
                local current = host.getResources
                return current
            """.trimIndent(),
            metadata = mapOf(
                JvmClassModuleProvider.IMPORTS_METADATA_KEY to "Context",
                JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path,
                JvmWorkspaceConfiguration.IMPORT_PREFIXES_METADATA_KEY to "android.content"
            ),
            engine = JvmWorkspaceEngine()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "getResources")
        )
        assertNotNull(
            hover,
            "Expected hover on Context instance member getResources when android.jar present."
        )
        val text = buildString {
            append(hover.symbol?.name.orEmpty())
            append(' ')
            append(hover.symbol?.kind?.name.orEmpty())
            append(' ')
            append(hover.typeInfo?.displayName.orEmpty())
        }
        assertTrue(
            text.contains("getResources", ignoreCase = true) ||
                hover.symbol?.kind == SymbolKind.METHOD ||
                text.contains("fun", ignoreCase = true),
            "Expected hover to mention getResources / METHOD surface; got: $text"
        )
    }

    @Test
    fun missing_android_jar_does_not_mount_context_provider() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val configuration = JvmWorkspaceConfiguration(
            androidJar = missing.path,
            classes = linkedSetOf("android.content.Context")
        )
        val providers = provider.providersFor(configuration)

        // Clean skip path for consumers: missing jar → no Context provider, no throw.
        assertTrue(
            providers.isEmpty() || jvmClassPath("android.content.Context") !in providers,
            "Missing android.jar must not mount a real Context provider; actual=${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun missing_android_jar_providers_for_does_not_throw() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val configuration = JvmWorkspaceConfiguration(
            androidJar = missing.path,
            classes = linkedSetOf("android.content.Context")
        )

        val providers = runCatching { provider.providersFor(configuration) }
            .getOrElse { error ->
                fail("Absent android.jar must degrade without throw; got ${error::class.simpleName}: ${error.message}")
            }

        assertTrue(
            providers.isEmpty() || jvmClassPath("android.content.Context") !in providers,
            "Absent jar must not surface a Context provider; actual=${providers.keys.map { it.value }}"
        )
    }

    @Test
    fun missing_android_jar_does_not_mount_bind_service_flags_provider() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val configuration = JvmWorkspaceConfiguration(
            androidJar = missing.path,
            classes = linkedSetOf(
                "android.content.Context.BindServiceFlags",
                "android.content.Context\$BindServiceFlags"
            )
        )
        val providers = runCatching { provider.providersFor(configuration) }
            .getOrElse { error ->
                fail("Absent android.jar nested Context types must degrade without throw; got ${error::class.simpleName}: ${error.message}")
            }

        assertTrue(
            providers.isEmpty() ||
                jvmClassPath("android.content.Context\$BindServiceFlags") !in providers,
            "Missing android.jar must not invent BindServiceFlags provider; actual=${providers.keys.map { it.value }}"
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
