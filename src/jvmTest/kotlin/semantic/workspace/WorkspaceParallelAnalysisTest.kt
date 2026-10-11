package semantic.workspace

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.workspace.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkspaceParallelAnalysisTest {
    @Test
    fun overlapping_require_expansion_keeps_independent_recursion_paths() {
        val dep = VirtualPath.of("dep.lua")
        val main = VirtualPath.of("main.lua")
        val enabled = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = object : JvmWorkspaceEngine() {
            override val checkDiagnostics = false
            internal override fun workspaceContext(input: LuaWorkspaceInput, path: VirtualPath, snapshot: WorkspaceSnapshot) =
                super.workspaceContext(input, path, snapshot).copy(onExpressionEvaluation = { node ->
                    if (enabled.get() && path == dep && Thread.currentThread().name == "blocked-module-query" &&
                        node is io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression) {
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS))
                    }
                })
        }
        val snapshot = engine.build(LuaWorkspaceInput(mapOf(
            dep to "import 'java.lang.StringBuilder'\nreturn { value = StringBuilder('text') }",
            main to "local dep = require 'dep'\ndep.value."
        ))).snapshot
        val resolver = WorkspaceModuleResolver(snapshot)
        enabled.set(true)
        val worker = java.util.concurrent.FutureTask {
            resolver.resolveRequire(main, "dep")!!.moduleType.fields.getValue("value").displayName
        }
        val thread = Thread(worker, "blocked-module-query").apply { start() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "provider expansion must actually overlap")
            val concurrent = resolver.resolveRequire(main, "dep")!!.moduleType.fields.getValue("value")
            assertTrue(concurrent.displayName.contains("StringBuilder"), concurrent.displayName)
        } finally {
            release.countDown()
            thread.join(10_000)
        }
        assertTrue(worker.get(10, TimeUnit.SECONDS).contains("StringBuilder"))
    }

    @Test
    fun failed_parallel_pass_does_not_publish_partial_parses_or_poison_the_next_build() {
        val fail = java.util.concurrent.atomic.AtomicBoolean(true)
        val engine = JvmWorkspaceEngine(workspaceParallelism = 4, analysisCheckpoint = {
            if (Thread.currentThread().name == "lua-workspace-frontend" && fail.compareAndSet(true, false)) {
                throw java.util.concurrent.CancellationException("cancelled front-end pass")
            }
        })
        val input = LuaWorkspaceInput(files = List(12) { VirtualPath.of("retry$it.lua") }
            .associateWith { "return { completeMember = 1 }" })
        assertFailsWith<java.util.concurrent.CancellationException> { engine.build(input) }
        val snapshot = engine.build(input).snapshot
        assertEquals(input.files.keys, snapshot.files.keys)
        snapshot.files.values.forEach { file ->
            assertEquals("return { completeMember = 1 }", file.semanticFile?.source)
            assertTrue(file.moduleExportSurface!!.moduleType.fields.containsKey("completeMember"))
        }
    }

    @Test
    fun independent_parsers_overlap_and_keep_distinct_asts_for_identical_sources() {
        val workers = ConcurrentHashMap.newKeySet<Long>()
        val started = CountDownLatch(2)
        val engine = JvmWorkspaceEngine(workspaceParallelism = 4, workspaceParserFactory = { version ->
            if (Thread.currentThread().name == "lua-workspace-frontend") {
                workers += Thread.currentThread().id
                started.countDown()
                check(started.await(5, TimeUnit.SECONDS)) { "independent parsers did not overlap" }
            }
            LuaParser(version)
        })
        val paths = List(8) { VirtualPath.of("file$it.lua") }
        val snapshot = engine.build(LuaWorkspaceInput(files = paths.associateWith { "local value = 1\nreturn value" })).snapshot
        assertTrue(workers.size >= 2)
        assertTrue(workers.size <= 4)
        val chunks = paths.map { snapshot.files.getValue(it).semanticFile!!.chunk }
        chunks.forEachIndexed { index, chunk ->
            chunks.take(index).forEach { assertNotSame(it, chunk, "equal source files must own separate ASTs") }
        }
    }

    @Test
    fun parallel_and_serial_builds_match_after_dependency_edits_and_removals() {
        val dep = VirtualPath.of("dep.lua")
        val main = VirtualPath.of("main.lua")
        val source = "local dep = require 'dep'\ndep."
        val files = linkedMapOf(
            dep to "return { oldMember = 1 }", main to source,
            VirtualPath.of("globals.lua") to "shared = { field = 1 }\nshared.helper = function() return 2 end",
            VirtualPath.of("java.lua") to "import 'java.util.ArrayList'\nlocal list = ArrayList()\nlist.add('x')",
            VirtualPath.of("cycleA.lua") to "require 'cycleB'\ncycleValueA = 1",
            VirtualPath.of("cycleB.lua") to "require 'cycleA'\ncycleValueB = cycleValueA",
            VirtualPath.of("duplicate1.lua") to "local x = 1\nreturn x",
            VirtualPath.of("duplicate2.lua") to "local x = 1\nreturn x"
        )
        val serial = JvmWorkspaceEngine(workspaceParallelism = 1)
        val parallel = JvmWorkspaceEngine(workspaceParallelism = 4)
        val input = LuaWorkspaceInput(files = files, standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3)
        var expected = serial.build(input).snapshot
        var actual = parallel.build(input).snapshot
        fun compare() {
            assertEquals(expected.files.keys, actual.files.keys)
            val expectedQueries = LuaWorkspaceQueryFacade(expected)
            val actualQueries = LuaWorkspaceQueryFacade(actual)
            expected.files.forEach { (path, file) ->
                assertEquals(file.publicFingerprint, actual.files.getValue(path).publicFingerprint, path.value)
                assertEquals(expectedQueries.diagnostics(path), actualQueries.diagnostics(path), path.value)
                assertEquals(file.semanticFile!!.snapshot.binder.declarationIndex.declarations.map { it.name to it.declaredType?.displayName },
                    actual.files.getValue(path).semanticFile!!.snapshot.binder.declarationIndex.declarations.map { it.name to it.declaredType?.displayName })
            }
            assertEquals(expectedQueries.completions(main, Position(2, 5)).map { it.label },
                actualQueries.completions(main, Position(2, 5)).map { it.label })
        }
        compare()
        assertTrue(LuaWorkspaceQueryFacade(actual).completions(main, Position(2, 5)).any { it.label == "oldMember" })
        val edit = WorkspaceDelta(upserts = mapOf(dep to "return { newMember = 'current' }", main to source + "\nlocal unused = 1"))
        expected = serial.update(expected, edit).snapshot
        actual = parallel.update(actual, edit).snapshot
        compare()
        val labels = LuaWorkspaceQueryFacade(actual).completions(main, Position(2, 5)).map { it.label }
        assertTrue("newMember" in labels)
        assertFalse("oldMember" in labels)
        val removal = WorkspaceDelta(removals = setOf(dep))
        expected = serial.update(expected, removal).snapshot
        actual = parallel.update(actual, removal).snapshot
        compare()
    }
}
