package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.syntax.NamedTypeSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * TASK — trailing/inline `---@type` attachment (adversarial audit, HIGH).
 *
 * A comment group whose first comment starts on the line where the previous statement ENDS is
 * a trailing comment (`local a = 1 ---@type string`). It documents the statement it trails:
 * it must attach BACKWARD to that statement and never forward to the construct that follows —
 * the forward adjacency window (gap 0..1) used to pin the group onto `local b`, typing the
 * wrong local.
 */
class TrailingInlineTypeAttachTddTest {

    private val parser = LuaParser()

    @Test
    fun trailingTypeCommentAttachesBackwardAndNeverForward() {
        val chunk = parser.parse(
            """
            local a = 1 ---@type string
            local b = nil
            """.trimIndent()
        )

        val statements = chunk.body.statements.filterIsInstance<LocalStatement>()
        val a = statements.single { it.init.single().name == "a" }
        val b = statements.single { it.init.single().name == "b" }
        val index = CommentAttachPass().attach(chunk)

        assertEquals("string", assertNotNull(index.getAttachment(a)).inlineTypeText)
        assertNull(index.getAttachment(b))
    }

    @Test
    fun trailingTypeCommentTypesOnlyTheTrailedLocal() {
        val chunk = parser.parse(
            """
            local a = 1 ---@type string
            local b = nil
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val locals = result.declarationIndex.declarations.filter { it.kind == DeclarationKind.LOCAL }

        val a = locals.single { it.name == "a" }
        val b = locals.single { it.name == "b" }

        assertEquals("string", assertIs<NamedTypeSyntax>(a.declaredTypeSyntax).name)
        assertNull(b.declaredTypeSyntax)
    }

    @Test
    fun trailingTypeCommentOnLastStatementOfBlockAttachesBackward() {
        val chunk = parser.parse(
            """
            do
                local a = 1 ---@type string
            end
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val a = result.declarationIndex.declarations.single { it.kind == DeclarationKind.LOCAL && it.name == "a" }

        assertEquals("string", assertIs<NamedTypeSyntax>(a.declaredTypeSyntax).name)
    }

    @Test
    fun trailingTypeCommentBeforeReturnDoesNotTypeTheReturn() {
        val chunk = parser.parse(
            """
            local a = 1 ---@type string
            return a
            """.trimIndent()
        )

        val statements = chunk.body.statements.filterIsInstance<LocalStatement>()
        val a = statements.single { it.init.single().name == "a" }
        val index = CommentAttachPass().attach(chunk)

        assertEquals("string", assertNotNull(index.getAttachment(a)).inlineTypeText)
        assertNull(index.getAttachment(assertNotNull(chunk.body.returnStatement)))
    }

    @Test
    fun leadingTypeCommentStillAttachesForwardToFollowingStatement() {
        // Guard against over-triggering: a comment group on its own line keeps the forward
        // attachment to the statement that follows it.
        val chunk = parser.parse(
            """
            ---@type string
            local a = nil
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = CommentAttachPass().attach(chunk)

        assertEquals("string", assertNotNull(index.getAttachment(local)).inlineTypeText)
    }
}
