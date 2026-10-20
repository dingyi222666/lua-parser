package semantic.workspace

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.workspace.LuaWorkspaceInput
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceDelta
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Regression coverage for the graph-reuse range hash: the module graph bakes each document
 * fact's RANGE into its entries (resolved dependencies, unresolved requires, dynamic require
 * sites), but the document-facts fingerprint hashes requires / source imports / dynamic
 * requires by NAME only. A range-only edit — inserting a line above a `require` — used to
 * pass the single-upsert reuse guard and leave the reused graph holding stale fact ranges.
 *
 * The facts signature the guard compares therefore also hashes the graph-relevant fact
 * ranges (line/column ints), so:
 * - a range-only facts edit must REBUILD the graph, and
 * - an edit that shifts no fact range must still REUSE the previous graph instance.
 */
class LuaWorkspaceEngineGraphRangeHashTest {

    @Test
    fun range_only_facts_edit_rebuilds_the_module_graph() {
        val engine = LuaWorkspaceEngine()
        val mainPath = VirtualPath.of("main.lua")
        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(mainPath to "local mod = require(\"mod\")\nreturn mod"),
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        ).snapshot

        // Insert a comment line ABOVE the require: the require NAME (and thus the facts
        // fingerprint) is unchanged, but the fact's range shifts down one line. The graph
        // must be rebuilt so its stored ranges stay current.
        val shifted = engine.update(
            previous = initial,
            delta = WorkspaceDelta(
                upserts = mapOf(mainPath to "-- inserted\nlocal mod = require(\"mod\")\nreturn mod")
            )
        )

        assertNotSame(
            initial.graph,
            shifted.snapshot.graph,
            "a range-only facts edit must rebuild the module graph instead of baking stale " +
                "fact ranges into the reused instance"
        )
    }

    @Test
    fun edit_that_keeps_fact_ranges_still_reuses_the_module_graph() {
        val engine = LuaWorkspaceEngine()
        val mainPath = VirtualPath.of("main.lua")
        val initial = engine.build(
            LuaWorkspaceInput(
                files = mapOf(mainPath to "local mod = require(\"mod\")\nreturn mod"),
                standardLibraryOverlayVersion = LuaVersion.LUA_5_3
            )
        ).snapshot

        // Append to the trailing statement: every graph-relevant fact keeps its exact
        // line/column range, so the graph instance must still be reused.
        val kept = engine.update(
            previous = initial,
            delta = WorkspaceDelta(
                upserts = mapOf(mainPath to "local mod = require(\"mod\")\nreturn mod -- note")
            )
        )

        assertSame(
            initial.graph,
            kept.snapshot.graph,
            "an edit that leaves all fact ranges intact must keep reusing the previous graph"
        )
    }
}
