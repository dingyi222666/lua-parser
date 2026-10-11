package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.Position
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import io.github.dingyi222666.luaparser.semantic.workspace.AnalysisProgress
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import java.util.concurrent.atomic.AtomicBoolean

class LspDeferredDocumentAnalysisTest {
    @Test
    fun typed_prefix_returns_fuzzy_candidates_without_poisoning_full_completion() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val uri = "file:///workspace/completion-prefix.lua"
        val source = "local getSharedValue=function() return 1 end\nlocal unrelated=1\ngsv"
        val documents = LuaTextDocumentService(service)
        try {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
            val typed = documents.completion(CompletionParams(TextDocumentIdentifier(uri), Position(2, 3)))
                .get(15, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue("getSharedValue" in typed, typed.toString())
            assertFalse("unrelated" in typed, typed.toString())
            val all = documents.completion(CompletionParams(TextDocumentIdentifier(uri), Position(2, 0)))
                .get(15, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue("getSharedValue" in all && "unrelated" in all, all.toString())
        } finally {
            service.close()
        }
    }

    @Test
    fun completion_after_close_returns_a_cancelled_future_instead_of_rejecting_submission() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        service.close()
        val request = service.completionAsync("file:///workspace/closed.lua", 0, 0)
        assertTrue(request.isCompletedExceptionally)
        kotlin.test.assertFailsWith<java.util.concurrent.CancellationException> { request.get() }
    }

