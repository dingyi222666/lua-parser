package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Adversarial-audit fix: `textDocument/didChange` must publish diagnostics for every
 * document the incremental update affected, not only for the edited path.
 *
 * Editing the public surface of a required module dirties its open consumers
 * ([io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceUpdateResult.affectedDocuments]);
 * their stale diagnostics have to be republished in the same didChange turn. Contract:
 * - edited document first, every path at most once;
 * - other affected documents are pushed only while they are open (closed indexed
 *   files stay pull-only via `diagnostics()` / `diagnosticsForUri()`);
 * - unrelated open documents are not republished.
 *
 * Test-only. Verification is review-owned (TASK-043). Workers must not run Gradle.
 */
class LspDidChangeAffectedDocumentsPublishTddTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun service_did_change_republishes_open_dependents_edited_path_first() {
        val workspace = workspace("service-level")
        val service = initialized(workspace.root)
        service.didOpen(open(workspace.depUri, DEP_V1))
        service.didOpen(open(workspace.mainUri, MAIN))
        service.didOpen(open(workspace.otherUri, OTHER))

        val published = service.didChange(fullChange(workspace.depUri, version = 2, text = DEP_V2))
        val uris = published.map { it.uri }

        assertEquals(workspace.depUri, uris.first(), "edited document must be published first; got $uris")
        assertTrue(workspace.mainUri in uris, "open consumer of the edited module must be republished; got $uris")
        assertFalse(workspace.otherUri in uris, "unrelated open document must not be republished; got $uris")
        assertEquals(uris.size, uris.toSet().size, "each document is published at most once; got $uris")
    }

    @Test
    fun service_did_change_skips_closed_dependents() {
        val workspace = workspace("closed-dependent")
        val service = initialized(workspace.root)
        // main.lua stays indexed from disk but is never opened.
        service.didOpen(open(workspace.depUri, DEP_V1))

        val published = service.didChange(fullChange(workspace.depUri, version = 2, text = DEP_V2))
        val uris = published.map { it.uri }

        assertEquals(listOf(workspace.depUri), uris, "closed dependents stay pull-only; got $uris")
        // Pull path still works for the closed dependent.
        assertEquals(workspace.mainUri, service.diagnosticsForUri(workspace.mainUri).uri)
    }

    @Test
    fun service_did_change_on_a_leaf_document_publishes_only_that_document() {
        val workspace = workspace("leaf")
        val service = initialized(workspace.root)
        service.didOpen(open(workspace.depUri, DEP_V1))
        service.didOpen(open(workspace.mainUri, MAIN))

        // Editing the consumer never dirties the provider.
        val published = service.didChange(
            fullChange(
                workspace.mainUri,
                version = 2,
                text = "local dep = require(\"dep\")\nlocal renamed = dep.value\nreturn { value = renamed }"
            )
        )

        assertEquals(listOf(workspace.mainUri), published.map { it.uri })
    }

    @Test
    fun text_document_service_pushes_every_returned_publish() {
        val workspace = workspace("text-document-service")
        val service = initialized(workspace.root)
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = service,
            publishDiagnostics = { published += it }
        )
        textDocuments.didOpen(open(workspace.depUri, DEP_V1))
        textDocuments.didOpen(open(workspace.mainUri, MAIN))
        textDocuments.didOpen(open(workspace.otherUri, OTHER))
        published.clear()

        textDocuments.didChange(fullChange(workspace.depUri, version = 2, text = DEP_V2))
        val uris = published.map { it.uri }

        assertEquals(workspace.depUri, uris.first(), "edited document must be pushed first; got $uris")
        assertTrue(workspace.mainUri in uris, "open consumer must be pushed through the lsp4j layer; got $uris")
        assertFalse(workspace.otherUri in uris, "unrelated open document must not be pushed; got $uris")
    }

    // --- helpers -----------------------------------------------------------------

    private data class Workspace(
        val root: Path,
        val depUri: String,
        val mainUri: String,
        val otherUri: String
    )

    private fun workspace(label: String): Workspace {
        val root = tempDir.newFolder(label).toPath()
        val dep = root.resolve("dep.lua").also { it.writeText(DEP_V1) }
        val main = root.resolve("main.lua").also { it.writeText(MAIN) }
        val other = root.resolve("other.lua").also { it.writeText(OTHER) }
        return Workspace(
            root = root,
            depUri = dep.toUri().toString(),
            mainUri = main.toUri().toString(),
            otherUri = other.toUri().toString()
        )
    }

    private fun initialized(root: Path): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), root.fileName.toString()))
                }
            )
            service.flushBackgroundRebuild()
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), root.fileName.toString()))
                }
            )
        }
    }

    private fun open(uri: String, text: String): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, text))
    }

    private fun fullChange(uri: String, version: Int, text: String): DidChangeTextDocumentParams {
        return DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, version),
            listOf(TextDocumentContentChangeEvent(text))
        )
    }

    companion object {
        /** Same provider / consumer shapes the engine-level dirty-set corpus locks. */
        private const val DEP_V1 = "local M = { value = 1 }\nreturn M"
        /** Public-surface change: a new export dirties every open consumer. */
        private const val DEP_V2 = "local M = { value = 1, extra = 2 }\nreturn M"
        private const val MAIN = "local dep = require(\"dep\")\nreturn { value = dep.value }"
        private const val OTHER = "return { ok = true }"
    }
}
