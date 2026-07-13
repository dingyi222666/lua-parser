package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-242 corpus: JVM LSP definition/hover against provider virtual paths.
 *
 * Acceptance (test-only; production mount remains TASK-169):
 * - Definition/hover on bound Java class names land on provider virtual paths when mounted.
 * - Without mounts, degrade gracefully (empty definition / null or non-provider hover, no throw).
 */
class LspJvmProviderDefinitionTddTest {

    @Test
    fun definition_and_hover_on_mounted_require_class_alias_land_on_provider_virtual_path() {
        val service = mountedService(
            classes = listOf("java.util.Arrays")
        )
        val document = service.open(
            "workspace/provider-def-require-alias.lua",
            """
            local Arrays = require("Arrays")
            local current = Arrays
            return current
            """
        )

        val definition = service.definition(definitionParams(document, "Arrays", occurrence = 3))
        val hover = assertNotNull(service.hover(hoverParams(document, "Arrays", occurrence = 3)))

        assertEquals(javaProviderUri("java.util.Arrays"), definition.single().uri)
        assertTrue(
            hover.markup.contains("Arrays") || hover.markup.contains("java.util.Arrays"),
            "Expected hover for mounted Arrays alias; got: ${hover.markup}"
        )
    }

    @Test
    fun definition_and_hover_on_mounted_jdk_class_member_land_on_provider_virtual_path() {
        val service = mountedService(
            classes = listOf("java.util.Arrays")
        )
        val document = service.open(
            "workspace/provider-def-require-member.lua",
            """
            local Arrays = require("Arrays")
            local current = Arrays.asList
            return current
            """
        )

        val definition = service.definition(definitionParams(document, "asList"))
        val hover = assertNotNull(service.hover(hoverParams(document, "asList")))

        assertEquals(javaProviderUri("java.util.Arrays"), definition.single().uri)
        assertTrue(hover.markup.contains("asList"), "Expected hover markup to mention asList; got: ${hover.markup}")
    }

    @Test
    fun definition_and_hover_on_import_bound_class_name_land_on_provider_virtual_path() {
        // Source-discovered import mounts the class provider (TASK-169 production path).
        val service = mountedService()
        val document = service.open(
            "workspace/provider-def-import-class.lua",
            """
            import "java.lang.StringBuilder"
            local builder = StringBuilder()
            return builder
            """
        )

        val definition = service.definition(definitionParams(document, "StringBuilder()", offset = 0))
        val hover = assertNotNull(service.hover(hoverParams(document, "StringBuilder()", offset = 0)))

        assertEquals(javaProviderUri("java.lang.StringBuilder"), definition.single().uri)
        assertTrue(
            hover.markup.contains("StringBuilder"),
            "Expected hover for bound StringBuilder class name; got: ${hover.markup}"
        )
    }

    @Test
    fun without_mounts_require_class_definition_and_hover_degrade_gracefully() {
        // Short require("Arrays") only resolves when CLASSES_METADATA (or equivalent) mounts the provider.
        val service = unmountedService()
        val document = service.open(
            "workspace/provider-def-unmounted-require.lua",
            """
            local Arrays = require("Arrays")
            local current = Arrays.asList
            return current
            """
        )

        val aliasDefinition = service.definition(definitionParams(document, "Arrays", occurrence = 3))
        val memberDefinition = service.definition(definitionParams(document, "asList"))
        val aliasHover = service.hover(hoverParams(document, "Arrays", occurrence = 3))
        val memberHover = service.hover(hoverParams(document, "asList"))

        assertFalse(
            aliasDefinition.any { it.uri == javaProviderUri("java.util.Arrays") },
            "Without mounts, Arrays alias must not resolve to provider virtual path; got: ${aliasDefinition.map { it.uri }}"
        )
        assertFalse(
            memberDefinition.any { it.uri == javaProviderUri("java.util.Arrays") },
            "Without mounts, asList must not resolve to provider virtual path; got: ${memberDefinition.map { it.uri }}"
        )
        assertFalse(
            aliasHover?.markup?.contains("__jvm__/classes/java/util/Arrays") == true,
            "Without mounts, hover must not advertise the Arrays provider path; got: ${aliasHover?.markup}"
        )
        assertFalse(
            memberHover?.markup?.contains("__jvm__/classes/java/util/Arrays") == true,
            "Without mounts, hover must not advertise the Arrays provider path; got: ${memberHover?.markup}"
        )
    }

    @Test
    fun provider_virtual_path_uri_shape_is_stable_file_uri_with_forward_slashes() {
        val service = mountedService(classes = listOf("java.lang.System"))
        val document = service.open(
            "workspace/provider-def-system-uri-shape.lua",
            """
            local System = require("System")
            local current = System.currentTimeMillis
            return current
            """
        )

        val definition = service.definition(definitionParams(document, "currentTimeMillis"))
        val hover = assertNotNull(service.hover(hoverParams(document, "currentTimeMillis")))
        val uri = definition.single().uri

        assertEquals("file:///__jvm__/classes/java/lang/System.lua", uri)
        assertFalse(uri.contains('\\'), "Provider virtual path URIs must use forward slashes: $uri")
        assertTrue(hover.markup.contains("currentTimeMillis"))
    }

    private fun mountedService(classes: List<String> = emptyList()): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
            if (classes.isNotEmpty()) {
                setWorkspaceMetadata(
                    mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to classes.joinToString("\n"))
                )
            }
        }
    }

    private fun unmountedService(): LuaLanguageService {
        // No CLASSES_METADATA_KEY, no androlua.imports, no android jar.
        // Short JDK module names such as "Arrays" therefore have no mounted provider.
        return LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
            setWorkspaceMetadata(emptyMap())
        }
    }

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(DidOpenTextDocumentParams(TextDocumentItem(document.uri, "lua", 1, document.source)))
        return document
    }

    private fun LuaTextDocumentService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(DidOpenTextDocumentParams(TextDocumentItem(document.uri, "lua", 1, document.source)))
        return document
    }

    private fun hoverParams(document: OpenDocument, needle: String, occurrence: Int = 1, offset: Int = 0): HoverParams {
        return HoverParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence, offset))
    }

    private fun definitionParams(
        document: OpenDocument,
        needle: String,
        occurrence: Int = 1,
        offset: Int = 0
    ): DefinitionParams {
        return DefinitionParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence, offset))
    }

    private fun javaProviderUri(className: String): String {
        return "file:///__jvm__/classes/${className.replace('.', '/')}.lua"
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
            val clampedOffset = offset.coerceIn(0, needle.lastIndex.coerceAtLeast(0))
            return positionAt(index + clampedOffset)
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
