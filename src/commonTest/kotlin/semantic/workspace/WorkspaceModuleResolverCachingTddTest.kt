package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraphBuilder
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkspaceModuleResolverCachingTddTest {
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
