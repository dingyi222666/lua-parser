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
 * ExpressionTypeEvaluator binary-ops typing corpus (TASK-458).
 *
 * Product policy (ExpressionTypeEvaluator.evaluateBinary):
 * - Arithmetic / power / floor-div / mod / bitwise / shifts → [PrimitiveType.NUMBER]
 *   regardless of operand static types (Lua coerces; evaluator does not narrow).
 * - Concat (`..`) → [PrimitiveType.STRING].
 * - Relational / equality (`<` `>` `<=` `>=` `==` `~=`) → [PrimitiveType.BOOLEAN].
 * - Logical `and` / `or` → [unionTypeOf] of both sides (conservative short-circuit;
 *   full ladder corpus lives in ExpressionAndOrShortCircuitTddTest / TASK-291).
 * - Nested homogeneous chains stay on the same result kind; intermediate
 *   BinaryExpressions remain evaluable without throw. Concat chains use honest
 *   Lua right-associativity (intermediate is root.right), matching power (^).
 * - Unknown / unbound identifiers on either side do not crash; arithmetic still
 *   yields number, concat string, relational boolean (operand types are evaluated
 *   but not required for the operator-kind result).
 *
 * Dual-path notes:
 * - IDEAL: same operator-kind results as product today (no operand-sensitive
 *   arithmetic refinement yet).
 * - CURRENTLY_ACCEPTS:
 *   - Binary XOR (`a ~ b` / BIT_TILDE) is **not** listed in evaluateBinary's
 *     number branch (only unary ~ is). Result may be UnknownType until product
 *     adds BIT_TILDE to the binary number set; tests accept number|unknown.
 *   - and/or union members may still collapse via TypeNormalizer (TASK-291).
 *
 * Test-only; no production edits. Verification is review-owned serial (TASK-043).
 */
class ExpressionTypeEvaluatorBinaryOpsTddTest {

    private val parser = LuaParser()

    // -------------------------------------------------------------------------
    // Arithmetic → number
    // -------------------------------------------------------------------------

    @Test
    fun addOfNumberLiteralsIsNumber() {
        assertBinaryResultKind("return 1 + 2", ExpressionOperator.ADD, "number")
    }

    @Test
    fun subOfNumberLiteralsIsNumber() {
        assertBinaryResultKind("return 5 - 3", ExpressionOperator.MINUS, "number")
    }

    @Test
    fun mulOfNumberLiteralsIsNumber() {
        assertBinaryResultKind("return 4 * 6", ExpressionOperator.MULT, "number")
    }

    @Test
    fun divOfNumberLiteralsIsNumber() {
        assertBinaryResultKind("return 8 / 2", ExpressionOperator.DIV, "number")
    }

    @Test
    fun modOfNumberLiteralsIsNumber() {
        assertBinaryResultKind("return 7 % 3", ExpressionOperator.MOD, "number")
    }

    @Test
    fun floorDivOfNumberLiteralsIsNumber() {
        assertBinaryResultKind("return 7 // 2", ExpressionOperator.DOUBLE_DIV, "number")
    }

    @Test
    fun powOfNumberLiteralsIsNumber() {
        assertBinaryResultKind("return 2 ^ 8", ExpressionOperator.BIT_EXP, "number")
    }

    @Test
    fun annotatedNumberArithmeticStaysNumber() {
        val source = """
            ---@type number
            local a = 1
            ---@type number
            local b = 2
            return a + b * (a - b) / b % 3 // 1 ^ 2
        """.trimIndent()
        val harness = evaluator(source)
        val type = harness.evaluator.evaluate(harness.returnExpression())
        assertNumberish(type, "annotated arithmetic ladder")
    }

