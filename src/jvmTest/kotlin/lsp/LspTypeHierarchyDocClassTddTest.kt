package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
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
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-273 — LSP type hierarchy for EmmyLua `@class` docs corpus.
 *
 * Locks the safety contract for textDocument/prepareTypeHierarchy and the
 * follow-on typeHierarchy/supertypes + typeHierarchy/subtypes requests when the
 * cursor sits on EmmyLua `---@class` names (or unknowns):
 * - prepareTypeHierarchy over documented `@class` symbols degrades safely when
 *   the feature is unimplemented (LSP4J default [UnsupportedOperationException]),
 *   and once implemented returns either null/empty or well-formed
 *   [TypeHierarchyItem]s (name + Class-ish kind + ordered ranges in the file).
 * - Unknown / free names and non-type positions return empty/null without a hard
 *   throw once the surface is live; unimplemented remains a documented gap.
 * - Supertypes / subtypes requests for a synthetic or prepared item likewise
 *   must not crash (empty list / null / documented gap only).
 *
 * Product type hierarchy is intentionally out of scope (test-only). Current
 * [LuaTextDocumentService] inherits the LSP4J defaults that throw
 * [UnsupportedOperationException], and [LuaLanguageService] does not advertise
 * typeHierarchyProvider. Dual-path assertions keep the corpus green until
 * product ships, then harden to empty-without-throw / well-formed item contracts.
 *
 * Verification is review-owned and serial; this worker does not run Gradle.
 */
class LspTypeHierarchyDocClassTddTest {

    // -------------------------------------------------------------------------
    // Capability / surface probe
    // -------------------------------------------------------------------------

