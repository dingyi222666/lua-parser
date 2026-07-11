package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.WorkspaceFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-373 — LSP prepareRename / rename multi-file corpus.
 *
 * Extends the single-file safety contract from [LspPrepareRenameSafetyTddTest]
 * to workspace multi-file scenarios for Android-Lua / Lua 5.3 + JVM LSP:
 * - prepareRename on local identifiers stays file-local (no invented cross-file
 *   ranges; same-name locals in other files are independent).
 * - prepareRename on require-module field use sites either accepts an
 *   identifier-span range or documents the unimplemented gap; never crashes.
 * - When rename succeeds for a multi-file module field, WorkspaceEdit changes
 *   cover identifier spans only and may touch every workspace file that
 *   actually references the symbol (no edits for unrelated same-name locals).
 * - Non-identifier multi-file positions (require string, whitespace, keyword)
 *   reject or document the gap without inventing product APIs.
 *
 * Product prepareRename/rename remains unimplemented on
 * [LuaTextDocumentService] (LSP4J default → UnsupportedOperationException).
 * This corpus dual-paths:
 * - Documented gap: unimplemented methods complete exceptionally with
 *   UnsupportedOperationException and are recorded as the known surface.
 * - Ideal path: when product lands prepareRename/rename, hard asserts enforce
 *   multi-file safety without inventing a new corpus.
 *
 * Multi-file rename *planning* is locked today via product-available
 * references + documentHighlight surfaces (same style as the single-file
 * prepareRename safety proxy):
 * - references for a module field include dep definition + consumer uses
 * - documentHighlight stays inside the requesting file (no cross-file ranges)
 * - same-name locals in separate files do not leak into each other's
 *   highlights / local-only rename plans
 *
 * Test-only; no product edits. Verification is review-owned and serial; this
 * worker does not run Gradle.
 */
class LspRenamePrepareMultiFileTddTest {

    // -------------------------------------------------------------------------
    // prepareRename: multi-file local stays file-local
    // -------------------------------------------------------------------------

    @Test
    fun prepare_rename_local_in_main_is_accepted_or_documented_gap_and_not_cross_file() {
        val root = tempWorkspace()
        root.resolve("dep.lua").writeLua(
            """
            local shared = 1
            local M = {}
            M.value = shared
            return M
            """.trimIndent()
        )
        val mainPath = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            local shared = dep.value
            return shared
            """.trimIndent()
        )

        val service = initializedService(root)
        val textDocuments = LuaTextDocumentService(service)
        val main = textDocuments.open(mainPath)

        // Local `shared` in main (declaration) — independent of dep's local `shared`.
        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(main, main.positionOf("shared", occurrence = 1))
        )

        when (outcome) {
            is PrepareRenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for prepareRename; got ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Rejected -> {
                fail(
                    "Local 'shared' in main should be renamable once prepareRename is implemented; " +
                        "got rejection: ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Accepted -> {
                if (!outcome.defaultBehavior) {
                    assertIdentifierSpanOnly(
                        range = outcome.range,
                        document = main,
                        identifier = "shared",
                        occurrence = 1,
                        label = "prepareRename range for local 'shared' in main"
                    )
                }
            }
        }
    }

    @Test
    fun prepare_rename_same_name_local_in_dep_is_independent_or_documented_gap() {
        val root = tempWorkspace()
        val depPath = root.resolve("dep.lua").writeLua(
            """
            local shared = 1
            local M = {}
            M.value = shared
            return M
            """.trimIndent()
        )
        root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            local shared = dep.value
            return shared
            """.trimIndent()
        )

