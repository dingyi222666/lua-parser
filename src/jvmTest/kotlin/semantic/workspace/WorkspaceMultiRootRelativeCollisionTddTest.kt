package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-412 corpus: multi-root **folder-relative** path last-wins collision.
 *
 * Product model documented here (no production edits in this task):
 *
 * - [VirtualPath] is **workspace-relative**, not folder-qualified. Two distinct
 *   host roots that both contain `lib/shared.lua` normalize to the same virtual
 *   key `lib/shared.lua`. There is no built-in `rootA/...` vs `rootB/...` prefix
 *   when paths are computed as folder-relative (see
 *   `LuaLanguageService.virtualPathForWorkspaceFile` / multi-folder index).
 * - Workspace engines and LSP indexes store sources in maps keyed by
 *   [VirtualPath] (`Map<VirtualPath, String>`, `linkedMapOf` last put wins).
 *   When multi-root indexing walks folders in order and writes the same relative
 *   key twice, **the last write wins** for source text, symbols, and URI bookkeeping.
 * - Non-colliding relative paths under either root remain independent and visible.
 * - Single-root / unique relative paths are unaffected (no false collision).
 * - Ideal future multi-root product may disambiguate via folder-qualified virtual
 *   paths or URI-keyed indexes; until then this corpus locks the collision so
 *   clients do not assume both roots' colliding files remain co-resident.
 *
 * Complements (not replaces) `lsp.LspDidChangeWorkspaceFoldersTddTest` multi-root
 * documentation: this suite focuses on the **workspace VirtualPath / engine map**
 * layer rather than the didChangeWorkspaceFolders capability gap.
 *
 * Test-only. Verification is review-owned / TASK-043 serialized
 * (`jvmTest --tests semantic.workspace.WorkspaceMultiRootRelativeCollisionTddTest`).
 * Workers must not run Gradle.
 */
class WorkspaceMultiRootRelativeCollisionTddTest {

    // -------------------------------------------------------------------------
    // VirtualPath identity under multi-root relative keys
    // -------------------------------------------------------------------------

    @Test
    fun same_folder_relative_path_collapses_to_one_virtual_path_across_roots() {
        // Host roots differ; folder-relative segments do not.
        val fromRootA = VirtualPath.of("lib/shared.lua")
        val fromRootB = VirtualPath.of("lib/shared.lua")
        val slashVariant = VirtualPath.of("lib\\shared.lua")

        assertEquals(fromRootA, fromRootB)
        assertEquals(fromRootA, slashVariant)
        assertEquals("lib/shared.lua", fromRootA.value)
        assertEquals(fromRootA.hashCode(), fromRootB.hashCode())
    }

    @Test
    fun nested_and_top_level_relative_collisions_share_keys() {
        assertEquals(VirtualPath.of("shared.lua"), VirtualPath.of("shared.lua"))
        assertEquals(
            VirtualPath.of("feature/mod/init.lua"),
            VirtualPath.of("feature\\mod\\init.lua")
        )
        // Leading slash still collapses to the same workspace-relative key
        // (VirtualPath drops empty segments from leading `/`).
        assertEquals(
            VirtualPath.of("lib/shared.lua"),
            VirtualPath.of("/lib/shared.lua")
        )
    }

    @Test
    fun distinct_relative_paths_do_not_collide() {
        assertNotEquals(VirtualPath.of("a/shared.lua"), VirtualPath.of("b/shared.lua"))
        assertNotEquals(VirtualPath.of("shared.lua"), VirtualPath.of("other.lua"))
        assertNotEquals(VirtualPath.of("lib/a.lua"), VirtualPath.of("lib/b.lua"))
    }

    // -------------------------------------------------------------------------
    // Map / index last-wins semantics (multi-root merge model)
    // -------------------------------------------------------------------------

    @Test
    fun linked_map_last_write_wins_for_colliding_relative_keys() {
        // Mirrors LuaLanguageService.indexedWorkspaceFiles: folder walk order puts
        // later roots over earlier ones for the same VirtualPath key.
        val files = linkedMapOf<VirtualPath, String>()
        val key = VirtualPath.of("shared.lua")
        files[key] = "return { from = \"rootA\" }"
        files[key] = "return { from = \"rootB\" }"

        assertEquals(1, files.size)
        assertEquals("return { from = \"rootB\" }", files.getValue(key))
    }

    @Test
    fun last_write_wins_is_order_dependent_across_roots() {
        val key = VirtualPath.of("lib/shared.lua")

        val aThenB = mergeFolderRelativeSources(
            listOf(
                mapOf(key to sourceFrom("A")),
                mapOf(key to sourceFrom("B"))
            )
        )
        assertEquals(sourceFrom("B"), aThenB.getValue(key))

        val bThenA = mergeFolderRelativeSources(
            listOf(
                mapOf(key to sourceFrom("B")),
                mapOf(key to sourceFrom("A"))
            )
        )
        assertEquals(sourceFrom("A"), bThenA.getValue(key))
    }

