package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.lspFileUri
import io.github.dingyi222666.luaparser.lsp.lspVirtualPathFromUri
import io.github.dingyi222666.luaparser.lsp.normalizeFileSystemPathFromUriPath
import io.github.dingyi222666.luaparser.lsp.normalizeLspFileUriPath
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LspUriHandlingTddTest {
    @Test
    fun untitled_documents_preserve_exact_uri_for_diagnostics_republish_and_close() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { diagnostics -> published += diagnostics })
        val uri = "untitled:Untitled-1"

        textDocuments.didOpen(openParams(uri, "local ="))
        textDocuments.republishDiagnostics()
        textDocuments.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))

        assertEquals(listOf(uri, uri, uri), published.map { it.uri })
        assertTrue(published[0].diagnostics.isNotEmpty(), "invalid untitled Lua should publish diagnostics")
        assertTrue(published[1].diagnostics.isNotEmpty(), "republished untitled diagnostics should keep the original URI")
        assertTrue(published[2].diagnostics.isEmpty(), "closing an untitled document should clear diagnostics")
    }

    @Test
    fun republish_after_custom_uri_close_interleaving_does_not_use_synthetic_file_uri() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val published = mutableListOf<PublishDiagnosticsParams>()
        val firstUri = "untitled:Race-1"
        val closingUri = "custom-lua:race-closing-document"
        var duringRepublish = false
        var closedDuringRepublish = false
        val textDocumentHolder = arrayOfNulls<LuaTextDocumentService>(1)
        val textDocuments = LuaTextDocumentService(
            service,
            publishDiagnostics = { diagnostics ->
                published += diagnostics
                if (duringRepublish && diagnostics.uri == firstUri && !closedDuringRepublish) {
                    closedDuringRepublish = true
                    textDocumentHolder[0]!!.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(closingUri)))
                }
            }
        )
        textDocumentHolder[0] = textDocuments

        textDocuments.didOpen(openParams(firstUri, "local ="))
        textDocuments.didOpen(openParams(closingUri, "local ="))
        published.clear()

        duringRepublish = true
        textDocuments.republishDiagnostics()
        duringRepublish = false

        assertTrue(closedDuringRepublish, "test must close a custom URI document during diagnostics republish")
        assertEquals(listOf(firstUri, closingUri, closingUri), published.map { it.uri })
        assertTrue(published.none { it.uri.startsWith("file:///__lsp_uri__/") })
    }

    @Test
    fun opaque_custom_document_uri_is_preserved_for_same_document_definition_locations() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val uri = "custom-lua:opaque-document"
        val source = "local function target()\n    return 1\nend\nreturn target()\n"

        val diagnostics = service.didOpen(openParams(uri, source))
        val definitions = service.definition(
            DefinitionParams(
                TextDocumentIdentifier(uri),
                positionOf(source, "target()", occurrence = 2)
            )
        )

        assertEquals(uri, diagnostics.uri)
        assertTrue(diagnostics.diagnostics.isEmpty())
        assertEquals(uri, definitions.single().uri)
    }

    @Test
    fun custom_scheme_document_uri_is_preserved_for_workspace_symbol_locations() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        val uri = "vscode-notebook-cell:/workspace/main.lua#cell-1"

        service.didOpen(openParams(uri, "local value = 1\nreturn value"))

        val symbols = service.workspaceSymbols("value")

        assertTrue(symbols.any { symbol -> symbol.name == "value" && symbol.location.uri == uri })
    }

    @Test
    fun unix_absolute_file_uri_keeps_leading_slash() {
        val path = normalizeLspFileUriPath("file:///home/user/project/main.lua")
        assertEquals("/home/user/project/main.lua", path)
        assertTrue(path!!.startsWith("/"), "Unix absolute file URIs must keep the leading slash")
        assertNotEquals("home/user/project/main.lua", path)
    }

    @Test
    fun windows_drive_file_uri_strips_only_drive_prefix_slash() {
        assertEquals(
            "C:/Users/dingyi/project/main.lua",
            normalizeLspFileUriPath("file:///C:/Users/dingyi/project/main.lua")
        )
        assertEquals(
            "c:/Users/dingyi/project/main.lua",
            normalizeLspFileUriPath("file:///c:/Users/dingyi/project/main.lua")
        )
        assertEquals(
            "C:/Users/dingyi/project/main.lua",
            normalizeFileSystemPathFromUriPath("/C:/Users/dingyi/project/main.lua")
        )
    }

    @Test
    fun percent_encoded_file_uri_path_roundtrips_with_lspFileUri() {
        val decoded = normalizeLspFileUriPath("file:///home/user/my%20project/hello%20world.lua")
        assertEquals("/home/user/my project/hello world.lua", decoded)

        // VirtualPath strips a leading slash (workspace-relative), so roundtrip through a relative path.
        val virtual = VirtualPath.of("my project/hello world.lua")
        val encoded = lspFileUri(virtual)
        assertTrue(encoded.startsWith("file:"), encoded)
        assertTrue("%20" in encoded || encoded.contains("my%20project"), "spaces must be percent-encoded: $encoded")

        val roundtrip = normalizeLspFileUriPath(encoded)
        assertEquals("/my project/hello world.lua", roundtrip)
    }

    @Test
    fun percent_encoded_windows_drive_file_uri_decodes_safely() {
        // URI decodes %3A to ':' in the path component → "/c:/Users/..."
        val path = normalizeLspFileUriPath("file:///c%3A/Users/dingyi/app/main.lua")
        assertEquals("c:/Users/dingyi/app/main.lua", path)
    }

    @Test
    fun non_file_schemes_return_null_from_normalize_and_use_synthetic_virtual_path() {
        assertNull(normalizeLspFileUriPath("untitled:Untitled-1"))
        assertNull(normalizeLspFileUriPath("custom-lua:opaque-document"))
        assertNull(normalizeLspFileUriPath("vscode-notebook-cell:/workspace/main.lua#cell-1"))

        val untitledPath = lspVirtualPathFromUri("untitled:Untitled-1")
        assertTrue(
            untitledPath.value.startsWith("__lsp_uri__/"),
            "non-file schemes must map to synthetic virtual paths, not corrupted filesystem paths: ${untitledPath.value}"
        )
        assertTrue(!untitledPath.value.contains("Untitled-1"), untitledPath.value)
    }

    @Test
    fun rootUri_and_workspace_folder_uri_share_one_normalization_path() {
        val unixRoot = "file:///home/user/project"
        val windowsRoot = "file:///C:/Users/dingyi/project"

        assertEquals(
            normalizeLspFileUriPath(unixRoot),
            normalizeLspFileUriPath("file:///home/user/project")
        )
        assertEquals(
            "/home/user/project",
            normalizeLspFileUriPath(unixRoot)
        )
        assertEquals(
            "C:/Users/dingyi/project",
            normalizeLspFileUriPath(windowsRoot)
        )

        // Service initialize accepts either rootUri or workspaceFolders and routes both
        // through WorkspaceFolder → pathFromFileUri / normalizeLspFileUriPath.
        val viaRootUri = LuaLanguageService().also { service ->
            service.initialize(InitializeParams().apply { rootUri = unixRoot })
        }
        val viaWorkspaceFolder = LuaLanguageService().also { service ->
            service.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(unixRoot, "project"))
            })
        }
        // Both services must accept the same URI shape without crashing; open under that root.
        val docUri = "file:///home/user/project/main.lua"
        val source = "local shared = 1\nreturn shared\n"
        val d1 = viaRootUri.didOpen(openParams(docUri, source))
        val d2 = viaWorkspaceFolder.didOpen(openParams(docUri, source))
        assertEquals(docUri, d1.uri)
        assertEquals(docUri, d2.uri)
    }

    @Test
    fun synthetic_workspace_file_uri_still_collapses_without_workspace_folders() {
        // Regression: no-folder initialize + file:///workspace/*.lua path shim.
        val path = lspVirtualPathFromUri(
            "file:///workspace/main.lua",
            workspaceFolderUriPrefixes = emptyMap(),
            collapseSyntheticWorkspaceRoot = true
        )
        assertEquals("main.lua", path.value)
    }

    private fun openParams(uri: String, source: String): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source))
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        require(occurrence >= 1) { "occurrence must be positive" }
        var index = -1
        var fromIndex = 0
        repeat(occurrence) {
            index = source.indexOf(needle, fromIndex)
            require(index >= 0) { "Could not find occurrence $occurrence of '$needle'" }
            fromIndex = index + needle.length
        }
        return positionAt(source, index)
    }

    private fun positionAt(source: String, offset: Int): Position {
        var line = 0
        var lineStart = 0
        for (index in 0 until offset) {
            if (source[index] == '\n') {
                line += 1
                lineStart = index + 1
            }
        }
        return Position(line, offset - lineStart)
    }
}
