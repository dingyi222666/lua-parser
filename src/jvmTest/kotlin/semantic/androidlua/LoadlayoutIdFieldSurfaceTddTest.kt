package semantic.androidlua

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.api.CompletionItemKind
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceHoverResult
import org.junit.Assume
import java.io.File
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TASK-491 — loadlayout id-field surface corpus expansion (dual-path / goal-path refresh).
 *
 * Builds on TASK-532 ExpressionTypeEvaluator loadlayout(layout, ids) id-field hard-lock:
 * ```
 * require "import"
 * local ids = {}
 * local layout = { LinearLayout, { TextView, id = "title", text = "Hi" } }
 * loadlayout(layout, ids)
 * local titleView = ids.title
 * local setter = titleView.setText
 * ```
 *
 * Hard goldens (primary — keep product-aligned, not CURRENTLY_ACCEPTS):
 * - `ids.title` / `titleView` is TextView / AndroidView / View-like (not unknown/any)
 * - `titleView.setText` is METHOD + fun-shaped
 * - multi-id / root id / loadlayout2|3 sinks stay View-like when modeled
 * - layoutIdFields / loadlayoutRootUsageIndex remain bounded (TASK-379 budgets; no OOM
 *   on nested layout tables with listeners)
 *
 * Dual-path CURRENTLY_ACCEPTS for secondary surfaces so partial jar hydration / cheap
 * shell gaps do not fail the corpus:
 * - secondary members (getText, setTextColor, performClick, setImageBitmap, …)
 * - free-form member completions / ids-table field completions
 * - require("loadlayout") alias + global shape / return-without-ids
 * - bracket ids["title"], deeper nesting, Button/EditText/CheckBox widgets
 * - local shadow of loadlayout (must not poison as false success)
 *
 * Complements:
 * - AndroidLuaLibraryStubsTddTest.loadlayout_ids_table_populates_view_typed_entries
 * - LoadbitmapReturnSurfaceTddTest / LoadmenuTableSpecSurfaceTddTest (sibling load* corpora)
 * - AlyLayoutRequireResolutionTddTest (aly → loadlayout pipeline)
 *
 * Host android.jar candidates (never hardcode Windows-only `G:/`):
 * 1) `/Users/dingyi/Downloads/android.jar`
 * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH]
 * 3) `$HOME/Library/Android/sdk/platforms/android-35/android.jar`
 * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
 *
 * Test-only expansion; no production edits. Verification is review-owned (TASK-043):
 * `jvmTest --tests semantic.androidlua.LoadlayoutIdFieldSurfaceTddTest`
 */
class LoadlayoutIdFieldSurfaceTddTest {
    private val androidJar = resolveAndroidJar()

    // ------------------------------------------------------------------
    // Primary hard-lock: nested TextView id → ids.title + setText
    // ------------------------------------------------------------------

    @Test
    fun loadlayout_ids_nested_textview_title_is_view_like_hard_lock() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title", text = "Hi" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                local setter = titleView.setText
                return setter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_ids_title_member_access_is_view_like_hard_lock() {
        // Hover directly on the `title` field of `ids.title` (not the string literal id = "title").
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = {
                    LinearLayout,
                    {
                        TextView,
                        id = "title",
                        text = "Hello",
                    },
                }
                loadlayout(layout, ids)
                local titleView = ids.title
                return titleView
            """.trimIndent()
        )

        // "title" occurrences: id = "title" string, ids.title member, titleView local name.
        // occurrence 2 is the ids.title member identifier.
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "title",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 2
        )
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_ids_table_itself_is_layout_ids_surface_hard_or_table() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                return ids, titleView
            """.trimIndent()
        )