    @Test
    fun type_hierarchy_capability_or_documented_gap_when_unimplemented() {
        val service = service()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-capability-probe.lua",
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

        when (outcome) {
            is HierarchyOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for prepareTypeHierarchy; " +
                        "got ${outcome.detail} (typeHierarchyProvider=${capabilities.typeHierarchyProvider})"
                )
            }
            is HierarchyOutcome.Failed -> {
                fail(
                    "prepareTypeHierarchy must not fail hard once surface is reachable: ${outcome.detail}"
                )
            }
            is HierarchyOutcome.Succeeded -> {
                // Capability may lag implementation; any returned items must be well-formed.
                assertWellFormedItems(outcome.items, document)
            }
        }
    }

    // -------------------------------------------------------------------------
    // prepareTypeHierarchy on @class docs
    // -------------------------------------------------------------------------

    @Test
    fun prepare_type_hierarchy_on_class_doc_name_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-class-doc.lua",
            """
            ---@class User
            ---@field id integer
            ---@field name string
            local User = {}
            return User
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("User", occurrence = 1))
        )

        assertPrepareSafeOrGap(
            outcome,
            document = document,
            context = "prepareTypeHierarchy on ---@class User name"
        )
    }

    @Test
    fun prepare_type_hierarchy_on_subclass_doc_with_parent_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-subclass-doc.lua",
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

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("Child", occurrence = 1))
        )

        assertPrepareSafeOrGap(
            outcome,
            document = document,
            context = "prepareTypeHierarchy on ---@class Child: Base"
        )

        if (outcome is HierarchyOutcome.Succeeded && outcome.items.isNotEmpty()) {
            val childItems = outcome.items.filter { it.name.contains("Child") }
            assertTrue(
                childItems.isNotEmpty() || outcome.items.any { it.kind == SymbolKind.Class },
                "When items are returned for Child, expect Class-kind or name containing Child; " +
                    "got ${describe(outcome.items)}"
            )
        }
    }

    @Test
    fun prepare_type_hierarchy_on_generic_class_doc_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-generic-class-doc.lua",
            """
            ---@class Box<T>
            ---@field value T
            local Box = {}
            return Box
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("Box", occurrence = 1))
        )

        assertPrepareSafeOrGap(
            outcome,
            document = document,
            context = "prepareTypeHierarchy on ---@class Box<T>"
        )
    }

    // -------------------------------------------------------------------------
    // Unknown symbols / non-type positions: empty without throw
    // -------------------------------------------------------------------------

    @Test
    fun prepare_type_hierarchy_unknown_symbol_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-unknown.lua",
            """
            ---@class Known
            local Known = {}
            return unknownTypeName
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("unknownTypeName"))
        )

        assertEmptyOrGap(
            outcome,
            context = "unknown free name 'unknownTypeName'"
        )
    }

    @Test
    fun prepare_type_hierarchy_undeclared_class_name_in_annotation_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // `@type` references a class that was never declared with `@class`.
        val document = textDocuments.open(
            "workspace/type-hierarchy-missing-class.lua",
            """
            ---@type MissingClass
            local value = {}
            return value
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("MissingClass"))
        )

        assertEmptyOrGap(
            outcome,
            context = "MissingClass referenced only via ---@type"
        )
    }

    @Test
    fun prepare_type_hierarchy_on_local_number_binding_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-local-number.lua",
            """
            local count = 1
            return count
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("count", occurrence = 1))
        )

        assertEmptyOrGap(
            outcome,
            context = "local number binding is not a type hierarchy root"
        )
    }

    @Test
    fun prepare_type_hierarchy_on_whitespace_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-whitespace.lua",
            """
            ---@class Widget
            local Widget = {}

            return Widget
            """
        )

        // Blank line between local and return — not an identifier / type name.
        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(2, 0))
        )

        assertEmptyOrGap(
            outcome,
            context = "whitespace / blank line between statements"
        )
    }

    @Test
    fun prepare_type_hierarchy_on_keyword_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-keyword.lua",
            """
            ---@class Widget
            local Widget = {}
            return Widget
            """
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("local"))
        )

        assertEmptyOrGap(
            outcome,
            context = "keyword 'local'"
        )
    }

    // -------------------------------------------------------------------------
    // Supertypes / subtypes degrade safely
    // -------------------------------------------------------------------------

    @Test
    fun type_hierarchy_supertypes_for_class_doc_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-supertypes.lua",
            """
            ---@class Animal
            local Animal = {}

            ---@class Dog: Animal
            local Dog = {}

            return Dog
            """
        )

        val prepare = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("Dog", occurrence = 1))
        )

        val item = when (prepare) {
            is HierarchyOutcome.Unsupported -> {
                assertTrue(
                    prepare.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for prepare; got ${prepare.detail}"
                )
                syntheticItem(document, name = "Dog", needle = "Dog", occurrence = 1)
            }
            is HierarchyOutcome.Failed -> {
                fail("prepareTypeHierarchy must not fail hard for Dog: ${prepare.detail}")
            }
            is HierarchyOutcome.Succeeded -> {
                prepare.items.firstOrNull()
                    ?: syntheticItem(document, name = "Dog", needle = "Dog", occurrence = 1)
            }
        }

        val outcome = invokeSupertypes(textDocuments, TypeHierarchySupertypesParams(item))
        assertListSafeOrGap(
            outcome,
            document = document,
            context = "typeHierarchy/supertypes for Dog: Animal"
        )
    }

    @Test
    fun type_hierarchy_subtypes_for_class_doc_degrades_safely() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-subtypes.lua",
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

        val prepare = invokePrepare(
            textDocuments,
            prepareParams(document, document.positionOf("Animal", occurrence = 1))
        )

        val item = when (prepare) {
            is HierarchyOutcome.Unsupported -> {
                assertTrue(
                    prepare.isUnsupportedOperation,
                    "Documented gap expects UnsupportedOperationException for prepare; got ${prepare.detail}"
                )
                syntheticItem(document, name = "Animal", needle = "Animal", occurrence = 1)
            }
            is HierarchyOutcome.Failed -> {
                fail("prepareTypeHierarchy must not fail hard for Animal: ${prepare.detail}")
            }
            is HierarchyOutcome.Succeeded -> {
                prepare.items.firstOrNull()
                    ?: syntheticItem(document, name = "Animal", needle = "Animal", occurrence = 1)
            }
        }

        val outcome = invokeSubtypes(textDocuments, TypeHierarchySubtypesParams(item))
        assertListSafeOrGap(
            outcome,
            document = document,
            context = "typeHierarchy/subtypes for Animal"
        )
    }

    @Test
    fun type_hierarchy_supertypes_unknown_item_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-supertypes-unknown.lua",
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
        assertEmptyListOrGap(
            outcome,
            context = "supertypes for unknown PhantomMissing item"
        )
    }

    @Test
    fun type_hierarchy_subtypes_unknown_item_empty_without_throw_or_gap() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-subtypes-unknown.lua",
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
        assertEmptyListOrGap(
            outcome,
            context = "subtypes for unknown PhantomMissing item"
        )
    }

    // -------------------------------------------------------------------------
    // Malformed / empty buffer safety
    // -------------------------------------------------------------------------

    @Test
    fun prepare_type_hierarchy_empty_document_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/type-hierarchy-empty.lua",
            ""
        )

        val outcome = invokePrepare(
            textDocuments,
            prepareParams(document, Position(0, 0))
        )

        assertEmptyOrGap(
            outcome,
            context = "empty document"
        )
    }

    @Test
    fun prepare_type_hierarchy_malformed_class_doc_does_not_crash() {
        val service = service()
        val textDocuments = LuaTextDocumentService(service)
        // Incomplete / nameless @class tags — product must not throw from the LSP surface.
        val document = textDocuments.open(
            "workspace/type-hierarchy-malformed-class.lua",
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

        assertEmptyOrGap(
            outcome,
            context = "malformed ---@class docs / local broken"
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
            HierarchyOutcome.Succeeded(items.orEmpty())
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
            HierarchyOutcome.Succeeded(items.orEmpty())
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
            HierarchyOutcome.Succeeded(items.orEmpty())
        } catch (error: Throwable) {
            val root = unwrap(error)
            if (isUnsupportedOperation(root)) {
                HierarchyOutcome.Unsupported(detail = root.toString(), isUnsupportedOperation = true)
            } else {
                HierarchyOutcome.Failed(detail = root.toString())
            }
        }
    }

    private fun assertPrepareSafeOrGap(
        outcome: HierarchyOutcome,
        document: OpenDocument,
        context: String
    ) {
        when (outcome) {
            is HierarchyOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is HierarchyOutcome.Failed -> {
                fail("$context must not throw a hard failure: ${outcome.detail}")
            }
            is HierarchyOutcome.Succeeded -> {
                // Empty is a valid degrade for partial implementations; non-empty must be well-formed.
                assertWellFormedItems(outcome.items, document)
            }
        }
    }

    private fun assertEmptyOrGap(outcome: HierarchyOutcome, context: String) {
        when (outcome) {
            is HierarchyOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is HierarchyOutcome.Failed -> {
                fail("$context must not throw; unknown/non-type symbols should be empty without throw: ${outcome.detail}")
            }
            is HierarchyOutcome.Succeeded -> {
                assertTrue(
                    outcome.items.isEmpty(),
                    "Expected empty prepareTypeHierarchy for $context; got ${describe(outcome.items)}"
                )
            }
        }
    }

    private fun assertListSafeOrGap(
        outcome: HierarchyOutcome,
        document: OpenDocument,
        context: String
    ) {
        when (outcome) {
            is HierarchyOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is HierarchyOutcome.Failed -> {
                fail("$context must not throw a hard failure: ${outcome.detail}")
            }
            is HierarchyOutcome.Succeeded -> {
                assertWellFormedItems(outcome.items, document)
            }
        }
    }

    private fun assertEmptyListOrGap(outcome: HierarchyOutcome, context: String) {
        when (outcome) {
            is HierarchyOutcome.Unsupported -> {
                assertTrue(
                    outcome.isUnsupportedOperation,
                    "Documented gap for $context expects UnsupportedOperationException; got ${outcome.detail}"
                )
            }
            is HierarchyOutcome.Failed -> {
                fail("$context must not throw; unknown items should yield empty without throw: ${outcome.detail}")
            }
            is HierarchyOutcome.Succeeded -> {
                assertTrue(
                    outcome.items.isEmpty(),
                    "Expected empty hierarchy list for $context; got ${describe(outcome.items)}"
                )
            }
        }
    }

    private fun assertWellFormedItems(items: List<TypeHierarchyItem>, document: OpenDocument) {
        items.forEach { item ->
            assertNotNull(item.name, "TypeHierarchyItem.name must be non-null")
            assertTrue(item.name.isNotBlank(), "TypeHierarchyItem.name must be non-blank: ${describe(listOf(item))}")
            assertNotNull(item.kind, "TypeHierarchyItem.kind must be non-null for ${item.name}")
            assertTrue(
                item.uri.isNullOrBlank() || item.uri == document.uri || item.uri.startsWith("file:"),
                "TypeHierarchyItem.uri should be empty or a file URI; got '${item.uri}' for ${item.name}"
            )
            val range = item.range
            val selection = item.selectionRange
            assertNotNull(range, "TypeHierarchyItem.range must be non-null for ${item.name}")
            assertNotNull(selection, "TypeHierarchyItem.selectionRange must be non-null for ${item.name}")
            assertOrderedRange(range, label = "range of ${item.name}")
            assertOrderedRange(selection, label = "selectionRange of ${item.name}")
            // selectionRange should be nested inside range when both are present.
            assertTrue(
                containsRange(range, selection),
                "selectionRange must be inside range for ${item.name}; " +
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

    private fun describe(items: List<TypeHierarchyItem>): String {
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

        data class Failed(val detail: String) : HierarchyOutcome()

        data class Succeeded(val items: List<TypeHierarchyItem>) : HierarchyOutcome()
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
