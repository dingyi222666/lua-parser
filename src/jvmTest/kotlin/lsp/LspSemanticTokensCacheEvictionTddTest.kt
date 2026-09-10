package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.SemanticTokensDeltaParams
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial-audit fix: the per-document semantic-tokens cache (last full payload +
 * resultId used by `semanticTokens/full/delta`) must be evicted when the document is
 * closed and when a watched file is deleted, instead of pinning the last payload for
 * the lifetime of the service.
 *
 * Observable contract (the cache itself is private):
 * - While the cache entry is alive, an unchanged token stream keeps its `resultId`.
 * - After `didClose`, the same token stream gets a fresh `resultId`, and a delta request
 *   quoting the pre-close id is answered with a full re-send rather than an empty delta.
 * - After a watched-file delete of a closed file, tokens are empty with no `resultId`
 *   and a delta quoting the old id is a full (empty) re-send.
 *
 * Test-only. Verification is review-owned (TASK-043). Workers must not run Gradle.
 */
class LspSemanticTokensCacheEvictionTddTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun did_close_evicts_the_cached_result_id() {
        val root = tempDir.newFolder("close-evicts").toPath()
        val file = root.resolve("tokens.lua").also { it.writeText(SOURCE) }
        val uri = file.toUri().toString()
        val service = initialized(root)

        val indexed = service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri)))
        val firstId = assertNotNull(indexed.resultId, "indexed file must yield a cached resultId")
        assertTrue(indexed.data.isNotEmpty(), "indexed file must encode tokens")

        // Opening with identical text keeps the token stream, so the cached id is reused.
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, SOURCE)))
        val opened = service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri)))
        assertEquals(firstId, opened.resultId, "unchanged token stream keeps its resultId while cached")
        assertEquals(indexed.data, opened.data)

        service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))

        // A delta quoting the pre-close id must not be honoured from a stale cache entry.
        val delta = service.semanticTokensFullDelta(
            SemanticTokensDeltaParams(TextDocumentIdentifier(uri), firstId)
        )
        assertTrue(delta.isLeft, "stale pre-close resultId must trigger a full re-send, not an empty delta")
        val resent = delta.left
        assertEquals(indexed.data, resent.data, "the indexed source still encodes the same tokens")
        assertNotEquals(firstId, resent.resultId, "didClose must evict the cached resultId")
        assertNotNull(resent.resultId)
    }

    @Test
    fun watched_delete_of_a_closed_file_drops_cached_tokens() {
        val root = tempDir.newFolder("delete-evicts").toPath()
        val file = root.resolve("deleted.lua").also { it.writeText(SOURCE) }
        val uri = file.toUri().toString()
        val service = initialized(root)

        val before = service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri)))
        val staleId = assertNotNull(before.resultId)
        assertTrue(before.data.isNotEmpty())

        Files.delete(file)
        service.applyWatchedFileChanges(listOf(FileEvent(uri, FileChangeType.Deleted)))

        val delta = service.semanticTokensFullDelta(
            SemanticTokensDeltaParams(TextDocumentIdentifier(uri), staleId)
        )
        assertTrue(delta.isLeft, "deleted file must answer with a full (empty) payload")
        assertTrue(delta.left.data.isEmpty(), "deleted file has no tokens")
        assertNull(delta.left.resultId, "deleted file has no cached resultId")

        val full = service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri)))
        assertTrue(full.data.isEmpty())
        assertNull(full.resultId)
    }

    @Test
    fun watched_delete_keeps_tokens_for_a_still_open_buffer() {
        val root = tempDir.newFolder("delete-open").toPath()
        val file = root.resolve("still-open.lua").also { it.writeText(SOURCE) }
        val uri = file.toUri().toString()
        val service = initialized(root)
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, SOURCE)))

        val before = service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri)))
        val id = assertNotNull(before.resultId)

        Files.delete(file)
        service.applyWatchedFileChanges(listOf(FileEvent(uri, FileChangeType.Deleted)))

        // The open overlay is still authoritative: same stream, same cached id.
        val after = service.semanticTokensFull(SemanticTokensParams(TextDocumentIdentifier(uri)))
        assertEquals(before.data, after.data)
        assertEquals(id, after.resultId, "open overlay must keep its cached resultId across a disk delete")
    }

    // --- helpers -----------------------------------------------------------------

    private fun initialized(root: Path): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), root.fileName.toString()))
                }
            )
        }
    }

    companion object {
        private const val SOURCE = "local function greet(name)\n    return \"hi \" .. name\nend\nreturn greet(\"lua\")"
    }
}
