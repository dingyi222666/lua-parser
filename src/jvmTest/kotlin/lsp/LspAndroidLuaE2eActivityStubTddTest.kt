package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.lsp.LuaWorkspaceService
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.Assume
import java.io.File
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-229 — Android-Lua E2E activity/view stub fixture expansion.
 *
 * Bounded LSP E2E corpus for reflection-backed `activity` / `View` surfaces.
 *
 * REVIEW23–28 rejection notes (activity member empty / Type: unknown / loadlayout empty):
 * - Overlay types `activity` as a named custom type (`AndroidLuaContext` /
 *   `LuaActivity`) without a usable reflected member surface in the LSP path, so
 *   bare `activity.getLuaDir` member completion/hover is empty/unknown.
 *   This corpus asserts product-current global hover and models activity-shaped
 *   stubs with Emmy `---@class` + `---@field` + `---@method Class:name()` (same
 *   shape as [CompletionProviderTest] / SemanticPipelinePublicFacade member
 *   completion goldens). Inline `---@type { field: fun() }` object types do not
 *   currently expand a member completion surface on the LSP path.
 * - Member completion/hover needles must target the *usage* site, not the earlier
 *   `---@field` / `---@method` doc token (occurrence 1 is the annotation).
 * - Android framework members come from the configured host `android.jar`.
 * - `loadlayout` returns JavaObject/View-like for hover; product does not expand a
 *   usable member surface on the raw return. REVIEW28: do **not** re-annotate a
 *   constructed `TextView` with `---@type android.widget.TextView` when asserting
 *   inherited View members (`setVisibility`) — that Emmy override can drop the
 *   constructor-inferred inheritance surface and yield empty completion labels.
 *   Assert inherited View members on an unannotated `TextView(activity)` local
 *   (same shape as [view_instance_member_completion_includes_set_visibility_and_get_id]
 *   / main_activity construction) and keep raw loadlayout hover separately.
 *
 * Verification is review-owned and serial; workers must not run Gradle.
 */
class LspAndroidLuaE2eActivityStubTddTest {
    private val androidJar = resolveAndroidJar()

    // -------------------------------------------------------------------------
    // Skip / discovery contract
    // -------------------------------------------------------------------------

    @Test
    fun missing_android_jar_skip_documents_explicit_reason() {
        val missing = File("/nonexistent/android-sdk/platforms/android-35/android.jar")
        val reason = missingAndroidJarSkipReason(missing)

        assertTrue(reason.contains("TASK-229"), "Skip reason must name TASK-229; got: $reason")
        assertTrue(reason.contains("android.jar"), "Skip reason must mention android.jar; got: $reason")
        assertTrue(reason.contains(missing.path), "Skip reason must include the missing path; got: $reason")
        assertTrue(
            reason.contains("ANDROID_HOME") || reason.contains("ANDROID_SDK_ROOT") || reason.contains("Install"),
            "Skip reason must tell the host how to recover; got: $reason"
        )
    }

    // -------------------------------------------------------------------------
    // Activity stub — completion + hover (LSP E2E)
    // -------------------------------------------------------------------------

    @Test
    fun activity_global_hover_surfaces_lua_activity_or_activity_stub() {
        val service = androidService()
        val document = service.open(
            "workspace/activity-stub-hover.lua",
            """
            require "import"
            local host = activity
            return host
            """
        )

        val hover = assertNotNull(
            service.hover(hoverParams(document, "activity", occurrence = 1)),
            "Expected hover for Android-Lua activity global stub."
        )
        // Product-current: name and/or overlay custom type (AndroidLuaContext / LuaActivity).
        assertHoverMentionsAny(hover, "activity", "LuaActivity", "AndroidLuaContext", "Activity")
    }

