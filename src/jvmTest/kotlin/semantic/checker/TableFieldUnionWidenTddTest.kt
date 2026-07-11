package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * TASK-332 corpus: table field writes widen union types deterministically.
 *
 * Product surfaces covered:
 * - Table constructor field values form [TableType.fields] with evaluated RHS types.
 * - Repeated same-key presence is last-write-wins at constructor evaluation time.
 * - Union-typed field values (via annotations / unionTypeOf) remain readable as
 *   union members after widen.
 * - MemberResolver reads after widen see the union members.
 *
 * Test-only. No production edits. Verification is review-owned (no Gradle).
 */
class TableFieldUnionWidenTddTest {

    private val parser = LuaParser()

    @Test
    fun tableConstructorFieldKeepsLiteralPrimitiveTypes() {
        val harness = evaluator("return { id = 1, label = \"x\", ok = true }")
        val table = assertIs<TableType>(harness.evaluator.evaluate(harness.returnExpression()))
        assertTrue(table.fields.containsKey("id"))
        assertTrue(table.fields.containsKey("label"))
        assertTrue(table.fields.containsKey("ok"))
        assertNumberish(table.fields.getValue("id"), "id")
        assertStringish(table.fields.getValue("label"), "label")
        assertBooleanish(table.fields.getValue("ok"), "ok")
    }

    @Test
    fun annotatedUnionFieldValueSurfacesUnionMembersOnRead() {
        val harness = evaluator(
            """
            ---@type string|number
            local v = 1
            return { value = v }
            """.trimIndent()
        )
        val table = assertIs<TableType>(harness.evaluator.evaluate(harness.returnExpression()))
        val field = table.fields.getValue("value")
        assertUnionContainsKinds(field, setOf("string", "number"), label = "value field")
    }

    @Test
    fun memberReadAfterUnionWidenSeesUnionMembers() {
        val binder = emptyBinder()
        val table = TableType(
            fields = mapOf(
                "value" to unionTypeOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
            )
        )
        val resolution = MemberResolver(binder).resolveMember(
            table,
            "value",
            preferMethod = false,
            lexicalScopeId = binder.scopeGraph.rootScope.id
        )
        val type = resolution.type
        assertTrue(type != null, "widened field must resolve")
        assertUnionContainsKinds(type!!, setOf("string", "number"), label = "member after widen")
    }

    @Test
    fun successiveUnionWidenIsDeterministicOrderInsensitiveOnMembers() {
        // string|number and number|string normalize to same member set.
        val a = unionTypeOf(PrimitiveType.STRING, PrimitiveType.NUMBER)
        val b = unionTypeOf(PrimitiveType.NUMBER, PrimitiveType.STRING)
        val tableA = TableType(fields = mapOf("v" to a))
        val tableB = TableType(fields = mapOf("v" to b))
        assertEquals(memberKinds(tableA.fields.getValue("v")), memberKinds(tableB.fields.getValue("v")))
    }

    @Test
    fun lastWriteWinsInTableConstructorForSameKey() {
        // Product evaluateTableConstructor overwrites fields[key] for each entry.
        val harness = evaluator("return { v = 1, v = \"x\" }")
        val table = assertIs<TableType>(harness.evaluator.evaluate(harness.returnExpression()))
        assertEquals(1, table.fields.size)
        assertStringish(table.fields.getValue("v"), "last write v")
    }

    @Test
    fun nestedTableFieldUnionWidenDoesNotThrow() {
        val harness = evaluator(
            """
            ---@type string|nil
            local leaf = nil
            return { outer = { inner = leaf } }
            """.trimIndent()
        )
        val outer = assertIs<TableType>(harness.evaluator.evaluate(harness.returnExpression()))
        val mid = assertIs<TableType>(outer.fields.getValue("outer"))
        assertUnionContainsKinds(
            mid.fields.getValue("inner"),
            required = setOf("string"),
            optional = setOf("nil"),
            label = "nested inner"
        )
    }

    @Test
    fun emptyTableHasNoFieldsAndReadsMissingAsUnknown() {
        val harness = evaluator("return {}")
        val table = assertIs<TableType>(harness.evaluator.evaluate(harness.returnExpression()))
        assertTrue(table.fields.isEmpty())
        val binder = emptyBinder()
        val missing = MemberResolver(binder).resolveMember(
            table,
            "nope",
            preferMethod = false,
            lexicalScopeId = binder.scopeGraph.rootScope.id
        )
        assertTrue(missing.type == null || missing.type!!.displayName.contains("unknown", ignoreCase = true))
    }

    @Test
    fun threeWayUnionFieldWidenPreservesAllMembers() {
        val field = unionTypeOf(PrimitiveType.STRING, PrimitiveType.NUMBER, PrimitiveType.BOOLEAN)
        val table = TableType(fields = mapOf("flag" to field))
        assertUnionContainsKinds(table.fields.getValue("flag"), setOf("string", "number", "boolean"), label = "3-way")
    }

    private fun evaluator(source: String): EvalHarness {
        val chunk = parser.parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return EvalHarness(chunk, ExpressionTypeEvaluator(resolved))
    }

    private fun emptyBinder(): io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult {
        val chunk = parser.parse("return nil")
        return TypeResolver().resolve(BinderPass().bind(chunk, CommentAttachPass().attach(chunk)))
    }

    private data class EvalHarness(val chunk: ChunkNode, val evaluator: ExpressionTypeEvaluator) {
        fun returnExpression(): ExpressionNode {
            val statement = chunk.body.returnStatement ?: error("expected return")
            return statement.arguments.single()
        }
    }

    private fun memberKinds(type: Type): Set<String> = when (type) {
        is UnionType -> type.types.flatMap { memberKinds(it) }.toSet()
        is PrimitiveType -> setOf(type.kind.name.lowercase().let {
            when (it) {
                "nil" -> "nil"
                else -> it.lowercase()
            }
        }.let { k ->
            when (type.kind) {
                PrimitiveType.Kind.NIL -> "nil"
                PrimitiveType.Kind.BOOLEAN -> "boolean"
                PrimitiveType.Kind.NUMBER -> "number"
                PrimitiveType.Kind.STRING -> "string"
                PrimitiveType.Kind.FUNCTION -> "function"
                PrimitiveType.Kind.TABLE -> "table"
                else -> type.displayName.lowercase()
            }
        })
        else -> setOf(type.displayName.lowercase())
    }

    private fun assertUnionContainsKinds(
        type: Type,
        required: Set<String>,
        optional: Set<String> = emptySet(),
        label: String
    ) {
        val kinds = memberKinds(type)
        required.forEach { kind ->
            assertTrue(kind in kinds, "$label: expected '$kind' in ${type.displayName} kinds=$kinds")
        }
        val unexpected = kinds - required - optional - setOf("unknown")
        if (kinds.size > 1 || required.size > 1) {
            assertTrue(unexpected.isEmpty(), "$label: unexpected $unexpected in ${type.displayName}")
        }
    }

    private fun assertNumberish(type: Type, label: String) {
        assertTrue("number" in memberKinds(type) || type.displayName.contains("number", true), "$label got ${type.displayName}")
    }

    private fun assertStringish(type: Type, label: String) {
        assertTrue("string" in memberKinds(type) || type.displayName.contains("string", true) || type.displayName.startsWith("\""), "$label got ${type.displayName}")
    }

    private fun assertBooleanish(type: Type, label: String) {
        assertTrue("boolean" in memberKinds(type) || type.displayName.contains("boolean", true) || type.displayName == "true" || type.displayName == "false", "$label got ${type.displayName}")
    }
}
