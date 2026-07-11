package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceSymbolParams
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration

class LspJavaAndroidFeatureTddTest {
    private val androidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

    @Test
    fun java_static_member_lsp_features_resolve_from_configured_jvm_provider() {
        val service = jvmService()
        val document = service.open(
            "workspace/java-arrays.lua",
            """
            local Arrays = require("Arrays")
            local first = Arrays.asList("one", "two")
            local second = Arrays.asList("three")
            return first, second
            """
        )

        val hover = assertNotNull(service.hover(hoverParams(document, "asList", occurrence = 1)))
        val completions = service.completionAt(document, "asList", occurrence = 1)
        val signatureHelp = assertNotNull(service.signatureHelp(signatureParams(document, "\"two\"")))
        val definition = service.definition(definitionParams(document, "asList", occurrence = 1))
        val references = service.references(referenceParams(document, "asList", occurrence = 1))
        val documentSymbols = service.documentSymbols(document.path).map { it.name }
        val workspaceSymbols = service.workspaceSymbols("asList")

        assertTrue(hover.markup.contains("asList"))
        assertCompletion(completions, "asList")
        assertTrue(signatureHelp.signatures.isNotEmpty(), "Expected reflected Java method signature help.")
        assertTrue(signatureHelp.signatures.any { it.label.contains("asList") || it.label.contains("fun(") })
        assertEquals(javaProviderUri("java.util.Arrays"), definition.single().uri)
        assertTrue(references.any { it.uri == javaProviderUri("java.util.Arrays") })
        assertEquals(2, references.count { it.uri == document.uri })
        assertTrue("Arrays" in documentSymbols)
        assertTrue("first" in documentSymbols)
        assertTrue(workspaceSymbols.any { it.name == "asList" && it.location.uri == javaProviderUri("java.util.Arrays") })
    }

    @Test
    fun android_import_table_lsp_features_resolve_class_static_field_and_member_methods() {
        val service = androidService()
        val document = service.openFixture("main_activity.lua")

        val classHover = assertNotNull(service.hover(hoverParams(document, "TextView(context)", offset = 2)))
        val fieldHover = assertNotNull(service.hover(hoverParams(document, "WINDOW_SERVICE", offset = 4)))
        val memberCompletions = service.completionAt(document, "setText", offset = 3)
        val memberSignatureHelp = assertNotNull(service.signatureHelp(signatureParams(document, "\"Hello Android Lua\"")))
        val staticFieldDefinition = service.definition(definitionParams(document, "WINDOW_SERVICE", offset = 4))
        val classDefinition = service.definition(definitionParams(document, "TextView(context)", offset = 2))
        val classReferences = service.references(referenceParams(document, "TextView(context)", offset = 2))
        val documentSymbols = service.documentSymbols(document.path).map { it.name }
        val workspaceSymbols = service.workspaceSymbols("TextView")

        assertTrue(classHover.markup.contains("TextView"))
        assertTrue(fieldHover.markup.contains("WINDOW_SERVICE"))
        assertCompletion(memberCompletions, "setText")
        assertCompletion(memberCompletions, "getText")
        assertTrue(memberSignatureHelp.signatures.isNotEmpty(), "Expected Android TextView method signature help.")
        assertEquals(androidProviderUri("android.content.Context"), staticFieldDefinition.single().uri)
        assertEquals(androidProviderUri("android.widget.TextView"), classDefinition.single().uri)
        assertTrue(classReferences.any { it.uri == androidProviderUri("android.widget.TextView") })
        assertTrue(classReferences.any { it.uri == document.uri })
        assertTrue("buildTitle" in documentSymbols)
        assertTrue("TextViewClass" in documentSymbols)
        assertTrue(workspaceSymbols.any { it.name == "TextView" && it.location.uri == androidProviderUri("android.widget.TextView") })
    }

