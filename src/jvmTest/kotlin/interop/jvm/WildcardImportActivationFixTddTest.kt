package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberKind
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import java.io.File
import org.junit.Assume
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Wave J wildcard-import activation fixes (adversarial audit follow-up):
 *
 * 1. Shallow-vs-deep static surface parity — wildcard-mounted class modules
 *    ([JvmClassModuleProvider.packageMemberClassProvidersFor]) must expose the same
 *    reflected static surface as explicit imports (`clazz.fields` / `clazz.methods`
 *    incl. inherited statics), so `TextView.VISIBLE` (declared on android.view.View)
 *    resolves through `import "android.widget.*"` exactly as through
 *    `import "android.widget.TextView"`. Exclusion filters stay (public / non-synthetic /
 *    non-bridge).
 * 2. Explicit-import precedence — an explicit `import "java.awt.List"` must win the
 *    `List` simple-name slot over a wildcard package member (java.util.List), in both
 *    doc orders, mirroring AndroLua's sequential _G installs where the more specific
 *    import overrides.
 * 3. Multi-argument import — `import("a.*", "b.*")` collects EVERY string-literal
 *    argument through the same parse path (not just the first), so both packages
 *    activate.
 *
 * android.jar-dependent cases soft-skip with an explicit reason (never hard-require a
 * host SDK); JDK-only cases run everywhere. This worker does not run Gradle; serial
 * review owns verification.
 */
class WildcardImportActivationFixTddTest {
    private val provider = JvmClassModuleProvider()
    private val androidJar = resolveAndroidJar()

    // -------------------------------------------------------------------------
    // 1) Wildcard shallow static surface == explicit import static surface
    // -------------------------------------------------------------------------

    @Test
    fun wildcard_shallow_surface_exposes_inherited_static_field_like_explicit_import() {
        requireAndroidJarOrSkip()

        val wildcardSurface = wildcardTextViewModule()
        val explicitSurface = explicitTextViewModule()

        // TextView.VISIBLE is declared on android.view.View; the declared-only surface
        // dropped it for wildcard mounts while the explicit import kept it.
        assertTrue(
            "VISIBLE" in wildcardSurface.fields,
            "Expected inherited View.VISIBLE on the wildcard TextView module through " +
                "android.widget.*; fields=${wildcardSurface.fields.keys.sorted()}"
        )
        assertTrue(
            "VISIBLE" in explicitSurface.fields,
            "Expected VISIBLE on the explicit-import TextView module (parity baseline); " +
                "fields=${explicitSurface.fields.keys.sorted()}"
        )

        val wildcardClass = assertIs<JavaInstanceType>(wildcardSurface.fields["__class"]).classType
        val visible = wildcardClass.staticMembers["VISIBLE"]
            ?: fail(
                "Expected VISIBLE in the wildcard JavaClassType static surface; " +
                    "statics=${wildcardClass.staticMembers.keys.sorted()}"
            )
        assertEquals(JavaMemberKind.FIELD, visible.memberKind)
        assertEquals(
            "android.view.View",
            visible.owner.canonicalName,
            "Inherited static must report its declaring owner (android.view.View), not the receiver"
        )
    }

    @Test
    fun wildcard_shallow_surface_static_parity_covers_declared_and_inherited_statics() {
        requireAndroidJarOrSkip()

        val wildcardFields = wildcardTextViewModule().fields.keys
        val explicitFields = explicitTextViewModule().fields.keys

        // Declared statics stay (AUTO_SIZE_TEXT_UNIFORM is declared on TextView) and the
        // inherited static set now matches the explicit import instead of being a subset
        // missing View-declared constants.
        // AUTO_SIZE_TEXT_TYPE_UNIFORM is declared on TextView itself; ALPHA is inherited
        // from android.view.View (verified via javap against android-35).
        assertTrue("AUTO_SIZE_TEXT_TYPE_UNIFORM" in wildcardFields, "Declared TextView static must stay")
        assertTrue(
            explicitFields.all { it in wildcardFields || it == "__call" },
            "Wildcard shallow static surface must match the explicit import surface; " +
                "missing=${(explicitFields - wildcardFields.toSet()).sorted()}"
        )
    }

    // -------------------------------------------------------------------------
    // 2) Explicit import beats wildcard member for the same simple name
    // -------------------------------------------------------------------------