    @Test
    fun multi_root_merge_keeps_non_colliding_siblings_from_both_roots() {
        val shared = VirtualPath.of("shared.lua")
        val onlyA = VirtualPath.of("a_only.lua")
        val onlyB = VirtualPath.of("b_only.lua")

        val merged = mergeFolderRelativeSources(
            listOf(
                mapOf(
                    shared to sourceFrom("A"),
                    onlyA to "local aOnlyMarker = true\nreturn aOnlyMarker"
                ),
                mapOf(
                    shared to sourceFrom("B"),
                    onlyB to "local bOnlyMarker = true\nreturn bOnlyMarker"
                )
            )
        )

        assertEquals(3, merged.size)
        assertEquals(sourceFrom("B"), merged.getValue(shared))
        assertEquals("local aOnlyMarker = true\nreturn aOnlyMarker", merged.getValue(onlyA))
        assertEquals("local bOnlyMarker = true\nreturn bOnlyMarker", merged.getValue(onlyB))
    }

    @Test
    fun uri_bookkeeping_last_write_wins_alongside_source() {
        // indexedWorkspaceUris uses the same VirtualPath key; last root's real URI wins.
        val key = VirtualPath.of("shared.lua")
        val uris = linkedMapOf<VirtualPath, String>()
        uris[key] = "file:///tmp/rootA/shared.lua"
        uris[key] = "file:///tmp/rootB/shared.lua"

        assertEquals(1, uris.size)
        assertEquals("file:///tmp/rootB/shared.lua", uris.getValue(key))
        assertFalse(uris.values.contains("file:///tmp/rootA/shared.lua"))
    }

    // -------------------------------------------------------------------------
    // Engine / harness: colliding VirtualPath keys in a single workspace map
    // -------------------------------------------------------------------------

    @Test
    fun engine_build_sees_only_last_source_for_colliding_virtual_path() {
        // Callers that already flattened multi-root indexes into one Map feed the
        // engine a single key — last-wins is decided before build().
        val path = VirtualPath.of("shared.lua")
        val files = linkedMapOf<VirtualPath, String>()
        files[path] = """
            local M = { tag = "rootA" }
            return M
        """.trimIndent()
        // Explicit overwrite documents multi-root second root winning.
        files[path] = """
            local M = { tag = "rootB" }
            return M
        """.trimIndent()

        val result = LuaWorkspaceEngine().build(LuaWorkspaceInput(files = files))
        val snapshotSource = result.snapshot.files.getValue(path).semanticFile?.source
        assertNotNull(snapshotSource, "engine must materialize semanticFile for shared.lua")
        assertTrue(
            snapshotSource.contains("rootB"),
            "engine must observe last-wins source for colliding virtual path; got: $snapshotSource"
        )
        assertFalse(
            snapshotSource.contains("rootA"),
            "overwritten rootA source must not remain in the snapshot for the shared key"
        )
        assertEquals(setOf(path), result.snapshot.files.keys)
    }

    @Test
    fun harness_build_with_single_relative_key_does_not_duplicate_entries() {
        // WorkspaceSemanticHarness associates path strings → VirtualPath; a multi-root
        // product that incorrectly double-inserted the same relative path would still
        // collapse to one map entry. Lock single-key cardinality.
        val harness = WorkspaceSemanticHarness.build(
            "shared.lua" to """
                local M = { tag = "only" }
                return M
            """.trimIndent(),
            "main.lua" to """
                local s = require("shared")
                return s.tag
            """.trimIndent()
        )

        assertEquals(1, harness.snapshot.files.keys.count { it.value == "shared.lua" })
        assertNotNull(harness.snapshot.files[VirtualPath.of("shared.lua")])
        assertEquals(1, harness.files.keys.count { it.value == "shared.lua" })
    }

