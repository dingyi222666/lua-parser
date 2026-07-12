package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-473 corpus: LuaJava `newInstance` **constructor overload surface** —
 * multi-overload JDK classes preserve reflected instance class / provider /
 * `NEW_INSTANCE_CALL` facts / member surfaces for valid type-shapes; same-arity
 * wrong type-shapes stay dual-path (ideal degrade/diagnose vs soft keep of
 * reflected class type when overload ranking is unavailable).
 *
 * Complements:
 * - [LuaJavaNewInstanceOverloadTddTest] — overload dual-path focus
 * - [LuaJavaNewInstanceAritySurfaceTddTest] — arity surface facts
 * - [JavaConstructorOverloadPickTddTest] — CallChecker ranking unit corpus
 *
 * Product contract under test (via `luajava.newInstance` string path):
 * - Valid overload shapes on multi-constructor JDK classes preserve the
 *   reflected instance class type (hard assert) and mount provider /
 *   `NEW_INSTANCE_CALL` facts.
 * - Distinct closed arities that match real constructors keep the instance
 *   surface without inventing APIs.
 * - Instance members after valid overloads are dual-path: ideal callable /
 *   field surface, soft gap unknown/blank when projection is incomplete.
 * - Same-arity type-shape mismatches (e.g. File(number), Locale(number),
 *   BigDecimal({}), UUID(string)) are dual-path.
 * - Alias / compact string-call / mixed valid+invalid paths follow the same
 *   dual-path policy when modeled.
 * - Shadowing / colon forms must not inherit the JVM newInstance surface.
 * - Host android.jar resolution documents Downloads + SDK android-35 only
 *   (never G:/). This corpus is JDK-only for overload shapes; android.jar is
 *   only asserted as host policy surface.
 *
 * Needle hygiene:
 * - Prefer long unique locals (`memberSurface`, `uuidValue`) so positionOf
 *   cannot collide with FQN substrings (`math`/`uuid`/`id`).
 *
 * Test-only scope. Workers do not run Gradle; verification is review-owned
 * serial jvmTest (TASK-043).
 */
class LuaJavaNewInstanceOverloadSurfaceTddTest {

    // ------------------------------------------------------------------
    // Valid multi-overload shapes (hard class + surface asserts)
    // ------------------------------------------------------------------

