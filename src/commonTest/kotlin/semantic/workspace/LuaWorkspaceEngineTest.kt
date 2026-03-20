package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.AnalysisProgress
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.ProgressReporter
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceUpdateResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LuaWorkspaceEngineTest {
    private val engine = LuaWorkspaceEngine()
    private val metadataAwareEngine = MetadataAwareWorkspaceEngine()

    @Test
    fun build_returns_file_snapshots_and_graph_payload() {
        val result = build(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to "local dep = require(\"dep\")\nreturn { value = dep.value }"
        )

        assertEquals(setOf(VirtualPath.of("dep.lua"), VirtualPath.of("main.lua")), result.snapshot.files.keys)
        assertTrue(result.snapshot.graph.activeProviders.containsKey("dep"))
        assertEquals(VirtualPath.of("dep.lua"), result.snapshot.graph.activeProviders.getValue("dep").path)
    }

    @Test
    fun build_mounts_selected_standard_library_overlay_into_graph() {
        val result = build(
            *(arrayOf(
                "main.lua" to "local math = require(\"math\")\nreturn math"
            )),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_4
        )

        assertEquals(VirtualPath.of("__lua_std__/5.4/math.lua"), result.snapshot.graph.activeProviders.getValue("math").path)
        assertTrue(result.snapshot.builtinOverlay.providerModules.keys.any { it == VirtualPath.of("__lua_std__/5.4/math.lua") })
        assertFalse(result.snapshot.files.containsKey(VirtualPath.of("__lua_std__/5.4/math.lua")))
    }

    @Test
    fun private_only_implementation_change_dirties_only_the_edited_file() {
        val initial = build(
            "dep.lua" to "local private = 1\nlocal M = { value = 1 }\nreturn M",
            "main.lua" to "local dep = require(\"dep\")\nreturn { value = dep.value }"
        )

        val result = update(
            initial,
            upserts = mapOf(
                "dep.lua" to "local private = 2\nlocal M = { value = 1 }\nreturn M"
            )
        )

        assertEquals(setOf(VirtualPath.of("dep.lua")), result.affectedDocuments)
        assertFalse(VirtualPath.of("main.lua") in result.affectedDocuments)
    }

    @Test
    fun public_export_change_dirties_reverse_dependency_closure() {
        val initial = build(
            "dep.lua" to "local M = { value = 1 }\nreturn M",
            "mid.lua" to "local dep = require(\"dep\")\nreturn { value = dep.value }",
            "top.lua" to "local mid = require(\"mid\")\nreturn { value = mid.value }"
        )

        val result = update(
            initial,
            upserts = mapOf(
                "dep.lua" to "local M = { value = 1, extra = 2 }\nreturn M"
            )
        )

        assertEquals(
            setOf(VirtualPath.of("dep.lua"), VirtualPath.of("mid.lua"), VirtualPath.of("top.lua")),
            result.affectedDocuments
        )
    }

    @Test
    fun adding_provider_resolves_previous_unresolved_require_and_dirties_dependent() {
        val initial = build(
            "main.lua" to "local dep = require(\"dep\")\nreturn dep"
        )

        val result = update(
            initial,
            upserts = mapOf(
                "dep.lua" to "return { value = 1 }"
            )
        )

        assertTrue(VirtualPath.of("main.lua") in result.filesWithRequireResolutionChanged)
        assertEquals(setOf(VirtualPath.of("dep.lua"), VirtualPath.of("main.lua")), result.affectedDocuments)
    }

    @Test
    fun removing_provider_invalidates_dependents_and_updates_affected_module_names() {
        val initial = build(
            "dep.lua" to "return { value = 1 }",
            "main.lua" to "local dep = require(\"dep\")\nreturn dep"
        )

        val result = update(
            initial,
            removals = setOf("dep.lua")
        )

        assertTrue(VirtualPath.of("main.lua") in result.filesWithRequireResolutionChanged)
        assertEquals(setOf(VirtualPath.of("main.lua")), result.affectedDocuments)
        assertTrue("dep" in result.affectedModuleNames)
        assertFalse(result.snapshot.graph.activeProviders.containsKey("dep"))
    }

    @Test
    fun provider_winner_change_from_conflict_dirties_dependents_even_if_old_provider_still_exists() {
        val initial = build(
            "shared.lua" to "return { value = 1 }",
            "main.lua" to "local shared = require(\"shared\")\nreturn shared"
        )

        val result = update(
            initial,
            upserts = mapOf(
                "alt.lua" to "module(\"shared\")\nvalue = 2"
            )
        )

        assertEquals(VirtualPath.of("alt.lua"), result.snapshot.graph.activeProviders.getValue("shared").path)
        assertEquals(setOf(VirtualPath.of("alt.lua"), VirtualPath.of("main.lua")), result.affectedDocuments)
    }

    @Test
    fun cyclic_public_change_dirties_the_whole_strongly_connected_component() {
        val initial = build(
            "a.lua" to "local b = require(\"b\")\nlocal M = { value = b.value }\nreturn M",
            "b.lua" to "local a = require(\"a\")\nlocal M = { value = a.value }\nreturn M"
        )

        val result = update(
            initial,
            upserts = mapOf(
                "a.lua" to "local b = require(\"b\")\nlocal M = { value = b.value, extra = 1 }\nreturn M"
            )
        )

        assertEquals(setOf(VirtualPath.of("a.lua"), VirtualPath.of("b.lua")), result.affectedDocuments)
    }

    @Test
    fun switching_from_lua53_to_lua54_removes_bit32_from_builtin_globals_without_provider_rewiring() {
        val initial = build(
            *(arrayOf(
                "main.lua" to "local current = bit32\nreturn current"
            )),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )

        val result = update(
            previous = initial,
            standardLibraryOverlayVersion = LuaVersion.LUA_5_4
        )

        assertFalse("bit32" in result.activeProviderChangedModuleNames)
        assertFalse(VirtualPath.of("main.lua") in result.filesWithRequireResolutionChanged)
        assertEquals(emptySet(), result.affectedDocuments)
        assertFalse("bit32" in result.snapshot.builtinOverlay.globals.globalNames)
    }

    @Test
    fun seeall_documents_become_affected_when_builtin_globals_overlay_changes() {
        val initial = build(
            *(arrayOf(
                "legacy.lua" to "module(\"legacy\", package.seeall)\nreturn legacy"
            )),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )

        val result = update(
            previous = initial,
            standardLibraryOverlayVersion = LuaVersion.LUA_5_4
        )

        assertEquals(setOf(VirtualPath.of("legacy.lua")), result.affectedDocuments)
        assertTrue(
            result.snapshot.files.getValue(VirtualPath.of("legacy.lua"))
                .legacyModuleEnvironment
                ?.segments
                .orEmpty()
                .any { it.hasSeeAllFallback }
        )
    }

    @Test
    fun update_recomputes_metadata_driven_extra_providers() {
        val initial = buildWithMetadataAwareEngine(
            metadata = mapOf("mounted.module" to "Mounted"),
            "main.lua" to "local mounted = require(\"Mounted\")\nreturn mounted.value"
        )

        val result = updateWithMetadataAwareEngine(
            previous = initial,
            metadata = mapOf("mounted.module" to "Renamed"),
            upserts = mapOf(
                "main.lua" to "local renamed = require(\"Renamed\")\nreturn renamed.value"
            )
        )

        assertFalse(result.snapshot.graph.activeProviders.containsKey("Mounted"))
        assertEquals(VirtualPath.of("__meta__/Renamed.lua"), result.snapshot.graph.activeProviders.getValue("Renamed").path)
        assertEquals(setOf("Mounted", "Renamed"), result.activeProviderChangedModuleNames)
        assertEquals(setOf(VirtualPath.of("main.lua")), result.affectedDocuments)
        assertEquals("Renamed", result.snapshot.metadata.getValue("mounted.module"))
    }

    @Test
    fun extra_provider_public_surface_change_dirties_current_consumers() {
        val initial = buildWithMetadataAwareEngine(
            metadata = mapOf("mounted.module" to "Mounted"),
            "main.lua" to "local mounted = require(\"Mounted\")\nreturn mounted.value"
        )

        val result = updateWithMetadataAwareEngine(
            previous = initial,
            metadata = mapOf("mounted.module" to "MountedV2"),
            upserts = mapOf(
                "main.lua" to "local mounted = require(\"Mounted\")\nreturn mounted.value"
            )
        )

        assertTrue(VirtualPath.of("main.lua") in result.affectedDocuments)
        assertTrue("Mounted" in result.affectedModuleNames)
        assertEquals(VirtualPath.of("__meta__/Mounted.lua"), result.snapshot.graph.activeProviders.getValue("Mounted").path)
    }

    @Test
    fun progress_reports_are_monotonic_and_end_with_complete() {
        val progress = mutableListOf<AnalysisProgress>()

        engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    VirtualPath.of("dep.lua") to "return { value = 1 }",
                    VirtualPath.of("main.lua") to "local dep = require(\"dep\")\nreturn dep"
                )
            ),
            reporter = ProgressReporter { progress += it }
        )

        assertTrue(progress.isNotEmpty())
        assertEquals(AnalysisProgress.Phase.COMPLETE, progress.last().phase)
        assertTrue(progress.zipWithNext().all { (left, right) -> left.completedFiles <= right.completedFiles })
    }

    private fun build(
        vararg files: Pair<String, String>,
        standardLibraryOverlayVersion: LuaVersion = LuaVersion.LUA_5_3
    ): WorkspaceUpdateResult {
        return engine.build(
            LuaWorkspaceInput(
                files = files.associate { (path, source) -> VirtualPath.of(path) to source },
                standardLibraryOverlayVersion = standardLibraryOverlayVersion
            )
        )
    }

    private fun update(
        previous: WorkspaceUpdateResult,
        upserts: Map<String, String> = emptyMap(),
        removals: Set<String> = emptySet(),
        standardLibraryOverlayVersion: LuaVersion = previous.snapshot.builtinOverlay.version
    ): WorkspaceUpdateResult {
        return engine.update(
            previous = previous.snapshot,
            delta = WorkspaceDelta(
                upserts = upserts.mapKeys { (path, _) -> VirtualPath.of(path) },
                removals = removals.mapTo(linkedSetOf()) { VirtualPath.of(it) }
            ),
            standardLibraryOverlayVersion = standardLibraryOverlayVersion
        )
    }

    private fun buildWithMetadataAwareEngine(
        metadata: Map<String, String>,
        vararg files: Pair<String, String>,
        standardLibraryOverlayVersion: LuaVersion = LuaVersion.LUA_5_3
    ): WorkspaceUpdateResult {
        return metadataAwareEngine.build(
            LuaWorkspaceInput(
                files = files.associate { (path, source) -> VirtualPath.of(path) to source },
                metadata = metadata,
                standardLibraryOverlayVersion = standardLibraryOverlayVersion
            )
        )
    }

    private fun updateWithMetadataAwareEngine(
        previous: WorkspaceUpdateResult,
        metadata: Map<String, String>,
        upserts: Map<String, String> = emptyMap(),
        removals: Set<String> = emptySet(),
        standardLibraryOverlayVersion: LuaVersion = previous.snapshot.builtinOverlay.version
    ): WorkspaceUpdateResult {
        return metadataAwareEngine.update(
            previous = previous.snapshot.copy(metadata = metadata),
            delta = WorkspaceDelta(
                upserts = upserts.mapKeys { (path, _) -> VirtualPath.of(path) },
                removals = removals.mapTo(linkedSetOf()) { VirtualPath.of(it) }
            ),
            standardLibraryOverlayVersion = standardLibraryOverlayVersion
        )
    }

    private class MetadataAwareWorkspaceEngine : LuaWorkspaceEngine() {
        override fun extraProviders(input: LuaWorkspaceInput): Map<VirtualPath, WorkspaceSnapshot.FileSnapshot> {
            val moduleName = input.metadata["mounted.module"] ?: return emptyMap()
            val version = if (moduleName.endsWith("V2")) 2 else 1
            val stableName = moduleName.removeSuffix("V2")
            val path = VirtualPath.of("__meta__/$stableName.lua")
            return mapOf(
                path to WorkspaceSnapshot.FileSnapshot(
                    cacheKey = "extra-$moduleName-$version",
                    moduleExportSurface = io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface(
                        moduleType = io.github.dingyi222666.luaparser.semantic.types.model.ModuleType(
                            moduleName = stableName,
                            fields = mapOf("value" to io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType.NUMBER),
                            methods = if (version == 1) {
                                emptyMap()
                            } else {
                                mapOf(
                                    "refresh" to io.github.dingyi222666.luaparser.semantic.types.model.FunctionType(
                                        returnType = io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType.BOOLEAN
                                    )
                                )
                            }
                        ),
                        sourceForm = io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL,
                        members = buildList {
                            add(
                                io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface.MemberExport(
                                    name = "value",
                                    exportPath = listOf("value"),
                                    kind = io.github.dingyi222666.luaparser.semantic.api.SymbolKind.FIELD,
                                    type = io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType.NUMBER,
                                    range = null
                                )
                            )
                            if (version == 2) {
                                add(
                                    io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface.MemberExport(
                                        name = "refresh",
                                        exportPath = listOf("refresh"),
                                        kind = io.github.dingyi222666.luaparser.semantic.api.SymbolKind.METHOD,
                                        type = io.github.dingyi222666.luaparser.semantic.types.model.FunctionType(
                                            returnType = io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType.BOOLEAN
                                        ),
                                        range = null
                                    )
                                )
                            }
                        }
                    ),
                    publicFingerprint = io.github.dingyi222666.luaparser.semantic.workspace.WorkspacePublicFingerprint(
                        providedModuleNames = setOf(stableName),
                        value = "fingerprint-$moduleName-$version"
                    )
                )
            )
        }
    }
}
