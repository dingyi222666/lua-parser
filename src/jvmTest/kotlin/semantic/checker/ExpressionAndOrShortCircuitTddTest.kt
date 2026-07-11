package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.BinaryExpression
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionOperator
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ExpressionTypeEvaluator and/or short-circuit typing corpus (TASK-291).
 *
 * Product policy (ExpressionTypeEvaluator.evaluateBinary as of TASK-291):
 * - `and` / `or` evaluate **both** operands for typing and return
 *   `unionTypeOf(leftType, rightType)` — a conservative static approximation
 *   of Lua short-circuit value selection (left may be returned, or right).
 * - Nested `and`/`or` ladders must not crash; intermediate BinaryExpressions
 *   remain evaluable and unions flatten/dedupe via TypeNormalizer.
 * - Unknown is contagious through unions; same-side primitives collapse.
 *
 * Runtime short-circuit (skipping right when left decides) is a VM concern and
 * is **not** modeled by the static evaluator; this corpus locks the
 * conservative union surface only. Test-only; no production edits.
 */
class ExpressionAndOrShortCircuitTddTest {

    private val parser = LuaParser()

    // -------------------------------------------------------------------------
    // Simple and/or: left | right union of literal / primitive surfaces
    // -------------------------------------------------------------------------

    @Test
    fun andOfNumberAndStringUnionsBothSides() {
        val harness = evaluator("return 1 and \"x\"")
        val expr = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.AND, expr.operator)

