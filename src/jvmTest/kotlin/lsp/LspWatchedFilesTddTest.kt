package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDD coverage for applying workspace/didChangeWatchedFiles create/change/delete events
 * into the LSP workspace snapshot (TASK-157).
 */
class LspWatchedFilesTddTest {
    @Test
    fun watched_create_indexes_new_lua_file_for_symbols_and_require_resolution() {
        val root = tempWorkspace()
        // Align field name used by require resolution with the symbol exported by dep.lua.
        val main = root.resolve("main.lua").writeLua("local dep = require(\"dep\")\nreturn dep.createdMarker")
        val service = initializedService(root)
        val workspace = LuaWorkspaceService(service)

        assertTrue(service.workspaceSymbols("createdMarker").none { it.location.uri == root.resolve("dep.lua").uri })

        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.createdMarker = 1\nreturn M")
        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(
                listOf(FileEvent(dep.uri, FileChangeType.Created))
            )
        )

        val symbols = service.workspaceSymbols("createdMarker")
        val definitions = service.definition(definitionParams(main, "createdMarker"))

        assertTrue(symbols.any { it.name == "createdMarker" && it.location.uri == dep.uri })
        assertTrue(definitions.isNotEmpty(), "expected definition for dep.createdMarker after watched create")
        assertEquals(dep.uri, definitions.single().uri)
        assertEquals(1, workspace.lastWatchedFileChanges().size)
        assertEquals(FileChangeType.Created, workspace.lastWatchedFileChanges().single().type)
    }

    @Test
    fun watched_change_updates_indexed_source_used_by_symbols_and_references() {
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.oldName = 1\nreturn M")
        val main = root.resolve("main.lua").writeLua("local dep = require(\"dep\")\nlocal copy = dep.oldName\nreturn copy")
        val service = initializedService(root)
        val workspace = LuaWorkspaceService(service)

        assertTrue(service.workspaceSymbols("oldName").any { it.location.uri == dep.uri })

        dep.path.writeText("local M = {}\nM.newName = 2\nreturn M")
        // Keep WorkspaceFile position helpers in sync with disk after external rewrite.
        dep.refreshSourceFromDisk()
        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(
                listOf(FileEvent(dep.uri, FileChangeType.Changed))
            )
        )

        assertTrue(service.workspaceSymbols("oldName").none { it.location.uri == dep.uri })
        assertTrue(service.workspaceSymbols("newName").any { it.name == "newName" && it.location.uri == dep.uri })

        val references = service.references(referenceParams(dep, "newName"))
        assertTrue(references.any { it.uri == dep.uri })
    }

    @Test
    fun watched_delete_removes_file_from_symbols_diagnostics_references_and_require_resolution() {
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.value = 1\nreturn M")
        val main = root.resolve("main.lua").writeLua("local dep = require(\"dep\")\nlocal copy = dep.value\nreturn copy")
        val service = initializedService(root)
        val workspace = LuaWorkspaceService(service)

        assertTrue(service.workspaceSymbols("value").any { it.location.uri == dep.uri })
        assertTrue(service.diagnostics("dep.lua").diagnostics.isEmpty())
        assertEquals(dep.uri, service.definition(definitionParams(main, "value")).single().uri)

        dep.path.deleteExisting()
        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(
                listOf(FileEvent(dep.uri, FileChangeType.Deleted))
            )
        )

        assertTrue(service.workspaceSymbols("value").none { it.location.uri == dep.uri })
        assertTrue(service.workspaceSymbols("value").none { it.location.uri.contains("dep.lua") })

        val deletedDiagnostics = service.diagnostics("dep.lua")
        assertEquals(dep.uri, deletedDiagnostics.uri)
        // Deleted indexed files should no longer contribute parse/semantic diagnostics from disk.
        assertTrue(deletedDiagnostics.diagnostics.isEmpty())

        val definitionsAfterDelete = service.definition(definitionParams(main, "value"))
        assertTrue(definitionsAfterDelete.none { it.uri == dep.uri })

        val referencesAfterDelete = service.references(referenceParams(main, "value"))
        assertTrue(referencesAfterDelete.none { it.uri == dep.uri })
    }

    @Test
    fun watched_delete_of_aly_file_removes_it_from_workspace_symbols() {
        val root = tempWorkspace()
        val layout = root.resolve("screen.aly").writeLua("local layoutMarker = true\nreturn layoutMarker")
        val service = initializedService(root)
        val workspace = LuaWorkspaceService(service)

        assertTrue(service.workspaceSymbols("layoutMarker").any { it.location.uri == layout.uri })

        layout.path.deleteExisting()
        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(
                listOf(FileEvent(layout.uri, FileChangeType.Deleted))
            )
        )

        assertTrue(service.workspaceSymbols("layoutMarker").none { it.location.uri == layout.uri })
    }

    @Test
    fun watched_create_indexes_aly_files() {
        val root = tempWorkspace()
        val service = initializedService(root)
        val workspace = LuaWorkspaceService(service)

        val layout = root.resolve("form.aly").writeLua("local formMarker = 42\nreturn formMarker")
        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(
                listOf(FileEvent(layout.uri, FileChangeType.Created))
            )
        )

        assertTrue(service.workspaceSymbols("formMarker").any { it.name == "formMarker" && it.location.uri == layout.uri })
    }

    @Test
    fun watched_change_does_not_overwrite_open_document_overlay() {
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.diskName = 1\nreturn M")
        val service = initializedService(root)
        val workspace = LuaWorkspaceService(service)

        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(dep.uri, "lua", 1, "local M = {}\nM.openOnly = 9\nreturn M")
            )
        )

        dep.path.writeText("local M = {}\nM.diskAfterChange = 2\nreturn M")
        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(
                listOf(FileEvent(dep.uri, FileChangeType.Changed))
            )
        )

        assertTrue(service.workspaceSymbols("openOnly").any { it.location.uri == dep.uri })
        assertTrue(service.workspaceSymbols("diskAfterChange").none { it.location.uri == dep.uri })
        assertTrue(service.workspaceSymbols("diskName").none { it.location.uri == dep.uri })
    }

    @Test
    fun watched_file_updates_republish_open_document_diagnostics_deterministically() {
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.value = 1\nreturn M")
        val main = root.resolve("main.lua").writeLua("local dep = require(\"dep\")\nreturn dep.value")
        val service = initializedService(root)

        val published = mutableListOf<PublishDiagnosticsParams>()
        val textDocuments = LuaTextDocumentService(
            service,
            publishDiagnostics = { diagnostics -> published += diagnostics }
        )
        val workspace = LuaWorkspaceService(
            languageService = service,
            onConfigurationChanged = textDocuments::republishDiagnostics
        )

        textDocuments.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(main.uri, "lua", 1, "local dep = require(\"dep\")\nreturn dep.value")
            )
        )
        published.clear()

        dep.path.deleteExisting()
        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(
                listOf(FileEvent(dep.uri, FileChangeType.Deleted))
            )
        )

        assertEquals(listOf(main.uri), published.map { it.uri })
        // Republish is deterministic: one params object per open document in open order.
        assertEquals(1, published.size)
    }

    @Test
    fun watched_non_lua_files_are_ignored() {
        val root = tempWorkspace()
        val service = initializedService(root)
        val workspace = LuaWorkspaceService(service)

        val readme = root.resolve("README.md")
        readme.writeText("# notes")
        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(
                listOf(FileEvent(readme.toUri().toString(), FileChangeType.Created))
            )
        )

        assertTrue(service.workspaceSymbols("notes").none { it.location.uri == readme.toUri().toString() })
        assertEquals(1, workspace.lastWatchedFileChanges().size)
    }

    @Test
    fun multiple_watched_events_are_applied_in_batch() {
        val root = tempWorkspace()
        val keep = root.resolve("keep.lua").writeLua("local keepMarker = true\nreturn keepMarker")
        val drop = root.resolve("drop.lua").writeLua("local dropMarker = true\nreturn dropMarker")
        val service = initializedService(root)
        val workspace = LuaWorkspaceService(service)

        val created = root.resolve("created.lua").writeLua("local createdMarker = true\nreturn createdMarker")
        drop.path.deleteExisting()
        keep.path.writeText("local keepUpdated = true\nreturn keepUpdated")

        workspace.didChangeWatchedFiles(
            DidChangeWatchedFilesParams(
                listOf(
                    FileEvent(created.uri, FileChangeType.Created),
                    FileEvent(drop.uri, FileChangeType.Deleted),
                    FileEvent(keep.uri, FileChangeType.Changed)
                )
            )
        )

        assertTrue(service.workspaceSymbols("createdMarker").any { it.location.uri == created.uri })
        assertTrue(service.workspaceSymbols("dropMarker").none { it.location.uri == drop.uri })
        assertTrue(service.workspaceSymbols("keepUpdated").any { it.location.uri == keep.uri })
        assertTrue(service.workspaceSymbols("keepMarker").none { it.location.uri == keep.uri })
    }

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
        return Files.createTempDirectory("lua-parser-lsp-watched-")
    }

    private fun Path.writeLua(source: String): WorkspaceFile {
        parent?.createDirectories()
        writeText(source)
        return WorkspaceFile(this)
    }

    private fun definitionParams(file: WorkspaceFile, needle: String, occurrence: Int = 1): DefinitionParams {
        return DefinitionParams(TextDocumentIdentifier(file.uri), file.positionOf(needle, occurrence))
    }

    private fun referenceParams(file: WorkspaceFile, needle: String, occurrence: Int = 1): ReferenceParams {
        return ReferenceParams(TextDocumentIdentifier(file.uri), file.positionOf(needle, occurrence), ReferenceContext(true))
    }

    /**
     * Test helper for on-disk workspace files. Source is re-read for position lookup so
     * external rewrites (watched change scenarios) stay aligned with disk content.
     */
    private class WorkspaceFile(val path: Path) {
        val uri: String = path.toUri().toString()
        private var source: String = Files.readString(path)

        fun refreshSourceFromDisk() {
            source = Files.readString(path)
        }

        fun positionOf(needle: String, occurrence: Int = 1): Position {
            // Always prefer current disk content when the file still exists so watched
            // change tests do not use a stale snapshot for needle lookup.
            if (Files.isRegularFile(path)) {
                source = Files.readString(path)
            }
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

        private fun positionAt(offset: Int): Position {
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

    private val Path.uri: String
        get() = toUri().toString()
}
