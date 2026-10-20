package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Wave K resolver-reuse corpus (wildcard-audit finding 3).
 *
 * [JvmWorkspaceEngine.workspaceContext] used to build a fresh WorkspaceModuleResolver per
 * analyzed file per update, so the resolver's caches (importedSymbolsFor keyed by path,
 * activeProvider keyed by module name, provider globals keyed by provider path) never
 * survived and `packageMembers` re-ran its O(wildcardMembers x extraProviders) linear scans
 * for every file of every keystroke. The engine now reuses one resolver across the files of
 * the same analysis snapshot, keyed by snapshot identity.
 *
 * Soundness pins encoded here:
 * - two files served against the SAME snapshot instance share one resolver instance;
 * - a repeated path (cycle re-analysis sweep, where attachSemanticState rewrites peer
 *   semantic files) starts a FRESH resolver generation, so a cached import map can never
 *   outlive the peer binding state it was derived from.
 *
 * This worker does not run Gradle; serial review owns verification.
 */
class JvmWorkspaceResolverReuseTddTest {
    @Test
    fun two_files_of_one_update_share_the_workspace_resolver() {
        val engine = JvmWorkspaceEngine()
        val pathA = VirtualPath.of("a.lua")
        val pathB = VirtualPath.of("b.lua")
        val input = LuaWorkspaceInput(
            files = linkedMapOf(
                pathA to "local alpha = 1",
                pathB to "local beta = 2"
            )
        )
        val snapshot = engine.build(input).snapshot

        val contextA = engine.workspaceContext(input, pathA, snapshot)
        val contextB = engine.workspaceContext(input, pathB, snapshot)

        assertTrue(
            contextA.workspaceResolver != null && contextB.workspaceResolver != null,
            "Both documents must carry a workspace resolver"
        )
        assertSame(
            contextA.workspaceResolver,
            contextB.workspaceResolver,
            "Files of the same analysis snapshot must reuse one WorkspaceModuleResolver so " +
                "its per-path caches survive across the update's files"
        )
    }

    @Test
    fun repeated_path_starts_a_fresh_resolver_generation() {
        val engine = JvmWorkspaceEngine()
        val pathA = VirtualPath.of("a.lua")
        val input = LuaWorkspaceInput(files = linkedMapOf(pathA to "local alpha = 1"))
        val snapshot = engine.build(input).snapshot

        val first = engine.workspaceContext(input, pathA, snapshot).workspaceResolver
        val second = engine.workspaceContext(input, pathA, snapshot).workspaceResolver

        assertNotSame(
            first,
            second,
            "A repeated path marks a cycle re-analysis sweep; sweeps must start a fresh " +
                "resolver generation instead of replaying cached per-path state"
        )
    }
}
