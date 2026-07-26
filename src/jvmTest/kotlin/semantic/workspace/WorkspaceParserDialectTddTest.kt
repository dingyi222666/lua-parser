package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The workspace declares one [LuaVersion] and it must drive the grammar, not only the stdlib
 * overlay catalog. Before this was wired, every workspace parsed as AndroLua 5.3 no matter what it
 * declared, so a workspace that declared Lua 5.4 did not get the 5.4 grammar: `local x <close>`
 * bound a plain identifier and the attribute was dropped on the floor.
 *
 * `<close>` is the probe because it is the one construct whose *shape* differs per dialect under
 * lenient workspace parsing (5.4 produces an [AttributeIdentifier]); acceptance alone cannot
 * distinguish dialects here, since snippet recovery accepts all three.
 */
class WorkspaceParserDialectTddTest {

    private val path = VirtualPath.of("/main.lua")
    private val closeAttributeSource = "local handle <close> = open()\nreturn handle\n"

    private fun LuaWorkspaceEngine.chunkFor(source: String, version: LuaVersion): ChunkNode =
        build(
            LuaWorkspaceInput(
                files = mapOf(path to source),
                standardLibraryOverlayVersion = version
            )
        ).snapshot.files.getValue(path).semanticFile!!.chunk

    private fun ChunkNode.localBindings() =
        body.statements.filterIsInstance<LocalStatement>().flatMap { it.init }

    @Test
    fun `lua 5_4 workspace parses to-be-closed attributes`() {
        val bindings = LuaWorkspaceEngine().chunkFor(closeAttributeSource, LuaVersion.LUA_5_4).localBindings()
        assertEquals(1, bindings.size)
        assertTrue(
            bindings.single() is AttributeIdentifier,
            "a workspace declaring Lua 5.4 must parse with the 5.4 grammar, got ${bindings.single()::class.simpleName}"
        )
    }

    @Test
    fun `non-5_4 workspaces do not invent attribute bindings`() {
        listOf(LuaVersion.LUA_5_3, LuaVersion.ANDROLUA_5_3).forEach { version ->
            val bindings = LuaWorkspaceEngine().chunkFor(closeAttributeSource, version).localBindings()
            assertEquals(1, bindings.size, "version=$version")
            assertTrue(
                bindings.single() !is AttributeIdentifier,
                "$version has no attribute syntax, got ${bindings.single()::class.simpleName}"
            )
        }
    }

    @Test
    fun `switching declared version re-parses with the new grammar`() {
        // Same engine instance and identical source text: the chunk memoized under the previous
        // dialect must not leak across a version change.
        val engine = LuaWorkspaceEngine()
        engine.chunkFor(closeAttributeSource, LuaVersion.ANDROLUA_5_3)
        val bindings = engine.chunkFor(closeAttributeSource, LuaVersion.LUA_5_4).localBindings()

        assertTrue(
            bindings.single() is AttributeIdentifier,
            "expected a 5.4 re-parse, got the cached AndroLua chunk (${bindings.single()::class.simpleName})"
        )
    }
}
