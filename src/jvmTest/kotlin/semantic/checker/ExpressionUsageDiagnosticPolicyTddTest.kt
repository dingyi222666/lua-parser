package semantic.checker

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import org.junit.Assume
import semantic.support.WorkspaceSemanticHarness
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Semantic-model diagnostic policy for ExpressionUsageChecker (adversarial-audit wave A):
 *
 * 1. Unused value locals report with INFO severity (editor hint, not a defect). The LSP
 *    publish surface maps this to lsp Information; the model is the source of truth.
 * 2. `checker.luajava.target.unresolved` reports WARNING, not ERROR: hosts without the
 *    target class on the classpath (no android.jar etc.) still run valid code on-device.
 * 3. Index-path member-missing diagnostics name the offending index key, matching the
 *    member-path message shape (`Unknown Java member '<key>' on <base>.`).
 * 4. `checker.global.unresolved` (FIXER-UNDEF): free-identifier reads report WARNING only
 *    when every resolution surface misses (visible VALUE declaration, imported symbol,
 *    active import-target root), with the S1-S4 suppression policy (writes, `_`-prefixed,
 *    UpperCamel, `_ENV`-param functions keep checking) and the `parent` overlay seed.
 * 5. Local-function dead code (wave Z FIXER-TRIO): unreferenced local `function` declarations
 *    report the same INFO unused-local diagnostic as unreferenced value locals; reads
 *    (direct calls, table-dispatch `{ f = f }` values) mark them referenced, while
 *    method-style `function M.f()` declarations stay outside the policy.
 *
 * Harness mirrors sibling interop suites (WorkspaceSemanticHarness + JvmWorkspaceEngine);
 * `luajava.bindClass` resolves host JDK classes reflectively, so `java.lang.System` is a
 * stable Java surface and `missing.DoesNotExist` a stable unresolved target.
 */
class ExpressionUsageDiagnosticPolicyTddTest {

    @Test
    fun unused_local_diagnostic_uses_info_severity() {
        val harness = harness("local unusedValue = 1\nreturn 1")

        val unused = diagnostics(harness).filter { it.code == "checker.local.unused" }

        assertTrue(
            unused.isNotEmpty(),
            "Expected unused-local diagnostic on executable chunk; actual: ${describe(diagnostics(harness))}."
        )
        val diagnostic = unused.single()
        assertEquals(DiagnosticSeverity.INFO, diagnostic.severity)
        assertEquals("Unused local 'unusedValue'.", diagnostic.message)
        // Canonical LSP unused signal (DiagnosticTag.Unnecessary = 1); the LSP publish
        // mapping translates it so clients render faded text.
        assertEquals(listOf(1), diagnostic.tags)
    }

    @Test
    fun used_local_emits_no_unused_local_diagnostic() {
        val harness = harness("local usedValue = 1\nreturn usedValue")

        val unused = diagnostics(harness).filter { it.code == "checker.local.unused" }

        assertTrue(
            unused.isEmpty(),
            "Read local must not report unused; actual: ${describe(diagnostics(harness))}."
        )
    }

    // =========================================================================
    // Local-function dead code (wave Z FIXER-TRIO): `local function name() end`
    // participates in the unused-local policy like `local f = function() end`.
    // =========================================================================

    @Test
    fun unused_local_function_reports_info_diagnostic() {
        val harness = harness("local function unusedHelper() end")

        val unused = diagnostics(harness).filter { it.code == "checker.local.unused" }

        assertTrue(
            unused.isNotEmpty(),
            "Zero-caller local function must report unused; " +
                "actual: ${describe(diagnostics(harness))}."
        )
        val diagnostic = unused.single()
        assertEquals(DiagnosticSeverity.INFO, diagnostic.severity)
        assertEquals("Unused local 'unusedHelper'.", diagnostic.message)
    }

    @Test
    fun used_local_function_emits_no_unused_local_diagnostic() {
        val harness = harness("local function usedHelper() end\nusedHelper()")

        val unused = diagnostics(harness).filter { it.code == "checker.local.unused" }

        assertTrue(
            unused.isEmpty(),
            "Called local function must not report unused; actual: ${describe(diagnostics(harness))}."
        )
    }

