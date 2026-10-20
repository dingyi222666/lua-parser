package io.github.dingyi222666.luaparser.sample

import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.lsp.events.AsyncEventListener
import io.github.rosemoe.sora.lsp.events.EventContext
import io.github.rosemoe.sora.lsp.events.document.DocumentChangeEvent
import io.github.rosemoe.sora.lsp.events.document.DocumentOpenEvent
import io.github.rosemoe.sora.lsp.events.signature.signatureHelp
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.utils.toFileUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Sends one ordered notification for all edits made in a single UI turn. */
internal class EditorDocumentSync(
    private val editor: WorkspaceCodeEditor,
    private val activeBridge: () -> LspEditor?
) {
    private val versions = IdentityHashMap<LspEditor, Int>()
    private val sender = Executors.newSingleThreadExecutor { task ->
        Thread(task, "lsp-document-send").apply { isDaemon = true }
    }
    private val changes = mutableListOf<TextDocumentContentChangeEvent>()
    private var pendingBridge: LspEditor? = null
    private var pendingFuture: CompletableFuture<Void>? = null
    private var signaturePosition: io.github.rosemoe.sora.text.CharPosition? = null
    private val drain = Runnable { flush() }
    @Volatile private var closed = false

    fun install(project: LspProject) {
        project.eventEmitter.removeListener(DocumentOpenEvent::class.java)
        project.eventEmitter.addListener(object : AsyncEventListener() {
            override val eventName = "textDocument/didOpen"
            override suspend fun handleAsync(context: EventContext) {
                val bridge = context.get<LspEditor>("lsp-editor")
                synchronized(versions) { versions[bridge] = 0 }
                bridge.requestManager?.didOpen(DidOpenTextDocumentParams(
                    TextDocumentItem(bridge.uri.toFileUri(), "lua", 0, bridge.editorContent)
                ))
            }
        })
    }

    fun changed(bridge: LspEditor, event: ContentChangeEvent) {
        if (closed || !bridge.isConnected || bridge !== activeBridge()) return
        begin(bridge)
        if (bridge.textDocumentSyncKind != TextDocumentSyncKind.Incremental ||
            event.action == ContentChangeEvent.ACTION_SET_NEW_TEXT) {
            changes.clear()
            changes += TextDocumentContentChangeEvent(editor.text.toString())
        } else {
            val start = Position(event.changeStart.line, event.changeStart.column)
            val end = if (event.action == ContentChangeEvent.ACTION_INSERT) start
                else Position(event.changeEnd.line, event.changeEnd.column)
            changes += TextDocumentContentChangeEvent(Range(start, end),
                if (event.action == ContentChangeEvent.ACTION_DELETE) "" else event.changedText.toString())
        }
        signaturePosition = event.changeStart.fromThis()
    }

    /** Explicit full-document path for programmatic CodeEditor.setText calls. */
    fun replaceAll(bridge: LspEditor) {
        if (closed || !bridge.isConnected || bridge !== activeBridge()) return
        begin(bridge)
        changes.clear()
        changes += TextDocumentContentChangeEvent(editor.text.toString())
        signaturePosition = null
    }

    private fun begin(bridge: LspEditor) {
        if (pendingBridge != null && pendingBridge !== bridge) detach()
        if (pendingFuture == null) {
            pendingBridge = bridge
            pendingFuture = CompletableFuture<Void>().also {
                bridge.eventManager.getEventListener(DocumentChangeEvent::class.java)?.future = it
            }
            editor.post(drain)
        }
    }

    private fun flush() {
        val bridge = pendingBridge ?: return
        val future = pendingFuture ?: return
        val position = signaturePosition
        val version = synchronized(versions) {
            (versions[bridge] ?: 0).inc().also { versions[bridge] = it }
        }
        val params = DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(bridge.uri.toFileUri(), version), changes.toList()
        )
        android.util.Log.i("LspDocumentSync", "didChange uri=${bridge.uri} full=${params.contentChanges.size} len=${editor.text.length} v=$version")
        pendingBridge = null
        pendingFuture = null
        changes.clear()
        sender.execute {
            try {
                if (closed) { future.cancel(false); return@execute }
                bridge.requestManager?.didChange(params)
                future.complete(null)
                if (position != null && bridge === activeBridge()) {
                    bridge.coroutineScope.launch(Dispatchers.IO) {
                        if (bridge === activeBridge()) bridge.eventManager.emitAsync(EventType.signatureHelp, position)
                    }
                }
            } catch (failure: Exception) {
                future.completeExceptionally(failure)
            }
        }
    }

    fun detach() {
        editor.removeCallbacks(drain)
        // A file switch can occur before the posted UI drain. Keep that last
        // edit in the ordered sender queue before detaching the old bridge.
        flush()
    }

    fun close() {
        closed = true
        editor.removeCallbacks(drain)
        pendingFuture?.cancel(false)
        pendingBridge = null
        pendingFuture = null
        changes.clear()
        signaturePosition = null
        sender.shutdown()
        synchronized(versions) { versions.clear() }
    }
}