    @Test
    fun arithmeticWithUnboundIdentifierStillNumberNoThrow() {
        val harness = evaluator("return missing + 1")
        val binary = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.ADD, binary.operator)
        val type = runCatching { harness.evaluator.evaluate(binary) }
            .getOrElse { error("crash on unbound arithmetic: $it") }
        assertNumberish(type, "missing + 1")
    }

    @Test
    fun arithmeticWithUnknownLocalStillNumberNoThrow() {
        val source = """
            local missing
            return missing * 3
        """.trimIndent()
        val harness = evaluator(source)
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on unknown * number: $it") }
        assertNumberish(type, "missing * 3")
    }

    @Test
    fun nestedAdditiveChainStaysNumberAndIntermediatesEvaluate() {
        val harness = evaluator("return 1 + 2 + 3 + 4")
        val root = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.ADD, root.operator)
        val type = runCatching { harness.evaluator.evaluate(root) }
            .getOrElse { error("crash on nested add chain: $it") }
        assertNumberish(type, "1+2+3+4")
        val left = assertIs<BinaryExpression>(root.left)
        assertNumberish(harness.evaluator.evaluate(left), "1+2+3 intermediate")
    }

    @Test
    fun mixedMulDivModChainStaysNumber() {
        val harness = evaluator("return 2 * 3 / 4 % 5")
        val type = harness.evaluator.evaluate(harness.returnExpression())
        assertNumberish(type, "2*3/4%5")
    }

    @Test
    fun floorDivChainStaysNumber() {
        val harness = evaluator("return 20 // 3 // 2")
        val root = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.DOUBLE_DIV, root.operator)
        assertNumberish(harness.evaluator.evaluate(root), "20//3//2")
    }

    @Test
    fun powerRightAssocShapeStillNumber() {
        // Lua power is right-associative: 2^3^2 → 2^(3^2)
        val harness = evaluator("return 2 ^ 3 ^ 2")
        val root = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.BIT_EXP, root.operator)
        assertNumberish(harness.evaluator.evaluate(root), "2^3^2")
        // Right child should be the nested power when right-assoc is preserved.
        val right = root.right
        if (right is BinaryExpression) {
            assertEquals(ExpressionOperator.BIT_EXP, right.operator)
            assertNumberish(harness.evaluator.evaluate(right), "3^2 intermediate")
        }
    }

    // -------------------------------------------------------------------------
    // Bitwise / shifts → number
    // -------------------------------------------------------------------------

    @Test
    fun bitAndIsNumber() {
        assertBinaryResultKind("return 5 & 3", ExpressionOperator.BIT_AND, "number")
    }

    @Test
    fun bitOrIsNumber() {
        assertBinaryResultKind("return 5 | 2", ExpressionOperator.BIT_OR, "number")
    }

    @Test
    fun bitXorBinaryTildeDualPath() {
        // Lua 5.3 binary XOR is `~`. Parser may emit BinaryExpression(BIT_TILDE).
        // Product evaluateBinary does **not** list BIT_TILDE (only unary evaluateUnary
        // maps BIT_TILDE → number), so binary XOR currently falls through to UnknownType.
        // Dual-path:
        //   CURRENTLY_ACCEPTS → unknown (binary BIT_TILDE missing from evaluateBinary)
        //   IDEAL             → number (same as other bitwise ops)
        val harness = evaluator("return 5 ~ 3")
        val expr = harness.returnExpression()
        val type = runCatching { harness.evaluator.evaluate(expr) }
            .getOrElse { error("crash evaluating binary xor: $it") }
        if (expr is BinaryExpression) {
            // Soft operator lock: prefer BIT_TILDE when present.
            assertTrue(
                expr.operator == ExpressionOperator.BIT_TILDE ||
                    expr.operator.name.contains("BIT") ||
                    expr.operator == ExpressionOperator.NE,
                "5 ~ 3: unexpected binary operator ${expr.operator}"
            )
        }
        val kinds = memberKinds(type)
        assertTrue(
            kinds == setOf("number") ||
                kinds == setOf("unknown") ||
                type == PrimitiveType.NUMBER ||
                type == UnknownType,
            "binary XOR dual-path: expected number (IDEAL) or unknown (CURRENTLY_ACCEPTS), " +
                "got ${type.displayName} kinds=$kinds"
        )
    }

    @Test
    fun shiftLeftIsNumber() {
        assertBinaryResultKind("return 1 << 4", ExpressionOperator.BIT_LT, "number")
    }

    @Test
    fun shiftRightIsNumber() {
        assertBinaryResultKind("return 16 >> 2", ExpressionOperator.BIT_GT, "number")
    }

    @Test
    fun bitwiseChainStaysNumber() {
        val harness = evaluator("return 1 | 2 & 3 << 1 >> 1")
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on bitwise chain: $it") }
        assertNumberish(type, "bitwise chain")
    }

    @Test
    fun annotatedBitwiseOperandsStillNumber() {
        val source = """
            ---@type number
            local a = 0xFF
            ---@type number
            local b = 0x0F
            return a & b | a << 1
        """.trimIndent()
        val harness = evaluator(source)
        assertNumberish(harness.evaluator.evaluate(harness.returnExpression()), "annotated bitwise")
    }

    // -------------------------------------------------------------------------
    // Concat → string
    // -------------------------------------------------------------------------

    @Test
    fun concatOfStringLiteralsIsString() {
        assertBinaryResultKind("return \"a\" .. \"b\"", ExpressionOperator.CONCAT, "string")
    }

    @Test
    fun concatOfNumberAndStringIsString() {
        // Product does not refine operands; concat result is always string.
        assertBinaryResultKind("return 1 .. \"x\"", ExpressionOperator.CONCAT, "string")
    }

    @Test
    fun concatChainStaysStringAndIntermediatesEvaluate() {
        // Lua concat is right-associative: "a" .. "b" .. "c" → "a" .. ("b" .. "c")
        // Keep honest tree shape; intermediate BinaryExpression is the right child.
        val harness = evaluator("return \"a\" .. \"b\" .. \"c\"")
        val root = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.CONCAT, root.operator)
        assertStringish(harness.evaluator.evaluate(root), "a..b..c")
        val intermediate = assertIs<BinaryExpression>(root.right)
        assertEquals(ExpressionOperator.CONCAT, intermediate.operator)
        assertStringish(harness.evaluator.evaluate(intermediate), "b..c intermediate")
    }

    @Test
    fun annotatedConcatStaysString() {
        val source = """
            ---@type string
            local a = "hello"
            ---@type number
            local b = 42
            return a .. b
        """.trimIndent()
        val harness = evaluator(source)
        assertStringish(harness.evaluator.evaluate(harness.returnExpression()), "annotated concat")
    }

    @Test
    fun concatWithUnknownLocalStillStringNoThrow() {
        val source = """
            local missing
            return missing .. "!"
        """.trimIndent()
        val harness = evaluator(source)
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on unknown concat: $it") }
        assertStringish(type, "missing .. \"!\"")
    }

    // -------------------------------------------------------------------------
    // Relational / equality → boolean
    // -------------------------------------------------------------------------

    @Test
    fun ltIsBoolean() {
        assertBinaryResultKind("return 1 < 2", ExpressionOperator.LT, "boolean")
    }

    @Test
    fun gtIsBoolean() {
        assertBinaryResultKind("return 3 > 1", ExpressionOperator.GT, "boolean")
    }

    @Test
    fun leIsBoolean() {
        assertBinaryResultKind("return 1 <= 1", ExpressionOperator.LE, "boolean")
    }

    @Test
    fun geIsBoolean() {
        assertBinaryResultKind("return 2 >= 1", ExpressionOperator.GE, "boolean")
    }

    @Test
    fun eqIsBoolean() {
        assertBinaryResultKind("return 1 == 1", ExpressionOperator.EQ, "boolean")
    }

    @Test
    fun neIsBoolean() {
        assertBinaryResultKind("return 1 ~= 2", ExpressionOperator.NE, "boolean")
    }

    @Test
    fun equalityOfDistinctPrimitivesIsBoolean() {
        assertBinaryResultKind("return \"x\" == 1", ExpressionOperator.EQ, "boolean")
    }

    @Test
    fun relationalWithAnnotatedLocalsIsBoolean() {
        val source = """
            ---@type number
            local a = 1
            ---@type number
            local b = 2
            return a < b
        """.trimIndent()
        val harness = evaluator(source)
        assertBooleanish(harness.evaluator.evaluate(harness.returnExpression()), "a < b")
    }

    @Test
    fun relationalWithUnknownStillBooleanNoThrow() {
        val source = """
            local missing
            return missing ~= nil
        """.trimIndent()
        val harness = evaluator(source)
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on unknown relational: $it") }
        assertBooleanish(type, "missing ~= nil")
    }

    // -------------------------------------------------------------------------
    // Logical and/or: dual-path bridge to TASK-291 (union of both sides)
    // -------------------------------------------------------------------------

    @Test
    fun andOfDistinctPrimitivesUnionsBothSides() {
        val harness = evaluator("return 1 and \"x\"")
        val binary = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.AND, binary.operator)
        val type = harness.evaluator.evaluate(binary)
        assertUnionContainsKinds(type, setOf("number", "string"), label = "1 and \"x\"")
    }

    @Test
    fun orOfDistinctPrimitivesUnionsBothSides() {
        val harness = evaluator("return false or 42")
        val binary = assertIs<BinaryExpression>(harness.returnExpression())
        assertEquals(ExpressionOperator.OR, binary.operator)
        val type = harness.evaluator.evaluate(binary)
        assertUnionContainsKinds(type, setOf("boolean", "number"), label = "false or 42")
    }

    @Test
    fun unionTypeOfMatchesEvaluatorAndForAnnotatedPrimitives() {
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
        assertEquals(memberKinds(expected), memberKinds(actual))
    }

    // -------------------------------------------------------------------------
    // Nested mixed operator ladders (precedence stress, no crash)
    // -------------------------------------------------------------------------

    @Test
    fun arithmeticRelationalMixDoesNotThrow() {
        // (1+2) < (3*4) → boolean
        val harness = evaluator("return 1 + 2 < 3 * 4")
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on arithmetic/relational mix: $it") }
        assertBooleanish(type, "1+2 < 3*4")
    }

    @Test
    fun concatAndArithmeticNeighborsDoNotThrow() {
        // Concat binds lower than arithmetic in Lua: 1+2 .. 3+4 → (1+2)..(3+4) → string
        val harness = evaluator("return 1 + 2 .. 3 + 4")
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on concat/arithmetic neighbors: $it") }
        assertStringish(type, "1+2 .. 3+4")
    }

    @Test
    fun relationalAndLogicalMixDoesNotThrow() {
        // (1 < 2) and (3 > 0) → boolean | boolean → booleanish union
        val harness = evaluator("return 1 < 2 and 3 > 0")
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on relational/logical mix: $it") }
        assertBooleanish(type, "1<2 and 3>0")
    }

    @Test
    fun deeplyNestedArithmeticChainDoesNotThrow() {
        val expr = (1..16).joinToString(" + ") { it.toString() }
        val harness = evaluator("return $expr")
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on deep add chain: $it") }
        assertNumberish(type, "deep add chain")
    }

    @Test
    fun parenthesizedMixedBinaryNestingDoesNotThrow() {
        val harness = evaluator("return ((1 + 2) * 3) .. (\"x\" .. \"y\")")
        val type = runCatching { harness.evaluator.evaluate(harness.returnExpression()) }
            .getOrElse { error("crash on parenthesized mixed nesting: $it") }
        assertStringish(type, "((1+2)*3)..(\"x\"..\"y\")")
    }

    // -------------------------------------------------------------------------
    // AST operator identity inventory (batch)
    // -------------------------------------------------------------------------

    @Test
    fun batchBinaryOperatorKindInventory() {
        val cases = listOf(
            BinaryOpCase("return 1 + 2", ExpressionOperator.ADD, "number"),
            BinaryOpCase("return 9 - 4", ExpressionOperator.MINUS, "number"),
            BinaryOpCase("return 3 * 5", ExpressionOperator.MULT, "number"),
            BinaryOpCase("return 9 / 3", ExpressionOperator.DIV, "number"),
            BinaryOpCase("return 10 % 4", ExpressionOperator.MOD, "number"),
            BinaryOpCase("return 10 // 3", ExpressionOperator.DOUBLE_DIV, "number"),
            BinaryOpCase("return 2 ^ 10", ExpressionOperator.BIT_EXP, "number"),
            BinaryOpCase("return 7 & 3", ExpressionOperator.BIT_AND, "number"),
            BinaryOpCase("return 4 | 1", ExpressionOperator.BIT_OR, "number"),
            BinaryOpCase("return 1 << 3", ExpressionOperator.BIT_LT, "number"),
            BinaryOpCase("return 8 >> 1", ExpressionOperator.BIT_GT, "number"),
            BinaryOpCase("return \"p\" .. \"q\"", ExpressionOperator.CONCAT, "string"),
            BinaryOpCase("return 1 < 2", ExpressionOperator.LT, "boolean"),
            BinaryOpCase("return 2 > 1", ExpressionOperator.GT, "boolean"),
            BinaryOpCase("return 1 <= 2", ExpressionOperator.LE, "boolean"),
            BinaryOpCase("return 2 >= 2", ExpressionOperator.GE, "boolean"),
            BinaryOpCase("return 1 == 1", ExpressionOperator.EQ, "boolean"),
            BinaryOpCase("return 1 ~= 0", ExpressionOperator.NE, "boolean"),
            BinaryOpCase("return true and false", ExpressionOperator.AND, "boolean"),
            BinaryOpCase("return nil or 1", ExpressionOperator.OR, "numberish-or-union")
        )
        assertTrue(cases.size >= 20, "inventory must cover full binary surface; got ${cases.size}")

        cases.forEach { case ->
            val harness = evaluator(case.source)
            val binary = assertIs<BinaryExpression>(
                harness.returnExpression(),
                "expected BinaryExpression for ${case.source}"
            )
            assertEquals(case.operator, binary.operator, "operator for ${case.source}")
            val type = runCatching { harness.evaluator.evaluate(binary) }
                .getOrElse { error("crash evaluating ${case.source}: $it") }
            when (case.expectedKind) {
                "number" -> assertNumberish(type, case.source)
                "string" -> assertStringish(type, case.source)
                "boolean" -> assertBooleanish(type, case.source)
                "numberish-or-union" -> {
                    val kinds = memberKinds(type)
                    assertTrue(
                        "number" in kinds || "nil" in kinds,
                        "${case.source}: expected number/nil union, got ${type.displayName} kinds=$kinds"
                    )
                }
                else -> error("unknown expected kind ${case.expectedKind}")
            }
        }
    }

    @Test
    fun batchBinaryOperatorChildrenPresent() {
        val sources = listOf(
            "return 1 + 2",
            "return 1 - 2",
            "return 1 * 2",
            "return 1 / 2",
            "return 1 % 2",
            "return 1 // 2",
            "return 1 ^ 2",
            "return 1 & 2",
            "return 1 | 2",
            "return 1 << 2",
            "return 1 >> 2",
            "return \"a\" .. \"b\"",
            "return 1 < 2",
            "return 1 > 2",
            "return 1 <= 2",
            "return 1 >= 2",
            "return 1 == 2",
            "return 1 ~= 2",
            "return 1 and 2",
            "return 1 or 2"
        )
        sources.forEach { source ->
            val harness = evaluator(source)
            val binary = assertIs<BinaryExpression>(harness.returnExpression())
            assertNotNull(binary.left, "left child for $source")
            assertNotNull(binary.right, "right child for $source")
            val type = runCatching { harness.evaluator.evaluate(binary) }
                .getOrElse { error("crash evaluating children for $source: $it") }
            assertNotNull(type)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private data class BinaryOpCase(
        val source: String,
        val operator: ExpressionOperator,
        val expectedKind: String
    )

    private fun assertBinaryResultKind(
        source: String,
        operator: ExpressionOperator,
        expectedKind: String,
        allowUnaryFallback: Boolean = false
    ) {
        val harness = evaluator(source)
        val expr = harness.returnExpression()
        val binary = when {
            expr is BinaryExpression -> expr
            allowUnaryFallback -> {
                // `a ~ b` may parse as binary XOR (BIT_OR-like) or fail; if product
                // surfaces a non-binary for `~`, accept numberish evaluation of the
                // whole return expression as CURRENTLY_ACCEPTS dual-path.
                val type = runCatching { harness.evaluator.evaluate(expr) }
                    .getOrElse { error("crash evaluating $source: $it") }
                when (expectedKind) {
                    "number" -> assertNumberish(type, "$source (unary/binary dual-path)")
                    "string" -> assertStringish(type, "$source (unary/binary dual-path)")
                    "boolean" -> assertBooleanish(type, "$source (unary/binary dual-path)")
                }
                return
            }
            else -> error("expected BinaryExpression for $source, got ${expr::class.simpleName}")
        }
        // Prefer exact operator when present; for `~` some grammars use BIT_OR/BIT_AND
        // family names — only soft-check when allowUnaryFallback is false.
        if (!allowUnaryFallback) {
            assertEquals(operator, binary.operator, "operator for $source")
        } else if (binary.operator != operator) {
            // CURRENTLY_ACCEPTS: accept any binary whose evaluateBinary path yields number.
            assertTrue(
                binary.operator in setOf(
                    ExpressionOperator.BIT_TILDE,
                    ExpressionOperator.BIT_OR,
                    ExpressionOperator.BIT_AND,
                    ExpressionOperator.BIT_EXP,
                    ExpressionOperator.NE
                ) || binary.operator.name.contains("BIT") || binary.operator.name.contains("TILDE"),
                "$source: unexpected operator ${binary.operator} for xor-ish surface"
            )
        }
        val type = harness.evaluator.evaluate(binary)
        when (expectedKind) {
            "number" -> assertNumberish(type, source)
            "string" -> assertStringish(type, source)
            "boolean" -> assertBooleanish(type, source)
            else -> error("unknown expected kind $expectedKind")
        }
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
        val unexpected = kinds - required - optional - setOf("unknown")
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
                type == PrimitiveType.NUMBER ||
                type === PrimitiveType.NUMBER,
            "$label: expected number-ish, got ${type.displayName} kinds=$kinds"
        )
        // Prefer exact PrimitiveType.NUMBER identity when product returns it.
        if (type == PrimitiveType.NUMBER) {
            assertSame(PrimitiveType.NUMBER, type)
        }
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
