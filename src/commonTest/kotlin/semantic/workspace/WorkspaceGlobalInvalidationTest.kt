package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.workspace.*
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlaySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkspaceGlobalInvalidationTest {
    private class Engine : LuaWorkspaceEngine() {
        override val includeAndroidFramework = false
        override val checkDiagnostics = false
    }

    private fun globals(source: String): String {
        val chunk = LuaParser().parse(source)
        val binder = SemanticPipeline().bindForGlobalFingerprint(chunk, BuiltinOverlaySnapshot.EMPTY.globals)
        return globalSymbolsFingerprint(binder.scopeGraph.globalWrites, SourceTokenFingerprint(source))
    }

    @Test
    fun deep_values_long_literals_function_bodies_and_tail_globals_all_change_fingerprints() {
        val pairs = listOf(
            "shared = { child = { child = { child = { child = { value = 1 } } } } }" to
                "shared = { child = { child = { child = { child = { value = 'changed' } } } } }",
            "shared = '${"a".repeat(300)}x'" to "shared = '${"a".repeat(300)}y'",
            "function shared() return 1 end" to "function shared() return 'changed' end",
            "shared = 1\nshared = 2" to "shared = 1\nshared = 'changed'",
            "shared = {}\nshared.deep = 1" to "shared = {}\nshared.deep = 'changed'"
        )
        pairs.forEach { (before, after) -> assertNotEquals(globals(before), globals(after), before) }
        val prefix = (0 until 530).joinToString("\n") { "global$it = $it" }
        assertNotEquals(globals("$prefix\ntail = 1"), globals("$prefix\ntail = 'changed'"))
    }

    @Test
    fun lexical_range_index_handles_trivia_crlf_bom_and_nested_tokens() {
        val source = "\uFEFF-- note\r\nshared = { value = 'x -- y' }\r\n"
        val range = Range(Position(2, 1), Position(2, source.lines()[1].length + 1))
        val indexed = SourceTokenFingerprint(source)
        val compact = "shared={value='x -- y'}"
        assertEquals(indexed.hash(range), SourceTokenFingerprint(compact)
            .hash(Range(Position(1, 1), Position(1, compact.length + 1))))
        assertEquals(globals("shared = 1 -- a\n"), globals("shared=1 -- b\n"))
    }

    @Test
    fun tail_global_updates_rebind_consumers_and_preserve_unrelated_documents() {
        val dep = VirtualPath.of("dep.lua")
        val consumer = VirtualPath.of("main.lua")
        val untouched = VirtualPath.of("other.lua")
        val prefix = (0 until 530).joinToString("\n") { "global$it = $it" }
        val engine = Engine()
        val previous = engine.build(LuaWorkspaceInput(mapOf(
            dep to "$prefix\ntail = 1\nreturn {}",
            consumer to "require 'dep'\nlocal value = tail\nreturn value",
            untouched to "return { untouched = true }"
        ))).snapshot
        val result = engine.update(previous, WorkspaceDelta(upserts = mapOf(
            dep to "$prefix\ntail = 'changed'\nreturn {}"
        )))
        assertTrue(consumer in result.affectedDocuments, "A tail global is a dependency surface change.")
        assertNotSame(previous.files.getValue(consumer).semanticFile!!.snapshot.binder,
            result.snapshot.files.getValue(consumer).semanticFile!!.snapshot.binder)
        assertSame(previous.files.getValue(untouched).semanticFile!!.snapshot.binder,
            result.snapshot.files.getValue(untouched).semanticFile!!.snapshot.binder)
        val hover = LuaWorkspaceQueryFacade(result.snapshot).hover(consumer, Position(3, 8))
        assertTrue(hover?.typeInfo?.displayName.orEmpty().contains("changed"), hover.toString())
    }

    @Test
    fun formatting_only_global_edits_keep_the_consumers_revision() {
        val dep = VirtualPath.of("dep.lua")
        val consumer = VirtualPath.of("main.lua")
        val engine = Engine()
        val previous = engine.build(LuaWorkspaceInput(mapOf(
            dep to "shared = { value = 1 }\nreturn {}",
            consumer to "require 'dep'\nreturn shared.value"
        ))).snapshot
        val current = engine.update(previous, WorkspaceDelta(upserts = mapOf(
            dep to "-- explanation\nshared={value=1} -- formatting only\nreturn {}"
        )))
        assertTrue(consumer !in current.affectedDocuments)
        assertSame(previous.files.getValue(consumer).semanticFile!!.snapshot.binder,
            current.snapshot.files.getValue(consumer).semanticFile!!.snapshot.binder)
    }
}
