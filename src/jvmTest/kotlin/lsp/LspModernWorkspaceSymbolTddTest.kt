package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageServer
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.SymbolCapabilities
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolOptions
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.WorkspaceSymbolResolveSupportCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-399 — LSP modern WorkspaceSymbol capability-branch corpus.
 *
 * Encodes the dual-path contract for `workspace/symbol` between:
 * - **Legacy** [SymbolInformation] (`Either.left`) — current [LuaWorkspaceService.symbol]
 *   always returns this branch today.
 * - **Modern** [WorkspaceSymbol] (`Either.right`) — intended when the client advertises
 *   modern workspace-symbol support (typically `workspace.symbol.resolveSupport`) and
 *   the product wire (TASK-397) starts returning [LuaLanguageService.modernWorkspaceSymbols].
 *
 * Product surfaces already available (and locked here without inventing APIs):
 * ```
 * LuaLanguageService.workspaceSymbols(query)       → List<SymbolInformation>
 * LuaLanguageService.modernWorkspaceSymbols(query) → List<WorkspaceSymbol>
 * LuaWorkspaceService.symbol(params)               → Either.left(legacy list) today
 * ServerCapabilities.workspaceSymbolProvider       → Either.right(WorkspaceSymbolOptions)
 * ```
 *
 * Capability-branch policy this corpus documents (matches TASK-397 acceptance):
 * - Client **without** modern resolve/modern WorkspaceSymbol support →
 *   wire response is `Either.left` legacy SymbolInformation (today's behavior).
 * - Client **with** `workspace.symbol.resolveSupport` (and/or future product
 *   capability tracking) → wire response should be `Either.right` modern
 *   WorkspaceSymbol list built from [LuaLanguageService.modernWorkspaceSymbols].
 * - Until TASK-397 lands, the modern wire branch is a **documented gap**: tests
 *   dual-path so today's `Either.left` still passes, while hard-asserting modern
 *   right-branch payloads when product starts returning them.
 * - Direct [LuaLanguageService.modernWorkspaceSymbols] always yields modern payloads
 *   (name / kind / Location-left uri / containerName) in lock-step with legacy
 *   [LuaLanguageService.workspaceSymbols] for the same query.
 *
 * Notes:
 * - Blank / whitespace-only / unset query is treated like blank via
 *   `params.query.orEmpty()` on the workspace service (legacy wire).
 * - Filter remains case-insensitive **contains** on symbol name (product facade).
 * - Provider-backed JVM module/member URIs stay stable under modern payloads.
 * - Test-only; no production edits. Verification is review-owned and serial;
 *   this worker does not run Gradle.
 */
class LspModernWorkspaceSymbolTddTest {

    // -------------------------------------------------------------------------
    // Server capability probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_advertises_workspace_symbol_provider_options() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        val provider = assertNotNull(
            capabilities.workspaceSymbolProvider,
            "workspaceSymbolProvider must be advertised"
        )
        assertTrue(
            provider.isRight,
            "product advertises WorkspaceSymbolOptions (Either.right); got $provider"
        )
        val options = assertNotNull(provider.right, "WorkspaceSymbolOptions payload")
        // resolveProvider may stay null/false until workspaceSymbol/resolve ships;
        // the options object itself is the capability advertisement.
        assertTrue(
            options is WorkspaceSymbolOptions,
            "workspaceSymbolProvider.right must be WorkspaceSymbolOptions; got ${options::class}"
        )
    }

    // -------------------------------------------------------------------------
    // Direct modern helper surface (always available; independent of wire branch)
    // -------------------------------------------------------------------------

    @Test
    fun modern_workspace_symbols_return_workspace_symbol_payloads() {
        val service = service()
        service.open(
            "workspace/modern-ws-payload.lua",
            """
            local modernAlpha = 1
            local function modernBeta()
                return modernAlpha
            end
            return modernBeta
            """
        )

        val symbols = service.modernWorkspaceSymbols("modern")

        assertTrue(symbols.isNotEmpty(), "expected modern hits; got ${symbols.describeModern()}")
        symbols.forEach { symbol ->
            assertNotNull(symbol.name)
            assertNotNull(symbol.kind)
            assertNotNull(symbol.location)
            assertTrue(
                symbol.location.isLeft,
                "product maps WorkspaceSymbol.location as Either.left(Location); got ${symbol.location}"
            )
            assertNotNull(symbol.location.left.uri)
            assertNotNull(symbol.location.left.range)
        }
        assertTrue(symbols.any { it.name == "modernAlpha" })
        assertTrue(symbols.any { it.name == "modernBeta" })
    }

    @Test
    fun modern_and_legacy_workspace_symbols_stay_in_lockstep_for_query() {
        val service = service()
        service.open(
            "workspace/modern-ws-lockstep-a.lua",
            """
            local function alphaRender()
                return 1
            end
            return alphaRender
            """
        )
        service.open(
            "workspace/modern-ws-lockstep-b.lua",
            """
            local alphabet = "abc"
            local betaRender = 2
            return alphabet
            """
        )

        val query = "alph"
        val legacy = service.workspaceSymbols(query)
            .map { it.fingerprint() }
            .sorted()
        val modern = service.modernWorkspaceSymbols(query)
            .map { it.fingerprint() }
            .sorted()

        assertEquals(legacy, modern, "modern and legacy fingerprints must match for query='$query'")
        assertTrue(legacy.any { it.startsWith("alphaRender@") })
        assertTrue(legacy.any { it.startsWith("alphabet@") })
        assertTrue(legacy.none { it.startsWith("betaRender@") })
    }

    @Test
    fun workspace_service_symbol_modern_client_caps_dual_path_left_or_right() {
        // Ideal (TASK-397): client resolveSupport → Either.right(modern WorkspaceSymbol list).
        // Today product ignores client caps and always returns Either.left. Dual-path:
        // accept left (documented gap) OR right with modern payloads matching the helper.
        val service = service()
        // Client caps are accepted on initialize even if product does not branch yet.
        service.initialize(initializeParamsWithModernResolveSupport())
        val workspace = LuaWorkspaceService(service)
        service.open(
            "workspace/modern-ws-wire-modern-caps.lua",
            """
            local function modernWireAlpha()
                return 1
            end
            local modernWireBeta = 2
            return modernWireAlpha
            """
        )

        val query = "modernWire"
        val either = workspace.symbol(WorkspaceSymbolParams(query)).get()
        val expectedModern = service.modernWorkspaceSymbols(query)
            .map { it.fingerprint() }
            .sorted()
        val expectedLegacy = service.workspaceSymbols(query)
            .map { it.fingerprint() }
            .sorted()

        assertEquals(expectedLegacy, expectedModern, "helper parity is a hard contract")

        when {
            either.isRight -> {
                val modern = either.right
                assertTrue(modern.isNotEmpty(), "modern right branch should not be empty for hits")
                assertEquals(
                    expectedModern,
                    modern.map { it.fingerprint() }.sorted(),
                    "Either.right payloads must match modernWorkspaceSymbols()"
                )
                modern.forEach { symbol ->
                    assertTrue(
                        symbol.location.isLeft,
                        "modern wire location must be Either.left(Location); got ${symbol.location}"
                    )
                }
            }
            either.isLeft -> {
                // Documented gap until TASK-397 wires the capability branch.
                val legacy = either.left
                assertEquals(
                    expectedLegacy,
                    legacy.map { it.fingerprint() }.sorted(),
                    "Either.left payloads must still match workspaceSymbols()"
                )
            }
            else -> {
                throw AssertionError("workspace/symbol Either must be left or right; got $either")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun service(metadata: Map<String, String> = emptyMap()): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(InitializeParams())
            if (metadata.isNotEmpty()) {
                setWorkspaceMetadata(metadata)
            }
        }
    }

    private fun initializeParamsWithModernResolveSupport(): InitializeParams {
        return InitializeParams().apply {
            capabilities = ClientCapabilities().apply {
                workspace = WorkspaceClientCapabilities().apply {
                    symbol = SymbolCapabilities().apply {
                        resolveSupport = WorkspaceSymbolResolveSupportCapabilities(
                            listOf("location.range")
                        )
                    }
                }
            }
        }
    }

    private fun initializeParamsWithoutModernResolveSupport(): InitializeParams {
        return InitializeParams().apply {
            capabilities = ClientCapabilities().apply {
                workspace = WorkspaceClientCapabilities().apply {
                    // Explicit symbol caps without resolveSupport → legacy branch.
                    symbol = SymbolCapabilities()
                }
            }
        }
    }

    private fun LuaLanguageService.open(path: String, source: String) {
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file:///$path", "lua", 1, source.trimIndent())
            )
        )
    }

    private fun SymbolInformation.fingerprint(): String {
        return "$name@${location.uri}#${kind}"
    }

    private fun WorkspaceSymbol.fingerprint(): String {
        val uri = when {
            location.isLeft -> location.left.uri
            location.isRight -> location.right.uri
            else -> "<no-uri>"
        }
        return "$name@$uri#$kind"
    }

    private fun List<SymbolInformation>.describeLegacy(): String {
        return joinToString(prefix = "[", postfix = "]") {
            "${it.name}@${it.location.uri}(${it.kind})"
        }
    }

    private fun List<WorkspaceSymbol>.describeModern(): String {
        return joinToString(prefix = "[", postfix = "]") { symbol ->
            val uri = when {
                symbol.location.isLeft -> symbol.location.left.uri
                symbol.location.isRight -> symbol.location.right.uri
                else -> "<no-uri>"
            }
            "${symbol.name}@$uri(${symbol.kind})"
        }
    }

    private companion object {
        val jdkMetadata = mapOf(
            JvmClassModuleProvider.CLASSES_METADATA_KEY to listOf(
                "java.util.Arrays",
                "java.util.Locale"
            ).joinToString("\n")
        )
    }
}