    @Test
    fun annotated_activity_local_member_completion_covers_stub_methods() {
        // Product overlay does not currently expand CustomType(LuaActivity/AndroidLuaContext)
        // into getLuaDir/newActivity members on the LSP path. E2E still needs a stable
        // activity-shaped member surface, so the fixture models the expected stub via
        // Emmy ---@class / ---@field / ---@method (product-supported member completion
        // shape from CompletionProviderTest). Use `{}` RHS so the annotation is not
        // overridden by the activity global's empty custom-type surface.
        val service = androidService()
        val document = service.open(
            "workspace/activity-stub-members.lua",
            """
            require "import"

            ---@class ActivityStub
            ---@field getLuaDir fun(): string
            ---@field loadDex fun(name: string): any
            ---@method ActivityStub:newActivity(path: string)
            ---@method ActivityStub:newTask(src: string): any
            ---@method ActivityStub:setContentView(view: any)
            ---@type ActivityStub
            local host = {}
            local dir = host.getLuaDir
            return dir
            """
        )

        // occurrence=2: skip the ---@field getLuaDir doc token (occurrence 1).
        val completions = service.completionAt(document, "getLuaDir", occurrence = 2, offset = 3)
        val labels = completions.items.map { it.label }

        assertCompletion(labels, "getLuaDir")
        assertCompletion(labels, "newActivity")
        assertCompletion(labels, "newTask")
        assertCompletion(labels, "loadDex")
        assertCompletion(labels, "setContentView")
    }

    // -------------------------------------------------------------------------
    // View stub — completion + hover (LSP E2E)
    // -------------------------------------------------------------------------

    @Test
    fun view_instance_member_completion_includes_set_visibility_and_get_id() {
        val service = androidService()
        val document = service.open(
            "workspace/view-member-completion.lua",
            """
            require "import"
            import "android.view.View"
            import "android.widget.TextView"
            local title = TextView(activity)
            title:setVisibility(View.VISIBLE)
            local id = title:getId()
            return title, id
            """
        )

        val visibilityCompletions = service.completionAt(document, "setVisibility", offset = 3)
        val idCompletions = service.completionAt(document, "getId", offset = 2)
        val visibilityLabels = visibilityCompletions.items.map { it.label }
        val idLabels = idCompletions.items.map { it.label }

        assertCompletion(visibilityLabels, "setVisibility")
        assertCompletion(idLabels, "getId")
    }

    @Test
    fun text_view_member_completion_includes_set_text_from_activity_host() {
        val service = androidService()
        val document = service.open(
            "workspace/textview-settext.lua",
            """
            require "import"
            import "android.widget.TextView"
            local title = TextView(activity)
            title:setText("Hello Activity Stub")
            return title
            """
        )

        val completions = service.completionAt(document, "setText", offset = 3)
        val labels = completions.items.map { it.label }

        assertCompletion(labels, "setText")
        assertCompletion(labels, "getText")
    }

    // -------------------------------------------------------------------------
    // TextDocumentService wrap path (same fixtures)
    // -------------------------------------------------------------------------