    @Test
    fun queued_completion_rechecks_document_revision_when_the_worker_becomes_available() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val uri = "file:///workspace/queued-completion.lua"
        val documents = LuaTextDocumentService(service)
        val params = CompletionParams(TextDocumentIdentifier(uri), Position(1, 6))
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val analysisStarted = CountDownLatch(1)
        val releaseAnalysis = CountDownLatch(1)
        try {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1,
                "function table.oldMember() end\ntable.")))
            assertTrue(documents.completion(params).get(15, TimeUnit.SECONDS).right.items.any { it.label == "oldMember" })
            val worker = LuaLanguageService::class.java.getDeclaredField("completionExecutor")
                .apply { isAccessible = true }.get(service) as java.util.concurrent.ExecutorService
            worker.submit {
                workerStarted.countDown()
                check(releaseWorker.await(15, TimeUnit.SECONDS))
            }
            assertTrue(workerStarted.await(10, TimeUnit.SECONDS))
            val requested = documents.completion(params)
            service.onLiveAnalysisProgress = {
                analysisStarted.countDown()
                check(releaseAnalysis.await(15, TimeUnit.SECONDS))
            }
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent("function table.latestMember() end\ntable."))))
            assertTrue(analysisStarted.await(10, TimeUnit.SECONDS))
            releaseWorker.countDown()
            // The old model is still published, but the queued request must wait for analysis.
            assertFalse(runCatching { requested.get(200, TimeUnit.MILLISECONDS) }.isSuccess)
            releaseAnalysis.countDown()
            val labels = requested.get(15, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue("latestMember" in labels, labels.toString())
            assertFalse("oldMember" in labels, labels.toString())
        } finally {
            releaseWorker.countDown()
            releaseAnalysis.countDown()
            service.close()
        }
    }

    @Test
    fun cold_live_wildcard_import_resolves_referenced_class_without_building_package_catalog() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val uri = "file:///workspace/wildcard-live.lua"
        val documents = LuaTextDocumentService(service)
        try {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 0,
                "import 'java.util.*'\nlocal list = ArrayList()\nlist.")))
            val labels = documents.completion(CompletionParams(
                TextDocumentIdentifier(uri), Position(2, 5)
            )).get(15, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue("add" in labels && "clear" in labels, labels.toString())
        } finally {
            service.close()
        }
    }

    @Test
    fun unrelated_edit_keeps_a_running_document_report_and_the_new_query_snapshot() {
        val root = Files.createTempDirectory("independent-check-publication")
        val first = root.resolve("first.lua").toUri().toString()
        val second = root.resolve("second.lua").toUri().toString()
        val firstSource = "local unusedFirst = 1\nlocal item = { value = 1 }\nprint(item.value)"
        Files.writeString(root.resolve("first.lua"), firstSource)
        Files.writeString(root.resolve("second.lua"), "local value = { oldMember = 1 }\nvalue.")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val block = AtomicBoolean(true)
        val engine = object : JvmWorkspaceEngine(analysisCheckpoint = {
            if (Thread.currentThread().name == "lsp-live-diagnostics" && block.compareAndSet(true, false)) {
                started.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            }
        }) { override val checkDiagnostics = false }
        val service = LuaLanguageService(engine = engine, deferDocumentAnalysis = true)
        val reports = java.util.concurrent.ConcurrentHashMap<String, List<org.eclipse.lsp4j.Diagnostic>>()
        val documents = LuaTextDocumentService(service, publishDiagnostics = { reports[it.uri] = it.diagnostics })
        try {
            service.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "workspace"))
            })
            assertTrue(service.flushBackgroundRebuild(10_000))
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(first, "lua", 1,
                firstSource)))
            assertTrue(started.await(10, TimeUnit.SECONDS))
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(second, "lua", 1,
                "local value = { oldMember = 1 }\nvalue.")))
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(second, 2),
                listOf(TextDocumentContentChangeEvent("local value = { newMember = 1 }\nvalue."))))
            await { service.completion(second, 1, 6).items.any { it.label == "newMember" } }
            release.countDown()
            await { reports[first].orEmpty().any { it.message.contains("unusedFirst") } }
            assertTrue(service.completion(second, 1, 6).items.any { it.label == "newMember" })
            assertFalse(service.completion(second, 1, 6).items.any { it.label == "oldMember" })
        } finally {
            release.countDown()
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun full_index_hydrates_java_objects_returned_by_disk_dependencies_and_checks_missing_members() =
        checkJavaDependency(LuaLanguageService(deferDocumentAnalysis = true))

    @Test
    fun eager_full_index_keeps_java_objects_returned_by_disk_dependencies() =
        checkJavaDependency(LuaLanguageService(engine = object : JvmWorkspaceEngine() {
            override val checkDiagnostics = false
        }, deferDocumentAnalysis = true))

    private fun checkJavaDependency(service: LuaLanguageService) {
        val root = Files.createTempDirectory("deferred-java-dependency")
        Files.writeString(root.resolve("dep.lua"), "import 'java.lang.StringBuilder'\nreturn { value = StringBuilder('text') }")
        val uri = root.resolve("main.lua").toUri().toString()
        val source = "local dep = require 'dep'\ndep.value."
        Files.writeString(root.resolve("main.lua"), source)
        val reports = java.util.concurrent.ConcurrentHashMap<String, List<org.eclipse.lsp4j.Diagnostic>>()
        val documents = LuaTextDocumentService(service, publishDiagnostics = { reports[it.uri] = it.diagnostics })
        try {
            service.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "workspace"))
            })
            assertTrue(service.flushBackgroundRebuild(10_000))
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 0, source)))
            val labels = documents.completion(CompletionParams(TextDocumentIdentifier(uri), Position(1, 10)))
                .get(15, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue(labels.containsAll(listOf("append", "setLength")), buildString {
                append(labels)
                val snapshot = LuaLanguageService::class.java.getDeclaredField("snapshot").apply { isAccessible = true }
                    .get(service) as io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
                snapshot.files.filterKeys { it.value in listOf("main.lua", "dep.lua") }.forEach { (path, file) ->
                    append("\n").append(path).append(": ").append(file.semanticFile?.source)
                    val semantic = file.semanticFile ?: return@forEach
                    semantic.chunk.body.statements.filterIsInstance<io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement>().forEach { local ->
                        local.variables.forEach { append("\nRHS: ").append(semantic.model.getTypeAt(it)) }
                    }
                    semantic.chunk.body.returnStatement?.arguments?.forEach { append("\nEXPORT: ").append(semantic.model.getTypeAt(it)) }
                }
            })
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 1),
                listOf(TextDocumentContentChangeEvent("local dep = require 'dep'\nprint(dep.value.missingMember)"))))
            await { reports[uri].orEmpty().any { it.message.contains("Unknown Java member 'missingMember'") } }
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent("local dep = require 'dep'\nlocal unusedJavaDependency = 1\nprint(dep.value.length())"))))
            await {
                reports[uri].orEmpty().any { it.message.contains("unusedJavaDependency") } &&
                    reports[uri].orEmpty().none { it.message.contains("missingMember") }
            }
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun full_index_waits_for_the_first_live_query_snapshot_instead_of_a_fixed_delay() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val documents = LuaTextDocumentService(service)
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fullBuild = CountDownLatch(1)
        val block = AtomicBoolean(true)
        service.onLiveAnalysisProgress = { progress ->
            if (progress.phase == AnalysisProgress.Phase.PARSING && block.compareAndSet(true, false)) {
                parsing.countDown()
                release.await(20, TimeUnit.SECONDS)
            }
        }
        service.onBuildProgress = { fullBuild.countDown() }
        try {
            service.initialize(InitializeParams())
            val uri = "file:///workspace/foreground.lua"
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 0,
                "local item = { foregroundMember = 1 }\nitem.")))
            assertTrue(parsing.await(5, TimeUnit.SECONDS))
            assertFalse(fullBuild.await(9, TimeUnit.SECONDS),
                "full indexing must not compete with an unfinished initial foreground pass")
            release.countDown()
            val completion = documents.completion(CompletionParams(TextDocumentIdentifier(uri), Position(1, 5)))
                .get(15, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue("foregroundMember" in completion)
            assertTrue(fullBuild.await(5, TimeUnit.SECONDS), "publication must release the full index")
            assertTrue(service.flushBackgroundRebuild(10_000))
        } finally {
            release.countDown()
            service.close()
        }
    }

    @Test
    fun cold_android_bootstrap_preserves_open_dependencies_and_answers_both_documents() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val documents = LuaTextDocumentService(service)
        val helper = "file:///workspace/helper.lua"
        val main = "file:///workspace/main.lua"
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val block = AtomicBoolean(true)
        service.onLiveAnalysisProgress = { progress ->
            if (progress.phase == AnalysisProgress.Phase.PARSING && block.compareAndSet(true, false)) {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        fun complete(uri: String, line: Int, column: Int) = documents.completion(CompletionParams(
            TextDocumentIdentifier(uri), Position(line, column))).get(10, TimeUnit.SECONDS).right.items.map { it.label }
        try {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(helper, "lua", 1,
                "local own = { helperMember = 1 }\nprint(own.helperMember)\nreturn { fromDependency = 1 }")))
            assertTrue(started.await(10, TimeUnit.SECONDS))
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(main, "lua", 1,
                "local value = require 'helper'\nvalue.")))
            release.countDown()
            assertTrue("fromDependency" in complete(main, 1, 6), "cold completion must include the open dependency")
            assertTrue("helperMember" in complete(helper, 1, 10), "the earlier open document must remain queryable")
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(helper, 2),
                listOf(TextDocumentContentChangeEvent("return { replacementMember = 1 }"))))
            await {
                val labels = complete(main, 1, 6)
                "replacementMember" in labels && "fromDependency" !in labels
            }
        } finally {
            release.countDown()
            service.close()
        }
    }

    @Test
    fun superseded_full_workspace_checker_stops_before_evaluating_the_remaining_expressions() {
        val root = Files.createTempDirectory("deferred-check-cancel")
        val original = "local unusedOriginal = 1\nlocal item={value=1}\n" +
            List(250) { "print(item.value)" }.joinToString("\n")
        Files.writeString(root.resolve("main.lua"), original)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val block = AtomicBoolean(true)
        val checkpoints = java.util.concurrent.atomic.AtomicInteger()
        val engine = object : JvmWorkspaceEngine(analysisCheckpoint = {
            if (Thread.currentThread().name == "lsp-live-diagnostics") {
                checkpoints.incrementAndGet()
                if (block.compareAndSet(true, false)) {
                    started.countDown()
                    release.await(15, TimeUnit.SECONDS)
                }
            }
        }) {
            override val checkDiagnostics = false
        }
        val service = LuaLanguageService(engine = engine, deferDocumentAnalysis = true)
        val uri = root.resolve("main.lua").toUri().toString()
        val reports = java.util.concurrent.ConcurrentHashMap<String, List<org.eclipse.lsp4j.Diagnostic>>()
        val documents = LuaTextDocumentService(service, publishDiagnostics = { reports[it.uri] = it.diagnostics })
        try {
            service.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "workspace"))
            })
            assertTrue(service.flushBackgroundRebuild(10_000))
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, original)))
            assertTrue(started.await(10, TimeUnit.SECONDS))
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent("local unusedCurrent = 1\nprint('current')"))))
            release.countDown()
            await { reports[uri].orEmpty().any { it.message.contains("unusedCurrent") } }
            assertTrue(checkpoints.get() < 30,
                "superseded checker must not walk its 250 old expressions: ${checkpoints.get()}")
            assertTrue(reports[uri].orEmpty().none { it.message.contains("unusedOriginal") })
        } finally {
            release.countDown()
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun opening_an_unchanged_indexed_document_checks_it_and_reopening_restores_its_report() {
        val root = Files.createTempDirectory("deferred-indexed-open")
        val source = "local unusedIndexedValue = 1\nprint('ready')"
        Files.writeString(root.resolve("main.lua"), source)
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val uri = root.resolve("main.lua").toUri().toString()
        val reports = java.util.concurrent.ConcurrentHashMap<String, List<org.eclipse.lsp4j.Diagnostic>>()
        val documents = LuaTextDocumentService(service, publishDiagnostics = { reports[it.uri] = it.diagnostics })
        try {
            service.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "workspace"))
            })
            assertTrue(service.flushBackgroundRebuild(10_000))
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
            await { reports[uri].orEmpty().any { it.message.contains("unusedIndexedValue") } }
            documents.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
            await { reports[uri]?.isEmpty() == true }
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 2, source)))
            await { reports[uri].orEmpty().any { it.message.contains("unusedIndexedValue") } }
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun full_workspace_queries_publish_while_diagnostics_are_blocked_then_refresh_after_edits() {
        val root = Files.createTempDirectory("deferred-query-first")
        Files.writeString(root.resolve("dep.lua"), "return { value = 'text', indexedMember = 1 }")
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val uri = root.resolve("main.lua").toUri().toString()
        val source = "local dep = require 'dep'\n---@return number\nfunction read() return dep.value end\n" +
            "dep.value\nlocal unusedMain = 1\nprint('ready')"
        val releaseBuild = CountDownLatch(1)
        val checked = CountDownLatch(1)
        val releaseChecker = CountDownLatch(1)
        val blockBuild = AtomicBoolean(true)
        val blockChecker = AtomicBoolean(true)
        val reports = java.util.concurrent.ConcurrentHashMap<String, List<org.eclipse.lsp4j.Diagnostic>>()
        service.onBuildProgress = { progress ->
            if (progress.phase == AnalysisProgress.Phase.PARSING && blockBuild.compareAndSet(true, false)) {
                releaseBuild.await(15, TimeUnit.SECONDS)
            }
        }
        service.onLiveDiagnosticsFileChecked = { path ->
            if (path.value == "main.lua" && blockChecker.compareAndSet(true, false)) {
                checked.countDown()
                releaseChecker.await(15, TimeUnit.SECONDS)
            }
        }
        val documents = LuaTextDocumentService(service, publishDiagnostics = { reports[it.uri] = it.diagnostics })
        try {
            service.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "workspace"))
            })
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
            assertTrue(checked.await(10, TimeUnit.SECONDS), "cold checker should run before the full index")
            releaseBuild.countDown()
            assertTrue(service.flushBackgroundRebuild(10_000), "full queries must not wait for the diagnostic worker")
            assertTrue(service.completion(uri, 3, 4).items.any { it.label == "indexedMember" })
            releaseChecker.countDown()
            await { reports[uri].orEmpty().any { it.severity == org.eclipse.lsp4j.DiagnosticSeverity.Error } }
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent(source.replace("---@return number", "---@return string")))))
            await {
                val diagnostics = reports[uri].orEmpty()
                diagnostics.any { it.message.contains("unusedMain") } &&
                    diagnostics.none { it.severity == org.eclipse.lsp4j.DiagnosticSeverity.Error }
            }
        } finally {
            releaseBuild.countDown()
            releaseChecker.countDown()
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun checked_document_diagnostics_arrive_while_another_document_is_still_running() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val reports = java.util.concurrent.ConcurrentHashMap<String, List<org.eclipse.lsp4j.Diagnostic>>()
        val documents = LuaTextDocumentService(service, publishDiagnostics = { reports[it.uri] = it.diagnostics })
        val first = "file:///workspace/first.lua"
        val second = "file:///workspace/second.lua"
        val analysisStarted = CountDownLatch(1)
        val startAnalysis = CountDownLatch(1)
        val secondChecked = CountDownLatch(1)
        val finishSecond = CountDownLatch(1)
        val blockAnalysis = AtomicBoolean(true)
        val blockSecond = AtomicBoolean(true)
        service.onLiveAnalysisProgress = { progress ->
            if (progress.phase == AnalysisProgress.Phase.PARSING && blockAnalysis.compareAndSet(true, false)) {
                analysisStarted.countDown()
                startAnalysis.await(10, TimeUnit.SECONDS)
            }
        }
        service.onLiveDiagnosticsFileChecked = { path ->
            if (path.value.endsWith("second.lua") && blockSecond.compareAndSet(true, false)) {
                secondChecked.countDown()
                finishSecond.await(10, TimeUnit.SECONDS)
            }
        }
        try {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(first, "lua", 1, "local unusedFirst = 1\nprint('ready')")))
            assertTrue(analysisStarted.await(10, TimeUnit.SECONDS))
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(second, "lua", 1, "local unusedSecond = 1\nprint('ready')")))
            startAnalysis.countDown()
            assertTrue(secondChecked.await(10, TimeUnit.SECONDS))
            assertTrue(reports[second].orEmpty().any { it.message.contains("unusedSecond") },
                "a checked file must publish before the whole batch finishes: $reports")
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(first, 2),
                listOf(TextDocumentContentChangeEvent("local unusedFirst = 1\nprint(unusedFirst)"))))
            finishSecond.countDown()
            await { reports[first]?.isEmpty() == true }
        } finally {
            startAnalysis.countDown()
            finishSecond.countDown()
            service.close()
        }
    }

    @Test
    fun edited_provider_diagnostics_are_published_before_its_unchanged_consumer_finishes() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val reports = java.util.concurrent.ConcurrentHashMap<String, List<org.eclipse.lsp4j.Diagnostic>>()
        val documents = LuaTextDocumentService(service, publishDiagnostics = { reports[it.uri] = it.diagnostics })
        val consumer = "file:///workspace/consumer.lua"
        val provider = "file:///workspace/provider.lua"
        val consumerChecked = CountDownLatch(1)
        val finishConsumer = CountDownLatch(1)
        val blockConsumer = AtomicBoolean(false)
        service.onLiveDiagnosticsFileChecked = { path ->
            if (path.value.endsWith("consumer.lua") && blockConsumer.compareAndSet(true, false)) {
                consumerChecked.countDown()
                finishConsumer.await(10, TimeUnit.SECONDS)
            }
        }
        try {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(consumer, "lua", 1,
                "local provider = require 'provider'\n---@return number\nfunction read() return provider.value end")))
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(provider, "lua", 1,
                "local initialUnusedProvider = 1\nreturn { value = 1 }")))
            await { service.diagnosticsForUri(provider).diagnostics.any { it.message.contains("initialUnusedProvider") } }
            reports.clear()
            blockConsumer.set(true)
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(provider, 2),
                listOf(TextDocumentContentChangeEvent("local unusedChangedProvider = 1\nreturn { value = 'changed' }"))))
            assertTrue(consumerChecked.await(10, TimeUnit.SECONDS))
            assertTrue(reports[provider].orEmpty().any { it.message.contains("unusedChangedProvider") },
                "the edited file must be checked and published before dependent consumers")
        } finally {
            finishConsumer.countDown()
            service.close()
        }
    }

    @Test
    fun live_edits_reuse_unchanged_diagnostics_and_replace_changed_file_diagnostics() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val reports = java.util.concurrent.ConcurrentHashMap<String, List<org.eclipse.lsp4j.Diagnostic>>()
        val checks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        service.onLiveDiagnosticsFileChecked = { path ->
            checks.computeIfAbsent(path.value.substringAfterLast('/')) {
                java.util.concurrent.atomic.AtomicInteger()
            }.incrementAndGet()
        }
        val documents = LuaTextDocumentService(service, publishDiagnostics = { reports[it.uri] = it.diagnostics })
        val first = "file:///workspace/first.lua"
        val second = "file:///workspace/second.lua"
        fun hasUnused(uri: String) = reports[uri].orEmpty().any { it.message.contains("unused", ignoreCase = true) }
        try {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(first, "lua", 1,
                "local unusedFirst = 1\nprint('ready')")))
            await { hasUnused(first) }
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(second, "lua", 1,
                "local unusedSecond = 1\nprint('ready')")))
            await { hasUnused(second) }
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(second, 2),
                listOf(TextDocumentContentChangeEvent("local usedSecond = 1\nprint(usedSecond)"))))
            await { checks["second.lua"]?.get() == 2 && reports[second]?.isEmpty() == true }
            assertTrue(hasUnused(first), "reusing a checked file must preserve its warnings")
            kotlin.test.assertEquals(1, checks["first.lua"]?.get(), "an unrelated edit must not recheck this file")
        } finally {
            service.close()
        }
    }

    @Test
    fun live_diagnostic_reuse_is_invalidated_when_a_required_module_changes() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val reports = java.util.concurrent.ConcurrentHashMap<String, List<org.eclipse.lsp4j.Diagnostic>>()
        val consumerChecks = java.util.concurrent.atomic.AtomicInteger()
        service.onLiveDiagnosticsFileChecked = { path ->
            if (path.value.endsWith("consumer.lua")) consumerChecks.incrementAndGet()
        }
        val documents = LuaTextDocumentService(service, publishDiagnostics = { reports[it.uri] = it.diagnostics })
        val provider = "file:///workspace/provider.lua"
        val consumer = "file:///workspace/consumer.lua"
        fun hasError() = reports[consumer].orEmpty().any { it.severity == org.eclipse.lsp4j.DiagnosticSeverity.Error }
        try {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(provider, "lua", 1, "return { value = 1 }")))
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(consumer, "lua", 1,
                "local provider = require 'provider'\n---@return number\nfunction read() return provider.value end")))
            await { consumerChecks.get() >= 1 && reports.containsKey(consumer) }
            assertFalse(hasError(), reports[consumer].toString())
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(provider, 2),
                listOf(TextDocumentContentChangeEvent("return { value = 'wrong' }"))))
            await { hasError() }
            val afterError = consumerChecks.get()
            assertTrue(afterError >= 2, "dependency changes must recheck consumers")
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(provider, 3),
                listOf(TextDocumentContentChangeEvent("return { value = 1 }"))))
            await { consumerChecks.get() > afterError && !hasError() }
        } finally {
            service.close()
        }
    }

    @Test
    fun cold_dependency_globals_preserve_members_extensions_and_import_shadowing() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val documents = LuaTextDocumentService(service)
        fun open(name: String, source: String) {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(
                "file:///workspace/$name.lua", "lua", 0, source)))
        }
        fun complete(name: String, line: Int, column: Int) = documents.completion(CompletionParams(
            TextDocumentIdentifier("file:///workspace/$name.lua"), Position(line, column)))
            .get(15, TimeUnit.SECONDS).right.items.map { it.label }
        try {
            open("helper", "StringBuilder = { ownMember = 1 }\nfunction table.remoteMember() end\nOtherGlobal = 42")
            open("main", "import 'java.lang.StringBuilder'\nrequire 'helper'\nStringBuilder.")
            val labels = complete("main", 2, 14)
            assertTrue("ownMember" in labels, labels.toString())
            assertFalse("append" in labels, "a dependency global must retain precedence over the Java import")
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(
                "file:///workspace/main.lua", 1), listOf(TextDocumentContentChangeEvent(
                    "require 'helper'\ntable."))))
            assertTrue(complete("main", 1, 6).containsAll(listOf("remoteMember", "insert")))
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(
                "file:///workspace/main.lua", 2), listOf(TextDocumentContentChangeEvent(
                    "require 'helper'\nOther"))))
            assertTrue("OtherGlobal" in complete("main", 1, 5), "lexical completion must still enumerate dependency globals")
        } finally {
            service.close()
        }
    }

    @Test
    fun superseded_live_analysis_is_cancelled_and_the_waiting_query_gets_the_latest_revision() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val documents = LuaTextDocumentService(service)
        val uri = "file:///workspace/superseded.lua"
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val blockFirstAnalysis = AtomicBoolean(true)
        val completedAnalyses = java.util.concurrent.atomic.AtomicInteger()
        service.onLiveAnalysisProgress = { progress ->
            if (progress.phase == AnalysisProgress.Phase.PARSING && blockFirstAnalysis.compareAndSet(true, false)) {
                started.countDown()
                resume.await(10, TimeUnit.SECONDS)
            }
            if (progress.phase == AnalysisProgress.Phase.COMPLETE) completedAnalyses.incrementAndGet()
        }
        try {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 0,
                "function table.obsolete() end\ntable.")))
            assertTrue(started.await(10, TimeUnit.SECONDS))
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 1),
                listOf(TextDocumentContentChangeEvent("function table.current() end\ntable."))))
            val completion = documents.completion(CompletionParams(TextDocumentIdentifier(uri), Position(1, 6)))
            resume.countDown()
            val labels = completion.get(15, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue(labels.containsAll(listOf("current", "insert")), labels.toString())
            assertFalse("obsolete" in labels)
            kotlin.test.assertEquals(1, completedAnalyses.get(), "only the current revision should finish analysis")
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent("function table.following() end\ntable."))))
            val following = documents.completion(CompletionParams(TextDocumentIdentifier(uri), Position(1, 6)))
                .get(15, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue("following" in following && "current" !in following, following.toString())
        } finally {
            resume.countDown()
            service.close()
        }
    }

    @Test
    fun completion_of_new_nested_document_after_another_buffer_is_published() {
        val root = Files.createTempDirectory("deferred-nested")
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val documents = LuaTextDocumentService(service)
        try {
            service.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "workspace"))
            })
            val mainUri = root.resolve("main.lua").toUri().toString()
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(mainUri, "lua", 0, "print('hello')")))
            val uri = root.resolve("mods/dingyi.lua").toUri().toString()
            val source = java.io.File("android/sample/src/main/assets/project/mods/dingyi.lua").readText()
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 0, source)))
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 1),
                listOf(TextDocumentContentChangeEvent(source + "\ntable.\n"))))
            val labels = documents.completion(CompletionParams(TextDocumentIdentifier(uri),
                Position(source.count { it == '\n' } + 1, 6))).get(20, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue(labels.containsAll(listOf("addObserver", "insert")), labels.toString())
        } finally {
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cold_live_java_completion_resolves_before_the_full_workspace_index() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val uri = "file:///workspace/java-live.lua"
        val documents = LuaTextDocumentService(service)
        try {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 0,
                "import 'java.util.ArrayList'\nlocal list = ArrayList()\nlist:")))
            val params = CompletionParams(TextDocumentIdentifier(uri), Position(2, 5))
            val labels = documents.completion(params).get(15, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue("add" in labels && "clear" in labels, "cold Java members missing: $labels")
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 1),
                listOf(TextDocumentContentChangeEvent("import 'java.lang.StringBuilder'\nlocal builder = StringBuilder()\n.append('hello')\n.\n"))))
            val updated = documents.completion(CompletionParams(TextDocumentIdentifier(uri), Position(3, 1)))
                .get(15, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue("append" in updated && "setLength" in updated, "edited Java chain lost its receiver: $updated")
        } finally {
            service.close()
        }
    }

    @Test
    fun completion_request_after_edit_returns_current_members_without_retrying_stale_results() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val uri = "file:///workspace/current-completion.lua"
        val documents = LuaTextDocumentService(service)
        val params = CompletionParams(TextDocumentIdentifier(uri), Position(1, 6))
        try {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1,
                "function table.oldMember() end\ntable.")))
            assertTrue(documents.completion(params).get(15, TimeUnit.SECONDS).right.items.any { it.label == "oldMember" })
            documents.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2),
                listOf(TextDocumentContentChangeEvent("function table.currentMember() end\ntable."))))
            val labels = documents.completion(params).get(15, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue("currentMember" in labels)
            assertFalse("oldMember" in labels, "one request must wait for its current document revision")
        } finally {
            service.close()
        }
    }

    @Test
    fun closing_connection_cancels_workspace_analysis_without_publishing_completion() {
        val root = Files.createTempDirectory("deferred-close")
        val parsing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        try {
            Files.writeString(root.resolve("main.lua"), "print('hello')")
            service.onBuildProgress = { progress ->
                if (progress.phase == AnalysisProgress.Phase.PARSING) {
                    parsing.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                if (progress.phase == AnalysisProgress.Phase.COMPLETE) completed.set(true)
            }
            service.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "workspace"))
            })
            assertTrue(parsing.await(10, TimeUnit.SECONDS))
            service.close()
            release.countDown()
            assertFalse(service.flushBackgroundRebuild(10_000))
            assertFalse(completed.get(), "a closed connection must not finish or publish a workspace index")
        } finally {
            release.countDown()
            service.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cold_query_snapshot_is_followed_by_real_semantic_diagnostics() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val uri = "file:///workspace/pending-diagnostics.lua"
        val reported = CountDownLatch(1)
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { report ->
            if (report.uri == uri && report.diagnostics.any { it.message.contains("unused", ignoreCase = true) }) {
                reported.countDown()
            }
        })
        textDocuments.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1,
            "local unusedValue = 1\nprint('hello')")))
        await { service.documentSymbols("workspace/pending-diagnostics.lua").any { it.name == "unusedValue" } }
        assertTrue(reported.await(15, TimeUnit.SECONDS), "query-first analysis must still publish checker warnings")
    }

    @Test
    fun finishing_workspace_index_republishes_diagnostics_with_cross_file_queries_ready() {
        val root = Files.createTempDirectory("deferred-workspace")
        try {
            Files.writeString(root.resolve("dep.lua"), "return { publishedField = 1 }")
            val service = LuaLanguageService(deferDocumentAnalysis = true)
            val uri = root.resolve("main.lua").toUri().toString()
            val published = CountDownLatch(1)
            val textDocuments = LuaTextDocumentService(service, publishDiagnostics = { report ->
                if (report.uri == uri && service.completion(uri, 1, 4).items.any { it.label == "publishedField" }) {
                    published.countDown()
                }
            })
            service.initialize(InitializeParams().apply {
                workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "workspace"))
            })
            textDocuments.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1,
                "local dep = require('dep')\ndep.")))
            assertTrue(published.await(15, TimeUnit.SECONDS), "workspace readiness must push fresh diagnostics")
            textDocuments.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cold_live_buffer_completion_tracks_edits_and_close_without_full_index() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        val uri = "file:///workspace/live.lua"
        val textDocuments = LuaTextDocumentService(service)
        textDocuments.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1,
            "function table.first() end\ntable.")))
        await { service.completion(uri, 1, 6).items.map { it.label }.containsAll(listOf("first", "insert")) }
        textDocuments.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2),
            listOf(TextDocumentContentChangeEvent("function table.second() end\ntable."))))
        await {
            val labels = service.completion(uri, 1, 6).items.map { it.label }
            "second" in labels && "first" !in labels && "insert" in labels
        }
        textDocuments.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
        await { service.completion(uri, 1, 6).items.isEmpty() }
    }

    @Test
    fun deferred_warm_edit_publishes_parse_errors_through_text_document_service() {
        val service = LuaLanguageService(deferDocumentAnalysis = true)
        service.initialize(InitializeParams())
        assertTrue(service.awaitWorkspaceReady())
        val uri = "file:///workspace/edited.lua"
        val errors = CountDownLatch(1)
        val textDocuments = LuaTextDocumentService(service, publishDiagnostics = {
            if (it.uri == uri && it.diagnostics.isNotEmpty()) errors.countDown()
        })
        textDocuments.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, "local value = 1")))
        textDocuments.didChange(DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, 2),
            listOf(TextDocumentContentChangeEvent("local ="))))
        assertTrue(errors.await(15, TimeUnit.SECONDS), "deferred diagnostics must reach the client")
        textDocuments.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        assertTrue(predicate(), "deferred analysis did not settle")
    }
}
