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
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.dsl.languages
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.lsp.client.connection.CustomConnectProvider
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.editor.LspProject
import io.github.rosemoe.sora.lsp.editor.getOption
import io.github.rosemoe.sora.lsp.events.EventContext
import io.github.rosemoe.sora.lsp.events.EventListener
import io.github.rosemoe.sora.lsp.events.EventType
import io.github.rosemoe.sora.lsp.events.document.applyEdits
import io.github.rosemoe.sora.lsp.events.diagnostics.publishDiagnostics
import io.github.rosemoe.sora.lsp.utils.createTextDocumentIdentifier
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.subscribeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File

/**
 * sora-editor Lua LSP sample for the lua-parser language server with a
 * Material 3 (View-based) editing surface, mirroring sora-editor 0.23.6's
 * app/LspTestActivity.kt with the differences required by OUR server:
 *
 *  - the server runs in-process ([LspServerService] over an abstract
 *    LocalSocket instead of a TCP `java.net.ServerSocket`),
 *  - `workspace/didChangeConfiguration` carries the JVM interop settings
 *    (`jvm.androidJar`, optionally `jvm.classpath` for dex files) once the
 *    connection is up — see LuaWorkspaceService.parseWorkspaceMetadata in
 *    :android for the accepted shapes (flat "jvm.androidJar" or nested
 *    jvm { androidJar } both work; this sample sends the flat form),
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

    private lateinit var editor: CodeEditor
    private lateinit var toolbar: MaterialToolbar
    private lateinit var diagnosticsBar: LinearLayout
    private lateinit var diagSummary: TextView

    private lateinit var lspProject: LspProject
    private lateinit var lspEditor: LspEditor

    /** Demo workspace materialized from assets/project on first run. */
    private lateinit var projectDir: File
    private lateinit var sampleFile: File

    /** Optional android.jar pushed into assets (see README.md). */
    private lateinit var androidJarCopy: File
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

        ensureTextmateTheme()
        setupToolbar()

        // INIT needs the long window: the first on-device initialize builds the
        // whole demo corpus AND mounts the 929-class demo dex. COMPLETION/HOVER
        // stay at sora's fast 3s defaults — completions never queue behind the
        // cold build (lock-free snapshot read), so 3s is plenty and keeps the
        // popup snappy.
        Timeout[Timeouts.INIT] = 60_000
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
            mark("copying workspace")
            prepareWorkspace()
            mark("workspace ready")
            // Second-level open: show the file text in the editor FIRST
            // (plain sora editor — readable without the language server),
            // then attach the LSP bridge and run the handshake.
            mark("editor text loading")
            withContext(Dispatchers.Main) {
                editor.setText(sampleFile.readText())
            }
            mark("editor text shown")
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
     * and copies the optional android.jar interop payload (OPTIONAL-IF-PRESENT
     * — the sample works without it; diagnostics about luajava interop just
     * stay quieter). The demo corpus's libs/classes.dex is advertised through
     * jvm.classpath so the dex mounting feature is exercised on-device.
     */
    private suspend fun prepareWorkspace(): Unit = withContext(Dispatchers.IO) {
        projectDir = ProjectBootstrapper.ensureWorkspace(this@MainActivity)
        sampleFile = File(projectDir, "main.lua")

        // assets/android.jar -> filesDir/android.jar (advertised via jvm.androidJar)
        androidJarCopy = File(filesDir, "android.jar")
        if (!androidJarCopy.isFile) {
            runCatching {
                assets.open(ANDROID_JAR_ASSET).use { input ->
                    androidJarCopy.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure {
                // No android.jar in assets: jvm.androidJar simply stays unset.
                androidJarCopy.delete()
                Log.i(TAG, "no $ANDROID_JAR_ASSET in assets; JVM interop config omitted")
            }
        }

        // assets/android.dex (CI: platform android.jar converted by d8) loads
        // framework classes through DexClassLoader on-device; optional-if-present.
        androidDexCopy = File(filesDir, "android.dex")
        if (!androidDexCopy.isFile) {
            runCatching {
                assets.open(ANDROID_DEX_ASSET).use { input ->
                    androidDexCopy.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure {
                Log.i(TAG, "no $ANDROID_DEX_ASSET in assets; device framework reflection omitted")
                androidDexCopy.delete()
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

        attachDiagnosticsListener()
    }

    /**
     * Opens a workspace file (project-relative) in the editor:
     * detach previous bridge -> attach new [LspEditor] -> load text ->
     * initialize handshake -> workspace/config (re)registration ->
     * `textDocument/didOpen`.
     */
    private suspend fun openFile(relativePath: String) {
        if (relativePath == currentFile && isEditorReady()) return

        val file = File(projectDir, relativePath)
        val text = withContext(Dispatchers.IO) {
            runCatching { file.readText() }
                .onFailure { Log.e(TAG, "unable to read $relativePath", it) }
                .getOrNull()
        } ?: run {
            withContext(Dispatchers.Main) {
                toast(getString(R.string.msg_open_failed, relativePath))
            }
            return
        }

        withContext(Dispatchers.Main) {
            if (!this@MainActivity::lspEditor.isInitialized) {
                toast(getString(R.string.msg_starting_server))
            }
            // Read-only while the connection and initial workspace index build.
            editor.editable = false
        }

        // 4. Detach the previous bridge OFF the main thread: LspEditor.dispose()
        //    blocks on textDocument/didClose (a local-socket write), and two
        //    live LspEditors must never subscribe the same CodeEditor (each
        //    would mirror didChange for its own URI). NOTE: disposing the last
        //    connected editor also STOPS the shared LanguageServerWrapper at
        //    0.23.6 (LanguageServerWrapper.disconnect -> stop(false)), tearing
        //    down the socket and its per-connection LuaLanguageServer — so
        //    every open below effectively re-initializes a fresh server
        //    connection. That is why step 7 re-sends workspace + config.
        val previous = if (this::lspEditor.isInitialized) lspEditor else null
        if (previous != null) {
            withContext(Dispatchers.IO) { runCatching { previous.dispose() } }
        }

        withContext(Dispatchers.Main) {
            // 5. Fresh bridge. LspEditor.editor's setter calls
            //    CodeEditor.setEditorLanguage, which destroys the previous
            //    Language — and LspLanguage.destroy() disposes its LspEditor,
            //    so stale bridges can never linger (already disposed above;
            //    dispose() is idempotent via its isClosed guard).
            lspEditor = lspProject.getOrCreateEditor(file.absolutePath)
            lspEditor.wrapperLanguage = createTextMateLanguage()
            lspEditor.editor = editor

            // 6. Load the text BEFORE didOpen: sora's DocumentOpenEvent builds
            //    textDocument/didOpen from LspEditor.editorContent (the editor's
            //    current text). The new bridge is not connected yet, so this
            //    setText cannot leak a premature textDocument/didChange.
            isApplyingProgrammaticText = true
            // CHECK-API(sora): none — CodeEditor.setText(CharSequence) verified
            // at tag 0.23.6 (CodeEditor.java).
            editor.setText(text)
            isApplyingProgrammaticText = false
            currentFile = relativePath
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
            // 7. initialize/initialized handshake (retries until the service
            //    accepts the socket; throws TimeoutException on failure).
            // sora's INIT retry window is a hard-coded 10s and our first
            // initialize on-device builds the whole corpus + mounts the 929-class
            // dex synchronously — longer than that window on first run. Keep
            // calling connectWithTimeout (it retries internally); the outer
            // retry loop below survives the first cold-start timeout and wins
            // on the second pass once the server has warmed up.
            var warmed = false
            repeat(3) { attempt ->
                try {
                    withContext(Dispatchers.IO) { lspEditor.connectWithTimeout() }
                    warmed = true
                    return@repeat
                } catch (e: java.util.concurrent.TimeoutException) {
                    Log.w(TAG, "LSP connect attempt ${attempt + 1} timed out; retrying (server warms up in background)", e)
                }
            }
            if (!warmed) {
                withContext(Dispatchers.IO) { lspEditor.connectWithTimeout() }
            }

            withContext(Dispatchers.IO) {
                // 8. (Re-)register the demo workspace folder and push the JVM
                //    interop config — the fresh server connection from step 4
                //    knows neither. The server dedupes/refreshes workspace
                //    roots (LuaWorkspaceService.didChangeWorkspaceFolders).
                lspEditor.requestManager?.didChangeWorkspaceFolders(
                    DidChangeWorkspaceFoldersParams().apply {
                        event = WorkspaceFoldersChangeEvent().apply {
                            added = listOf(
                                WorkspaceFolder(
                                    "file://${projectDir.absolutePath}",
                                    "sample-project"
                                )
                            )
                        }
                    }
                )

                val settings = buildJvmSettings()
                if (settings.isNotEmpty()) {
                    lspEditor.requestManager?.didChangeConfiguration(
                        DidChangeConfigurationParams(settings)
                    )
                }

                // 9. textDocument/didOpen — carries the buffer text from step 6;
                //    the server answers with textDocument/publishDiagnostics,
                //    feeding both the editor squiggles (sora's built-in
                //    PublishDiagnosticsEvent) and the summary bar (step 3's
                //    listener).
                lspEditor.openDocument()
            }
            connected = true
        } catch (failure: Exception) {
            Log.e(TAG, "unable to open $relativePath (language server)", failure)
        }

        withContext(Dispatchers.Main) {
            editor.editable = true
            if (connected) {
                setDirty(false)
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
     * (verified against its parseWorkspaceMetadata: flat "jvm.androidJar" /
     * "jvm.classpath" keys or nested jvm { ... } sections are both accepted).
     *
     * jvm.androidJar  — absolute path of android.jar; powers luajava interop
     *                   (bindClass/import of java.* types with hover + completion).
     * jvm.classpath   — extra classpath entries; dex/apk entries are mounted
     *                   through the dex parser (see DexMountingTddTest in :android).
     */
    private fun buildJvmSettings(): Map<String, Any> {
        val settings = linkedMapOf<String, Any>()
        if (this::androidJarCopy.isInitialized && androidJarCopy.isFile) {
            settings["jvm.androidJar"] = androidJarCopy.absolutePath
        }
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
        lifecycleScope.launch {
            val text = withContext(Dispatchers.Main) { editor.text.toString() }
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    File(projectDir, path).writeText(text)
                    lspEditor.saveDocument()
                }.exceptionOrNull()
            }
            withContext(Dispatchers.Main) {
                if (failure == null) {
                    setDirty(false)
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
        lifecycleScope.launch {
            val edits = withContext(Dispatchers.IO) {
                // Formatting profile per the sample contract. sora's
                // LspEventManager.init() registered a FormattingOptions
                // (tabSize=4, insertSpaces=true); reuse that instance so its
                // own FullFormattingEvent path would see the same profile.
                val options = lspEditor.eventManager.getOption<FormattingOptions>()
                    ?.apply {
                        tabSize = 4
                        isInsertSpaces = false
                    } ?: FormattingOptions().apply {
                        tabSize = 4
                        isInsertSpaces = false
                    }
                val params = DocumentFormattingParams()
                params.textDocument = lspEditor.uri.createTextDocumentIdentifier()
                params.options = options
                lspEditor.requestManager?.formatting(params)?.get()
            }

            if (edits.isNullOrEmpty()) {
                toast(getString(R.string.msg_no_formatting))
                return@launch
            }

            withContext(Dispatchers.Main) {
                isApplyingProgrammaticText = true
                val formatted = Content(editor.text.toString())
                lspEditor.eventManager.emit(EventType.applyEdits) {
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
        lspProject.eventEmitter.addListener(object : EventListener {
            override val eventName: String = EventType.publishDiagnostics

            override fun handle(context: EventContext) {
                val diagnostics = context.getOrNull<List<Diagnostic>>("data") ?: return
                runOnUiThread { showDiagnostics(diagnostics) }
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

    // ----------------------------------------------------- TextMate rendering

    private var grammarsLoaded = false

    private fun createTextMateLanguage(): TextMateLanguage {
        // Grammar must be resolvable through FileProviderRegistry, which was
        // pointed at assets in ensureTextmateTheme() (same order as the sora
        // sample). Registration happens once; each call still returns a FRESH
        // TextMateLanguage because CodeEditor.setEditorLanguage destroys the
        // previous language (and its LspLanguage wrapper) on every file switch.
        if (!grammarsLoaded) {
            GrammarRegistry.getInstance().loadGrammars(
                languages {
                    language("lua") {
                        grammar = "textmate/lua/syntaxes/lua.tmLanguage.json"
                        scopeName = "source.lua"
                        languageConfiguration = "textmate/lua/language-configuration.json"
                    }
                }
            )
            grammarsLoaded = true
        }
        // true: also collect plain identifiers for word-based completion on top
        // of the LSP completion items. (The sora sample passes false.)
        return TextMateLanguage.create("source.lua", true)
    }

    private fun ensureTextmateTheme() {
        val currentScheme = editor.colorScheme
        if (currentScheme is TextMateColorScheme) {
            return
        }

        // Route every textmate file lookup through assets before any registry load.
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))

        val themeRegistry = ThemeRegistry.getInstance()
        // sora LSP sample's own theme — known-good with this engine version.
        val themePath = "textmate/quietlight.json"
        themeRegistry.loadTheme(
            ThemeModel(
                IThemeSource.fromInputStream(
                    FileProviderRegistry.getInstance().tryGetInputStream(themePath),
                    themePath,
                    null
                ),
                "quietlight"
            )
        )
        themeRegistry.setTheme("quietlight")

        editor.colorScheme = TextMateColorScheme.create(themeRegistry)
    }

    // ------------------------------------------------------------- teardown

    override fun onDestroy() {
        super.onDestroy()
        // Graceful LSP shutdown: dispose sends shutdown/exit and detaches the
        // editor bridges (mirrors the sora sample's intent). lifecycleScope is
        // ALREADY cancelled by the time onDestroy runs, so the dispose calls go
        // through a fresh IO scope instead — LspEditor.dispose() writes to the
        // local socket and must not run on the main thread anyway.
        editor.release()
        // LspProject.dispose() -> closeAllEditors() -> LspEditor.dispose() has a
        // race in sora 0.23.6 (its internal edit-history removeAll can hit an
        // empty list — IndexOutOfBoundsException, device-verified crash on
        // destroy). Guard the whole dispose path; a leaked connection on
        // process teardown is harmless (the service closes the sockets).
        val disposeScope = CoroutineScope(Dispatchers.IO)
        if (this::lspEditor.isInitialized) {
            disposeScope.launch { runCatching { lspEditor.dispose() } }
        }
        if (this::lspProject.isInitialized) {
            disposeScope.launch { runCatching { lspProject.dispose() } }
        }
        stopService(Intent(this, LspServerService::class.java))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val ANDROID_JAR_ASSET = "android.jar"
        const val ANDROID_DEX_ASSET = "android.dex"
    }
}