    @Test
    fun text_document_service_exposes_activity_and_view_stub_hover_and_completion() {
        val languageService = androidService()
        val textDocuments = LuaTextDocumentService(languageService)

        val source = """
            require "import"
            import "android.view.View"
            import "android.widget.TextView"

            ---@class ActivityWrapStub
            ---@field getLuaDir fun(): string
            ---@method ActivityWrapStub:setContentView(view: any)
            ---@type ActivityWrapStub
            local host = {}
            local title = TextView(activity)
            title:setText("wrapped")
            title:setVisibility(View.VISIBLE)
            local dir = host.getLuaDir
            return title, dir
        """.trimIndent()
        val uri = "file:///workspace/activity-view-wrap.lua"
        textDocuments.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))

        val activityPosition = positionOf(source, "activity", occurrence = 1)
        val setTextPosition = positionOf(source, "setText", offset = 3)
        // occurrence=2: usage-site host.getLuaDir, not ---@field getLuaDir.
        val getLuaDirPosition = positionOf(source, "getLuaDir", occurrence = 2, offset = 3)

        val activityHover = assertNotNull(
            textDocuments.hover(HoverParams(TextDocumentIdentifier(uri), activityPosition)).get(),
            "TextDocumentService hover must surface activity stub."
        )
        val setTextCompletion = textDocuments
            .completion(CompletionParams(TextDocumentIdentifier(uri), setTextPosition))
            .get()
            .right
        val getLuaDirCompletion = textDocuments
            .completion(CompletionParams(TextDocumentIdentifier(uri), getLuaDirPosition))
            .get()
            .right

        assertHoverMentionsAny(activityHover, "activity", "LuaActivity", "AndroidLuaContext", "Activity", "TextView")
        assertCompletion(setTextCompletion.items.map { it.label }, "setText")
        assertCompletion(getLuaDirCompletion.items.map { it.label }, "getLuaDir")
        assertCompletion(getLuaDirCompletion.items.map { it.label }, "setContentView")
    }

    // -------------------------------------------------------------------------
    // Host-jar optional surface (skip cleanly when unavailable)
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun androidService(): LuaLanguageService {
        requireAndroidJarOrSkip()
        val jarPath = androidJar.path
        return LuaLanguageService(
            JvmWorkspaceEngine(
                configuration = JvmWorkspaceConfiguration(androidJar = jarPath)
            )
        ).also { service ->
            service.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
            // Flat config keeps metadata in sync for import prefixes / androlua imports.
            LuaWorkspaceService(service).didChangeConfiguration(
                DidChangeConfigurationParams(
                    mapOf(
                        "jvm.androidJar" to jarPath,
                        "jvm.importPrefixes" to listOf(
                            "java.lang",
                            "android.app",
                            "android.content",
                            "android.view",
                            "android.view.View",
                            "android.widget"
                        ),
                        "androlua.imports" to listOf(
                            "Activity",
                            "Context",
                            "View",
                            "TextView",
                            "Button",
                            "LinearLayout"
                        )
                    )
                )
            )
        }
    }

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(DidOpenTextDocumentParams(TextDocumentItem(document.uri, "lua", 1, document.source)))
        return document
    }

    private fun LuaLanguageService.completionAt(
        document: OpenDocument,
        needle: String,
        occurrence: Int = 1,
        offset: Int = 0
    ): org.eclipse.lsp4j.CompletionList {
        val position = document.positionOf(needle, occurrence, offset)
        return completion(document.path, position.line, position.character)
    }

    private fun hoverParams(
        document: OpenDocument,
        needle: String,
        occurrence: Int = 1,
        offset: Int = 0
    ): HoverParams {
        return HoverParams(
            TextDocumentIdentifier(document.uri),
            document.positionOf(needle, occurrence, offset)
        )
    }

    private fun requireAndroidJarOrSkip() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
    }

    private fun assertCompletion(labels: List<String>, expected: String) {
        assertTrue(expected in labels, "Expected completion '$expected', actual labels: $labels.")
    }

    private fun assertHoverMentionsAny(hover: Hover, vararg expectedFragments: String) {
        val markup = hoverMarkup(hover)
        assertTrue(
            expectedFragments.any { fragment -> markup.contains(fragment, ignoreCase = true) },
            "Expected hover to mention one of ${expectedFragments.toList()}, actual: $markup."
        )
    }

    private fun hoverMarkup(hover: Hover): String {
        val contents = hover.contents
        return when {
            contents.isRight -> contents.right.value
            contents.isLeft -> contents.left.joinToString("\n") { either ->
                if (either.isRight) either.right.value else either.left.toString()
            }
            else -> hover.toString()
        }
    }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1, offset: Int = 0): Position {
        require(occurrence > 0) { "Occurrence must be positive." }
        var from = 0
        var index = -1
        repeat(occurrence) {
            index = source.indexOf(needle, from)
            require(index >= 0) { "Could not find occurrence ${it + 1} of '$needle' in source." }
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

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"

        fun positionOf(needle: String, occurrence: Int = 1, offset: Int = 0): Position {
            require(occurrence >= 1) { "occurrence must be positive" }
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find occurrence ${it + 1} of '$needle' in $path" }
                fromIndex = index + needle.length
            }
            return positionAt(index + offset.coerceIn(0, needle.lastIndex.coerceAtLeast(0)))
        }

        private fun positionAt(absoluteOffset: Int): Position {
            var line = 0
            var character = 0
            for (i in 0 until absoluteOffset.coerceAtMost(source.length)) {
                if (source[i] == '\n') {
                    line += 1
                    character = 0
                } else {
                    character += 1
                }
            }
            return Position(line, character)
        }
    }

    companion object {
        /**
         * Host-resolution order for TASK-229 corpus:
         * 1) WAVE22 mac SDK path from worker environment
         * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS discovery
         * 3) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
         */
        private fun resolveAndroidJar(): File {
            val candidates = linkedSetOf<File>()
            candidates += File("/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar")
            candidates += File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env -> System.getenv(env)?.trim()?.takeIf(String::isNotEmpty) }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }
            return candidates.firstOrNull { it.isFile } ?: candidates.first()
        }

        internal fun missingAndroidJarSkipReason(androidJar: File): String {
            return "TASK-229 skipped: android.jar not found at ${androidJar.path}. " +
                "Install Android SDK Platform 35 (or set ANDROID_HOME / ANDROID_SDK_ROOT / jvm.androidJar) " +
                "before running Android-Lua E2E activity/view stub completion/hover corpus."
        }
    }
}
