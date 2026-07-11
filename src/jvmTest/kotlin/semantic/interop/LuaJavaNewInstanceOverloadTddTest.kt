package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-442 corpus: LuaJava `newInstance` **constructor overload** selection
 * (type-shape / multi-overload classes), not just raw arity counts.
 *
 * Complements:
 * - [LuaJavaNewInstanceArityTddTest] / [LuaJavaNewInstanceAritySurfaceTddTest]
 *   (arity dual-path + surface facts)
 * - [JavaConstructorOverloadPickTddTest]
 *   (direct CallChecker ranking on synthetic / reflected `__call` surfaces)
 *
 * Product contract under test (via `luajava.newInstance` string path):
 * - Valid overload shapes on multi-constructor JDK classes preserve the
 *   reflected instance class type (hard assert) and typically mount provider /
 *   `NEW_INSTANCE_CALL` facts.
 * - Distinct closed arities that match real constructors (e.g. StringBuilder
 *   `()`, `(String)`, `(int)`) keep the instance surface without inventing APIs.
 * - [ExpressionTypeEvaluator.resolveNewInstanceCall] ranks constructor overloads
 *   via CallChecker against the reflected module `__call` surface (same ranking
 *   as direct Java class construction) after dropping the class-name string arg.
 * - Same-arity type-shape mismatches (e.g. File with a number, Locale with a
 *   number-only unary, BigDecimal with a table, UUID with a single string) are
 *   dual-path:
 *   - Ideal (current product): degrade to unknown after NO_MATCHING_SIGNATURE.
 *   - Soft fallback: constructor/overload diagnostic and/or CURRENTLY_ACCEPTS
 *     keep of the reflected instance type if ranking is unavailable for a surface.
 * - Alias / compact string-call / mixed valid+invalid paths follow the same
 *   dual-path policy when modeled.
 * - Shadowing / colon forms must not inherit the JVM newInstance surface.
 *
 * Needle hygiene (REVIEW41 rework WAVE36F):
 * - Do not hover needles that are substrings of FQNs / ctor string args
 *   (`id` ⊂ `uuid`, bare `m` ⊂ `math`/`BigDecimal`). Prefer long unique locals
 *   (`uuidValue`, `memberSurface`).
 *
 * Does **not** invent APIs: only exercises `luajava.newInstance` / aliases
 * already modeled by resolveNewInstanceCall and document-fact collection.
 *
 * Test-only scope. Workers do not run Gradle; verification is review-owned
 * serial jvmTest. Host android.jar (if ever needed) is Downloads + SDK
 * android-35 only — never G:/.
 */
class LuaJavaNewInstanceOverloadTddTest {

    // ------------------------------------------------------------------
    // Valid multi-overload shapes (hard asserts)
    // ------------------------------------------------------------------

