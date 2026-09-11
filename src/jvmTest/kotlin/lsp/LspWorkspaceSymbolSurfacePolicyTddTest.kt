package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind as SurfaceSymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind as LspSymbolKind
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Workspace-symbol audit wave M — LSP workspace/symbol surface policy corpus.
 *
 * Locks the product policy introduced for the workspace-symbol audit findings:
 *
 * 1. **Deterministic result cap (finding 2).** [LuaWorkspaceQueryFacade.workspaceSymbolEntries]
 *    caps results at 500 AFTER the deterministic (name, path, line, column) sort, so a
 *    blank query returns a stable 500-entry prefix instead of every local in every file.
 *    The cap is per query: symbols beyond the blank-query window still resolve when
 *    matched by a narrower query.
 *
 * 2. **Body-LOCAL exclusion (finding 2).** Locals bound inside function bodies, loop
 *    bodies, and conditional blocks never surface in workspace/symbol — only chunk-level
 *    locals join globals/functions/classes/fields/methods and module export surfaces.
 *    textDocument/documentSymbol keeps the full per-file surface: the exclusion is
 *    workspace-only.
 *
 * 3. **Quiet CREATED policy (finding 3).** workspace/symbol before initialize completes
 *    quietly with an empty legacy list (parity with text requests), and the wrapper's
 *    `resolveWorkspaceSymbol` returns its input instead of lsp4j's default
 *    UnsupportedOperationException (resolveProvider is not advertised).
 *
 * 4. **Indexed extraProvider entries (finding 4).** extraProvider symbol entries whose
 *    synthetic `__jvm__` path has no real location are dropped unless the provider is
 *    actually indexed (module-graph claimed, or a real workspace file path).
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspWorkspaceSymbolSurfacePolicyTddTest {

    // -------------------------------------------------------------------------
    // Finding 2 — deterministic 500 cap applied after the sort
    // -------------------------------------------------------------------------

    @Test
    fun workspace_symbols_cap_at_500_after_deterministic_sort() {
        val service = service()
        service.open(
            "workspace/ws-cap.lua",
            (0 until 600).joinToString("\n") { index -> "local wsCap%03d = 0".format(index) }
        )

        val blank = service.workspaceSymbols("")

        assertEquals(500, blank.size, "blank query must cap at 500 entries; got ${blank.size}")
        val names = blank.map { it.name }
        assertEquals(names.sorted(), names, "cap must keep the deterministic sorted prefix")
        assertEquals("wsCap000", names.first(), "sorted prefix must start at the first name")
        assertEquals("wsCap499", names.last(), "sorted prefix must end at the cap boundary")

        // The cap is per query: symbols beyond the blank-query window still resolve
        // when matched by a narrower query.
        val tail = service.workspaceSymbols("wsCap599")
        assertTrue(
            tail.any { it.name == "wsCap599" && it.location.uri == "file:///workspace/ws-cap.lua" },
            "cap must not hide exact matches from narrower queries; got ${tail.map { it.name }}"
        )

        // A query matching more than the cap is capped too.
        assertEquals(500, service.workspaceSymbols("wsCap").size)
    }

    // -------------------------------------------------------------------------
    // Finding 2 — body-LOCAL exclusion (workspace surface only)
    // -------------------------------------------------------------------------

    @Test
    fun workspace_symbols_exclude_body_locals_but_keep_chunk_surface() {
        val service = service()
        service.open(
            "workspace/ws-body-locals.lua",
            """
            local chunkLocal = 1
            local function chunkFn()
                local bodyLocal = chunkLocal + 1
                return bodyLocal
            end
            for loopVar = 1, 3 do
                local loopBodyLocal = loopVar
            end
            return chunkFn
            """.trimIndent()
        )

        val names = service.workspaceSymbols("").map { it.name }

        assertTrue("chunkLocal" in names, "chunk-level local must surface; got $names")
        assertTrue("chunkFn" in names, "chunk-level function must surface; got $names")
        assertTrue("bodyLocal" !in names, "function-body local must not surface in workspace/symbol; got $names")
        assertTrue("loopBodyLocal" !in names, "loop-body local must not surface in workspace/symbol; got $names")
        assertTrue("loopVar" !in names, "loop control variable must not surface in workspace/symbol; got $names")

        // documentSymbol keeps the full per-file surface: the exclusion is workspace-only.
        val documentNames = service.documentSymbols("workspace/ws-body-locals.lua").map { it.name }
        assertTrue("bodyLocal" in documentNames, "documentSymbol surface keeps body locals; got $documentNames")
    }

    // -------------------------------------------------------------------------
    // Finding 3 — quiet CREATED policy + resolveWorkspaceSymbol pre-emption
    // -------------------------------------------------------------------------

    @Test
    fun workspace_symbol_before_initialize_is_quiet_empty() {
        val server = LuaLanguageServer()

        val either = server.workspaceService.symbol(WorkspaceSymbolParams("value"))
            .get(5, TimeUnit.SECONDS)

        assertTrue(either.isLeft, "pre-initialize workspace/symbol uses the legacy branch; got $either")
        assertTrue(either.left.isEmpty(), "pre-initialize workspace/symbol must be quiet-empty; got $either")
    }

    @Test
    fun resolve_workspace_symbol_returns_input_quietly() {
        val server = LuaLanguageServer()
        val input = WorkspaceSymbol(
            "wsResolveEcho",
            LspSymbolKind.Function,
            Either.forLeft(
                Location(
                    "file:///workspace/ws-resolve-echo.lua",
                    Range(Position(0, 0), Position(0, 8))
                )
            ),
            null
        )

        val resolved = server.workspaceService.resolveWorkspaceSymbol(input)
            .get(5, TimeUnit.SECONDS)

        assertEquals("wsResolveEcho", resolved?.name, "resolve must echo the input symbol")
        assertEquals(
            "file:///workspace/ws-resolve-echo.lua",
            resolved?.location?.left?.uri,
            "resolve must keep the inline location"
        )
    }

    // -------------------------------------------------------------------------
    // Finding 4 — unindexed extraProvider __jvm__ entries are dropped
    // -------------------------------------------------------------------------

    @Test
    fun extra_provider_symbol_entries_require_indexed_location() {
        val providerPath = VirtualPath.of("__jvm__/classes/java/util/Arrays.lua")
        val providerSnapshot = providerFileSnapshot("Arrays")

        // Not indexed: the synthetic __jvm__ path is claimed by no module provider and
        // is not a real workspace file — no fabricated file:/// entries may surface.
        val unindexed = LuaWorkspaceQueryFacade(
            WorkspaceSnapshot(extraProviders = mapOf(providerPath to providerSnapshot))
        )
        assertTrue(
            unindexed.workspaceSymbolEntries("Arrays").isEmpty(),
            "unindexed __jvm__ provider must not fabricate symbol entries"
        )
        assertTrue(
            unindexed.workspaceSymbolEntries("").isEmpty(),
            "unindexed __jvm__ provider must not surface on blank queries either"
        )

        // Indexed: a module-graph-claimed provider keeps its module + member entries.
        val indexed = LuaWorkspaceQueryFacade(
            WorkspaceSnapshot(
                extraProviders = mapOf(providerPath to providerSnapshot),
                graph = WorkspaceModuleGraph(
                    providersByModuleName = mapOf(
                        "Arrays" to listOf(
                            WorkspaceModuleGraph.ModuleProvider(
                                moduleName = "Arrays",
                                path = providerPath,
                                source = WorkspaceModuleGraph.ProviderSource.EXTRA_WORKSPACE_PROVIDER
                            )
                        )
                    )
                )
            )
        )
        val names = indexed.workspaceSymbolEntries("").map { it.name }
        assertTrue("Arrays" in names, "indexed provider module must surface; got $names")
        assertTrue("asList" in names, "indexed provider member must surface; got $names")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun service(): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(InitializeParams())
        }
    }

    private fun LuaLanguageService.open(path: String, source: String) {
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file:///$path", "lua", 1, source)
            )
        )
    }

    /** Stand-in for a reflective JVM class provider snapshot (no real file location). */
    private fun providerFileSnapshot(moduleName: String): WorkspaceSnapshot.FileSnapshot {
        val moduleType = ModuleType(moduleName)
        val surface = ModuleExportSurface(
            moduleType = moduleType,
            sourceForm = ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL,
            members = listOf(
                ModuleExportSurface.MemberExport(
                    name = "asList",
                    exportPath = listOf("asList"),
                    kind = SurfaceSymbolKind.FUNCTION,
                    type = moduleType,
                    range = null
                )
            )
        )
        return WorkspaceSnapshot.FileSnapshot(moduleExportSurface = surface)
    }
}
