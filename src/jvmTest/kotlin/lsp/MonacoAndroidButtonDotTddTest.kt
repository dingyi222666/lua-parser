package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import io.github.dingyi222666.luaparser.lsp.LspTextDocumentRequestPolicy
import org.eclipse.lsp4j.*
import org.junit.Assume
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Monaco android_sample.lua lock for incomplete `button.` after
 * `import "android.widget.*"` + Button() construction.
 */
class MonacoAndroidButtonDotTddTest {
    private val androidJar: File? = resolveAndroidJar()

    @Test
    fun button_dot_after_widget_wildcard_import_surfaces_view_members_via_tds() {
        Assume.assumeTrue(
            "host android.jar required for Button member surface",
            androidJar != null && androidJar!!.isFile
        )
        val root = Files.createTempDirectory("monaco-android-button-")
        val source = DEMO_SOURCE
        write(root, "android_sample.lua", source)

        val jarPath = androidJar!!.absolutePath
        val service = LuaLanguageService(
            JvmWorkspaceEngine(
                configuration = JvmWorkspaceConfiguration(androidJar = jarPath)
            )
        )
        service.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "monaco-lsp-demo"))
        })
        LuaWorkspaceService(service).didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "jvm.androidJar" to jarPath,
                    "jvm.importPrefixes" to listOf(
                        "java.lang",
                        "android.app",
                        "android.content",
                        "android.view",
                        "android.widget"
                    ),
                    "androlua.imports" to listOf("TextView", "Button", "LinearLayout")
                )
            )
        )

        val uri = root.resolve("android_sample.lua").toUri().toString()
        val tds = LuaTextDocumentService(
            languageService = service,
            requestPolicy = { LspTextDocumentRequestPolicy.Accept }
        )
        tds.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))

        val pos = positionAfter(source, "button.")
        println("TDS uri=$uri pos=$pos jar=$jarPath")
        val either = tds.completion(
            CompletionParams(
                TextDocumentIdentifier(uri),
                pos,
                CompletionContext(CompletionTriggerKind.TriggerCharacter, ".")
            )
        ).get()
        val items = if (either.isRight) either.right.items else either.left
        val labels = items.map { it.label }
        println("TDS BUTTON labels count=${labels.size} sample=${labels.take(25)}")
        assertTrue(
            labels.any {
                it == "setText" || it == "setOnClickListener" || it == "setVisibility" || it == "getText"
            },
            "expected Button/View members after button.; got first=${labels.take(40)}"
        )
    }

    @Test
    fun button_dot_via_language_service_path_surfaces_view_members() {
        Assume.assumeTrue("host android.jar required", androidJar != null && androidJar!!.isFile)
        val root = Files.createTempDirectory("monaco-android-button-ls-")
        val source = DEMO_SOURCE
        write(root, "android_sample.lua", source)
        val jarPath = androidJar!!.absolutePath
        val service = LuaLanguageService(
            JvmWorkspaceEngine(
                configuration = JvmWorkspaceConfiguration(androidJar = jarPath)
            )
        )
        service.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "ws"))
        })
        LuaWorkspaceService(service).didChangeConfiguration(
            DidChangeConfigurationParams(mapOf("jvm.androidJar" to jarPath))
        )
        val path = "android_sample.lua"
        val uri = root.resolve("android_sample.lua").toUri().toString()
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
        val pos = positionAfter(source, "button.")
        val labels = service.completion(path, pos.line, pos.character).items.map { it.label }
        println("LS labels count=${labels.size} sample=${labels.take(25)}")
        assertTrue(
            labels.any { it == "setText" || it == "setOnClickListener" || it == "setVisibility" },
            "LS expected Button members; got ${labels.take(40)}"
        )
    }

    private fun resolveAndroidJar(): File? {
        val candidates = listOfNotNull(
            System.getenv("ANDROID_JAR"),
            System.getProperty("luaparser.android.jar"),
            System.getenv("ANDROID_HOME")?.let { "$it/platforms/android-35/android.jar" },
            System.getenv("ANDROID_HOME")?.let { "$it/platforms/android-34/android.jar" },
            System.getProperty("user.home") + "/Library/Android/sdk/platforms/android-35/android.jar",
            System.getProperty("user.home") + "/Library/Android/sdk/platforms/android-34/android.jar",
            runCatching { JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH }.getOrNull()
        )
        return candidates.map { File(it) }.firstOrNull { it.isFile }
    }

    private fun write(root: Path, name: String, source: String) {
        root.resolve(name).parent?.createDirectories()
        root.resolve(name).writeText(source)
    }

    private fun positionAfter(source: String, needle: String): Position {
        val idx = source.lastIndexOf(needle)
        require(idx >= 0) { "needle $needle missing" }
        val offset = idx + needle.length
        var line = 0
        var lineStart = 0
        for (i in 0 until offset) {
            if (source[i] == '\n') {
                line += 1
                lineStart = i + 1
            }
        }
        return Position(line, offset - lineStart)
    }


    @Test
    fun button_dot_explicit_import_surfaces_setText() {
        Assume.assumeTrue("host android.jar required", androidJar != null && androidJar!!.isFile)
        val root = Files.createTempDirectory("monaco-android-button-explicit-")
        val source = """
            require "import"
            import "android.widget.Button"
            import "android.widget.TextView"
            local function build()
                local button = Button()
                button.
                return button
            end
            return build
        """.trimIndent() + "\n"
        write(root, "android_sample.lua", source)
        val jarPath = androidJar!!.absolutePath
        val service = LuaLanguageService(
            JvmWorkspaceEngine(
                configuration = JvmWorkspaceConfiguration(androidJar = jarPath)
            )
        )
        service.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "ws"))
        })
        LuaWorkspaceService(service).didChangeConfiguration(
            DidChangeConfigurationParams(mapOf("jvm.androidJar" to jarPath))
        )
        val uri = root.resolve("android_sample.lua").toUri().toString()
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
        val pos = positionAfter(source, "button.")
        val labels = service.completion("android_sample.lua", pos.line, pos.character).items.map { it.label }
        println("EXPLICIT labels count=${labels.size} sample=${labels.take(30)}")
        assertTrue(labels.any { it == "setText" || it == "setVisibility" }, "explicit got ${labels.take(40)}")
    }

    @Test
    fun button_colon_setText_mid_token_surfaces() {
        Assume.assumeTrue("host android.jar required", androidJar != null && androidJar!!.isFile)
        val root = Files.createTempDirectory("monaco-android-button-colon-")
        val source = """
            require "import"
            import "android.widget.*"
            local function build()
                local button = Button()
                button:setText("x")
                return button
            end
            return build
        """.trimIndent() + "\n"
        write(root, "android_sample.lua", source)
        val jarPath = androidJar!!.absolutePath
        val service = LuaLanguageService(
            JvmWorkspaceEngine(configuration = JvmWorkspaceConfiguration(androidJar = jarPath))
        )
        service.initialize(InitializeParams().apply {
            workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "ws"))
        })
        LuaWorkspaceService(service).didChangeConfiguration(
            DidChangeConfigurationParams(mapOf("jvm.androidJar" to jarPath))
        )
        val uri = root.resolve("android_sample.lua").toUri().toString()
        service.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
        val pos = positionAfter(source, "button:")
        // caret mid setText
        val mid = source.indexOf("setText") + 3
        var line=0; var ls=0
        for (i in 0 until mid) { if (source[i]=='\n'){line++; ls=i+1} }
        val labels = service.completion("android_sample.lua", line, mid-ls).items.map { it.label }
        println("COLON mid labels count=${labels.size} has setText=${"setText" in labels} sample=${labels.take(20)}")
        assertTrue("setText" in labels || labels.any { it.startsWith("set") }, "colon mid got ${labels.take(40)}")
    }

    companion object {
        private val DEMO_SOURCE = """
            --- Android-Lua-ish sample (needs jvm.androidJar + imports for full surface).
            require "import"
            import "android.widget.*"

            local function build()
                local tv = TextView()
                local button = Button()
                button.
                -- Member completion / hover when android.jar is configured
                return tv
            end

            return {
                build = build,
            }
        """.trimIndent() + "\n"
    }
}