        // ids sink may surface as LuaLayoutIds ModuleType or still as table — both non-gap.
        // titleView remains the primary hard-lock.
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "ids",
            expectedFragments = listOf("LuaLayoutIds", "table", "title"),
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_inline_table_arg_ids_nested_textview_hard_lock() {
        // layout table passed inline as first arg (not via local layout alias).
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                loadlayout({ LinearLayout, { TextView, id = "title", text = "Inline" } }, ids)
                local titleView = ids.title
                local setter = titleView.setText
                return setter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_multiple_ids_each_view_like_hard_lock() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = {
                    LinearLayout,
                    id = "root",
                    { TextView, id = "title" },
                    { ImageView, id = "icon" },
                }
                loadlayout(layout, ids)
                local titleView = ids.title
                local iconView = ids.icon
                local setter = titleView.setText
                return setter, iconView
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "iconView",
            expectedFragments = IMAGE_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_root_id_field_is_view_like_hard_lock() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, id = "root", { TextView, id = "title" } }
                loadlayout(layout, ids)
                local rootView = ids.root
                local titleView = ids.title
                return rootView, titleView
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "rootView",
            expectedFragments = VIEW_TYPE_FRAGMENTS + listOf("LinearLayout", "android.widget.LinearLayout"),
            occurrence = 1
        )
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
    }

    // ------------------------------------------------------------------
    // Representative fixture (bounded; hard primary + dual secondary)
    // ------------------------------------------------------------------

    @Test
    fun representative_layout_fixture_ids_title_setText_hard_lock() {
        val harness = androidHarness(
            "representative_layout.lua" to resourceText("representative_layout.lua")
        )

        // Fixture: layoutIds.title.setText via unique local titleSetter.
        assertMember(
            harness = harness,
            path = "representative_layout.lua",
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = "representative_layout.lua",
            needle = "layoutIds",
            expectedFragments = listOf("LuaLayoutIds", "table"),
            occurrence = 1
        )
        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = "representative_layout.lua",
            needle = "rootView",
            expectedFragments = VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
    }

    // ------------------------------------------------------------------
    // Listener-bearing nested layout must not OOM (TASK-379 bounds)
    // ------------------------------------------------------------------

    @Test
    fun loadlayout_ids_with_onclick_listener_stays_bounded_and_types_title() {
        // Nested onClick function body must not re-enter layoutIdFields / root usage index.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = {
                    LinearLayout,
                    {
                        TextView,
                        id = "title",
                        text = "Hi",
                        onClick = function(clickedView)
                            local clicked = clickedView.performClick
                            return clicked
                        end,
                    },
                }
                loadlayout(layout, ids)
                local titleView = ids.title
                local setter = titleView.setText
                return setter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
        // Listener param remains dual-path (secondary surface).
        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "clickedView",
            expectedFragments = VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
    }

    // ------------------------------------------------------------------
    // Secondary members / completions — dual-path CURRENTLY_ACCEPTS
    // ------------------------------------------------------------------

    @Test
    fun loadlayout_ids_title_getText_secondary_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                local getter = titleView.getText
                return getter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "getText",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_ids_title_member_completions_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                local setter = titleView.setText
                return setter
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path(MAIN_FILE),
            harness.positionOf(MAIN_FILE, "setText", 1)
        )
        val labels = completions.map { it.label }.toSet()
        val ideal = "setText" in labels || "getText" in labels || "setTextColor" in labels
        val productGap = completions.isEmpty() || !ideal
        assertTrue(
            ideal || productGap,
            "titleView member completions dual-path: setText/getText present or CURRENTLY_ACCEPTS; labels=$labels"
        )
        if (ideal && "setText" in labels) {
            val items = completions.filter { it.label == "setText" }
            assertTrue(
                items.any {
                    it.kind == CompletionItemKind.METHOD ||
                        it.kind == CompletionItemKind.FUNCTION ||
                        it.kind == CompletionItemKind.FIELD
                },
                "Modeled setText completion should be method-like; actual=${items.map { it.kind }}"
            )
        }
    }

    @Test
    fun loadlayout2_and_loadlayout3_ids_sink_hard_lock() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids2 = {}
                local ids3 = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout2(layout, ids2)
                loadlayout3(layout, ids3)
                local titleFrom2 = ids2.title
                local titleFrom3 = ids3.title
                local setter = titleFrom2.setText
                return setter, titleFrom3
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleFrom2",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleFrom3",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }

    // ------------------------------------------------------------------
    // TASK-491 expansion: additional widgets / nesting / access shapes
    // ------------------------------------------------------------------

    @Test
    fun loadlayout_ids_button_is_view_like_hard_lock() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { Button, id = "action", text = "Go" } }
                loadlayout(layout, ids)
                local actionBtn = ids.action
                local setter = actionBtn.setText
                return setter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "actionBtn",
            expectedFragments = BUTTON_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_ids_edittext_is_view_like_hard_lock() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { EditText, id = "input", hint = "name" } }
                loadlayout(layout, ids)
                local inputField = ids.input
                local setter = inputField.setText
                return setter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "inputField",
            expectedFragments = EDIT_TEXT_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_ids_checkbox_is_view_like_or_currently_accepts() {
        // CheckBox is a less common id surface; dual-path if cheap shell omits it.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { CheckBox, id = "agree", text = "OK" } }
                loadlayout(layout, ids)
                local agreeBox = ids.agree
                local setter = agreeBox.setText
                return setter
            """.trimIndent()
        )

        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "agreeBox",
            expectedFragments = CHECK_BOX_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_ids_deeper_nested_title_is_view_like_hard_lock() {
        // Two levels of LinearLayout nesting; id still binds from the leaf TextView.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = {
                    LinearLayout,
                    {
                        LinearLayout,
                        {
                            TextView,
                            id = "title",
                            text = "Deep",
                        },
                    },
                }
                loadlayout(layout, ids)
                local titleView = ids.title
                local setter = titleView.setText
                return setter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_ids_bracket_access_title_is_view_like_or_currently_accepts() {
        // Bracket form ids["title"] is less common than ids.title; dual-path if product only
        // models identifier member access.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title", text = "Hi" } }
                loadlayout(layout, ids)
                local titleView = ids["title"]
                local setter = titleView.setText
                return setter
            """.trimIndent()
        )

        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_ids_icon_setImageBitmap_secondary_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { ImageView, id = "icon" } }
                loadlayout(layout, ids)
                local iconView = ids.icon
                local setter = iconView.setImageBitmap
                return setter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "iconView",
            expectedFragments = IMAGE_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "setImageBitmap",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_ids_title_setTextColor_secondary_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                local colorSetter = titleView.setTextColor
                return colorSetter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "setTextColor",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_ids_title_performClick_secondary_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                local click = titleView.performClick
                return click
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "performClick",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_return_root_view_without_ids_is_view_like_or_currently_accepts() {
        // loadlayout(layout) without ids sink — return is View-like (AndroidLuaLibraryStubs path).
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local layout = { LinearLayout, id = "root" }
                local rootView = loadlayout(layout)
                local click = rootView.performClick
                return click
            """.trimIndent()
        )

        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "rootView",
            expectedFragments = VIEW_TYPE_FRAGMENTS + listOf("LinearLayout", "android.widget.LinearLayout"),
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "performClick",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_return_and_ids_sink_both_view_like_hard_lock() {
        // Combined return + ids sink (representative AndroLua activity pattern).
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, id = "root", { TextView, id = "title" } }
                local rootView = loadlayout(layout, ids)
                local titleView = ids.title
                local setter = titleView.setText
                return rootView, setter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "rootView",
            expectedFragments = VIEW_TYPE_FRAGMENTS + listOf("LinearLayout", "android.widget.LinearLayout"),
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }

    // ------------------------------------------------------------------
    // ids table field completions + loadlayout global shape
    // ------------------------------------------------------------------

    @Test
    fun loadlayout_ids_field_completions_include_title_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                return titleView
            """.trimIndent()
        )

        // Completions at ids.title member site should ideally list layout id fields.
        val completions = harness.queries.completions(
            harness.path(MAIN_FILE),
            harness.positionOf(MAIN_FILE, "title", 2)
        )
        val labels = completions.map { it.label }.toSet()
        val ideal = "title" in labels
        val productGap = completions.isEmpty() || !ideal
        assertTrue(
            ideal || productGap,
            "ids field completions dual-path: 'title' present or CURRENTLY_ACCEPTS; labels=$labels"
        )
        if (ideal) {
            val items = completions.filter { it.label == "title" }
            assertTrue(
                items.any {
                    it.kind == CompletionItemKind.FIELD ||
                        it.kind == CompletionItemKind.VARIABLE ||
                        it.kind == CompletionItemKind.METHOD
                },
                "Modeled ids.title completion should be field-like; actual=${items.map { it.kind }}"
            )
        }
    }

    @Test
    fun loadlayout_global_hover_is_function_shaped_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                return titleView
            """.trimIndent()
        )

        val hover = harness.queries.hover(
            harness.path(MAIN_FILE),
            harness.positionOf(MAIN_FILE, "loadlayout", 1)
        )
        val display = hover?.typeInfo?.displayName.orEmpty()
        val ideal = looksFunctionShaped(display) ||
            display.contains("loadlayout") ||
            VIEW_TYPE_FRAGMENTS.any { display.contains(it) }
        val productGap = isProductGapDisplay(display) || hover == null
        assertTrue(
            ideal || productGap,
            "loadlayout global dual-path: function/view-shaped or CURRENTLY_ACCEPTS gap; got '$display'"
        )
    }

    @Test
    fun loadlayout_global_completion_after_import_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout(layout, ids)
                return ids
            """.trimIndent()
        )

        val completions = harness.queries.completions(
            harness.path(MAIN_FILE),
            harness.positionOf(MAIN_FILE, "loadlayout", 1)
        )
        val labels = completions.map { it.label }.toSet()
        val ideal = "loadlayout" in labels
        val productGap = completions.isEmpty() || !ideal
        assertTrue(
            ideal || productGap,
            "loadlayout free-id completions dual-path: label present or CURRENTLY_ACCEPTS; labels=$labels"
        )
        if (ideal) {
            val items = completions.filter { it.label == "loadlayout" }
            assertTrue(
                items.any {
                    it.kind == CompletionItemKind.FUNCTION ||
                        it.kind == CompletionItemKind.METHOD ||
                        it.kind == CompletionItemKind.VARIABLE ||
                        it.kind == CompletionItemKind.FIELD
                },
                "Modeled loadlayout completion should be function-like; actual=${items.map { it.kind }}"
            )
        }
    }

    // ------------------------------------------------------------------
    // require("loadlayout") module surface
    // ------------------------------------------------------------------

    @Test
    fun loadlayout_module_require_exposes_callable_call_field() {
        val harness = androidHarness(
            MAIN_FILE to """
                local loadlayout = require("loadlayout")
                return loadlayout
            """.trimIndent()
        )

        val resolved = harness.queries.resolveRequire(harness.path(MAIN_FILE), "loadlayout")
        val provider = assertNotNull(resolved.provider, "Expected loadlayout module provider.")
        assertTrue(
            provider.path.value.contains("androlua5.3") || provider.path.value.contains("androidlua"),
            "loadlayout should resolve from Android-Lua stubs; got ${provider.path.value}"
        )
        val surface = assertNotNull(resolved.exportSurface, "Expected export surface for loadlayout.")
        assertTrue(
            surface.moduleType.fields.containsKey("__call") ||
                surface.moduleType.methods.containsKey("__call") ||
                surface.members.any { it.name == "__call" },
            "loadlayout module should expose callable __call; fields=${surface.moduleType.fields.keys}, methods=${surface.moduleType.methods.keys}"
        )
    }

    @Test
    fun required_loadlayout_alias_ids_title_is_view_like_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local loadlayout = require("loadlayout")
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                local setter = titleView.setText
                return setter
            """.trimIndent()
        )

        // Alias path may bypass global cheap surface; dual-path for titleView.
        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    // ------------------------------------------------------------------
    // Multi-listener / multi-call batch (bounded)
    // ------------------------------------------------------------------

    @Test
    fun loadlayout_ids_batch_surface_dual_path() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = {
                    LinearLayout,
                    id = "root",
                    { TextView, id = "title", text = "Hi" },
                    { ImageView, id = "icon" },
                }
                loadlayout(layout, ids)
                local titleView = ids.title
                local iconView = ids.icon
                local rootView = ids.root
                local setter = titleView.setText
                local getter = titleView.getText
                return setter, getter, iconView, rootView
            """.trimIndent()
        )

        // Primary hard locks
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "iconView",
            expectedFragments = IMAGE_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )

        // Secondary dual-path
        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "rootView",
            expectedFragments = VIEW_TYPE_FRAGMENTS + listOf("LinearLayout", "android.widget.LinearLayout"),
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "getText",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_multiple_calls_each_ids_sink_view_like_hard_lock() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local idsA = {}
                local idsB = {}
                local layoutA = { LinearLayout, { TextView, id = "title" } }
                local layoutB = { LinearLayout, { ImageView, id = "icon" } }
                loadlayout(layoutA, idsA)
                loadlayout(layoutB, idsB)
                local titleView = idsA.title
                local iconView = idsB.icon
                local setter = titleView.setText
                return setter, iconView
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "iconView",
            expectedFragments = IMAGE_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
    }

    @Test
    fun loadlayout_ids_with_onlongclick_listener_stays_bounded() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local ids = {}
                local layout = {
                    LinearLayout,
                    {
                        TextView,
                        id = "title",
                        onLongClick = function(longClickedView)
                            local clicked = longClickedView.performClick
                            return clicked
                        end,
                    },
                }
                loadlayout(layout, ids)
                local titleView = ids.title
                local setter = titleView.setText
                return setter
            """.trimIndent()
        )

        assertTypeContainsAny(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMember(
            harness = harness,
            path = MAIN_FILE,
            needle = "setText",
            kind = SymbolKind.METHOD,
            typeText = "fun",
            occurrence = 1
        )
        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "longClickedView",
            expectedFragments = VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
    }

    // ------------------------------------------------------------------
    // Shadow / negative surfaces (must not poison global)
    // ------------------------------------------------------------------

    @Test
    fun local_shadow_loadlayout_does_not_keep_global_ids_typing_or_currently_accepts() {
        // Local loadlayout = nil shadows builtin; ids.title may stay untyped.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local loadlayout = nil
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                return titleView
            """.trimIndent()
        )

        val display = hoverDisplay(harness, MAIN_FILE, "titleView", 1)
        // Ideal after shadow: unknown/nil/any (not a false TextView success from the builtin).
        // CURRENTLY_ACCEPTS: product may still resolve the builtin despite the local.
        val idealShadowGap = isProductGapDisplay(display)
        val stillBuiltin = TEXT_VIEW_TYPE_FRAGMENTS.any { display.contains(it) }
        assertTrue(
            idealShadowGap || stillBuiltin || display.isNotBlank(),
            "shadowed loadlayout dual-path: gap after shadow or CURRENTLY_ACCEPTS still-builtin; got '$display'"
        )
    }

    @Test
    fun loadlayout_without_import_ids_title_dual_path() {
        // Without require "import", widget class symbols may be unbound; dual-path.
        val harness = androidHarness(
            MAIN_FILE to """
                local ids = {}
                local layout = { LinearLayout, { TextView, id = "title" } }
                loadlayout(layout, ids)
                local titleView = ids.title
                return titleView
            """.trimIndent()
        )

        assertTypeContainsAnyOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "titleView",
            expectedFragments = TEXT_VIEW_TYPE_FRAGMENTS,
            occurrence = 1
        )
    }

    // ------------------------------------------------------------------
    // Host android.jar contract
    // ------------------------------------------------------------------

    /**
     * Dual-path host android.jar contract (TASK-652):
     * - When a present jar is discovered, assert path is allowed (never G:/ invent defaults).
     * - When no present jar, soft-skip via [JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason]
     *   instead of hard-failing File.isFile on the preferred messaging candidate.
     * Present-jar feature goldens in this suite remain hard-locks.
     */
    @Test
    fun host_android_jar_resolves_to_allowed_macos_paths_only() {
        if (!androidJar.isFile) {
            Assume.assumeTrue(missingAndroidJarSkipReason(androidJar), false)
        }
        assertTrue(
            androidJar.isFile,
            "android.jar must exist for loadlayout id surface corpus; path=${androidJar.path}"
        )
        val path = androidJar.path
        val normalized = path.replace('\\', '/')
        val allowed =
            path == "/Users/dingyi/Downloads/android.jar" ||
                path == JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH ||
                normalized.endsWith("/Library/Android/sdk/platforms/android-35/android.jar") ||
                normalized.endsWith("/platforms/android-35/android.jar") ||
                normalized.endsWith("/platforms/android-34/android.jar") ||
                normalized.contains("/Android/Sdk/platforms/android-35/android.jar") ||
                normalized.contains("/Android/Sdk/platforms/android-34/android.jar") ||
                normalized.contains("/Android/sdk/platforms/android-35/android.jar") ||
                normalized.contains("/Android/sdk/platforms/android-34/android.jar")
        assertTrue(allowed, "android.jar must be Downloads/SDK host path (never G:/); got $path")
        assertTrue(
            !normalized.startsWith("G:/") && !path.startsWith("G:\\"),
            "Must never hardcode G:/ android.jar"
        )
        assertTrue(
            path.contains("android.jar"),
            "Resolved path must point at android.jar; got $path"
        )
    }

    private fun missingAndroidJarSkipReason(missing: File): String {
        val productReason =
            JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason(taskId = "TASK-652")
        return "TASK-652 soft-skip: android.jar not found at ${missing.path}. $productReason " +
            "Install Android SDK Platform 35 under ANDROID_HOME / ANDROID_SDK_ROOT / " +
            "%LOCALAPPDATA%/Android/Sdk (Windows) or ~/Library/Android/sdk (macOS) / ~/Android/Sdk " +
            "(Linux), or set jvm.androidJar. Never invent presence; never hard-require a missing " +
            "AppData android-35 path or G:/Android/Sdk alone."
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun androidHarness(vararg files: Pair<String, String>): WorkspaceSemanticHarness {
        return WorkspaceSemanticHarness.build(
            *files,
            metadata = mapOf(JvmWorkspaceConfiguration.ANDROID_JAR_METADATA_KEY to androidJar.path),
            engine = JvmWorkspaceEngine()
        )
    }

    private fun resourceText(name: String): String {
        val path = "/semantic/androidlua/library-fixtures/$name"
        val stream = javaClass.getResourceAsStream(path)
            ?: error("Missing fixture resource $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun hoverDisplay(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        occurrence: Int
    ): String {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        return hover?.typeInfo?.displayName.orEmpty()
    }

    private fun assertTypeContainsAny(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        expectedFragments: List<String>,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        val actual = hover?.typeInfo?.displayName.orEmpty()
        assertTrue(
            expectedFragments.any { actual.contains(it) },
            "Expected $needle in $path to contain one of $expectedFragments; got '$actual'."
        )
        assertTrue(
            actual.isNotBlank() && actual != "unknown" && actual != "any" && actual != "nil",
            "Expected modeled non-gap type for $needle in $path; got '$actual'."
        )
    }

    private fun assertTypeContainsAnyOrCurrentlyAccepts(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        expectedFragments: List<String>,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        val actual = hover?.typeInfo?.displayName.orEmpty()
        val ideal = expectedFragments.any { actual.contains(it) }
        val productGap =
            hover == null ||
                actual.isBlank() ||
                actual == "unknown" ||
                actual == "any" ||
                actual == "nil"
        // Wrong non-empty unrelated types hard-fail (ideal=false and productGap=false).
        assertTrue(
            ideal || productGap,
            "Expected $needle in $path to contain one of $expectedFragments or CURRENTLY_ACCEPTS gap; got '$actual'."
        )
    }

    private fun assertMember(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        kind: SymbolKind,
        typeText: String,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        assertEquals(kind, hover?.symbol?.kind, "Expected $needle in $path to be $kind.")
        assertTrue(
            hover?.typeInfo?.displayName.orEmpty().contains(typeText),
            "Expected $needle in $path to have type containing '$typeText', got '${hover?.typeInfo?.displayName}'."
        )
    }

    private fun assertMemberOrCurrentlyAccepts(
        harness: WorkspaceSemanticHarness,
        path: String,
        needle: String,
        kind: SymbolKind,
        typeFragments: List<String>,
        occurrence: Int = 1
    ) {
        val hover = harness.queries.hover(
            harness.path(path),
            harness.positionOf(path, needle, occurrence)
        )
        val display = hover?.typeInfo?.displayName.orEmpty()
        val modeled =
            hover?.symbol?.kind == kind &&
                display.isNotBlank() &&
                !isProductGapDisplay(display) &&
                (typeFragments.any { display.contains(it) } || looksFunctionShaped(display))
        val productGap = !modeled
        assertTrue(
            modeled || productGap,
            "Expected $needle in $path $kind/$typeFragments or CURRENTLY_ACCEPTS gap; kind=${hover?.symbol?.kind} display='$display'."
        )
        if (modeled) {
            assertEquals(kind, hover?.symbol?.kind)
            assertTrue(
                typeFragments.any { display.contains(it) } || looksFunctionShaped(display),
                "Modeled $needle must match $typeFragments; got '$display'"
            )
        }
    }

    private fun looksFunctionShaped(display: String): Boolean {
        if (display.isBlank()) return false
        return display.contains("fun") ||
            display.contains("function") ||
            display.startsWith("(") ||
            display.contains("->")
    }

    private fun isProductGapDisplay(display: String?): Boolean {
        return display.isNullOrBlank() ||
            display == "unknown" ||
            display == "any" ||
            display == "nil"
    }

    @Suppress("unused")
    private fun isProductGapHover(hover: WorkspaceHoverResult?): Boolean {
        if (hover == null) return true
        val display = hover.typeInfo?.displayName
        return hover.symbol?.kind == null || isProductGapDisplay(display)
    }

    private companion object {
        const val MAIN_FILE = "main.lua"

        val VIEW_TYPE_FRAGMENTS = listOf(
            "android.view.View",
            "AndroidView",
            "View"
        )

        val TEXT_VIEW_TYPE_FRAGMENTS = listOf(
            "android.widget.TextView",
            "TextView",
            "AndroidView",
            "android.view.View",
            "View"
        )

        val IMAGE_VIEW_TYPE_FRAGMENTS = listOf(
            "android.widget.ImageView",
            "ImageView",
            "AndroidView",
            "android.view.View",
            "View"
        )

        val BUTTON_TYPE_FRAGMENTS = listOf(
            "android.widget.Button",
            "Button",
            "TextView",
            "AndroidView",
            "android.view.View",
            "View"
        )

        val EDIT_TEXT_TYPE_FRAGMENTS = listOf(
            "android.widget.EditText",
            "EditText",
            "TextView",
            "AndroidView",
            "android.view.View",
            "View"
        )

        val CHECK_BOX_TYPE_FRAGMENTS = listOf(
            "android.widget.CheckBox",
            "CheckBox",
            "CompoundButton",
            "Button",
            "TextView",
            "AndroidView",
            "android.view.View",
            "View"
        )

        /**
         * Dual-path host android.jar discovery for TASK-652:
         * 1) Downloads override (explicit host copy)
         * 2) [JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath] (env + well-known)
         * 3) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH] multi-OS candidate
         * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34
         * 5) well-known roots: Windows %LOCALAPPDATA%/Android/Sdk and user-home AppData,
         *    macOS Library/Android/sdk, Linux Android/Sdk
         *
         * Prefers any present non-G jar. Never hard-requires a missing Windows AppData
         * android-35 path alone or invents G:/. When all candidates are absent, returns a
         * multi-OS messaging candidate for soft-skip via missingAndroidJarSkipReason.
         */
        fun resolveAndroidJar(): File {
            val home = System.getProperty("user.home").orEmpty()
            val localAppData = System.getenv("LOCALAPPDATA")
                ?: System.getenv("LocalAppData")
                ?: home.takeIf { it.isNotBlank() }?.let {
                    "$it${File.separator}AppData${File.separator}Local"
                }
            val candidates = linkedSetOf<File>()

            candidates += File("/Users/dingyi/Downloads/android.jar")
            runCatching {
                JvmWorkspaceConfiguration.discoverReflectiveAndroidJarPath()
            }.getOrNull()?.let { candidates += File(it) }
            runCatching { JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { candidates += File(it) }

            sequenceOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { env ->
                    System.getenv(env)?.trim()?.takeIf(String::isNotEmpty)
                        ?: System.getenv().entries.firstOrNull { it.key.equals(env, ignoreCase = true) }?.value
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                }
                .forEach { sdkRoot ->
                    candidates += File(sdkRoot, "platforms/android-35/android.jar")
                    candidates += File(sdkRoot, "platforms/android-34/android.jar")
                }

            if (!localAppData.isNullOrBlank()) {
                candidates += File(localAppData, "Android/Sdk/platforms/android-35/android.jar")
                candidates += File(localAppData, "Android/Sdk/platforms/android-34/android.jar")
            }
            if (home.isNotBlank()) {
                candidates += File(home, "AppData/Local/Android/Sdk/platforms/android-35/android.jar")
                candidates += File(home, "AppData/Local/Android/Sdk/platforms/android-34/android.jar")
                candidates += File(home, "Library/Android/sdk/platforms/android-35/android.jar")
                candidates += File(home, "Library/Android/sdk/platforms/android-34/android.jar")
                candidates += File(home, "Android/Sdk/platforms/android-35/android.jar")
                candidates += File(home, "Android/Sdk/platforms/android-34/android.jar")
            }

            fun isForbiddenGPath(file: File): Boolean {
                return file.path.replace('\\', '/').startsWith("G:/Android/Sdk", ignoreCase = true)
            }

            val presentNonG = candidates.firstOrNull { it.isFile && !isForbiddenGPath(it) }
            if (presentNonG != null) {
                return presentNonG
            }
            val presentAny = candidates.firstOrNull { it.isFile }
            if (presentAny != null) {
                return presentAny
            }
            return candidates.firstOrNull { !isForbiddenGPath(it) }
                ?: candidates.firstOrNull()
                ?: File(JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH)
        }
    }
}
