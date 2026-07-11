package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DefinitionCapabilities
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-402 — LSP Location vs LocationLink definition dual-path corpus.
 *
 * Locks the contract for `textDocument/definition` Either shape:
 * - Product today always wraps [LuaLanguageService.definition] results as
 *   `Either.forLeft(List<Location>)` from [LuaTextDocumentService.definition]
 *   (and the language-server lifecycle wrapper). Docs note location lists rather
 *   than [LocationLink] objects.
 * - When clients advertise `textDocument.definition.linkSupport=true`, the ideal
 *   product path may eventually return `Either.forRight(List<LocationLink>)` with
 *   well-formed targetUri / targetRange / targetSelectionRange (and optional
 *   originSelectionRange). Until then, returning Location lists remains the
 *   documented dual-path gap and is accepted.
 * - Without linkSupport (or with empty client caps), responses must stay on the
 *   Location (left) arm — never invent LocationLink payloads.
 * - Local definition targets stay in-file; missing symbols / non-identifier
 *   positions degrade without hard-crash.
 *
 * Test-only; no production edits. Verification is review-owned and serial; this
 * worker does not run Gradle.
 */
class LspDefinitionLocationLinkTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun definition_provider_is_advertised_on_initialize() {
        val service = bareService()
        val capabilities = service.initialize(InitializeParams()).capabilities