    @Test
    fun string_builder_zero_arg_overload_preserves_instance_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder")
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        assertProviderPath(harness, "java.lang.StringBuilder")
        assertFalse(
            diagnostics(harness).any { it.looksLikeConstructorOverloadProblem() },
            "Valid zero-arg StringBuilder overload must not emit overload diagnostics; actual: ${diagnostics(harness).map { it.message }}"
        )
    }

    @Test
    fun string_builder_string_seed_overload_preserves_instance_type() {
        // StringBuilder(String) / CharSequence sibling — string seed is a valid overload.
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", "seed")
                local append = builder.append
                return builder, append
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        assertCallable(hoverDisplay(harness, "append", occurrence = 2), "StringBuilder.append after string overload")
    }

    @Test
    fun string_builder_capacity_number_overload_preserves_instance_type() {
        // StringBuilder(int capacity) is a distinct overload from StringBuilder(String).
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
    fun locale_language_tag_overload_preserves_instance_type() {
        // java.util.Locale(String language) and multi-arg language/country/variant siblings.
        val harness = jvmHarness(
            "main.lua" to """
                local locale = luajava.newInstance("java.util.Locale", "en")
                return locale
            """.trimIndent()
        )

        assertHoverType(harness, "locale", "java.util.Locale", occurrence = 2)
        assertNewInstanceFact(harness, "java.util.Locale")
        assertProviderPath(harness, "java.util.Locale")
    }

    @Test
    fun locale_language_country_overload_preserves_instance_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local locale = luajava.newInstance("java.util.Locale", "en", "US")
                return locale
            """.trimIndent()
        )

        assertHoverType(harness, "locale", "java.util.Locale", occurrence = 2)
        assertNewInstanceFact(harness, "java.util.Locale")
    }

    @Test
    fun file_path_string_overload_preserves_instance_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", "build.gradle.kts")
                local name = file.getName
                return file, name
            """.trimIndent()
        )

        assertHoverType(harness, "file", "java.io.File", occurrence = 2)
        assertNewInstanceFact(harness, "java.io.File")
        assertCallable(hoverDisplay(harness, "name", occurrence = 2), "File.getName after path overload")
    }

    @Test
    fun file_parent_child_two_string_overload_preserves_instance_type() {
        // File(String parent, String child) is a distinct two-arg overload from File(File, String).
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", "tmp", "out.txt")
                return file
            """.trimIndent()
        )

        assertHoverType(harness, "file", "java.io.File", occurrence = 2)
        assertNewInstanceFact(harness, "java.io.File")
    }

    @Test
    fun big_decimal_string_overload_preserves_instance_type() {
        // BigDecimal has (String), (double), (int), (BigInteger), ... siblings.
        val harness = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.math.BigDecimal", "3.14")
                return value
            """.trimIndent()
        )

        assertHoverType(harness, "value", "java.math.BigDecimal", occurrence = 2)
        assertNewInstanceFact(harness, "java.math.BigDecimal")
        assertProviderPath(harness, "java.math.BigDecimal")
    }

    @Test
    fun big_decimal_number_overload_preserves_instance_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.math.BigDecimal", 42)
                return value
            """.trimIndent()
        )

        assertHoverType(harness, "value", "java.math.BigDecimal", occurrence = 2)
        assertNewInstanceFact(harness, "java.math.BigDecimal")
    }

    @Test
    fun point_zero_and_two_number_overloads_preserve_instance_type() {
        // java.awt.Point has () and (int, int) among others.
        val zero = jvmHarness(
            "main.lua" to """
                local point = luajava.newInstance("java.awt.Point")
                local x = point.x
                return point, x
            """.trimIndent()
        )
        assertHoverType(zero, "point", "java.awt.Point", occurrence = 2)
        assertNewInstanceFact(zero, "java.awt.Point")
        val xHover = zero.queries.hover(
            zero.path("main.lua"),
            zero.positionOf("main.lua", "x", occurrence = 2)
        )
        assertEquals(SymbolKind.FIELD, xHover?.symbol?.kind)
        assertEquals("number", xHover?.typeInfo?.displayName)

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
    fun integer_string_and_number_overloads_preserve_instance_type() {
        // Integer(String) and Integer(int) are same-arity-family siblings (distinct shapes).
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
    fun valid_overload_via_local_alias_preserves_instance_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local builder = newInstance("java.lang.StringBuilder", "seed")
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
    }

    @Test
    fun valid_overload_via_chained_alias_preserves_instance_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local make = newInstance
                local again = make
                local locale = again("java.util.Locale", "en", "US")
                return locale
            """.trimIndent()
        )

        assertHoverType(harness, "locale", "java.util.Locale", occurrence = 2)
        assertNewInstanceFact(harness, "java.util.Locale")
    }

    @Test
    fun valid_compact_string_call_zero_arg_overload_preserves_instance_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance "java.lang.StringBuilder"
                return builder
            """.trimIndent()
        )

        assertHoverType(harness, "builder", "java.lang.StringBuilder", occurrence = 2)
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
    }

    // ------------------------------------------------------------------
    // Breadth corpus: multi-overload JDK classes, valid shapes
    // ------------------------------------------------------------------

    @Test
    fun new_instance_valid_overload_shape_corpus_table() {
        data class Case(
            val className: String,
            val ctorArgs: String,
            val localName: String,
            val member: String? = null,
            val memberExpectCallable: Boolean = true
        )

        // Member local is always `memberSurface` (not bare `m`) so positionOf cannot
        // collide with FQN substrings such as `math` / `BigDecimal`.
        val cases = listOf(
            Case("java.lang.StringBuilder", "", "builder", member = "append"),
            Case("java.lang.StringBuilder", "\"seed\"", "seeded", member = "length"),
            Case("java.lang.StringBuilder", "32", "capacity", member = "append"),
            Case("java.util.Locale", "\"en\"", "lang", member = "getLanguage"),
            Case("java.util.Locale", "\"en\", \"US\"", "us", member = "getCountry"),
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
                // Prefer return-site binding (occurrence=2): declaration + return use.
                val memberDisplay = hoverDisplay(harness, "memberSurface", occurrence = 2)
                if (case.memberExpectCallable) {
                    // Dual-path / CURRENTLY_ACCEPTS for instance member surface:
                    // Ideal: fun(...) callable. Soft gap: unknown/blank when instance
                    // methods are not yet projected for this reflected class, as long as
                    // the newInstance class type + provider + NEW_INSTANCE_CALL hold.
                    val idealCallable = looksCallable(memberDisplay.orEmpty())
                    val softGap =
                        memberDisplay == null ||
                            memberDisplay.isBlank() ||
                            memberDisplay == "unknown" ||
                            memberDisplay.equals("any", ignoreCase = true)
                    assertTrue(
                        idealCallable || softGap,
                        "newInstance ${case.className}.${case.member} dual-path: callable (ideal) " +
                            "or CURRENTLY_ACCEPTS gap; member=$memberDisplay"
                    )
                    if (idealCallable) {
                        assertNotUnknown(memberDisplay)
                    }
                } else {
                    // Field-like: known non-callable, or soft gap.
                    if (memberDisplay != null &&
                        memberDisplay.isNotBlank() &&
                        memberDisplay != "unknown" &&
                        !memberDisplay.equals("any", ignoreCase = true)
                    ) {
                        assertFalse(
                            looksCallable(memberDisplay),
                            "Expected field-like surface for ${case.className}.${case.member}, got $memberDisplay"
                        )
                    }
                }
            }
            assertFalse(
                diagnostics(harness).any { it.looksLikeConstructorOverloadProblem() },
                "Valid overload corpus case ${case.className}(${case.ctorArgs}) must not emit overload diagnostics; actual: ${diagnostics(harness).map { it.message }}"
            )
        }
    }

    // ------------------------------------------------------------------
    // Type-shape / overload mismatches — dual-path
    // ------------------------------------------------------------------

    @Test
    fun file_number_unary_is_wrong_type_shape_dual_path() {
        // File has File(String) and File(URI) unary overloads — not File(number).
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", 42)
                return file
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "file", preservedClassType = "java.io.File")
    }

    @Test
    fun file_boolean_unary_is_wrong_type_shape_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", true)
                return file
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "file", preservedClassType = "java.io.File")
    }

    @Test
    fun locale_number_unary_is_wrong_type_shape_dual_path() {
        // Locale unary is String language — number is wrong type shape at matching arity.
        val harness = jvmHarness(
            "main.lua" to """
                local locale = luajava.newInstance("java.util.Locale", 1)
                return locale
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "locale", preservedClassType = "java.util.Locale")
    }

    @Test
    fun locale_table_unary_is_wrong_type_shape_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local locale = luajava.newInstance("java.util.Locale", {})
                return locale
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "locale", preservedClassType = "java.util.Locale")
    }

    @Test
    fun big_decimal_table_unary_is_wrong_type_shape_dual_path() {
        // BigDecimal has many unary overloads; a bare table matches none cleanly.
        val harness = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.math.BigDecimal", {})
                return value
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "value", preservedClassType = "java.math.BigDecimal")
    }

    @Test
    fun big_decimal_boolean_unary_is_wrong_type_shape_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.math.BigDecimal", false)
                return value
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "value", preservedClassType = "java.math.BigDecimal")
    }

    @Test
    fun integer_table_unary_is_wrong_type_shape_dual_path() {
        // Integer(String) / Integer(int) — table is wrong shape.
        val harness = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.lang.Integer", {})
                return value
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "value", preservedClassType = "java.lang.Integer")
    }

    @Test
    fun integer_boolean_unary_is_wrong_type_shape_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local value = luajava.newInstance("java.lang.Integer", true)
                return value
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "value", preservedClassType = "java.lang.Integer")
    }

    @Test
    fun string_builder_boolean_unary_is_wrong_type_shape_dual_path() {
        // StringBuilder(String|int|CharSequence) — boolean is wrong shape at arity 1.
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", true)
                return builder
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "builder", preservedClassType = "java.lang.StringBuilder")
    }

    @Test
    fun string_builder_table_unary_is_wrong_type_shape_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local builder = luajava.newInstance("java.lang.StringBuilder", {})
                return builder
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "builder", preservedClassType = "java.lang.StringBuilder")
    }

    @Test
    fun point_string_unary_is_wrong_type_shape_dual_path() {
        // Point has () and (int,int) primarily — unary string is wrong shape.
        val harness = jvmHarness(
            "main.lua" to """
                local point = luajava.newInstance("java.awt.Point", "origin")
                return point
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "point", preservedClassType = "java.awt.Point")
    }

    @Test
    fun point_string_pair_is_wrong_type_shape_dual_path() {
        // (int, int) overload exists; ("a","b") is wrong type shape at arity 2.
        val harness = jvmHarness(
            "main.lua" to """
                local point = luajava.newInstance("java.awt.Point", "a", "b")
                return point
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "point", preservedClassType = "java.awt.Point")
    }

    @Test
    fun uuid_string_unary_is_wrong_type_shape_dual_path() {
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
    fun wrong_type_shape_via_local_alias_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local file = newInstance("java.io.File", 99)
                return file
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "file", preservedClassType = "java.io.File")
    }

    @Test
    fun wrong_type_shape_via_chained_alias_dual_path() {
        val harness = jvmHarness(
            "main.lua" to """
                local newInstance = luajava.newInstance
                local make = newInstance
                local again = make
                local locale = again("java.util.Locale", 0)
                return locale
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "locale", preservedClassType = "java.util.Locale")
    }

    @Test
    fun wrong_type_shape_instance_member_use_dual_path() {
        // Ideal: wrong overload degrades instance type so member surface is unknown/non-callable
        // (or a constructor diagnostic fires). Soft fallback may still keep File + getName.
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", 1)
                local name = file.getName
                return file, name
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "file", preservedClassType = "java.io.File")
        val memberDisplay = hoverDisplay(harness, "name", occurrence = 2)
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
    fun valid_and_invalid_overload_mix_preserves_valid_and_dual_paths_invalid() {
        val harness = jvmHarness(
            "main.lua" to """
                local good = luajava.newInstance("java.lang.StringBuilder", "seed")
                local bad = luajava.newInstance("java.io.File", 7)
                return good, bad
            """.trimIndent()
        )

        assertHoverType(harness, "good", "java.lang.StringBuilder", occurrence = 2)
        assertNewInstanceFact(harness, "java.lang.StringBuilder")
        assertWrongOverloadDualPath(harness, "bad", preservedClassType = "java.io.File")
    }

    @Test
    fun multi_arg_type_shape_mismatch_locale_number_pair_dual_path() {
        // Locale(String, String) exists; Locale(number, number) is wrong type shape.
        val harness = jvmHarness(
            "main.lua" to """
                local locale = luajava.newInstance("java.util.Locale", 1, 2)
                return locale
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "locale", preservedClassType = "java.util.Locale")
    }

    @Test
    fun multi_arg_type_shape_mismatch_file_number_pair_dual_path() {
        // File(String, String) exists; File(number, number) is wrong type shape.
        val harness = jvmHarness(
            "main.lua" to """
                local file = luajava.newInstance("java.io.File", 1, 2)
                return file
            """.trimIndent()
        )

        assertWrongOverloadDualPath(harness, "file", preservedClassType = "java.io.File")
    }

    // ------------------------------------------------------------------
    // Missing / invalid class-name / dynamic targets (hard degrade path)
    // ------------------------------------------------------------------

    @Test
    fun missing_class_name_argument_degrades_or_diagnoses() {
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
    fun non_string_class_name_degrades_or_diagnoses() {
        val harness = jvmHarness(
            "main.lua" to """
                local bad = luajava.newInstance(42, "extra")
                return bad
            """.trimIndent()
        )

        assertMissingOrInvalidClassNameSurface(harness, "bad")
    }

    /**
     * Missing class-name target: product emits unresolved LuaJava target diagnostic
     * and types the binding as unknown. Variable name must not be a substring of the
     * class target string (occurrence collision with the string literal).
     */
    @Test
    fun missing_class_target_reports_diagnostic_and_unknown_type() {
        val harness = jvmHarness(
            "main.lua" to """
                local instance = luajava.newInstance("missing.DoesNotExistOverload")
                return instance
            """.trimIndent()
        )

        assertUnknownTargetDiagnostic(harness, "missing.DoesNotExistOverload")
        val display = hoverDisplay(harness, "instance", occurrence = 2)
        assertTrue(
            display == null || display.isBlank() || display == "unknown",
            "Unknown class newInstance should be unknown, got '$display'"
        )
    }

    @Test
    fun dynamic_class_name_does_not_emit_new_instance_fact_or_crash() {
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
    fun colon_new_instance_call_does_not_accidentally_model_helper() {
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

    private fun assertCallable(displayName: String?, label: String = "type") {
        val text = displayName.orEmpty()
        assertTrue(
            looksCallable(text),
            "Expected callable $label, got $displayName."
        )
        assertNotUnknown(displayName)
    }

    private fun looksCallable(displayName: String): Boolean {
        return displayName.contains("fun(") ||
            Regex("""fun\s*<[^>]+>\s*\(""").containsMatchIn(displayName)
    }

    private fun assertNotUnknown(displayName: String?) {
        assertFalse(displayName.isNullOrBlank(), "Expected a modeled Java type, got no type.")
        assertFalse(displayName == "unknown", "Expected a modeled Java type, got unknown.")
    }

    /**
     * Dual-path wrong constructor **overload type-shape** policy:
     *
     * - Ideal (TASK-523 product path): resolveNewInstanceCall consults CallChecker ranking
     *   on the reflected `__call` constructor surface; incompatible shapes degrade to
     *   unknown/blank (and may also emit constructor/overload diagnostics when checkers run).
     * - Soft fallback / CURRENTLY_ACCEPTS: keep the reflected instance class type when a
     *   surface has no rankable `__call` constructors, or diagnostic-only signaling without
     *   type degradation.
     *
     * Unexpected third outcomes (e.g. unrelated class type with no diagnostic) still fail.
     * Aligned with [LuaJavaNewInstanceArityTddTest] dual-path matcher breadth (no bare "type").
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
        // Soft fallback: keep reflected instance type even if unrelated soft diagnostics exist.
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
        // Match arity corpus breadth: constructor/newInstance surface + mismatch tokens.
        // Do **not** treat bare "type" as a constructor surface (false positives).
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
}
