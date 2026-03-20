package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFactsCollector
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleEnvironmentMode
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DocumentFactsCollectorTest {
    @Test
    fun tracks_static_requires_separately_from_dynamic_require_sites() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                require "alpha"
                require("beta")
                require(name)
                local dep = require(prefix .. suffix)
                require()
            """.trimIndent()
        )

        assertEquals(listOf("alpha", "beta"), facts.requires.map { it.moduleName })
        assertEquals(
            listOf(
                DocumentFacts.DynamicRequireKind.NON_STRING_LITERAL,
                DocumentFacts.DynamicRequireKind.NON_STRING_LITERAL,
                DocumentFacts.DynamicRequireKind.MISSING_ARGUMENT
            ),
            facts.dynamicRequires.map { it.kind }
        )
    }

    @Test
    fun collects_legacy_module_calls_and_segments_environment() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                module("pkg.runtime")
                do
                    module("pkg.runtime.seeall", package.seeall)
                end
                local function configure()
                    module("pkg.runtime.inner")
                end
            """.trimIndent()
        )

        assertEquals(
            listOf("pkg.runtime", "pkg.runtime.seeall", "pkg.runtime.inner"),
            facts.legacyModuleCalls.map { it.moduleName }
        )
        assertEquals(
            listOf(true, true, false),
            facts.legacyModuleCalls.map { it.isTopLevel }
        )
        assertEquals(
            listOf(
                ModuleEnvironmentMode.CHUNK,
                ModuleEnvironmentMode.LEGACY_MODULE,
                ModuleEnvironmentMode.LEGACY_MODULE_SEEALL
            ),
            facts.environmentSegments.map { it.mode }
        )
    }

    @Test
    fun records_return_export_hints_for_identifier_and_table() {
        val identifierFacts = collectFacts(
            path = "pkg/id.lua",
            source = """
                local M = {}
                return M
            """.trimIndent()
        )
        val tableFacts = collectFacts(
            path = "pkg/table.lua",
            source = "return { value = 1 }"
        )
        val multiValueFacts = collectFacts(
            path = "pkg/multi.lua",
            source = "return a, b"
        )

        assertEquals(DocumentFacts.ReturnExportShapeKind.IDENTIFIER, identifierFacts.returnHint.kind)
        assertEquals("M", identifierFacts.returnHint.identifierName)
        assertEquals(DocumentFacts.ReturnExportShapeKind.TABLE_LITERAL, tableFacts.returnHint.kind)
        assertEquals(DocumentFacts.ReturnExportShapeKind.MULTI_VALUE, multiValueFacts.returnHint.kind)
    }

    @Test
    fun collects_export_write_anchors_for_member_assignments_and_functions() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                local M = {}
                M.value = 1
                _M["name"] = 2
                function M.run() end
                do
                    M.nested.value = 3
                end
                local function inner()
                    M.hidden = 4
                end
            """.trimIndent()
        )

        assertEquals(
            listOf(
                Triple("M", "value", DocumentFacts.ExportWriteAnchorKind.MEMBER_ASSIGNMENT),
                Triple("_M", "name", DocumentFacts.ExportWriteAnchorKind.INDEX_ASSIGNMENT),
                Triple("M", "run", DocumentFacts.ExportWriteAnchorKind.FUNCTION_DECLARATION),
                Triple("M", "nested.value", DocumentFacts.ExportWriteAnchorKind.MEMBER_ASSIGNMENT)
            ),
            facts.exportWriteAnchors.map {
                Triple(it.rootIdentifier, it.accessPath.joinToString("."), it.kind)
            }
        )
    }

    @Test
    fun collects_top_level_bare_identifier_assignment_and_function_anchors() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                value = 1
                function run() end
            """.trimIndent()
        )

        assertEquals(
            listOf(
                Triple("value", "", DocumentFacts.ExportWriteAnchorKind.BARE_ASSIGNMENT),
                Triple("run", "", DocumentFacts.ExportWriteAnchorKind.BARE_FUNCTION_DECLARATION)
            ),
            facts.exportWriteAnchors.map {
                Triple(it.rootIdentifier, it.accessPath.joinToString("."), it.kind)
            }
        )
    }

    @Test
    fun nested_function_bare_writes_do_not_produce_export_anchors() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                local function inner()
                    value = 1
                    function run() end
                end
            """.trimIndent()
        )

        assertEquals(emptyList(), facts.exportWriteAnchors)
    }

    @Test
    fun derives_module_name_candidates_from_virtual_path_and_module_call() {
        val facts = collectFacts(
            path = "pkg/runtime/init.lua",
            source = "module(\"pkg.runtime.override\")"
        )

        assertEquals(
            listOf(
                "pkg.runtime" to DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH,
                "pkg.runtime.override" to DocumentFacts.ModuleNameCandidateSource.LEGACY_MODULE_CALL
            ),
            facts.moduleNameCandidates.map { it.moduleName to it.source }
        )
    }

    @Test
    fun deduplicates_repeated_module_name_candidates_by_module_name_and_source() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                module("same.name")
                module("same.name")
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "pkg.runtime" to DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH,
                "same.name" to DocumentFacts.ModuleNameCandidateSource.LEGACY_MODULE_CALL
            ),
            facts.moduleNameCandidates.map { it.moduleName to it.source }
        )
    }

    @Test
    fun starts_environment_mode_switch_after_triggering_module_call() {
        val facts = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                module("pkg.runtime")
                local value = 1
            """.trimIndent()
        )

        val trigger = facts.legacyModuleCalls.single().range
        val initialSegment = facts.environmentSegments[0]
        val switchedSegment = facts.environmentSegments[1]

        assertEquals(ModuleEnvironmentMode.CHUNK, initialSegment.mode)
        assertEquals(trigger.end, initialSegment.end)
        assertEquals(ModuleEnvironmentMode.LEGACY_MODULE, switchedSegment.mode)
        assertEquals(trigger.end, switchedSegment.start)
        assertEquals(trigger, switchedSegment.triggerRange)
    }

    @Test
    fun fingerprint_changes_when_fact_payload_changes() {
        val baseline = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                require "alpha"
                return { value = 1 }
            """.trimIndent()
        )
        val whitespaceVariant = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                -- spacing only
                require("alpha")

                return { value = 1 }
            """.trimIndent()
        )
        val payloadVariant = collectFacts(
            path = "pkg/runtime.lua",
            source = """
                require "beta"
                return { value = 1 }
            """.trimIndent()
        )

        assertEquals(baseline.fingerprint, whitespaceVariant.fingerprint)
        assertNotEquals(baseline.fingerprint, payloadVariant.fingerprint)
    }

    private fun collectFacts(path: String, source: String): DocumentFacts {
        val chunk = LuaParser().parse(source)
        return DocumentFactsCollector.collect(VirtualPath.of(path), chunk)
    }
}