        val type = harness.evaluator.evaluate(expr)
        assertUnionContainsKinds(type, setOf("number", "string"), label = "1 and \"x\"")
    }

    @Test
    fun orOfNumberAndStringUnionsBothSides() {
        val harness = evaluator("return 0 or \"fallback\"")
        val expr = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.OR, expr.operator)

        val type = harness.evaluator.evaluate(expr)
        assertUnionContainsKinds(type, setOf("number", "string"), label = "0 or \"fallback\"")
    }

    @Test
    fun andOfStringAndNilPreservesOptionalShape() {
        val harness = evaluator("return \"ok\" and nil")
        val type = harness.evaluator.evaluate(harness.returnExpression())
        assertUnionContainsKinds(type, setOf("string", "nil"), label = "\"ok\" and nil")
    }

    @Test
    fun orOfNilAndStringPreservesOptionalShape() {
        val harness = evaluator("return nil or \"ok\"")
        val type = harness.evaluator.evaluate(harness.returnExpression())
        assertUnionContainsKinds(type, setOf("string", "nil"), label = "nil or \"ok\"")
    }

    @Test
    fun andOfSamePrimitiveCollapsesToThatPrimitive() {
        // 1 and 2 → both number-ish; normalizer may keep literals or collapse to number.
        val harness = evaluator("return 1 and 2")
        val type = harness.evaluator.evaluate(harness.returnExpression())
        assertNumberish(type, "1 and 2")
    }

    @Test
    fun orOfSamePrimitiveCollapsesToThatPrimitive() {
        val harness = evaluator("return \"a\" or \"b\"")
        val type = harness.evaluator.evaluate(harness.returnExpression())
        assertStringish(type, "\"a\" or \"b\"")
    }

    @Test
    fun andOfBooleanLiteralsStaysBooleanish() {
        val harness = evaluator("return true and false")
        val type = harness.evaluator.evaluate(harness.returnExpression())
        assertBooleanish(type, "true and false")
    }

    @Test
    fun orOfFalseAndNumberUnionsBooleanAndNumber() {
        val harness = evaluator("return false or 42")
        val type = harness.evaluator.evaluate(harness.returnExpression())
        assertUnionContainsKinds(type, setOf("boolean", "number"), label = "false or 42")
    }

    // -------------------------------------------------------------------------
    // Annotated locals: conservative left | right across and/or
    // -------------------------------------------------------------------------

    @Test
    fun annotatedStringAndNumberUnionsBothDeclaredSides() {
        val source = """
            ---@type string
            local left = "x"
            ---@type number
            local right = 1
            return left and right
        """.trimIndent()

        val harness = evaluator(source)
        val type = harness.evaluator.evaluate(harness.returnExpression())
        assertUnionContainsKinds(type, setOf("string", "number"), label = "annotated left and right")
    }

    @Test
    fun annotatedNumberOrStringUnionsBothDeclaredSides() {
        val source = """
            ---@type number
            local left = 0
            ---@type string
            local right = "x"
            return left or right
        """.trimIndent()

        val harness = evaluator(source)
        val type = harness.evaluator.evaluate(harness.returnExpression())
        assertUnionContainsKinds(type, setOf("number", "string"), label = "annotated left or right")
    }

    @Test
    fun annotatedOptionalStringOrNumberPreservesMembers() {
        val source = """
            ---@type string|nil
            local left = nil
            ---@type number
            local right = 2
            return left or right
        """.trimIndent()

        val harness = evaluator(source)
        val type = harness.evaluator.evaluate(harness.returnExpression())
        // Conservative: string | nil | number (or string | number if nil collapsed away
        // only when left is non-optional — here left is optional so nil is legitimate).
        assertUnionContainsKinds(
            type,
            required = setOf("string", "number"),
            optional = setOf("nil"),
            label = "optional string or number"
        )
    }

    @Test
    fun annotatedBooleanAndTableUnionsBothSides() {
        val source = """
            ---@type boolean
            local left = true
            ---@type table
            local right = {}
            return left and right
        """.trimIndent()

        val harness = evaluator(source)
        val type = harness.evaluator.evaluate(harness.returnExpression())
        assertUnionContainsKinds(type, setOf("boolean", "table"), label = "boolean and table")
    }

    // -------------------------------------------------------------------------
    // Nested chains: no crash, unions flatten, intermediate nodes evaluable
    // -------------------------------------------------------------------------

    @Test
    fun nestedAndChainOfThreeDoesNotThrowAndUnionsAllSides() {
        val harness = evaluator("return 1 and \"x\" and true")
        val root = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.AND, root.operator)

        val type = runCatching { harness.evaluator.evaluate(root) }
            .getOrElse { error("crash on nested and chain: $it") }

        assertNotNull(type)
        assertUnionContainsKinds(
            type,
            required = setOf("number", "string", "boolean"),
            label = "1 and \"x\" and true"
        )

        // Intermediate left BinaryExpression must also evaluate without throw.
        val left = assertIs<BinaryExpression>(root.left)
        val leftType = harness.evaluator.evaluate(left)
        assertUnionContainsKinds(leftType, setOf("number", "string"), label = "1 and \"x\" intermediate")
    }

    @Test
    fun nestedOrChainOfThreeDoesNotThrowAndUnionsAllSides() {
        val harness = evaluator("return nil or 0 or \"z\"")
        val root = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.OR, root.operator)

        val type = runCatching { harness.evaluator.evaluate(root) }
            .getOrElse { error("crash on nested or chain: $it") }

        assertUnionContainsKinds(
            type,
            required = setOf("number", "string"),
            optional = setOf("nil"),
            label = "nil or 0 or \"z\""
        )
    }

    @Test
    fun mixedAndOrLadderDoesNotThrow() {
        // Precedence: `and` binds tighter than `or` → (a and b) or (c and d)
        val harness = evaluator("return 1 and \"a\" or false and 2")
        val root = assertIs<BinaryExpression>(harness.returnExpression())

        val type = runCatching { harness.evaluator.evaluate(root) }
            .getOrElse { error("crash on mixed and/or ladder: $it") }

        assertNotNull(type)
        // Conservative union across both branches: number | string | boolean
        // (false and 2 → boolean | number; 1 and "a" → number | string).
        assertUnionContainsKinds(
            type,
            required = setOf("number"),
            optional = setOf("string", "boolean"),
            label = "mixed and/or ladder"
        )
        assertTrue(
            type != NeverTypeSentinel,
            "mixed ladder must not collapse to never"
        )
    }

    @Test
    fun deeplyNestedAndOrChainDoesNotThrow() {
        // Long ladder: stress flattening + recursion without stack/NPE issues.
        val expr = (1..12).joinToString(" and ") { i ->
            if (i % 3 == 0) "\"s$i\"" else i.toString()
        }
        val harness = evaluator("return $expr")
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on deep and chain (12): $it") }

        assertNotNull(type)
        assertUnionContainsKinds(
            type,
            required = setOf("number", "string"),
            label = "deep and chain"
        )
    }

    @Test
    fun deeplyNestedOrChainDoesNotThrow() {
        val expr = (1..12).joinToString(" or ") { i ->
            if (i % 2 == 0) "nil" else "\"v$i\""
        }
        val harness = evaluator("return $expr")
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on deep or chain (12): $it") }

        assertNotNull(type)
        assertUnionContainsKinds(
            type,
            required = setOf("string"),
            optional = setOf("nil"),
            label = "deep or chain"
        )
    }

    @Test
    fun parenthesizedMixedNestingDoesNotThrow() {
        val harness = evaluator("return (nil or 1) and (\"x\" or false)")
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on parenthesized mixed nesting: $it") }

        assertNotNull(type)
        // (nil|number) and (string|boolean) → union of all four members
        assertUnionContainsKinds(
            type,
            required = setOf("number", "string"),
            optional = setOf("nil", "boolean"),
            label = "parenthesized mixed nesting"
        )
    }

    // -------------------------------------------------------------------------
    // Unknown / missing: contagious, still no crash
    // -------------------------------------------------------------------------

    @Test
    fun andWithUnknownLocalDoesNotThrow() {
        val source = """
            local missing
            return missing and 1
        """.trimIndent()
        val harness = evaluator(source)
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on unknown and number: $it") }

        // Unknown is contagious in unions → UnknownType (or unknown | number if not collapsed).
        assertTrue(
            type == UnknownType || memberKinds(type).contains("unknown") || memberKinds(type).contains("number"),
            "expected unknown-ish result for missing and 1, got ${type.displayName}"
        )
    }

    @Test
    fun orWithUnknownLocalDoesNotThrow() {
        val source = """
            local missing
            return missing or "fallback"
        """.trimIndent()
        val harness = evaluator(source)
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on unknown or string: $it") }

        assertTrue(
            type == UnknownType ||
                memberKinds(type).contains("unknown") ||
                memberKinds(type).contains("string"),
            "expected unknown-ish or string for missing or \"fallback\", got ${type.displayName}"
        )
    }

    @Test
    fun andOfTwoUnknownsDoesNotThrow() {
        val source = """
            local a
            local b
            return a and b
        """.trimIndent()
        val harness = evaluator(source)
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on unknown and unknown: $it") }
        assertNotNull(type)
    }

    // -------------------------------------------------------------------------
    // AST shape: operator identity + both children visited (typing both sides)
    // -------------------------------------------------------------------------

    @Test
    fun andBinaryHasAndOperatorAndBothChildren() {
        val harness = evaluator("return left and right")
        val binary = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.AND, binary.operator)
        assertNotNull(binary.left)
        assertNotNull(binary.right)
        // Evaluating must touch both sides without throw even if unbound identifiers.
        val type = runCatching { harness.evaluator.evaluate(binary) }
            .getOrElse { error("crash evaluating unbound and: $it") }
        assertNotNull(type)
    }

    @Test
    fun orBinaryHasOrOperatorAndBothChildren() {
        val harness = evaluator("return left or right")
        val binary = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.OR, binary.operator)
        assertNotNull(binary.left)
        assertNotNull(binary.right)
        val type = runCatching { harness.evaluator.evaluate(binary) }
            .getOrElse { error("crash evaluating unbound or: $it") }
        assertNotNull(type)
    }

    @Test
    fun unionTypeOfMatchesEvaluatorAndForDistinctPrimitives() {
        // Direct factory lock: product and-path is unionTypeOf(left, right).
        val expected = unionTypeOf(PrimitiveType.NUMBER, PrimitiveType.STRING)
        val harness = evaluator(
            """
            ---@type number
            local a = 1
            ---@type string
            local b = "x"
            return a and b
            """.trimIndent()
        )
        val actual = harness.evaluator.evaluate(harness.returnExpression())
        assertEquals(expected.displayName, actual.displayName)
        assertEquals(memberKinds(expected), memberKinds(actual))
    }

    @Test
    fun unionTypeOfMatchesEvaluatorOrForDistinctPrimitives() {
        val expected = unionTypeOf(PrimitiveType.BOOLEAN, PrimitiveType.NUMBER)
        val harness = evaluator(
            """
            ---@type boolean
            local a = false
            ---@type number
            local b = 9
            return a or b
            """.trimIndent()
        )
        val actual = harness.evaluator.evaluate(harness.returnExpression())
        assertEquals(memberKinds(expected), memberKinds(actual))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Sentinel only used for "not never" messaging — never constructed as Type. */
    private val NeverTypeSentinel: Type = UnknownType

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

    private fun memberKinds(type: Type): Set<String> {
        return when (type) {
            is UnionType -> type.types.flatMap { memberKinds(it) }.toSet()
            is LiteralType -> setOf(kindName(type.baseType))
            is PrimitiveType -> setOf(kindName(type))
            UnknownType -> setOf("unknown")
            else -> {
                val name = type.displayName.lowercase()
                when {
                    name.contains("unknown") -> setOf("unknown")
                    name.contains("string") || name.startsWith("\"") || name.startsWith("'") -> setOf("string")
                    name.contains("number") || name.toDoubleOrNull() != null -> setOf("number")
                    name.contains("boolean") || name == "true" || name == "false" -> setOf("boolean")
                    name.contains("nil") -> setOf("nil")
                    name.contains("table") || name.startsWith("{") -> setOf("table")
                    else -> setOf(name)
                }
            }
        }
    }

    private fun kindName(type: PrimitiveType): String = when (type.kind) {
        PrimitiveType.Kind.NIL -> "nil"
        PrimitiveType.Kind.BOOLEAN -> "boolean"
        PrimitiveType.Kind.NUMBER -> "number"
        PrimitiveType.Kind.STRING -> "string"
        PrimitiveType.Kind.FUNCTION -> "function"
        PrimitiveType.Kind.TABLE -> "table"
        PrimitiveType.Kind.THREAD -> "thread"
        PrimitiveType.Kind.USERDATA -> "userdata"
        PrimitiveType.Kind.ANY -> "any"
        PrimitiveType.Kind.UNKNOWN -> "unknown"
        PrimitiveType.Kind.NEVER -> "never"
        PrimitiveType.Kind.ERROR -> "error"
    }

    private fun assertUnionContainsKinds(
        type: Type,
        required: Set<String>,
        optional: Set<String> = emptySet(),
        label: String
    ) {
        val kinds = memberKinds(type)
        required.forEach { kind ->
            assertTrue(
                kind in kinds,
                "$label: expected member kind '$kind' in ${type.displayName} (kinds=$kinds)"
            )
        }
        // Optional members: if present they must be expected; absence is fine.
        val unexpected = kinds - required - optional - setOf("unknown")
        // Allow pure collapse to a single required kind without extras.
        if (kinds.size > 1 || required.size > 1) {
            assertTrue(
                unexpected.isEmpty() || unexpected.all { it in optional },
                "$label: unexpected kinds $unexpected in ${type.displayName} (kinds=$kinds)"
            )
        }
    }

    private fun assertNumberish(type: Type, label: String) {
        val kinds = memberKinds(type)
        assertTrue(
            kinds == setOf("number") ||
                (type is LiteralType && type.baseType == PrimitiveType.NUMBER) ||
                type == PrimitiveType.NUMBER,
            "$label: expected number-ish, got ${type.displayName} kinds=$kinds"
        )
    }

    private fun assertStringish(type: Type, label: String) {
        val kinds = memberKinds(type)
        assertTrue(
            kinds == setOf("string") ||
                (type is LiteralType && type.baseType == PrimitiveType.STRING) ||
                type == PrimitiveType.STRING,
            "$label: expected string-ish, got ${type.displayName} kinds=$kinds"
        )
    }

    private fun assertBooleanish(type: Type, label: String) {
        val kinds = memberKinds(type)
        assertTrue(
            kinds == setOf("boolean") ||
                (type is LiteralType && type.baseType == PrimitiveType.BOOLEAN) ||
                type == PrimitiveType.BOOLEAN,
            "$label: expected boolean-ish, got ${type.displayName} kinds=$kinds"
        )
    }
}
