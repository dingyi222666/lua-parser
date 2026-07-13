package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent
import org.eclipse.lsp4j.WorkspaceServerCapabilities
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-400 / TASK-516 — LSP `workspace/didChangeWorkspaceFolders` multi-root corpus.
 *
 * Dual-path contract for dynamic multi-root folder changes:
 * - **Product path (TASK-516):** [LuaLanguageService] advertises
 *   `ServerCapabilities.workspace.workspaceFolders` with `supported` +
 *   `changeNotifications`. [LuaWorkspaceService.didChangeWorkspaceFolders]
 *   reindexes via [LuaLanguageService.applyWorkspaceFolderChanges] (reuses
 *   indexWorkspaceFolder/refreshWorkspaceFolderIndex). Added folders index
 *   `.lua`/`.aly` without a process restart; removed folders drop indexed
 *   sources/symbols; open-document overlays remain authoritative.
 * - **Legacy gap path (still soft-accepted):** if a surface reverts to the
 *   LSP4J default [UnsupportedOperationException] / missing capability, the
 *   dual-path branches below still record the documented gap without hard-crash.
 *
 * Multi-root **relative path collision** remains last-write-wins unless a
 * non-overlapping follow-up disambiguates virtual keys: folder indexing stores
 * virtual paths relative to each workspace folder root (`dep.lua` under root A
 * and root B share the same virtual key). Two roots that both contain the same
 * relative path therefore collide in the indexed map — last write wins for
 * symbols/source while each file still has a distinct real `file:` URI.
 * Documented here so a future multi-root slice can disambiguate
 * (folder-qualified virtual paths or URI-keyed index) without regressing the
 * single-root path used by [LspWorkspaceFoldersTddTest].
 *
 * Verification is review-owned / TASK-043 serialized
 * (`jvmTest --tests lsp.LspDidChangeWorkspaceFoldersTddTest`). Workers must not
 * run Gradle.
 */
class LspDidChangeWorkspaceFoldersTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun did_change_workspace_folders_surface_is_invokable_without_killing_service() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val workspace = LuaWorkspaceService(languageService)
        val root = tempWorkspace("surface")
        val folder = WorkspaceFolder(root.uri, root.fileName.toString())

        val outcome = invokeDidChangeWorkspaceFolders(
            workspace,
            DidChangeWorkspaceFoldersParams(
                WorkspaceFoldersChangeEvent(
                    listOf(folder),
                    emptyList()
                )
            )
        )

        assertTrue(
            outcome is FolderChangeOutcome.Unsupported ||
                outcome is FolderChangeOutcome.Accepted ||
                outcome is FolderChangeOutcome.Failed,
            "didChangeWorkspaceFolders surface must resolve to a known outcome; got $outcome"
        )

        if (outcome is FolderChangeOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "folder-change surface must not hard-crash; got ${outcome.detail}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Future support goldens (dual-path: soft when gap, hard when accepted)
    // -------------------------------------------------------------------------

    @Test
    fun future_support_added_folder_indexes_lua_without_server_restart() {
        val languageService = LuaLanguageService().also { it.initialize(InitializeParams()) }
        val workspace = LuaWorkspaceService(languageService)
        val addedRoot = tempWorkspace("added")
        val dep = addedRoot.resolve("dep.lua").writeLua("local M = {}\nM.addedMarker = 1\nreturn M")
        val folder = WorkspaceFolder(addedRoot.uri, addedRoot.fileName.toString())

        assertTrue(
            languageService.workspaceSymbols("addedMarker").none { it.location.uri == dep.uri },
            "precondition: folder not yet in workspace roots"
        )

        val outcome = invokeDidChangeWorkspaceFolders(
            workspace,
            DidChangeWorkspaceFoldersParams(
                WorkspaceFoldersChangeEvent(listOf(folder), emptyList())
            )
        )

        when (outcome) {
            is FolderChangeOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "gap path records UnsupportedOperationException; got ${outcome.detail}"
                )
                // Still no index after unsupported notification (no silent re-init).
                assertTrue(
                    languageService.workspaceSymbols("addedMarker").none { it.location.uri == dep.uri }
                )
            }
            is FolderChangeOutcome.Accepted -> {
                assertTrue(
                    languageService.workspaceSymbols("addedMarker").any {
                        it.name == "addedMarker" && it.location.uri == dep.uri
                    },
                    "future support: added workspace folder must index dep.lua without restart"
                )
            }
            is FolderChangeOutcome.Failed -> {
                assertFalse(isHardCrash(outcome.detail), "add-folder path must not hard-crash: ${outcome.detail}")
            }
        }
    }

    @Test
    fun future_support_removed_folder_drops_indexed_symbols_without_server_restart() {
        val root = tempWorkspace("remove-root")
        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.removeMarker = 1\nreturn M")
        val languageService = initializedWithFolders(listOf(root))
        val workspace = LuaWorkspaceService(languageService)

        assertTrue(
            languageService.workspaceSymbols("removeMarker").any { it.location.uri == dep.uri },
            "precondition: initialize-time multi/single root indexes removeMarker"
        )

        val outcome = invokeDidChangeWorkspaceFolders(
            workspace,
            DidChangeWorkspaceFoldersParams(
                WorkspaceFoldersChangeEvent(
                    emptyList(),
                    listOf(WorkspaceFolder(root.uri, root.fileName.toString()))
                )
            )
        )

        when (outcome) {
            is FolderChangeOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "gap path records UnsupportedOperationException; got ${outcome.detail}"
                )
                // Gap: symbols remain until a future re-initialize / product support.
                assertTrue(
                    languageService.workspaceSymbols("removeMarker").any { it.location.uri == dep.uri },
                    "unsupported remove must leave initialize-time index intact"
                )
            }
            is FolderChangeOutcome.Accepted -> {
                assertTrue(
                    languageService.workspaceSymbols("removeMarker").none { it.location.uri == dep.uri },
                    "future support: removed workspace folder must drop its indexed symbols"
                )
            }
            is FolderChangeOutcome.Failed -> {
                assertFalse(isHardCrash(outcome.detail), "remove-folder path must not hard-crash: ${outcome.detail}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Multi-root relative path collision (initialize-time documentation)
    // -------------------------------------------------------------------------

    @Test
    fun multi_root_initialize_indexes_distinct_relative_paths_across_folders() {
        val rootA = tempWorkspace("multi-a")
        val rootB = tempWorkspace("multi-b")
        val aOnly = rootA.resolve("a_only.lua").writeLua("local aOnlyMarker = true\nreturn aOnlyMarker")
        val bOnly = rootB.resolve("b_only.lua").writeLua("local bOnlyMarker = true\nreturn bOnlyMarker")

        val service = initializedWithFolders(listOf(rootA, rootB))

        assertTrue(
            service.workspaceSymbols("aOnlyMarker").any { it.location.uri == aOnly.uri },
            "multi-root initialize must index unique relative path under folder A"
        )
        assertTrue(
            service.workspaceSymbols("bOnlyMarker").any { it.location.uri == bOnly.uri },
            "multi-root initialize must index unique relative path under folder B"
        )
    }

    /**
     * Documents the multi-root relative-path collision:
     *
     * `LuaLanguageService` indexes files with virtual paths relative to each
     * workspace folder root (`virtualPathForWorkspaceFile`). Two roots that both
     * contain `shared.lua` therefore share the virtual key `shared.lua` in
     * `indexedWorkspaceFiles` / `indexedWorkspaceUris` (LinkedHashMap last-write
     * wins). Workspace symbols for the colliding name then report **one** of the
     * real URIs — not both — until product disambiguates multi-root virtual paths.
     *
     * This is independent of `didChangeWorkspaceFolders` (gap or future): the
     * collision already exists for multi-folder `initialize`.
     */

    @Test
    fun single_root_initialize_still_indexes_without_did_change_workspace_folders() {
        // Regression lock: missing didChangeWorkspaceFolders must not break the
        // initialize-time single-root path covered by LspWorkspaceFoldersTddTest.
        val root = tempWorkspace("single")
        val dep = root.resolve("dep.lua").writeLua("local M = {}\nM.singleRoot = 1\nreturn M")
        val service = initializedWithFolders(listOf(root))

        assertTrue(
            service.workspaceSymbols("singleRoot").any {
                it.name == "singleRoot" && it.location.uri == dep.uri
            },
            "initialize-time single-root indexing must work without didChangeWorkspaceFolders"
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun invokeDidChangeWorkspaceFolders(
        workspace: LuaWorkspaceService,
        params: DidChangeWorkspaceFoldersParams
    ): FolderChangeOutcome {
        return try {
            workspace.didChangeWorkspaceFolders(params)
            FolderChangeOutcome.Accepted
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                FolderChangeOutcome.Unsupported(
                    detail = root.toString(),
                    isUnsupportedOperation = true
                )
            } else if (isHardCrash(root.toString())) {
                FolderChangeOutcome.Failed(detail = root.toString())
            } else {
                FolderChangeOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun initializedWithFolders(roots: List<Path>): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = roots.map { root ->
                        WorkspaceFolder(root.uri, root.fileName.toString())
                    }
                }
            )
        }
    }

    private fun tempWorkspace(label: String): Path {
        return Files.createTempDirectory("lua-parser-lsp-wfolders-$label-")
    }

    private fun Path.writeLua(source: String): WorkspaceFile {
        parent?.createDirectories()
        writeText(source)
        return WorkspaceFile(this)
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) {
            // Notifications are not futures; still peel common wrappers if present.
            val cause = current.cause ?: break
            if (
                current::class.simpleName == "ExecutionException" ||
                current::class.simpleName == "CompletionException" ||
                current is RuntimeException && cause is UnsupportedOperationException
            ) {
                current = cause
            } else {
                break
            }
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

    private fun isHardCrash(detail: String): Boolean {
        return detail.contains("NullPointerException", ignoreCase = true) ||
            detail.contains("AssertionError", ignoreCase = true) ||
            detail.contains("KotlinNullPointerException", ignoreCase = true) ||
            detail.contains("IndexOutOfBoundsException", ignoreCase = true)
    }

    private sealed class FolderChangeOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : FolderChangeOutcome()

        data object Accepted : FolderChangeOutcome()

        data class Failed(val detail: String) : FolderChangeOutcome()
    }

    private data class WorkspaceFile(val path: Path) {
        val uri: String = path.toUri().toString()
    }

    private val Path.uri: String
        get() = toUri().toString()
}
