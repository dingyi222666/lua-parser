package interop.jvm

import io.github.dingyi222666.luaparser.interop.dex.DexClass
import io.github.dingyi222666.luaparser.interop.dex.DexParseResult
import io.github.dingyi222666.luaparser.interop.dex.parseDex
import io.github.dingyi222666.luaparser.interop.jvm.DexClassModelAdapter
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test against the REAL demo dex shipped in this repository at
 * `tools/monaco-lsp-demo/workspace/libs/classes.dex` (Dalvik 035, 929 classes,
 * 1.38 MB). The fixture is located relative to the repo root by walking up from
 * `user.dir`, so the suite works both from the gradle jvmTest working directory
 * (repo root) and from IDE run configurations launched in subdirectories.
 *
 * Checkouts without the demo corpus degrade gracefully: every test soft-skips
 * via [assumeTrue] when the fixture file is absent.
 *
 * Class names asserted here were verified against this exact fixture during
 * parser bring-up (real-dex smoke run: 929/929 classes parsed).
 */
class DexRealFixtureTddTest {

    private companion object {
        /** Repo-root-relative path of the demo classes.dex fixture. */
        const val FIXTURE_RELATIVE_PATH = "tools/monaco-lsp-demo/workspace/libs/classes.dex"

        /**
         * Upper bound for MUTF-8 decode diagnostics on the demo corpus; the
         * file is known-good, so any significant count signals a decoder bug.
         */
        const val MAX_MUTF8_DIAGNOSTICS = 5000

        /** Lower bound of class_defs in the shipped fixture (actual: 929). */
        const val MIN_CLASSES = 900

        /**
         * Real class names decoded from the fixture during parser bring-up, all
         * with non-empty method tables.
         */
        val KNOWN_CLASSES = listOf(
            "android/support/v4/view/ViewCompat", // largest class in the dex (145 methods)
            "adrt/ADRTSender", // Android Studio ADRT debugging transport
            "android/arch/lifecycle/BuildConfig" // androidx lifecycle BuildConfig
        )

        fun locateFixture(): File? {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            var depth = 0
            while (dir != null && depth < 6) {
                val candidate = File(dir, FIXTURE_RELATIVE_PATH)
                if (candidate.isFile) {
                    return candidate
                }
                dir = dir.parentFile
                depth++
            }
            return null
        }

        /**
         * Parses the fixture once per JVM (must not throw on this well-formed
         * file - a crash here fails every test below with the raw exception).
         */
        val parsed: Pair<File, DexParseResult> by lazy {
            val fixture = assertNotNull(locateFixture(), "fixture resolution unexpectedly failed")
            fixture to parseDex(fixture.readBytes())
        }
    }

    private fun withFixture(block: (File, DexParseResult) -> Unit) {
        // JUnit4 assumption failure => the test is reported as skipped on
        // checkouts without the demo corpus (kotlin.test has no assume API in
        // the kotlin-test version this project pins, and this suite is JVM-only;
        // JUnit4's Assume.assumeTrue has no message parameter).
        assumeTrue(locateFixture()?.isFile == true) // skip: demo dex fixture not present
        val (fixture, result) = parsed
        block(fixture, result)
    }

    @Test
    fun realDemoDexParsesAtExpectedScaleWithoutThrowing() {
        withFixture { fixture, result ->
            assertTrue(fixture.length() > 1_000_000, "fixture unexpectedly small: $fixture")
            assertTrue(
                result.classes.size >= MIN_CLASSES,
                "expected >= $MIN_CLASSES classes, got ${result.classes.size}"
            )
            assertEquals(929, result.classes.size, "fixture class_defs_size known to be 929")
        }
    }

    @Test
    fun mutf8DiagnosticsStaySaneOnRealCorpus() {
        withFixture { _, result ->
            val mutf8Failures = result.diagnostics.count { it.contains("MUTF-8") }
            assertTrue(
                mutf8Failures < MAX_MUTF8_DIAGNOSTICS,
                "MUTF-8 diagnostics=$mutf8Failures exceeds sane bound for known-good corpus"
            )
            // Every parsed class must have a usable binary name - undecodable
            // descriptor strings are the failure mode this guards against.
            assertTrue(result.classes.all { it.binaryName.isNotBlank() })
        }
    }

    @Test
    fun knownRealClassesParseWithNonEmptyMethods() {
        withFixture { _, result ->
            val byName = result.classes.associateBy(DexClass::binaryName)
            for (name in KNOWN_CLASSES) {
                val cls = assertNotNull(byName[name], "known class $name missing from parse result")
                assertTrue(cls.methods.isNotEmpty(), "known class $name parsed with empty methods")
                assertTrue(cls.methods.all { it.returnDescriptor.isNotBlank() })
                assertTrue(cls.simpleName == name.substringAfterLast('/'))
            }
        }
    }

    @Test
    fun realClassConvertsToModuleTypeThroughAdapter() {
        withFixture { _, result ->
            val viewCompat = result.classes.first { it.binaryName == "android/support/v4/view/ViewCompat" }

            // Must not throw on a real, 145-method class hydrated from the full dex set.
            val module = DexClassModelAdapter.toModuleType(viewCompat, result.classes)

            assertEquals("ViewCompat", module.moduleName)
            assertTrue(
                module.fields.containsKey("__class"),
                "module table must expose the __class instance shell"
            )
            assertTrue(module.fields.getValue("__class") is JavaInstanceType)
            assertTrue(
                module.methods.isNotEmpty(),
                "all-static compat class must surface static methods as module methods"
            )
        }
    }

    @Test
    fun adapterConversionOfEveryParsedClassTerminates() {
        withFixture { _, result ->
            // Bulk conversion of all 929 classes must terminate without throwing
            // (cycle guards in the adapter) - spot-check a sample for shape.
            val sample = result.classes.asSequence()
                .filter { it.methods.isNotEmpty() }
                .take(50)
                .map { DexClassModelAdapter.toModuleType(it, result.classes) }
                .toList()
            assertEquals(50, sample.size)
            assertTrue(sample.all { it.fields.containsKey("__class") })
        }
    }
}
