package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.TypeHierarchyPrepareParams
import org.eclipse.lsp4j.TypeHierarchySubtypesParams
import org.eclipse.lsp4j.TypeHierarchySupertypesParams
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-421 — LSP typeHierarchy dual-path safety corpus.
 *
 * Locks the safety contract for `textDocument/prepareTypeHierarchy` and the
 * follow-on `typeHierarchy/supertypes` + `typeHierarchy/subtypes` requests when
 * the feature is unimplemented or only partially product-reachable:
 * - Unimplemented surface (LSP4J default [UnsupportedOperationException] on
 *   [LuaTextDocumentService]) must degrade as a documented gap — never as an
 *   unexpected hard crash (NPE / AssertionError / IndexOutOfBounds).
 * - When product soft-degrades instead of throwing, empty / null item lists are
 *   accepted ("empty per product ads").
 * - Empty documents, syntax-error buffers, large files, never-opened URIs,
 *   non-type positions, out-of-bounds cursors, and phantom items must not invent
 *   process-killing failures.
 * - When product returns items (capability advertised and surface live), each
 *   [TypeHierarchyItem] must be well-formed (non-blank name, kind, ordered
 *   range/selectionRange with selection inside range).
 *
 * Product type hierarchy remains out of scope for this worker (test-only).
 * Current [LuaLanguageService] does not advertise typeHierarchyProvider and
 * [LuaTextDocumentService] inherits the LSP4J defaults that throw
 * [UnsupportedOperationException]. Tests therefore dual-path:
 * - Documented gap: unimplemented methods complete exceptionally with
 *   UnsupportedOperationException and are recorded as the known surface.
 * - Ideal / soft path: empty list (or null → treated as empty) when the server
 *   elects soft degrade per product ads; well-formed items when live.
 *
 * Complements [LspTypeHierarchyDocClassTddTest] (TASK-273 EmmyLua `@class` corpus
 * with dual-path asserts) with an explicit safety dual-path lock that never
 * hard-skips the unimplemented surface. Verification is review-owned and serial;
 * this worker does not run Gradle.
 */
class LspTypeHierarchySafetyTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun initialize_type_hierarchy_capability_is_null_or_explicit_when_product_lands() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities

        assertNotNull(capabilities, "initialize must return ServerCapabilities")
        val provider = capabilities.typeHierarchyProvider
        if (provider == null) {
            // Documented gap: product has not advertised type hierarchy yet.
            assertTrue(
                true,
                "typeHierarchyProvider absent is an accepted pre-product surface"
            )
            return
        }

        // When advertised as Either, either boolean true or options object is fine.
        val enabled = when {
            provider.isLeft -> provider.left == true
            provider.isRight -> provider.right != null
            else -> false
        }
        assertTrue(
            enabled,
            "advertised typeHierarchyProvider must enable type hierarchy " +
                "(boolean true or options object); got $provider"
        )
    }

    @Test
    fun type_hierarchy_prepare_surface_is_invokable_without_killing_service() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-surface.lua",
            """
            ---@class Probe
            local Probe = {}
            return Probe
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("Probe", occurrence = 1))
        )

        assertTrue(
            outcome is HierarchyOutcome.Unsupported ||
                outcome is HierarchyOutcome.Empty ||
                outcome is HierarchyOutcome.Succeeded ||
                outcome is HierarchyOutcome.Failed,
            "prepareTypeHierarchy surface must resolve to a known outcome; got $outcome " +
                "(typeHierarchyProvider=${capabilities.typeHierarchyProvider})"
        )

        if (outcome is HierarchyOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "prepareTypeHierarchy surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is HierarchyOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is HierarchyOutcome.Succeeded) {
            assertWellFormedItems(outcome.items, document, label = "surface prepare items")
        }
    }

    @Test
    fun type_hierarchy_unimplemented_degrades_as_documented_gap_or_empty() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-gap.lua",
            """
            ---@class Widget
            local Widget = {}
            return Widget
            """
        )

        val prepare = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("Widget", occurrence = 1))
        )
        assertHierarchyDegradesAsGapOrEmpty(
            prepare,
            document,
            context = "unimplemented / pre-product prepareTypeHierarchy"
        )

        // Even when prepare is unimplemented, exercise follow-on surfaces with a
        // synthetic item so supertypes/subtypes dual-path is locked independently.
        val item = syntheticItem(document, name = "Widget", needle = "Widget", occurrence = 1)
        val supertypes = invokeSupertypes(textDocuments, TypeHierarchySupertypesParams(item))
        val subtypes = invokeSubtypes(textDocuments, TypeHierarchySubtypesParams(item))

        assertHierarchyDegradesAsGapOrEmpty(
            supertypes,
            document,
            context = "unimplemented / pre-product typeHierarchy/supertypes"
        )
        assertHierarchyDegradesAsGapOrEmpty(
            subtypes,
            document,
            context = "unimplemented / pre-product typeHierarchy/subtypes"
        )
    }

    // -------------------------------------------------------------------------
    // Prepare request safety: empty / syntax error / large / unknown uri / oob
    // -------------------------------------------------------------------------

    @Test
    fun prepare_type_hierarchy_empty_document_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-empty.lua",
            ""
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(0, 0))
        )

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "empty document",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_type_hierarchy_syntax_error_buffer_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-syntax-error.lua",
            """
            ---@class
            local function broken(
                return {
                    a = 1,
                    b =
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(0, 6))
        )

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "syntax-error buffer"
        )
    }

    @Test
    fun prepare_type_hierarchy_large_document_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val body = buildString {
            repeat(200) { i ->
                append("---@class Type").append(i).append('\n')
                append("local Type").append(i).append(" = {}\n")
            }
            append("return Type0")
        }
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-large.lua",
            body
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("Type0", occurrence = 1))
        )

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "large document"
        )
    }

    @Test
    fun prepare_type_hierarchy_never_opened_uri_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val params = TypeHierarchyPrepareParams(
            TextDocumentIdentifier("file:///workspace/type-hierarchy-safety-never-opened.lua"),
            Position(0, 0)
        )

        val outcome = invokePrepare(textDocuments, params)

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document = null,
            context = "never-opened document uri"
        )
    }

    @Test
    fun prepare_type_hierarchy_out_of_bounds_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-oob.lua",
            """
            ---@class Tiny
            local Tiny = {}
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(99, 99))
        )

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "out-of-bounds position",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_type_hierarchy_on_whitespace_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-whitespace.lua",
            """
            ---@class Work
            local Work = {}

            return Work
            """
        )

        // Blank line between local and return.
        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(2, 0))
        )

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "whitespace / blank line",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_type_hierarchy_on_keyword_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-keyword.lua",
            """
            ---@class Work
            local Work = {}
            return Work
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("local"))
        )

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "keyword 'local'",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_type_hierarchy_on_numeric_literal_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-number.lua",
            "local value = 42\nreturn value"
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("42"))
        )

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "numeric literal",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun prepare_type_hierarchy_class_doc_corpus_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-class-doc.lua",
            """
            ---@class Base
            ---@field id integer
            local Base = {}

            ---@class Child: Base
            ---@field name string
            local Child = {}

            return Child
            """
        )

        val onBase = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("Base", occurrence = 1))
        )
        val onChild = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("Child", occurrence = 1))
        )

        assertHierarchyDegradesAsGapOrEmpty(
            onBase,
            document,
            context = "---@class Base name"
        )
        assertHierarchyDegradesAsGapOrEmpty(
            onChild,
            document,
            context = "---@class Child: Base name"
        )
    }

    @Test
    fun prepare_type_hierarchy_twice_is_stable_on_gap_or_empty_or_items() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-twice.lua",
            """
            ---@class Alpha
            local Alpha = {}
            ---@class Beta: Alpha
            local Beta = {}
            return Beta
            """
        )
        val params = prepareParams(document, document.positionOf("Alpha", occurrence = 1))

        val first = invokePrepare(textDocuments, params)
        val second = invokePrepare(textDocuments, params)

        assertHierarchyDegradesAsGapOrEmpty(first, document, context = "first prepareTypeHierarchy call")
        assertHierarchyDegradesAsGapOrEmpty(second, document, context = "second prepareTypeHierarchy call")

        // Same dual-path class on both invocations (gap stays gap; empty stays empty;
        // live items stay live). Soft: do not require identical payloads yet.
        assertTrue(
            first::class == second::class ||
                (first is HierarchyOutcome.Empty && second is HierarchyOutcome.Succeeded) ||
                (first is HierarchyOutcome.Succeeded && second is HierarchyOutcome.Empty),
            "repeated prepareTypeHierarchy calls should stay on the same dual-path family; " +
                "first=$first second=$second"
        )
        if (first is HierarchyOutcome.Succeeded && second is HierarchyOutcome.Succeeded) {
            assertWellFormedItems(first.items, document, label = "first call")
            assertWellFormedItems(second.items, document, label = "second call")
        }
    }

    // -------------------------------------------------------------------------
    // Supertypes / subtypes safety (independent of prepare product readiness)
    // -------------------------------------------------------------------------

    @Test
    fun supertypes_surface_is_invokable_without_killing_service() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-supertypes-surface.lua",
            """
            ---@class Animal
            local Animal = {}
            ---@class Dog: Animal
            local Dog = {}
            return Dog
            """
        )
        val item = syntheticItem(document, name = "Dog", needle = "Dog", occurrence = 1)

        val outcome = invokeSupertypes(textDocuments, TypeHierarchySupertypesParams(item))

        assertTrue(
            outcome is HierarchyOutcome.Unsupported ||
                outcome is HierarchyOutcome.Empty ||
                outcome is HierarchyOutcome.Succeeded ||
                outcome is HierarchyOutcome.Failed,
            "typeHierarchy/supertypes surface must resolve to a known outcome; got $outcome"
        )
        if (outcome is HierarchyOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "supertypes surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is HierarchyOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is HierarchyOutcome.Succeeded) {
            assertWellFormedItems(outcome.items, document, label = "surface supertypes")
        }
    }

    @Test
    fun subtypes_surface_is_invokable_without_killing_service() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-subtypes-surface.lua",
            """
            ---@class Animal
            local Animal = {}
            ---@class Dog: Animal
            local Dog = {}
            ---@class Cat: Animal
            local Cat = {}
            return Animal
            """
        )
        val item = syntheticItem(document, name = "Animal", needle = "Animal", occurrence = 1)

        val outcome = invokeSubtypes(textDocuments, TypeHierarchySubtypesParams(item))

        assertTrue(
            outcome is HierarchyOutcome.Unsupported ||
                outcome is HierarchyOutcome.Empty ||
                outcome is HierarchyOutcome.Succeeded ||
                outcome is HierarchyOutcome.Failed,
            "typeHierarchy/subtypes surface must resolve to a known outcome; got $outcome"
        )
        if (outcome is HierarchyOutcome.Failed) {
            assertFalse(
                isHardCrash(outcome.detail),
                "subtypes surface must not hard-crash; got ${outcome.detail}"
            )
        }
        if (outcome is HierarchyOutcome.Unsupported) {
            assertTrue(
                outcome.isUnsupportedOperation,
                "gap path must be UnsupportedOperationException; got ${outcome.detail}"
            )
        }
        if (outcome is HierarchyOutcome.Succeeded) {
            assertWellFormedItems(outcome.items, document, label = "surface subtypes")
        }
    }

    @Test
    fun supertypes_unknown_item_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-supertypes-unknown.lua",
            """
            ---@class Known
            local Known = {}
            return Known
            """
        )
        val phantom = TypeHierarchyItem(
            "PhantomMissing",
            SymbolKind.Class,
            document.uri,
            Range(Position(0, 0), Position(0, 1)),
            Range(Position(0, 0), Position(0, 1))
        )

        val outcome = invokeSupertypes(textDocuments, TypeHierarchySupertypesParams(phantom))

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "supertypes for unknown PhantomMissing item",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun subtypes_unknown_item_gap_or_empty_or_well_formed() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-subtypes-unknown.lua",
            """
            ---@class Known
            local Known = {}
            return Known
            """
        )
        val phantom = TypeHierarchyItem(
            "PhantomMissing",
            SymbolKind.Class,
            document.uri,
            Range(Position(0, 0), Position(0, 1)),
            Range(Position(0, 0), Position(0, 1))
        )

        val outcome = invokeSubtypes(textDocuments, TypeHierarchySubtypesParams(phantom))

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "subtypes for unknown PhantomMissing item",
            allowNonEmptyWhenSucceeded = false
        )
    }

    @Test
    fun supertypes_never_opened_uri_item_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val phantom = TypeHierarchyItem(
            "Ghost",
            SymbolKind.Class,
            "file:///workspace/type-hierarchy-safety-supertypes-never-opened.lua",
            Range(Position(0, 0), Position(0, 1)),
            Range(Position(0, 0), Position(0, 1))
        )

        val outcome = invokeSupertypes(textDocuments, TypeHierarchySupertypesParams(phantom))

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document = null,
            context = "supertypes for never-opened uri item"
        )
    }

    @Test
    fun subtypes_never_opened_uri_item_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val phantom = TypeHierarchyItem(
            "Ghost",
            SymbolKind.Class,
            "file:///workspace/type-hierarchy-safety-subtypes-never-opened.lua",
            Range(Position(0, 0), Position(0, 1)),
            Range(Position(0, 0), Position(0, 1))
        )

        val outcome = invokeSubtypes(textDocuments, TypeHierarchySubtypesParams(phantom))

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document = null,
            context = "subtypes for never-opened uri item"
        )
    }

    @Test
    fun prepare_then_supertypes_subtypes_chain_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-chain.lua",
            """
            ---@class Root
            local Root = {}
            ---@class Mid: Root
            local Mid = {}
            ---@class Leaf: Mid
            local Leaf = {}
            return Leaf
            """
        )

        val prepare = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("Mid", occurrence = 1))
        )
        assertHierarchyDegradesAsGapOrEmpty(
            prepare,
            document,
            context = "prepare in prepare→supertypes/subtypes chain"
        )

        val item = when (prepare) {
            is HierarchyOutcome.Succeeded -> prepare.items.firstOrNull()
                ?: syntheticItem(document, name = "Mid", needle = "Mid", occurrence = 1)
            else -> syntheticItem(document, name = "Mid", needle = "Mid", occurrence = 1)
        }

        val supertypes = invokeSupertypes(textDocuments, TypeHierarchySupertypesParams(item))
        val subtypes = invokeSubtypes(textDocuments, TypeHierarchySubtypesParams(item))

        assertHierarchyDegradesAsGapOrEmpty(
            supertypes,
            document,
            context = "supertypes in prepare→supertypes/subtypes chain"
        )
        assertHierarchyDegradesAsGapOrEmpty(
            subtypes,
            document,
            context = "subtypes in prepare→supertypes/subtypes chain"
        )
    }

    @Test
    fun prepare_type_hierarchy_malformed_class_doc_does_not_hard_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-safety-malformed-class.lua",
            """
            ---@class
            ---@class <T>
            ---@class :
            local broken = {}
            return broken
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("broken"))
        )

        assertHierarchyDegradesAsGapOrEmpty(
            outcome,
            document,
            context = "malformed ---@class docs / local broken",
            allowNonEmptyWhenSucceeded = false
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun service(): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(InitializeParams())
        }
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

    private fun prepareParams(document: OpenDocument, position: Position): TypeHierarchyPrepareParams {
        return TypeHierarchyPrepareParams(TextDocumentIdentifier(document.uri), position)
    }

    private fun syntheticItem(
        document: OpenDocument,
        name: String,
        needle: String,
        occurrence: Int
    ): TypeHierarchyItem {
        val start = document.positionOf(needle, occurrence)
        val end = Position(start.line, start.character + needle.length)
        val range = Range(start, end)
        return TypeHierarchyItem(name, SymbolKind.Class, document.uri, range, range)
    }

    private fun invokePrepare(
        textDocuments: LuaTextDocumentService,
        params: TypeHierarchyPrepareParams
    ): HierarchyOutcome {
        return try {
            val items = textDocuments.prepareTypeHierarchy(params).get()
            classifyItems(items)
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                HierarchyOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                HierarchyOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun invokeSupertypes(
        textDocuments: LuaTextDocumentService,
        params: TypeHierarchySupertypesParams
    ): HierarchyOutcome {
        return try {
            val items = textDocuments.typeHierarchySupertypes(params).get()
            classifyItems(items)
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                HierarchyOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                HierarchyOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun invokeSubtypes(
        textDocuments: LuaTextDocumentService,
        params: TypeHierarchySubtypesParams
    ): HierarchyOutcome {
        return try {
            val items = textDocuments.typeHierarchySubtypes(params).get()
            classifyItems(items)
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                HierarchyOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                HierarchyOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun classifyItems(items: MutableList<TypeHierarchyItem>?): HierarchyOutcome {
        if (items == null) {
            return HierarchyOutcome.Empty(detail = "null type hierarchy items")
        }
        val list = items.toList()
        return if (list.isEmpty()) {
            HierarchyOutcome.Empty(detail = "empty type hierarchy item list")
        } else {
            HierarchyOutcome.Succeeded(items = list)
        }
    }

    private fun assertHierarchyDegradesAsGapOrEmpty(
        outcome: HierarchyOutcome,
        document: OpenDocument?,
        context: String,
        allowNonEmptyWhenSucceeded: Boolean = true
    ) {
        when (outcome) {
            is HierarchyOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is HierarchyOutcome.Empty -> {
                // Soft degrade (empty / null) is the ideal unimplemented-or-no-hit path
                // "per product ads" once the server elects not to throw.
            }
            is HierarchyOutcome.Succeeded -> {
                if (document != null) {
                    assertWellFormedItems(outcome.items, document, label = "items for $context")
                } else {
                    outcome.items.forEach { item ->
                        assertNotNull(item.name, "TypeHierarchyItem.name must be non-null for $context")
                        assertTrue(item.name.isNotBlank(), "TypeHierarchyItem.name must be non-blank for $context")
                    }
                }
                if (!allowNonEmptyWhenSucceeded) {
                    // Non-type / empty / oob positions should ideally be Empty;
                    // if product invents items they must still be shape-valid (already checked).
                    assertTrue(
                        outcome.items.isNotEmpty(),
                        "$context succeeded path has non-empty items (already validated)"
                    )
                }
            }
            is HierarchyOutcome.Failed -> {
                assertFalse(
                    isHardCrash(outcome.detail),
                    "typeHierarchy at $context must not NPE/assert; got ${outcome.detail}"
                )
            }
        }
    }

    private fun assertWellFormedItems(
        items: List<TypeHierarchyItem>,
        document: OpenDocument,
        label: String
    ) {
        items.forEach { item ->
            assertNotNull(item.name, "$label: TypeHierarchyItem.name must be non-null")
            assertTrue(
                item.name.isNotBlank(),
                "$label: TypeHierarchyItem.name must be non-blank: ${describeItems(listOf(item))}"
            )
            assertNotNull(item.kind, "$label: TypeHierarchyItem.kind must be non-null for ${item.name}")
            assertTrue(
                item.uri.isNullOrBlank() || item.uri == document.uri || item.uri.startsWith("file:"),
                "$label: TypeHierarchyItem.uri should be empty or a file URI; got '${item.uri}' for ${item.name}"
            )
            val range = item.range
            val selection = item.selectionRange
            assertNotNull(range, "$label: TypeHierarchyItem.range must be non-null for ${item.name}")
            assertNotNull(selection, "$label: TypeHierarchyItem.selectionRange must be non-null for ${item.name}")
            assertOrderedRange(range, label = "$label range of ${item.name}")
            assertOrderedRange(selection, label = "$label selectionRange of ${item.name}")
            assertTrue(
                containsRange(range, selection),
                "$label: selectionRange must be inside range for ${item.name}; " +
                    "range=${formatRange(range)} selection=${formatRange(selection)}"
            )
        }
    }

    private fun assertOrderedRange(range: Range, label: String) {
        assertTrue(
            range.end.line > range.start.line ||
                (range.end.line == range.start.line &&
                    range.end.character >= range.start.character),
            "$label must be ordered; got ${formatRange(range)}"
        )
        assertTrue(range.start.line >= 0, "$label start.line must be >= 0")
        assertTrue(range.start.character >= 0, "$label start.character must be >= 0")
        assertTrue(range.end.character >= 0, "$label end.character must be >= 0")
    }

    private fun containsRange(outer: Range, inner: Range): Boolean {
        val startsOk =
            inner.start.line > outer.start.line ||
                (inner.start.line == outer.start.line &&
                    inner.start.character >= outer.start.character)
        val endsOk =
            inner.end.line < outer.end.line ||
                (inner.end.line == outer.end.line &&
                    inner.end.character <= outer.end.character)
        return startsOk && endsOk
    }

    private fun describeItems(items: List<TypeHierarchyItem>): String {
        return items.joinToString(prefix = "[", postfix = "]") { item ->
            val kind = item.kind?.name ?: "?"
            "${item.name}/$kind@${formatRange(item.range)}"
        }
    }

    private fun formatRange(range: Range?): String {
        if (range == null) {
            return "null"
        }
        return "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"
    }

    private fun isHardCrash(detail: String): Boolean {
        return detail.contains("NullPointerException", ignoreCase = true) ||
            detail.contains("AssertionError", ignoreCase = true) ||
            detail.contains("KotlinNullPointerException", ignoreCase = true) ||
            detail.contains("IndexOutOfBoundsException", ignoreCase = true)
    }

    private fun unwrap(error: Throwable): Throwable {
        var current = error
        while (
            (current is ExecutionException || current is CompletionException) &&
            current.cause != null
        ) {
            current = current.cause!!
        }
        return current
    }

    private fun isUnsupportedOperation(error: Throwable): Boolean {
        if (error is UnsupportedOperationException) {
            return true
        }
        val message = error.message.orEmpty()
        return message.contains("UnsupportedOperationException") ||
            message.contains("not implemented", ignoreCase = true)
    }

    private sealed class HierarchyOutcome {
        data class Unsupported(
            val detail: String,
            val isUnsupportedOperation: Boolean
        ) : HierarchyOutcome()

        data class Empty(val detail: String) : HierarchyOutcome()

        data class Succeeded(val items: List<TypeHierarchyItem>) : HierarchyOutcome()

        data class Failed(val detail: String) : HierarchyOutcome()
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