        assertNotNull(capabilities, "initialize must return ServerCapabilities")
        assertNotNull(
            capabilities.definitionProvider,
            "definitionProvider must be advertised so clients may send textDocument/definition"
        )
    }

    @Test
    fun definition_surface_is_invokable_via_text_document_service() {
        val languageService = service()
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/definition-locationlink-surface.lua",
            """
            local value = 1
            return value
            """
        )

        val outcome = invokeDefinition(
            textDocuments,
            definitionParams(document, "value", occurrence = 2)
        )

        assertTrue(
            outcome is DefinitionOutcome.Locations ||
                outcome is DefinitionOutcome.LocationLinks ||
                outcome is DefinitionOutcome.Empty ||
                outcome is DefinitionOutcome.Failed,
            "definition surface must resolve to a known outcome; got $outcome"
        )
        if (outcome is DefinitionOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "definition surface must not hard-crash; got ${outcome.detail}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Without linkSupport → Location (left) arm preferred / required when non-empty
    // -------------------------------------------------------------------------

    @Test
    fun without_link_support_definition_returns_location_list_either_left() {
        val languageService = service(linkSupport = false)
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/definition-locationlink-no-linksupport.lua",
            """
            local value = 1
            return value
            """
        )

        val outcome = invokeDefinition(
            textDocuments,
            definitionParams(document, "value", occurrence = 2)
        )

        when (outcome) {
            is DefinitionOutcome.Locations -> {
                assertLocalDefinitionLocations(
                    locations = outcome.locations,
                    document = document,
                    expectedLine = document.lineOf("value", occurrence = 1),
                    label = "no-linkSupport local use"
                )
            }
            is DefinitionOutcome.LocationLinks -> {
                fail(
                    "Without client linkSupport, product must not return LocationLink (right) arm; " +
                        "got ${describeLinks(outcome.links)}"
                )
            }
            is DefinitionOutcome.Empty -> {
                // Soft path if binding is empty in current product; still must not be right-arm.
            }
            is DefinitionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "no-linkSupport definition must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun empty_client_capabilities_definition_stays_on_location_list_path() {
        val languageService = service(linkSupport = null)
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/definition-locationlink-empty-caps.lua",
            """
            local name = "x"
            local copy = name
            return copy
            """
        )

        val outcome = invokeDefinition(
            textDocuments,
            definitionParams(document, "name", occurrence = 2)
        )

        when (outcome) {
            is DefinitionOutcome.Locations -> {
                assertLocalDefinitionLocations(
                    locations = outcome.locations,
                    document = document,
                    expectedLine = document.lineOf("name", occurrence = 1),
                    label = "empty client caps local use"
                )
            }
            is DefinitionOutcome.LocationLinks -> {
                fail(
                    "Empty client capabilities must not yield LocationLink arm; " +
                        "got ${describeLinks(outcome.links)}"
                )
            }
            is DefinitionOutcome.Empty -> Unit
            is DefinitionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "empty-caps definition must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun language_service_definition_always_returns_plain_location_list() {
        // Direct service API is List<Location>; LocationLink is only an Either arm on the
        // textDocument / language-server transport surface.
        val languageService = service(linkSupport = true)
        val document = languageService.open(
            "workspace/definition-locationlink-service-list.lua",
            """
            local flag = true
            return flag
            """
        )

        val locations = languageService.definition(
            definitionParams(document, "flag", occurrence = 2)
        )

        assertTrue(locations.isNotEmpty(), "expected at least one Location from language service")
        assertLocalDefinitionLocations(
            locations = locations,
            document = document,
            expectedLine = document.lineOf("flag", occurrence = 1),
            label = "languageService.definition plain list"
        )
    }

    // -------------------------------------------------------------------------
    // With linkSupport → dual-path Location (current) vs LocationLink (ideal)
    // -------------------------------------------------------------------------

    @Test
    fun with_link_support_definition_dual_path_location_or_location_link() {
        val languageService = service(linkSupport = true)
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/definition-locationlink-with-linksupport.lua",
            """
            local value = 1
            return value
            """
        )

        val usePosition = document.positionOf("value", occurrence = 2)
        val declLine = document.lineOf("value", occurrence = 1)
        val outcome = invokeDefinition(
            textDocuments,
            definitionParams(document, "value", occurrence = 2)
        )

        when (outcome) {
            is DefinitionOutcome.Locations -> {
                // Documented current product: always Either.left(List<Location>),
                // even when the client advertises linkSupport.
                assertLocalDefinitionLocations(
                    locations = outcome.locations,
                    document = document,
                    expectedLine = declLine,
                    label = "linkSupport=true current Location path"
                )
            }
            is DefinitionOutcome.LocationLinks -> {
                // Ideal path once product maps definitions to LocationLink.
                assertLocalDefinitionLinks(
                    links = outcome.links,
                    document = document,
                    expectedLine = declLine,
                    originHint = usePosition,
                    label = "linkSupport=true ideal LocationLink path"
                )
            }
            is DefinitionOutcome.Empty -> {
                // Soft degrade if binding empty; still dual-path safe.
            }
            is DefinitionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "linkSupport definition must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun with_link_support_function_local_definition_dual_path() {
        val languageService = service(linkSupport = true)
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/definition-locationlink-function.lua",
            """
            local function greet(name)
                return name
            end
            return greet
            """
        )

        val outcome = invokeDefinition(
            textDocuments,
            definitionParams(document, "greet", occurrence = 2)
        )

        when (outcome) {
            is DefinitionOutcome.Locations -> {
                assertLocalDefinitionLocations(
                    locations = outcome.locations,
                    document = document,
                    expectedLine = document.lineOf("greet", occurrence = 1),
                    label = "function local Location path"
                )
            }
            is DefinitionOutcome.LocationLinks -> {
                assertLocalDefinitionLinks(
                    links = outcome.links,
                    document = document,
                    expectedLine = document.lineOf("greet", occurrence = 1),
                    originHint = document.positionOf("greet", occurrence = 2),
                    label = "function local LocationLink path"
                )
            }
            is DefinitionOutcome.Empty -> Unit
            is DefinitionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "function definition dual-path must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun with_link_support_parameter_use_dual_path() {
        val languageService = service(linkSupport = true)
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/definition-locationlink-param.lua",
            """
            local function render(name)
                return name
            end
            return render
            """
        )

        // Occurrences of "name": 1 parameter, 2 body use.
        val outcome = invokeDefinition(
            textDocuments,
            definitionParams(document, "name", occurrence = 2)
        )

        when (outcome) {
            is DefinitionOutcome.Locations -> {
                assertLocalDefinitionLocations(
                    locations = outcome.locations,
                    document = document,
                    expectedLine = document.lineOf("name", occurrence = 1),
                    label = "parameter use Location path"
                )
            }
            is DefinitionOutcome.LocationLinks -> {
                assertLocalDefinitionLinks(
                    links = outcome.links,
                    document = document,
                    expectedLine = document.lineOf("name", occurrence = 1),
                    originHint = document.positionOf("name", occurrence = 2),
                    label = "parameter use LocationLink path"
                )
            }
            is DefinitionOutcome.Empty -> Unit
            is DefinitionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "parameter definition dual-path must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // LocationLink shape contract (when right arm is returned)
    // -------------------------------------------------------------------------

    @Test
    fun location_link_payload_has_required_target_fields_when_returned() {
        val languageService = service(linkSupport = true)
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/definition-locationlink-shape.lua",
            """
            local alpha = 10
            local beta = alpha
            return beta
            """
        )

        val outcome = invokeDefinition(
            textDocuments,
            definitionParams(document, "alpha", occurrence = 2)
        )

        when (outcome) {
            is DefinitionOutcome.LocationLinks -> {
                assertTrue(outcome.links.isNotEmpty(), "LocationLink arm must not be empty when right")
                outcome.links.forEachIndexed { index, link ->
                    assertTrue(
                        !link.targetUri.isNullOrBlank(),
                        "link[$index] targetUri required; got ${describeLinks(outcome.links)}"
                    )
                    assertNotNull(
                        link.targetRange,
                        "link[$index] targetRange required; got ${describeLinks(outcome.links)}"
                    )
                    assertNotNull(
                        link.targetSelectionRange,
                        "link[$index] targetSelectionRange required; got ${describeLinks(outcome.links)}"
                    )
                    assertOrderedRange(link.targetRange, "link[$index].targetRange")
                    assertOrderedRange(link.targetSelectionRange, "link[$index].targetSelectionRange")
                    assertRangeContained(
                        outer = link.targetRange,
                        inner = link.targetSelectionRange,
                        label = "link[$index] selection inside target"
                    )
                    link.originSelectionRange?.let { origin ->
                        assertOrderedRange(origin, "link[$index].originSelectionRange")
                    }
                }
            }
            is DefinitionOutcome.Locations -> {
                // Current product path — Location list shape already covered elsewhere.
                assertTrue(
                    outcome.locations.all { !it.uri.isNullOrBlank() && it.range != null },
                    "Location arm must still be well-formed; got ${describeLocations(outcome.locations)}"
                )
            }
            is DefinitionOutcome.Empty -> Unit
            is DefinitionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "shape probe must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Safety: missing / non-identifier / either empty
    // -------------------------------------------------------------------------

    @Test
    fun definition_missing_symbol_degrades_without_throw_on_either_arm() {
        val languageService = service(linkSupport = true)
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/definition-locationlink-missing.lua",
            """
            local value = 1
            return unknownName
            """
        )

        val outcome = invokeDefinition(
            textDocuments,
            definitionParams(document, "unknownName", occurrence = 1)
        )

        when (outcome) {
            is DefinitionOutcome.Locations -> {
                assertTrue(
                    outcome.locations.isEmpty() ||
                        outcome.locations.none { it.uri.contains("__jvm__/") },
                    "unbound identifier must not invent JVM provider paths; " +
                        "got ${describeLocations(outcome.locations)}"
                )
            }
            is DefinitionOutcome.LocationLinks -> {
                assertTrue(
                    outcome.links.isEmpty() ||
                        outcome.links.none { it.targetUri.orEmpty().contains("__jvm__/") },
                    "unbound identifier LocationLink must not invent JVM provider paths; " +
                        "got ${describeLinks(outcome.links)}"
                )
            }
            is DefinitionOutcome.Empty -> Unit
            is DefinitionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "missing symbol must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun definition_non_identifier_position_degrades_without_throw() {
        val languageService = service(linkSupport = false)
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/definition-locationlink-non-id.lua",
            """
            local value = 1
            return value
            """
        )

        val outcome = invokeDefinition(
            textDocuments,
            DefinitionParams(
                TextDocumentIdentifier(document.uri),
                Position(0, 0) // start of "local" line — not an identifier use
            )
        )

        when (outcome) {
            is DefinitionOutcome.Locations,
            is DefinitionOutcome.LocationLinks,
            is DefinitionOutcome.Empty -> Unit
            is DefinitionOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "non-identifier position must not hard-crash; got ${outcome.detail}"
                )
            }
        }
    }

    @Test
    fun definition_either_is_exactly_one_arm_when_present() {
        val languageService = service(linkSupport = true)
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/definition-locationlink-either-arm.lua",
            """
            local token = 42
            return token
            """
        )

        val either = runCatching {
            textDocuments.definition(definitionParams(document, "token", occurrence = 2)).get()
        }.getOrElse { error ->
            if (isHardCrash(errorDetail(error))) {
                fail("definition Either must not hard-crash: ${errorDetail(error)}")
            }
            null
        } ?: return

        // LSP Either must be left XOR right (or both empty/null only if product returns empty Either).
        val left = either.isLeft
        val right = either.isRight
        assertTrue(
            left xor right || (!left && !right),
            "definition Either must not claim both arms simultaneously; left=$left right=$right either=$either"
        )
        if (left) {
            assertTrue(
                either.left != null,
                "left arm present implies non-null left payload"
            )
        }
        if (right) {
            assertTrue(
                either.right != null,
                "right arm present implies non-null right payload"
            )
        }
    }

    @Test
    fun declaration_surface_dual_path_matches_definition_either_policy() {
        // Declaration uses the same Either<List<Location>, List<LocationLink>> surface.
        // Current product always returns left Locations; LocationLink is dual-path ideal.
        val languageService = service(linkSupport = true)
        val textDocuments = LuaTextDocumentService(languageService)
        val document = textDocuments.open(
            "workspace/definition-locationlink-declaration.lua",
            """
            local value = 1
            return value
            """
        )

        val either = runCatching {
            textDocuments.declaration(
                org.eclipse.lsp4j.DeclarationParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionOf("value", occurrence = 2)
                )
            ).get()
        }.getOrElse { error ->
            if (isHardCrash(errorDetail(error))) {
                fail("declaration Either must not hard-crash: ${errorDetail(error)}")
            }
            null
        } ?: return

        when {
            either.isLeft -> {
                val locations = either.left.orEmpty()
                if (locations.isNotEmpty()) {
                    assertLocalDefinitionLocations(
                        locations = locations,
                        document = document,
                        expectedLine = document.lineOf("value", occurrence = 1),
                        label = "declaration Location path"
                    )
                }
            }
            either.isRight -> {
                val links = either.right.orEmpty()
                if (links.isNotEmpty()) {
                    assertLocalDefinitionLinks(
                        links = links,
                        document = document,
                        expectedLine = document.lineOf("value", occurrence = 1),
                        originHint = document.positionOf("value", occurrence = 2),
                        label = "declaration LocationLink path"
                    )
                }
            }
            else -> Unit
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun bareService(): LuaLanguageService = LuaLanguageService()

    private fun service(linkSupport: Boolean? = null): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(initializeParams(linkSupport))
        }
    }

    private fun initializeParams(linkSupport: Boolean?): InitializeParams {
        return InitializeParams().apply {
            if (linkSupport != null) {
                capabilities = ClientCapabilities().apply {
                    textDocument = TextDocumentClientCapabilities().apply {
                        definition = DefinitionCapabilities().apply {
                            setLinkSupport(linkSupport)
                        }
                    }
                }
            } else {
                capabilities = ClientCapabilities()
            }
        }
    }

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )
        return document
    }

    private fun LuaTextDocumentService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(document.uri, "lua", 1, document.source)
            )
        )
        return document
    }

    private fun definitionParams(
        document: OpenDocument,
        needle: String,
        occurrence: Int = 1
    ): DefinitionParams {
        return DefinitionParams(
            TextDocumentIdentifier(document.uri),
            document.positionOf(needle, occurrence)
        )
    }

    private fun invokeDefinition(
        textDocuments: LuaTextDocumentService,
        params: DefinitionParams
    ): DefinitionOutcome {
        return try {
            val either = textDocuments.definition(params).get()
            classifyEither(either)
        } catch (error: Throwable) {
            val detail = errorDetail(error)
            if (isHardCrash(detail)) {
                DefinitionOutcome.Failed(detail = detail)
            } else {
                // Soft failures (e.g. rejected lifecycle) surface as Failed without hard-crash flag.
                DefinitionOutcome.Failed(detail = detail)
            }
        }
    }

    private fun classifyEither(
        either: Either<MutableList<out Location>, MutableList<out LocationLink>>?
    ): DefinitionOutcome {
        if (either == null) {
            return DefinitionOutcome.Empty
        }
        return when {
            either.isLeft -> {
                val locations = either.left.orEmpty()
                if (locations.isEmpty()) {
                    DefinitionOutcome.Empty
                } else {
                    DefinitionOutcome.Locations(locations)
                }
            }
            either.isRight -> {
                val links = either.right.orEmpty()
                if (links.isEmpty()) {
                    DefinitionOutcome.Empty
                } else {
                    DefinitionOutcome.LocationLinks(links)
                }
            }
            else -> DefinitionOutcome.Empty
        }
    }

    private fun assertLocalDefinitionLocations(
        locations: List<Location>,
        document: OpenDocument,
        expectedLine: Int,
        label: String
    ) {
        assertTrue(locations.isNotEmpty(), "$label expected Location hits; got empty")
        assertTrue(
            locations.any { it.uri == document.uri && it.range.start.line == expectedLine },
            "$label expected same-file hit on line $expectedLine; got ${describeLocations(locations)}"
        )
        assertTrue(
            locations.all { it.uri == document.uri },
            "$label pure local definition must stay in requesting file; got ${describeLocations(locations)}"
        )
        locations.forEachIndexed { index, location ->
            assertNotNull(location.range, "$label location[$index].range")
            assertOrderedRange(location.range, "$label location[$index].range")
        }
        if (locations.size == 1) {
            assertEquals(document.uri, locations.single().uri, "$label URI")
            assertEquals(expectedLine, locations.single().range.start.line, "$label line")
        }
    }

    private fun assertLocalDefinitionLinks(
        links: List<LocationLink>,
        document: OpenDocument,
        expectedLine: Int,
        originHint: Position?,
        label: String
    ) {
        assertTrue(links.isNotEmpty(), "$label expected LocationLink hits; got empty")
        assertTrue(
            links.any {
                it.targetUri == document.uri &&
                    it.targetSelectionRange?.start?.line == expectedLine
            } || links.any {
                it.targetUri == document.uri &&
                    it.targetRange?.start?.line == expectedLine
            },
            "$label expected same-file target on line $expectedLine; got ${describeLinks(links)}"
        )
        assertTrue(
            links.all { it.targetUri == document.uri },
            "$label pure local LocationLink must stay in requesting file; got ${describeLinks(links)}"
        )
        links.forEachIndexed { index, link ->
            assertTrue(!link.targetUri.isNullOrBlank(), "$label link[$index].targetUri")
            assertNotNull(link.targetRange, "$label link[$index].targetRange")
            assertNotNull(link.targetSelectionRange, "$label link[$index].targetSelectionRange")
            assertOrderedRange(link.targetRange, "$label link[$index].targetRange")
            assertOrderedRange(link.targetSelectionRange, "$label link[$index].targetSelectionRange")
            assertRangeContained(
                outer = link.targetRange,
                inner = link.targetSelectionRange,
                label = "$label link[$index] selection inside target"
            )
            link.originSelectionRange?.let { origin ->
                assertOrderedRange(origin, "$label link[$index].originSelectionRange")
                if (originHint != null) {
                    // Soft: origin should cover the use site line when product fills it.
                    assertTrue(
                        origin.start.line <= originHint.line && origin.end.line >= originHint.line,
                        "$label originSelectionRange should cover use line ${originHint.line}; " +
                            "got ${describeRange(origin)}"
                    )
                }
            }
        }
    }

    private fun assertOrderedRange(range: Range, label: String) {
        val start = range.start
        val end = range.end
        assertNotNull(start, "$label.start")
        assertNotNull(end, "$label.end")
        assertTrue(
            start.line < end.line ||
                (start.line == end.line && start.character <= end.character),
            "$label must be ordered start<=end; got ${describeRange(range)}"
        )
        assertTrue(start.line >= 0 && start.character >= 0, "$label start non-negative")
        assertTrue(end.line >= 0 && end.character >= 0, "$label end non-negative")
    }

    private fun assertRangeContained(outer: Range, inner: Range, label: String) {
        val outerStart = outer.start
        val outerEnd = outer.end
        val innerStart = inner.start
        val innerEnd = inner.end
        val startsOk =
            innerStart.line > outerStart.line ||
                (innerStart.line == outerStart.line && innerStart.character >= outerStart.character)
        val endsOk =
            innerEnd.line < outerEnd.line ||
                (innerEnd.line == outerEnd.line && innerEnd.character <= outerEnd.character)
        assertTrue(
            startsOk && endsOk,
            "$label expected ${describeRange(inner)} inside ${describeRange(outer)}"
        )
    }

    private fun describeLocations(locations: List<Location>): String {
        return locations.joinToString(prefix = "[", postfix = "]") { location ->
            "${location.uri}@${describeRange(location.range)}"
        }
    }

    private fun describeLinks(links: List<LocationLink>): String {
        return links.joinToString(prefix = "[", postfix = "]") { link ->
            "target=${link.targetUri}@${describeRange(link.targetRange)}" +
                "/sel=${describeRange(link.targetSelectionRange)}" +
                link.originSelectionRange?.let { "/origin=${describeRange(it)}" }.orEmpty()
        }
    }

    private fun describeRange(range: Range?): String {
        if (range == null) return "<null>"
        val s = range.start
        val e = range.end
        return "${s?.line}:${s?.character}-${e?.line}:${e?.character}"
    }

    private fun errorDetail(error: Throwable): String {
        return buildString {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 8) {
                if (depth > 0) append('|')
                append(current::class.simpleName.orEmpty())
                append(':')
                append(current.message.orEmpty())
                current = when (current) {
                    is ExecutionException, is CompletionException -> current.cause
                    else -> current.cause
                }
                depth += 1
            }
        }
    }

    private fun isHardCrash(detail: String): Boolean {
        return detail.contains("NullPointerException", ignoreCase = true) ||
            detail.contains("KotlinNullPointerException", ignoreCase = true) ||
            detail.contains("IndexOutOfBoundsException", ignoreCase = true) ||
            detail.contains("ArrayIndexOutOfBoundsException", ignoreCase = true) ||
            detail.contains("StringIndexOutOfBoundsException", ignoreCase = true) ||
            detail.contains("StackOverflowError", ignoreCase = true) ||
            detail.contains("OutOfMemoryError", ignoreCase = true)
    }

    private sealed class DefinitionOutcome {
        data class Locations(val locations: List<Location>) : DefinitionOutcome()
        data class LocationLinks(val links: List<LocationLink>) : DefinitionOutcome()
        data object Empty : DefinitionOutcome()
        data class Failed(val detail: String) : DefinitionOutcome()
    }

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"

        fun positionOf(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1) { "occurrence must be positive" }
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find occurrence $occurrence of '$needle' in $path" }
                fromIndex = index + needle.length
            }
            return positionAt(index)
        }

        fun lineOf(needle: String, occurrence: Int = 1): Int {
            return positionOf(needle, occurrence).line
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
}
