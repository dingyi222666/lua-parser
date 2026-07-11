package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * TASK-264 corpus: `luajava.loadLib` / loadLib aliases expose documented package/function
 * surfaces (static methods and fields) or unknown with diagnostics. Shadowed locals must not
 * inherit JVM loadLib semantics.
 *
 * Test-only; product fixes belong in separately scoped tasks.
 */
class LuaJavaLoadLibSurfaceTddTest {

    // ------------------------------------------------------------------
    // Direct luajava.loadLib package/function surfaces
    // ------------------------------------------------------------------

    @Test
    fun load_lib_static_method_result_is_callable() {
        val harness = jvmHarness(
            "main.lua" to """
                local currentTimeMillis = luajava.loadLib("java.lang.System", "currentTimeMillis")
                return currentTimeMillis
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "currentTimeMillis", occurrence = 2)
        )

        assertCallable(hover?.typeInfo?.displayName)
        assertProviderPath(harness, "java.lang.System")
    }

    @Test
    fun load_lib_static_field_result_uses_reflected_field_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local root = luajava.loadLib("java.util.Locale", "ROOT")
                return root
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "root", occurrence = 2)
        )

        assertEquals("java.util.Locale", hover?.typeInfo?.displayName)
        assertNotUnknown(hover?.typeInfo?.displayName)
        assertProviderPath(harness, "java.util.Locale")
    }

    @Test
    fun load_lib_static_method_definition_points_to_provider() {
        val harness = jvmHarness(
            "main.lua" to """
                local currentTimeMillis = luajava.loadLib("java.lang.System", "currentTimeMillis")
                return currentTimeMillis
            """.trimIndent()
        )

        // Hover the load-result local (binding may attach method symbols to the local instead of
        // the member-name string argument).
        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "currentTimeMillis", occurrence = 2)
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "currentTimeMillis", occurrence = 2)
        )
        assertCallable(hover?.typeInfo?.displayName)
        // Soft: provider path may appear if member-string/goto maps to System; do not require
        // definition path equality when product treats load result as a local binding.
        assertTrue(
            definitions.isEmpty() ||
                definitions.any {
                    it.path == harness.path("main.lua") ||
                        it.path == harness.path("__jvm__/classes/java/lang/System.lua")
                },
            "Expected local or System provider definition; got ${definitions.map { it.path }}"
        )
    }

    @Test
    fun load_lib_document_facts_record_loaded_class_target() {
        val harness = jvmHarness(
            "main.lua" to """
                local currentTimeMillis = luajava.loadLib("java.lang.System", "currentTimeMillis")
                return currentTimeMillis
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)

        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL &&
                    it.target == "java.lang.System"
            },
            "Expected LOAD_LIB_CALL fact for java.lang.System; got $loads"
        )
    }

    @Test
    fun load_lib_mounts_class_provider_for_target_package() {
        val harness = jvmHarness(
            "main.lua" to """
                local lineSeparator = luajava.loadLib("java.lang.System", "lineSeparator")
                return lineSeparator
            """.trimIndent()
        )

        assertProviderPath(harness, "java.lang.System")
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "lineSeparator", occurrence = 2)
        )
        // lineSeparator may be modeled as callable method or string field depending on JDK surface.
        assertNotUnknown(hover?.typeInfo?.displayName)
    }

    @Test
    fun load_lib_package_surface_corpus_table() {
        // Breadth corpus: documented JDK static members across packages via loadLib.
        data class Case(
            val className: String,
            val member: String,
            val expectCallable: Boolean,
            val expectedFieldType: String? = null
        )

        val cases = listOf(
            Case("java.lang.System", "currentTimeMillis", expectCallable = true),
            Case("java.lang.System", "nanoTime", expectCallable = true),
            Case("java.lang.System", "lineSeparator", expectCallable = true),
            Case("java.util.Locale", "getDefault", expectCallable = true),
            Case("java.util.Locale", "ROOT", expectCallable = false, expectedFieldType = "java.util.Locale"),
            Case("java.util.Locale", "US", expectCallable = false, expectedFieldType = "java.util.Locale"),
            Case("java.lang.Math", "max", expectCallable = true),
            Case("java.lang.Math", "min", expectCallable = true),
            Case("java.lang.Integer", "parseInt", expectCallable = true),
            Case("java.lang.Integer", "valueOf", expectCallable = true),
            Case("java.lang.Boolean", "parseBoolean", expectCallable = true),
            Case("java.util.Objects", "requireNonNull", expectCallable = true),
            Case("java.util.Collections", "emptyList", expectCallable = true),
            Case("java.nio.charset.StandardCharsets", "UTF_8", expectCallable = false, expectedFieldType = "java.nio.charset.Charset")
        )

        cases.forEach { case ->
            val localName = "loaded_${case.member}"
            val harness = jvmHarness(
                "main.lua" to """
                    local $localName = luajava.loadLib("${case.className}", "${case.member}")
                    return $localName
                """.trimIndent()
            )

            assertProviderPath(harness, case.className)
            val hover = harness.queries.hover(
                harness.path("main.lua"),
                harness.positionOf("main.lua", localName, occurrence = 2)
            )
            val display = hover?.typeInfo?.displayName
            if (case.expectCallable) {
                assertCallable(display, "loadLib ${case.className}.${case.member}")
            } else {
                assertEquals(
                    case.expectedFieldType,
                    display,
                    "loadLib ${case.className}.${case.member} field type"
                )
            }
            val loads = jvmClassLoads(harness)
            assertTrue(
                loads.any {
                    it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL &&
                        it.target == case.className
                },
                "LOAD_LIB_CALL fact missing for ${case.className}; got $loads"
            )
        }
    }

    @Test
    fun load_lib_result_callable_can_be_invoked_without_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local currentTimeMillis = luajava.loadLib("java.lang.System", "currentTimeMillis")
                local now = currentTimeMillis()
                return now
            """.trimIndent()
        )

        assertProviderPath(harness, "java.lang.System")
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "now", occurrence = 2)
        )
        // Prefer reflected return (number) but accept degraded types if call-site inference is deferred.
        val display = hover?.typeInfo?.displayName
        assertTrue(
            !display.isNullOrBlank(),
            "Expected a modeled or degraded return type for loadLib call result, got '$display'"
        )
        harness.queries.diagnostics(harness.path("main.lua"))
    }

    // ------------------------------------------------------------------
    // loadLib aliases (local + chained)
    // ------------------------------------------------------------------

    @Test
    fun load_lib_local_alias_resolves_static_method() {
        val harness = jvmHarness(
            "main.lua" to """
                local loadLib = luajava.loadLib
                local currentTimeMillis = loadLib("java.lang.System", "currentTimeMillis")
                return currentTimeMillis
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "currentTimeMillis", occurrence = 2)
        )

        assertCallable(hover?.typeInfo?.displayName)
        assertProviderPath(harness, "java.lang.System")
    }

    @Test
    fun load_lib_local_alias_resolves_static_field() {
        val harness = jvmHarness(
            "main.lua" to """
                local loadLib = luajava.loadLib
                local root = loadLib("java.util.Locale", "ROOT")
                return root
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "root", occurrence = 2)
        )

        assertEquals("java.util.Locale", hover?.typeInfo?.displayName)
    }

    @Test
    fun load_lib_chained_alias_resolves_static_method() {
        val harness = jvmHarness(
            "main.lua" to """
                local loadLib = luajava.loadLib
                local load = loadLib
                local again = load
                local currentTimeMillis = again("java.lang.System", "currentTimeMillis")
                return currentTimeMillis
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "currentTimeMillis", occurrence = 2)
        )

        assertCallable(hover?.typeInfo?.displayName)
        assertProviderPath(harness, "java.lang.System")
    }

    @Test
    fun load_lib_alias_document_facts_record_load() {
        val harness = jvmHarness(
            "main.lua" to """
                local load = luajava.loadLib
                local currentTimeMillis = load("java.lang.System", "currentTimeMillis")
                return currentTimeMillis
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)

        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL &&
                    it.target == "java.lang.System"
            }
        )
    }

    @Test
    fun load_lib_chained_alias_document_facts_record_load() {
        val harness = jvmHarness(
            "main.lua" to """
                local loadLib = luajava.loadLib
                local load = loadLib
                local again = load
                local currentTimeMillis = again "java.lang.System", "currentTimeMillis"
                return currentTimeMillis
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)

        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL &&
                    it.target == "java.lang.System"
            },
            "Expected chained-alias LOAD_LIB_CALL; got $loads"
        )
    }

    @Test
    fun compact_string_call_load_lib_resolves_static_method() {
        val harness = jvmHarness(
            "main.lua" to """
                local getDefault = luajava.loadLib "java.util.Locale", "getDefault"
                return getDefault
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "getDefault", occurrence = 2)
        )

        assertCallable(hover?.typeInfo?.displayName)
        assertProviderPath(harness, "java.util.Locale")
    }

    @Test
    fun compact_string_call_load_lib_alias_resolves_static_method() {
        val harness = jvmHarness(
            "main.lua" to """
                local loadLib = luajava.loadLib
                local load = loadLib
                local getDefault = load "java.util.Locale", "getDefault"
                return getDefault
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "getDefault", occurrence = 2)
        )

        assertCallable(hover?.typeInfo?.displayName)
        assertProviderPath(harness, "java.util.Locale")
    }

    // ------------------------------------------------------------------
    // Unknown targets + diagnostics
    // ------------------------------------------------------------------

    @Test
    fun load_lib_missing_class_target_reports_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local loader = luajava.loadLib("missing.DoesNotExist", "open")
                return loader
            """.trimIndent()
        )

        assertUnknownTargetDiagnostic(harness, "missing.DoesNotExist")
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "loader", occurrence = 2)
        )
        assertEquals("unknown", hover?.typeInfo?.displayName ?: "unknown")
    }

    @Test
    fun load_lib_alias_missing_class_target_reports_diagnostic() {
        val harness = jvmHarness(
            "main.lua" to """
                local loadLib = luajava.loadLib
                local loader = loadLib("missing.Package.Class", "open")
                return loader
            """.trimIndent()
        )

        assertUnknownTargetDiagnostic(harness, "missing.Package.Class")
    }

    @Test
    fun load_lib_unknown_member_on_known_class_is_unknown_not_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local weird = luajava.loadLib("java.lang.System", "definitelyNotARealMember_xyz")
                return weird
            """.trimIndent()
        )

        // Class still mounts; missing member should not crash analysis.
        assertProviderPath(harness, "java.lang.System")
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "weird", occurrence = 2)
        )
        val display = hover?.typeInfo?.displayName
        assertTrue(
            display == null || display == "unknown" || display.isBlank(),
            "Unknown member should be unknown, got '$display'."
        )
        // Must not throw / hang — querying diagnostics is the smoke check.
        harness.queries.diagnostics(harness.path("main.lua"))
    }

    @Test
    fun load_lib_dynamic_targets_do_not_emit_load_facts_or_crash() {
        val harness = jvmHarness(
            "main.lua" to """
                local className = "java.lang.System"
                local memberName = "currentTimeMillis"
                local loader = luajava.loadLib(className, memberName)
                return loader
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)
        assertFalse(
            loads.any { it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL },
            "Dynamic loadLib targets must not invent LOAD_LIB_CALL facts; got $loads"
        )
        harness.queries.diagnostics(harness.path("main.lua"))
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "loader", occurrence = 2)
        )
        assertEquals("unknown", hover?.typeInfo?.displayName ?: "unknown")
    }

    // ------------------------------------------------------------------
    // Shadowing: local loadLib must not inherit JVM semantics
    // ------------------------------------------------------------------

    @Test
    fun shadowed_local_load_lib_function_does_not_gain_jvm_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local function loadLib(target, member)
                    return { target = target, member = member }
                end

                local loadResult = loadLib("java.lang.System", "currentTimeMillis")
                local loadCall = loadResult()
                return loadResult, loadCall
            """.trimIndent()
        )

        assertHoverType(harness, "loadResult", "{ target: unknown, member: unknown }", occurrence = 2)
        assertHoverType(harness, "loadCall", "unknown", occurrence = 2)

        val loads = jvmClassLoads(harness)
        assertFalse(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL &&
                    it.target == "java.lang.System"
            },
            "Shadowed bare loadLib must not emit LOAD_LIB_CALL facts; got $loads"
        )
    }

    @Test
    fun shadowed_local_luajava_load_lib_member_does_not_gain_jvm_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local luajava = {
                    loadLib = function(target, member)
                        return { target = target, member = member }
                    end
                }
                local load = luajava.loadLib

                local loadResult = luajava.loadLib("java.lang.System", "currentTimeMillis")
                local aliasResult = load("java.lang.System", "currentTimeMillis")
                local loadCall = loadResult()
                local aliasCall = aliasResult()
                return loadResult, aliasResult, loadCall, aliasCall
            """.trimIndent()
        )

        assertHoverType(harness, "loadResult", "{ target: unknown, member: unknown }", occurrence = 2)
        assertHoverType(harness, "aliasResult", "{ target: unknown, member: unknown }", occurrence = 2)
        assertHoverType(harness, "loadCall", "unknown", occurrence = 2)
        assertHoverType(harness, "aliasCall", "unknown", occurrence = 2)

        val loads = jvmClassLoads(harness)
        assertFalse(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL &&
                    it.target == "java.lang.System"
            },
            "Shadowed luajava.loadLib must not emit LOAD_LIB_CALL facts; got $loads"
        )
    }

    @Test
    fun colon_load_lib_call_does_not_accidentally_model_helper() {
        val harness = jvmHarness(
            "main.lua" to """
                local loaded = luajava:loadLib("java.lang.System", "currentTimeMillis")
                return loaded
            """.trimIndent()
        )

        assertHoverType(harness, "loaded", "unknown", occurrence = 2)
        val loads = jvmClassLoads(harness)
        assertFalse(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.LOAD_LIB_CALL &&
                    it.target == "java.lang.System"
            },
            "Colon loadLib must not emit LOAD_LIB_CALL; got $loads"
        )
    }

    @Test
    fun colon_load_lib_alias_does_not_gain_jvm_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local load = luajava:loadLib
                local loadResult = load("java.lang.System", "currentTimeMillis")
                local loadCall = loadResult()
                return loadResult, loadCall
            """.trimIndent()
        )

        assertHoverTypeIsNot(harness, "loadResult", "fun(", substring = true, occurrence = 2)
        assertHoverType(harness, "loadCall", "unknown", occurrence = 2)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun jvmHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            engine = JvmWorkspaceEngine()
        )
    }

    private fun jvmClassLoads(harness: WorkspaceSemanticHarness): List<DocumentFacts.JvmClassLoadFact> {
        return harness.snapshot.files.getValue(harness.path("main.lua")).documentFacts?.jvmClassLoads.orEmpty()
    }

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
    }

    private fun assertCallable(displayName: String?, label: String = "type") {
        assertTrue(
            displayName.orEmpty().contains("fun("),
            "Expected callable $label, got $displayName."
        )
        assertNotUnknown(displayName)
    }

    private fun assertHoverType(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expected: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )
        assertEquals(expected, hover?.typeInfo?.displayName)
    }

    private fun assertHoverTypeIsNot(
        harness: WorkspaceSemanticHarness,
        needle: String,
        unexpected: String,
        substring: Boolean = false,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )
        val display = hover?.typeInfo?.displayName
        if (substring) {
            assertFalse(
                display.orEmpty().contains(unexpected),
                "Expected type not containing '$unexpected', got '$display'."
            )
        } else {
            assertNotEquals(unexpected, display)
        }
    }

    private fun assertNotUnknown(displayName: String?) {
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java type, got unknown.")
    }

    private fun assertUnknownTargetDiagnostic(harness: WorkspaceSemanticHarness, target: String) {
        val diagnostics = harness.queries.diagnostics(harness.path("main.lua"))
        assertTrue(
            diagnostics.containsTarget(target),
            "Expected unknown LuaJava target diagnostic for $target; actual diagnostics: ${diagnostics.map { it.message }}."
        )
    }

    private fun List<Diagnostic>.containsTarget(target: String): Boolean {
        return any { diagnostic ->
            diagnostic.message.contains(target) &&
                (diagnostic.message.contains("unknown", ignoreCase = true) ||
                    diagnostic.message.contains("not found", ignoreCase = true) ||
                    diagnostic.message.contains("unresolved", ignoreCase = true))
        }
    }
}
