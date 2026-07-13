package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SelectionRangeParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.services.LanguageClient
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-project-style editor lifecycle corpus for LuaLanguageService / TextDocumentService.
 *
 * Focus: didOpen/didChange/didClose/didSave sequences, version bumps, reopen-after-close,
 * incremental mid-function / mid-string / mid-comment edits, rapid didChange bursts,
 * concurrent multi-file open, URI edges (spaces / nested dirs), CRLF vs LF, large-file smoke,
 * initialize capability surface, soft shutdown/exit probes.
 *
 * Test-only; no product edits. Never G:/. Does not run full jvmTest.
 */
class LspRealProjectEditorLifecycleTddTest {

    // -------------------------------------------------------------------------
    // Initialize / capability negotiation
    // -------------------------------------------------------------------------

    @Test
    fun initialize_advertises_full_sync_for_editor_buffer_model() {
        val service = LuaLanguageService()
        val caps = service.initialize(InitializeParams()).capabilities
        assertEquals(TextDocumentSyncKind.Full, caps.textDocumentSync.left)
    }

    @Test
    fun initialize_with_workspace_folder_temp_root_keeps_document_services() {
        val root = tempWorkspace("init-folder")
        val server = LuaLanguageServer()
        val result = server.initialize(
            InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), root.fileName.toString()))
            }
        ).get()
        assertNotNull(result.capabilities)
        assertNotNull(server.textDocumentService)
    }

    @Test
    fun initialize_capabilities_surface_includes_core_editor_features() {
        val caps = LuaLanguageService().initialize(InitializeParams()).capabilities
        assertNotNull(caps.hoverProvider)
        assertNotNull(caps.completionProvider)
        assertNotNull(caps.definitionProvider)
        assertNotNull(caps.signatureHelpProvider)
        assertNotNull(caps.documentSymbolProvider)
        assertNotNull(caps.foldingRangeProvider)
        assertNotNull(caps.selectionRangeProvider)
        assertNotNull(caps.renameProvider)
        assertNotNull(caps.codeActionProvider)
        assertNotNull(caps.documentFormattingProvider)
    }

    @Test
    fun initialize_signature_help_trigger_characters_match_editor_typing() {
        val caps = LuaLanguageService().initialize(InitializeParams()).capabilities
        assertEquals(listOf("(", ","), caps.signatureHelpProvider.triggerCharacters)
        assertEquals(listOf(")"), caps.signatureHelpProvider.retriggerCharacters)
    }

    // -------------------------------------------------------------------------
    // didOpen / didChange / didClose / didSave lifecycle
    // -------------------------------------------------------------------------

    @Test
    fun open_change_close_sequence_publishes_and_clears_for_real_module() {
        val h = harness()
        val uri = "file:///workspace/src/app/main.lua"
        h.textDocuments.didOpen(openParams(uri, MODULE_MAIN, version = 1))
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
        h.published.clear()

        h.textDocuments.didChange(fullChange(uri, 2, "local ="))
        assertTrue(lastFor(h.published, uri).diagnostics.isNotEmpty())
        h.published.clear()

        h.textDocuments.didClose(closeParams(uri))
        assertEquals(uri, h.published.single().uri)
        assertTrue(h.published.single().diagnostics.isEmpty())
    }

    @Test
    fun did_save_after_open_is_noop_and_keeps_buffer_queryable() {
        val h = harness()
        val uri = "file:///workspace/src/app/save_probe.lua"
        h.textDocuments.didOpen(openParams(uri, MODULE_MAIN, version = 1))
        h.textDocuments.didSave(DidSaveTextDocumentParams(TextDocumentIdentifier(uri), null))
        assertTrue(
            h.languageService.documentSymbols("workspace/src/app/save_probe.lua")
                .any { it.name == "App" || it.name == "start" || it.name == "M" || it.name == "boot" }
        )
    }

    @Test
    fun did_save_with_text_payload_does_not_crash_or_clear_open_document() {
        val h = harness()
        val uri = "file:///workspace/src/app/save_text.lua"
        h.textDocuments.didOpen(openParams(uri, MODULE_MAIN, version = 1))
        h.textDocuments.didSave(DidSaveTextDocumentParams(TextDocumentIdentifier(uri), MODULE_MAIN))
        assertTrue(h.languageService.diagnosticsForUri(uri).diagnostics.isEmpty())
    }

    @Test
    fun reopen_after_close_restores_fresh_version_and_symbols() {
        val h = harness()
        val uri = "file:///workspace/src/app/reopen.lua"
        h.textDocuments.didOpen(openParams(uri, "local a = 1\nreturn a", version = 1))
        h.textDocuments.didClose(closeParams(uri))
        h.published.clear()

        h.textDocuments.didOpen(openParams(uri, "local b = 2\nreturn b", version = 1))
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
        val symbols = h.languageService.documentSymbols("workspace/src/app/reopen.lua").map { it.name }
        assertTrue("b" in symbols)
        assertFalse("a" in symbols)
    }

    @Test
    fun reopen_after_close_with_higher_open_version_is_accepted() {
        val h = harness()
        val uri = "file:///workspace/src/app/reopen_v.lua"
        h.textDocuments.didOpen(openParams(uri, MODULE_MAIN, version = 5))
        h.textDocuments.didClose(closeParams(uri))
        h.published.clear()
        h.textDocuments.didOpen(openParams(uri, MODULE_MAIN, version = 1))
        assertEquals(listOf(uri), h.published.map { it.uri })
    }

    @Test
    fun version_bump_sequence_1_2_3_applies_last_text_only() {
        val h = harness()
        val uri = "file:///workspace/src/app/versions.lua"
        h.textDocuments.didOpen(openParams(uri, "local v1 = 1\nreturn v1", version = 1))
        h.textDocuments.didChange(fullChange(uri, 2, "local v2 = 2\nreturn v2"))
        h.textDocuments.didChange(fullChange(uri, 3, "local v3 = 3\nreturn v3"))
        val names = h.languageService.documentSymbols("workspace/src/app/versions.lua").map { it.name }
        assertTrue("v3" in names)
        assertFalse("v1" in names)
        assertFalse("v2" in names)
    }

    @Test
    fun stale_version_after_bump_is_ignored_for_symbols() {
        val h = harness()
        val uri = "file:///workspace/src/app/stale.lua"
        h.textDocuments.didOpen(openParams(uri, "local current = 1\nreturn current", version = 4))
        h.textDocuments.didChange(fullChange(uri, 5, "local next = 2\nreturn next"))
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 4, "local stale = 9\nreturn stale"))
        assertTrue(h.published.isEmpty())
        val names = h.languageService.documentSymbols("workspace/src/app/stale.lua").map { it.name }
        assertTrue("next" in names)
        assertFalse("stale" in names)
    }

    @Test
    fun equal_version_change_is_ignored() {
        val h = harness()
        val uri = "file:///workspace/src/app/equal_v.lua"
        h.textDocuments.didOpen(openParams(uri, "local keep = 1\nreturn keep", version = 2))
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 2, "local overwritten = 0\nreturn overwritten"))
        assertTrue(h.published.isEmpty())
        assertTrue(
            h.languageService.documentSymbols("workspace/src/app/equal_v.lua").any { it.name == "keep" }
        )
    }

    @Test
    fun change_unknown_document_is_rejected_without_publish() {
        val h = harness()
        h.textDocuments.didChange(fullChange("file:///workspace/src/missing.lua", 1, "return 1"))
        assertTrue(h.published.isEmpty())
    }

    @Test
    fun change_after_close_is_rejected() {
        val h = harness()
        val uri = "file:///workspace/src/app/after_close.lua"
        h.textDocuments.didOpen(openParams(uri, MODULE_MAIN, version = 1))
        h.textDocuments.didClose(closeParams(uri))
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 2, "local ="))
        assertTrue(h.published.isEmpty())
    }

    @Test
    fun close_unknown_document_still_clears_client_diagnostics() {
        val h = harness()
        h.textDocuments.didClose(closeParams("file:///workspace/never-opened-lifecycle.lua"))
        assertEquals("file:///workspace/never-opened-lifecycle.lua", h.published.single().uri)
        assertTrue(h.published.single().diagnostics.isEmpty())
    }

    @Test
    fun open_close_open_close_cycle_leaves_no_symbols() {
        val h = harness()
        val uri = "file:///workspace/src/app/cycle.lua"
        val path = "workspace/src/app/cycle.lua"
        repeat(3) {
            h.textDocuments.didOpen(openParams(uri, MODULE_MAIN, version = 1))
            h.textDocuments.didClose(closeParams(uri))
        }
        assertTrue(h.languageService.documentSymbols(path).isEmpty())
    }

    @Test
    fun same_uri_reopen_without_close_replaces_buffer() {
        val h = harness()
        val uri = "file:///workspace/src/app/reopen_replace.lua"
        h.textDocuments.didOpen(openParams(uri, "local old = 1\nreturn old", version = 1))
        h.textDocuments.didOpen(openParams(uri, "local fresh = 2\nreturn fresh", version = 2))
        val names = h.languageService.documentSymbols("workspace/src/app/reopen_replace.lua").map { it.name }
        assertTrue("fresh" in names)
        assertFalse("old" in names)
    }

    @Test
    fun multi_step_open_edit_save_close_reopen_keeps_last_open_snapshot() {
        val h = harness()
        val uri = "file:///workspace/src/app/session.lua"
        h.textDocuments.didOpen(openParams(uri, "local a = 1\nreturn a", version = 1))
        h.textDocuments.didChange(fullChange(uri, 2, "local b = 2\nreturn b"))
        h.textDocuments.didSave(DidSaveTextDocumentParams(TextDocumentIdentifier(uri), "local b = 2\nreturn b"))
        h.textDocuments.didClose(closeParams(uri))
        h.textDocuments.didOpen(openParams(uri, "local c = 3\nreturn c", version = 1))
        assertTrue(
            h.languageService.documentSymbols("workspace/src/app/session.lua").any { it.name == "c" }
        )
    }

    @Test
    fun change_then_close_clears_symbols_for_that_uri_only() {
        val h = harness()
        val keep = "file:///workspace/src/app/keep_life.lua"
        val target = "file:///workspace/src/app/drop_life.lua"
        h.textDocuments.didOpen(openParams(keep, "local keep = 1\nreturn keep", version = 1))
        h.textDocuments.didOpen(openParams(target, "local drop = 2\nreturn drop", version = 1))
        h.textDocuments.didChange(fullChange(target, 2, "local drop = 3\nreturn drop"))
        h.textDocuments.didClose(closeParams(target))
        assertTrue(h.languageService.documentSymbols("workspace/src/app/keep_life.lua").any { it.name == "keep" })
        assertTrue(h.languageService.documentSymbols("workspace/src/app/drop_life.lua").isEmpty())
    }

    // -------------------------------------------------------------------------
    // Incremental mid-function / mid-string / mid-comment edits
    // -------------------------------------------------------------------------

    @Test
    fun range_edit_mid_function_renames_local_binding() {
        val service = plainService()
        val uri = "file:///workspace/src/app/mid_fn.lua"
        val source = """
            local function render(name)
                local message = name
                return message
            end
            return render
        """.trimIndent()
        service.didOpen(openParams(uri, source, version = 1))
        service.didChange(
            rangeChange(
                uri = uri,
                version = 2,
                start = Position(1, 10),
                end = Position(1, 17),
                rangeLength = 7,
                text = "payload"
            )
        )
        val names = service.documentSymbols("workspace/src/app/mid_fn.lua").map { it.name }
        assertTrue("payload" in names)
        assertFalse("message" in names)
    }

    @Test
    fun range_edit_mid_string_keeps_valid_document() {
        val h = harness()
        val uri = "file:///workspace/src/app/mid_string.lua"
        val source = """
            local title = "Hello World"
            return title
        """.trimIndent()
        h.textDocuments.didOpen(openParams(uri, source, version = 1))
        h.published.clear()
        h.textDocuments.didChange(
            rangeChange(
                uri = uri,
                version = 2,
                start = Position(0, 21),
                end = Position(0, 26),
                rangeLength = 5,
                text = "Lua"
            )
        )
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
        assertTrue(
            h.languageService.documentSymbols("workspace/src/app/mid_string.lua").any { it.name == "title" }
        )
    }

    @Test
    fun range_edit_mid_comment_does_not_invent_parse_errors() {
        val h = harness()
        val uri = "file:///workspace/src/app/mid_comment.lua"
        val source = """
            -- TODO: wire feature flag
            local flag = true
            return flag
        """.trimIndent()
        h.textDocuments.didOpen(openParams(uri, source, version = 1))
        h.published.clear()
        h.textDocuments.didChange(
            rangeChange(
                uri = uri,
                version = 2,
                start = Position(0, 10),
                end = Position(0, 14),
                rangeLength = 4,
                text = "ship"
            )
        )
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
    }

    @Test
    fun range_edit_breaks_string_then_repair_clears_diagnostics() {
        val h = harness()
        val uri = "file:///workspace/src/app/string_break.lua"
        val source = "local s = \"ok\"\nreturn s"
        h.textDocuments.didOpen(openParams(uri, source, version = 1))
        h.textDocuments.didChange(fullChange(uri, 2, "local s = \"ok\nreturn s"))
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 3, "local s = \"ok\"\nreturn s"))
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
    }

    @Test
    fun insert_lines_mid_function_body_keeps_function_symbol() {
        val service = plainService()
        val uri = "file:///workspace/src/app/insert_body.lua"
        val source = """
            local function tick(state)
                return state
            end
            return tick
        """.trimIndent()
        service.didOpen(openParams(uri, source, version = 1))
        service.didChange(
            fullChange(
                uri,
                2,
                """
                local function tick(state)
                    local next = state + 1
                    return next
                end
                return tick
                """.trimIndent()
            )
        )
        val names = service.documentSymbols("workspace/src/app/insert_body.lua").map { it.name }
        assertTrue("tick" in names)
        assertTrue("next" in names)
    }

    @Test
    fun delete_function_body_to_empty_then_restore() {
        val h = harness()
        val uri = "file:///workspace/src/app/delete_body.lua"
        h.textDocuments.didOpen(openParams(uri, MODULE_MAIN, version = 1))
        h.textDocuments.didChange(fullChange(uri, 2, "return 1"))
        assertTrue(
            h.languageService.documentSymbols("workspace/src/app/delete_body.lua")
                .none { it.name == "start" }
        )
        h.textDocuments.didChange(fullChange(uri, 3, MODULE_MAIN))
        assertTrue(
            h.languageService.documentSymbols("workspace/src/app/delete_body.lua")
                .any { it.name == "start" || it.name == "App" || it.name == "M" || it.name == "boot" }
        )
    }

    @Test
    fun mid_table_constructor_edit_keeps_table_binding() {
        val service = plainService()
        val uri = "file:///workspace/src/app/table_edit.lua"
        val source = """
            local cfg = {
                host = "localhost",
                port = 8080
            }
            return cfg
        """.trimIndent()
        service.didOpen(openParams(uri, source, version = 1))
        service.didChange(
            fullChange(
                uri,
                2,
                """
                local cfg = {
                    host = "127.0.0.1",
                    port = 9090,
                    debug = true
                }
                return cfg
                """.trimIndent()
            )
        )
        assertTrue(service.documentSymbols("workspace/src/app/table_edit.lua").any { it.name == "cfg" })
    }

    @Test
    fun incremental_insert_at_eof_extends_module() {
        val service = plainService()
        val uri = "file:///workspace/src/app/eof.lua"
        service.didOpen(openParams(uri, "local M = {}\nreturn M", version = 1))
        service.didChange(
            fullChange(uri, 2, "local M = {}\nfunction M.run()\n  return 1\nend\nreturn M")
        )
        assertTrue(
            service.documentSymbols("workspace/src/app/eof.lua").any { it.name == "run" || it.name == "M" }
        )
    }

    @Test
    fun range_edit_inside_nested_if_block_stays_clean() {
        val h = harness()
        val uri = "file:///workspace/src/app/nested_if.lua"
        val source = """
            local function run(flag)
                if flag then
                    local value = 1
                    return value
                end
                return 0
            end
            return run
        """.trimIndent()
        h.textDocuments.didOpen(openParams(uri, source, version = 1))
        h.published.clear()
        h.textDocuments.didChange(
            fullChange(
                uri,
                2,
                """
                local function run(flag)
                    if flag then
                        local value = 42
                        return value
                    end
                    return 0
                end
                return run
                """.trimIndent()
            )
        )
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
        assertTrue(
            h.languageService.documentSymbols("workspace/src/app/nested_if.lua").any { it.name == "value" || it.name == "run" }
        )
    }

    // -------------------------------------------------------------------------
    // Rapid didChange bursts — last version wins
    // -------------------------------------------------------------------------

    @Test
    fun rapid_did_change_burst_last_version_wins_no_crash() {
        val h = harness()
        val uri = "file:///workspace/src/app/burst.lua"
        h.textDocuments.didOpen(openParams(uri, "local n = 0\nreturn n", version = 1))
        h.published.clear()
        for (v in 2..25) {
            h.textDocuments.didChange(fullChange(uri, v, "local n = $v\nreturn n"))
        }
        assertTrue(h.published.isNotEmpty())
        assertTrue(
            h.languageService.documentSymbols("workspace/src/app/burst.lua").any { it.name == "n" }
        )
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 10, "local stale = 1\nreturn stale"))
        assertTrue(h.published.isEmpty())
        assertFalse(
            h.languageService.documentSymbols("workspace/src/app/burst.lua").any { it.name == "stale" }
        )
    }

    @Test
    fun rapid_burst_with_invalid_then_valid_ends_clean() {
        val h = harness()
        val uri = "file:///workspace/src/app/burst_fix.lua"
        h.textDocuments.didOpen(openParams(uri, MODULE_MAIN, version = 1))
        h.published.clear()
        val steps = listOf(
            2 to "local =",
            3 to "local x = 1\nreturn x",
            4 to "local = 2",
            5 to MODULE_MAIN
        )
        steps.forEach { (v, text) -> h.textDocuments.didChange(fullChange(uri, v, text)) }
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
    }

    @Test
    fun concurrent_did_change_on_same_uri_does_not_crash_service() {
        val h = harness()
        val uri = "file:///workspace/src/app/concurrent_change.lua"
        h.textDocuments.didOpen(openParams(uri, "local n = 0\nreturn n", version = 1))
        val pool = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(1)
        val errors = AtomicInteger(0)
        val tasks = (2..20).map { version ->
            pool.submit {
                latch.await(5, TimeUnit.SECONDS)
                try {
                    h.textDocuments.didChange(fullChange(uri, version, "local n = $version\nreturn n"))
                } catch (_: Throwable) {
                    errors.incrementAndGet()
                }
            }
        }
        latch.countDown()
        tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdownNow()
        assertEquals(0, errors.get(), "didChange must not throw under concurrent bursts")
        assertTrue(
            h.languageService.documentSymbols("workspace/src/app/concurrent_change.lua")
                .any { it.name == "n" }
        )
    }

    @Test
    fun null_version_change_dual_path_applies_or_keeps_prior() {
        val h = harness()
        val uri = "file:///workspace/src/app/null_ver.lua"
        h.textDocuments.didOpen(openParams(uri, "local a = 1\nreturn a", version = 1))
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, version = null, text = "local b = 2\nreturn b"))
        val names = h.languageService.documentSymbols("workspace/src/app/null_ver.lua").map { it.name }
        assertTrue("a" in names || "b" in names)
    }

    // -------------------------------------------------------------------------
    // Concurrent open of many files
    // -------------------------------------------------------------------------

    @Test
    fun concurrent_open_of_many_project_files_isolates_diagnostics() {
        val h = harness()
        val count = 24
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(1)
        val errors = AtomicInteger(0)
        val futures = (0 until count).map { i ->
            pool.submit {
                latch.await(5, TimeUnit.SECONDS)
                try {
                    val uri = "file:///workspace/src/mod/m$i.lua"
                    val text = if (i % 7 == 0) "local =" else "local v$i = $i\nreturn v$i"
                    h.textDocuments.didOpen(openParams(uri, text, version = 1))
                } catch (_: Throwable) {
                    errors.incrementAndGet()
                }
            }
        }
        latch.countDown()
        futures.forEach { it.get(15, TimeUnit.SECONDS) }
        pool.shutdownNow()
        assertEquals(0, errors.get())
        val byUri = lastPublishedByUri(h.published)
        assertTrue(byUri.size >= count / 2)
        val cleanUri = "file:///workspace/src/mod/m1.lua"
        if (byUri.containsKey(cleanUri)) {
            assertTrue(byUri.getValue(cleanUri).diagnostics.isEmpty())
        }
        val dirtyUri = "file:///workspace/src/mod/m0.lua"
        if (byUri.containsKey(dirtyUri)) {
            assertTrue(byUri.getValue(dirtyUri).diagnostics.isNotEmpty())
        }
    }

    @Test
    fun open_many_nested_dirs_then_close_half_keeps_remaining_symbols() {
        val h = harness()
        val openUris = (0 until 12).map { i ->
            val uri = "file:///workspace/src/feature/pack$i/mod.lua"
            h.textDocuments.didOpen(openParams(uri, "local pack$i = $i\nreturn pack$i", version = 1))
            uri
        }
        openUris.take(6).forEach { h.textDocuments.didClose(closeParams(it)) }
        assertTrue(
            h.languageService.documentSymbols("workspace/src/feature/pack11/mod.lua")
                .any { it.name == "pack11" }
        )
        assertTrue(
            h.languageService.documentSymbols("workspace/src/feature/pack0/mod.lua").isEmpty()
        )
    }

    @Test
    fun open_two_language_ids_same_workspace_do_not_collide() {
        val h = harness()
        h.textDocuments.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file:///workspace/src/a.lua", "lua", 1, "local a = 1\nreturn a")
            )
        )
        h.textDocuments.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file:///workspace/src/b.aly", "aly", 1, "local b = 2\nreturn b")
            )
        )
        assertTrue(h.languageService.documentSymbols("workspace/src/a.lua").any { it.name == "a" })
        assertTrue(h.languageService.documentSymbols("workspace/src/b.aly").any { it.name == "b" })
    }

    // -------------------------------------------------------------------------
    // URI edges: spaces, nested dirs
    // -------------------------------------------------------------------------

    @Test
    fun file_uri_with_spaces_in_path_opens_and_publishes() {
        val h = harness()
        val uri = "file:///workspace/my%20project/hello%20world.lua"
        h.textDocuments.didOpen(openParams(uri, "local hello = 1\nreturn hello", version = 1))
        assertEquals(uri, h.published.last().uri)
        assertTrue(h.published.last().diagnostics.isEmpty())
    }

    @Test
    fun file_uri_with_spaces_change_and_close_preserve_uri() {
        val h = harness()
        val uri = "file:///workspace/src/ui/My Screen.lua"
        h.textDocuments.didOpen(openParams(uri, "local screen = {}\nreturn screen", version = 1))
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 2, "local screen = { id = 1 }\nreturn screen"))
        assertEquals(listOf(uri), h.published.map { it.uri })
        h.published.clear()
        h.textDocuments.didClose(closeParams(uri))
        assertEquals(uri, h.published.single().uri)
        assertTrue(h.published.single().diagnostics.isEmpty())
    }

    @Test
    fun deeply_nested_project_uri_stays_queryable() {
        val h = harness()
        val uri = "file:///workspace/app/src/main/lua/com/example/feature/controller.lua"
        h.textDocuments.didOpen(openParams(uri, MODULE_CONTROLLER, version = 1))
        val symbols = h.languageService
            .documentSymbols("workspace/app/src/main/lua/com/example/feature/controller.lua")
            .map { it.name }
        assertTrue(symbols.any { it == "Controller" || it == "handle" || it == "M" || it == "create" })
    }

    @Test
    fun sibling_nested_dirs_do_not_cross_publish_on_change() {
        val h = harness()
        val a = "file:///workspace/src/a/mod.lua"
        val b = "file:///workspace/src/b/mod.lua"
        h.textDocuments.didOpen(openParams(a, "local a = 1\nreturn a", version = 1))
        h.textDocuments.didOpen(openParams(b, "local b = 2\nreturn b", version = 1))
        h.published.clear()
        h.textDocuments.didChange(fullChange(a, 2, "local ="))
        assertEquals(listOf(a), h.published.map { it.uri })
        assertTrue(h.languageService.diagnostics("workspace/src/b/mod.lua").diagnostics.isEmpty())
    }

    // -------------------------------------------------------------------------
    // CRLF vs LF
    // -------------------------------------------------------------------------

    @Test
    fun crlf_source_opens_clean_and_exposes_symbols() {
        val h = harness()
        val uri = "file:///workspace/src/app/crlf.lua"
        val source = "local value = 1\r\nlocal function render()\r\n    return value\r\nend\r\nreturn render\r\n"
        h.textDocuments.didOpen(openParams(uri, source, version = 1))
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
        val names = h.languageService.documentSymbols("workspace/src/app/crlf.lua").map { it.name }
        assertTrue("value" in names)
        assertTrue("render" in names)
    }

    @Test
    fun lf_to_crlf_full_replace_keeps_clean_diagnostics() {
        val h = harness()
        val uri = "file:///workspace/src/app/line_endings.lua"
        h.textDocuments.didOpen(openParams(uri, "local x = 1\nreturn x", version = 1))
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 2, "local x = 1\r\nreturn x\r\n"))
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
    }

    @Test
    fun range_edit_on_crlf_buffer_mid_line_is_safe() {
        val service = plainService()
        val uri = "file:///workspace/src/app/crlf_range.lua"
        val source = "local first = 1\r\nlocal second = 2\r\nreturn first + second\r\n"
        service.didOpen(openParams(uri, source, version = 1))
        service.didChange(
            rangeChange(
                uri = uri,
                version = 2,
                start = Position(1, 6),
                end = Position(1, 12),
                rangeLength = 6,
                text = "renamed"
            )
        )
        val names = service.documentSymbols("workspace/src/app/crlf_range.lua").map { it.name }
        assertTrue(names.isNotEmpty())
        assertTrue("first" in names || "renamed" in names || "second" in names)
    }

    // -------------------------------------------------------------------------
    // Large file soft performance smoke
    // -------------------------------------------------------------------------

    @Test
    fun large_file_500_lines_open_completes_with_symbols() {
        val h = harness()
        val uri = "file:///workspace/src/app/large.lua"
        val source = buildLargeModule(lineCount = 500)
        val started = System.nanoTime()
        h.textDocuments.didOpen(openParams(uri, source, version = 1))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(elapsedMs < 30_000, "500-line open should finish in test process; took ${elapsedMs}ms")
        assertTrue(lastFor(h.published, uri).diagnostics.isEmpty())
        assertTrue(
            h.languageService.documentSymbols("workspace/src/app/large.lua")
                .any { it.name.startsWith("fn_") || it.name == "M" }
        )
    }

    @Test
    fun large_file_change_last_function_completes() {
        val h = harness()
        val uri = "file:///workspace/src/app/large_change.lua"
        val source = buildLargeModule(lineCount = 500)
        h.textDocuments.didOpen(openParams(uri, source, version = 1))
        h.published.clear()
        val started = System.nanoTime()
        h.textDocuments.didChange(fullChange(uri, 2, source + "\nlocal extra = true\nreturn extra\n"))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(elapsedMs < 30_000, "500-line change should finish; took ${elapsedMs}ms")
        assertEquals(listOf(uri), h.published.map { it.uri })
    }

    // -------------------------------------------------------------------------
    // Soft shutdown / exit
    // -------------------------------------------------------------------------

    @Test
    fun soft_shutdown_after_editor_session_returns_zero() {
        val server = LuaLanguageServer()
        server.initialize(InitializeParams()).get()
        server.textDocumentService.didOpen(openParams("file:///workspace/src/app/soft_shutdown.lua", MODULE_MAIN))
        assertEquals(0, server.shutdown().get(5, TimeUnit.SECONDS))
    }

    @Test
    fun soft_exit_after_shutdown_ignores_further_did_open() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        server.shutdown().get()
        server.exit()
        client.published.clear()
        server.textDocumentService.didOpen(openParams("file:///workspace/src/app/after_exit.lua", "local ="))
        assertTrue(client.published.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Folding / selection / signature soft probes
    // -------------------------------------------------------------------------

    @Test
    fun folding_ranges_on_large_realish_module_are_well_formed_or_empty() {
        val service = plainService()
        val uri = "file:///workspace/src/app/fold.lua"
        service.didOpen(openParams(uri, MODULE_MAIN + "\n" + MODULE_CONTROLLER, version = 1))
        val ranges = service.foldingRanges(FoldingRangeRequestParams(TextDocumentIdentifier(uri)))
        ranges.forEach { range ->
            assertTrue(range.startLine >= 0)
            assertTrue(range.endLine >= range.startLine)
        }
    }

    @Test
    fun selection_range_nested_blocks_return_chain_or_null_slots() {
        val service = plainService()
        val uri = "file:///workspace/src/app/select.lua"
        val source = MODULE_MAIN
        service.didOpen(openParams(uri, source, version = 1))
        val pos = positionOf(source, "start", occurrence = 1)
        val ranges = service.selectionRanges(
            SelectionRangeParams(TextDocumentIdentifier(uri), listOf(pos))
        )
        assertEquals(1, ranges.size)
        val first = ranges[0]
        if (first != null) {
            assertTrue(first.range.start.line >= 0)
        }
    }

    @Test
    fun signature_help_active_parameter_while_typing_args_on_project_fn() {
        val service = plainService()
        val uri = "file:///workspace/src/app/sig.lua"
        val source = """
            ---@param name string
            ---@param count number
            local function greet(name, count)
                return name
            end
            local msg = greet("hi", 2)
            return msg
        """.trimIndent()
        service.didOpen(openParams(uri, source, version = 1))
        val first = service.signatureHelp(
            SignatureHelpParams(TextDocumentIdentifier(uri), positionOf(source, "\"hi\","))
        )
        val second = service.signatureHelp(
            SignatureHelpParams(TextDocumentIdentifier(uri), positionOf(source, "2)"))
        )
        if (first != null) {
            assertEquals(0, first.activeParameter)
            assertTrue(first.signatures.isNotEmpty())
        }
        if (second != null) {
            assertEquals(1, second.activeParameter)
        }
    }

    @Test
    fun open_temp_disk_project_files_via_workspace_folder_index() {
        val root = tempWorkspace("disk-open")
        val main = root.resolve("src/app/main.lua")
        main.parent.createDirectories()
        main.writeText(MODULE_MAIN)
        val service = initializedService(root)
        val uri = main.toUri().toString()
        val published = mutableListOf<PublishDiagnosticsParams>()
        val docs = LuaTextDocumentService(service, publishDiagnostics = { published += it })
        docs.didOpen(openParams(uri, MODULE_MAIN, version = 1))
        assertTrue(published.isNotEmpty())
        assertTrue(published.last().diagnostics.isEmpty())
    }

    @Test
    fun did_open_aly_language_id_still_accepts_lua_source() {
        val h = harness()
        val uri = "file:///workspace/src/app/screen.aly"
        h.textDocuments.didOpen(
            DidOpenTextDocumentParams(TextDocumentItem(uri, "aly", 1, "local ui = {}\nreturn ui"))
        )
        assertEquals(uri, h.published.last().uri)
    }

    @Test
    fun empty_document_open_is_clean_or_soft_diagnostic() {
        val h = harness()
        val uri = "file:///workspace/src/app/empty.lua"
        h.textDocuments.didOpen(openParams(uri, "", version = 1))
        assertTrue(
            h.published.last().diagnostics.isEmpty() ||
                h.published.last().diagnostics.all { it.severity != null }
        )
    }

    @Test
    fun whitespace_only_document_open_is_safe() {
        val h = harness()
        val uri = "file:///workspace/src/app/ws.lua"
        h.textDocuments.didOpen(openParams(uri, "   \n\t\n", version = 1))
        assertEquals(uri, h.published.last().uri)
    }

    @Test
    fun did_change_full_sync_from_valid_to_valid_keeps_publish_uri_stable() {
        val h = harness()
        val uri = "file:///workspace/src/app/stable_uri.lua"
        h.textDocuments.didOpen(openParams(uri, MODULE_MAIN, version = 1))
        h.published.clear()
        h.textDocuments.didChange(fullChange(uri, 2, MODULE_CONTROLLER))
        assertEquals(listOf(uri), h.published.map { it.uri })
        assertTrue(h.published.single().diagnostics.isEmpty())
    }

    @Test
    fun server_connected_client_receives_open_change_close_lifecycle() {
        val server = LuaLanguageServer()
        val client = RecordingLanguageClient()
        server.connect(client.asClient())
        server.initialize(InitializeParams()).get()
        val uri = "file:///workspace/src/app/server_life.lua"
        server.textDocumentService.didOpen(openParams(uri, MODULE_MAIN, version = 1))
        server.textDocumentService.didChange(fullChange(uri, 2, "local ="))
        server.textDocumentService.didClose(closeParams(uri))
        assertTrue(client.published.size >= 3)
        assertEquals(uri, client.published.first().uri)
        assertEquals(uri, client.published.last().uri)
        assertTrue(client.published.last().diagnostics.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun harness(): Harness {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            languageService = languageService,
            publishDiagnostics = { published += it }
        )
        return Harness(languageService, textDocuments, published)
    }

    private fun plainService(): LuaLanguageService {
        return LuaLanguageService().also { it.initialize(InitializeParams()) }
    }

    private fun initializedService(root: Path): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(
                        WorkspaceFolder(root.toUri().toString(), root.fileName.toString())
                    )
                }
            )
        }
    }

    private fun tempWorkspace(label: String): Path {
        return Files.createTempDirectory("lua-parser-lsp-real-editor-$label-")
    }

    private data class Harness(
        val languageService: LuaLanguageService,
        val textDocuments: LuaTextDocumentService,
        val published: MutableList<PublishDiagnosticsParams>
    )

    private fun openParams(uri: String, text: String, version: Int = 1): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", version, text))
    }

    private fun fullChange(uri: String, version: Int?, text: String): DidChangeTextDocumentParams {
        return DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, version),
            listOf(TextDocumentContentChangeEvent(text))
        )
    }

    private fun rangeChange(
        uri: String,
        version: Int,
        start: Position,
        end: Position,
        rangeLength: Int,
        text: String
    ): DidChangeTextDocumentParams {
        return DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, version),
            listOf(TextDocumentContentChangeEvent(Range(start, end), rangeLength, text))
        )
    }

    private fun closeParams(uri: String): DidCloseTextDocumentParams {
        return DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
    }

    private fun lastFor(published: List<PublishDiagnosticsParams>, uri: String): PublishDiagnosticsParams {
        val matching = published.filter { it.uri == uri }
        assertTrue(matching.isNotEmpty(), "expected publish for $uri")
        return matching.last()
    }

    private fun lastPublishedByUri(
        published: List<PublishDiagnosticsParams>
    ): Map<String, PublishDiagnosticsParams> {
        val result = linkedMapOf<String, PublishDiagnosticsParams>()
        published.forEach { result[it.uri] = it }
        return result
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var from = 0
        var found = 0
        while (from <= source.length) {
            val index = source.indexOf(needle, from)
            require(index >= 0) { "needle '$needle' not found (occurrence=$occurrence)" }
            found++
            if (found == occurrence) {
                var line = 0
                var lastBreak = -1
                for (i in 0 until index) {
                    if (source[i] == '\n') {
                        line++
                        lastBreak = i
                    }
                }
                return Position(line, index - (lastBreak + 1))
            }
            from = index + needle.length
        }
        error("unreachable")
    }

    private fun buildLargeModule(lineCount: Int): String {
        val sb = StringBuilder()
        sb.append("local M = {}\n")
        var lines = 1
        var i = 0
        while (lines < lineCount - 2) {
            sb.append("function M.fn_").append(i).append("()\n")
            sb.append("  local x").append(i).append(" = ").append(i).append("\n")
            sb.append("  return x").append(i).append("\n")
            sb.append("end\n")
            lines += 4
            i++
        }
        sb.append("return M\n")
        return sb.toString()
    }

    private class RecordingLanguageClient : InvocationHandler {
        val published = mutableListOf<PublishDiagnosticsParams>()

        fun asClient(): LanguageClient {
            return Proxy.newProxyInstance(
                LanguageClient::class.java.classLoader,
                arrayOf(LanguageClient::class.java),
                this
            ) as LanguageClient
        }

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? {
            if (method.name == "publishDiagnostics") {
                published += args?.single() as PublishDiagnosticsParams
                return null
            }
            if (method.declaringClass == Object::class.java) {
                return when (method.name) {
                    "toString" -> "RecordingLanguageClient"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            return when (method.returnType) {
                java.lang.Void.TYPE -> null
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                CompletableFuture::class.java -> CompletableFuture.completedFuture<Any?>(null)
                else -> null
            }
        }
    }

    companion object {
        private val MODULE_MAIN = """
            local M = {}
            local App = {}

            function App.start(config)
                local name = config and config.name or "app"
                return name
            end

            function M.boot()
                return App.start({ name = "demo" })
            end

            return M
        """.trimIndent()

        private val MODULE_CONTROLLER = """
            local M = {}
            local Controller = {}

            function Controller.handle(req)
                local path = req and req.path or "/"
                return { ok = true, path = path }
            end

            function M.create()
                return Controller
            end

            return M
        """.trimIndent()
    }
}
