package io.github.dingyi222666.luaparser.sample

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File

/**
 * Minimal sora-editor Lua LSP sample for the lua-parser language server,
 * mirroring sora-editor 0.23.6's app/LspTestActivity.kt with the differences
 * required by OUR server:
 *
 *  - the server runs in-process ([LspServerService] over an abstract
 *    LocalSocket instead of a TCP `java.net.ServerSocket`),
 *  - `workspace/didChangeConfiguration` carries the JVM interop settings
 *    (`jvm.androidJar`, optionally `jvm.classpath` for dex files) once the
 *    connection is up — see LuaWorkspaceService.parseWorkspaceMetadata in
 *    :android for the accepted shapes (flat "jvm.androidJar" or nested
 *    jvm { androidJar } both work; this sample sends the flat form),
 *  - the demo workspace (filesDir/project with sample.lua) is materialized
 *    from assets on first run and registered as a workspace folder.
 *
 * sora API note: verified against sora-editor tag 0.23.6. The
 * `languageServerDefinition { name(...); ext(...); connection { local(...) } }`
 * DSL from sora master does not exist at 0.23.6, so the equivalent
 * 0.23.6 constructor [CustomLanguageServerDefinition] is used (see
 * [createServerDefinition]).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var editor: CodeEditor
    private lateinit var lspProject: LspProject
    private lateinit var lspEditor: LspEditor

    /** Demo workspace materialized from assets on first run. */
    private lateinit var projectDir: File
    private lateinit var sampleFile: File

    /** Optional JVM interop payloads pushed into assets (see README.md). */
    private lateinit var androidJarCopy: File
    private lateinit var dexCopy: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editor = findViewById(R.id.code_editor)
        editor.apply {
            typefaceText = Typeface.MONOSPACE
            typefaceLineNumber = Typeface.MONOSPACE
        }

        ensureTextmateTheme()

        lifecycleScope.launch {
            prepareWorkspace()
            connectToLanguageServer()
            setEditorText()
        }
    }

    // --------------------------------------------------------- workspace prep

    /**
     * Copies the demo project from assets into filesDir/project (first run
     * only) and the optional JVM interop payloads (android.jar / classes.dex,
     * both OPTIONAL-IF-PRESENT — the sample works without them; diagnostics
     * about luajava interop just stay quieter).
     */
    private suspend fun prepareWorkspace(): Unit = withContext(Dispatchers.IO) {
        projectDir = File(filesDir, "project").apply { mkdirs() }
        for (assetName in LUA_ASSETS) {
            val target = File(projectDir, assetName)
            if (!target.isFile) {
                assets.open(assetName).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        sampleFile = File(projectDir, "sample.lua")

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

        // assets/classes.dex -> filesDir/classes.dex (advertised via jvm.classpath)
        dexCopy = File(filesDir, "classes.dex")
        if (!dexCopy.isFile) {
            runCatching {
                assets.open(DEX_ASSET).use { input ->
                    dexCopy.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure {
                dexCopy.delete()
                Log.i(TAG, "no $DEX_ASSET in assets; jvm.classpath omitted")
            }
        }
    }

    // ------------------------------------------------------------- LSP attach

    private suspend fun connectToLanguageServer(): Unit = withContext(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
            toast("Starting Lua language server...")
            // Read-only while the connection and initial workspace index build.
            editor.editable = false
        }

        val projectPath = projectDir.absolutePath

        // 1. Server side: bind the LocalSocket and launch one LuaLanguageServer
        //    per accepted connection.
        startService(Intent(this@MainActivity, LspServerService::class.java))

        // 2. Client side: sora LSP project + server definition over the same
        //    abstract socket name.
        lspProject = LspProject(projectPath)
        lspProject.addServerDefinition(createServerDefinition())

        withContext(Dispatchers.Main) {
            // 3. Editor bridge: LspEditor around the CodeEditor, TextMate as the
            //    wrapper language (highlighting) with LSP features layered on top.
            lspEditor = lspProject.createEditor("$projectPath/sample.lua")
            lspEditor.wrapperLanguage = createTextMateLanguage()
            lspEditor.editor = editor
        }

        var connected = false
        try {
            // 4. initialize/initialized handshake (retries until the service
            //    accepts the socket; throws TimeoutException on failure).
            lspEditor.connectWithTimeout()

            // 5. On CONNECTED: register the demo workspace folder (mirrors the
            //    sora sample; the server dedupes against initialize-time roots)
            //    and push the JVM interop configuration.
            lspEditor.requestManager?.didChangeWorkspaceFolders(
                DidChangeWorkspaceFoldersParams().apply {
                    event = WorkspaceFoldersChangeEvent().apply {
                        added = listOf(
                            WorkspaceFolder("file://$projectPath", "sample-project")
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

            connected = true
        } catch (failure: Exception) {
            Log.e(TAG, "unable to connect the Lua language server", failure)
        }

        withContext(Dispatchers.Main) {
            if (connected) {
                toast("Lua language server connected")
            } else {
                toast("Unable to connect the Lua language server")
            }
            editor.editable = true
        }
    }

    /**
     * The server definition. sora 0.23.6 form of (master):
     * `languageServerDefinition { ext("lua"); connection { local("lua-lsp") } }`.
     * `name` is not a constructor parameter at 0.23.6 (the wrapper is keyed by
     * extension alone), so only `ext` and the connect provider are set.
     */
    private fun createServerDefinition(): CustomLanguageServerDefinition {
        return CustomLanguageServerDefinition(
            "lua",
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
        if (this::dexCopy.isInitialized && dexCopy.isFile) {
            settings["jvm.classpath"] = dexCopy.absolutePath
        }
        return settings
    }

    private suspend fun setEditorText(): Unit = withContext(Dispatchers.Main) {
        val text = withContext(Dispatchers.IO) { sampleFile.readText() }
        // CHECK-API(sora): CodeEditor.setText(CharSequence) — the sora 0.23.6
        // sample uses editor.setText(ContentIO.createFrom(...), null); the plain
        // CharSequence overload has existed across the 0.23.x line.
        editor.setText(text)
    }

    // ----------------------------------------------------- TextMate rendering

    private fun createTextMateLanguage(): TextMateLanguage {
        // Grammar must be resolvable through FileProviderRegistry, which was
        // pointed at assets in ensureTextmateTheme() (same order as the sora sample).
        GrammarRegistry.getInstance().loadGrammars(
            languages {
                language("lua") {
                    grammar = "textmate/lua/syntaxes/lua.tmLanguage.json"
                    scopeName = "source.lua"
                    languageConfiguration = "textmate/lua/language-configuration.json"
                }
            }
        )
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
        val themePath = "textmate/lua-dark.json"
        themeRegistry.loadTheme(
            ThemeModel(
                IThemeSource.fromInputStream(
                    FileProviderRegistry.getInstance().tryGetInputStream(themePath),
                    themePath,
                    null
                ),
                "lua-dark"
            )
        )
        themeRegistry.setTheme("lua-dark")

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
        val disposeScope = CoroutineScope(Dispatchers.IO)
        if (this::lspEditor.isInitialized) {
            disposeScope.launch { lspEditor.dispose() }
        }
        if (this::lspProject.isInitialized) {
            disposeScope.launch { lspProject.dispose() }
        }
        stopService(Intent(this, LspServerService::class.java))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "MainActivity"
        const val ANDROID_JAR_ASSET = "android.jar"
        const val DEX_ASSET = "classes.dex"

        /** Lua files copied from assets into the demo project dir on first run. */
        val LUA_ASSETS = listOf("sample.lua", "sample_module.lua")
    }
}