    @Test
    fun explicit_import_after_wildcard_wins_same_simple_name() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                import "java.util.*"
                import "java.awt.List"
                local l = List
                return l
            """.trimIndent(),
            engine = JvmWorkspaceEngine()
        )

        assertListResolvesToJavaAwt(harness)
    }

    @Test
    fun explicit_import_before_wildcard_still_wins_same_simple_name() {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to """
                import "java.awt.List"
                import "java.util.*"
                local l = List
                return l
            """.trimIndent(),
            engine = JvmWorkspaceEngine()
        )

        assertListResolvesToJavaAwt(harness)
    }

    // -------------------------------------------------------------------------
    // 3) Multi-argument import activates every string target
    // -------------------------------------------------------------------------

    @Test
    fun multi_argument_wildcard_import_records_and_activates_every_target() {
        val source = """import("java.util.*", "java.io.*")"""
        val facts = collectFacts(source)
        assertEquals(
            listOf("java.util.*", "java.io.*"),
            facts.sourceImports.map { it.target },
            "Every string-literal import argument must be collected, not only the first"
        )

        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to source,
            engine = JvmWorkspaceEngine()
        )
        assertTrue(
            harness.path("__jvm__/packages/java/util.lua") in harness.snapshot.extraProviders,
            "First wildcard argument must mount its package provider; " +
                "extras=${harness.snapshot.extraProviders.keys.map { it.value }}"
        )
        assertTrue(
            harness.path("__jvm__/packages/java/io.lua") in harness.snapshot.extraProviders,
            "Second wildcard argument must mount its package provider too; " +
                "extras=${harness.snapshot.extraProviders.keys.map { it.value }}"
        )

        val symbols = WorkspaceModuleResolver(harness.snapshot).importedSymbolsFor(harness.path("main.lua"))
        assertTrue("List" in symbols, "java.util.* members must activate; got=${symbols.keys.sorted()}")
        assertTrue("File" in symbols, "java.io.* members must activate; got=${symbols.keys.sorted()}")
    }

    @Test
    fun multi_argument_import_feeds_jvm_class_loads_for_non_wildcard_targets() {
        val facts = collectFacts("""import("java.util.*", "java.io.File")""")

        assertEquals(
            listOf("java.util.*", "java.io.File"),
            facts.sourceImports.map { it.target }
        )
        // Existing contract preserved: wildcard targets stay out of jvmClassLoads,
        // explicit class targets keep their IMPORT_CALL load fact.
        assertEquals(
            listOf(DocumentFacts.JvmClassLoadKind.IMPORT_CALL to "java.io.File"),
            facts.jvmClassLoads.map { it.kind to it.target }
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun assertListResolvesToJavaAwt(harness: WorkspaceSemanticHarness) {
        val symbols = WorkspaceModuleResolver(harness.snapshot).importedSymbolsFor(harness.path("main.lua"))
        val listSymbol = symbols["List"]
            ?: fail("Expected a List import symbol; got=${symbols.keys.sorted()}")
        assertEquals(
            harness.path("__jvm__/classes/java/awt/List.lua"),
            listSymbol.providerPath,
            "Explicit java.awt.List import must beat the java.util.* wildcard member for List"
        )

        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "List", occurrence = 2)
        )
        assertEquals(
            listOf(harness.path("__jvm__/classes/java/awt/List.lua")),
            definitions.map { it.path },
            "Bare List usage must define to the explicit java.awt.List provider"
        )
    }

    private fun wildcardTextViewModule(): ModuleType = moduleAt(
        provider.packageMemberClassProvidersFor(
            importTargets = listOf("android.widget.*"),
            configuration = JvmWorkspaceConfiguration(androidJar = androidJar.path)
        ),
        "__jvm__/classes/android/widget/TextView.lua",
        "wildcard android.widget.*"
    )

    private fun explicitTextViewModule(): ModuleType = moduleAt(
        provider.providersFor(
            JvmWorkspaceConfiguration(
                androidJar = androidJar.path,
                classes = linkedSetOf("android.widget.TextView")
            )
        ),
        "__jvm__/classes/android/widget/TextView.lua",
        "explicit android.widget.TextView"
    )

    private fun moduleAt(
        providers: Map<VirtualPath, WorkspaceSnapshot.FileSnapshot>,
        pathValue: String,
        label: String
    ): ModuleType {
        val snapshot = providers[VirtualPath.of(pathValue)]
            ?: fail(
                "Expected $label provider at $pathValue; " +
                    "actual=${providers.keys.map { it.value }}"
            )
        return snapshot.moduleExportSurface?.moduleType
            ?: fail("Expected module export surface for $label at $pathValue")
    }

    private fun collectFacts(source: String): DocumentFacts {
        val chunk = LuaParser().parse(source)
        return DocumentFactsCollector.collect(VirtualPath.of("app/main.lua"), chunk)
    }

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    private companion object {
        /**
         * Host-resolution order for the android.jar gate (mirrors the TASK-211 corpus):
         * WAVE mac SDK path, DEFAULT_ANDROID_JAR_PATH, then ANDROID_HOME / ANDROID_SDK_ROOT
         * platforms 35/34. Never hard-requires a missing jar; callers soft-skip.
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

        private fun missingAndroidJarSkipReason(androidJar: File): String {
            return "WAVE-J skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35 (or set ANDROID_HOME / ANDROID_SDK_ROOT / " +
                "jvm.androidJar) before running wildcard shallow static surface parity corpus."
        }
    }
}
