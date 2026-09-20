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
