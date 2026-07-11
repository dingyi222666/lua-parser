package semantic.checker

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.api.Diagnostic
import io.github.dingyi222666.luaparser.semantic.api.DiagnosticSeverity
import io.github.dingyi222666.luaparser.semantic.model.SemanticModel
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-362 corpus: product [checker.member.missing] diagnostic surface.
 *
 * Product snapshot (ExpressionUsageChecker / CheckerPass / DiagnosticCodeStability):
 * - Stable code string: `checker.member.missing`.
 * - Emission is **Java diagnostic surface only**
 *   ([ExpressionUsageChecker.isJavaDiagnosticSurface]):
 *   `JavaClassType` / `JavaInstanceType` / `JavaArrayType`, Java-provider
 *   [ClassType] names (`contains '.'` / `'$'`), Java-backed [ModuleType]
 *   (`__class` field), constrained type params, and all-Java unions.
 * - Plain Lua tables, EmmyLua `@class` without Java names, freeform locals, and
 *   builtins **do not** invent `checker.member.missing` when a field is absent.
 * - Member form message: `Unknown Java member '<name>' on <baseDisplay>.`
 * - Index form message: `Unknown Java member on <baseDisplay>.`
 * - Default severity remains [DiagnosticSeverity.ERROR].
 *
 * Complements:
 * - [DiagnosticCodeStabilityTddTest] (catalog membership of the code string)
 * - [CallArityFreeformSilenceTddTest] (product catalog alignment)
 * - [GlobalWriteReadonlyDiagnosticTddTest] (silence + reserved code pattern)
 * - [MemberResolverTest] / nested / multi-level (resolver failure reasons, not
 *   pipeline diagnostic codes)
 * - interop Java static field / enum missing surfaces (hover unknown degrade)
 *
 * SemanticModel is obtained from [SemanticPipeline.analyze] for pure Lua silence
 * fixtures, and from workspace file models (via [JvmWorkspaceEngine]) for
 * Java-backed emission fixtures. Test-only. Verification is review-owned (no Gradle).
 */
class MemberMissingDiagnosticTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()

    // -------------------------------------------------------------------------
    // Product code string + catalog lock
    // -------------------------------------------------------------------------

    @Test
    fun memberMissingCodeStringIsLocked() {
        assertEquals("checker.member.missing", MEMBER_MISSING_CODE)
        assertTrue(MEMBER_MISSING_CODE in PRODUCT_KNOWN_PIPELINE_CODES)
        assertTrue(MEMBER_MISSING_CODE.startsWith("checker.member."))
        assertFalse(MEMBER_MISSING_CODE.startsWith("checker.call."))
        assertFalse(MEMBER_MISSING_CODE.startsWith("checker.global."))
    }

    @Test
    fun reservedNonProductMemberCodesRemainAbsentFromCatalog() {
        // Future freeform-table / pure-Lua member codes must not collide with product.
        for (reserved in RESERVED_NON_PRODUCT_MEMBER_CODES) {
            assertFalse(
                reserved in PRODUCT_KNOWN_PIPELINE_CODES,
                "reserved non-product code $reserved collides with product catalog"
            )
            assertFalse(reserved == MEMBER_MISSING_CODE)
        }
    }

    // -------------------------------------------------------------------------
    // Plain Lua silence (no Java surface → no member.missing)
    // -------------------------------------------------------------------------

    @Test
    fun plainTableMissingFieldDoesNotEmitMemberMissing() {
        val model = analyze(
            """
            local t = { a = 1 }
            return t.missingField
            """.trimIndent()
        )

        assertNoCode(model, MEMBER_MISSING_CODE)
        assertFalse(codesOf(model).any { it.startsWith("checker.member.") })
    }

    @Test
    fun plainTableMissingIndexDoesNotEmitMemberMissing() {
        val model = analyze(
            """
            local t = { a = 1 }
            return t["missingKey"]
            """.trimIndent()
        )

        assertNoCode(model, MEMBER_MISSING_CODE)
    }

    @Test
    fun emmyClassMissingFieldDoesNotEmitMemberMissingWithoutJavaName() {
        // EmmyLua ClassType without '.' / '$' is not a Java diagnostic surface.
        val model = analyze(
            """
            ---@class Point
            ---@field x number
            ---@field y number
            ---@type Point
            local p = { x = 1, y = 2 }
            return p.z
            """.trimIndent()
        )

        assertNoCode(model, MEMBER_MISSING_CODE)
        assertFalse(codesOf(model).any { it.startsWith("checker.member.") })
    }

    @Test
    fun freeformLocalMissingMemberChainDoesNotEmitMemberMissing() {
        val model = analyze(
            """
            local root = {}
            return root.child.grandchild
            """.trimIndent()
        )

        assertNoCode(model, MEMBER_MISSING_CODE)
    }

    @Test
    fun builtinMathMissingMemberDoesNotEmitMemberMissing() {
        // Builtin module surface is not Java-backed for this diagnostic gate.
        val model = analyze(
            """
            return math.notARealMathMember
            """.trimIndent()
        )

        assertNoCode(model, MEMBER_MISSING_CODE)
    }

    @Test
    fun nilBaseMemberDoesNotInventMemberMissingCode() {
        val model = analyze(
            """
            ---@type nil
            local base = nil
            return base.field
            """.trimIndent()
        )

        assertNoCode(model, MEMBER_MISSING_CODE)
    }

    @Test
    fun undefinedGlobalMemberAccessDoesNotEmitMemberMissing() {
        val model = analyze(
            """
            return totallyUndefinedGlobal.field
            """.trimIndent()
        )

        assertNoCode(model, MEMBER_MISSING_CODE)
        assertFalse(codesOf(model).any { it.startsWith("checker.global.") })
    }

    @Test
    fun plainLuaMatchingMemberAccessEmitsNoCheckerCodes() {
        val model = analyze(
            """
            local t = { name = "ok" }
            return t.name
            """.trimIndent()
        )

        assertEquals(emptySet(), codesOf(model))
    }

    // -------------------------------------------------------------------------
    // Java-backed emission (JvmWorkspaceEngine → SemanticModel diagnostics)
    // -------------------------------------------------------------------------

    @Test
    fun bindClassMissingStaticMemberEmitsMemberMissingCode() {
        val model = jvmModel(
            """
            local Integer = luajava.bindClass("java.lang.Integer")
            local value = Integer.noSuchStaticField
            return value
            """.trimIndent()
        )

        assertHasCode(model, MEMBER_MISSING_CODE)
        val hits = diagnosticsWithCode(model, MEMBER_MISSING_CODE)
        assertTrue(hits.isNotEmpty())
        assertTrue(
            hits.any { diagnostic ->
                diagnostic.message.contains("noSuchStaticField") &&
                    diagnostic.message.contains("Unknown Java member", ignoreCase = true)
            },
            "expected Unknown Java member message naming noSuchStaticField; " +
                "got=${hits.map { it.message }}"
        )
        assertTrue(hits.all { it.severity == DiagnosticSeverity.ERROR })
        assertOnlyKnownCodes(codesOf(model))
    }

    @Test
    fun bindClassMissingStaticMemberMessageLocksMemberForm() {
        val model = jvmModel(
            """
            local System = luajava.bindClass("java.lang.System")
            return System.notARealStaticField
            """.trimIndent()
        )

        val hits = diagnosticsWithCode(model, MEMBER_MISSING_CODE)
        assertTrue(hits.isNotEmpty(), "expected $MEMBER_MISSING_CODE; codes=${codesOf(model)}")
        // Product: "Unknown Java member '<name>' on <baseDisplay>."
        assertTrue(
            hits.any { it.message.startsWith("Unknown Java member 'notARealStaticField' on ") },
            "member form message must start with Unknown Java member 'notARealStaticField' on …; " +
                "got=${hits.map { it.message }}"
        )
        assertTrue(hits.all { it.message.endsWith(".") })
    }

    @Test
    fun bindClassKnownStaticMemberDoesNotEmitMemberMissing() {
        val model = jvmModel(
            """
            local Integer = luajava.bindClass("java.lang.Integer")
            local max = Integer.MAX_VALUE
            return max
            """.trimIndent()
        )

        assertNoCode(model, MEMBER_MISSING_CODE)
    }

    @Test
    fun bindClassMissingInstanceMemberEmitsMemberMissingCode() {
        val model = jvmModel(
            """
            local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
            local builder = StringBuilder()
            local bad = builder.noSuchInstanceMember
            return bad
            """.trimIndent()
        )

        assertHasCode(model, MEMBER_MISSING_CODE)
        val hits = diagnosticsWithCode(model, MEMBER_MISSING_CODE)
        assertTrue(
            hits.any { it.message.contains("noSuchInstanceMember") },
            "instance missing member must name the identifier; got=${hits.map { it.message }}"
        )
        assertOnlyKnownCodes(codesOf(model))
    }

    @Test
    fun bindClassKnownInstanceMemberDoesNotEmitMemberMissing() {
        val model = jvmModel(
            """
            local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
            local builder = StringBuilder()
            local append = builder.append
            return append
            """.trimIndent()
        )

        assertNoCode(model, MEMBER_MISSING_CODE)
    }

    @Test
    fun bindClassMissingStaticMemberViaIndexEmitsMemberMissingCode() {
        val model = jvmModel(
            """
            local System = luajava.bindClass("java.lang.System")
            return System["notARealIndexedStatic"]
            """.trimIndent()
        )

        assertHasCode(model, MEMBER_MISSING_CODE)
        val hits = diagnosticsWithCode(model, MEMBER_MISSING_CODE)
        // Index form: "Unknown Java member on <baseDisplay>."
        assertTrue(
            hits.any {
                it.message.startsWith("Unknown Java member on ") &&
                    !it.message.contains("'notARealIndexedStatic'")
            },
            "index form should not quote the index token the way member form quotes names; " +
                "got=${hits.map { it.message }}"
        )
        assertOnlyKnownCodes(codesOf(model))
    }

    @Test
    fun missingMemberOnSecondBoundClassIsolatedFromKnownField() {
        val model = jvmModel(
            """
            local Locale = luajava.bindClass("java.util.Locale")
            local Integer = luajava.bindClass("java.lang.Integer")
            local root = Locale.ROOT
            local bad = Integer.noSuchIntegerField
            return bad
            """.trimIndent()
        )

        assertHasCode(model, MEMBER_MISSING_CODE)
        val hits = diagnosticsWithCode(model, MEMBER_MISSING_CODE)
        assertTrue(
            hits.any { it.message.contains("noSuchIntegerField") },
            "missing Integer field must diagnose; got=${hits.map { it.message }}"
        )
        // Known Locale.ROOT must not invent a second missing diagnostic for ROOT.
        assertFalse(
            hits.any { it.message.contains("'ROOT'") },
            "known ROOT must not emit member.missing; got=${hits.map { it.message }}"
        )
        assertOnlyKnownCodes(codesOf(model))
    }

    @Test
    fun mixedKnownAndMissingMembersOnlyDiagnoseMissing() {
        val model = jvmModel(
            """
            local System = luajava.bindClass("java.lang.System")
            local out = System.out
            local missing = System.notARealStaticField
            return missing
            """.trimIndent()
        )

        val hits = diagnosticsWithCode(model, MEMBER_MISSING_CODE)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.message.contains("notARealStaticField") })
        assertFalse(hits.any { it.message.contains("'out'") })
        assertEquals(
            hits.map { it.code }.distinct(),
            listOf(MEMBER_MISSING_CODE)
        )
    }

    @Test
    fun memberMissingDiagnosticIsDeterministicAcrossRepeatedAnalyze() {
        val source =
            """
            local Integer = luajava.bindClass("java.lang.Integer")
            local value = Integer.noSuchStaticField
            return value
            """.trimIndent()

        val first = jvmModel(source).getDiagnostics().map(::diagnosticFingerprint)
        val second = jvmModel(source).getDiagnostics().map(::diagnosticFingerprint)

        assertEquals(first, second, "member.missing diagnostics must be stable across repeated analyze")
        assertTrue(first.any { it[0] == MEMBER_MISSING_CODE })
    }

    @Test
    fun memberMissingCorpusTableCoversSilenceAndJavaEmission() {
        data class Case(
            val name: String,
            val source: String,
            val expectMemberMissing: Boolean,
            val useJvm: Boolean
        )

        val cases = listOf(
            Case(
                name = "plain-table-silence",
                source = """
                    local t = { a = 1 }
                    return t.missing
                """.trimIndent(),
                expectMemberMissing = false,
                useJvm = false
            ),
            Case(
                name = "emmy-class-silence",
                source = """
                    ---@class Box
                    ---@field value number
                    ---@type Box
                    local b = { value = 1 }
                    return b.other
                """.trimIndent(),
                expectMemberMissing = false,
                useJvm = false
            ),
            Case(
                name = "java-static-missing",
                source = """
                    local Integer = luajava.bindClass("java.lang.Integer")
                    return Integer.noSuchStaticField
                """.trimIndent(),
                expectMemberMissing = true,
                useJvm = true
            ),
            Case(
                name = "java-static-known",
                source = """
                    local Integer = luajava.bindClass("java.lang.Integer")
                    return Integer.MAX_VALUE
                """.trimIndent(),
                expectMemberMissing = false,
                useJvm = true
            ),
            Case(
                name = "java-instance-missing",
                source = """
                    local StringBuilder = luajava.bindClass("java.lang.StringBuilder")
                    local b = StringBuilder()
                    return b.noSuchInstanceMember
                """.trimIndent(),
                expectMemberMissing = true,
                useJvm = true
            ),
            Case(
                name = "java-index-missing",
                source = """
                    local System = luajava.bindClass("java.lang.System")
                    return System["notARealIndexedStatic"]
                """.trimIndent(),
                expectMemberMissing = true,
                useJvm = true
            )
        )

        val failures = mutableListOf<String>()
        for (case in cases) {
            val model = if (case.useJvm) jvmModel(case.source) else analyze(case.source)
            val codes = codesOf(model)
            val has = MEMBER_MISSING_CODE in codes
            if (has != case.expectMemberMissing) {
                failures += "${case.name}: expectMemberMissing=${case.expectMemberMissing} has=$has codes=$codes"
            }
            val unknown = codes - PRODUCT_KNOWN_PIPELINE_CODES
            if (unknown.isNotEmpty()) {
                failures += "${case.name}: unknown product codes=$unknown all=$codes"
            }
            for (reserved in RESERVED_NON_PRODUCT_MEMBER_CODES) {
                if (reserved in codes) {
                    failures += "${case.name}: reserved non-product $reserved emitted"
                }
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    private fun analyze(source: String): SemanticModel =
        pipeline.analyze(parser.parse(source)).model

    private fun jvmModel(source: String): SemanticModel {
        val harness = WorkspaceSemanticHarness.build(
            "main.lua" to source,
            engine = JvmWorkspaceEngine()
        )
        val path = harness.path("main.lua")
        val model = harness.snapshot.files[path]?.semanticFile?.model
        checkNotNull(model) { "expected SemanticModel for main.lua after JvmWorkspaceEngine analyze" }
        return model
    }

    private fun codesOf(model: SemanticModel): Set<String> =
        model.getDiagnostics().mapNotNull { it.code }.toSet()

    private fun diagnosticsWithCode(model: SemanticModel, code: String): List<Diagnostic> =
        model.getDiagnostics().filter { it.code == code }

    private fun assertNoCode(model: SemanticModel, code: String) {
        assertFalse(
            code in codesOf(model),
            "expected no $code; got=${codesOf(model)} messages=${model.getDiagnostics().map { it.message }}"
        )
    }

    private fun assertHasCode(model: SemanticModel, code: String) {
        assertTrue(
            code in codesOf(model),
            "expected $code; got=${codesOf(model)} messages=${model.getDiagnostics().map { it.message }}"
        )
    }

    private fun assertOnlyKnownCodes(codes: Set<String>) {
        val unexpected = codes - PRODUCT_KNOWN_PIPELINE_CODES
        assertTrue(
            unexpected.isEmpty(),
            "new diagnostic codes require explicit assertion updates; unexpected=$unexpected all=$codes"
        )
    }

    private fun diagnosticFingerprint(diagnostic: Diagnostic): List<Any?> =
        listOf(
            diagnostic.code,
            diagnostic.message,
            diagnostic.severity,
            diagnostic.range?.start?.line,
            diagnostic.range?.start?.column,
            diagnostic.range?.end?.line,
            diagnostic.range?.end?.column
        )

    private companion object {
        const val MEMBER_MISSING_CODE = "checker.member.missing"

        /**
         * Reserved future pure-Lua / freeform member codes — **not** product today.
         * Product only emits [MEMBER_MISSING_CODE] on Java diagnostic surfaces.
         */
        val RESERVED_NON_PRODUCT_MEMBER_CODES: Set<String> = setOf(
            "checker.member.undefined",
            "checker.member.unknown",
            "checker.table.missingField",
            "checker.field.missing"
        )

        /**
         * Full product pipeline catalog (same membership as
         * DiagnosticCodeStabilityTddTest.KNOWN_PIPELINE_CODES).
         */
        val PRODUCT_KNOWN_PIPELINE_CODES: Set<String> = setOf(
            "checker.function.return.extraValues",
            "checker.function.return.typeMismatch",
            "checker.function.signature.missingParamName",
            "checker.function.signature.multipleVararg",
            "checker.function.signature.namedVararg",
            "checker.function.signature.optionalVararg",
            "checker.function.signature.parameterContractMismatch",
            "checker.function.signature.requiredAfterOptional",
            "checker.function.signature.unknownParam",
            "checker.function.signature.varargNotLast",
            "checker.local.unused",
            "checker.luajava.target.unresolved",
            MEMBER_MISSING_CODE
        )
    }
}
