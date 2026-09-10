package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.syntax.IndexTableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NamedTypeSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * TASK — positional `---@type` mapping on multi-name locals (adversarial audit, MEDIUM).
 *
 * `---@type boolean, string` above `local ok, err = pcall(f)` types `ok` boolean and `err`
 * string. The mapping used to be dropped entirely because the binder only typed locals with
 * exactly one declared name. Splitting is depth-aware: commas nested inside generics, tables,
 * or parens do not split the positional list.
 */
class BinderPositionalMultiLocalTypeTddTest {

    private val parser = LuaParser()

    @Test
    fun mapsPositionalTypeTextOntoMultiNameLocalDeclarations() {
        val chunk = parser.parse(
            """
            ---@type boolean, string
            local ok, err = pcall(f)
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val locals = result.declarationIndex.declarations.filter { it.kind == DeclarationKind.LOCAL }

        assertEquals("boolean", assertIs<NamedTypeSyntax>(locals.single { it.name == "ok" }.declaredTypeSyntax).name)
        assertEquals("string", assertIs<NamedTypeSyntax>(locals.single { it.name == "err" }.declaredTypeSyntax).name)
    }

    @Test
    fun nestedCommasDoNotSplitPositionalMapping() {
        val chunk = parser.parse(
            """
            ---@type table<string, number>, boolean
            local store, flag = {}, true
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val locals = result.declarationIndex.declarations.filter { it.kind == DeclarationKind.LOCAL }

        val store = assertIs<IndexTableTypeSyntax>(locals.single { it.name == "store" }.declaredTypeSyntax)
        assertEquals("string", assertIs<NamedTypeSyntax>(store.keyType).name)
        assertEquals("number", assertIs<NamedTypeSyntax>(store.valueType).name)
        assertEquals("boolean", assertIs<NamedTypeSyntax>(locals.single { it.name == "flag" }.declaredTypeSyntax).name)
    }

    @Test
    fun fewerTypePartsThanNamesLeavesSurplusNamesUnresolved() {
        val chunk = parser.parse(
            """
            ---@type boolean
            local ok, err = pcall(f)
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val locals = result.declarationIndex.declarations.filter { it.kind == DeclarationKind.LOCAL }

        assertNull(locals.single { it.name == "ok" }.declaredTypeSyntax)
        assertNull(locals.single { it.name == "err" }.declaredTypeSyntax)
    }

    @Test
    fun singleNameLocalStillTakesTheWholeTypeText() {
        val chunk = parser.parse(
            """
            ---@type table<string, number>
            local value = {}
            """.trimIndent()
        )

        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val value = result.declarationIndex.declarations.single { it.kind == DeclarationKind.LOCAL && it.name == "value" }

        assertIs<IndexTableTypeSyntax>(value.declaredTypeSyntax)
    }
}
