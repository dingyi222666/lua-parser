package io.github.dingyi222666.luaparser.sample

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.view.View
import com.google.android.material.appbar.MaterialToolbar
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lsp.requests.Timeout
import io.github.rosemoe.sora.lsp.requests.Timeouts
import io.github.rosemoe.sora.event.EventReceiver
import io.github.rosemoe.sora.event.Unsubscribe
import io.github.rosemoe.sora.lsp.client.connection.CustomConnectProvider
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lsp.editor.getOption
import io.github.rosemoe.sora.lsp.events.EventContext
import io.github.rosemoe.sora.lsp.events.EventListener
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.events.document.applyEdits
import io.github.rosemoe.sora.lsp.events.document.DocumentChangeEvent
import io.github.rosemoe.sora.lsp.events.diagnostics.publishDiagnostics
import io.github.rosemoe.sora.lsp.events.diagnostics.PublishDiagnosticsEvent
import io.github.rosemoe.sora.lsp.utils.createTextDocumentIdentifier
import io.github.rosemoe.sora.lsp.utils.transformToEditorDiagnostics
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.SchemeGitHub
import io.github.rosemoe.sora.widget.subscribeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * sora-editor Lua LSP sample for the lua-parser language server with a
 * Material 3 (View-based) editing surface, mirroring sora-editor 0.23.6's
 * app/LspTestActivity.kt with the differences required by OUR server:
 *
 *  - the server runs in-process ([LspServerService] over an abstract
 *    LocalSocket instead of a TCP `java.net.ServerSocket`),
 *  - `workspace/didChangeConfiguration` carries the JVM interop settings
 *    (`jvm.androidDex`, optionally `jvm.classpath` for dex files) once the
 *    connection is up — see LuaWorkspaceService.parseWorkspaceMetadata in
 *    :android for the accepted shapes (flat "jvm.androidDex" or nested
 *    jvm { androidDex } both work; this sample sends the flat form),
 *  - the demo workspace (filesDir/project holding the REAL demo corpus:
 *    main.lua + adapter/ + model/ + mods/ + views/ + layout/ + image/ +
 *    libs/classes.dex) is materialized from assets/project on first run via
 *    [ProjectBootstrapper] and registered as a workspace folder.
 *
 * M3 UI (all View-based — the app hosts the View-based sora CodeEditor, so
 * no Compose):
 *  - [MaterialToolbar] with file browser / Format / Save actions; the save
 *    icon doubles as the dirty indicator (amber while unsaved edits exist),
 *  - [FileBrowserFragment] (M3 bottom sheet) listing the workspace's
 *    `.lua`/`.aly` files; tapping one runs [openFile],
 *  - a bottom diagnostics summary bar ("X error(s), Y warning(s)"); tapping
 *    it jumps to the first reported line.
 *
 * sora API note: verified against sora-editor tag 0.23.6. The
 * `languageServerDefinition { name(...); ext(...); connection { local(...) } }`
 * DSL from sora master does not exist at 0.23.6, so the equivalent
 * 0.23.6 constructor [CustomLanguageServerDefinition] is used (see
 * [createServerDefinition]).
 */
class MainActivity : AppCompatActivity(), FileBrowserFragment.Listener {

    private lateinit var editor: WorkspaceCodeEditor
    private lateinit var toolbar: MaterialToolbar
    private lateinit var diagnosticsBar: LinearLayout
    private lateinit var diagSummary: TextView

    private lateinit var lspProject: LspProject
    private lateinit var lspEditor: LspEditor

    /** Demo workspace materialized from assets/project on first run. */
    private lateinit var projectDir: File
    private lateinit var sampleFile: File

    private lateinit var androidDexCopy: File

    /** Demo corpus dex (filesDir/project/libs/classes.dex) advertised via jvm.classpath. */
    private lateinit var dexCopy: File

    /** Project-relative path of the file currently loaded into the editor. */
    private var currentFile: String? = null

    /** Latest server-published diagnostics, for the "tap to jump" bar. */
    private var latestDiagnostics: List<Diagnostic> = emptyList()

    /**
     * Guard for programmatic [CodeEditor.setText] calls (open/format): the
     * resulting ContentChangeEvent must not flip the dirty indicator.
     */
    private var isApplyingProgrammaticText = false

