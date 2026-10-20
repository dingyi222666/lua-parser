package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ForGenericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ForNumericStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Loop-header scoping: a numeric/generic for statement's LOOP scope covers the BODY only.
 *
 * Lua semantics locked here:
 * - Header expressions evaluate in the ENCLOSING scope. The freshly declared control
 *   variables are not in scope there: `for i = 1, #i do` reads the OUTER `i`, and
 *   `for k, v in pairs(k) do` passes the OUTER `k` to pairs.
 * - The control variables live in the loop scope: reads inside the body resolve to them
 *   (numeric control var surfaces as `number`).
 * - The declared name token in the header still resolves to the loop declaration via the
 *   declaration's own range (hover/rename on the header name keeps working).
 *
 * Regression lock for DeclarationBinder: numeric/generic for scopes are created with
 * `node.body.range` (repeat-until keeps `node.range` — its until-condition legitimately
 * sees the body locals).
 */
class BinderLoopHeaderScopeTddTest {

    private val parser = LuaParser()
    private val pipeline = SemanticPipeline()

    @Test
    fun numericForHeaderExpressionResolvesOuterLocalWhileBodyResolvesControlVariable() {
        val source = """
            local i = "s"
            for i = 1, #i do
                print(i)
            end
            """.trimIndent()
        val chunk = parser.parse(source)

        // Binder-level lock: the LOOP scope range starts at the body, not the header.
        val forStatement = chunk.body.statements.filterIsInstance<ForNumericStatement>().single()
        val bound = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val loopScope = assertNotNull(bound.scopeGraph.getScope(forStatement.body))
        assertEquals(forStatement.body.range, loopScope.range)
        // Header positions (the `#i` read) belong to the enclosing scope; body positions
        // resolve to the loop scope holding the control variable.
        assertEquals(bound.scopeGraph.rootScope, bound.positionQueries.getScopeAt(positionOf(source, "i", 3)))
        assertEquals(loopScope, bound.positionQueries.getScopeAt(positionOf(source, "i", 4)))

        val model = pipeline.analyze(chunk).model

        val outerDeclarationStart = positionOf(source, "i", 1)
        val headerToken = positionOf(source, "i", 2)

        // `#i` in the header resolves to the OUTER string local, not the loop's numeric i.
        val headerSymbol = assertNotNull(model.getSymbolAt(positionOf(source, "i", 3)), "expected a symbol for the header `#i` read")
        assertEquals("i", headerSymbol.name)
        assertEquals(outerDeclarationStart, headerSymbol.range?.start)
        // NOTE: Symbol.type is not populated with inferred initializer types here
        // (inferred types surface via model.getTypeAt(node), not getSymbolAt) — the
        // semantic being pinned is WHICH declaration the header read resolves to.

        // `print(i)` inside the body resolves to the loop's numeric control variable.
        val bodySymbol = assertNotNull(model.getSymbolAt(positionOf(source, "i", 4)), "expected a symbol for the body `i` read")
        assertEquals("i", bodySymbol.name)
        assertEquals(headerToken, bodySymbol.range?.start)

        // The header control-var token itself still resolves to the loop declaration.
        val declarationSymbol = assertNotNull(model.getSymbolAt(headerToken))
        assertEquals(headerToken, declarationSymbol.range?.start)
        assertEquals(bodySymbol.symbolId, declarationSymbol.symbolId)
        assertNotEquals(headerSymbol.symbolId, declarationSymbol.symbolId)
    }

    @Test
    fun genericForIteratorExpressionResolvesOuterLocal() {
        val source = """
            local k = "key"
            for k, v in pairs(k) do end
            """.trimIndent()
        val chunk = parser.parse(source)

        // Binder-level lock: the generic for LOOP scope range starts at the body.
        val forStatement = chunk.body.statements.filterIsInstance<ForGenericStatement>().single()
        val bound = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val loopScope = assertNotNull(bound.scopeGraph.getScope(forStatement.body))
        assertEquals(forStatement.body.range, loopScope.range)

        val model = pipeline.analyze(chunk).model

        val outerDeclarationStart = positionOf(source, "k", 1)
        val headerToken = positionOf(source, "k", 2)

        // `pairs(k)` in the header resolves to the OUTER string local, not the loop's k.
        val iteratorSymbol = assertNotNull(model.getSymbolAt(positionOf(source, "k", 3)), "expected a symbol for the `pairs(k)` read")
        assertEquals("k", iteratorSymbol.name)
        assertEquals(outerDeclarationStart, iteratorSymbol.range?.start)
        // (Symbol.type is not populated with inferred initializer types — see numeric test note.)

        // The header loop-variable token itself still resolves to the generic for variable.
        val declarationSymbol = assertNotNull(model.getSymbolAt(headerToken))
        assertEquals(headerToken, declarationSymbol.range?.start)
        assertNotEquals(iteratorSymbol.symbolId, declarationSymbol.symbolId)
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var from = 0
        var found = 0
        while (found < occurrence) {
            val index = source.indexOf(needle, from)
            require(index >= 0) { "Missing whole-identifier occurrence $occurrence of '$needle'." }
            from = index + needle.length
            val beforeOk = index == 0 || !source[index - 1].isLuaIdentChar()
            val afterOk = index + needle.length >= source.length ||
                !source[index + needle.length].isLuaIdentChar()
            if (!beforeOk || !afterOk) {
                continue
            }
            found += 1
            if (found == occurrence) {
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
        error("Unreachable: missing whole-identifier occurrence $occurrence of '$needle'.")
    }

    private fun Char.isLuaIdentChar(): Boolean =
        isLetterOrDigit() || this == '_'
}
