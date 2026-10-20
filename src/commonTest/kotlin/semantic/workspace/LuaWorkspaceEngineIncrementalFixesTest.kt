package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceModuleResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression coverage for three workspace-engine fixes:
 *
 * - SPEC A (provider-global fingerprint): editing a provider's NON-exported global must dirty
 *   its consumers; a whitespace-only edit must not.
 * - SPEC B (graph reuse): a single-file edge-neutral edit must reuse the previous module graph
 *   instance; a facts-changing edit must rebuild it.
 * - SPEC C (resolver re-pointing): after an update, every stored context must be re-pointed at
 *   ONE resolver derived from the returned snapshot, never a per-document mid-update resolver.
 */
class LuaWorkspaceEngineIncrementalFixesTest {
    private val providerPath = VirtualPath.of("P.lua")
    private val consumerPath = VirtualPath.of("consumer.lua")

    private fun workspaceInput(providerSource: String): LuaWorkspaceInput = LuaWorkspaceInput(
        files = mapOf(
            providerPath to providerSource,
            // The consumer only reads the provider's global through the module resolver, so the
            // provider's export surface (it has no return statement) never changes across these
            // edits and the global-symbol fingerprint is the only possible dirty seed.
            consumerPath to "local P = require(\"P\")\nreturn P.shared"
        ),
        standardLibraryOverlayVersion = LuaVersion.LUA_5_3
    )

    @Test
    fun provider_global_edit_dirties_consumers_but_whitespace_edit_does_not() {
        val engine = LuaWorkspaceEngine()
        val initial = engine.build(workspaceInput("shared = 42")).snapshot

        // Fixture guard: the consumer really binds the provider global through the resolver.
        assertTrue(
            "shared" in WorkspaceModuleResolver(initial).importedSymbolsFor(consumerPath),
            "fixture guard: the consumer must import the provider global 'shared'"
        )

        // Globals-only edit (number -> string literal): the provider's export surface and
        // document facts are unchanged, so only the global-symbol fingerprint can flag it.
        val globalsEdit = engine.update(
            previous = initial,
            delta = WorkspaceDelta(upserts = mapOf(providerPath to "shared = 'forty-two'"))
        )

        assertTrue(
            providerPath in globalsEdit.publicSurfaceChangedFiles,
            "provider whose non-exported global changed must be a public surface change; " +
                "got ${globalsEdit.publicSurfaceChangedFiles}"
        )
        assertTrue(
            consumerPath in globalsEdit.affectedDocuments,
            "consumer requiring the provider must be re-analyzed to re-bind the changed global; " +
                "got ${globalsEdit.affectedDocuments}"
        )

        // End to end: after re-analysis the consumer's resolver view of the global carries the
        // new value type, not the stale one from the initial build.
        val typeBefore = WorkspaceModuleResolver(initial)
            .importedSymbolsFor(consumerPath)
            .getValue("shared")
            .valueType
            .displayName
        val typeAfter = WorkspaceModuleResolver(globalsEdit.snapshot)
            .importedSymbolsFor(consumerPath)
            .getValue("shared")
            .valueType
            .displayName
        assertTrue(
            typeBefore != typeAfter,
            "consumer's bound type of the provider global must follow the edit: $typeBefore -> $typeAfter"
        )

        // Whitespace-only edit: identical globals and surface, so neither the provider's public
        // surface may be flagged nor the consumer dirtied.
        val whitespaceEdit = engine.update(
            previous = globalsEdit.snapshot,
            delta = WorkspaceDelta(upserts = mapOf(providerPath to "shared = 'forty-two'\n"))
        )

        assertFalse(
            providerPath in whitespaceEdit.publicSurfaceChangedFiles,
            "whitespace-only edit must not flag the provider; got ${whitespaceEdit.publicSurfaceChangedFiles}"
        )
        assertFalse(
            consumerPath in whitespaceEdit.affectedDocuments,
            "whitespace-only edit must not dirty the consumer; got ${whitespaceEdit.affectedDocuments}"
        )
    }

    @Test
    fun single_file_edge_neutral_edit_reuses_previous_graph_and_facts_change_rebuilds_it() {
        val engine = LuaWorkspaceEngine()
        val mainPath = VirtualPath.of("main.lua")
        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(mainPath to "local value = 1\nreturn value"),
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        ).snapshot

