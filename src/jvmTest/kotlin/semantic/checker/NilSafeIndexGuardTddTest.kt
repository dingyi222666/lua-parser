package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.IndexExpression
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.checker.MemberFailureReason
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-331 corpus: nil-safe index / guard patterns must not crash the checker.
 *
 * Product policy (ExpressionTypeEvaluator.evaluateIndexExpression / MemberResolver.resolveIndex):
 * - Indexing an unresolved / nil / unknown base degrades to [UnknownType] (or null resolution
 *   type → UnknownType at the evaluator boundary) without throwing.
 * - Optional table unions (`T | nil`) resolve index only on table branches; missing/nil
 *   branches do not invent members.
 * - Guard-style `and`/`or` before index remains evaluable (conservative union typing).
 *
 * Test-only. No production edits. Verification is review-owned (no Gradle).
 */
class NilSafeIndexGuardTddTest {

    private val parser = LuaParser()

    @Test
    fun indexOnExplicitNilBaseDoesNotThrowAndYieldsUnknown() {
        val harness = evaluator(
            """
            ---@type nil
            local base = nil
            return base[1]
            """.trimIndent()
        )
        val expr = assertIs<IndexExpression>(harness.returnExpression())
        val type = runCatching { harness.evaluator.evaluate(expr) }
            .getOrElse { error("nil base index must not throw: $it") }
        assertUnknownish(type, "nil base index")
    }

    @Test
    fun memberOnExplicitNilBaseDoesNotThrowAndYieldsUnknown() {
        val harness = evaluator(
            """
            ---@type nil
            local base = nil
            return base.field
            """.trimIndent()
        )
        val expr = assertIs<MemberExpression>(harness.returnExpression())
        val type = runCatching { harness.evaluator.evaluate(expr) }
            .getOrElse { error("nil base member must not throw: $it") }
        assertUnknownish(type, "nil base member")
    }

    @Test
    fun indexOnUnboundIdentifierDoesNotThrow() {
        val harness = evaluator("return missing[\"key\"]")
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("unbound index must not throw: $it") }
        assertUnknownish(type, "unbound index")
    }

    @Test
    fun indexOnUnknownLocalDoesNotThrow() {
        val harness = evaluator(
            """
            local missing
            return missing[1]
            """.trimIndent()
        )
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("unknown local index must not throw: $it") }
        assertUnknownish(type, "unknown local index")
    }

    @Test
    fun optionalTableUnionIndexDoesNotThrowAndKeepsKnownFieldWhenPresent() {
        val table = TableType(fields = mapOf("value" to PrimitiveType.STRING))
        val optional = unionTypeOf(table, PrimitiveType.NIL)
        val binder = emptyBinder()
        val resolver = MemberResolver(binder)
        val resolution = runCatching {
            resolver.resolveMember(
                optional,
                "value",
                preferMethod = false,
                lexicalScopeId = binder.scopeGraph.rootScope.id
            )
        }.getOrElse { error("optional union member resolve must not throw: $it") }

        val type = resolution.type
        if (type != null) {
            assertTrue(
                type == PrimitiveType.STRING ||
                    type == UnknownType ||
                    (type is UnionType && type.types.any {
                        it == PrimitiveType.STRING || it == UnknownType || it == PrimitiveType.NIL
                    }),
                "optional table.field should be string-ish/unknown/nil, got ${type.displayName}"
            )
        }
    }

    @Test
    fun optionalTableUnionMissingMemberDoesNotThrow() {
        val table = TableType(fields = mapOf("value" to PrimitiveType.NUMBER))
        val optional = unionTypeOf(table, PrimitiveType.NIL)
        val binder = emptyBinder()
        val resolver = MemberResolver(binder)
        val resolution = runCatching {
            resolver.resolveMember(
                optional,
                "missing",
                preferMethod = false,
                lexicalScopeId = binder.scopeGraph.rootScope.id
            )
        }.getOrElse { error("missing member on optional must not throw: $it") }

        if (resolution.type != null) {
            assertUnknownish(resolution.type!!, "missing on optional")
        }
        assertTrue(
            resolution.failureReason == null ||
                resolution.failureReason == MemberFailureReason.MISSING_MEMBER ||
                resolution.failureReason == MemberFailureReason.UNSUPPORTED_BASE_TYPE,
            "unexpected failure ${resolution.failureReason}"
        )
    }

    @Test
    fun nestedIndexChainThroughNilDoesNotThrow() {
        val harness = evaluator(
            """
            ---@type nil
            local root = nil
            return root[1][2].leaf
            """.trimIndent()
        )
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("nested nil index chain must not throw: $it") }
        assertUnknownish(type, "nested nil chain")
    }

    @Test
    fun guardStyleAndBeforeIndexDoesNotThrow() {
        val harness = evaluator(
            """
            ---@type table|nil
            local t = nil
            return t and t["key"]
            """.trimIndent()
        )
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("guard-style and before index must not throw: $it") }
        assertNotNull(type)
    }

    @Test
    fun guardStyleOrFallbackAfterIndexDoesNotThrow() {
        val harness = evaluator(
            """
            local t
            return t["key"] or "fallback"
            """.trimIndent()
        )
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("or-fallback after index must not throw: $it") }
        assertNotNull(type)
    }

    @Test
    fun indexOnPrimitiveNumberDoesNotThrow() {
        val harness = evaluator("return (1)[\"x\"]")
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("number base index must not throw: $it") }
        assertUnknownish(type, "number base index")
    }

    @Test
    fun indexWithNilKeyOnKnownTableDoesNotThrow() {
        val harness = evaluator(
            """
            local t = { a = 1 }
            return t[nil]
            """.trimIndent()
        )
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("nil key index must not throw: $it") }
        assertNotNull(type)
    }

    @Test
    fun deepUnresolvedIndexLadderDoesNotThrow() {
        val harness = evaluator("return a.b.c[1].d[e].f")
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("deep unresolved ladder must not throw: $it") }
        assertUnknownish(type, "deep unresolved ladder")
    }

    private fun evaluator(source: String): EvalHarness {
        val chunk = parser.parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return EvalHarness(
            chunk = chunk,
            binder = resolved,
            evaluator = ExpressionTypeEvaluator(resolved)
        )
    }

    private fun emptyBinder(): BinderPassResult {
        val chunk = parser.parse("return nil")
        return TypeResolver().resolve(BinderPass().bind(chunk, CommentAttachPass().attach(chunk)))
    }

    private data class EvalHarness(
        val chunk: ChunkNode,
        val binder: BinderPassResult,
        val evaluator: ExpressionTypeEvaluator
    ) {
        fun returnExpression(): ExpressionNode {
            val statement = chunk.body.returnStatement
                ?: error("expected chunk body returnStatement")
            return statement.arguments.singleOrNull()
                ?: error("expected single return argument, got ${statement.arguments.size}")
        }
    }

    private fun assertUnknownish(type: Type, label: String) {
        assertTrue(
            type == UnknownType ||
                type.displayName.equals("unknown", ignoreCase = true) ||
                type.displayName.equals("any", ignoreCase = true) ||
                (type is UnionType && type.types.any { it == UnknownType || it == PrimitiveType.NIL }),
            "$label: expected unknown/any/nil-union degrade, got ${type.displayName}"
        )
        if (type is PrimitiveType) {
            assertTrue(
                type == PrimitiveType.NIL || type == PrimitiveType.ANY || type == PrimitiveType.UNKNOWN,
                "$label: unexpected concrete primitive ${type.displayName}"
            )
        }
    }
}
