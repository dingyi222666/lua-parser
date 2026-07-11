package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TDD coverage for TASK-158: LSP open/change/close use engine.update deltas;
 * configuration/metadata changes keep a full rebuild fallback.
 *
 * Call counts use test-visible counters on [LuaLanguageService] because
 * JvmWorkspaceEngine is final.
 */
class LspIncrementalWorkspaceUpdateTddTest {
    @Test
    fun open_change_close_use_engine_update_after_initial_build() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())

        assertEquals(1, service.fullRebuildCount, "initialize should full-build once")
        assertEquals(0, service.incrementalUpdateCount)

        val uri = "file:///workspace/incremental-main.lua"
        val openSource =
            "---@param value number\nlocal function render(value)\n    return value\nend\nlocal current = render(1)\nreturn current"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(uri, "lua", 1, openSource)
            )
        )

        assertEquals(1, service.fullRebuildCount, "didOpen must not full-rebuild")
        assertEquals(1, service.incrementalUpdateCount, "didOpen should apply an incremental update")

        val changedSource =
            "---@param value number\nlocal function render(value)\n    return value\nend\nlocal current = render(2)\nreturn current"
        service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent(changedSource))
            )
        )

        assertEquals(1, service.fullRebuildCount)
        assertEquals(2, service.incrementalUpdateCount)

        service.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier(uri))
        )

        assertEquals(1, service.fullRebuildCount, "didClose must not full-rebuild")
        assertEquals(3, service.incrementalUpdateCount, "didClose should apply an incremental update")

        assertTrue(service.workspaceSymbols("render").isEmpty())
    }

    @Test
    fun sequence_of_edits_keeps_diagnostics_completion_hover_symbols_and_require_resolution() {
        val root = Files.createTempDirectory("lua-parser-lsp-incremental-")
        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.value = 1\nreturn M")
        val main = root.resolve("main.lua").writeLua(
            "local dep = require(\"dep\")\nlocal copy = dep.value\nreturn copy"
        )

        val service = LuaLanguageService()
        service.initialize(
            InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), root.fileName.toString()))
            }
        )

        assertEquals(1, service.fullRebuildCount)
        val updatesAfterInit = service.incrementalUpdateCount

        val openMain =
            "local dep = require(\"dep\")\nlocal renamed = dep.value\nreturn renamed"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(main.uri, "lua", 1, openMain)
            )
        )
        assertEquals(updatesAfterInit + 1, service.incrementalUpdateCount)
        assertEquals(1, service.fullRebuildCount)

        val definitions = service.definition(
            DefinitionParams(TextDocumentIdentifier(main.uri), main.positionOf("value"))
        )
        assertTrue(definitions.isNotEmpty(), "expected definition after incremental open")
        assertEquals(dep.uri, definitions.single().uri)

        val completionOffset = openMain.indexOf("dep.") + "dep.".length
        val completionPos = positionAt(openMain, completionOffset)
        val completions = service.completion(main.uri, completionPos.line, completionPos.character)
        assertTrue(completions.items.any { it.label == "value" }, "expected member completion for dep.value")

        val hover = service.hover(
            HoverParams(TextDocumentIdentifier(main.uri), positionAt(openMain, openMain.indexOf("renamed")))
        )
        assertNotNull(hover)

        assertTrue(service.workspaceSymbols("value").any { it.location.uri == dep.uri })

        val changedMain =
            "local dep = require(\"dep\")\nlocal afterEdit = dep.value\nreturn afterEdit"
        service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(main.uri, 2),
                listOf(TextDocumentContentChangeEvent(changedMain))
            )
        )
        assertEquals(1, service.fullRebuildCount)
        assertEquals(updatesAfterInit + 2, service.incrementalUpdateCount)

        assertTrue(service.workspaceSymbols("afterEdit").any { it.location.uri == main.uri })
        assertTrue(service.documentSymbols(main.uri).any { it.name == "afterEdit" })

        val brokenMain = "local dep = require(\"dep\")\nlocal ="
        service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(main.uri, 3),
                listOf(TextDocumentContentChangeEvent(brokenMain))
            )
        )
        val diagnostics = service.diagnosticsForUri(main.uri)
        assertTrue(diagnostics.diagnostics.isNotEmpty(), "expected parse diagnostics after broken edit")
        assertEquals(1, service.fullRebuildCount)

        service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(main.uri)))
        assertEquals(1, service.fullRebuildCount)
        assertTrue(service.incrementalUpdateCount >= updatesAfterInit + 4)

        val definitionsAfterClose = service.definition(
            DefinitionParams(TextDocumentIdentifier(main.uri), main.positionOf("value"))
        )
        assertEquals(dep.uri, definitionsAfterClose.single().uri)
        assertTrue(service.workspaceSymbols("copy").any { it.location.uri == main.uri })
    }

    @Test
    fun metadata_configuration_uses_full_rebuild_fallback() {
        val service = LuaLanguageService()
        service.initialize(InitializeParams())
        assertEquals(1, service.fullRebuildCount)

        val uri = "file:///workspace/meta-main.lua"
        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    uri,
                    "lua",
                    1,
                    "local Locale = require(\"Locale\")\nreturn Locale"
                )
            )
        )
        assertEquals(1, service.fullRebuildCount)
        assertEquals(1, service.incrementalUpdateCount)

        service.setWorkspaceMetadata(
            mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Locale")
        )

        assertEquals(2, service.fullRebuildCount, "metadata invalidation must full-rebuild")
        assertEquals(1, service.incrementalUpdateCount, "metadata path must not use update deltas")

        val symbols = service.workspaceSymbols("Locale")
        assertTrue(
            symbols.isNotEmpty() || service.completion(uri, 0, 20).items.isNotEmpty(),
            "expected Locale-related symbols or completions after metadata rebuild"
        )
    }

    private fun Path.writeLua(source: String): WorkspaceFile {
        parent?.createDirectories()
        writeText(source)
        return WorkspaceFile(this)
    }

    private class WorkspaceFile(val path: Path) {
        val uri: String = path.toUri().toString()
        private val source: String = Files.readString(path)

        fun positionOf(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1)
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find occurrence $occurrence of '$needle' in $path" }
                fromIndex = index + needle.length
            }
            return positionAt(source, index)
        }
    }

    private companion object {
        fun positionAt(source: String, offset: Int): Position {
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
}