    private var connectedToastShown = false
    private val openMutex = Mutex()
    private val documentBridges = linkedMapOf<String, LspEditor>()
    private val documentBuffers = linkedMapOf<String, String>()
    private val dirtyDocuments = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge and pad the toolbar below the status bar — without
        // this the MIUI status bar overlays/hides the MaterialToolbar entirely.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        editor = findViewById(R.id.code_editor)
        diagnosticsBar = findViewById(R.id.diagnostics_bar)
        diagSummary = findViewById(R.id.diag_summary)

        editor.apply {
            typefaceText = Typeface.MONOSPACE
            typefaceLineNumber = Typeface.MONOSPACE
        }

        // Status-bar insets keep the toolbar visible under edge-to-edge.
        val root = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, statusBars.top, view.paddingRight, view.paddingBottom)
            insets
        }

        // Android 13+: the foreground-service notification is invisible without
        // the runtime notification permission (service keeps running either way).
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        editor.colorScheme = SchemeGitHub()
        // Keep the first editable frame cheap. The Lua highlighter is attached
        // with the active LSP bridge in openFile after the workspace handshake;
        // running a full lexer pass while the first live semantic analysis is
        // starting only contends for the same software-emulated CPU.
        editor.setEditorLanguage(EmptyLanguage())
        setupToolbar()

        // initialize returns capabilities immediately; heavy indexing is background work.
        Timeout[Timeouts.INIT] = 10_000
        Timeout[Timeouts.COMPLETION] = 10_000
        Timeout[Timeouts.SHUTDOWN] = 15_000
        subscribeDirtyTracking()
        diagnosticsBar.setOnClickListener { jumpToFirstProblem() }

        // Live indexing status in the bottom bar: the service hosts the LSP
        // in-process, so progress arrives without any client round-trip.
        lifecycleScope.launch {
            LspServerService.buildProgress.collect { status ->
                diagSummary.text = status
            }
        }

        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            fun mark(phase: String) {
                val total = System.currentTimeMillis() - t0
                Log.i("PerfTiming", "$phase at +${total}ms")
                runOnUiThread {
                    diagSummary.text = getString(R.string.diag_opening, phase, total.toInt())
                }
            }
            // The first document is usable while the rest of the bundled
            // workspace is copied. Connecting later opens the current buffer,
            // preserving any edits made during that copy.
            mark("editor text loading")
            val initialText = withContext(Dispatchers.IO) {
                val existing = File(filesDir, "project/main.lua")
                if (existing.isFile) existing.readText()
                else assets.open("project/main.lua").bufferedReader().use { it.readText() }
            }
            isApplyingProgrammaticText = true
            editor.setText(initialText)
            isApplyingProgrammaticText = false
            currentFile = "main.lua"
            toolbar.subtitle = currentFile
            editor.editable = true
            mark("editor text shown")
            mark("copying workspace")
            prepareWorkspace()
            mark("workspace ready")
            connectToLanguageServer()
            mark("lsp connected")
            openFile(sampleFile.toRelativeString(projectDir))
            mark("open complete")
            Log.i("PerfTiming", "TOTAL open at +${System.currentTimeMillis() - t0}ms")
        }
    }

    // --------------------------------------------------------- workspace prep
    // (Lane S1 territory: ProjectBootstrapper + assets/project + manifest
    // marker — left untouched by the UI upgrade.)

    /**
     * Materializes the demo project from assets/project into
     * filesDir/project (first run, or whenever the shipped corpus changes —
     * see [ProjectBootstrapper] for the manifest + version marker contract)
     * and copies the required Android framework DEX interop payload. The demo corpus's
     * libs/classes.dex is advertised through jvm.classpath so the dex mounting feature is
     * exercised on-device.
     */
    private suspend fun prepareWorkspace(): Unit = withContext(Dispatchers.IO) {
        projectDir = ProjectBootstrapper.ensureWorkspace(this@MainActivity)
        sampleFile = File(projectDir, "main.lua")

        // Refresh the required metadata on APK upgrades, publishing only a complete copy.
        androidDexCopy = File(filesDir, "android.dex")
        val metadataVersion = File(filesDir, "android.dex.version")
        val apkVersion = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        if (!androidDexCopy.isFile || !metadataVersion.isFile || metadataVersion.readText() != apkVersion) {
            val pending = File(filesDir, "android.dex.pending")
            try {
                assets.open(ANDROID_DEX_ASSET).use { input ->
                    pending.outputStream().use { output -> input.copyTo(output) }
                }
                check(pending.renameTo(androidDexCopy)) { "Cannot publish Android API metadata" }
                metadataVersion.writeText(apkVersion)
            } finally {
                pending.delete()
            }
        }

        // The bundled corpus ships libs/classes.dex inside the project; mount
        // it directly (no top-level filesDir copy needed anymore).
        dexCopy = File(projectDir, "libs/classes.dex")
    }

    // ------------------------------------------------------------- LSP attach

    /**
     * Starts the in-process server and builds the client-side [LspProject].
     * Per-file bridges are created lazily by [openFile].
     */
    private suspend fun connectToLanguageServer(): Unit = withContext(Dispatchers.IO) {
        // The server must see dex/android metadata before initialize schedules
        // its first workspace build. Sending this later through
        // didChangeConfiguration would rebuild the whole workspace a second time.
        LspServerService.configureWorkspaceMetadata(buildJvmSettings())
        // 1. Server side: bind the LocalSocket and launch one LuaLanguageServer
        //    per accepted connection.
        startService(Intent(this@MainActivity, LspServerService::class.java))

        // 2. Client side: sora LSP project rooted at the workspace.
        lspProject = LspProject(projectDir.absolutePath)

        // 3. Server definitions. sora keys language-server wrappers by file
        //    extension, and the browser opens .aly files too (layout/*.aly in
        //    the corpus), so register the SAME in-process socket for both
        //    extensions — each connection gets its own LuaLanguageServer
        //    instance, per the LspServerService concurrency contract.
        lspProject.addServerDefinition(createServerDefinition("lua"))
        lspProject.addServerDefinition(createServerDefinition("aly"))
        lspProject.init()
        editor.bindProject(lspProject)
        attachDiagnosticsListener()
    }

    /**
     * Opens a workspace file (project-relative) in the editor:
     * detach previous bridge -> attach new [LspEditor] -> load text ->
     * initialize handshake -> workspace/config (re)registration ->
     * `textDocument/didOpen`.
     */
    private suspend fun openFile(relativePath: String) = openMutex.withLock {
        if (relativePath == currentFile && isEditorReady()) return@withLock

        val file = File(projectDir, relativePath)
        val text = documentBuffers[relativePath] ?: withContext(Dispatchers.IO) {
            runCatching { file.readText() }
                .onFailure { Log.e(TAG, "unable to read $relativePath", it) }
                .getOrNull()
        } ?: run {
            withContext(Dispatchers.Main) {
                toast(getString(R.string.msg_open_failed, relativePath))
            }
            return@withLock
        }

        withContext(Dispatchers.Main) {
            if (!this@MainActivity::lspEditor.isInitialized) {
                toast(getString(R.string.msg_starting_server))
            }
            editor.editable = true
        }

        val highlightLanguage = LuaHighlightLanguage()
        withContext(Dispatchers.Main) {
            currentFile?.let { documentBuffers[it] = editor.text.toString() }
            editor.detachBridgeEvents()

            // 6. Load the text BEFORE didOpen: sora's DocumentOpenEvent builds
            //    textDocument/didOpen from LspEditor.editorContent (the editor's
            //    current text). The new bridge is not connected yet, so this
            //    setText cannot leak a premature textDocument/didChange.
            isApplyingProgrammaticText = true
            // CHECK-API(sora): none — CodeEditor.setText(CharSequence) verified
            // at tag 0.23.6 (CodeEditor.java).
            if (currentFile != relativePath) editor.setText(text)
            isApplyingProgrammaticText = false
            currentFile = relativePath
            lspEditor = documentBridges.getOrPut(relativePath) { lspProject.getOrCreateEditor(file.absolutePath) }
            lspEditor.wrapperLanguage = highlightLanguage
            // Reattach even when the view is the same: switching away removes
            // its subscriptions, so returning must install fresh receivers.
            lspEditor.editor = editor
            toolbar.subtitle = relativePath
            // XML res-auto attrs (app:title* / app:iconTint) are avoided on
            // purpose: AAPT2 attr linking against the material/appcompat AARs
            // proved cache-sensitive on the CI runner; everything visual is
            // set programmatically here instead.
            toolbar.setTitleTextColor(ContextCompat.getColor(this@MainActivity, R.color.toolbar_title))
            toolbar.setSubtitleTextColor(ContextCompat.getColor(this@MainActivity, R.color.toolbar_subtitle))
        }

        var connected = false
        try {
            Log.i(TAG, "connecting editor $relativePath")
            // Existing bridges already own their open buffer; reuse their
            // connection instead of restarting the whole extension server.
            if (!lspEditor.isConnected) withContext(Dispatchers.IO) { lspEditor.connectWithTimeout() }
            connected = true
            Log.i(TAG, "editor connected $relativePath")
        } catch (failure: Exception) {
            Log.e(TAG, "unable to open $relativePath (language server)", failure)
        }

        withContext(Dispatchers.Main) {
            editor.editable = true
            if (connected) {
                setDirty(relativePath in dirtyDocuments)
                if (!connectedToastShown) {
                    connectedToastShown = true
                    toast(getString(R.string.msg_server_connected))
                }
            } else {
                toast(getString(R.string.msg_open_failed, relativePath))
            }
        }
    }

    /**
     * The server definition. sora 0.23.6 form of (master):
     * `languageServerDefinition { name(...); ext(...); connection { local(...) } }`.
     * `name` is not a constructor parameter at 0.23.6 (the wrapper is keyed by
     * extension alone), so only `ext` and the connect provider are set.
     */
    private fun createServerDefinition(ext: String): CustomLanguageServerDefinition {
        return CustomLanguageServerDefinition(
            ext,
            CustomLanguageServerDefinition.ServerConnectProvider {
                CustomConnectProvider(LocalSocketStreamProvider(LspServerService.SOCKET_NAME))
            }
        )
    }

    /**
     * Flat workspace configuration consumed by LuaWorkspaceService in :android
     * (verified against its parseWorkspaceMetadata: flat "jvm.androidDex" /
     * "jvm.classpath" keys or nested jvm { ... } sections are both accepted).
     *
     * jvm.androidDex  — absolute path of framework classes.dex; powers device-side
     *                   framework member hydration and luajava interop.
     * jvm.classpath   — extra classpath entries; dex/apk entries are mounted
     *                   through the dex parser (see DexMountingTddTest in :android).
     */
    private fun buildJvmSettings(): Map<String, String> {
        val settings = linkedMapOf<String, String>()
        if (this::androidDexCopy.isInitialized && androidDexCopy.isFile) {
            settings["jvm.androidDex"] = androidDexCopy.absolutePath
        }
        if (this::dexCopy.isInitialized && dexCopy.isFile) {
            settings["jvm.classpath"] = dexCopy.absolutePath
        }
        return settings
    }

    // ------------------------------------------------------ toolbar + browser

    private fun setupToolbar() {
        // Inflate through the AppCompat support menu inflater so app:iconTint
        // on the items is honored (Toolbar.inflateMenu alone goes through the
        // platform inflater and would silently drop it on the dark toolbar).
        menuInflater.inflate(R.menu.menu_main, toolbar.menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open_file -> {
                    showFileBrowser()
                    true
                }
                R.id.action_format -> {
                    formatCurrentFile()
                    true
                }
                R.id.action_save -> {
                    saveCurrentFile()
                    true
                }
                else -> false
            }
        }
        // Deterministic baseline tint for every action icon (the save icon is
        // then re-tinted by setDirty as the unsaved-edits indicator).
        val menu = toolbar.menu
        for (index in 0 until menu.size()) {
            menu.getItem(index).icon?.mutate()
                ?.setTint(ContextCompat.getColor(this, R.color.toolbar_icon))
        }
    }

    private fun showFileBrowser() {
        if (!this::projectDir.isInitialized) return
        FileBrowserFragment.newInstance()
            .show(supportFragmentManager, FileBrowserFragment.TAG)
    }

    override fun onFileSelected(relativePath: String) {
        if (!isEditorReady()) {
            toast(getString(R.string.msg_starting_server))
            return
        }
        lifecycleScope.launch { openFile(relativePath) }
    }

    /**
     * Dirty tracking: every user edit flips the save indicator. Programmatic
     * setText calls (open/format) are excluded via [isApplyingProgrammaticText].
     */
    private fun subscribeDirtyTracking() {
        // CHECK-API(sora): none — CodeEditor.subscribeEvent(receiver) verified
        // at tag 0.23.6 (inline extension in io.github.rosemoe.sora.widget /
        // Editor.kt, delegating to EventManager.subscribeEvent(Class, receiver)).
        editor.subscribeEvent(object : EventReceiver<ContentChangeEvent> {
            override fun onReceive(event: ContentChangeEvent, unsubscribe: Unsubscribe) {
                if (isApplyingProgrammaticText) return
                // Editor events dispatch on the main thread; runOnUiThread is
                // an inline no-op hop there.
                runOnUiThread { setDirty(true) }
            }
        })
    }

    /** Save indicator: the toolbar save icon warms up while the buffer is dirty. */
    private fun setDirty(dirty: Boolean) {
        currentFile?.let { path -> if (dirty) dirtyDocuments.add(path) else dirtyDocuments.remove(path) }
        if (!this::toolbar.isInitialized) return
        val item = toolbar.menu.findItem(R.id.action_save) ?: return
        val icon = item.icon?.mutate() ?: return
        icon.setTint(
            ContextCompat.getColor(
                this,
                if (dirty) R.color.save_dirty else R.color.toolbar_icon
            )
        )
        item.icon = icon
    }

    // ------------------------------------------------------------ save/format

    private fun isEditorReady(): Boolean =
        this::lspEditor.isInitialized && lspEditor.isConnected

    /**
     * Save flow: write the editor buffer back to `filesDir/project/<rel>` and
     * notify the server via `textDocument/didSave` (sora's DocumentSaveEvent
     * attaches the editor buffer text, mirroring the disk write).
     */
    private fun saveCurrentFile() {
        val path = currentFile
        if (path == null || !isEditorReady()) {
            toast(getString(R.string.msg_not_connected))
            return
        }
        val bridge = lspEditor
        lifecycleScope.launch {
            val text = withContext(Dispatchers.Main) { editor.text.toString() }
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    File(projectDir, path).writeText(text)
                    bridge.eventManager.getEventListener(DocumentChangeEvent::class.java)?.future?.get(10, TimeUnit.SECONDS)
                    bridge.saveDocument()
                }.exceptionOrNull()
            }
            withContext(Dispatchers.Main) {
                if (failure == null) {
                    if (currentFile == path && editor.text.toString() == text) setDirty(false)
                    toast(getString(R.string.msg_saved, path))
                } else {
                    Log.e(TAG, "save failed for $path", failure)
                    toast(getString(R.string.msg_save_failed))
                }
            }
        }
    }

    /**
     * Format flow: `textDocument/formatting` with tabSize=4 / insertSpaces=false
     * straight through sora's [LspEditor.requestManager] (the 0.23.6
     * RequestManager extends lsp4j's TextDocumentService, so `formatting` is
     * the raw LSP request). The returned TextEdits are applied to a CLONE of
     * the buffer using sora's own ApplyEditsEvent (identical in-order replace
     * semantics to its FullFormattingEvent), then the formatted text replaces
     * the buffer in one shot — the swap fires ContentChangeEvent, which sora's
     * LspEditorContentChangeEventReceiver mirrors to the server as a full
     * textDocument/didChange.
     */
    private fun formatCurrentFile() {
        if (!isEditorReady()) {
            toast(getString(R.string.msg_not_connected))
            return
        }
        val bridge = lspEditor
        val source = editor.text.toString()
        lifecycleScope.launch {
            val edits = try { withContext(Dispatchers.IO) {
                bridge.eventManager.getEventListener(DocumentChangeEvent::class.java)?.future?.get(10, TimeUnit.SECONDS)
                // Formatting profile per the sample contract. sora's
                // LspEventManager.init() registered a FormattingOptions
                // (tabSize=4, insertSpaces=true); reuse that instance so its
                // own FullFormattingEvent path would see the same profile.
                val options = bridge.eventManager.getOption<FormattingOptions>()
                    ?.apply {
                        tabSize = 4
                        isInsertSpaces = false
                    } ?: FormattingOptions().apply {
                        tabSize = 4
                        isInsertSpaces = false
                    }
                val params = DocumentFormattingParams()
                params.textDocument = bridge.uri.createTextDocumentIdentifier()
                params.options = options
                bridge.requestManager?.formatting(params)?.get(10, TimeUnit.SECONDS)
            } } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "format failed for ${bridge.uri}", failure)
                toast(getString(R.string.msg_format_failed))
                return@launch
            }

            if (edits.isNullOrEmpty()) {
                toast(getString(R.string.msg_no_formatting))
                return@launch
            }

            withContext(Dispatchers.Main) {
                if (lspEditor !== bridge || editor.text.toString() != source) return@withContext
                isApplyingProgrammaticText = true
                val formatted = Content(editor.text.toString())
                bridge.eventManager.emit(EventType.applyEdits) {
                    put("edits", edits)
                    put("content", formatted)
                }
                editor.setText(formatted)
                isApplyingProgrammaticText = false
                setDirty(true) // formatted buffer differs from the file until saved
                toast(getString(R.string.msg_formatted))
            }
        }
    }

    // ----------------------------------------------------------- diagnostics

    /**
     * Diagnostics display path: the server pushes `textDocument/publishDiagnostics`
     * -> sora's DefaultLanguageClient stores them in LspProject.diagnosticsContainer
     * and calls LspEditor.onDiagnosticsUpdate() -> the editor-lsp event bus
     * emits `"editor/publishDiagnostics"` (sora's PublishDiagnosticsEvent turns
     * it into editor squiggles). This extra listener on the SAME project-scoped
     * emitter keeps the summary bar in step; it survives editor switches because
     * the emitter is owned by the LspProject, not by any single LspEditor.
     */
    private fun attachDiagnosticsListener() {
        lspProject.eventEmitter.removeListener(PublishDiagnosticsEvent::class.java)
        lspProject.eventEmitter.addListener(object : EventListener {
            override val eventName: String = EventType.publishDiagnostics

            override fun handle(context: EventContext) {
                val bridge = context.getOrNull<LspEditor>("lsp-editor") ?: return
                val diagnostics = context.getOrNull<List<Diagnostic>>("data")?.toList() ?: return
                // Sora 0.23.6 appends each complete LSP report. Consume its
                // staging list synchronously so the next report replaces this
                // one, including an empty report that clears all errors.
                lspProject.diagnosticsContainer.setDiagnostics(bridge.uri, mutableListOf())
                runOnUiThread {
                    if (this@MainActivity::lspEditor.isInitialized && bridge === lspEditor && !isDestroyed) {
                        // A delayed report may refer to the pre-edit buffer.
                        // Convert and assign on this same UI turn; Sora's
                        // default renderer posts again and can race setText.
                        val content = editor.text
                        val valid = diagnostics.filter { diagnostic ->
                            val range = diagnostic.range ?: return@filter false
                            listOf(range.start, range.end).all { position ->
                                position.line in 0 until content.lineCount &&
                                    position.character in 0..content.getColumnCount(position.line)
                            }
                        }
                        val regions = DiagnosticsContainer()
                        regions.addDiagnostics(valid.transformToEditorDiagnostics(editor))
                        editor.diagnostics = regions
                        showDiagnostics(valid)
                    }
                }
            }
        })
    }

    private fun showDiagnostics(diagnostics: List<Diagnostic>) {
        latestDiagnostics = diagnostics
        val errors = diagnostics.count {
            it.severity == DiagnosticSeverity.Error
        }
        val warnings = diagnostics.count {
            it.severity == DiagnosticSeverity.Warning
        }
        diagSummary.text = if (errors == 0 && warnings == 0) {
            getString(R.string.diag_none)
        } else {
            getString(R.string.diag_summary, errors, warnings)
        }
    }

    /** Bar tap: jump to the first (lowest-severity-ranked, then topmost) line. */
    private fun jumpToFirstProblem() {
        val target = latestDiagnostics
            .filter { it.range?.start != null }
            .minWithOrNull(
                compareBy(
                    { severityRank(it) },
                    { it.range.start.line }
                )
            ) ?: return
        val lineCount = editor.text.lineCount
        val line = target.range.start.line.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        val column = target.range.start.character.coerceAtLeast(0)
        // CHECK-API(sora): none — CodeEditor.setSelection(line, column)
        // verified at tag 0.23.6 (CodeEditor.java).
        editor.setSelection(line, column)
    }

    private fun severityRank(diagnostic: Diagnostic): Int = when (diagnostic.severity) {
        DiagnosticSeverity.Error -> 0
        DiagnosticSeverity.Warning -> 1
        else -> 2
    }

    // ------------------------------------------------------------- teardown

    override fun onDestroy() {
        super.onDestroy()
        editor.detachBridgeEvents()
        editor.release()
        val bridges = documentBridges.values.toList()
        if (this::lspProject.isInitialized) {
            CoroutineScope(Dispatchers.IO).launch {
                // Dispose a stable list first: Sora closeAllEditors mutates its
                // own map during iteration. Never dispose bridges concurrently.
                bridges.forEach { bridge -> runCatching { bridge.dispose() } }
                runCatching { lspProject.dispose() }
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val ANDROID_DEX_ASSET = "android.dex"
    }
}
