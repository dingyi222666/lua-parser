package io.github.dingyi222666.luaparser.sample

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import io.github.rosemoe.sora.lsp.editor.LspEditor
import io.github.rosemoe.sora.lsp.events.document.DocumentChangeEvent
import io.github.rosemoe.sora.lsp.utils.createTextDocumentIdentifier
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.component.DefaultCompletionLayout
import io.github.rosemoe.sora.lang.completion.CompletionItem
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Drives the shipped editor and its real LocalSocket LSP connection. */
@RunWith(AndroidJUnit4::class)
class EditorE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val budget = InstrumentationRegistry.getArguments().getString("readyBudgetMs")?.toLong() ?: 10_000L
    @get:org.junit.Rule
    val methodSampling = object : org.junit.rules.TestWatcher() {
        private var tracing = false

        override fun starting(description: org.junit.runner.Description) {
            if (description.methodName != "openCompleteEditSwitchFormatSaveAndRecreate") return
            val path = InstrumentationRegistry.getArguments().getString("methodTrace") ?: return
            File(path).parentFile?.mkdirs()
            android.os.Debug.startMethodTracingSampling(path, 32 * 1024 * 1024, 5000)
            tracing = true
        }

        override fun finished(description: org.junit.runner.Description) {
            if (tracing) android.os.Debug.stopMethodTracing()
            tracing = false
        }
    }

    @Test
    fun openBuffersKeepDependencyCompletionCurrentAcrossFileSwitches() {
        val project = ProjectBootstrapper.ensureWorkspace(instrumentation.targetContext)
        val helper = File(project, "e2e_open_helper.lua")
        val consumer = File(project, "e2e_open_consumer.lua")
        helper.writeText("return { fromOpenDependency = 1 }")
        consumer.writeText("local value = require 'e2e_open_helper'\nvalue.")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("connected editor for dependent buffers", budget) {
                    onActivity(scenario) { bridge(it)?.isConnected == true }
                }
                open(scenario, helper.name)
                open(scenario, consumer.name)
                assertTrue("open dependency must appear in completion",
                    "fromOpenDependency" in complete(scenario, 1, 6))
                open(scenario, helper.name)
                onActivity(scenario) {
                    it.findViewById<CodeEditor>(R.id.code_editor).setText("return { editedDependencyMember = 1 }")
                }
                open(scenario, consumer.name)
                await("unsaved dependency edit replaces completion", budget) {
                    val labels = complete(scenario, 1, 6)
                    "editedDependencyMember" in labels && "fromOpenDependency" !in labels
                }
                assertEquals("dependency edit must remain unsaved on disk",
                    "return { fromOpenDependency = 1 }", helper.readText())
            }
        } finally {
            helper.delete()
            consumer.delete()
        }
    }

    @Test
    fun openCompleteEditSwitchFormatSaveAndRecreate() {
        System.setProperty("lua.parser.perf", "true")
        val started = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("text visible", budget) {
                onActivity(scenario) {
                    it.findViewById<MaterialToolbar>(R.id.toolbar).subtitle == "main.lua" &&
                        it.findViewById<CodeEditor>(R.id.code_editor).text.length > 0
                }
            }
            measure("textVisible", started)
            val startupEdit = "\n-- edit made while the workspace starts\n"
            onActivity(scenario) {
                val view = it.findViewById<CodeEditor>(R.id.code_editor)
                assertTrue(view.isEditable)
                val text = view.text
                text.insert(text.lineCount - 1, text.getColumnCount(text.lineCount - 1), startupEdit)
            }
            await("connected editable editor", budget) {
                onActivity(scenario) {
                    bridge(it)?.isConnected == true && it.findViewById<CodeEditor>(R.id.code_editor).isEditable
                }
            }
            measure("editorReady", started)
            assertTrue(onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).text.toString().endsWith(startupEdit)
            })

            // This is the real corpus file, including its custom table.addObserver
            // definition. Both custom and builtin results must be returned.
            open(scenario, "mods/dingyi.lua")
            val source = onActivity(scenario) { it.findViewById<CodeEditor>(R.id.code_editor).text.toString() }
            onActivity(scenario) {
                val text = it.findViewById<CodeEditor>(R.id.code_editor).text
                val lastLine = text.lineCount - 1
                text.insert(lastLine, text.getColumnCount(lastLine), "\ntable.\n")
            }
            val line = source.count { it == '\n' } + 1
            val completionStarted = SystemClock.elapsedRealtime()
            await("custom and builtin completion", budget) {
                val labels = complete(scenario, line, 6)
                labels.containsAll(listOf("addObserver", "insert"))
            }
            measure("firstValidCompletion", completionStarted)
            repeat(5) {
                val t = SystemClock.elapsedRealtime()
                assertTrue(complete(scenario, line, 6).containsAll(listOf("addObserver", "insert")))
                measure("warmCompletion$it", t)
            }

            val editStarted = SystemClock.elapsedRealtime()
            onActivity(scenario) {
                val text = it.findViewById<CodeEditor>(R.id.code_editor).text
                val definition = text.toString().indexOf("table.addObserver=")
                assertTrue(definition >= 0)
                val offset = definition + "table.".length
                val start = text.indexer.getCharPosition(offset)
                val end = text.indexer.getCharPosition(offset + "addObserver".length)
                text.replace(start.line, start.column, end.line, end.column, "observeChange")
            }
            await("incremental completion replaces the old member", budget) {
                val labels = complete(scenario, line, 6)
                "observeChange" in labels && "addObserver" !in labels && "insert" in labels
            }
            measure("incrementalCompletion", editStarted)

            val popupLayout = DefaultCompletionLayout()
            onActivity(scenario) {
                val view = it.findViewById<CodeEditor>(R.id.code_editor)
                view.setSelection(line, 6)
                view.getComponent(EditorAutoCompletion::class.java).setLayout(popupLayout)
            }
            SystemClock.sleep(350)
            val popupStarted = SystemClock.elapsedRealtime()
            onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor)
                    .getComponent(EditorAutoCompletion::class.java).requireCompletion()
            }
            await("visible completion popup has custom and builtin items", budget) {
                onActivity(scenario) {
                    val popup = it.findViewById<CodeEditor>(R.id.code_editor).getComponent(EditorAutoCompletion::class.java)
                    val adapter = popupLayout.completionList.adapter
                    popup.isShowing && adapter != null && (0 until adapter.count)
                        .map { index -> (adapter.getItem(index) as CompletionItem).label.toString() }
                        .containsAll(listOf("observeChange", "insert"))
                }
            }
            measure("visibleCompletionPopup", popupStarted)
            onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).getComponent(EditorAutoCompletion::class.java).hide()
            }

            val diagnosticsStarted = SystemClock.elapsedRealtime()
            onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).text.insert(0, 0, "local neverReadValue = 1; ")
            }
            await("checker warning delivered to the visible editor", budget) {
                onActivity(scenario) {
                    @Suppress("UNCHECKED_CAST")
                    val diagnostics = MainActivity::class.java.getDeclaredField("latestDiagnostics")
                        .apply { isAccessible = true }.get(it) as List<Diagnostic>
                    diagnostics.any { diagnostic -> diagnostic.message.contains("neverReadValue") }
                }
            }
            measure("semanticDiagnosticsAfterEdit", diagnosticsStarted)

            open(scenario, "main.lua")
            assertTrue(onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).text.toString().endsWith(startupEdit)
            })
            open(scenario, "mods/dingyi.lua")
            assertTrue(onActivity(scenario) {
                val buffer = it.findViewById<CodeEditor>(R.id.code_editor).text.toString()
                "table.observeChange=" in buffer && "table.addObserver=" !in buffer
            })
            assertTrue(complete(scenario, line, 6).containsAll(listOf("observeChange", "insert")))
            await("highlight restored after returning to document", budget) {
                onActivity(scenario) {
                    it.findViewById<CodeEditor>(R.id.code_editor).styles?.spans?.read()
                        ?.getSpansOnLine(0)?.any { span -> span.style == EditorColorScheme.LITERAL.toLong() } == true
                }
            }
            open(scenario, "main.lua")
            open(scenario, "layout/main.aly")
            open(scenario, "main.lua")
            onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).setText("local value=1\nprint(value)\n")
                it.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_format, 0)
            }
            await("formatted text", budget) {
                onActivity(scenario) { it.findViewById<CodeEditor>(R.id.code_editor).text.toString().contains("value = 1") }
            }
            onActivity(scenario) {
                it.findViewById<MaterialToolbar>(R.id.toolbar).menu.performIdentifierAction(R.id.action_save, 0)
            }
            await("saved buffer", budget) {
                val context = instrumentation.targetContext
                File(context.filesDir, "project/main.lua").readText().contains("value = 1")
            }
            scenario.recreate()
            await("recreated connected editor", budget) {
                onActivity(scenario) { bridge(it)?.isConnected == true }
            }
            assertTrue(onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).text.toString().contains("value = 1")
            })
        }
    }

    @Test
    fun javaAndLuaMemberCompletionSupportsColonAndFluentNewlineDots() {
        val launchStarted = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("initial document text", budget) {
                onActivity(scenario) { it.findViewById<CodeEditor>(R.id.code_editor).text.length > 0 }
            }
            measure("javaFixtureTextVisible", launchStarted)
            await("connected editable editor", budget) {
                onActivity(scenario) {
                    bridge(it)?.isConnected == true &&
                        it.findViewById<CodeEditor>(R.id.code_editor).isEditable
                }
            }
            measure("javaFixtureEditorReady", launchStarted)
            onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).setText(
                    """import "android.graphics.drawable.GradientDrawable"
local roundDrawable=GradientDrawable()
.setShape(GradientDrawable.RECTANGLE)
.setCornerRadius(16)
.setColor(0xffffffff)
.
.setStroke(2,0x1f000000)
""".trimIndent()
                )
            }
            var drawableLabels = emptyList<String>()
            val drawableStarted = SystemClock.elapsedRealtime()
            await("newline-dot Java chain completion", budget) {
                drawableLabels = complete(scenario, 5, 1)
                drawableLabels.isNotEmpty()
            }
            assertTrue("newline-dot Java chain completion: $drawableLabels", drawableLabels.contains("setStroke"))
            assertTrue("newline-dot receiver surface: $drawableLabels", drawableLabels.contains("setColor"))
            measure("drawableCompletion", drawableStarted)
            assertPopup(scenario, 5, 1, listOf("setStroke", "setColor"))

            onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).setText(
                    """---@class Receiver
---@method Receiver:refresh(): string
---@type Receiver
local receiver = {}
receiver:
""".trimIndent()
                )
            }
            var colonLabels = emptyList<String>()
            val colonStarted = SystemClock.elapsedRealtime()
            await("colon member completion", budget) {
                colonLabels = complete(scenario, 4, 9)
                colonLabels.isNotEmpty()
            }
            assertTrue("colon member completion: $colonLabels", colonLabels.contains("refresh"))
            measure("colonCompletion", colonStarted)
            assertPopup(scenario, 4, 9, listOf("refresh"))
            onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).text.insert(4, 9, "ref")
            }
            await("partially typed colon member", budget) {
                "refresh" in complete(scenario, 4, 12)
            }
            assertPopup(scenario, 4, 12, listOf("refresh"))

            onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).setText(
                    "activity.actionBar.hide()\nactivity.actionBar.elevation=1\nlocal unusedActionBarTest = 1"
                )
            }
            var actionBarLabels = emptyList<String>()
            val actionBarStarted = SystemClock.elapsedRealtime()
            await("actionBar JavaBean completion", budget) {
                actionBarLabels = complete(scenario, 0, 19)
                actionBarLabels.isNotEmpty()
            }
            assertTrue("actionBar JavaBean member completion: $actionBarLabels",
                actionBarLabels.contains("hide") && actionBarLabels.contains("show"))
            measure("actionBarCompletion", actionBarStarted)
            repeat(3) {
                val warmStarted = SystemClock.elapsedRealtime()
                assertTrue(complete(scenario, 0, 19).containsAll(listOf("hide", "show")))
                measure("actionBarWarm$it", warmStarted)
            }
            var diagnostics = emptyList<Diagnostic>()
            await("diagnostics for the current actionBar buffer", budget) {
                diagnostics = onActivity(scenario) {
                    @Suppress("UNCHECKED_CAST")
                    MainActivity::class.java.getDeclaredField("latestDiagnostics")
                        .apply { isAccessible = true }.get(it) as List<Diagnostic>
                }
                diagnostics.any { it.message.contains("unusedActionBarTest") }
            }
            assertTrue("activity.actionBar diagnostics: $diagnostics",
                diagnostics.none { it.message.contains("Unknown Java member") })
        }
    }

    @Test
    fun completionDetailsSelectOverloadsAndDistinguishVoidFromNilAfterEdits() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("connected editable editor", budget) {
                onActivity(scenario) {
                    bridge(it)?.isConnected == true && it.findViewById<CodeEditor>(R.id.code_editor).isEditable
                }
            }
            val source = """local API = {}
---@param value number
---@return number
---@overload fun(value: string): string
function API.convert(value) return value end
local function idle() end
local function nullValue() return nil end
API.convert('item')
idle()
"""
            onActivity(scenario) { it.findViewById<CodeEditor>(R.id.code_editor).setText(source) }
            await("completion chooses string overload", budget) {
                completeItems(scenario, 7, 6).singleOrNull { it.label == "convert" }?.detail == "fun(value: string): string"
            }
            onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).text.replace(7, 12, 7, 18, "42")
            }
            await("edited argument selects number overload", budget) {
                completeItems(scenario, 7, 6).singleOrNull { it.label == "convert" }?.detail == "fun(value: number): number"
            }
            val lexical = completeItems(scenario, 8, 0)
            assertEquals("fun(): void", lexical.single { it.label == "idle" }.detail)
            assertEquals("fun(): nil", lexical.single { it.label == "nullValue" }.detail)
            assertTrue("completion must not concatenate overloads with &",
                lexical.none { it.detail?.contains(" & ") == true })
        }
    }

    @Test
    fun dynamicallyImportedDexCompletesJavaObjectsInsideLuaTables() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("connected editor for runtime dex import", budget) {
                onActivity(scenario) { bridge(it)?.isConnected == true }
            }
            val library = File(instrumentation.targetContext.filesDir, "project/libs/runtime-e2e.dex")
            library.delete()
            onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).setText(
                    "local Greeter=luajava.bindClass('fixture.dynamic.Greeter')\nGreeter."
                )
            }
            assertFalse("unimported fixture must not already exist in the classpath",
                "libraryMarker" in complete(scenario, 1, 8))
            library.parentFile!!.mkdirs()
            instrumentation.context.assets.open("runtime-greeter.dex").use { input ->
                library.outputStream().use { output -> input.copyTo(output) }
            }
            val prefix = "import '${library.absolutePath}'\n" +
                "local Greeter=luajava.bindClass('fixture.dynamic.Greeter')\n"
            val started = SystemClock.elapsedRealtime()
            onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).setText(prefix + "Greeter.")
            }
            await("runtime dex static completion", budget) {
                complete(scenario, 2, 8).containsAll(listOf("create", "libraryMarker"))
            }
            measure("dynamicDexCompletion", started)
            assertPopup(scenario, 2, 8, listOf("create", "libraryMarker"))
            onActivity(scenario) {
                it.findViewById<CodeEditor>(R.id.code_editor).setText(
                    prefix + "local holder={value=Greeter.create()}\nholder.value:"
                )
            }
            await("Java return value in a Lua table", budget) {
                "greet" in complete(scenario, 3, 13)
            }
            assertPopup(scenario, 3, 13, listOf("greet"))
            onActivity(scenario) {
                val text = it.findViewById<CodeEditor>(R.id.code_editor).text
                text.replace(3, 13, 3, 13, "gre")
            }
            await("incremental partial dex member completion", budget) {
                "greet" in complete(scenario, 3, 16)
            }
            assertPopup(scenario, 3, 16, listOf("greet"))
        }
    }

    private fun assertPopup(scenario: ActivityScenario<MainActivity>, line: Int, column: Int, expected: List<String>) {
        await("completion source ready for popup: $expected", budget) {
            complete(scenario, line, column).containsAll(expected)
        }
        val layout = DefaultCompletionLayout()
        onActivity(scenario) {
            val view = it.findViewById<CodeEditor>(R.id.code_editor)
            view.setSelection(line, column)
            view.getComponent(EditorAutoCompletion::class.java).setLayout(layout)
        }
        SystemClock.sleep(350)
        onActivity(scenario) {
            it.findViewById<CodeEditor>(R.id.code_editor).getComponent(EditorAutoCompletion::class.java).requireCompletion()
        }
        await("member completion popup: $expected", budget) {
            onActivity(scenario) {
                val popup = it.findViewById<CodeEditor>(R.id.code_editor).getComponent(EditorAutoCompletion::class.java)
                val adapter = layout.completionList.adapter
                popup.isShowing && adapter != null && (0 until adapter.count)
                    .map { index -> (adapter.getItem(index) as CompletionItem).label.toString() }
                    .containsAll(expected)
            }
        }
        onActivity(scenario) {
            it.findViewById<CodeEditor>(R.id.code_editor).getComponent(EditorAutoCompletion::class.java).hide()
        }
    }

    private fun open(scenario: ActivityScenario<MainActivity>, path: String) {
        val started = SystemClock.elapsedRealtime()
        onActivity(scenario) { it.onFileSelected(path) }
        await("open $path", budget) {
            onActivity(scenario) {
                it.findViewById<MaterialToolbar>(R.id.toolbar).subtitle == path &&
                    bridge(it)?.isConnected == true && it.findViewById<CodeEditor>(R.id.code_editor).isEditable
            }
        }
        measure("switch:$path", started)
    }

    private fun complete(scenario: ActivityScenario<MainActivity>, line: Int, column: Int): List<String> {
        return completeItems(scenario, line, column).map { it.label }
    }

    private fun completeItems(scenario: ActivityScenario<MainActivity>, line: Int, column: Int): List<org.eclipse.lsp4j.CompletionItem> {
        val pending = onActivity(scenario) { activity ->
            bridge(activity)?.let { it to it.eventManager.getEventListener(DocumentChangeEvent::class.java)?.future }
        } ?: return emptyList()
        // Direct RequestManager calls must observe the same edit barrier as the UI completion path.
        pending.second?.get(budget, TimeUnit.MILLISECONDS)
        val editor = pending.first
        val result = editor.requestManager?.completion(CompletionParams(
            editor.uri.createTextDocumentIdentifier(), Position(line, column)
        ))?.get(budget, TimeUnit.MILLISECONDS) ?: return emptyList()
        return if (result.isLeft) result.left else result.right.items
    }

    private fun bridge(activity: MainActivity): LspEditor? = runCatching {
        MainActivity::class.java.getDeclaredField("lspEditor").apply { isAccessible = true }.get(activity) as? LspEditor
    }.getOrNull()

    private fun <T> onActivity(scenario: ActivityScenario<MainActivity>, block: (MainActivity) -> T): T {
        val result = AtomicReference<T>()
        scenario.onActivity { result.set(block(it)) }
        return result.get()
    }

    private fun await(label: String, timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        fail("Timed out after ${timeout}ms: $label")
    }

    private fun measure(label: String, started: Long) {
        Log.i("AndroidE2E", "$label=${SystemClock.elapsedRealtime() - started}ms")
    }
}