        // Edge-neutral edit: the source changes (cacheKey differs, file is re-analyzed) but the
        // graph-relevant document facts (requires, imports, module claims) are identical, so the
        // previous graph instance must be reused instead of rebuilt.
        val edgeNeutral = engine.update(
            previous = initial,
            delta = WorkspaceDelta(upserts = mapOf(mainPath to "local value = 2\nreturn value"))
        )

        assertSame(
            initial.graph,
            edgeNeutral.snapshot.graph,
            "edge-neutral single-file edit must reuse the previous module graph instance"
        )

        // Adding a require changes the document facts signature, so the graph must be rebuilt.
        val edgeChanged = engine.update(
            previous = edgeNeutral.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(mainPath to "local value = 2\nlocal mod = require(\"mod\")\nreturn value")
            )
        )

        assertNotSame(
            initial.graph,
            edgeChanged.snapshot.graph,
            "a facts-changing edit (new require) must rebuild the module graph"
        )
    }

    @Test
    fun every_stored_context_after_an_update_is_re_pointed_at_one_final_resolver() {
        val engine = LuaWorkspaceEngine()
        val previousSnapshot = engine.build(workspaceInput("shared = 42")).snapshot

        // A whitespace-only upsert keeps the consumer OUT of the dirty set, so its stored
        // context is carried over verbatim from the build — exactly the stale pin this spec
        // fixes. The re-point must still replace its resolver.
        val result = engine.update(
            previous = previousSnapshot,
            delta = WorkspaceDelta(upserts = mapOf(providerPath to "shared = 42\n"))
        )

        // Fixture guard: the consumer was not re-analyzed (same binder instance survived), so
        // its re-pointed resolver can only have come from the re-point pass.
        val previousConsumer = previousSnapshot.files.getValue(consumerPath).semanticFile
        val resultConsumer = result.snapshot.files.getValue(consumerPath).semanticFile
        assertSame(
            previousConsumer?.snapshot?.binder,
            resultConsumer?.snapshot?.binder,
            "fixture guard: the consumer must not have been re-analyzed by this update"
        )

        // Loop over every stored file: each context must carry a resolver, and all of them must
        // be the SAME instance — the single resolver built over the final snapshot.
        val storedResolvers = result.snapshot.files.mapNotNull { (_, fileSnapshot) ->
            fileSnapshot.semanticFile?.snapshot?.workspaceContext?.workspaceResolver
        }
        assertEquals(
            result.snapshot.files.size,
            storedResolvers.size,
            "every stored semantic file must carry a workspace context with a resolver"
        )
        assertTrue(storedResolvers.isNotEmpty(), "fixture guard: the workspace has analyzed files")
        val sharedResolver = storedResolvers.first()
        storedResolvers.forEach { resolver ->
            assertSame(sharedResolver, resolver, "all stored contexts must share one final resolver")
        }

        // The shared resolver must be fresh: none of the per-document resolvers stored by the
        // previous snapshot may have survived the update.
        val previousResolvers = previousSnapshot.files.mapNotNull { (_, fileSnapshot) ->
            fileSnapshot.semanticFile?.snapshot?.workspaceContext?.workspaceResolver
        }
        assertTrue(previousResolvers.isNotEmpty(), "fixture guard: the previous snapshot stored resolvers")
        previousResolvers.forEach { staleResolver ->
            assertNotSame(staleResolver, sharedResolver, "stored contexts must not keep a previous update's resolver")
        }

        // Behavioral identity with the returned snapshot: the shared resolver must derive
        // exactly what a resolver freshly built over the returned snapshot derives.
        val freshResolver = WorkspaceModuleResolver(result.snapshot)
        result.snapshot.files.keys.forEach { path ->
            assertEquals(
                freshResolver.importedSymbolsFor(path),
                sharedResolver.importedSymbolsFor(path),
                "stored resolver must derive imports from the returned snapshot for $path"
            )
        }
        assertEquals(
            freshResolver.resolveRequire(consumerPath, "P"),
            sharedResolver.resolveRequire(consumerPath, "P"),
            "stored resolver must resolve requires against the returned snapshot"
        )
    }
}
