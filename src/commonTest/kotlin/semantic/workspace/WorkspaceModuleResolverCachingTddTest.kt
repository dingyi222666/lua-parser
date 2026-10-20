package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraphBuilder
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs

class WorkspaceModuleResolverCachingTddTest {
    @Test
    fun deferred_globals_keep_extensions_visibility_and_current_binding_after_edit() {
        val provider = VirtualPath.of("helpers.lua")
        val consumer = VirtualPath.of("main.lua")
        val engine = LuaWorkspaceEngine()
        fun source(member: String) = """
            shared = { base = 1 }
            shared.$member = "value"
            table.$member = function() return 1 end
            local hidden = {}
            hidden.privateMember = 1
        """.trimIndent()
        val first = engine.build(LuaWorkspaceInput(
            files = mapOf(provider to source("oldMember"), consumer to "require 'helpers'"),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )).snapshot
        val original = WorkspaceModuleResolver(first, deferDependencyGlobals = true)
        fun check(resolver: WorkspaceModuleResolver, member: String, absent: String) {
            val names = resolver.deferredDependencyGlobalNamesFor(consumer)
            assertTrue("shared" in names)
            assertTrue("table" in names)
            assertFalse("hidden" in names)
            assertNull(resolver.importedSymbolFor(consumer, "hidden"))
            assertNull(resolver.importedSymbolFor(consumer, "notDeclared"))
            val shared = assertIs<TableType>(assertNotNull(resolver.importedSymbolFor(consumer, "shared")).valueType)
            assertTrue("base" in shared.fields)
            assertTrue(member in shared.fields)
            assertFalse(absent in shared.fields)
            val table = assertNotNull(resolver.importedSymbolFor(consumer, "table")).valueType
            val members = when (table) {
                is ModuleType -> table.fields.keys + table.methods.keys
                is TableType -> table.fields.keys + table.methods.keys
                else -> error("Expected table surface, got ${table.displayName}")
            }
            assertTrue(member in members)
            assertFalse(absent in members)
        }
        check(original, "oldMember", "newMember")
        val edited = engine.update(first, WorkspaceDelta(upserts = mapOf(provider to source("newMember")))).snapshot
        check(WorkspaceModuleResolver(edited, deferDependencyGlobals = true), "newMember", "oldMember")
        check(original, "oldMember", "newMember")
    }

    @Test
    fun repeated_wildcard_import_lookup_reuses_path_result() {
        val path = VirtualPath.of("main.lua")
        val source = """
            import "android.widget.*"
            local title = TextView()
        """.trimIndent()
        val files = mapOf(
            path to WorkspaceSnapshot.FileSnapshot(
                documentFacts = DocumentFactsCollector.collect(path, LuaParser().parse(source))
            )
        )
        val overlay = BuiltinOverlayLoader.load(LuaVersion.ANDROLUA_5_3) { _, _ ->
            WorkspaceSnapshot.FileSnapshot()
        }
        val snapshot = WorkspaceSnapshot(
            files = files,
            builtinOverlay = overlay,
            graph = WorkspaceModuleGraphBuilder.build(files, overlay)
        )
        val resolver = WorkspaceModuleResolver(snapshot)

        val first = resolver.importedSymbolsFor(path)
        val second = resolver.importedSymbolsFor(path)

        assertTrue("TextView" in first)
        assertSame(first, second)
    }
}
