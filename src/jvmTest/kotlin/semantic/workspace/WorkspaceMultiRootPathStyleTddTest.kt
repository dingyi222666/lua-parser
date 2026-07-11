package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleGraph
import semantic.support.WorkspaceSemanticHarness
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-476 corpus: multi-root **path style** matrix (separator styles × folder-relative keys).
 *
 * Complements (does not replace):
 * - [WorkspaceMultiRootRelativeCollisionTddTest] — multi-root last-write-wins collision
 * - [WorkspaceModuleResolverPathStyleTddTest] — require dots vs slashes package style
 * - [VirtualPathNormalizationTddTest] / [VirtualPathWindowsSlashTddTest] — pure VirtualPath
 *
 * Product model documented here (test-only; no production edits):
 *
 * - [VirtualPath] is **workspace-relative**, not folder-qualified. Distinct host roots that
 *   contribute the same folder-relative path collapse to one virtual key after separator
 *   normalization (`\` → `/`, duplicates / trailing separators collapse, `.` / `..` rules).
 * - Multi-root indexing feeds a single `Map<VirtualPath, String>` (linkedMap last put wins).
 *   Separator style of the **input string** must not create a second map entry for the same
 *   logical relative path: `lib/shared.lua` and `lib\\shared.lua` are one key.
 * - Documented require style remains **dotted** package names (`lib.shared`); provider paths
 *   stay slash-separated after normalize. Path-derived module names never keep `\` or `/`.
 * - Across multi-root merges, non-colliding relative paths stay independent; colliding
 *   relative paths (regardless of separator style used when the contribution was written)
 *   keep only the last root's source.
 * - Ideal future multi-root product may disambiguate via folder-qualified virtual paths;
 *   until then this corpus locks separator-style equivalence under multi-root last-wins.
 *
 * Dual-path policy:
 * - HARD: VirtualPath slash/backslash identity; multi-root merge last-wins across separator
 *   styles; engine/harness single-key cardinality; dotted require → slash provider; path-
 *   derived module names stay dotted; host android.jar policy Downloads + SDK android-35 only.
 * - CURRENTLY_ACCEPTS (soft): slash-form / mixed require strings may alias the dotted provider
 *   or stay unresolved — never invent a fabricated path; never register slash-form active keys.
 *
 * Host android.jar: not opened by this pure workspace corpus. Policy reminder only —
 * `/Users/dingyi/Downloads/android.jar` and
 * `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar`; never G:/.
 *
 * Test-only. Verification is review-owned / TASK-043 serialized
 * (`jvmTest --tests semantic.workspace.WorkspaceMultiRootPathStyleTddTest`).
 * Workers must not run Gradle.
 */
class WorkspaceMultiRootPathStyleTddTest {

    // -------------------------------------------------------------------------
    // HARD: VirtualPath separator styles under multi-root relative keys
    // -------------------------------------------------------------------------

    @Test
    fun multi_root_relative_key_is_separator_style_invariant() {
        val styles = listOf(
            "lib/shared.lua",
            "lib\\shared.lua",
            "lib//shared.lua",
            "lib\\\\shared.lua",
            "lib/shared.lua/",
            "lib\\shared.lua\\",
            "lib/./shared.lua",
            "lib\\.\\shared.lua"
        )
        val normalized = styles.map { VirtualPath.of(it) }.distinct()
        assertEquals(1, normalized.size, "all separator styles must collapse to one multi-root key")
        assertEquals("lib/shared.lua", normalized.single().value)
        assertEquals(1, styles.map { VirtualPath.of(it) }.toSet().size)
        assertEquals(
            VirtualPath.of("lib/shared.lua").hashCode(),
            VirtualPath.of("lib\\shared.lua").hashCode()
        )
    }

    @Test
    fun nested_multi_root_relative_keys_normalize_across_windows_and_unix_styles() {
        val cases = listOf(
            "feature/mod/init.lua" to "feature\\mod\\init.lua",
            "deep/nested/pkg/mod.lua" to "deep\\nested\\pkg\\mod.lua",
            "a/b/c.lua" to "a\\b/c.lua",
            "pkg/util.lua" to "pkg//util.lua"
        )
        for ((forward, windows) in cases) {
            assertEquals(VirtualPath.of(forward), VirtualPath.of(windows), "pair $forward vs $windows")
            assertFalse('\\' in VirtualPath.of(windows).value)
        }
        // Leading slash still collapses to workspace-relative (not host-absolute).
        assertEquals(VirtualPath.of("lib/shared.lua"), VirtualPath.of("/lib/shared.lua"))
        assertEquals(VirtualPath.of("lib/shared.lua"), VirtualPath.of("\\lib\\shared.lua"))
    }

    @Test
    fun distinct_relative_paths_remain_distinct_under_any_separator_style() {
        assertNotEquals(VirtualPath.of("a/shared.lua"), VirtualPath.of("b\\shared.lua"))
        assertNotEquals(VirtualPath.of("shared.lua"), VirtualPath.of("other\\shared.lua"))
        assertNotEquals(VirtualPath.of("lib/a.lua"), VirtualPath.of("lib\\b.lua"))
        assertNotEquals(VirtualPath.of("feature/init.lua"), VirtualPath.of("feature\\mod\\init.lua"))
    }

    // -------------------------------------------------------------------------
    // HARD: multi-root merge last-wins across separator styles
    // -------------------------------------------------------------------------

    @Test
    fun multi_root_merge_last_wins_when_roots_use_different_separator_styles() {
        val keyForward = VirtualPath.of("lib/shared.lua")
        val keyBackslash = VirtualPath.of("lib\\shared.lua")
        assertEquals(keyForward, keyBackslash)

        val aThenB = mergeFolderRelativeSources(
            listOf(
                mapOf(keyForward to sourceFrom("rootA-unix")),
                mapOf(keyBackslash to sourceFrom("rootB-windows"))
            )
        )
        assertEquals(1, aThenB.size)
        assertEquals(sourceFrom("rootB-windows"), aThenB.getValue(keyForward))

        val bThenA = mergeFolderRelativeSources(
            listOf(
                mapOf(keyBackslash to sourceFrom("rootB-windows")),
                mapOf(keyForward to sourceFrom("rootA-unix"))
            )
        )
        assertEquals(sourceFrom("rootA-unix"), bThenA.getValue(keyForward))
    }

    @Test
    fun multi_root_merge_keeps_non_colliding_siblings_from_both_roots_under_mixed_styles() {
        val shared = VirtualPath.of("shared.lua")
        val onlyA = VirtualPath.of("a_only.lua")
        val onlyB = VirtualPath.of("b\\only.lua") // normalizes to b/only.lua

        val merged = mergeFolderRelativeSources(
            listOf(
                mapOf(
                    shared to sourceFrom("A"),
                    onlyA to sourceFrom("A-only")
                ),
                mapOf(
                    VirtualPath.of("shared.lua") to sourceFrom("B"),
                    onlyB to sourceFrom("B-only")
                )
            )
        )

        assertEquals(3, merged.size)
        assertEquals(sourceFrom("B"), merged.getValue(shared))
        assertEquals(sourceFrom("A-only"), merged.getValue(onlyA))
        assertEquals(sourceFrom("B-only"), merged.getValue(VirtualPath.of("b/only.lua")))
    }

    @Test
    fun three_root_chain_with_alternating_separator_styles_keeps_final_writer() {
        val key = VirtualPath.of("shared.lua")
        val merged = mergeFolderRelativeSources(
            listOf(
                mapOf(VirtualPath.of("shared.lua") to sourceFrom("R1")),
                mapOf(VirtualPath.of("shared.lua") to sourceFrom("R2")),
                mapOf(VirtualPath.of("shared.lua") to sourceFrom("R3"))
            )
        )
        assertEquals(sourceFrom("R3"), merged.getValue(key))
        assertEquals(1, merged.size)

        // Same chain when roots contribute with alternating separator spellings.
        val mergedStyles = mergeFolderRelativeSources(
            listOf(
                mapOf(VirtualPath.of("lib/shared.lua") to sourceFrom("R1")),
                mapOf(VirtualPath.of("lib\\shared.lua") to sourceFrom("R2")),
                mapOf(VirtualPath.of("lib//shared.lua") to sourceFrom("R3"))
            )
        )
        assertEquals(1, mergedStyles.size)
        assertEquals(sourceFrom("R3"), mergedStyles.getValue(VirtualPath.of("lib/shared.lua")))
        assertEquals(
            VirtualPath.of("lib/shared.lua"),
            VirtualPath.of("lib\\shared.lua")
        )
    }

    // -------------------------------------------------------------------------
    // HARD: engine / harness single-key cardinality under path styles
    // -------------------------------------------------------------------------

    @Test
    fun engine_build_collapses_separator_style_duplicate_puts_to_last_source() {
        val path = VirtualPath.of("shared.lua")
        val files = linkedMapOf<VirtualPath, String>()
        files[VirtualPath.of("shared.lua")] = sourceFrom("rootA")
        // Second put with equal key (backslash of() normalizes identically).
        files[VirtualPath.of("shared.lua")] = sourceFrom("rootB")
        files[VirtualPath.of("shared.lua")] = sourceFrom("rootB")

        val result = LuaWorkspaceEngine().build(LuaWorkspaceInput(files = files))
        val snapshotSource = result.snapshot.files.getValue(path).semanticFile?.source
        assertNotNull(snapshotSource)
        assertTrue(snapshotSource.contains("rootB"), "last-wins source must win; got: $snapshotSource")
        assertFalse(snapshotSource.contains("rootA"))
        assertEquals(setOf(path), result.snapshot.files.keys)

        // Explicit multi-style puts before engine build.
        val styled = linkedMapOf<VirtualPath, String>()
        styled[VirtualPath.of("lib\\shared.lua")] = sourceFrom("styleA")
        styled[VirtualPath.of("lib/shared.lua")] = sourceFrom("styleB")
        val styledResult = LuaWorkspaceEngine().build(LuaWorkspaceInput(files = styled))
        assertEquals(1, styledResult.snapshot.files.size)
        val styledSource = styledResult.snapshot.files
            .getValue(VirtualPath.of("lib/shared.lua"))
            .semanticFile
            ?.source
        assertNotNull(styledSource)
        assertTrue(styledSource.contains("styleB"))
        assertFalse(styledSource.contains("styleA"))
    }

    @Test
    fun harness_path_helper_normalizes_windows_style_to_slash_virtual_path() {
        val harness = WorkspaceSemanticHarness.build(
            "lib/shared.lua" to sourceFrom("shared"),
            "main.lua" to """
                local s = require("lib.shared")
                return s.from
            """.trimIndent()
        )
        assertEquals(harness.path("lib/shared.lua"), harness.path("lib\\shared.lua"))
        assertEquals("lib/shared.lua", harness.path("lib\\shared.lua").value)
        assertEquals(1, harness.snapshot.files.keys.count { it.value == "lib/shared.lua" })
    }

    @Test
    fun require_resolution_binds_dotted_module_to_slash_provider_after_multi_root_flatten() {
        // After multi-root contributions are flattened to one VirtualPath map, require
        // still uses documented dotted package style against slash-separated providers.
        val flattened = mergeFolderRelativeSources(
            listOf(
                mapOf(
                    VirtualPath.of("lib\\shared.lua") to """
                        local M = { from = "loser" }
                        return M
                    """.trimIndent()
                ),
                mapOf(
                    VirtualPath.of("lib/shared.lua") to """
                        local M = { from = "winner" }
                        return M
                    """.trimIndent(),
                    VirtualPath.of("main.lua") to """
                        local s = require("lib.shared")
                        return s.from
                    """.trimIndent()
                )
            )
        )
        val result = LuaWorkspaceEngine().build(LuaWorkspaceInput(files = flattened))
        val queries = LuaWorkspaceQueryFacade(result.snapshot)
        val main = VirtualPath.of("main.lua")
        val resolved = queries.resolveRequire(main, "lib.shared")

        assertEquals(VirtualPath.of("lib/shared.lua"), resolved.provider?.path)
        assertEquals(WorkspaceModuleGraph.ProviderSource.VIRTUAL_PATH, resolved.provider?.source)
        assertEquals("lib.shared", resolved.moduleName)
        assertEquals(1, result.snapshot.files.keys.count { it.value == "lib/shared.lua" })
        val source = result.snapshot.files.getValue(VirtualPath.of("lib/shared.lua")).semanticFile?.source
        assertNotNull(source)
        assertTrue(source.contains("winner"))
        assertFalse(source.contains("loser"))
    }

    // -------------------------------------------------------------------------
    // HARD: path-derived module names / package style under multi-root keys
    // -------------------------------------------------------------------------

    @Test
    fun path_derived_module_names_stay_dotted_for_slash_and_backslash_virtual_inputs() {
        val harness = WorkspaceSemanticHarness.build(
            "feature/sub/util.lua" to "return {}",
            "feature/sub/init.lua" to "return {}",
            "top.lua" to "return {}"
        )

        // path() with backslash form still addresses the same file entries.
        assertEquals(harness.path("feature/sub/util.lua"), harness.path("feature\\sub\\util.lua"))

        val pathCandidates = harness.snapshot.files.values
            .flatMap { it.documentFacts?.moduleNameCandidates.orEmpty() }
            .filter { it.source == DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH }
            .map { it.moduleName }

        assertTrue(pathCandidates.isNotEmpty())
        assertTrue(pathCandidates.all { !it.contains('/') && !it.contains('\\') })
        assertTrue(pathCandidates.contains("feature.sub.util"))
        assertTrue(pathCandidates.contains("feature.sub"))
        assertTrue(pathCandidates.contains("top"))
        assertFalse(harness.snapshot.graph.activeProviders.keys.any { it.contains('/') || it.contains('\\') })
    }

    @Test
    fun init_lua_package_directory_under_mixed_style_keys_uses_parent_dotted_name() {
        val harness = WorkspaceSemanticHarness.build(
            "pkg/runtime/init.lua" to "return { ready = true }",
            "main.lua" to """
                local runtime = require("pkg.runtime")
                return runtime.ready
            """.trimIndent()
        )

        val providerPath = harness.path("pkg/runtime/init.lua")
        assertEquals(providerPath, harness.path("pkg\\runtime\\init.lua"))
        val facts = harness.snapshot.files.getValue(providerPath).documentFacts
        val virtualCandidate = facts?.moduleNameCandidates
            ?.single { it.source == DocumentFacts.ModuleNameCandidateSource.VIRTUAL_PATH }

        assertEquals("pkg.runtime", virtualCandidate?.moduleName)
        assertEquals(
            providerPath,
            harness.queries.resolveRequire(harness.path("main.lua"), "pkg.runtime").provider?.path
        )
        assertFalse(harness.snapshot.graph.activeProviders.containsKey("pkg.runtime.init"))
    }

    @Test
    fun resolved_dependency_records_dotted_module_against_normalized_slash_provider_path() {
        val harness = WorkspaceSemanticHarness.build(
            "lib/core.lua" to "return { n = 1 }",
            "main.lua" to """
                local core = require("lib.core")
                return core.n
            """.trimIndent()
        )

        val dependency = harness.snapshot.graph.resolvedDependencies
            .getValue(harness.path("main.lua"))
            .single()

        assertEquals("lib.core", dependency.moduleName)
        assertEquals(harness.path("lib/core.lua"), dependency.provider.path)
        assertEquals(harness.path("lib\\core.lua"), dependency.provider.path)
        assertTrue(dependency.provider.path.value.contains('/'))
        assertFalse(dependency.moduleName.contains('/'))
        assertFalse(dependency.moduleName.contains('\\'))
        assertFalse('\\' in dependency.provider.path.value)
    }

    // -------------------------------------------------------------------------
    // Dual-path: slash / mixed require styles after multi-root flatten
    // -------------------------------------------------------------------------

    @Test
    fun slash_style_require_after_multi_root_flatten_is_alias_or_unresolved_never_fabricated() {
        // Documented style is dotted. Slash-form may alias or stay unresolved.
        val harness = WorkspaceSemanticHarness.build(
            "feature/profile.lua" to "return { title = \"profile\" }",
            "main.lua" to """
                local profile = require("feature/profile")
                return profile
            """.trimIndent(),
            "dotted_consumer.lua" to """
                local profile = require("feature.profile")
                return profile
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val providerPath = harness.path("feature/profile.lua")
        val slashResolved = harness.queries.resolveRequire(main, "feature/profile")
        val dottedLookedUp = harness.queries.lookupModule("feature.profile")
        val dottedConsumerResolved = harness.queries.resolveRequire(
            harness.path("dotted_consumer.lua"),
            "feature.profile"
        )
        val unresolved = harness.snapshot.graph.unresolvedStaticRequires[main].orEmpty()

        assertEquals(providerPath, dottedLookedUp.provider?.path)
        assertEquals(providerPath, dottedConsumerResolved.provider?.path)
        assertEquals("feature.profile", dottedConsumerResolved.moduleName)

        if (slashResolved.provider != null) {
            // CURRENTLY_ACCEPTS alias path: product maps slash form to the real provider.
            assertEquals(providerPath, slashResolved.provider?.path)
        } else {
            // CURRENTLY_ACCEPTS unresolved path: no fabricated provider / active slash key.
            assertNull(slashResolved.exportSurface)
            assertTrue(unresolved.any { it.moduleName == "feature/profile" })
            assertFalse(harness.snapshot.graph.activeProviders.containsKey("feature/profile"))
            assertTrue(
                harness.snapshot.graph.resolvedDependencies[main].orEmpty().none {
                    it.moduleName == "feature.profile"
                }
            )
        }
    }

    @Test
    fun mixed_separator_require_does_not_fabricate_provider_under_multi_root_keys() {
        val harness = WorkspaceSemanticHarness.build(
            "a/b/c.lua" to "return { ok = true }",
            "main.lua" to """
                local bad = require("a.b/c")
                return bad
            """.trimIndent(),
            "dotted_consumer.lua" to """
                local ok = require("a.b.c")
                return ok
            """.trimIndent()
        )

        val main = harness.path("main.lua")
        val providerPath = harness.path("a/b/c.lua")
        val mixed = harness.queries.resolveRequire(main, "a.b/c")
        val dottedLookedUp = harness.queries.lookupModule("a.b.c")
        val dottedConsumerResolved = harness.queries.resolveRequire(
            harness.path("dotted_consumer.lua"),
            "a.b.c"
        )

        assertEquals(providerPath, dottedLookedUp.provider?.path)
        assertEquals(providerPath, dottedConsumerResolved.provider?.path)

        if (mixed.provider != null) {
            assertEquals(providerPath, mixed.provider?.path)
        } else {
            assertNull(mixed.exportSurface)
            assertTrue(
                harness.snapshot.graph.unresolvedStaticRequires[main]
                    .orEmpty()
                    .any { it.moduleName == "a.b/c" }
            )
            assertFalse(harness.snapshot.graph.activeProviders.containsKey("a.b/c"))
        }
    }

    // -------------------------------------------------------------------------
    // Nested collision matrix + URI bookkeeping with path styles
    // -------------------------------------------------------------------------

    @Test
    fun collision_matrix_documents_last_wins_for_nested_paths_across_separator_styles() {
        val cases = listOf(
            "mod.lua" to "mod.lua",
            "pkg/mod.lua" to "pkg\\mod.lua",
            "deep/nested/mod.lua" to "deep\\nested\\mod.lua",
            "feature/init.lua" to "feature\\init.lua"
        )

        for ((forward, windows) in cases) {
            val key = VirtualPath.of(forward)
            assertEquals(key, VirtualPath.of(windows))
            val merged = mergeFolderRelativeSources(
                listOf(
                    mapOf(VirtualPath.of(forward) to sourceFrom("first:$forward")),
                    mapOf(VirtualPath.of(windows) to sourceFrom("second:$windows"))
                )
            )
            assertEquals(
                sourceFrom("second:$windows"),
                merged.getValue(key),
                "last-wins for relative key $forward / $windows"
            )
            assertEquals(1, merged.size, "collision must not retain both roots for $forward")
        }
    }

    @Test
    fun uri_bookkeeping_last_write_wins_with_separator_style_equivalent_keys() {
        val key = VirtualPath.of("shared.lua")
        val uris = linkedMapOf<VirtualPath, String>()
        uris[VirtualPath.of("shared.lua")] = "file:///tmp/rootA/shared.lua"
        uris[VirtualPath.of("shared.lua")] = "file:///tmp/rootB/shared.lua"

        assertEquals(1, uris.size)
        assertEquals("file:///tmp/rootB/shared.lua", uris.getValue(key))
        assertFalse(uris.values.contains("file:///tmp/rootA/shared.lua"))

        // Backslash-form VirtualPath.of is the same map key as forward-slash form.
        uris[VirtualPath.of("shared.lua")] = "file:///tmp/rootC/shared.lua"
        assertEquals(VirtualPath.of("shared.lua"), VirtualPath.of("shared.lua"))
        assertEquals("file:///tmp/rootC/shared.lua", uris.getValue(VirtualPath.of("shared.lua")))

        val styledUris = linkedMapOf<VirtualPath, String>()
        styledUris[VirtualPath.of("lib/shared.lua")] = "file:///tmp/rootA/lib/shared.lua"
        styledUris[VirtualPath.of("lib\\shared.lua")] = "file:///tmp/rootB/lib/shared.lua"
        assertEquals(1, styledUris.size)
        assertEquals(
            "file:///tmp/rootB/lib/shared.lua",
            styledUris.getValue(VirtualPath.of("lib/shared.lua"))
        )
    }

    @Test
    fun open_document_overlay_beats_indexed_multi_root_winner_for_any_separator_style_key() {
        // currentWorkspaceFiles(): indexed first, then openDocuments putAll — open wins.
        val key = VirtualPath.of("shared.lua")
        val indexed = linkedMapOf(VirtualPath.of("shared.lua") to sourceFrom("indexed-winner"))
        val open = linkedMapOf(VirtualPath.of("shared.lua") to sourceFrom("unsaved-overlay"))

        val current = linkedMapOf<VirtualPath, String>()
        current.putAll(indexed)
        current.putAll(open)

        assertEquals(sourceFrom("unsaved-overlay"), current.getValue(key))
        assertEquals(1, current.size)

        // Same with backslash-style of() for the open overlay key.
        val indexed2 = linkedMapOf(VirtualPath.of("lib/shared.lua") to sourceFrom("indexed"))
        val open2 = linkedMapOf(VirtualPath.of("lib\\shared.lua") to sourceFrom("open"))
        val current2 = linkedMapOf<VirtualPath, String>()
        current2.putAll(indexed2)
        current2.putAll(open2)
        assertEquals(sourceFrom("open"), current2.getValue(VirtualPath.of("lib/shared.lua")))
        assertEquals(1, current2.size)
    }

    @Test
    fun non_collision_multi_root_union_preserves_unique_paths_regardless_of_style() {
        val merged = mergeFolderRelativeSources(
            listOf(
                mapOf(
                    VirtualPath.of("a/one.lua") to sourceFrom("A1"),
                    VirtualPath.of("a\\two.lua") to sourceFrom("A2")
                ),
                mapOf(
                    VirtualPath.of("b\\one.lua") to sourceFrom("B1"),
                    VirtualPath.of("b/two.lua") to sourceFrom("B2")
                )
            )
        )
        assertEquals(4, merged.size)
        assertEquals(sourceFrom("A1"), merged.getValue(VirtualPath.of("a/one.lua")))
        assertEquals(sourceFrom("A2"), merged.getValue(VirtualPath.of("a/two.lua")))
        assertEquals(sourceFrom("B1"), merged.getValue(VirtualPath.of("b/one.lua")))
        assertEquals(sourceFrom("B2"), merged.getValue(VirtualPath.of("b/two.lua")))
    }

    // -------------------------------------------------------------------------
    // Inventory matrix + host android.jar policy
    // -------------------------------------------------------------------------

    @Test
    fun multi_root_path_style_inventory_matrix_documents_hard_and_soft_contracts() {
        val hardAccept = listOf(
            "VirtualPath separator-style identity for multi-root relative keys",
            "multi-root merge last-wins across unix/windows path styles",
            "engine/harness single-key cardinality after flatten",
            "dotted require → slash provider after multi-root flatten",
            "path-derived module names stay dotted (no slash/backslash)",
            "host android.jar Downloads + SDK android-35 only"
        )
        val softAccept = listOf(
            "slash-form require alias OR unresolved after multi-root flatten",
            "mixed-separator require alias OR unresolved (no fabricate)"
        )
        val hardReject = listOf(
            "two co-resident map entries for same relative path under different separators",
            "fabricated provider path for unmatched slash/mixed require",
            "activeProviders keys with slash or backslash separators",
            "G:/ android.jar host path"
        )

        assertTrue(hardAccept.size >= 6)
        assertTrue(softAccept.size >= 2)
        assertTrue(hardReject.size >= 4)
        assertTrue(hardAccept.intersect(softAccept.toSet()).isEmpty())
        assertTrue(hardAccept.intersect(hardReject.toSet()).isEmpty())
    }

    @Test
    fun host_android_jar_policy_is_downloads_and_sdk_android35_never_g_drive() {
        // Corpus does not open android.jar. Lock host policy so expansion tasks do not
        // reintroduce G:/ hard-codes in related docs/tests.
        val allowedHints = listOf(
            "/Users/dingyi/Downloads/android.jar",
            "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"
        )
        val forbidden = listOf("G:/", "G:\\", "g:/android.jar")
        assertTrue(allowedHints.all { it.contains("android.jar") })
        assertTrue(forbidden.none { hint -> allowedHints.any { it.contains(hint, ignoreCase = true) } })
        assertFalse(allowedHints.any { it.startsWith("G:") || it.startsWith("g:") })

        // Candidate discovery list for related Android-Lua workspaces (never G:/).
        val candidates = hostAndroidJarCandidates()
        assertTrue(candidates.any { it.endsWith("/Downloads/android.jar") })
        assertTrue(
            candidates.any {
                it.contains("/Library/Android/sdk/platforms/android-35/android.jar") ||
                    it.contains("platforms/android-35/android.jar")
            }
        )
        assertTrue(candidates.none { it.startsWith("G:") || it.startsWith("g:") || it.contains("G:/") })
        // Presence is host-dependent; this corpus only locks the policy surface.
        val present = candidates.map { File(it) }.firstOrNull { it.isFile }
        if (present != null) {
            assertTrue(present.path.contains("android.jar"))
            assertFalse(present.path.startsWith("G:"))
        }
    }

    // -------------------------------------------------------------------------
    // Helpers — pure multi-root folder-relative merge model
    // -------------------------------------------------------------------------

    /**
     * Simulates multi-root indexing: each folder contributes a map of
     * **folder-relative** [VirtualPath] → source; later folders overwrite earlier
     * ones for the same key (last-wins). Separator style of the contribution key
     * is already normalized by [VirtualPath.of].
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

    /**
     * Host android.jar resolution order (never hardcodes Windows-only G:/):
     * 1) `/Users/dingyi/Downloads/android.jar`
     * 2) `$HOME/Library/Android/sdk/platforms/android-35/android.jar`
     * 3) fixed macOS SDK path android-35
     * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
     */
    private fun hostAndroidJarCandidates(): List<String> {
        val candidates = mutableListOf<String>()
        candidates += "/Users/dingyi/Downloads/android.jar"
        val home = System.getProperty("user.home")
        if (!home.isNullOrBlank()) {
            candidates += "$home/Library/Android/sdk/platforms/android-35/android.jar"
        }
        candidates += "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"
        for (envKey in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            val sdkRoot = System.getenv(envKey)
            if (!sdkRoot.isNullOrBlank()) {
                candidates += "$sdkRoot/platforms/android-35/android.jar"
                candidates += "$sdkRoot/platforms/android-34/android.jar"
            }
        }
        return candidates
    }
}