    @Test
    fun local_function_table_dispatch_read_stays_silent() {
        // Table-dispatch usage counts as a read: `{ readyLoad = readyLoad }` is a plain
        // identifier read of the FUNCTION declaration (markLocalRead), and the later
        // member call keeps the dispatch table itself referenced.
        val harness = harness(
            "local function readyLoad() end\n" +
                "local handlers = { readyLoad = readyLoad }\n" +
                "handlers.readyLoad(1)"
        )

        val unused = diagnostics(harness).filter { it.code == "checker.local.unused" }

        assertTrue(
            unused.isEmpty(),
            "Table-dispatch referenced local function must not report unused; " +
                "actual: ${describe(diagnostics(harness))}."
        )
    }

    @Test
    fun method_style_module_function_stays_out_of_unused_policy() {
        // `function M.exposed() end` declares a METHOD member (module surface), not a
        // value local — it must stay outside the unused-local emission.
        val harness = harness("function M.exposed() end")

        val unused = diagnostics(harness).filter { it.code == "checker.local.unused" }

        assertTrue(
            unused.isEmpty(),
            "Method-style module function must not report unused; " +
                "actual: ${describe(diagnostics(harness))}."
        )
    }

    @Test
    fun luajava_unresolved_target_diagnostic_uses_warning_severity() {
        val harness = harness("local cls = luajava.bindClass(\"missing.DoesNotExist\")\nreturn cls")

        val unresolved = diagnostics(harness).filter { it.code == "checker.luajava.target.unresolved" }

        assertTrue(
            unresolved.isNotEmpty(),
            "Expected unresolved LuaJava target diagnostic; actual: ${describe(diagnostics(harness))}."
        )
        val diagnostic = unresolved.single()
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
        assertTrue(
            diagnostic.message.contains("missing.DoesNotExist"),
            "Message should name the unresolved target; actual: ${diagnostic.message}."
        )
    }

    @Test
    fun index_path_member_diagnostic_includes_index_key() {
        val harness = harness(
            "local System = luajava.bindClass(\"java.lang.System\")\n" +
                "return System[\"definitelyNotAStaticMember\"]"
        )

        val memberMissing = diagnostics(harness).filter { it.code == "checker.member.missing" }

        assertTrue(
            memberMissing.isNotEmpty(),
            "Expected index-path member-missing diagnostic; actual: ${describe(diagnostics(harness))}."
        )
        val message = memberMissing.single().message
        assertTrue(
            message.contains("definitelyNotAStaticMember"),
            "Index key must appear in the message; actual: $message."
        )
        assertTrue(
            message.contains("System"),
            "Base type name must appear in the message; actual: $message."
        )
        assertEquals(DiagnosticSeverity.ERROR, memberMissing.single().severity)
    }

    // =========================================================================
    // checker.global.unresolved (free-identifier diagnostics, FIXER-UNDEF)
    // =========================================================================

