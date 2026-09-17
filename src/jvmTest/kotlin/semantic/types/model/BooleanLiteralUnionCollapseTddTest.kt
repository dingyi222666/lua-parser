package semantic.types.model

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `true | false` literal unions are exactly boolean: hover / completion must render
 * `boolean` instead of the literal pair (user-visible as `fun(t, s): false | true`).
 * `true | boolean` / `false | boolean` already collapsed via the primitive-widening
 * rule; the bare literal pair is the remaining gap.
 */
class BooleanLiteralUnionCollapseTddTest {

    @Test
    fun union_of_both_boolean_literals_collapses_to_boolean() {
        assertEquals(
            PrimitiveType.BOOLEAN,
            unionTypeOf(LiteralType(false, PrimitiveType.BOOLEAN), LiteralType(true, PrimitiveType.BOOLEAN))
        )
    }

    @Test
    fun union_of_both_boolean_literals_plus_other_members_keeps_boolean_slot() {
        val union = unionTypeOf(
            LiteralType(false, PrimitiveType.BOOLEAN),
            LiteralType(true, PrimitiveType.BOOLEAN),
            PrimitiveType.STRING
        )
        assertEquals(setOf("boolean", "string"), (union as UnionType).types.map { it.displayName }.toSet())
    }

    @Test
    fun single_boolean_literal_union_stays_literal() {
        assertEquals(
            LiteralType(true, PrimitiveType.BOOLEAN),
            unionTypeOf(LiteralType(true, PrimitiveType.BOOLEAN), LiteralType(true, PrimitiveType.BOOLEAN))
        )
    }

    @Test
    fun branch_returns_of_true_and_false_hover_as_boolean() {
        val source = """
            local function check(flag)
              if flag then
                return false
              end
              return true
            end
            return check
        """.trimIndent()
        val model = SemanticPipeline().analyze(LuaParser().parse(source)).model

        val symbol = model.getSymbolAt(positionOf(source, "check"))
        assertEquals(
            "fun(flag: unknown): boolean",
            symbol?.type?.displayName,
            "branch-return literal pair must render as boolean"
        )
    }

    private fun positionOf(source: String, needle: String): Position {
        val index = source.indexOf(needle)
        check(index >= 0) { "Missing '$needle'" }
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
}