    @Test
    fun android_wildcard_layout_lsp_features_cover_ids_listener_and_cross_file_symbols() {
        val service = androidService()
        service.openFixture("main_activity.lua")
        val layout = service.openFixture("layout_screen.lua")
        val details = service.openFixture("details_fragment.lua")

        val wildcardCompletions = service.completionAt(layout, "TextView,", offset = 2)
        val idCompletions = service.completionAt(layout, "messageText:setText", offset = 4)
        val listenerHover = assertNotNull(service.hover(hoverParams(layout, "onClick", occurrence = 2, offset = 3)))
        val listenerDefinition = service.definition(definitionParams(layout, "onClick", occurrence = 2, offset = 3))
        val textViewReferences = service.references(referenceParams(layout, "TextView,", offset = 2))
        val layoutSymbols = service.documentSymbols(layout.path).map { it.name }
        val attachSymbols = service.workspaceSymbols("attach")

        assertCompletion(wildcardCompletions, "TextView")
        assertCompletion(wildcardCompletions, "Button")
        assertCompletion(idCompletions, "messageText")
        assertCompletion(idCompletions, "submitButton")
        assertTrue(listenerHover.markup.contains("onClick"))
        assertEquals(androidProviderUri("android.view.View\$OnClickListener"), listenerDefinition.single().uri)
        assertTrue(textViewReferences.any { it.uri == androidProviderUri("android.widget.TextView") })
        assertTrue(textViewReferences.any { it.uri == layout.uri })
        assertTrue(textViewReferences.any { it.uri == details.uri })
        assertTrue("layout" in layoutSymbols)
        assertTrue("click" in layoutSymbols)
        assertTrue(attachSymbols.any { it.name == "attach" && it.location.uri == details.uri })
    }

