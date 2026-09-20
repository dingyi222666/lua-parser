package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Adversarial-audit fix: `textDocument/didChange` for a file that was indexed from a
 * workspace folder but never opened with `didOpen`.
 *
 * Before the fix, [LuaLanguageService.didChange] folded ranged edits onto
 * `openDocuments[path].orEmpty()`, so the first ranged edit on an indexed-but-unopened
 * file replaced the whole overlay with the edit fragment. The buffer must instead be
 * based on the indexed source (mirroring how watched-file changes resolve sources), so
 * hover / completion / symbols keep seeing the full document with the edit applied.
 *
 * The jvmTest source set runs on JUnit 4 (`kotlin("test-junit")`), so the JUnit 4
 * [TemporaryFolder] rule stands in for JUnit 5's `@TempDir`.
 *
 * Test-only. Verification is review-owned (TASK-043). Workers must not run Gradle.
 */
class LspDidChangeWithoutOpenTddTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun ranged_change_without_did_open_folds_onto_the_indexed_source() {
        val root = tempDir.newFolder("without-open").toPath()
        val file = root.resolve("module.lua")
        file.writeText(ORIGINAL)
        val uri = file.toUri().toString()
        val service = initialized(root)

        // Precondition: the file is indexed from disk before any didOpen.
        val indexedSymbols = service.documentSymbols(uri).map { it.name }
        assertTrue("alphaMarker" in indexedSymbols, "precondition: indexed symbols $indexedSymbols")
        assertTrue("omegaMarker" in indexedSymbols, "precondition: indexed symbols $indexedSymbols")

        // Two same-length ranged edits, never a full-document replace and never a didOpen:
        // rename the first local at its declaration and at its use site.
        val published = service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 1),
                listOf(
                    TextDocumentContentChangeEvent(
                        Range(Position(0, 6), Position(0, 17)),
                        "alphaMarker".length,
                        "alphaEdited"
                    ),
                    TextDocumentContentChangeEvent(
                        Range(Position(2, 7), Position(2, 18)),
                        "alphaMarker".length,
                        "alphaEdited"
                    )
                )
            )
        )

        // The edited document is published first.
        assertTrue(published.isNotEmpty(), "didChange must publish for the edited document")
        assertEquals(uri, published.first().uri)

        // Symbols prove the edit was applied on top of the full indexed buffer:
        // the renamed local is visible and the untouched second local survived.
        val symbols = service.documentSymbols(uri).map { it.name }
        assertTrue("alphaEdited" in symbols, "ranged edit must be applied; symbols=$symbols")
        assertTrue("omegaMarker" in symbols, "untouched remainder of the document must survive; symbols=$symbols")
        assertFalse("alphaMarker" in symbols, "old name must be gone after the edit; symbols=$symbols")

        // Hover on the second local (line 1) only works when the full document is the buffer.
        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(1, 6))
        )
        assertNotNull(hover, "hover on the untouched local must see the full document")

        // Lexical completion inside the last line sees both locals.
        val completions = service.completion(uri, 2, 25).items.map { it.label }
        assertTrue("omegaMarker" in completions, "completion must see the full document; got $completions")
        assertTrue("alphaEdited" in completions, "completion must see the applied edit; got $completions")
    }

    @Test
    fun full_replace_without_did_open_still_replaces_the_whole_document() {
        val root = tempDir.newFolder("without-open-full").toPath()
        val file = root.resolve("module.lua")
        file.writeText(ORIGINAL)
        val uri = file.toUri().toString()
        val service = initialized(root)

        service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 1),
                listOf(TextDocumentContentChangeEvent("local replaced = 3\nreturn replaced"))
            )
        )

        val symbols = service.documentSymbols(uri).map { it.name }
        assertTrue("replaced" in symbols, "full replace must apply; symbols=$symbols")
        assertFalse("omegaMarker" in symbols, "full replace must not keep the indexed text; symbols=$symbols")
    }

    @Test
    fun ranged_change_on_an_open_document_still_uses_the_open_buffer() {
        val root = tempDir.newFolder("open-overlay").toPath()
        val file = root.resolve("module.lua")
        file.writeText(ORIGINAL)
        val uri = file.toUri().toString()
        val service = initialized(root)

        // The open overlay differs from disk; ranged edits must fold onto the overlay.
        val overlay = "local overlayMarker = 1\nlocal omegaMarker = 2\nreturn overlayMarker + omegaMarker"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, overlay)
            )
        )
        service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(
                    TextDocumentContentChangeEvent(
                        Range(Position(1, 6), Position(1, 17)),
                        "omegaMarker".length,
                        "omegaEdited"
                    ),
                    TextDocumentContentChangeEvent(
                        Range(Position(2, 23), Position(2, 34)),
                        "omegaMarker".length,
                        "omegaEdited"
                    )
                )
            )
        )

        val symbols = service.documentSymbols(uri).map { it.name }
        assertTrue("overlayMarker" in symbols, "open overlay must stay authoritative; symbols=$symbols")
        assertTrue("omegaEdited" in symbols, "ranged edit must apply to the overlay; symbols=$symbols")
        assertFalse("alphaMarker" in symbols, "disk text must not leak into an open buffer; symbols=$symbols")
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
        /** Line 0: `local alphaMarker = 1`; line 1: `local omegaMarker = 2`; line 2: use site. */
        private const val ORIGINAL = "local alphaMarker = 1\nlocal omegaMarker = 2\nreturn alphaMarker + omegaMarker"
    }
}
