package io.github.dingyi222666.luaparser.sample

import android.content.Context
import android.util.AttributeSet
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lsp.editor.LspLanguage
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lsp.editor.event.LspEditorContentChangeEventReceiver
import io.github.rosemoe.sora.lsp.editor.event.LspEditorSelectionChangeEventReceiver
import io.github.rosemoe.sora.event.Event
import io.github.rosemoe.sora.event.EventReceiver
import io.github.rosemoe.sora.event.SubscriptionReceipt
import io.github.rosemoe.sora.widget.CodeEditor

/** Activity owns LSP connections; replacing a syntax language only releases UI resources. */
class WorkspaceCodeEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CodeEditor(context, attrs) {
    private val bridgeSubscriptions = mutableListOf<SubscriptionReceipt<*>>()
    @Volatile private var activeBridge: LspEditor? = null
    private val documentSync = EditorDocumentSync(this) { activeBridge }

    fun bindProject(project: LspProject) = documentSync.install(project)

    override fun setText(text: CharSequence?) {
        super.setText(text)
        activeBridge?.let(documentSync::replaceAll)
    }

    fun detachBridgeEvents() {
        documentSync.detach()
        activeBridge = null
        bridgeSubscriptions.forEach { it.unsubscribe() }
        bridgeSubscriptions.clear()
    }

    override fun <T : Event> subscribeEvent(eventType: Class<T>, receiver: EventReceiver<T>): SubscriptionReceipt<T> {
        val receipt = if (receiver is LspEditorContentChangeEventReceiver) {
            super.subscribeEvent(eventType, EventReceiver<T> { event, _ ->
                if (event is ContentChangeEvent) {
                    // Resolve the bridge at dispatch time. LspEditor installs
                    // this receiver before connect, and a receiver captured
                    // during an earlier file attachment would otherwise drop
                    // the first full setText change on the active document.
                    activeBridge?.let { documentSync.changed(it, event) }
                }
            })
        } else super.subscribeEvent(eventType, receiver)
        if (receiver is LspEditorContentChangeEventReceiver || receiver is LspEditorSelectionChangeEventReceiver) {
            bridgeSubscriptions += receipt
        }
        return receipt
    }

    override fun setEditorLanguage(language: Language?) {
        activeBridge = (language as? LspLanguage)?.editor
        super.setEditorLanguage(if (language is LspLanguage) ActivityOwnedLanguage(language) else language)
    }

    override fun release() {
        activeBridge = null
        documentSync.close()
        super.release()
    }

    // Connections live for the Activity; switching files releases only the
    // old highlighting analyzer. Its LSP formatter remains usable on return.
    private class ActivityOwnedLanguage(private val delegate: LspLanguage) : Language by delegate {
        private val highlighting = delegate.wrapperLanguage
        private val analyzer = delegate.analyzeManager
        override fun getAnalyzeManager() = analyzer
        override fun destroy() {
            highlighting?.destroy()
        }
    }
}
