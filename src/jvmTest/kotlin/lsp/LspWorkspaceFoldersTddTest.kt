package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
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
import kotlin.test.assertTrue

class LspWorkspaceFoldersTddTest {
    @Test
    fun initialization_indexes_lua_and_aly_files_under_workspace_folders() {
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.value = 1\nreturn M")
        val layout = root.resolve("screen.aly").writeLua("local layoutMarker = true\nreturn layoutMarker")

        val service = initializedService(root)

        val depSymbols = service.workspaceSymbols("value")
        val layoutSymbols = service.workspaceSymbols("layoutMarker")

        assertTrue(depSymbols.any { it.name == "value" && it.location.uri == dep.uri })
        assertTrue(layoutSymbols.any { it.name == "layoutMarker" && it.location.uri == layout.uri })
    }

    @Test
    fun unopened_indexed_modules_are_visible_to_require_resolution_and_references() {
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.value = 1\nreturn M")
        val main = root.resolve("main.lua").writeLua("local dep = require(\"dep\")\nlocal copy = dep.value\nreturn copy")
        val service = initializedService(root)

        val definitions = service.definition(definitionParams(main, "value"))
        val references = service.references(referenceParams(dep, "value"))

        assertEquals(dep.uri, definitions.single().uri)
        assertTrue(references.any { it.uri == dep.uri })
        assertTrue(references.any { it.uri == main.uri })
    }

    @Test
    fun diagnostics_can_be_requested_for_unopened_indexed_workspace_files() {
        val root = tempWorkspace()
        val broken = root.resolve("broken.lua").writeLua("local =")
        val service = initializedService(root)

        val diagnostics = service.diagnostics("broken.lua")

        assertEquals(broken.uri, diagnostics.uri)
        assertTrue(diagnostics.diagnostics.isNotEmpty())
        assertEquals(DiagnosticSeverity.Error, diagnostics.diagnostics.first().severity)
    }

    @Test
    fun open_documents_overlay_indexed_files_without_losing_unsaved_edits() {
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.value = 1\nreturn M")
        val main = root.resolve("main.lua").writeLua("local dep = require(\"dep\")\nlocal copy = dep.value\nreturn copy")
        val service = initializedService(root)

        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(main.uri, "lua", 1, "local dep = require(\"dep\")\nlocal changed = dep.value\nreturn changed")
            )
        )
        service.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(main.uri, 2),
                listOf(TextDocumentContentChangeEvent("local dep = require(\"dep\")\nlocal unsavedOnly = dep.value\nreturn unsavedOnly"))
            )
        )

        val symbols = service.workspaceSymbols("unsavedOnly")
        val references = service.references(referenceParams(dep, "value"))

        assertTrue(symbols.any { it.name == "unsavedOnly" && it.location.uri == main.uri })
        assertTrue(references.any { it.uri == dep.uri })
        assertTrue(references.any { it.uri == main.uri })
        assertTrue(service.workspaceSymbols("copy").none { it.location.uri == main.uri })
    }

    @Test
    fun closing_open_document_restores_indexed_workspace_file_source() {
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.value = 1\nreturn M")
        val main = root.resolve("main.lua").writeLua("local dep = require(\"dep\")\nlocal diskOnly = dep.value\nreturn diskOnly")
        val service = initializedService(root)

        service.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(main.uri, "lua", 1, "local dep = require(\"dep\")\nlocal unsavedOnly = dep.value\nreturn unsavedOnly")
            )
        )
        service.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(main.uri)))

        val references = service.references(referenceParams(dep, "value"))

        assertTrue(service.workspaceSymbols("unsavedOnly").none { it.location.uri == main.uri })
        assertTrue(service.workspaceSymbols("diskOnly").any { it.location.uri == main.uri })
        assertTrue(references.any { it.uri == dep.uri })
        assertTrue(references.any { it.uri == main.uri })
    }

    @Test
    fun root_uri_initialization_indexes_workspace_when_folders_are_absent() {
        val root = tempWorkspace()
        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.value = 1\nreturn M")

        val service = LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    rootUri = root.uri
                }
            )
        }

        val symbols = service.workspaceSymbols("value")
        val diagnostics = service.diagnostics("dep.lua")

        assertTrue(symbols.any { it.name == "value" && it.location.uri == dep.uri })
        assertEquals(dep.uri, diagnostics.uri)
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
        return Files.createTempDirectory("lua-parser-lsp-workspace-")
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

    private data class WorkspaceFile(val path: Path) {
        val uri: String = path.toUri().toString()
        private val source: String = Files.readString(path)

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