    @Test
    fun text_document_and_workspace_services_wrap_android_java_feature_queries_after_configuration() {
        assertAndroidJarExists()
        val languageService = initializedService()
        val textDocuments = LuaTextDocumentService(languageService)
        val workspace = LuaWorkspaceService(languageService)
        workspace.didChangeConfiguration(androidConfiguration())
        val document = textDocuments.open(
            "workspace/text-services-android.lua",
            """
            require "import"
            import "android.widget.TextView"
            import "android.content.Context"
            local view = TextView(activity)
            view:setText(Context.WINDOW_SERVICE)
            return view
            """
        )

        val memberPosition = document.positionOf("setText", offset = 3)
        val classPosition = document.positionOf("TextView(activity)", offset = 2)
        val hover = assertNotNull(textDocuments.hover(HoverParams(TextDocumentIdentifier(document.uri), memberPosition)).get())
        val completion = textDocuments.completion(CompletionParams(TextDocumentIdentifier(document.uri), memberPosition)).get().right
        val definition = textDocuments.definition(DefinitionParams(TextDocumentIdentifier(document.uri), classPosition)).get().left
        val references = textDocuments.references(
            ReferenceParams(TextDocumentIdentifier(document.uri), classPosition, ReferenceContext(true))
        ).get()
        val documentSymbols = textDocuments.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(document.uri))).get()
        val workspaceSymbols = workspace.symbol(WorkspaceSymbolParams("TextView")).get().left

        assertTrue(hover.markup.contains("setText"))
        assertCompletion(completion, "setText")
        assertEquals(androidProviderUri("android.widget.TextView"), definition.single().uri)
        assertTrue(references.any { it.uri == androidProviderUri("android.widget.TextView") })
        assertTrue(documentSymbols.any { it.left.name == "view" })
        assertTrue(workspaceSymbols.any { it.name == "TextView" && it.location.uri == androidProviderUri("android.widget.TextView") })
    }

    @Test
    fun language_server_lifecycle_rejects_java_android_queries_after_shutdown_exit() {
        val server = LuaLanguageServer()
        server.initialize(
            InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
            }
        ).get()
        server.workspaceService.didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "androlua.imports" to listOf("String"),
                    "jvm.importPrefixes" to listOf("java.lang")
                )
            )
        )
        val uri = "file:///workspace/lifecycle-java.lua"
        server.textDocumentService.didOpen(
            openParams(uri, "local current = String\nreturn current")
        )

        val beforeExitDefinition = server.textDocumentService.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(0, 16))
        ).get().left

        server.shutdown().get()
        server.exit()

        val ignoredHover = server.textDocumentService.hover(
            HoverParams(TextDocumentIdentifier(uri), Position(0, 16))
        ).get()
        val ignoredCompletion = server.textDocumentService.completion(
            CompletionParams(TextDocumentIdentifier(uri), Position(0, 16))
        ).get().right
        val ignoredDefinition = server.textDocumentService.definition(
            DefinitionParams(TextDocumentIdentifier(uri), Position(0, 16))
        ).get().left
        val rejectedWorkspaceSymbols = server.workspaceService.symbol(WorkspaceSymbolParams("String"))

        assertEquals(javaProviderUri("java.lang.String"), beforeExitDefinition.single().uri)
        assertEquals(null, ignoredHover)
        assertTrue(ignoredCompletion.items.isEmpty())
        assertTrue(ignoredDefinition.isEmpty())
        assertTrue(
            rejectedWorkspaceSymbols.isCompletedExceptionally,
            "Workspace symbol requests should be rejected once the server has exited."
        )
    }

    private fun jvmService(): LuaLanguageService {
        return initializedService().apply {
            setWorkspaceMetadata(
                mapOf(
                    JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                        "java.lang.String",
                        "java.lang.StringBuilder",
                        "java.util.Arrays",
                        "java.util.Locale"
                    ).joinToString("\n")
                )
            )
        }
    }

    private fun androidService(): LuaLanguageService {
        assertAndroidJarExists()
        return initializedService().also { service ->
            LuaWorkspaceService(service).didChangeConfiguration(androidConfiguration())
        }
    }

    private fun initializedService(): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
        }
    }

    private fun androidConfiguration(): DidChangeConfigurationParams {
        return DidChangeConfigurationParams(
            mapOf(
                "jvm.androidJar" to androidJar.path,
                "jvm.importPrefixes" to listOf(
                    "java.lang",
                    "java.util",
                    "android.app",
                    "android.content",
                    "android.view",
                    "android.view.View",
                    "android.widget"
                ),
                "androlua.imports" to listOf(
                    "Activity",
                    "Context",
                    "View",
                    "OnClickListener",
                    "TextView",
                    "Button",
                    "LinearLayout"
                )
            )
        )
    }

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(openParams(document.uri, document.source))
        return document
    }

    private fun LuaTextDocumentService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(openParams(document.uri, document.source))
        return document
    }

    private fun LuaLanguageService.openFixture(name: String): OpenDocument {
        return open(pathFor(name), fixture(name))
    }

    private fun LuaLanguageService.completionAt(
        document: OpenDocument,
        needle: String,
        occurrence: Int = 1,
        offset: Int = 0
    ): org.eclipse.lsp4j.CompletionList {
        val position = document.positionOf(needle, occurrence, offset)
        return completion(document.path, position.line, position.character)
    }

    private fun hoverParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): HoverParams {
        return HoverParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence, offset))
    }

    private fun signatureParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): SignatureHelpParams {
        return SignatureHelpParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence, offset))
    }

    private fun definitionParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): DefinitionParams {
        return DefinitionParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence, offset))
    }

    private fun referenceParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): ReferenceParams {
        return ReferenceParams(
            TextDocumentIdentifier(document.uri),
            document.positionOf(needle, occurrence, offset),
            ReferenceContext(true)
        )
    }

    private fun openParams(uri: String, source: String): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source))
    }

    private fun fixture(name: String): String {
        val path = "lsp/androidlua/$name"
        val resource = javaClass.classLoader.getResource(path)
        return assertNotNull(resource, "Missing fixture resource $path.").readText()
    }

    private fun pathFor(name: String): String = "workspace/$name"

    private fun javaProviderUri(className: String): String {
        return "file:///__jvm__/classes/${className.replace('.', '/')}.lua"
    }

    private fun androidProviderUri(className: String): String = javaProviderUri(className)

    private fun assertAndroidJarExists() {
        assertTrue(androidJar.isFile, "Expected Android platform jar at ${androidJar.path}.")
        assertTrue(androidJar.length() > 0, "Expected non-empty Android platform jar at ${androidJar.path}.")
    }

    private fun assertCompletion(completions: org.eclipse.lsp4j.CompletionList, label: String) {
        assertTrue(
            completions.items.any { it.label == label },
            "Expected completion '$label' in ${completions.items.map { it.label }}."
        )
    }

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"

        fun positionOf(needle: String, occurrence: Int = 1, offset: Int = 0): Position {
            require(occurrence >= 1) { "occurrence must be positive" }
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find occurrence ${it + 1} of '$needle' in $path" }
                fromIndex = index + needle.length
            }
            return positionAt(index + offset.coerceIn(0, needle.lastIndex.coerceAtLeast(0)))
        }

        private fun positionAt(offset: Int): Position {
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
    }

    private val org.eclipse.lsp4j.Hover.markup: String
        get() = contents.right.value
}
