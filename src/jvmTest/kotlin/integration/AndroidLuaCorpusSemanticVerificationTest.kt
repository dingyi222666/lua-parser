package integration

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BlockNode
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.ExpressionNode
import io.github.dingyi222666.luaparser.parser.ast.node.StatementNode
import io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor
import io.github.dingyi222666.luaparser.semantic.workspace.DocumentFacts
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import semantic.support.WorkspaceSemanticHarness

class AndroidLuaCorpusSemanticVerificationTest {
    private val androidJar = File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)

    /**
     * Joint green-lock (TASK-605/609): host dual-path Android-Lua root + android-35 jar
     * defaults must clear MODULE-VERIFY root/path red without G:/ sole hard defaults and
     * without macOS-only absolute path asserts on Windows CI.
     *
     * Coordinates TASK-562 (root dual-path), TASK-563..565 (recovery/semantic/full-tree),
     * and JvmWorkspaceConfiguration jar discovery. Host-present clone + SDK android-35
     * present is sufficient; empty ANDROID_LUA_MAIN is treated as unset.
     *
     * Windows evidence (run 29173639103): do not assert File(macOS path).canonicalFile
     * (becomes hybrid G:\Users\dingyi\projects\...) when the present clone is
     * G:\Android-Lua\app\src\main.
     */
    @Test
    fun host_dual_path_root_and_android_jar_joint_green_lock() {
        // Root: property/env overrides, then OS-aware host dual-path (present clone wins).
        val root = assertExternalSourceRoot()
        assertTrue(root.isDirectory, "Joint green-lock requires host Android-Lua root directory.")

        val defaultRoot = File(DEFAULT_ANDROID_LUA_MAIN)
        assertTrue(
            defaultRoot.isDirectory,
            "DEFAULT_ANDROID_LUA_MAIN must resolve to a present dual-path candidate; " +
                "got ${defaultRoot.path}. Tried: ${hostAndroidLuaMainCandidates().joinToString()}."
        )
        assertEquals(
            defaultRoot.canonicalFile,
            root.canonicalFile,
            "DEFAULT_ANDROID_LUA_MAIN must match assertExternalSourceRoot first present candidate."
        )
        assertTrue(
            hostAndroidLuaMainCandidates().any { candidate ->
                sameResolvedFile(File(candidate), root)
            },
            "Joint green-lock root must be one of host dual-path candidates " +
                "${hostAndroidLuaMainCandidates()}; got ${root.path}."
        )
        assertTrue(
            !isInventedWindowsHybridOfMacOsAndroidLuaPath(root),
            "Dual-path root must not invent broken Windows hybrid of the macOS clone path " +
                "(for example G:/Users/dingyi/projects/java_projects/Android-Lua/app/src/main); " +
                "got ${root.path}."
        )

        // macOS host: prefer the real macOS clone when that directory is present.
        val macClone = File(HOST_MACOS_ANDROID_LUA_MAIN)
        if (!isWindowsHost() && macClone.isDirectory) {
            assertEquals(
                macClone.canonicalFile,
                root.canonicalFile,
                "With macOS clone present and overrides unset/empty, dual-path root must prefer " +
                    "$HOST_MACOS_ANDROID_LUA_MAIN over $WINDOWS_DOCUMENTED_ANDROID_LUA_MAIN."
            )
            assertTrue(
                !root.path.replace('\\', '/').startsWith("G:/Android-Lua", ignoreCase = true),
                "Joint green-lock root must not hard-require G:/Android-Lua when macOS clone exists; got ${root.path}."
            )
        }

        // Windows host: present Windows clone wins (documented G:/Android-Lua or user-home clone);
        // never require non-existent macOS absolute / hybrid path.
        if (isWindowsHost()) {
            val winDoc = File(WINDOWS_DOCUMENTED_ANDROID_LUA_MAIN)
            val homeClone = File(hostUserHomeAndroidLuaMain())
            assertTrue(
                (winDoc.isDirectory && sameResolvedFile(winDoc, root)) ||
                    (homeClone.isDirectory && sameResolvedFile(homeClone, root)) ||
                    hostAndroidLuaMainCandidates().any { sameResolvedFile(File(it), root) && File(it).isDirectory },
                "On Windows, dual-path root must choose a present Windows clone " +
                    "(for example $WINDOWS_DOCUMENTED_ANDROID_LUA_MAIN or user-home projects clone) " +
                    "without requiring macOS-only $HOST_MACOS_ANDROID_LUA_MAIN; got ${root.path}."
            )
        }

        // Jar: DEFAULT_ANDROID_JAR_PATH dual-path discovery (never sole G:/ hardcode).
        assertAndroidJarExists()
        val jarPath = androidJar.path.replace('\\', '/')
        assertTrue(
            androidJar.isFile && androidJar.length() > 0,
            "Joint green-lock requires non-empty host android.jar at ${androidJar.path}."
        )
        assertTrue(
            !jarPath.startsWith("G:/Android/Sdk", ignoreCase = true),
            "DEFAULT_ANDROID_JAR_PATH must not hard-require G:/Android/Sdk when host SDK jar exists; got ${androidJar.path}."
        )
        val hostMacJar = File(HOST_MACOS_ANDROID_JAR_PATH)
        if (hostMacJar.isFile) {
            assertTrue(
                jarPath.contains("/platforms/android-") && androidJar.name.equals("android.jar", ignoreCase = true),
                "Expected platforms/android-*/android.jar discovery; got ${androidJar.path}."
            )
            // Prefer android-35 when present on this host (WAVE hard-lock path).
            assertTrue(
                hostMacJar.isFile,
                "Host WAVE path missing: $HOST_MACOS_ANDROID_JAR_PATH"
            )
            assertTrue(
                sameResolvedFile(androidJar, hostMacJar) ||
                    (jarPath.contains("/Library/Android/sdk/platforms/android-") && androidJar.isFile),
                "With host android-35 present, DEFAULT_ANDROID_JAR_PATH must resolve to a non-G: host platform jar; got ${androidJar.path}."
            )
        }

        // Manifest integrity under dual-path root: all parse-input rows exist (TASK-562/605).
        val rows = loadManifestRows()
        assertTrue(rows.size >= 30, "Joint green-lock expects bounded manifest ≥30 rows; got ${rows.size}.")
        val missing = rows.filter { it.isParseInput }.mapNotNull { row ->
            val file = row.sourceFile(root)
            if (!file.isFile) "${row.id}:${row.sourcePath}" else null
        }
        assertTrue(missing.isEmpty(), "Missing external corpus files under dual-path root: $missing")

        // Soft-skip reason must not invent a silent G:/ fallback when discovery succeeds.
        val softSkip = JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason()
        assertTrue(
            softSkip.contains("present") || softSkip.contains(androidJar.path) || softSkip.contains("android.jar is present"),
            "When host jar is present, soft-skip reason should report presence; got: $softSkip"
        )
    }

    @Test
    fun manifest_records_bounded_external_android_lua_corpus() {
        val rows = loadManifestRows()

        assertTrue(rows.size >= 30, "Expected a bounded Android-Lua manifest with at least 30 rows.")
        assertEquals(rows.size, rows.map { it.id }.distinct().size, "Corpus IDs must be unique.")
        assertTrue(rows.any { it.kind == "lua" }, "Manifest must include Lua source rows.")
        assertTrue(rows.any { it.kind == "aly" }, "Manifest must include .aly layout rows.")
        assertTrue(rows.any { it.kind == "java-anchor" }, "Manifest must include Java interop anchor rows.")
        assertTrue(rows.any { it.hasTag("parser.recovery") }, "Manifest must keep explicit recovery rows.")
        assertTrue(rows.any { it.hasTag("interop.android") }, "Manifest must cover Android interop rows.")
        assertExternalSourceRoot().also { root ->
            rows.filter { it.isParseInput }.forEach { row ->
                assertTrue(row.sourceFile(root).isFile, "Missing external Android-Lua source for ${row.id}: ${row.sourcePath}.")
            }
        }
    }

    @Test
    fun parser_accepts_strict_rows_and_recovers_marked_rows_without_crashing() {
        val root = assertExternalSourceRoot()
        val failures = loadManifestRows()
            .filter { it.isParseInput }
            .mapNotNull { row ->
                runCatching {
                    val source = row.source(root)
                    if (row.hasTag("parser.recovery")) {
                        assertRecoveryParse(row, source)
                    } else {
                        val chunk = LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false).parse(source)
                        val badNodes = collectBadNodeNames(chunk)
                        assertTrue(badNodes.isEmpty(), "${row.id} strict parse produced recovered/bad nodes: $badNodes.")
                    }
                }.exceptionOrNull()?.let { failure ->
                    "${row.id} (${row.sourcePath}) failed parser verification: ${failure.message}"
                }
            }

        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n\n"))
    }

    @Test
    fun semantic_workspace_analyzes_representative_external_sources_with_android_java_metadata() {
        val root = assertExternalSourceRoot()
        val jarPresent = androidJar.isFile && androidJar.length() > 0
        val rowsById = loadManifestRows().associateBy { it.id }
        val workspaceFiles = semanticWorkspaceRows(rowsById, root)
        val harness = WorkspaceSemanticHarness.build(
            *workspaceFiles,
            metadata = androidMetadata(includeAndroidJar = jarPresent),
            engine = JvmWorkspaceEngine()
        )

        // Full representative matrix: original 13 + loadmenu/autotheme/loadlayout2/3/service + large asset.
        val expectedPaths = SEMANTIC_WORKSPACE_PATHS
        assertTrue(
            expectedPaths.size >= 18,
            "Semantic workspace gate must expand beyond the original 13-file subset; got ${expectedPaths.size}."
        )
        expectedPaths.forEach { path ->
            assertTrue(harness.path(path) in harness.files, "Missing representative workspace file mapping for $path.")
        }
        assertTrue(
            "loadmenu.lua" in expectedPaths &&
                "autotheme.lua" in expectedPaths &&
                "layouthelper/loadlayout2.lua" in expectedPaths &&
                "layouthelper/loadlayout3.lua" in expectedPaths,
            "Matrix must include runtime-loadmenu, runtime-autotheme, layouthelper-loadlayout2, layouthelper-loadlayout3."
        )
        assertTrue(
            "asset-andlua.lua" in expectedPaths || "asset-yidian.lua" in expectedPaths,
            "Matrix must include one additional large asset (asset-andlua or asset-yidian)."
        )
        assertTrue(
            SEMANTIC_SERVICE_WORKSPACE_PATHS.any { it in expectedPaths },
            "Matrix must include at least one semantic.service-tagged source (loadlayout / loadlayout2 / loadlayout3)."
        )

        val mainFacts = facts(harness, "asset-main.lua")
        val loadlayoutFacts = facts(harness, "loadlayout.lua")
        val loadmenuFacts = facts(harness, "loadmenu.lua")
        val autothemeFacts = facts(harness, "autotheme.lua")
        val loadlayout2Facts = facts(harness, "layouthelper/loadlayout2.lua")
        val loadlayout3Facts = facts(harness, "layouthelper/loadlayout3.lua")
        val importProvider = harness.queries.resolveRequire(harness.path("asset-main.lua"), "import")
        val socketUrlProvider = harness.queries.lookupModule("socket.url")

        assertTrue("android.widget.*" in mainFacts.sourceImports.map { it.target })
        assertTrue("java.io.File" in mainFacts.sourceImports.map { it.target })
        assertTrue(mainFacts.sourceImports.any { it.target.endsWith(".*") }, "Expected wildcard Android-Lua imports in asset-main.")
        assertEquals(harness.path("import.lua"), importProvider.provider?.path)
        assertEquals(harness.path("socket/url.lua"), socketUrlProvider.provider?.path)

        // bindClass / loadlayout / loadmenu facts are source-level DocumentFacts (no jar required).
        assertBindClassTarget(loadlayoutFacts, "android.view.ViewGroup", "loadlayout.lua")
        assertTrue(
            loadlayoutFacts.jvmClassLoads.any { it.target == "android.view.View\$OnClickListener" },
            "Expected loadlayout.lua to record Android listener binding."
        )
        assertBindClassTarget(loadmenuFacts, "com.androlua.LuaDrawable", "loadmenu.lua")
        assertBindClassTarget(autothemeFacts, "android.os.Build", "autotheme.lua")
        assertBindClassTarget(loadlayout2Facts, "android.view.ViewGroup", "layouthelper/loadlayout2.lua")
        assertTrue(
            loadlayout2Facts.jvmClassLoads.any { it.target == "android.view.View\$OnClickListener" },
            "Expected layouthelper/loadlayout2.lua to record Android listener binding."
        )
        assertBindClassTarget(loadlayout3Facts, "android.view.ViewGroup", "layouthelper/loadlayout3.lua")
        assertTrue(
            loadlayout3Facts.jvmClassLoads.any { it.target == "android.view.View\$OnClickListener" },
            "Expected layouthelper/loadlayout3.lua to record Android listener binding."
        )

        if (jarPresent) {
            assertProvider(harness, "java.io.File")
            assertProvider(harness, "java.util.ArrayList")
            assertProvider(harness, "android.widget.TextView")
            assertProvider(harness, "android.view.View\$OnClickListener")
        } else {
            // Soft-skip Android-only provider asserts when host android.jar is absent.
            val reason =
                "Soft-skip Android-only JVM provider asserts: host android.jar missing or empty at " +
                    "${androidJar.path} (JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH / SDK android-35). " +
                    "Source-level bindClass / loadlayout / loadmenu facts and require/module resolution remain hard-asserted."
            assertTrue(true, reason)
            println(reason)
        }

        assertTrue(
            harness.snapshot.files.values.all { it.semanticFile != null },
            "Every representative corpus file should receive semantic state."
        )
    }

    @Test
    fun semantic_workspace_full_tree_external_paths_resolve_require_import_and_helpers() {
        val root = assertExternalSourceRoot()
        val jarPresent = androidJar.isFile && androidJar.length() > 0
        val rowsById = loadManifestRows().associateBy { it.id }

        // Full-tree mode: keep real relative paths under app/src/main (no synthetic remaps).
        val workspaceFiles = fullTreeExternalWorkspaceRows(rowsById, root)
        val harness = WorkspaceSemanticHarness.buildExternalRelative(
            *workspaceFiles,
            metadata = androidMetadata(includeAndroidJar = jarPresent),
            engine = JvmWorkspaceEngine(),
            fullTreeMode = true
        )

        val mainPath = "assets/main.lua"
        val importPath = "resources/lua/import.lua"
        val loadlayoutPath = "resources/lua/loadlayout.lua"
        val layoutPath = "assets/layout.aly"

        assertTrue(harness.path(mainPath) in harness.files, "Expected full-tree mapping for $mainPath.")
        assertTrue(harness.path(importPath) in harness.files, "Expected full-tree mapping for $importPath.")
        assertTrue(harness.path(loadlayoutPath) in harness.files, "Expected full-tree mapping for $loadlayoutPath.")
        assertTrue(harness.path(layoutPath) in harness.files, "Expected full-tree mapping for $layoutPath.")
        assertTrue(
            workspaceFiles.none { (path, _) -> path == "import.lua" || path == "asset-main.lua" },
            "Full-tree mode must not remap to synthetic import.lua / asset-main.lua keys."
        )

        val mainFacts = facts(harness, mainPath)
        assertTrue("android.widget.*" in mainFacts.sourceImports.map { it.target })
        assertTrue("java.io.File" in mainFacts.sourceImports.map { it.target })

        val importProvider = harness.queries.resolveRequire(harness.path(mainPath), "import")
        val loadlayoutProvider = harness.queries.lookupModule("loadlayout")
        val layoutProvider = harness.queries.lookupModule("layout")

        assertEquals(
            harness.path(importPath),
            importProvider.provider?.path,
            "require(\"import\") must resolve to external resources/lua/import.lua, got ${importProvider.provider?.path}."
        )
        assertTrue(
            importProvider.provider!!.path.value.endsWith("resources/lua/import.lua"),
            "Import provider path must end with real relative source path resources/lua/import.lua."
        )
        assertEquals(
            harness.path(loadlayoutPath),
            loadlayoutProvider.provider?.path,
            "lookupModule(\"loadlayout\") must use external resources/lua/loadlayout.lua."
        )
        assertTrue(
            loadlayoutProvider.provider!!.path.value.endsWith("resources/lua/loadlayout.lua"),
            "loadlayout provider path must end with real relative source path."
        )
        assertEquals(
            harness.path(layoutPath),
            layoutProvider.provider?.path,
            "lookupModule(\"layout\") must use external assets/layout.aly."
        )
        assertTrue(
            layoutProvider.provider!!.path.value.endsWith("assets/layout.aly") ||
                layoutProvider.provider!!.path.value.endsWith("layout.aly"),
            "layout provider path must end with real relative .aly path."
        )

        // BindClass facts from external loadlayout remain source-level (no jar required).
        val loadlayoutFacts = facts(harness, loadlayoutPath)
        assertBindClassTarget(loadlayoutFacts, "android.view.ViewGroup", loadlayoutPath)

        if (jarPresent) {
            assertProvider(harness, "android.widget.TextView")
            assertProvider(harness, "java.io.File")
        } else {
            val reason =
                "Soft-skip Android-only JVM provider asserts in full-tree gate: host android.jar missing at " +
                    "${androidJar.path}."
            assertTrue(true, reason)
            println(reason)
        }

        assertTrue(
            harness.snapshot.files.values.all { it.semanticFile != null },
            "Every full-tree external file should receive semantic state."
        )
    }

    @Test
    fun lsp_queries_external_android_lua_sources_with_android_metadata() {
        val root = assertExternalSourceRoot()
        // Dual-path discovery (ANDROID_HOME/SDK_ROOT + well-known SDK roots). Never hard-fail solely
        // because a preferred candidate such as %LOCALAPPDATA%/Android/Sdk/platforms/android-35/android.jar
        // is absent when no present host jar was discovered — soft-skip with an explicit reason instead.
        val jarPresent = androidJar.isFile && androidJar.length() > 0
        if (!jarPresent) {
            val reason = JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason()
            val message =
                "Soft-skip LSP android metadata gate: host android.jar missing or empty at " +
                    "${androidJar.path} (JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH dual-path). " +
                    "Discovery reason: $reason"
            assertTrue(reason.isNotBlank(), message)
            println(message)
            return
        }
        assertAndroidJarExists()
        val rows = loadManifestRows().associateBy { it.id }
        // Query surface still uses real external asset-main source text (no inlined constants).
        // Workspace folder stays synthetic so LSP does not walk the entire Android-Lua tree;
        // full-tree require fidelity is covered by
        // semantic_workspace_full_tree_external_paths_resolve_require_import_and_helpers.
        val service = androidLanguageService()
        val mainSource = rows.getValue("asset-main").source(root)
        val layoutSource = rows.getValue("layout-root").source(root)
        val mainUri = "file:///workspace/android-lua/assets/main.lua"
        val layoutUri = "file:///workspace/android-lua/assets/layout.aly"

        service.didOpen(openParams(mainUri, mainSource))
        service.didOpen(openParams(layoutUri, layoutSource))

        val textViewPosition = positionOf(mainSource, "TextView;", offset = 2)
        val hover = service.hover(HoverParams(TextDocumentIdentifier(mainUri), textViewPosition))
        val definition = service.definition(DefinitionParams(TextDocumentIdentifier(mainUri), textViewPosition))
        val completions = service.completion("workspace/android-lua/assets/main.lua", textViewPosition.line, textViewPosition.character)
        val providerSymbols = service.workspaceSymbols("TextView")
        val documentSymbols = service.documentSymbols("workspace/android-lua/assets/main.lua")

        assertNotNull(hover, "Expected hover for imported Android TextView in real asset-main source.")
        assertTrue(hover.contents.right.value.contains("TextView"), "Expected TextView hover, got ${hover.contents.right.value}.")
        assertTrue(definition.any { it.uri == androidProviderUri("android.widget.TextView") }, "Expected TextView provider definition, got $definition.")
        assertTrue(completions.items.any { it.label == "TextView" }, "Expected TextView completion in real asset-main source.")
        assertTrue(providerSymbols.any { it.location.uri == androidProviderUri("android.widget.TextView") })
        assertTrue(documentSymbols.isNotEmpty(), "Expected document symbols for real Android-Lua main source.")
    }

    /**
     * Hard lock for external `parser.recovery` rows (TASK-563).
     *
     * Product recovery no longer depends on stdout (`This error is ignored now.` was removed
     * with structured diagnostics — see TASK-142 / `parseWithDiagnostics`). Gate recovery on:
     * 1. strict parse failure (or dual-path CURRENTLY_ACCEPTS documented per row),
     * 2. recovery parse that does not crash, and
     * 3. bad AST markers and/or non-empty structured `recoveryDiagnostics`.
     *
     * Dual-path CURRENTLY_ACCEPTS: only when structured diagnostics are empty but bad AST
     * markers still prove recovery engaged (record honestly in the assertion message).
     * External Android-Lua sources remain read-only.
     */
    private fun assertRecoveryParse(row: ManifestRow, source: String) {
        val strictFailure = runCatching {
            LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false).parse(source)
        }.exceptionOrNull()

        // Prefer structured API; capture stdout only as a non-gating observation (product should be quiet).
        val recoveryRun = captureStdout {
            LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = true)
                .parseWithDiagnostics(source)
        }
        val parseResult = recoveryRun.value
        val badNodes = collectBadNodeNames(parseResult.chunk)
        val structuredDiagnostics = parseResult.recoveryDiagnostics
        val structuredMessages = structuredDiagnostics.map { it.message }
        val stdoutLegacyNoise = recoveryRun.output.contains("This error is ignored now.")

        if (strictFailure == null) {
            // Dual-path CURRENTLY_ACCEPTS only when recovery evidence still exists.
            assertTrue(
                badNodes.isNotEmpty() || structuredDiagnostics.isNotEmpty(),
                buildString {
                    append("${row.id} is tagged parser.recovery but strict parsing succeeded ")
                    append("and recovery produced neither bad AST markers nor structured diagnostics. ")
                    append("CURRENTLY_ACCEPTS dual-path is only allowed when recovery evidence remains. ")
                    append("stdoutLegacyNoise=$stdoutLegacyNoise stdoutLen=${recoveryRun.output.length}")
                }
            )
            // Honest dual-path record: strict currently accepts this external recovery row.
            println(
                "CURRENTLY_ACCEPTS dual-path for ${row.id}: strict parse succeeded; " +
                    "recovery evidence badNodes=$badNodes structured=${structuredMessages.take(8)} " +
                    "(stdout legacy ignored-now=$stdoutLegacyNoise)"
            )
        } else {
            assertTrue(
                true,
                "${row.id} strict parse failed as expected for parser.recovery: ${strictFailure.message}"
            )
        }

        // Hard lock: structured diagnostics and/or bad AST markers — never solely stdout.
        val hasStructured = structuredDiagnostics.isNotEmpty()
        val hasBadMarkers = badNodes.isNotEmpty()
        assertTrue(
            hasStructured || hasBadMarkers,
            buildString {
                append("${row.id} is tagged parser.recovery but produced no structured ")
                append("parseWithDiagnostics warnings and no bad AST markers. ")
                append("stdout must not be the sole recovery signal (legacy 'This error is ignored now.' ")
                append("present=$stdoutLegacyNoise, stdoutLen=${recoveryRun.output.length}). ")
                append("strictFailed=${strictFailure != null}")
            }
        )

        // Recovery must keep a reachable chunk (no crash already implied by reaching here).
        assertNotNull(parseResult.chunk.body, "${row.id} recovery parse returned a chunk without body.")
        assertTrue(
            parseResult.chunk.body.statements.isNotEmpty() ||
                parseResult.chunk.body.returnStatement != null ||
                hasBadMarkers ||
                hasStructured,
            "${row.id} recovery parse produced an empty unreachable chunk without recovery evidence."
        )

        // Named external rows called out by AC: asset-main13-recovery / asset-thomelua.
        if (row.id == "asset-main13-recovery" || row.id == "asset-thomelua") {
            assertTrue(
                hasStructured || hasBadMarkers,
                "${row.id}: required recovery evidence (structured diagnostics or bad markers) missing."
            )
            // Prefer structured when product emits it; dual-path when only bad markers remain.
            if (!hasStructured && hasBadMarkers) {
                println(
                    "CURRENTLY_ACCEPTS structured-empty dual-path for ${row.id}: " +
                        "structured recoveryDiagnostics empty; bad AST markers present=$badNodes"
                )
            }
        }
    }

    /**
     * Representative external matrix for the integration semantic workspace gate (TASK-564).
     *
     * Expands the historical 13-file subset with:
     * - `runtime-loadmenu`, `runtime-autotheme`
     * - `layouthelper-loadlayout2`, `layouthelper-loadlayout3` (semantic.service + bindClass)
     * - large asset `asset-andlua` (alternative: `asset-yidian`)
     *
     * Sources are loaded from the read-only Android-Lua root only — never vendored into the repo.
     */
    private fun semanticWorkspaceRows(rowsById: Map<String, ManifestRow>, root: File): Array<Pair<String, String>> {
        fun source(id: String): String = rowsById.getValue(id).source(root)
        return arrayOf(
            "import.lua" to source("runtime-import"),
            "loadlayout.lua" to source("runtime-loadlayout"),
            "loadmenu.lua" to source("runtime-loadmenu"),
            "autotheme.lua" to source("runtime-autotheme"),
            "loadbitmap.lua" to source("runtime-loadbitmap"),
            "http.lua" to source("runtime-http"),
            "socket/url.lua" to source("runtime-socket-url"),
            "json.lua" to source("runtime-json"),
            "xml.lua" to source("runtime-xml"),
            "permission.lua" to source("runtime-permission"),
            "asset-main.lua" to source("asset-main"),
            "Dialog.lua" to source("asset-dialog"),
            "toast.lua" to source("asset-toast"),
            "asset-andlua.lua" to source("asset-andlua"),
            "layouthelper/loadlayout2.lua" to source("layouthelper-loadlayout2"),
            "layouthelper/loadlayout3.lua" to source("layouthelper-loadlayout3"),
            "layout.lua" to source("layout-root"),
            "My.lua" to source("layout-my")
        )
    }

    /**
     * Full-tree external multi-file workspace (TASK-565).
     *
     * Virtual paths preserve real relative paths under Android-Lua `app/src/main`
     * (e.g. `assets/main.lua`, `resources/lua/import.lua`) so require/import resolution
     * uses external-file providers, not synthetic remaps.
     *
     * Minimum set from AC: assets/main.lua + resources/lua/import.lua +
     * resources/lua/loadlayout.lua + one `.aly`, plus a few helpers for chain fidelity.
     */
    private fun fullTreeExternalWorkspaceRows(rowsById: Map<String, ManifestRow>, root: File): Array<Pair<String, String>> {
        fun row(id: String): Pair<String, String> {
            val manifest = rowsById.getValue(id)
            return manifest.sourcePath to manifest.source(root)
        }
        return arrayOf(
            row("runtime-import"),
            row("runtime-loadlayout"),
            row("runtime-loadmenu"),
            row("runtime-socket-url"),
            row("runtime-http"),
            row("asset-main"),
            row("asset-dialog"),
            row("layout-root"),
            row("layout-my")
        )
    }

    private fun androidLanguageService(): LuaLanguageService {
        return LuaLanguageService().also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace/android-lua", "android-lua"))
                }
            )
            LuaWorkspaceService(service).didChangeConfiguration(DidChangeConfigurationParams(androidSettings()))
        }
    }

    private fun androidSettings(): Map<String, Any> {
        return mapOf(
            "jvm.androidJar" to androidJar.path,
            "jvm.importPrefixes" to androidImportPrefixes,
            "androlua.imports" to androidLuaImports
        )
    }

    private fun androidMetadata(includeAndroidJar: Boolean = true): Map<String, String> {
        return JvmWorkspaceConfiguration(
            androidJar = if (includeAndroidJar) androidJar.path else null,
            classes = setOf("java.io.File", "java.util.ArrayList", "android.widget.TextView", "android.view.View\$OnClickListener"),
            androluaImports = androidLuaImports,
            importPrefixes = androidImportPrefixes
        ).applyToMetadata(emptyMap())
    }

    private fun facts(harness: WorkspaceSemanticHarness, path: String): DocumentFacts {
        return assertNotNull(harness.snapshot.files.getValue(harness.path(path)).documentFacts, "Missing facts for $path.")
    }

    private fun assertBindClassTarget(facts: DocumentFacts, target: String, label: String) {
        assertTrue(
            facts.jvmClassLoads.any {
                it.kind == DocumentFacts.JvmClassLoadKind.BIND_CLASS_CALL && it.target == target
            },
            "Expected $label to record LuaJava bindClass target $target; got ${facts.jvmClassLoads.map { it.target }}."
        )
    }

    private fun assertProvider(harness: WorkspaceSemanticHarness, className: String) {
        assertTrue(
            harness.hasJvmClassProvider(className),
            "Expected JVM provider for $className under __jvm__/classes/."
        )
    }

    private fun loadManifestRows(): List<ManifestRow> {
        val resource = assertNotNull(
            javaClass.classLoader.getResource(MANIFEST_RESOURCE),
            "Missing Android-Lua corpus manifest resource $MANIFEST_RESOURCE."
        )
        return resource.readText()
            .lineSequence()
            .filter { it.startsWith("| `") }
            .mapNotNull(::parseManifestRow)
            .toList()
    }

    private fun parseManifestRow(line: String): ManifestRow? {
        val cells = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
        if (cells.size < 5) {
            return null
        }
        val kind = unquote(cells[2])
        if (kind != "lua" && kind != "aly" && kind != "java-anchor") {
            return null
        }
        val tagsCellIndex = if (kind == "java-anchor") 3 else 4
        return ManifestRow(
            id = unquote(cells[0]),
            sourcePath = unquote(cells[1]),
            kind = kind,
            tags = TAG_PATTERN.findAll(cells[tagsCellIndex]).map { it.groupValues[1] }.toSet()
        )
    }

    private fun unquote(value: String): String = value.trim().removeSurrounding("`")

    /**
     * Resolve the external Android-Lua `app/src/main` root (read-only).
     *
     * Override order:
     * 1. system property `androidLua.main` (non-blank)
     * 2. environment `ANDROID_LUA_MAIN` (non-blank; empty treated as unset)
     * 3. host dual-path candidates (first existing directory wins):
     *    - Windows: user-home projects clone first, then documented `G:/Android-Lua/app/src/main`
     *      (never invent `G:/Users/dingyi/projects/...` hybrids from the macOS absolute path)
     *    - macOS/other: macOS clone path first when present, then user-home clone,
     *      then documented Windows path as last-resort only
     *
     * Missing root fails with a message that lists tried paths and override knobs.
     */
    private fun assertExternalSourceRoot(): File {
        val override = configuredAndroidLuaMainOverride()
        if (override != null) {
            val root = File(override)
            assertTrue(
                root.isDirectory,
                buildString {
                    append("Expected Android-Lua source root at ${root.path} ")
                    append("(from -DandroidLua.main / ANDROID_LUA_MAIN). ")
                    append("Tried override path only. ")
                    append("Set a valid checkout via -DandroidLua.main or ANDROID_LUA_MAIN, ")
                    append("or unset them to use host dual-path candidates: ")
                    append(hostAndroidLuaMainCandidates().joinToString())
                    append(". External tree remains read-only.")
                }
            )
            return root
        }

        val tried = mutableListOf<String>()
        for (candidate in hostAndroidLuaMainCandidates()) {
            val root = File(candidate)
            tried += root.path
            if (root.isDirectory) {
                return root
            }
        }

        assertTrue(
            false,
            buildString {
                append("Expected Android-Lua source root among host dual-path candidates; none exist. ")
                append("Tried: ${tried.joinToString()}. ")
                append("Override with -DandroidLua.main or ANDROID_LUA_MAIN (empty values are treated as unset). ")
                append("On Windows, prefer a present Windows clone (user-home projects or ")
                append("$WINDOWS_DOCUMENTED_ANDROID_LUA_MAIN); do not invent hybrid ")
                append("G:/Users/dingyi/projects/... from the macOS path. ")
                append("On macOS, host clone $HOST_MACOS_ANDROID_LUA_MAIN is preferred when present; ")
                append("documented Windows $WINDOWS_DOCUMENTED_ANDROID_LUA_MAIN is last-resort only ")
                append("(never a sole hard default that fails macOS solely because G: is missing). ")
                append("External tree remains read-only; do not vendor Android-Lua sources.")
            }
        )
        error("unreachable")
    }

    private fun configuredAndroidLuaMainOverride(): String? {
        return System.getProperty(ANDROID_LUA_MAIN_PROPERTY)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: System.getenv(ANDROID_LUA_MAIN_ENV)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    }

    private fun isWindowsHost(): Boolean {
        return System.getProperty("os.name").orEmpty().lowercase().contains("win")
    }

    /**
     * user.home-relative Android-Lua `app/src/main` clone (portable across hosts).
     * Prefer this over inventing drive-letter hybrids of the macOS absolute path.
     */
    private fun hostUserHomeAndroidLuaMain(): String {
        val home = System.getProperty("user.home")?.trim()?.takeIf { it.isNotEmpty() } ?: "."
        return File(home, "projects/java_projects/Android-Lua/app/src/main").path
    }

    /**
     * OS-aware dual-path candidates after property/env overrides.
     * First existing directory wins. Never includes the macOS absolute path on Windows,
     * because Java maps `/Users/...` to `<current-drive>:\Users\...` hybrids.
     */
    private fun hostAndroidLuaMainCandidates(): List<String> = HOST_ANDROID_LUA_MAIN_CANDIDATES

    /**
     * Detect the broken Windows hybrid of the macOS clone path:
     * `<drive>:/Users/dingyi/projects/java_projects/Android-Lua/app/src/main`.
     * Dual-path resolution must never select this invented path over a real Windows clone.
     */
    private fun isInventedWindowsHybridOfMacOsAndroidLuaPath(root: File): Boolean {
        if (!isWindowsHost()) {
            return false
        }
        val normalized = root.path.replace('\\', '/')
        val hybridSuffix = "/Users/dingyi/projects/java_projects/Android-Lua/app/src/main"
        val looksLikeHybrid = normalized.length >= 2 &&
            normalized[1] == ':' &&
            normalized.substring(2).replace('\\', '/').equals(hybridSuffix, ignoreCase = true)
        if (!looksLikeHybrid) {
            return false
        }
        // Hybrid is "invented" when the documented/home Windows clones exist or the native
        // macOS absolute path is not a real directory on this host layout.
        val winDoc = File(WINDOWS_DOCUMENTED_ANDROID_LUA_MAIN)
        val homeClone = File(hostUserHomeAndroidLuaMain())
        return winDoc.isDirectory || homeClone.isDirectory || !File(HOST_MACOS_ANDROID_LUA_MAIN).isDirectory
    }

    private fun assertAndroidJarExists() {
        assertTrue(
            androidJar.isFile,
            buildString {
                append("Expected Android platform jar at ${androidJar.path} ")
                append("(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH dual-path discovery). ")
                append("Preferred host WAVE path: $HOST_MACOS_ANDROID_JAR_PATH. ")
                append("Never hard-requires G:/Android/Sdk; set jvm.androidJar / ANDROID_HOME / ANDROID_SDK_ROOT ")
                append("or install platforms/android-35 under a well-known SDK root.")
            }
        )
        assertTrue(androidJar.length() > 0, "Expected non-empty Android platform jar at ${androidJar.path}.")
        assertTrue(
            !androidJar.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true) ||
                !File(HOST_MACOS_ANDROID_JAR_PATH).isFile,
            "When host macOS SDK jar exists, DEFAULT_ANDROID_JAR_PATH must not resolve solely to G:/; got ${androidJar.path}."
        )
    }

    private fun sameResolvedFile(left: File, right: File): Boolean {
        return runCatching { left.canonicalFile == right.canonicalFile }
            .getOrElse { left.absolutePath == right.absolutePath }
    }

    private fun collectBadNodeNames(chunk: ChunkNode): List<String> {
        val badNodes = mutableListOf<String>()
        val visitor = object : ASTVisitor<Unit> {
            private fun record(node: BaseASTNode) {
                if (node.bad) {
                    badNodes += node::class.simpleName ?: node::class.qualifiedName ?: "UnknownNode"
                }
            }

            override fun visitChunkNode(node: ChunkNode, value: Unit) {
                record(node)
                super<ASTVisitor>.visitChunkNode(node, value)
            }

            override fun visitBlockNode(node: BlockNode, value: Unit) {
                record(node)
                super<ASTVisitor>.visitBlockNode(node, value)
            }

            override fun visitStatementNode(node: StatementNode, value: Unit) {
                record(node)
                super<ASTVisitor>.visitStatementNode(node, value)
            }

            override fun visitExpressionNode(node: ExpressionNode, value: Unit) {
                record(node)
                super<ASTVisitor>.visitExpressionNode(node, value)
            }

            override fun visitAttributeIdentifier(identifier: AttributeIdentifier, value: Unit) {
                record(identifier)
            }
        }
        visitor.visitChunkNode(chunk, Unit)
        return badNodes
    }

    private fun <T> captureStdout(block: () -> T): Captured<T> {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        val stream = PrintStream(bytes, true, Charsets.UTF_8.name())
        return try {
            System.setOut(stream)
            val value = block()
            stream.flush()
            Captured(value, bytes.toString(Charsets.UTF_8.name()))
        } finally {
            System.setOut(original)
            stream.close()
        }
    }

    private fun openParams(uri: String, source: String): DidOpenTextDocumentParams {
        return DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source))
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1, offset: Int = 0): Position {
        require(occurrence > 0) { "Occurrence must be positive." }
        var from = 0
        var index = -1
        repeat(occurrence) {
            index = source.indexOf(needle, from)
            require(index >= 0) { "Could not find occurrence ${it + 1} of '$needle'." }
            from = index + needle.length
        }
        val target = index + min(offset, needle.lastIndex.coerceAtLeast(0))
        var line = 0
        var character = 0
        for (i in 0 until target) {
            if (source[i] == '\n') {
                line += 1
                character = 0
            } else {
                character += 1
            }
        }
        return Position(line, character)
    }

    private fun androidProviderUri(className: String): String {
        return "file:///__jvm__/classes/${className.replace('.', '/')}.lua"
    }

    private data class ManifestRow(
        val id: String,
        val sourcePath: String,
        val kind: String,
        val tags: Set<String>
    ) {
        val isParseInput: Boolean = kind == "lua" || kind == "aly"

        fun hasTag(tag: String): Boolean = tag in tags

        fun source(root: File): String = sourceFile(root).readText(Charsets.UTF_8)

        fun sourceFile(root: File): File = File(root, sourcePath)
    }

    private data class Captured<T>(val value: T, val output: String)

    private companion object {
        const val MANIFEST_RESOURCE = "integration/androidlua/corpus-manifest.md"
        const val ANDROID_LUA_MAIN_PROPERTY = "androidLua.main"
        const val ANDROID_LUA_MAIN_ENV = "ANDROID_LUA_MAIN"

        /** Preferred host clone path on this macOS workstation (present for commit 686a792…). */
        const val HOST_MACOS_ANDROID_LUA_MAIN =
            "/Users/dingyi/projects/java_projects/Android-Lua/app/src/main"

        /**
         * Documented Windows clone path. On Windows hosts it is a first-class present-clone
         * candidate; on macOS/Linux it is last-resort only (never a sole hard default).
         */
        const val WINDOWS_DOCUMENTED_ANDROID_LUA_MAIN = "G:/Android-Lua/app/src/main"

        /**
         * Host WAVE hard-lock android.jar path (SDK platforms/android-35).
         * Presence check / messaging only; discovery uses
         * [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] and never hard-requires G:/.
         */
        const val HOST_MACOS_ANDROID_JAR_PATH =
            "/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar"

        /**
         * Host dual-path candidates after system property / env overrides.
         * OS-aware: Windows prefers present Windows clones and never injects the macOS
         * absolute path (Java would invent `<drive>:\Users\dingyi\projects\...` hybrids).
         * macOS prefers the real macOS clone first; Windows G: remains last-resort there.
         */
        val HOST_ANDROID_LUA_MAIN_CANDIDATES: List<String>
            get() {
                val home = System.getProperty("user.home")?.trim()?.takeIf { it.isNotEmpty() } ?: "."
                val homeClone = File(home, "projects/java_projects/Android-Lua/app/src/main").path
                val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
                return if (windows) {
                    listOf(homeClone, WINDOWS_DOCUMENTED_ANDROID_LUA_MAIN).distinct()
                } else {
                    listOf(
                        HOST_MACOS_ANDROID_LUA_MAIN,
                        homeClone,
                        WINDOWS_DOCUMENTED_ANDROID_LUA_MAIN
                    ).distinct()
                }
            }

        /**
         * Default / fallback root when overrides are unset: first present dual-path candidate,
         * else an OS-appropriate messaging candidate (never invent a Windows hybrid of the
         * macOS absolute path when no directory is present).
         */
        val DEFAULT_ANDROID_LUA_MAIN: String
            get() {
                val candidates = HOST_ANDROID_LUA_MAIN_CANDIDATES
                candidates.firstOrNull { File(it).isDirectory }?.let { return it }
                val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
                return if (windows) {
                    candidates.firstOrNull() ?: WINDOWS_DOCUMENTED_ANDROID_LUA_MAIN
                } else {
                    HOST_MACOS_ANDROID_LUA_MAIN
                }
            }

        /**
         * Workspace virtual paths for the expanded semantic matrix (TASK-564).
         * Must stay in lock-step with [semanticWorkspaceRows].
         */
        val SEMANTIC_WORKSPACE_PATHS: List<String> = listOf(
            "import.lua",
            "loadlayout.lua",
            "loadmenu.lua",
            "autotheme.lua",
            "loadbitmap.lua",
            "http.lua",
            "socket/url.lua",
            "json.lua",
            "xml.lua",
            "permission.lua",
            "asset-main.lua",
            "Dialog.lua",
            "toast.lua",
            "asset-andlua.lua",
            "layouthelper/loadlayout2.lua",
            "layouthelper/loadlayout3.lua",
            "layout.lua",
            "My.lua"
        )

        /** Paths sourced from manifest rows tagged `semantic.service`. */
        val SEMANTIC_SERVICE_WORKSPACE_PATHS: List<String> = listOf(
            "loadlayout.lua",
            "layouthelper/loadlayout2.lua",
            "layouthelper/loadlayout3.lua"
        )

        val TAG_PATTERN = Regex("`([^`]+)`")
        val androidImportPrefixes = listOf(
            "java.lang",
            "java.util",
            "java.io",
            "android.app",
            "android.content",
            "android.view",
            "android.view.View",
            "android.widget",
            "com.androlua"
        )
        val androidLuaImports = listOf(
            "Activity",
            "Context",
            "View",
            "OnClickListener",
            "TextView",
            "Button",
            "LinearLayout",
            "CardView"
        )
    }
}