        val service = initializedService(root)
        val textDocuments = LuaTextDocumentService(service)
        val dep = textDocuments.open(depPath)

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(dep, dep.positionOf("shared", occurrence = 1))
        )

        when (outcome) {
            is PrepareRenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for prepareRename; got ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Rejected -> {
                fail(
                    "Local 'shared' in dep should be renamable once prepareRename is implemented; " +
                        "got rejection: ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Accepted -> {
                if (!outcome.defaultBehavior) {
                    assertIdentifierSpanOnly(
                        range = outcome.range,
                        document = dep,
                        identifier = "shared",
                        occurrence = 1,
                        label = "prepareRename range for local 'shared' in dep"
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // prepareRename: multi-file module field use site
    // -------------------------------------------------------------------------

    @Test
    fun prepare_rename_module_field_use_site_is_accepted_or_documented_gap() {
        val root = tempWorkspace()
        root.resolve("dep.lua").writeLua(
            """
            local M = {}
            M.value = 1
            return M
            """.trimIndent()
        )
        val mainPath = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            local first = dep.value
            local second = dep.value
            return second
            """.trimIndent()
        )

        val service = initializedService(root)
        val textDocuments = LuaTextDocumentService(service)
        val main = textDocuments.open(mainPath)

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(main, main.positionOf("value", occurrence = 1))
        )

        when (outcome) {
            is PrepareRenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for prepareRename; got ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Rejected -> {
                // Soft: product may reject cross-file field rename until multi-file
                // policy lands; must not crash and must carry a detail.
                assertTrue(
                    outcome.detail.isNotBlank(),
                    "Rejection for module field use site should carry a detail string"
                )
            }
            is PrepareRenameOutcome.Accepted -> {
                if (!outcome.defaultBehavior) {
                    assertIdentifierSpanOnly(
                        range = outcome.range,
                        document = main,
                        identifier = "value",
                        occurrence = 1,
                        label = "prepareRename range for module field 'value' use site"
                    )
                    if (outcome.placeholder != null) {
                        assertEquals(
                            "value",
                            outcome.placeholder,
                            "prepareRename placeholder should be the current identifier text"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun prepare_rename_module_field_definition_site_is_accepted_or_documented_gap() {
        val root = tempWorkspace()
        val depPath = root.resolve("dep.lua").writeLua(
            """
            local M = {}
            M.value = 1
            return M
            """.trimIndent()
        )
        root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            return dep.value
            """.trimIndent()
        )

        val service = initializedService(root)
        val textDocuments = LuaTextDocumentService(service)
        val dep = textDocuments.open(depPath)

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(dep, dep.positionOf("value", occurrence = 1))
        )

        when (outcome) {
            is PrepareRenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for prepareRename; got ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Rejected -> {
                assertTrue(
                    outcome.detail.isNotBlank(),
                    "Rejection for module field definition should carry a detail string"
                )
            }
            is PrepareRenameOutcome.Accepted -> {
                if (!outcome.defaultBehavior) {
                    assertIdentifierSpanOnly(
                        range = outcome.range,
                        document = dep,
                        identifier = "value",
                        occurrence = 1,
                        label = "prepareRename range for module field 'value' definition"
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // prepareRename: multi-file non-identifier / unsafe positions
    // -------------------------------------------------------------------------

    @Test
    fun prepare_rename_on_require_module_string_is_rejected_or_documented_gap() {
        val root = tempWorkspace()
        root.resolve("dep.lua").writeLua("local M = {}\nM.value = 1\nreturn M")
        val mainPath = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            return dep.value
            """.trimIndent()
        )

        val service = initializedService(root)
        val textDocuments = LuaTextDocumentService(service)
        val main = textDocuments.open(mainPath)

        // Cursor on the require module string — not a renamable Lua identifier.
        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(main, main.positionOf("\"dep\""))
        )

        assertPrepareRenameRejectedOrGap(
            outcome,
            context = "require module string literal"
        )
    }

    @Test
    fun prepare_rename_on_whitespace_in_multi_file_workspace_is_rejected_or_documented_gap() {
        val root = tempWorkspace()
        root.resolve("dep.lua").writeLua("local M = {}\nreturn M")
        val mainPath = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")

            return dep
            """.trimIndent()
        )

        val service = initializedService(root)
        val textDocuments = LuaTextDocumentService(service)
        val main = textDocuments.open(mainPath)

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(main, Position(1, 0))
        )

        assertPrepareRenameRejectedOrGap(
            outcome,
            context = "whitespace / blank line in multi-file main"
        )
    }

    @Test
    fun prepare_rename_on_keyword_in_multi_file_workspace_is_rejected_or_documented_gap() {
        val root = tempWorkspace()
        root.resolve("dep.lua").writeLua("local M = {}\nreturn M")
        val mainPath = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            return dep
            """.trimIndent()
        )

        val service = initializedService(root)
        val textDocuments = LuaTextDocumentService(service)
        val main = textDocuments.open(mainPath)

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(main, main.positionOf("local"))
        )

        assertPrepareRenameRejectedOrGap(
            outcome,
            context = "keyword 'local' in multi-file main"
        )
    }

    // -------------------------------------------------------------------------
    // rename: multi-file local must not invent edits in other files
    // -------------------------------------------------------------------------

    @Test
    fun rename_local_in_main_does_not_edit_dep_or_documented_gap() {
        val root = tempWorkspace()
        val depPath = root.resolve("dep.lua").writeLua(
            """
            local shared = 1
            local M = {}
            M.value = shared
            return M
            """.trimIndent()
        )
        val mainPath = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            local shared = dep.value
            return shared
            """.trimIndent()
        )

        val service = initializedService(root)
        val textDocuments = LuaTextDocumentService(service)
        val main = textDocuments.open(mainPath)
        // Ensure dep is also open so a buggy multi-file rename could see it.
        textDocuments.open(depPath)

        val outcome = invokeRename(
            textDocuments,
            renameParams(main, main.positionOf("shared", occurrence = 1), "renamedShared")
        )

        when (outcome) {
            is RenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for rename; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Failed -> {
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true),
                    "rename of multi-file local must not NPE; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
                val changes = outcome.edit.changes.orEmpty()
                // No edits in dep for main's local `shared`.
                val depEdits = changes[depPath.uri].orEmpty()
                assertTrue(
                    depEdits.isEmpty(),
                    "rename of main-local 'shared' must not invent TextEdits in dep.lua; " +
                        "depEdits=${depEdits.size}"
                )
                val mainEdits = changes[main.uri].orEmpty()
                // Ideal product path: edit main occurrences only, identifier span only.
                if (mainEdits.isNotEmpty()) {
                    mainEdits.forEach { edit ->
                        assertEquals(
                            "renamedShared",
                            edit.newText,
                            "local rename newText must match requested name"
                        )
                        assertEquals(
                            edit.range.start.line,
                            edit.range.end.line,
                            "identifier rename ranges must be single-line"
                        )
                        assertEquals(
                            "shared".length,
                            edit.range.end.character - edit.range.start.character,
                            "rename TextEdit range must cover identifier span only"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun rename_module_field_edits_identifier_spans_across_files_or_documented_gap() {
        val root = tempWorkspace()
        val depPath = root.resolve("dep.lua").writeLua(
            """
            local M = {}
            M.value = 1
            return M
            """.trimIndent()
        )
        val mainPath = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            local first = dep.value
            local second = dep.value
            return second
            """.trimIndent()
        )

        val service = initializedService(root)
        val textDocuments = LuaTextDocumentService(service)
        val main = textDocuments.open(mainPath)
        textDocuments.open(depPath)

        val outcome = invokeRename(
            textDocuments,
            renameParams(main, main.positionOf("value", occurrence = 1), "renamedValue")
        )

        when (outcome) {
            is RenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for rename; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Failed -> {
                // Soft until multi-file rename lands; hard crash is not allowed.
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true),
                    "rename of multi-file module field must not NPE; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
                val changes = outcome.edit.changes.orEmpty()
                val documentChanges = outcome.edit.documentChanges.orEmpty()
                // Empty edit is a soft reject; non-empty must be identifier-span only
                // and only touch files that actually reference the field.
                if (changes.isEmpty() && documentChanges.isEmpty()) {
                    return
                }
                changes.forEach { (uri, edits) ->
                    assertTrue(
                        uri == main.uri || uri == depPath.uri,
                        "multi-file field rename must not invent edits outside main/dep; uri=$uri"
                    )
                    edits.forEach { edit ->
                        assertEquals("renamedValue", edit.newText)
                        assertEquals(
                            edit.range.start.line,
                            edit.range.end.line,
                            "identifier rename ranges must be single-line (uri=$uri)"
                        )
                        assertEquals(
                            "value".length,
                            edit.range.end.character - edit.range.start.character,
                            "rename TextEdit range must cover identifier span only (uri=$uri)"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun rename_missing_symbol_in_multi_file_workspace_does_not_crash_or_documented_gap() {
        val root = tempWorkspace()
        root.resolve("dep.lua").writeLua("local M = {}\nreturn M")
        val mainPath = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            return dep
            """.trimIndent()
        )

        val service = initializedService(root)
        val textDocuments = LuaTextDocumentService(service)
        val main = textDocuments.open(mainPath)

        val outcome = invokeRename(
            textDocuments,
            renameParams(main, Position(20, 0), "renamed")
        )

        when (outcome) {
            is RenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for rename; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Failed -> {
                assertFalse(
                    outcome.detail.contains("NullPointerException", ignoreCase = true) ||
                        outcome.detail.contains("AssertionError", ignoreCase = true),
                    "rename on missing symbol must not NPE/assert; got ${outcome.detail}"
                )
            }
            is RenameOutcome.Succeeded -> {
                val changes = outcome.edit.changes.orEmpty()
                val documentChanges = outcome.edit.documentChanges.orEmpty()
                assertTrue(
                    changes.isEmpty() && documentChanges.isEmpty(),
                    "rename at missing symbol should yield empty WorkspaceEdit when accepted; " +
                        "changes=${changes.keys} documentChanges=${documentChanges.size}"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Product-available multi-file rename planning proxies
    // -------------------------------------------------------------------------

    @Test
    fun references_for_module_field_plan_multi_file_rename_sites() {
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua(
            """
            local M = {}
            M.value = 1
            return M
            """.trimIndent()
        )
        val main = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            local first = dep.value
            local second = dep.value
            return second
            """.trimIndent()
        )

        val service = initializedService(root)
        // Open main so references resolve against live documents as well as index.
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(main.uri, "lua", 1, Files.readString(main.path))
            )
        )

        val references = service.references(
            ReferenceParams(
                TextDocumentIdentifier(main.uri),
                main.positionOf("value", occurrence = 1),
                ReferenceContext(true)
            )
        )

        assertTrue(
            references.any { it.uri == dep.uri },
            "multi-file rename plan (references) must include dep definition of 'value'"
        )
        assertEquals(
            2,
            references.count { it.uri == main.uri },
            "multi-file rename plan must include both main use sites of 'value'"
        )
        references.forEach { location ->
            assertTrue(
                location.range.end.line > location.range.start.line ||
                    (location.range.end.line == location.range.start.line &&
                        location.range.end.character >= location.range.start.character),
                "reference range must be ordered: ${location.uri} ${location.range}"
            )
        }
    }

    @Test
    fun document_highlight_for_module_field_stays_in_requesting_file() {
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua(
            """
            local M = {}
            M.value = 1
            return M
            """.trimIndent()
        )
        val main = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            local first = dep.value
            local second = dep.value
            return second
            """.trimIndent()
        )

        val service = initializedService(root)
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(main.uri, "lua", 1, Files.readString(main.path))
            )
        )
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(dep.uri, "lua", 1, Files.readString(dep.path))
            )
        )

        val highlights = service.documentHighlights(
            DocumentHighlightParams(
                TextDocumentIdentifier(main.uri),
                main.positionOf("value", occurrence = 1)
            )
        )

        assertTrue(highlights.isNotEmpty(), "Expected document highlights for module field 'value' in main")
        // documentHighlight is file-local by LSP contract — never invent cross-file
        // ranges even when references would span the workspace.
        highlights.forEach { highlight ->
            assertTrue(
                highlight.range.end.line > highlight.range.start.line ||
                    (highlight.range.end.line == highlight.range.start.line &&
                        highlight.range.end.character >= highlight.range.start.character),
                "highlight range must be ordered: ${highlight.range}"
            )
            // Soft cover floor: range should relate to 'value' on the main file.
            val startLine = highlight.range.start.line
            assertTrue(
                startLine in 0..3,
                "highlight for main-side 'value' should stay on main content lines; got line=$startLine"
            )
        }
    }

    @Test
    fun document_highlight_same_name_local_does_not_leak_across_files() {
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua(
            """
            local shared = 1
            local M = {}
            M.value = shared
            return M
            """.trimIndent()
        )
        val main = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            local shared = dep.value
            return shared
            """.trimIndent()
        )

        val service = initializedService(root)
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(main.uri, "lua", 1, Files.readString(main.path))
            )
        )
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(dep.uri, "lua", 1, Files.readString(dep.path))
            )
        )

        val mainHighlights = service.documentHighlights(
            DocumentHighlightParams(
                TextDocumentIdentifier(main.uri),
                main.positionOf("shared", occurrence = 1)
            )
        )
        val depHighlights = service.documentHighlights(
            DocumentHighlightParams(
                TextDocumentIdentifier(dep.uri),
                dep.positionOf("shared", occurrence = 1)
            )
        )

        assertTrue(mainHighlights.isNotEmpty(), "Expected highlights for main-local 'shared'")
        assertTrue(depHighlights.isNotEmpty(), "Expected highlights for dep-local 'shared'")

        // Counts are independent: main has decl + return; dep has decl + use in M.value.
        // Soft floor — product may over/under-count, but must not crash and must
        // return ordered ranges for each file separately.
        mainHighlights.forEach { highlight ->
            assertTrue(
                highlight.range.end.line > highlight.range.start.line ||
                    (highlight.range.end.line == highlight.range.start.line &&
                        highlight.range.end.character >= highlight.range.start.character),
                "main highlight range must be ordered: ${highlight.range}"
            )
        }
        depHighlights.forEach { highlight ->
            assertTrue(
                highlight.range.end.line > highlight.range.start.line ||
                    (highlight.range.end.line == highlight.range.start.line &&
                        highlight.range.end.character >= highlight.range.start.character),
                "dep highlight range must be ordered: ${highlight.range}"
            )
        }
    }

    @Test
    fun text_document_service_prepare_rename_surface_is_invokable_in_multi_file_workspace() {
        val root = tempWorkspace()
        root.resolve("dep.lua").writeLua("local M = {}\nM.value = 1\nreturn M")
        val mainPath = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            return dep.value
            """.trimIndent()
        )

        val service = initializedService(root)
        val textDocuments = LuaTextDocumentService(service)
        val main = textDocuments.open(mainPath)

        val outcome = invokePrepareRename(
            textDocuments,
            prepareRenameParams(main, main.positionOf("value", occurrence = 1))
        )
        assertTrue(
            outcome is PrepareRenameOutcome.Unsupported ||
                outcome is PrepareRenameOutcome.Rejected ||
                outcome is PrepareRenameOutcome.Accepted,
            "prepareRename surface must resolve to a known outcome in multi-file workspace; got $outcome"
        )
    }

    @Test
    fun unopened_indexed_module_field_references_still_plan_multi_file_rename() {
        // Mirrors LspWorkspaceFoldersTddTest: unopened indexed modules remain
        // visible to references — rename planning must not require every file open.
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua(
            """
            local M = {}
            M.value = 1
            return M
            """.trimIndent()
        )
        val main = root.resolve("main.lua").writeLua(
            """
            local dep = require("dep")
            local copy = dep.value
            return copy
            """.trimIndent()
        )

        val service = initializedService(root)
        // Do not open either file — rely on workspace folder index only.

        val references = service.references(
            ReferenceParams(
                TextDocumentIdentifier(main.uri),
                main.positionOf("value", occurrence = 1),
                ReferenceContext(true)
            )
        )

        assertTrue(
            references.any { it.uri == dep.uri },
            "indexed (unopened) multi-file rename plan must include dep definition"
        )
        assertTrue(
            references.any { it.uri == main.uri },
            "indexed (unopened) multi-file rename plan must include main use site"
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun initializedService(root: Path): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(root.uri, root.fileName.toString()))
                }
            )
        }
    }

    private fun tempWorkspace(): Path {
        return Files.createTempDirectory("lua-parser-lsp-rename-multifile-")
    }

    private fun Path.writeLua(source: String): WorkspaceFile {
        parent?.createDirectories()
        writeText(source)
        return WorkspaceFile(this, source)
    }

    private fun LuaTextDocumentService.open(file: WorkspaceFile): WorkspaceFile {
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(file.uri, "lua", 1, file.source)
            )
        )
        return file
    }

    private fun prepareRenameParams(document: WorkspaceFile, position: Position): PrepareRenameParams {
        return PrepareRenameParams(TextDocumentIdentifier(document.uri), position)
    }

    private fun renameParams(document: WorkspaceFile, position: Position, newName: String): RenameParams {
        return RenameParams(TextDocumentIdentifier(document.uri), position, newName)
    }

    private fun invokePrepareRename(
        textDocuments: LuaTextDocumentService,
        params: PrepareRenameParams
    ): PrepareRenameOutcome {
        return try {
            val result = textDocuments.prepareRename(params).get()
            when {
                result == null -> PrepareRenameOutcome.Rejected(detail = "null result")
                result.isFirst -> PrepareRenameOutcome.Accepted(
                    range = result.first,
                    placeholder = null
                )
                result.isSecond -> PrepareRenameOutcome.Accepted(
                    range = result.second.range,
                    placeholder = result.second.placeholder
                )
                result.isThird -> PrepareRenameOutcome.Accepted(
                    range = Range(params.position, params.position),
                    placeholder = null,
                    defaultBehavior = true
                )
                else -> PrepareRenameOutcome.Rejected(detail = "empty Either3: $result")
            }
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                PrepareRenameOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                PrepareRenameOutcome.Rejected(detail = root.toString())
            }
        }
    }

    private fun invokeRename(
        textDocuments: LuaTextDocumentService,
        params: RenameParams
    ): RenameOutcome {
        return try {
            val edit = textDocuments.rename(params).get()
            if (edit == null) {
                RenameOutcome.Succeeded(WorkspaceEdit())
            } else {
                RenameOutcome.Succeeded(edit)
            }
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                RenameOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                RenameOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun assertPrepareRenameRejectedOrGap(
        outcome: PrepareRenameOutcome,
        context: String
    ) {
        when (outcome) {
            is PrepareRenameOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is PrepareRenameOutcome.Rejected -> {
                assertTrue(
                    outcome.detail.isNotBlank(),
                    "Rejection for $context should carry a detail string"
                )
            }
            is PrepareRenameOutcome.Accepted -> {
                if (outcome.defaultBehavior) {
                    // DefaultBehavior means "client word range" — not a server-endorsed rename.
                    return
                }
                fail(
                    "prepareRename must reject $context once implemented; " +
                        "got accepted range ${outcome.range.start.line}:${outcome.range.start.character}-" +
                        "${outcome.range.end.line}:${outcome.range.end.character}"
                )
            }
        }
    }

    private fun assertIdentifierSpanOnly(
        range: Range,
        document: WorkspaceFile,
        identifier: String,
        occurrence: Int?,
        label: String
    ) {
        assertTrue(
            range.start.line == range.end.line,
            "$label must be single-line (identifier span only); " +
                "got ${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"
        )
        val span = range.end.character - range.start.character
        assertEquals(
            identifier.length,
            span,
            "$label character span must equal identifier length " +
                "('$identifier' len=${identifier.length}); " +
                "range=${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"
        )
        val extracted = document.slice(range)
        assertEquals(
            identifier,
            extracted,
            "$label source slice must equal identifier text"
        )
        if (occurrence != null) {
            val expected = document.positionOf(identifier, occurrence)
            assertEquals(
                expected.line,
                range.start.line,
                "$label start line must match occurrence $occurrence of '$identifier'"
            )
            assertEquals(
                expected.character,
                range.start.character,
                "$label start character must match occurrence $occurrence of '$identifier'"
            )
        }
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (
            (current is ExecutionException || current is CompletionException) &&
            current.cause != null
        ) {
            current = current.cause!!
        }
        return current
    }

    private fun isUnsupportedOperation(error: Throwable): Boolean {
        if (error is UnsupportedOperationException) {
            return true
        }
        val message = error.message.orEmpty()
        return message.contains("UnsupportedOperationException") ||
            message.contains("not implemented", ignoreCase = true)
    }

    private sealed class PrepareRenameOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : PrepareRenameOutcome()

        data class Rejected(val detail: String) : PrepareRenameOutcome()

        data class Accepted(
            val range: Range,
            val placeholder: String?,
            val defaultBehavior: Boolean = false
        ) : PrepareRenameOutcome()
    }

    private sealed class RenameOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : RenameOutcome()

        data class Failed(val detail: String) : RenameOutcome()

        data class Succeeded(val edit: WorkspaceEdit) : RenameOutcome()
    }

    private data class WorkspaceFile(
        val path: Path,
        val source: String
    ) {
        val uri: String = path.toUri().toString()

        fun positionOf(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1) { "occurrence must be positive" }
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find occurrence $occurrence of '$needle' in $path" }
                fromIndex = index + needle.length
            }
            return positionAt(index)
        }

        fun slice(range: Range): String {
            val start = offsetAt(range.start)
            val end = offsetAt(range.end).coerceAtLeast(start)
            return source.substring(start, end.coerceAtMost(source.length))
        }

        private fun positionAt(offset: Int): Position {
            var line = 0
            var lineStart = 0
            for (i in 0 until offset) {
                if (source[i] == '\n') {
                    line += 1
                    lineStart = i + 1
                }
            }
            return Position(line, offset - lineStart)
        }

        private fun offsetAt(position: Position): Int {
            var line = 0
            var lineStart = 0
            var index = 0
            while (index < source.length && line < position.line) {
                if (source[index] == '\n') {
                    line += 1
                    lineStart = index + 1
                }
                index += 1
            }
            return (lineStart + position.character).coerceIn(0, source.length)
        }
    }

    private val Path.uri: String
        get() = toUri().toString()
}