    @Test
    fun unresolved_free_identifier_reports_single_warning_diagnostic() {
        val harness = harness("local a = 1\nprint(a)\nlocal b = mysteryHelper + 1")

        val unresolved = diagnostics(harness).filter { it.code == "checker.global.unresolved" }

        assertTrue(
            unresolved.isNotEmpty(),
            "Expected unresolved-global diagnostic on free mysteryHelper read; " +
                "actual: ${describe(diagnostics(harness))}."
        )
        val diagnostic = unresolved.single()
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity)
        assertEquals("Unresolved global 'mysteryHelper'.", diagnostic.message)
    }

    @Test
    fun unresolved_global_suppression_policy_silences_known_identifier_classes() {
        val harness = harness(
            """
            MyGlobal = 1
            local x = MyGlobal
            bareWrite = 5
            local w = bareWrite
            local y = _scratch
            local z = UpperCamelThing
            local function handled(paramName)
              local inner = paramName
              return inner
            end
            return handled(x + w + y + z)
            """.trimIndent()
        )

        val unresolved = diagnostics(harness).filter { it.code == "checker.global.unresolved" }

        assertTrue(
            unresolved.isEmpty(),
            "Suppression policy must silence written, `_`-prefixed, UpperCamel, parameter and " +
                "local reads; actual: ${describe(diagnostics(harness))}."
        )
    }

    @Test
    fun env_param_function_keeps_free_identifier_checking() {
        // Spec decision (S4): no blanket _ENV-param disable. print stays silent (builtin
        // overlay global); the free `x` read still flags because the env chains back to
        // the global surface (corpus FN shape: free `h` in dingyi.lua:137).
        val harness = harness("local callback = function(_ENV) print(x) end\ncallback(1)")

        val unresolved = diagnostics(harness).filter { it.code == "checker.global.unresolved" }

        assertTrue(
            unresolved.isNotEmpty(),
            "Free `x` read inside an _ENV-param function must flag; " +
                "actual: ${describe(diagnostics(harness))}."
        )
        val diagnostic = unresolved.single()
        assertEquals("Unresolved global 'x'.", diagnostic.message)
        assertTrue(
            diagnostics(harness).none {
                it.code == "checker.global.unresolved" && it.message.contains("'print'")
            },
            "Builtin print must stay silent inside _ENV-param functions; " +
                "actual: ${describe(diagnostics(harness))}."
        )
    }

    @Test
    fun imported_module_symbol_read_stays_silent() {
        val harness = WorkspaceSemanticHarness.build(
            "helper.lua" to "local M = {}\nreturn M\n",
            "main.lua" to "import \"helper\"\nlocal value = helper\nreturn value"
        )

        val unresolved = diagnostics(harness).filter { it.code == "checker.global.unresolved" }

        assertTrue(
            unresolved.isEmpty(),
            "Read of an imported module alias must stay silent; actual: ${describe(diagnostics(harness))}."
        )
    }

    @Test
    fun imported_package_root_read_stays_silent_when_android_jar_present() {
        val androidJar = resolveAndroidJar()
        Assume.assumeTrue(
            "host android.jar required for the wildcard package-root surface",
            androidJar != null && androidJar.isFile
        )
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to "import \"android.widget.*\"\nlocal root = android\nreturn root",
            engine = JvmWorkspaceEngine(
                configuration = JvmWorkspaceConfiguration(androidJar = androidJar!!.absolutePath)
            )
        )

        val unresolved = diagnostics(harness).filter { it.code == "checker.global.unresolved" }

        assertTrue(
            unresolved.isEmpty(),
            "Free `android` root read must stay silent behind an active wildcard import; " +
                "actual: ${describe(diagnostics(harness))}."
        )
    }

    @Test
    fun loadlayout_parent_global_read_stays_silent() {
        val harness = harness(
            """
            loadlayout("layout/main")
            local ids = parent.getPageIds(1)
            local idsTable = parent.getIdsTable()
            return ids, idsTable
            """.trimIndent()
        )

        val unresolved = diagnostics(harness).filter { it.code == "checker.global.unresolved" }

        assertTrue(
            unresolved.isEmpty(),
            "loadlayout-injected `parent` reads must stay silent after the overlay seed; " +
                "actual: ${describe(diagnostics(harness))}."
        )
    }

    private fun harness(source: String): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            "main.lua" to source,
            metadata = mapOf(
                io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider.CLASSES_METADATA_KEY to
                    "java.lang.System"
            ),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun diagnostics(harness: WorkspaceSemanticHarness) =
        harness.queries.diagnostics(harness.path("main.lua"))

    private fun resolveAndroidJar(): File? {
        val candidates = listOfNotNull(
            System.getenv("ANDROID_JAR"),
            System.getProperty("luaparser.android.jar"),
            System.getenv("ANDROID_HOME")?.let { "$it/platforms/android-35/android.jar" },
            System.getenv("ANDROID_HOME")?.let { "$it/platforms/android-34/android.jar" },
            System.getProperty("user.home") + "/Library/Android/sdk/platforms/android-35/android.jar",
            System.getProperty("user.home") + "/Library/Android/sdk/platforms/android-34/android.jar",
            runCatching { JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH }.getOrNull()
        )
        return candidates.map { File(it) }.firstOrNull { it.isFile }
    }

    private fun describe(diagnostics: List<io.github.dingyi222666.luaparser.semantic.api.Diagnostic>): String {
        return diagnostics.map { diagnostic -> "${diagnostic.severity}/${diagnostic.code}: ${diagnostic.message}" }
            .toString()
    }
}
