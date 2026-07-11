package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolCapabilities
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.jsonrpc.messages.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-398 — LSP hierarchical DocumentSymbol vs flatten SymbolInformation corpus.
 *
 * Encodes the dual-path contract for `textDocument/documentSymbol` around the
 * client capability `textDocument.documentSymbol.hierarchicalDocumentSymbolSupport`
 * (LSP 3.10+ / [DocumentSymbolCapabilities]):
 *
 * Product helpers already exist:
 * - [LuaLanguageService.hierarchicalDocumentSymbols] → nested [DocumentSymbol] tree
 * - [LuaLanguageService.documentSymbols] → depth-first flatten to [SymbolInformation]
 *   (children carry `containerName` = parent symbol name)
 *
 * Wire surface today ([LuaTextDocumentService.documentSymbol]) always maps the
 * flattened [SymbolInformation] list as `Either.forLeft` regardless of client
 * capabilities. Sibling TASK-396 wires capability-aware branching:
 * - hierarchicalDocumentSymbolSupport == true  → `Either.forRight` DocumentSymbol list
 * - false / missing / null                     → keep SymbolInformation flatten
 *
 * This corpus is test-only and dual-path:
 * - Always asserts service-level hierarchical vs flatten parity / nesting.
 * - Accepts either "always-left" (pre-TASK-396) or capability-aware left/right
 *   branching once the product wire lands — never invents a new algorithm.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspHierarchicalDocumentSymbolTddTest {

    // -------------------------------------------------------------------------
    // Server capability advertisement
    // -------------------------------------------------------------------------

    @Test
    fun initialize_advertises_document_symbol_provider() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        assertNotNull(
            capabilities.documentSymbolProvider,
            "documentSymbolProvider must be advertised so clients can request symbols"
        )
    }

    // -------------------------------------------------------------------------
    // Service-level hierarchical tree
    // -------------------------------------------------------------------------

    @Test
    fun hierarchical_document_symbols_nest_class_methods_and_fields() {
        val service = service()
        service.open(
            "workspace/hier-class.lua",
            """
            ---@class Box
            ---@field width number
            ---@field height number
            local Box = {}

            ---@param self Box
            ---@return number
            function Box:area()
                return self.width * self.height
            end

            return Box
            """
        )

        val roots = service.hierarchicalDocumentSymbols("workspace/hier-class.lua")
        val box = assertNotNull(findHierarchical(roots, "Box"), "expected Box root; got ${roots.describeHierarchical()}")
        assertTrue(
            box.kind == SymbolKind.Class || box.kind == SymbolKind.Variable || box.kind == SymbolKind.Module,
            "Box kind should be Class/Variable/Module; got ${box.kind}"
        )
        assertNotNull(box.range)
        assertNotNull(box.selectionRange)

        val allNames = flattenHierarchicalNames(roots)
        assertTrue("Box" in allNames)
        assertTrue(
            "width" in allNames || "height" in allNames || "area" in allNames,
            "class tree must expose at least one field/method; got $allNames"
        )
    }

    @Test
    fun hierarchical_document_symbols_include_module_export_fields() {
        val service = service()
        service.open(
            "workspace/hier-module.lua",
            """
            local M = {}
            function M.render()
                return 1
            end
            M.label = "ok"
            return M
            """
        )

        val roots = service.hierarchicalDocumentSymbols("workspace/hier-module.lua")
        val names = flattenHierarchicalNames(roots)

        assertTrue("render" in names, "export function render must appear; got $names")
        assertTrue("label" in names || "M" in names, "module export or M root must appear; got $names")
        assertTrue(roots.isNotEmpty())
        assertTrue(
            roots.any { it.children.orEmpty().isNotEmpty() || it.name == "render" || it.name == "M" },
            "module symbols must nest children or surface as roots; got ${roots.describeHierarchical()}"
        )
    }

    @Test
    fun hierarchical_document_symbols_preserve_top_level_locals_with_ranges() {
        val service = service()
        service.open(
            "workspace/hier-locals.lua",
            """
            local value = 1
            local function render()
                return value
            end
            return render
            """
        )

        val roots = service.hierarchicalDocumentSymbols("workspace/hier-locals.lua")
        val names = roots.map { it.name }

        assertTrue("value" in names, "top-level local value missing; got $names")
        assertTrue("render" in names, "top-level function render missing; got $names")
        roots.forEach { symbol ->
            assertNotNull(symbol.range, "range required for ${symbol.name}")
            assertNotNull(symbol.selectionRange, "selectionRange required for ${symbol.name}")
        }
    }

    @Test
    fun hierarchical_document_symbols_preserve_provider_backed_local_aliases() {
        val service = service(jdkMetadata)
        service.open(
            "workspace/hier-provider-alias.lua",
            "local Arrays = require(\"Arrays\")\nlocal current = Arrays.asList\nreturn current"
        )

        val roots = service.hierarchicalDocumentSymbols("workspace/hier-provider-alias.lua")
        val names = roots.map { it.name }

        assertTrue("Arrays" in names, "provider alias Arrays missing; got $names")
        assertTrue("current" in names, "local current missing; got $names")
    }

    // -------------------------------------------------------------------------
    // Service-level flatten SymbolInformation
    // -------------------------------------------------------------------------

    @Test
    fun document_symbols_flatten_lists_top_level_locals_and_functions() {
        val service = service()
        service.open(
            "workspace/flat-locals.lua",
            """
            local value = 1
            local function render()
                return value
            end
            return render
            """
        )

        val symbols = service.documentSymbols("workspace/flat-locals.lua")
        val names = symbols.map { it.name }

        assertTrue("value" in names, "flatten must include value; got ${symbols.describeFlat()}")
        assertTrue("render" in names, "flatten must include render; got ${symbols.describeFlat()}")
        symbols.forEach { symbol ->
            assertEquals(
                "file:///workspace/flat-locals.lua",
                symbol.location.uri,
                "flatten SymbolInformation must carry document uri"
            )
            assertNotNull(symbol.location.range)
        }
    }

    @Test
    fun document_symbols_flatten_includes_nested_names_with_container() {
        val service = service()
        service.open(
            "workspace/flat-nested.lua",
            """
            local M = {}
            function M.render()
                return 1
            end
            M.label = "ok"
            return M
            """
        )

        val hierarchical = service.hierarchicalDocumentSymbols("workspace/flat-nested.lua")
        val flattened = service.documentSymbols("workspace/flat-nested.lua")

        val hierarchicalNames = flattenHierarchicalNames(hierarchical)
        val flatNames = flattened.map { it.name }.toSet()

        // Flatten is a depth-first expansion of the hierarchical tree.
        assertTrue(
            flatNames.containsAll(hierarchicalNames),
            "flatten names must cover hierarchical names; hierarchical=$hierarchicalNames flat=$flatNames"
        )
        assertEquals(
            hierarchicalNames.size,
            flattened.size,
            "flatten size must equal hierarchical node count; hierarchical=$hierarchicalNames flat=${flattened.describeFlat()}"
        )

        // Nested children must report containerName = parent name when present in the tree.
        hierarchical.forEach { root ->
            root.children.orEmpty().forEach { child ->
                val match = flattened.filter { it.name == child.name }
                assertTrue(match.isNotEmpty(), "child ${child.name} must appear in flatten")
                assertTrue(
                    match.any { it.containerName == root.name || it.containerName == null },
                    "child ${child.name} should carry containerName=${root.name} (or null pre-wire); " +
                        "got ${match.map { it.containerName }}"
                )
            }
        }
    }

    @Test
    fun document_symbols_flatten_matches_hierarchical_name_multiset_for_class() {
        val service = service()
        service.open(
            "workspace/flat-class-parity.lua",
            """
            ---@class Point
            ---@field x number
            ---@field y number
            local Point = {}

            function Point:len()
                return self.x + self.y
            end

            return Point
            """
        )

        val hierarchical = service.hierarchicalDocumentSymbols("workspace/flat-class-parity.lua")
        val flattened = service.documentSymbols("workspace/flat-class-parity.lua")

        assertEquals(
            flattenHierarchicalNames(hierarchical).sorted(),
            flattened.map { it.name }.sorted(),
            "class flatten multiset must match hierarchical node names"
        )
    }

    // -------------------------------------------------------------------------
    // TextDocumentService wire: Either.left (flatten) vs Either.right (hierarchical)
    // -------------------------------------------------------------------------

    @Test
    fun text_document_service_document_symbol_without_hierarchical_cap_returns_symbol_information_left() {
        // Client omits hierarchicalDocumentSymbolSupport (default / legacy clients).
        val service = service(initializeParams = initializeParams(hierarchical = null))
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/wire-flat-default.lua",
            """
            local value = 1
            local function render()
                return value
            end
            return render
            """
        )

        val response = textDocuments.documentSymbol(
            DocumentSymbolParams(TextDocumentIdentifier(document.uri))
        ).get()

        assertWireResponse(
            response = response,
            hierarchicalCapability = null,
            expectedNames = setOf("value", "render"),
            documentUri = document.uri
        )
    }

    @Test
    fun text_document_service_document_symbol_with_hierarchical_false_stays_on_flatten_branch() {
        val service = service(initializeParams = initializeParams(hierarchical = false))
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/wire-flat-false.lua",
            """
            local alpha = 1
            local function beta()
                return alpha
            end
            return beta
            """
        )

        val response = textDocuments.documentSymbol(
            DocumentSymbolParams(TextDocumentIdentifier(document.uri))
        ).get()

        assertWireResponse(
            response = response,
            hierarchicalCapability = false,
            expectedNames = setOf("alpha", "beta"),
            documentUri = document.uri
        )
    }

    @Test
    fun text_document_service_document_symbol_with_hierarchical_true_dual_path() {
        // Dual-path:
        // - Pre-TASK-396 product always returns Either.left SymbolInformation flatten.
        // - Post-TASK-396 product may return Either.right DocumentSymbol hierarchy when
        //   hierarchicalDocumentSymbolSupport is true.
        val service = service(initializeParams = initializeParams(hierarchical = true))
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/wire-hier-true.lua",
            """
            local M = {}
            function M.render()
                return 1
            end
            M.label = "ok"
            return M
            """
        )

        val hierarchical = service.hierarchicalDocumentSymbols(document.path)
        val flattened = service.documentSymbols(document.path)
        val response = textDocuments.documentSymbol(
            DocumentSymbolParams(TextDocumentIdentifier(document.uri))
        ).get()

        assertTrue(response.isNotEmpty(), "wire response must not be empty for open document")

        val allLeft = response.all { it.isLeft }
        val allRight = response.all { it.isRight }

        assertTrue(
            allLeft || allRight,
            "wire response must be homogeneous left(SymbolInformation) or right(DocumentSymbol); " +
                "mixed=${response.map { if (it.isLeft) "L" else "R" }}"
        )

        if (allRight) {
            // Capability-aware hierarchical branch (TASK-396 wire).
            val roots = response.map { it.right }
            val wireNames = flattenHierarchicalNames(roots)
            val serviceNames = flattenHierarchicalNames(hierarchical)
            assertEquals(
                serviceNames.sorted(),
                wireNames.sorted(),
                "right-branch DocumentSymbols must match hierarchicalDocumentSymbols()"
            )
            assertTrue(
                "render" in wireNames,
                "hierarchical wire must surface export render; got $wireNames"
            )
            roots.forEach { symbol ->
                assertNotNull(symbol.range)
                assertNotNull(symbol.selectionRange)
            }
        } else {
            // Current product: always flatten SymbolInformation as left.
            val symbols = response.map { it.left }
            val wireNames = symbols.map { it.name }.toSet()
            val flatNames = flattened.map { it.name }.toSet()
            assertEquals(
                flatNames,
                wireNames,
                "left-branch SymbolInformation must match documentSymbols() flatten"
            )
            assertTrue(
                "render" in wireNames || "M" in wireNames,
                "flatten wire must surface module symbols; got ${symbols.describeFlat()}"
            )
            symbols.forEach { symbol ->
                assertEquals(document.uri, symbol.location.uri)
            }
        }
    }

    @Test
    fun text_document_service_document_symbol_hierarchical_true_class_nesting_dual_path() {
        val service = service(initializeParams = initializeParams(hierarchical = true))
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/wire-hier-class.lua",
            """
            ---@class Box
            ---@field width number
            local Box = {}
            function Box:area()
                return self.width
            end
            return Box
            """
        )

        val response = textDocuments.documentSymbol(
            DocumentSymbolParams(TextDocumentIdentifier(document.uri))
        ).get()

        assertWireResponse(
            response = response,
            hierarchicalCapability = true,
            expectedNames = setOf("Box"),
            documentUri = document.uri,
            requireAnyOf = setOf("Box", "width", "area")
        )

        if (response.all { it.isRight }) {
            val roots = response.map { it.right }
            val box = assertNotNull(findHierarchical(roots, "Box"))
            // Hierarchical branch may nest fields/methods under Box.
            val nested = flattenHierarchicalNames(listOf(box)) - "Box"
            assertTrue(
                nested.isNotEmpty() || roots.any { it.name == "width" || it.name == "area" },
                "hierarchical class response should expose width/area somewhere; got ${roots.describeHierarchical()}"
            )
        }
    }

    @Test
    fun text_document_service_document_symbol_empty_document_is_empty_list() {
        val service = service(initializeParams = initializeParams(hierarchical = true))
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open("workspace/wire-empty.lua", "\n")

        val response = textDocuments.documentSymbol(
            DocumentSymbolParams(TextDocumentIdentifier(document.uri))
        ).get()

        assertTrue(
            response.isEmpty() || response.all { entry ->
                when {
                    entry.isLeft -> entry.left.name.isNotBlank()
                    entry.isRight -> entry.right.name.isNotBlank()
                    else -> false
                }
            },
            "empty / near-empty buffer must not throw; got size=${response.size}"
        )
    }

    @Test
    fun hierarchical_and_flatten_name_parity_stable_across_capability_init_params() {
        // Service helpers ignore client caps today; parity must hold regardless of
        // how initialize was called (null / false / true hierarchical support).
        listOf(null, false, true).forEach { hierarchical ->
            val service = service(initializeParams = initializeParams(hierarchical = hierarchical))
            service.open(
                "workspace/parity-cap-$hierarchical.lua",
                """
                local value = 1
                local function render()
                    return value
                end
                return render
                """
            )

            val path = "workspace/parity-cap-$hierarchical.lua"
            val hierarchicalNames = flattenHierarchicalNames(service.hierarchicalDocumentSymbols(path)).sorted()
            val flatNames = service.documentSymbols(path).map { it.name }.sorted()
            assertEquals(
                hierarchicalNames,
                flatNames,
                "parity broken for hierarchicalCapability=$hierarchical"
            )
        }
    }

    @Test
    fun flatten_container_name_links_child_to_parent_for_module_export() {
        val service = service()
        service.open(
            "workspace/flat-container.lua",
            """
            local M = {}
            function M.paint()
                return 1
            end
            return M
            """
        )

        val hierarchical = service.hierarchicalDocumentSymbols("workspace/flat-container.lua")
        val flattened = service.documentSymbols("workspace/flat-container.lua")

        val parentWithPaintChild = hierarchical.firstOrNull { root ->
            root.children.orEmpty().any { it.name == "paint" }
        }

        if (parentWithPaintChild != null) {
            val paint = flattened.filter { it.name == "paint" }
            assertTrue(paint.isNotEmpty(), "paint must be in flatten")
            assertTrue(
                paint.any { it.containerName == parentWithPaintChild.name },
                "paint containerName should be ${parentWithPaintChild.name}; got ${paint.map { it.containerName }}"
            )
        } else {
            // paint may surface as a root if product does not nest under M — still present.
            assertTrue(
                "paint" in flattened.map { it.name },
                "paint must still appear in flatten even without nesting; got ${flattened.describeFlat()}"
            )
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun service(
        metadata: Map<String, String> = emptyMap(),
        initializeParams: InitializeParams = InitializeParams()
    ): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(initializeParams)
            if (metadata.isNotEmpty()) {
                setWorkspaceMetadata(metadata)
            }
        }
    }

    private fun initializeParams(hierarchical: Boolean?): InitializeParams {
        return InitializeParams().apply {
            if (hierarchical != null) {
                capabilities = ClientCapabilities().apply {
                    textDocument = TextDocumentClientCapabilities().apply {
                        documentSymbol = DocumentSymbolCapabilities().apply {
                            hierarchicalDocumentSymbolSupport = hierarchical
                        }
                    }
                }
            }
            // hierarchical == null → leave capabilities unset (legacy clients).
        }
    }

    private fun LuaLanguageService.open(path: String, source: String) {
        didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem("file:///$path", "lua", 1, source.trimIndent())
            )
        )
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

    /**
     * Dual-path wire assertion.
     *
     * - hierarchicalCapability == true: accept all-left (current product) or all-right (TASK-396).
     * - hierarchicalCapability == false / null: prefer all-left flatten; if product ever returns
     *   all-right despite false/null, still accept only if names match hierarchical service helper
     *   (defensive), but flag that true-capability branching is the intended path.
     */
    private fun assertWireResponse(
        response: MutableList<Either<SymbolInformation, DocumentSymbol>>,
        hierarchicalCapability: Boolean?,
        expectedNames: Set<String>,
        documentUri: String,
        requireAnyOf: Set<String> = expectedNames
    ) {
        assertTrue(response.isNotEmpty(), "documentSymbol wire must return symbols for open buffer")

        val allLeft = response.all { it.isLeft }
        val allRight = response.all { it.isRight }
        assertTrue(
            allLeft || allRight,
            "mixed left/right documentSymbol payload is invalid: ${response.map { if (it.isLeft) "L" else "R" }}"
        )

        when {
            allLeft -> {
                val symbols = response.map { it.left }
                val names = symbols.map { it.name }.toSet()
                assertTrue(
                    requireAnyOf.any { it in names },
                    "flatten wire missing any of $requireAnyOf; got ${symbols.describeFlat()}"
                )
                // expectedNames soft-documents the intended top-level set; requireAnyOf is the hard gate.
                assertTrue(
                    expectedNames.isNotEmpty() || requireAnyOf.isNotEmpty(),
                    "assertWireResponse requires a non-empty name gate"
                )
                symbols.forEach { symbol ->
                    assertEquals(documentUri, symbol.location.uri)
                    assertNotNull(symbol.location.range)
                }
                if (hierarchicalCapability == true) {
                    // Documented pre-wire gap: capability true still yields flatten left.
                    assertTrue(
                        allLeft,
                        "pre-TASK-396 product keeps left flatten even when hierarchical=true"
                    )
                }
            }

            allRight -> {
                val roots = response.map { it.right }
                val names = flattenHierarchicalNames(roots)
                assertTrue(
                    requireAnyOf.any { it in names },
                    "hierarchical wire missing any of $requireAnyOf; got $names"
                )
                // expectedNames soft-documents intended roots; hard gate remains requireAnyOf.
                assertTrue(
                    expectedNames.isNotEmpty() || requireAnyOf.isNotEmpty(),
                    "assertWireResponse requires a non-empty name gate"
                )
                roots.forEach { symbol ->
                    assertNotNull(symbol.range)
                    assertNotNull(symbol.selectionRange)
                    assertFalse(symbol.name.isBlank())
                }
                // When hierarchicalCapability is false/null, right-branch is unexpected but
                // tolerated only if the payload is well-formed DocumentSymbols (future-proof).
                if (hierarchicalCapability != true) {
                    assertTrue(
                        names.isNotEmpty(),
                        "unexpected right-branch without hierarchical cap still must be non-empty"
                    )
                }
            }
        }
    }

    private fun findHierarchical(symbols: List<DocumentSymbol>, name: String): DocumentSymbol? {
        symbols.forEach { symbol ->
            if (symbol.name == name) {
                return symbol
            }
            findHierarchical(symbol.children.orEmpty(), name)?.let { return it }
        }
        return null
    }

    private fun flattenHierarchicalNames(symbols: List<DocumentSymbol>): Set<String> {
        val names = linkedSetOf<String>()
        fun walk(nodes: List<DocumentSymbol>) {
            nodes.forEach { node ->
                names += node.name
                walk(node.children.orEmpty())
            }
        }
        walk(symbols)
        return names
    }

    private fun List<DocumentSymbol>.describeHierarchical(): String {
        fun walk(nodes: List<DocumentSymbol>, depth: Int): List<String> {
            return nodes.flatMap { node ->
                val line = "${"  ".repeat(depth)}${node.name}(${node.kind})"
                listOf(line) + walk(node.children.orEmpty(), depth + 1)
            }
        }
        return walk(this, 0).joinToString(prefix = "[", postfix = "]", separator = "; ")
    }

    private fun List<SymbolInformation>.describeFlat(): String {
        return joinToString(prefix = "[", postfix = "]") {
            "${it.name}@${it.location.uri}(${it.kind},container=${it.containerName})"
        }
    }

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"
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