    @Test
    fun require_resolution_binds_to_unique_provider_path_for_shared_module() {
        // After a multi-root collision is flattened, require("shared") can only see
        // one VirtualPath provider — never two co-resident roots under the same key.
        val harness = WorkspaceSemanticHarness.build(
            "shared.lua" to """
                local M = { from = "winner" }
                return M
            """.trimIndent(),
            "main.lua" to """
                local s = require("shared")
                return s.from
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val resolved = harness.queries.resolveRequire(main, "shared")
        assertEquals(harness.path("shared.lua"), resolved.provider?.path)
        assertEquals(1, harness.snapshot.files.keys.count { it.value.endsWith("shared.lua") })
        assertEquals(
            1,
            harness.snapshot.graph.activeProviders.values.count { it.path.value == "shared.lua" }
        )
    }

    @Test
    fun engine_input_map_cardinality_matches_unique_virtual_paths_only() {
        // Document that Kotlin map construction with duplicate VirtualPath keys
        // cannot represent both multi-root files — the second source is the only
        // one that reaches LuaWorkspaceInput.files.
        val key = VirtualPath.of("pkg/mod.lua")
        val flattened = mergeFolderRelativeSources(
            listOf(
                mapOf(key to sourceFrom("root-one")),
                mapOf(key to sourceFrom("root-two"))
            )
        )
        val result = LuaWorkspaceEngine().build(LuaWorkspaceInput(files = flattened))
        assertEquals(1, result.snapshot.files.size)
        assertEquals(key, result.snapshot.files.keys.single())
        val source = result.snapshot.files.getValue(key).semanticFile?.source
        assertNotNull(source)
        assertTrue(source.contains("root-two"))
        assertFalse(source.contains("root-one"))
    }

    // -------------------------------------------------------------------------
    // Nested relative collision matrix (documentation goldens)
    // -------------------------------------------------------------------------

    @Test
    fun collision_matrix_documents_last_wins_for_nested_relative_paths() {
        val cases = listOf(
            "mod.lua",
            "pkg/mod.lua",
            "deep/nested/mod.lua",
            "feature/init.lua"
        )

        for (relative in cases) {
            val key = VirtualPath.of(relative)
            val merged = mergeFolderRelativeSources(
                listOf(
                    mapOf(key to sourceFrom("first:$relative")),
                    mapOf(key to sourceFrom("second:$relative"))
                )
            )
            assertEquals(
                sourceFrom("second:$relative"),
                merged.getValue(key),
                "last-wins for relative key $relative"
            )
            assertEquals(1, merged.size, "collision must not retain both roots for $relative")
        }
    }

    @Test
    fun three_root_chain_keeps_only_final_writer() {
        val key = VirtualPath.of("shared.lua")
        val merged = mergeFolderRelativeSources(
            listOf(
                mapOf(key to sourceFrom("R1")),
                mapOf(key to sourceFrom("R2")),
                mapOf(key to sourceFrom("R3"))
            )
        )
        assertEquals(sourceFrom("R3"), merged.getValue(key))
        assertEquals(1, merged.size)
    }

    @Test
    fun non_collision_multi_root_union_preserves_all_unique_relative_paths() {
        val merged = mergeFolderRelativeSources(
            listOf(
                mapOf(
                    VirtualPath.of("a/one.lua") to sourceFrom("A1"),
                    VirtualPath.of("a/two.lua") to sourceFrom("A2")
                ),
                mapOf(
                    VirtualPath.of("b/one.lua") to sourceFrom("B1"),
                    VirtualPath.of("b/two.lua") to sourceFrom("B2")
                )
            )
        )
        assertEquals(4, merged.size)
        assertEquals(sourceFrom("A1"), merged.getValue(VirtualPath.of("a/one.lua")))
        assertEquals(sourceFrom("B2"), merged.getValue(VirtualPath.of("b/two.lua")))
    }

    @Test
    fun open_document_overlay_model_beats_indexed_collision_winner() {
        // currentWorkspaceFiles(): indexed first, then openDocuments putAll — open wins.
        val key = VirtualPath.of("shared.lua")
        val indexed = linkedMapOf(key to sourceFrom("indexed-winner"))
        val open = linkedMapOf(key to sourceFrom("unsaved-overlay"))

        val current = linkedMapOf<VirtualPath, String>()
        current.putAll(indexed)
        current.putAll(open)

        assertEquals(sourceFrom("unsaved-overlay"), current.getValue(key))
    }

    @Test
    fun partial_overlap_multi_root_merges_union_with_last_wins_on_overlap() {
        val shared = VirtualPath.of("common/util.lua")
        val aOnly = VirtualPath.of("a/feature.lua")
        val bOnly = VirtualPath.of("b/feature.lua")

        val merged = mergeFolderRelativeSources(
            listOf(
                mapOf(
                    shared to sourceFrom("A-common"),
                    aOnly to sourceFrom("A-only")
                ),
                mapOf(
                    shared to sourceFrom("B-common"),
                    bOnly to sourceFrom("B-only")
                )
            )
        )

        assertEquals(3, merged.size)
        assertEquals(sourceFrom("B-common"), merged.getValue(shared))
        assertEquals(sourceFrom("A-only"), merged.getValue(aOnly))
        assertEquals(sourceFrom("B-only"), merged.getValue(bOnly))
        // Distinct nested relatives under different prefixes never collide.
        assertNotEquals(aOnly, bOnly)
        assertNotEquals(aOnly, shared)
    }

    // -------------------------------------------------------------------------
    // Helpers — pure multi-root folder-relative merge model
    // -------------------------------------------------------------------------

    /**
     * Simulates multi-root indexing: each folder contributes a map of
     * **folder-relative** [VirtualPath] → source; later folders overwrite earlier
     * ones for the same key (last-wins).
     */
    private fun mergeFolderRelativeSources(
        folderContributionsInOrder: List<Map<VirtualPath, String>>
    ): Map<VirtualPath, String> {
        val merged = linkedMapOf<VirtualPath, String>()
        for (contribution in folderContributionsInOrder) {
            merged.putAll(contribution)
        }
        return merged
    }

    private fun sourceFrom(tag: String): String =
        "local M = { from = \"$tag\" }\nreturn M"
}