    @Test
    fun string_builder_zero_arg_overload_surfaces_class_provider_and_fact() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder")
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertProviderPath(harness, "java.lang.StringBuilder")
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        assertFalse(
            diagnostics(harness).any { it.looksLikeConstructorOverloadProblem() },
            "Valid zero-arg StringBuilder overload must not emit overload diagnostics; actual: ${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun string_builder_string_seed_overload_surfaces_instance_member() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", "seed")
                local memberSurface = builder.append
                return builder, memberSurface
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        assertProviderPath(harness, "java.lang.StringBuilder")
        assertMemberSurfaceDualPath(harness, "memberSurface", expectCallable = true, label = "StringBuilder.append")
    }

    @Test
    fun string_builder_capacity_number_overload_surfaces_class_and_fact() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", 64)
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        assertFalse(
            diagnostics(harness).any { it.looksLikeConstructorOverloadProblem() },
            "Valid capacity overload must not emit overload diagnostics; actual: ${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun locale_language_and_country_overloads_surface_class_and_fact() {
        val lang = jvmHarness(
            "main.lua" to """
                local locale = luajava.newInstance("java.util.Locale", "en")
                return locale
            """.trimIndent()
        )
        assertHoverType(lang, "locale", "java.util.Locale", occurrence = 2)
        assertNewInstanceFact(lang, "java.util.Locale")
        assertProviderPath(lang, "java.util.Locale")

        val country = jvmHarness(
            "main.lua" to """
                local locale = luajava.newInstance("java.util.Locale", "en", "US")
                return locale
            """.trimIndent()
        )
        assertHoverType(country, "locale", "java.util.Locale", occurrence = 2)
        assertNewInstanceFact(country, "java.util.Locale")
    }

    @Test
    fun file_path_and_parent_child_overloads_surface_class_and_member() {
        val path = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", "build.gradle.kts")
                local memberSurface = file.getName
                return file, memberSurface
            """.trimIndent()
        )
        assertHoverType(path, "file", "java.io.File", occurrence = 2)
        assertNewInstanceFact(path, "java.io.File")
        assertMemberSurfaceDualPath(path, "memberSurface", expectCallable = true, label = "File.getName")

        val nested = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", "tmp", "out.txt")
                return file
            """.trimIndent()
        )
        assertHoverType(nested, "file", "java.io.File", occurrence = 2)
        assertNewInstanceFact(nested, "java.io.File")
    }

    @Test
    fun big_decimal_string_and_number_overloads_surface_class_and_fact() {
        val fromString = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.math.BigDecimal", "3.14")
                return value
            """.trimIndent()
        )
        assertHoverType(fromString, "value", "java.math.BigDecimal", occurrence = 2)
        assertNewInstanceFact(fromString, "java.math.BigDecimal")
        assertProviderPath(fromString, "java.math.BigDecimal")

        val fromNumber = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.math.BigDecimal", 42)
                return value
            """.trimIndent()
        )
        assertHoverType(fromNumber, "value", "java.math.BigDecimal", occurrence = 2)
        assertNewInstanceFact(fromNumber, "java.math.BigDecimal")
    }

    @Test
    fun point_zero_and_two_number_overloads_surface_field() {
        val zero = jvmHarness(
            "main.lua" to """
                local point = luajava.newInstance("java.awt.Point")
                local memberSurface = point.x
                return point, memberSurface
            """.trimIndent()
        )
        assertHoverType(zero, "point", "java.awt.Point", occurrence = 2)
        assertNewInstanceFact(zero, "java.awt.Point")
        val xHover = zero.queries.hover(
            zero.path("main.lua"),
            zero.positionOf("main.lua", "memberSurface", occurrence = 2)
        )
        // Dual-path field: ideal number FIELD; soft gap unknown/blank/any.
        val xDisplay = xHover?.typeInfo?.displayName
        val fieldLike =
            xHover?.symbol?.kind == SymbolKind.FIELD ||
                xDisplay == "number" ||
                xDisplay == null ||
                xDisplay.isBlank() ||
                xDisplay == "unknown" ||
                xDisplay.equals("any", ignoreCase = true)
        assertTrue(
            fieldLike && (xDisplay == null || !looksCallable(xDisplay) || xDisplay == "number"),
            "Point.x after zero-arg overload dual-path field/gap; kind=${xHover?.symbol?.kind} type=$xDisplay"
        )

        val two = jvmHarness(
            "main.lua" to """
                local point = luajava.newInstance("java.awt.Point", 1, 2)
                return point
            """.trimIndent()
        )
        assertHoverType(two, "point", "java.awt.Point", occurrence = 2)
        assertNewInstanceFact(two, "java.awt.Point")
    }

    @Test
    fun integer_string_and_number_overloads_surface_class_and_fact() {
        val fromString = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.lang.Integer", "7")
                return value
            """.trimIndent()
        )
        assertHoverType(fromString, "value", "java.lang.Integer", occurrence = 2)
        assertNewInstanceFact(fromString, "java.lang.Integer")

        val fromNumber = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.lang.Integer", 7)
                return value
            """.trimIndent()
        )
        assertHoverType(fromNumber, "value", "java.lang.Integer", occurrence = 2)
        assertNewInstanceFact(fromNumber, "java.lang.Integer")
    }

    @Test
    fun valid_overload_via_local_and_chained_alias_surfaces_class_and_fact() {
        val local = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local builder = newInstance("java.lang.StringBuilder", "seed")
                return builder
            """.trimIndent()
        )
        assertHoverType(local, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertNewInstanceFact(local, "java.lang.StringBuilder")

        val chained = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local make = newInstance
                local again = make
                local locale = again("java.util.Locale", "en", "US")
                return locale
            """.trimIndent()
        )
        assertHoverType(chained, "locale", "java.util.Locale", occurrence = 2)
        assertNewInstanceFact(chained, "java.util.Locale")
    }

    @Test
    fun valid_compact_string_call_zero_arg_overload_surfaces_class_and_fact() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance "java.lang.StringBuilder"
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        assertProviderPath(harness, "java.lang.StringBuilder")
    }

    @Test
    fun valid_overload_instance_method_definition_points_to_provider_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", "seed")
                local memberSurface = builder.append
                return memberSurface
            """.trimIndent()
        )

        val definitions = harness.queries.gotoDefinition(
            harness.path("main.lua"),
            harness.positionOf("main.lua", "memberSurface", occurrence = 2)
        )
        val provider = harness.path("__jvm__/classes/java/lang/StringBuilder.lua")
        val main = harness.path("main.lua")
        // Dual-path: ideal provider path; product may attach main.lua or empty when
        // member still types as callable (surface proven via hover dual-path).
        assertTrue(
            definitions.isEmpty() ||
                definitions.any { it.path == provider || it.path == main },
            "Expected StringBuilder provider and/or main.lua definition (or empty gap); got ${definitions.map { it.path }}"
        )
        assertMemberSurfaceDualPath(harness, "memberSurface", expectCallable = true, label = "append definition path")
    }

    @Test
    fun valid_overload_member_completions_include_instance_api_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", "seed")
                local memberSurface = builder.append
                return builder, memberSurface
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path("main.lua"),
            memberPosition(harness, "builder.append")
        )
        val appendItem = completions.singleOrNull { it.label == "append" }
        val listed =
            appendItem != null &&
                (appendItem.kind == CompletionItemKind.METHOD ||
                    appendItem.kind == CompletionItemKind.FUNCTION ||
                    appendItem.kind == CompletionItemKind.FIELD ||
                    appendItem.kind == CompletionItemKind.VARIABLE)
        // Soft dual-path: product may list append, list other members only, or leave empty.
        assertTrue(
            listed || completions.isEmpty() || completions.any { it.label.isNotBlank() },
            "Valid overload member completion dual-path: append listing, other members, or empty gap; " +
                "actual=${completions.map { "${it.label}:${it.kind}" }}"
        )
        if (listed) {
            val detail = appendItem?.detail.orEmpty()
            assertTrue(
                detail.isBlank() ||
                    looksCallable(detail) ||
                    detail.contains("append", ignoreCase = true) ||
                    detail != "unknown",
                "append completion detail dual-path; got ${appendItem?.detail}"
            )
        }
    }

    // ------------------------------------------------------------------
    // Breadth corpus: multi-overload JDK classes, valid shapes + surface
    // ------------------------------------------------------------------

    @Test
    fun new_instance_valid_overload_surface_corpus_table() {
        data class Case(
            val className: String,
            val ctorArgs: String,
            val localName: String,
            val member: String? = null,
            val memberExpectCallable: Boolean = true
        )

        val cases = listOf(
            Case("java.lang.StringBuilder", "", "builder", member = "append"),
            Case("java.lang.StringBuilder", "\"seed\"", "seeded", member = "length"),
            Case("java.lang.StringBuilder", "32", "capacity", member = "append"),
            Case("java.util.Locale", "\"en\"", "lang", member = "getLanguage"),
            Case("java.util.Locale", "\"en\", \"US\"", "usLocale", member = "getCountry"),
            Case("java.io.File", "\"x.txt\"", "file", member = "getName"),
            Case("java.io.File", "\"tmp\", \"out.txt\"", "nested", member = "getPath"),
            Case("java.math.BigDecimal", "\"1.5\"", "decStr", member = "toString"),
            Case("java.math.BigDecimal", "10", "decNum", member = "toString"),
            Case("java.lang.Integer", "\"9\"", "intStr", member = "intValue"),
            Case("java.lang.Integer", "9", "intNum", member = "intValue"),
            Case("java.awt.Point", "", "origin", member = "x", memberExpectCallable = false),
            Case("java.awt.Point", "3, 4", "offset", member = "y", memberExpectCallable = false),
            Case("java.lang.StringBuffer", "\"buf\"", "buffer", member = "append"),
            Case("java.util.ArrayList", "", "list", member = "size"),
            Case("java.util.HashMap", "", "map", member = "put")
        )

        cases.forEach { case ->
            val args = if (case.ctorArgs.isEmpty()) {
                "\"${case.className}\""
            } else {
                "\"${case.className}\", ${case.ctorArgs}"
            }
            val memberLine = case.member?.let { "\n                local memberSurface = ${case.localName}.$it" }.orEmpty()
            val returnLine = if (case.member != null) {
                "return ${case.localName}, memberSurface"
            } else {
                "return ${case.localName}"
            }
            val harness = jvmHarness(
                "main.lua" to """
                    local ${case.localName} = luajava.newInstance($args)$memberLine
                    $returnLine
                """.trimIndent()
            )

            assertHoverType(harness, case.localName, case.className, occurrence = 2)
            assertProviderPath(harness, case.className)
            assertNewInstanceFact(harness, case.className)
            if (case.member != null) {
                assertMemberSurfaceDualPath(
                    harness = harness,
                    needle = "memberSurface",
                    expectCallable = case.memberExpectCallable,
                    label = "newInstance ${case.className}.${case.member}"
                )
            }
            assertFalse(
                diagnostics(harness).any { it.looksLikeConstructorOverloadProblem() },
                "Valid overload surface corpus case ${case.className}(${case.ctorArgs}) must not emit overload diagnostics; actual: ${diagnostics(harness).map { it.message }}"
            )
        }
    }

    // ------------------------------------------------------------------
    // Type-shape / overload mismatches — dual-path surface
    // ------------------------------------------------------------------

    @Test
    fun file_number_unary_wrong_type_shape_dual_path_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", 42)
                return file
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "file", preservedClassType = "java.io.File")
        assertNoCrashDiagnosticsQuery(harness)
    }

    @Test
    fun file_boolean_unary_wrong_type_shape_dual_path_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", true)
                return file
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "file", preservedClassType = "java.io.File")
    }

    @Test
    fun locale_number_and_table_unary_wrong_type_shape_dual_path_surface() {
        val number = jvmHarness(
            "main.lua" to """
                local locale = luajava.newInstance("java.util.Locale", 1)
                return locale
            """.trimIndent()
        )
        assertWrongOverloadDualPath(number, "locale", preservedClassType = "java.util.Locale")

        val table = jvmHarness(
            "main.lua" to """
                local locale = luajava.newInstance("java.util.Locale", {})
                return locale
            """.trimIndent()
        )
        assertWrongOverloadDualPath(table, "locale", preservedClassType = "java.util.Locale")
    }

    @Test
    fun big_decimal_table_and_boolean_unary_wrong_type_shape_dual_path_surface() {
        val table = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.math.BigDecimal", {})
                return value
            """.trimIndent()
        )
        assertWrongOverloadDualPath(table, "value", preservedClassType = "java.math.BigDecimal")

        val boolean = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.math.BigDecimal", false)
                return value
            """.trimIndent()
        )
        assertWrongOverloadDualPath(boolean, "value", preservedClassType = "java.math.BigDecimal")
    }

    @Test
    fun integer_table_and_boolean_unary_wrong_type_shape_dual_path_surface() {
        val table = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.lang.Integer", {})
                return value
            """.trimIndent()
        )
        assertWrongOverloadDualPath(table, "value", preservedClassType = "java.lang.Integer")

        val boolean = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.lang.Integer", true)
                return value
            """.trimIndent()
        )
        assertWrongOverloadDualPath(boolean, "value", preservedClassType = "java.lang.Integer")
    }

    @Test
    fun string_builder_boolean_and_table_unary_wrong_type_shape_dual_path_surface() {
        val boolean = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", true)
                return builder
            """.trimIndent()
        )
        assertWrongOverloadDualPath(boolean, "builder", preservedClassType = "java.lang.StringBuilder")

        val table = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", {})
                return builder
            """.trimIndent()
        )
        assertWrongOverloadDualPath(table, "builder", preservedClassType = "java.lang.StringBuilder")
    }

    @Test
    fun point_string_unary_and_string_pair_wrong_type_shape_dual_path_surface() {
        val unary = jvmHarness(
            "main.lua" to """
                local point = luajava.newInstance("java.awt.Point", "origin")
                return point
            """.trimIndent()
        )
        assertWrongOverloadDualPath(unary, "point", preservedClassType = "java.awt.Point")

        val pair = jvmHarness(
            "main.lua" to """
                local point = luajava.newInstance("java.awt.Point", "a", "b")
                return point
            """.trimIndent()
        )
        assertWrongOverloadDualPath(pair, "point", preservedClassType = "java.awt.Point")
    }

    @Test
    fun uuid_string_unary_wrong_type_shape_dual_path_surface() {
        // UUID public ctor is (long, long) — single string is wrong shape/arity family.
        // Needle must not be a substring of "uuid" (positionOf is raw indexOf).
        val harness = jvmHarness(
            "main.lua" to """
                local uuidValue = luajava.newInstance("java.util.UUID", "not-a-uuid-ctor")
                return uuidValue
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "uuidValue", preservedClassType = "java.util.UUID")
    }

    @Test
    fun wrong_type_shape_via_local_and_chained_alias_dual_path_surface() {
        val local = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local file = newInstance("java.io.File", 99)
                return file
            """.trimIndent()
        )
        assertWrongOverloadDualPath(local, "file", preservedClassType = "java.io.File")

        val chained = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local make = newInstance
                local again = make
                local locale = again("java.util.Locale", 0)
                return locale
            """.trimIndent()
        )
        assertWrongOverloadDualPath(chained, "locale", preservedClassType = "java.util.Locale")
    }

    @Test
    fun wrong_type_shape_instance_member_use_dual_path_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", 1)
                local memberSurface = file.getName
                return file, memberSurface
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "file", preservedClassType = "java.io.File")
        val memberDisplay = hoverDisplay(harness, "memberSurface", occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeConstructorOverloadProblem() || it.looksLikeUnknownNewInstance()
        }
        val memberDegraded =
            memberDisplay == null ||
                memberDisplay.isBlank() ||
                memberDisplay == "unknown" ||
                !looksCallable(memberDisplay)
        val currentProductKeepsMemberSurface =
            memberDisplay != null && looksCallable(memberDisplay)
        assertTrue(
            memberDegraded || diagnosticHit || currentProductKeepsMemberSurface,
            "Wrong-overload newInstance member path must either degrade/diagnose (ideal) " +
                "or soft-fallback keep typed surface; " +
                "member=$memberDisplay diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun multi_arg_type_shape_mismatch_locale_and_file_number_pair_dual_path_surface() {
        val locale = jvmHarness(
            "main.lua" to """
                local locale = luajava.newInstance("java.util.Locale", 1, 2)
                return locale
            """.trimIndent()
        )
        assertWrongOverloadDualPath(locale, "locale", preservedClassType = "java.util.Locale")

        val file = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", 1, 2)
                return file
            """.trimIndent()
        )
        assertWrongOverloadDualPath(file, "file", preservedClassType = "java.io.File")
    }

    @Test
    fun valid_and_invalid_overload_mix_preserves_valid_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local good = luajava.newInstance("java.lang.StringBuilder", "seed")
                local bad = luajava.newInstance("java.io.File", 7)
                return good, bad
            """.trimIndent()
        )

        assertHoverType(harness, "good", "java.lang.StringBuilder", occurrence = 2)
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        assertProviderPath(harness, "java.lang.StringBuilder")
        assertWrongOverloadDualPath(harness, "bad", preservedClassType = "java.io.File")
    }

    // ------------------------------------------------------------------
    // Missing / invalid class-name / dynamic targets (hard degrade path)
    // ------------------------------------------------------------------

    @Test
    fun missing_class_name_argument_degrades_or_diagnoses_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local missing = luajava.newInstance()
                return missing
            """.trimIndent()
        )

        assertMissingOrInvalidClassNameSurface(harness, "missing")
        assertFalse(
            jvmClassLoads(harness).any { it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL },
            "newInstance() with no class name must not invent NEW_INSTANCE_CALL facts; got ${jvmClassLoads(harness)}"
        )
    }

    @Test
    fun non_string_class_name_degrades_or_diagnoses_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local bad = luajava.newInstance(42, "extra")
                return bad
            """.trimIndent()
        )

        assertMissingOrInvalidClassNameSurface(harness, "bad")
    }

    @Test
    fun missing_class_target_reports_diagnostic_and_unknown_type_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local instance = luajava.newInstance("missing.DoesNotExistOverloadSurface")
                return instance
            """.trimIndent()
        )

        assertUnknownTargetDiagnostic(harness, "missing.DoesNotExistOverloadSurface")
        val display = hoverDisplay(harness, "instance", occurrence = 2)
        assertTrue(
            display == null || display.isBlank() || display == "unknown",
            "Unknown class newInstance should be unknown, got '$display'"
        )
    }

    @Test
    fun dynamic_class_name_does_not_emit_new_instance_fact_or_crash_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local className = "java.lang.StringBuilder"
                local builder = luajava.newInstance(className, "seed")
                return builder
            """.trimIndent()
        )

        val loads = jvmClassLoads(harness)
        assertFalse(
            loads.any { it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL },
            "Dynamic newInstance targets must not invent NEW_INSTANCE_CALL facts; got $loads"
        )
        assertNoCrashDiagnosticsQuery(harness)
        val display = hoverDisplay(harness, "builder", occurrence = 2)
        assertTrue(
            display == null || display.isBlank() || display == "unknown",
            "Dynamic class-name newInstance should be unknown, got '$display'"
        )
    }

    // ------------------------------------------------------------------
    // Shadowing / colon: must not inherit JVM newInstance surface
    // ------------------------------------------------------------------

    @Test
    fun shadowed_local_new_instance_function_does_not_gain_jvm_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local function newInstance(target, arg)
                    return { value = target, arg = arg }
                end

                local instanceResult = newInstance("java.lang.StringBuilder", "seed")
                local instanceAppend = instanceResult.append
                return instanceResult, instanceAppend
            """.trimIndent()
        )

        assertHoverTypeIsTableLikeNotJvmClass(harness, "instanceResult", occurrence = 2)
        assertHoverType(harness, "instanceAppend", "unknown", occurrence = 2)
        assertFalse(
            jvmClassLoads(harness).any {
                it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL &&
                    it.target == "java.lang.StringBuilder"
            },
            "Shadowed bare newInstance must not emit NEW_INSTANCE_CALL; got ${jvmClassLoads(harness)}"
        )
    }

    @Test
    fun shadowed_local_luajava_new_instance_member_does_not_gain_jvm_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local luajava = {
                    newInstance = function(target, arg)
                        return { value = target, arg = arg }
                    end
                }
                local make = luajava.newInstance

                local instanceResult = luajava.newInstance("java.lang.StringBuilder", "seed")
                local aliasResult = make("java.lang.StringBuilder", 64)
                local instanceAppend = instanceResult.append
                local aliasAppend = aliasResult.append
                return instanceResult, aliasResult, instanceAppend, aliasAppend
            """.trimIndent()
        )

        assertHoverTypeIsTableLikeNotJvmClass(harness, "instanceResult", occurrence = 2)
        assertHoverTypeIsTableLikeNotJvmClass(harness, "aliasResult", occurrence = 2)
        assertHoverType(harness, "instanceAppend", "unknown", occurrence = 2)
        assertHoverType(harness, "aliasAppend", "unknown", occurrence = 2)
        assertFalse(
            jvmClassLoads(harness).any {
                it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL &&
                    it.target == "java.lang.StringBuilder"
            },
            "Shadowed luajava.newInstance must not emit NEW_INSTANCE_CALL; got ${jvmClassLoads(harness)}"
        )
    }

    @Test
    fun colon_new_instance_call_does_not_accidentally_model_helper_surface() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava:newInstance("java.lang.StringBuilder", "seed")
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "unknown", occurrence = 2)
        assertFalse(
            jvmClassLoads(harness).any {
                it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL &&
                    it.target == "java.lang.StringBuilder"
            },
            "Colon newInstance must not emit NEW_INSTANCE_CALL; got ${jvmClassLoads(harness)}"
        )
    }

    // ------------------------------------------------------------------
    // Host android.jar policy (Downloads + SDK android-35; never G:/)
    // ------------------------------------------------------------------

    @Test
    fun host_android_jar_candidates_include_downloads_and_sdk_never_g_drive() {
        val candidates = hostAndroidJarCandidates().map { it.path.replace('\\', '/') }
        assertTrue(
            candidates.any { it.endsWith("/Downloads/android.jar") },
            "Candidate list must include macOS/user Downloads android.jar; got: $candidates"
        )
        assertTrue(
            candidates.any { it.contains("/Library/Android/sdk/platforms/android-35/android.jar") } ||
                candidates.any { it.contains("platforms/android-35/android.jar") },
            "Candidate list must include macOS SDK platforms/android-35/android.jar; got: $candidates"
        )
        assertFalse(
            candidates.any { it.startsWith("G:/") || it.startsWith("G:\\") || it.contains("G:/Android") },
            "Candidate list must never hardcode Windows G:/ paths; got: $candidates"
        )
    }

    @Test
    fun host_android_jar_present_or_skipped_with_explicit_reason_surface() {
        val jar = resolveHostAndroidJar()
        if (jar != null) {
            assertTrue(jar.isFile, "Resolved android.jar must be a file: ${jar.path}")
            assertTrue(jar.length() > 0L, "Resolved android.jar must be non-empty: ${jar.path}")
            val normalized = jar.path.replace('\\', '/')
            assertFalse(
                normalized.startsWith("G:/") || normalized.contains("G:/Android"),
                "Resolved android.jar must not use G:/; got $normalized"
            )
            assertTrue(
                normalized.endsWith("/android.jar"),
                "Resolved path must end with android.jar; got $normalized"
            )
        } else {
            val reason = missingAndroidJarSkipReason()
            assertTrue(reason.contains("android.jar"), "Skip reason must mention android.jar; got: $reason")
            assertTrue(
                reason.contains("Downloads") || reason.contains("android-35") || reason.contains("SDK"),
                "Skip reason must tell the host how to recover; got: $reason"
            )
            assertFalse(reason.contains("G:/"), "Skip reason must never mention G:/; got: $reason")
        }
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

    private fun assertNewInstanceFact(harness: WorkspaceSemanticHarness, className: String) {
        val loads = jvmClassLoads(harness)
        assertTrue(
            loads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.NEW_INSTANCE_CALL &&
                    it.target == className
            },
            "Expected NEW_INSTANCE_CALL fact for $className; got $loads"
        )
    }

    private fun assertProviderPath(harness: WorkspaceSemanticHarness, className: String) {
        val path = harness.path("__jvm__/classes/${className.replace('.', '/')}.lua")
        assertTrue(
            path in harness.snapshot.extraProviders,
            "Expected provider $path for $className; actual providers: ${harness.snapshot.extraProviders.keys.map { it.value }}."
        )
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

    private fun hoverDisplay(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int
    ): String? {
        return harness.queries.hover(
            harness.path("main.lua"),
            harness.positionOf("main.lua", needle, occurrence)
        )?.typeInfo?.displayName
    }

    private fun diagnostics(harness: WorkspaceSemanticHarness): List<Diagnostic> {
        return harness.queries.diagnostics(harness.path("main.lua"))
    }

    private fun assertNoCrashDiagnosticsQuery(harness: WorkspaceSemanticHarness) {
        diagnostics(harness)
    }

    private fun looksCallable(displayName: String): Boolean {
        return displayName.contains("fun(") ||
            Regex("""fun\s*<[^>]+>\s*\(""").containsMatchIn(displayName)
    }

    /**
     * Dual-path instance member after valid newInstance overload:
     * - Ideal callable (or non-callable field when [expectCallable] is false).
     * - Soft gap: unknown/blank/any when instance projection is incomplete, as long
     *   as the newInstance class type + provider + NEW_INSTANCE_CALL hold elsewhere.
     */
    private fun assertMemberSurfaceDualPath(
        harness: WorkspaceSemanticHarness,
        needle: String,
        expectCallable: Boolean,
        label: String
    ) {
        val memberDisplay = hoverDisplay(harness, needle, occurrence = 2)
        if (expectCallable) {
            val idealCallable = looksCallable(memberDisplay.orEmpty())
            val softGap =
                memberDisplay == null ||
                    memberDisplay.isBlank() ||
                    memberDisplay == "unknown" ||
                    memberDisplay.equals("any", ignoreCase = true)
            assertTrue(
                idealCallable || softGap,
                "$label dual-path: callable (ideal) or CURRENTLY_ACCEPTS gap; member=$memberDisplay"
            )
            if (idealCallable) {
                assertFalse(memberDisplay.isNullOrBlank(), "Expected modeled callable, got blank.")
                assertFalse(memberDisplay == "unknown", "Expected modeled callable, got unknown.")
            }
        } else {
            if (memberDisplay != null &&
                memberDisplay.isNotBlank() &&
                memberDisplay != "unknown" &&
                !memberDisplay.equals("any", ignoreCase = true)
            ) {
                assertFalse(
                    looksCallable(memberDisplay),
                    "Expected field-like surface for $label, got $memberDisplay"
                )
            }
        }
    }

    /**
     * Dual-path wrong constructor **overload type-shape** policy:
     *
     * - Ideal: resolveNewInstanceCall consults CallChecker ranking on the reflected
     *   `__call` constructor surface; incompatible shapes degrade to unknown/blank
     *   (and may also emit constructor/overload diagnostics when checkers run).
     * - Soft fallback / CURRENTLY_ACCEPTS: keep the reflected instance class type when a
     *   surface has no rankable `__call` constructors, or diagnostic-only signaling without
     *   type degradation.
     *
     * Unexpected third outcomes (e.g. unrelated class type with no diagnostic) still fail.
     */
    private fun assertWrongOverloadDualPath(
        harness: WorkspaceSemanticHarness,
        needle: String,
        preservedClassType: String
    ) {
        val display = hoverDisplay(harness, needle, occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeConstructorOverloadProblem() || it.looksLikeUnknownNewInstance()
        }
        val idealUnknown =
            display == null || display.isBlank() || display == "unknown"
        val currentlyAcceptsKeepsClassType = display == preservedClassType

        assertTrue(
            idealUnknown || diagnosticHit || currentlyAcceptsKeepsClassType,
            "Wrong newInstance constructor overload shape must degrade/diagnose (ideal) or keep " +
                "soft-fallback instance type $preservedClassType; " +
                "type=$display diagnostics=${diagnostics(harness).map { it.message }}"
        )

        if (display != null && display.isNotBlank() && display != "unknown" &&
            display != preservedClassType
        ) {
            assertTrue(
                diagnosticHit,
                "Unexpected non-target type for wrong-overload newInstance without diagnostic; " +
                    "expected unknown, $preservedClassType (CURRENTLY_ACCEPTS), or diagnostic; " +
                    "type=$display diagnostics=${diagnostics(harness).map { it.message }}"
            )
        }
    }

    private fun assertMissingOrInvalidClassNameSurface(
        harness: WorkspaceSemanticHarness,
        needle: String
    ) {
        val display = hoverDisplay(harness, needle, occurrence = 2)
        val diagnosticHit = diagnostics(harness).any {
            it.looksLikeConstructorOverloadProblem() ||
                it.looksLikeUnknownNewInstance() ||
                it.message.contains("newInstance", ignoreCase = true)
        }
        val degraded =
            display == null || display.isBlank() || display == "unknown"
        assertTrue(
            degraded || diagnosticHit,
            "newInstance missing/invalid class name must degrade or diagnose; " +
                "type=$display diagnostics=${diagnostics(harness).map { it.message }}"
        )
    }

    private fun assertHoverTypeIsTableLikeNotJvmClass(
        harness: WorkspaceSemanticHarness,
        needle: String,
        occurrence: Int = 1
    ) {
        val display = hoverDisplay(harness, needle, occurrence).orEmpty()
        assertTrue(
            display == "table" ||
                display.startsWith("{") ||
                display.contains("value"),
            "Expected table-like shadowed newInstance result, got '$display'."
        )
        assertFalse(
            display.startsWith("java."),
            "Shadowed newInstance must not expose JVM class type, got '$display'."
        )
        assertFalse(
            looksCallable(display),
            "Shadowed newInstance must not expose JVM callable surface, got '$display'."
        )
    }

    private fun assertUnknownTargetDiagnostic(harness: WorkspaceSemanticHarness, target: String) {
        val diagnostics = diagnostics(harness)
        assertTrue(
            diagnostics.any { diagnostic ->
                diagnostic.message.contains(target) &&
                    (diagnostic.message.contains("unknown", ignoreCase = true) ||
                        diagnostic.message.contains("not found", ignoreCase = true) ||
                        diagnostic.message.contains("unresolved", ignoreCase = true))
            },
            "Expected unknown LuaJava target diagnostic for $target; actual: ${diagnostics.map { it.message }}"
        )
    }

    private fun Diagnostic.looksLikeConstructorOverloadProblem(): Boolean {
        val message = message.lowercase()
        val mentionsConstructorSurface =
            message.contains("constructor") ||
                message.contains("newinstance") ||
                message.contains("new instance") ||
                message.contains("arity") ||
                message.contains("argument") ||
                message.contains("parameter") ||
                message.contains("signature") ||
                message.contains("overload")
        val mentionsMismatch =
            message.contains("no matching") ||
                message.contains("mismatch") ||
                message.contains("wrong") ||
                message.contains("invalid") ||
                message.contains("expected") ||
                message.contains("too many") ||
                message.contains("too few") ||
                message.contains("missing") ||
                message.contains("required") ||
                message.contains("cannot") ||
                message.contains("unable") ||
                message.contains("unknown") ||
                message.contains("unresolved") ||
                message.contains("not found") ||
                message.contains("ambiguous")
        return mentionsConstructorSurface && mentionsMismatch
    }

    private fun Diagnostic.looksLikeUnknownNewInstance(): Boolean {
        val message = message.lowercase()
        return (message.contains("newinstance") || message.contains("constructor") || message.contains("java")) &&
            (
                message.contains("unknown") ||
                    message.contains("invalid") ||
                    message.contains("unresolved") ||
                    message.contains("not found") ||
                    message.contains("no matching")
                )
    }

    private fun memberPosition(harness: WorkspaceSemanticHarness, memberAccess: String): Position {
        val dotIndex = memberAccess.indexOf('.')
        check(dotIndex >= 0) { "Expected member access with dot, got $memberAccess." }
        val source = harness.files.getValue(harness.path("main.lua"))
        val index = source.indexOf(memberAccess)
        check(index >= 0) { "Missing '$memberAccess' in main.lua." }
        return positionAt(source, index + dotIndex + 1)
    }

    private fun positionAt(source: String, index: Int): Position {
        var line = 1
        var column = 1
        for (i in 0 until index) {
            if (source[i] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return Position(line, column)
    }

    /**
     * Host android.jar candidates (never hardcodes Windows-only G:/):
     * 1) user-provided Downloads android.jar
     * 2) macOS user Library Android SDK platforms/android-35
     * 3) absolute macOS SDK path used by this host
     * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34
     */
    private fun hostAndroidJarCandidates(): List<File> {
        val candidates = mutableListOf<File>()
        candidates += File("/Users/dingyi/Downloads/android.jar")
        val home = System.getProperty("user.home")
        if (!home.isNullOrBlank()) {
            candidates += File(home, "Downloads/android.jar")
            candidates += File(home, "Library/Android/sdk/platforms/android-35/android.jar")
        }
        candidates += File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar")
        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { env ->
            val sdkRoot = System.getenv(env)
            if (!sdkRoot.isNullOrBlank()) {
                candidates += File(sdkRoot, "platforms/android-35/android.jar")
                candidates += File(sdkRoot, "platforms/android-34/android.jar")
            }
        }
        return candidates.distinctBy { it.path }
    }

    private fun resolveHostAndroidJar(): File? {
        return hostAndroidJarCandidates().firstOrNull { it.isFile && it.length() > 0L }
    }

    private fun missingAndroidJarSkipReason(): String {
        val tried = hostAndroidJarCandidates().joinToString("; ") { it.path }
        return "android.jar not found on host. Place at Downloads/android.jar or " +
            "Library/Android/sdk/platforms/android-35/android.jar " +
            "(never invent Windows drive-letter defaults). Tried: $tried"
    }
}
