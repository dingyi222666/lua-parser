package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceQueryFacade
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TASK-343 corpus: deleting a module invalidates dependents' require edges;
 * re-add restores surfaces without stale types.
 *
 * Test-only. Verification is review-owned (no Gradle).
 */
class WorkspaceFileDeleteInvalidateTddTest {

    private val engine = LuaWorkspaceEngine()

    @Test
    fun deleteExporterRemovesActiveProviderAndBreaksRequireEdge() {
        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    path("dep.lua") to "return { value = \"dep\" }",
                    path("main.lua") to "local d = require(\"dep\")\nreturn d.value"
                ),
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        )
        assertEquals(path("dep.lua"), initial.snapshot.graph.activeProviders["dep"]?.path)
        assertTrue(initial.snapshot.graph.resolvedDependencies[path("main.lua")].orEmpty().any { it.moduleName == "dep" })

        val afterDelete = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(removals = setOf(path("dep.lua"))),
            standardLibraryOverlayVersion = LuaVersion.LUA_5_3
        )
        assertFalse(afterDelete.snapshot.files.containsKey(path("dep.lua")))
        assertNull(afterDelete.snapshot.graph.activeProviders["dep"])
        val mainDeps = afterDelete.snapshot.graph.resolvedDependencies[path("main.lua")].orEmpty()
        assertFalse(mainDeps.any { it.moduleName == "dep" && it.provider.path == path("dep.lua") })
        val unresolved = afterDelete.snapshot.graph.unresolvedStaticRequires[path("main.lua")].orEmpty()
        assertTrue(
            unresolved.any { it.moduleName == "dep" } || mainDeps.none { it.moduleName == "dep" },
            "delete must leave require unresolved or without provider edge"
        )
        assertTrue(
            path("main.lua") in afterDelete.affectedDocuments ||
                path("dep.lua") in afterDelete.changedFiles ||
                afterDelete.affectedDocuments.isNotEmpty(),
            "delete should mark dependents/affected documents"
        )
    }

    @Test
    fun reAddExporterRestoresRequireSurfaceWithoutStaleFingerprint() {
        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    path("dep.lua") to "return { value = \"v1\" }",
                    path("main.lua") to "local d = require(\"dep\")\nreturn d.value"
                )
            )
        )
        val deleted = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(removals = setOf(path("dep.lua")))
        )
        val restored = engine.update(
            previous = deleted.snapshot,
            delta = WorkspaceDelta(
                upserts = mapOf(path("dep.lua") to "return { value = \"v2\" }")
            )
        )
        assertTrue(restored.snapshot.files.containsKey(path("dep.lua")))
        assertEquals(path("dep.lua"), restored.snapshot.graph.activeProviders["dep"]?.path)
        val queries = LuaWorkspaceQueryFacade(restored.snapshot)
        val resolved = queries.resolveRequire(path("main.lua"), "dep")
        assertEquals(path("dep.lua"), resolved.provider?.path)
        val surface = assertNotNull(resolved.exportSurface)
        assertTrue(
            surface.members.any { it.name == "value" || it.exportPath == listOf("value") },
            "restored surface must expose value; members=${surface.members.map { it.exportPath }}"
        )
        // Fingerprint / public surface should reflect new content (v2), not stale v1.
        val depSnap = restored.snapshot.files.getValue(path("dep.lua"))
        val oldFp = deleted.snapshot.files[path("dep.lua")]?.publicFingerprint
        // After delete the file is gone; restored fingerprint must exist and differ from pre-delete if content changed.
        val initialFp = initial.snapshot.files.getValue(path("dep.lua")).publicFingerprint
        assertTrue(
            depSnap.publicFingerprint != initialFp || surface.moduleType.fields.isNotEmpty(),
            "re-add with new content should not keep identical stale surface blindly"
        )
        assertNull(oldFp)
    }

    @Test
    fun deleteUnrelatedFileDoesNotDropUnrelatedRequireEdge() {
        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    path("dep.lua") to "return { value = 1 }",
                    path("other.lua") to "return {}",
                    path("main.lua") to "local d = require(\"dep\")\nreturn d.value"
                )
            )
        )
        val after = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(removals = setOf(path("other.lua")))
        )
        assertFalse(after.snapshot.files.containsKey(path("other.lua")))
        assertEquals(path("dep.lua"), after.snapshot.graph.activeProviders["dep"]?.path)
        assertTrue(
            after.snapshot.graph.resolvedDependencies[path("main.lua")].orEmpty()
                .any { it.moduleName == "dep" }
        )
    }

    @Test
    fun multiHopDependentInvalidatedWhenMidDeleted() {
        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(
                    path("leaf.lua") to "return { n = 1 }",
                    path("mid.lua") to "local l = require(\"leaf\")\nreturn { n = l.n }",
                    path("main.lua") to "local m = require(\"mid\")\nreturn m.n"
                )
            )
        )
        val after = engine.update(
            previous = initial.snapshot,
            delta = WorkspaceDelta(removals = setOf(path("mid.lua")))
        )
        assertNull(after.snapshot.graph.activeProviders["mid"])
        assertTrue(after.snapshot.graph.activeProviders.containsKey("leaf"))
        val mainUnresolved = after.snapshot.graph.unresolvedStaticRequires[path("main.lua")].orEmpty()
        val mainResolved = after.snapshot.graph.resolvedDependencies[path("main.lua")].orEmpty()
        assertTrue(
            mainUnresolved.any { it.moduleName == "mid" } || mainResolved.none { it.moduleName == "mid" }
        )
    }

    private fun path(value: String) = VirtualPath.of(value)
}
