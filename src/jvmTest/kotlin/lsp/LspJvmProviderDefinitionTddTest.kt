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
    fun definition_and_hover_on_import_bound_instance_member_land_on_provider_virtual_path() {
        val service = mountedService()
        val document = service.open(
            "workspace/provider-def-import-member.lua",
            """
            import "java.lang.StringBuilder"
            local builder = StringBuilder()
            local current = builder.append
            return current
            """
        )

        val definition = service.definition(definitionParams(document, "append"))
        val hover = assertNotNull(service.hover(hoverParams(document, "append")))

        assertEquals(javaProviderUri("java.lang.StringBuilder"), definition.single().uri)
        assertTrue(hover.markup.contains("append"), "Expected hover for append; got: ${hover.markup}")
    }

    @Test
    fun definition_and_hover_on_luajava_bind_class_alias_land_on_provider_virtual_path() {
        // bindClass loads are source-discovered and mounted into extraProviders (TASK-169).
        val service = mountedService()
        val document = service.open(
            "workspace/provider-def-bind-class.lua",
            """
            local Locale = luajava.bindClass("java.util.Locale")
            local current = Locale.getDefault
            return current
            """
        )

        val classDefinition = service.definition(definitionParams(document, "Locale", occurrence = 2))
        val memberDefinition = service.definition(definitionParams(document, "getDefault"))
        val classHover = assertNotNull(service.hover(hoverParams(document, "Locale", occurrence = 2)))
        val memberHover = assertNotNull(service.hover(hoverParams(document, "getDefault")))

        assertEquals(javaProviderUri("java.util.Locale"), classDefinition.single().uri)
        assertEquals(javaProviderUri("java.util.Locale"), memberDefinition.single().uri)
        assertTrue(
            classHover.markup.contains("Locale"),
            "Expected hover for bindClass Locale alias; got: ${classHover.markup}"
        )
        assertTrue(
            memberHover.markup.contains("getDefault"),
            "Expected hover for Locale.getDefault; got: ${memberHover.markup}"
        )
    }

    @Test
    fun definition_and_hover_on_configured_androlua_import_land_on_provider_virtual_path() {
        val service = mountedService()
        LuaWorkspaceService(service).didChangeConfiguration(
            DidChangeConfigurationParams(
                mapOf(
                    "androlua.imports" to listOf("String"),
                    "jvm.importPrefixes" to listOf("java.lang")
                )
            )
        )
        val document = service.open(
            "workspace/provider-def-androlua-import.lua",
            """
            local current = String
            return current
            """
        )

        val definition = service.definition(definitionParams(document, "String"))
        val hover = assertNotNull(service.hover(hoverParams(document, "String")))

        assertEquals(javaProviderUri("java.lang.String"), definition.single().uri)
        assertTrue(
            hover.markup.contains("String"),
            "Expected hover for configured androlua String import; got: ${hover.markup}"
        )
    }

    @Test
    fun text_document_service_definition_and_hover_use_provider_virtual_paths_when_mounted() {
        val languageService = mountedService(classes = listOf("java.util.Arrays"))
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/provider-def-text-service.lua",
            """
            local Arrays = require("Arrays")
            local current = Arrays.asList
            return current
            """
        )

        val definition = textDocuments.definition(definitionParams(document, "asList")).get().left
        val hover = assertNotNull(textDocuments.hover(hoverParams(document, "asList")).get())

        assertEquals(javaProviderUri("java.util.Arrays"), definition.single().uri)
        assertTrue(hover.markup.contains("asList"), "Expected text-document hover for asList; got: ${hover.markup}")
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
    fun without_mounts_missing_class_bind_degrades_to_empty_or_non_provider_definition() {
        val service = unmountedService()
        val document = service.open(
            "workspace/provider-def-unmounted-missing-class.lua",
            """
            local Missing = luajava.bindClass("com.missing.DoesNotExist")
            local current = Missing.nope
            return current
            """
        )

        val classDefinition = service.definition(definitionParams(document, "Missing", occurrence = 2))
        val memberDefinition = service.definition(definitionParams(document, "nope"))
        // Hover may be null or local-only; must not throw (implicit) and must not invent a provider path.
        val classHover = service.hover(hoverParams(document, "Missing", occurrence = 2))
        val memberHover = service.hover(hoverParams(document, "nope"))

        assertFalse(
            classDefinition.any { it.uri.startsWith("file:///__jvm__/classes/") },
            "Missing class must not land on a JVM provider virtual path; got: ${classDefinition.map { it.uri }}"
        )
        assertFalse(
            memberDefinition.any { it.uri.startsWith("file:///__jvm__/classes/") },
            "Missing member must not land on a JVM provider virtual path; got: ${memberDefinition.map { it.uri }}"
        )
        assertFalse(
            classHover?.markup?.contains("__jvm__/classes/com/missing/DoesNotExist") == true,
            "Missing class hover must not invent provider path; got: ${classHover?.markup}"
        )
        assertFalse(
            memberHover?.markup?.contains("__jvm__/classes/com/missing/DoesNotExist") == true,
            "Missing member hover must not invent provider path; got: ${memberHover?.markup}"
        )
    }

    @Test
    fun without_mounts_empty_metadata_keeps_unknown_short_name_unresolved_while_mounted_peer_still_works() {
        val emptyMetadata = unmountedService()
        emptyMetadata.setWorkspaceMetadata(emptyMap())
        val emptyDocument = emptyMetadata.open(
            "workspace/provider-def-empty-metadata.lua",
            """
            local Arrays = require("Arrays")
            return Arrays.asList
            """
        )

        val emptyDefinitions = emptyMetadata.definition(definitionParams(emptyDocument, "asList"))
        assertFalse(
            emptyDefinitions.any { it.uri == javaProviderUri("java.util.Arrays") },
            "Empty metadata must not mount Arrays provider for short require; got: ${emptyDefinitions.map { it.uri }}"
        )

        val selective = unmountedService()
        selective.setWorkspaceMetadata(
            mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.util.Locale")
        )
        val arraysDocument = selective.open(
            "workspace/provider-def-unrelated-metadata.lua",
            """
            local Arrays = require("Arrays")
            return Arrays.asList
            """
        )
        val arraysDefinitions = selective.definition(definitionParams(arraysDocument, "asList"))
        assertFalse(
            arraysDefinitions.any { it.uri == javaProviderUri("java.util.Arrays") },
            "Unrelated metadata must not mount Arrays provider; got: ${arraysDefinitions.map { it.uri }}"
        )

        // Locale remains available when explicitly mounted (sanity that degrade is selective).
        val localeDocument = selective.open(
            "workspace/provider-def-unrelated-locale.lua",
            """
            local Locale = require("Locale")
            return Locale.getDefault
            """
        )
        val localeDefinitions = selective.definition(definitionParams(localeDocument, "getDefault"))
        assertEquals(javaProviderUri("java.util.Locale"), localeDefinitions.single().uri)
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
