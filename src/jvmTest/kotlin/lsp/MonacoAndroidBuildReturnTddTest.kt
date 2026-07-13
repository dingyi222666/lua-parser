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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Monaco android_sample: unannotated local `build` that returns `tv = TextView()` after
 * `import "android.widget.*"` must surface a TextView-ish return type on hover of `build`
 * and on the call result `local view = build()`.
 */
class MonacoAndroidBuildReturnTddTest {
    private val androidJar: File? = resolveAndroidJar()

    @Test
    fun hover_on_build_surfaces_textview_return_not_unknown() {
        Assume.assumeTrue("host android.jar required", androidJar != null && androidJar!!.isFile)
        val root = Files.createTempDirectory("monaco-android-build-return-")
        val source = DEMO_SOURCE
        write(root, "android_sample.lua", source)
        val jarPath = androidJar!!.absolutePath
        val service = openService(root, jarPath, source)

        val hover = assertNotNull(
            service.hover(hoverParams(root, source, "build", occurrence = 1)),
            "expected hover on local function build"
        )
        val markup = hoverMarkup(hover)
        println("HOVER build markup=$markup")
        assertTrue(
            markup.contains("TextView", ignoreCase = true) ||
                markup.contains("android.widget.TextView"),
            "expected build return TextView-ish; got: $markup"
        )
        assertTrue(
            !markup.contains("fun(): unknown") &&
                !Regex(""":\s*unknown\b""").containsMatchIn(markup),
            "build must not hover as fun(): unknown; got: $markup"
        )
    }

    @Test
    fun call_result_view_dot_surfaces_textview_members() {
        Assume.assumeTrue("host android.jar required", androidJar != null && androidJar!!.isFile)
        val root = Files.createTempDirectory("monaco-android-build-call-")
        val source = """
            require "import"
            import "android.widget.*"
            local function build()
                local tv = TextView()
                return tv
            end
            local view = build()
            view.
        """.trimIndent() + "\n"
        write(root, "android_sample.lua", source)
        val jarPath = androidJar!!.absolutePath
        val service = openService(root, jarPath, source)
        val uri = root.resolve("android_sample.lua").toUri().toString()
        val tds = LuaTextDocumentService(
            languageService = service,
            requestPolicy = { LspTextDocumentRequestPolicy.Accept }
        )
        tds.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
        val pos = positionAfter(source, "view.")
        val either = tds.completion(
            CompletionParams(
                TextDocumentIdentifier(uri),
                pos,
                CompletionContext(CompletionTriggerKind.TriggerCharacter, ".")
            )
        ).get()
        val items = if (either.isRight) either.right.items else either.left
        val labels = items.map { it.label }
        println("VIEW. labels count=${labels.size} sample=${labels.take(20)}")
        assertTrue(
            labels.any { it == "setText" || it == "setVisibility" || it == "getText" },
            "call result of build() should be TextView instance; got ${labels.take(40)}"
        )
    }

    @Test
    fun hover_on_tv_local_surfaces_textview() {
        Assume.assumeTrue("host android.jar required", androidJar != null && androidJar!!.isFile)
        val root = Files.createTempDirectory("monaco-android-tv-hover-")
        val source = DEMO_SOURCE
        write(root, "android_sample.lua", source)
        val jarPath = androidJar!!.absolutePath
        val service = openService(root, jarPath, source)
        // second "tv" is the return identifier (`return tv`)
        val hover = assertNotNull(
            service.hover(hoverParams(root, source, "tv", occurrence = 2)),
            "expected hover on return tv"
        )
        val markup = hoverMarkup(hover)
        println("HOVER tv markup=$markup")
        assertTrue(
            markup.contains("TextView", ignoreCase = true) ||
                markup.contains("android.widget.TextView"),
            "tv local should be TextView; got: $markup"
        )
    }

    private fun openService(root: Path, jarPath: String, source: String): LuaLanguageService {
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
        return service
    }

    private fun hoverParams(
        root: Path,
        source: String,
        needle: String,
        occurrence: Int
    ): HoverParams {
        val uri = root.resolve("android_sample.lua").toUri().toString()
        return HoverParams(TextDocumentIdentifier(uri), positionOf(source, needle, occurrence))
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

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var idx = -1
        var from = 0
        repeat(occurrence) {
            idx = source.indexOf(needle, from)
            require(idx >= 0) { "missing occurrence $occurrence of $needle" }
            from = idx + needle.length
        }
        return offsetToPosition(source, idx)
    }

    private fun positionAfter(source: String, needle: String): Position {
        val idx = source.lastIndexOf(needle)
        require(idx >= 0) { "missing $needle" }
        return offsetToPosition(source, idx + needle.length)
    }

    private fun offsetToPosition(source: String, offset: Int): Position {
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

    private fun hoverMarkup(hover: Hover): String {
        val contents = hover.contents ?: return ""
        return when {
            contents.isRight -> contents.right?.value.orEmpty()
            contents.isLeft -> contents.left.orEmpty().joinToString("\n") { either ->
                when {
                    either.isRight -> either.right?.value.orEmpty()
                    either.isLeft -> either.left?.toString().orEmpty()
                    else -> ""
                }
            }
            else -> hover.toString()
        }
    }

    companion object {
        private val DEMO_SOURCE = """
            --- Android-Lua-ish sample (needs jvm.androidJar + imports for full surface).
            require "import"
            import "android.widget.*"

            local function build()
                local tv = TextView()
                local button = Button()
                return tv
            end

            return {
                build = build,
            }
        """.trimIndent() + "\n"
    }
}
