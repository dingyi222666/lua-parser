package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import java.io.File
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentItem
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wave J — dead wildcard/package import diagnostic (adversarial-audit follow-up).
 *
 * A wildcard import whose package enumeration comes back empty (`android.widgt.*`
 * typo, case mismatch, absent jar) used to emit nothing: the wildcard targets are
 * stripped from DocumentFacts.jvmClassLoads and IMPORT_CALL stays off the engine's
 * diagnostic kind set. The engine now records dead wildcard source imports through
 * the existing unresolved-target channel, so the checker publishes
 * `checker.luajava.target.unresolved` as a WARNING — same policy as bindClass/newInstance
 * targets since wave A (hosts without the package still run valid code on-device).
 *
 * The live-wildcard negative control needs a real host android.jar and soft-skips
 * without one (never hard-requires a missing SDK).
 *
 * Test-only; exercises the publish policy, not the semantic model internals.
 */
class LspDeadWildcardImportDiagnosticTddTest {
    private val androidJar = resolveAndroidJar()

    @Test
    fun dead_wildcard_import_publishes_unresolved_target_warning() {
        val service = initializedService()

        val published = service.didOpen(
            openParams(
                "file:///workspace/dead-wildcard-import.lua",
                "import \"android.does.not.exist.*\"\nreturn 1"
            )
        )

        val unresolved = published.diagnostics.filter { it.code?.left == "checker.luajava.target.unresolved" }
        assertTrue(
            unresolved.isNotEmpty(),
            "Expected unresolved LuaJava target diagnostic for the dead wildcard import; " +
                "actual: ${describe(published.diagnostics)}."
        )
        val diagnostic = unresolved.single()
        assertEquals(DiagnosticSeverity.Warning, diagnostic.severity)
        assertTrue(
            diagnostic.message.contains("android.does.not.exist.*"),
            "Message should name the dead wildcard target; actual: ${diagnostic.message}."
        )
    }

    @Test
    fun dead_package_alias_wildcard_typo_publishes_warning() {
        val service = initializedService()

        val published = service.didOpen(
            openParams(
                "file:///workspace/dead-wildcard-typo.lua",
                "import \"java.util.concurrentx.*\"\nreturn 1"
            )
        )

        val unresolved = published.diagnostics.filter { it.code?.left == "checker.luajava.target.unresolved" }
        assertTrue(
            unresolved.isNotEmpty(),
            "Expected unresolved LuaJava target diagnostic for the typo'd JDK wildcard; " +
                "actual: ${describe(published.diagnostics)}."
        )
        assertTrue(
            unresolved.single().message.contains("java.util.concurrentx.*"),
            "Message should name the typo'd package; actual: ${unresolved.single().message}."
        )
    }

    @Test
    fun live_wildcard_import_does_not_publish_unresolved_warning() {
        requireAndroidJarOrSkip()
        val service = initializedService()

        val published = service.didOpen(
            openParams(
                "file:///workspace/live-wildcard-import.lua",
                "import \"android.widget.*\"\nlocal current = TextView\nreturn current"
            )
        )

        val unresolved = published.diagnostics.filter { it.code?.left == "checker.luajava.target.unresolved" }
        assertTrue(
            unresolved.isEmpty(),
            "A wildcard package that enumerates real classes must stay warning-free; " +
                "actual: ${describe(published.diagnostics)}."
        )
    }

    private fun initializedService(): LuaLanguageService {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        return service
    }

    private fun openParams(uri: String, text: String): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, text))
    }

    private fun describe(diagnostics: List<org.eclipse.lsp4j.Diagnostic>): String {
        return diagnostics.map { diagnostic ->
            "${diagnostic.severity}/${diagnostic.code?.left}: ${diagnostic.message}"
        }.toString()
    }

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    private companion object {
        /**
         * Mirrors the default engine discovery (ANDROID_HOME / ANDROID_SDK_ROOT / well-known
         * SDK roots / DEFAULT_ANDROID_JAR_PATH) so the gate matches what the live-wildcard
         * negative control actually needs. Never hard-requires a missing jar.
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
                "Install Android SDK Platform 35 (or set ANDROID_HOME / ANDROID_SDK_ROOT) " +
                "before running the live-wildcard negative control."
        }
    }
}
