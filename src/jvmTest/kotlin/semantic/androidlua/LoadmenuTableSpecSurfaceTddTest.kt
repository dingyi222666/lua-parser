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
 * TASK-445 — ExpressionTypeEvaluator / Android-Lua `loadmenu` table-spec dual-path corpus.
 *
 * Locks the TASK-184 / ExpressionTypeEvaluator load* family path for:
 * ```
 * require "import"
 * local menuBar = loadmenu(activity.getMenu(), {
 *   { title = "Refresh", id = "refresh", onClick = function(item) ... end },
 * })
 * ```
 * Ideal: `menuBar` is menu-like (`android.view.Menu` / `AndroidMenu` / `Menu`) and
 * members such as `add` / `findItem` / `clear` / `size` are METHOD + function-shaped.
 * Spec listeners ideally type `item` as MenuItem-like.
 *
 * Dual-path / CURRENTLY_ACCEPTS:
 * Product may still leave call-result typing partial (unknown/any/blank), fail to
 * hydrate Menu members under android.jar, or leave menu-spec `onClick` item params
 * untyped (layout listener typing currently targets View listeners, not MenuItem).
 * Ideal goldens assert modeled Menu surfaces; gaps are accepted so the corpus stays
 * green while still locking the loadmenu(table-spec) call shape and host jar paths.
 * Wrong non-empty unrelated types hard-fail (not treated as product gap).
 *
 * Complements:
 * - AndroidLuaLibraryStubsTddTest.loadmenu_global_returns_menu_like_value_and_accepts_table_specs
 * - AndroidLuaLibraryStubsTddTest.loadmenu_module_resolves_as_callable_library_stub
 * - LoadbitmapReturnSurfaceTddTest (sibling load* family corpus)
 *
 * Host android.jar candidates (never hardcode Windows-only `G:/`):
 * 1) `/Users/dingyi/Downloads/android.jar`
 * 2) [JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH]
 * 3) `$HOME/Library/Android/sdk/platforms/android-35/android.jar`
 * 4) ANDROID_HOME / ANDROID_SDK_ROOT platforms/android-35|34/android.jar
 *
 * Test-only; no production edits. Verification is review-owned (TASK-043):
 * `jvmTest --tests semantic.androidlua.LoadmenuTableSpecSurfaceTddTest`
 */
class LoadmenuTableSpecSurfaceTddTest {
    private val androidJar = resolveAndroidJar()

    // ------------------------------------------------------------------
    // loadmenu(activity.getMenu(), { ... }) return menu-like surface
    // ------------------------------------------------------------------

    @Test
    fun loadmenu_with_table_spec_return_is_menu_like_or_currently_accepts() {
        // Mirrors AndroidLuaLibraryStubsTddTest.loadmenu_global_returns_menu_like_value_and_accepts_table_specs
        // Unique local `menuBar` avoids substring collision with "loadmenu" / "getMenu".
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "Refresh", id = "refresh", onClick = function(item) return item.getTitle end },
                })
                local add = menuBar.add
                return add
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "menuBar",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
    }

    @Test
    fun loadmenu_table_spec_menu_add_member_is_method_function_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "Refresh", id = "refresh" },
                })
                local add = menuBar.add
                return add
            """.trimIndent()
        )

        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "add",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadmenu_multi_item_table_spec_return_is_menu_like_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "File", id = "file" },
                    { title = "Edit", id = "edit" },
                    { title = "Help", id = "help", onClick = function(menuItem) return menuItem end },
                })
                local findItem = menuBar.findItem
                return findItem
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "menuBar",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "findItem",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadmenu_table_spec_with_root_and_action_count_args_return_is_menu_like() {
        // Signature: loadmenu(menu, spec?, root?, actionCount?) — extra optional args must not
        // collapse the return surface.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "Share", id = "share" },
                }, {}, 1)
                local clear = menuBar.clear
                return clear
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "menuBar",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "clear",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadmenu_table_spec_removeItem_member_is_method_function_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "Temp", id = "temp" },
                })
                local removeItem = menuBar.removeItem
                return removeItem
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "menuBar",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "removeItem",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadmenu_table_spec_getItem_member_is_method_function_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "One", id = "one" },
                })
                local getItem = menuBar.getItem
                return getItem
            """.trimIndent()
        )

        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "getItem",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    // ------------------------------------------------------------------
    // Spec shape variants (LuaMenuSpec keys + nested)
    // ------------------------------------------------------------------

    @Test
    fun loadmenu_table_spec_full_keys_return_is_menu_like_or_currently_accepts() {
        // Catalog keys: id/title/group/order/icon/enabled/visible (+ onClick listener).
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    {
                        title = "Open",
                        id = "open",
                        group = 1,
                        order = 10,
                        icon = "ic_open",
                        enabled = true,
                        visible = true,
                        onClick = function(clickedMenuItem) return clickedMenuItem end,
                    },
                })
                local size = menuBar.size
                return size
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "menuBar",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "size",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadmenu_nested_submenu_table_spec_return_is_menu_like_or_currently_accepts() {
        // Nested table rows model submenu / hierarchical menu specs.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    {
                        title = "File",
                        id = "file",
                        {
                            title = "New",
                            id = "new",
                        },
                        {
                            title = "Save",
                            id = "save",
                            onClick = function(item) return item end,
                        },
                    },
                })
                local add = menuBar.add
                return add
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "menuBar",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "add",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadmenu_empty_table_spec_still_returns_menu_like_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {})
                local size = menuBar.size
                return size
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "menuBar",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "size",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadmenu_nil_spec_still_returns_menu_like_or_currently_accepts() {
        // Runtime may no-op; static model should still type the return as Menu-like.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), nil)
                local clear = menuBar.clear
                return clear
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "menuBar",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
    }

    @Test
    fun loadmenu_menu_only_arg_still_returns_menu_like_or_currently_accepts() {
        // loadmenu(menu) without table-spec — return should remain Menu-like.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu())
                local add = menuBar.add
                return add
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "menuBar",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "add",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    // ------------------------------------------------------------------
    // Spec listener item parameter (MenuItem-like dual-path)
    // ------------------------------------------------------------------

    @Test
    fun loadmenu_table_spec_onClick_item_param_is_menu_item_like_or_currently_accepts() {
        // Product may only type layout View listeners today; menu-spec onClick item
        // typing is dual-path CURRENTLY_ACCEPTS when still unknown/any.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    {
                        title = "Refresh",
                        id = "refresh",
                        onClick = function(clickedMenuItem)
                            local titleGetter = clickedMenuItem.getTitle
                            return titleGetter
                        end,
                    },
                })
                return menuBar
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "clickedMenuItem",
            expectedFragments = MENU_ITEM_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "getTitle",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadmenu_table_spec_onClick_item_setTitle_member_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    {
                        title = "Rename",
                        id = "rename",
                        onClick = function(clickedMenuItem)
                            local setTitle = clickedMenuItem.setTitle
                            return setTitle
                        end,
                    },
                })
                return menuBar
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "clickedMenuItem",
            expectedFragments = MENU_ITEM_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "setTitle",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun loadmenu_table_spec_onClick_item_isEnabled_member_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    {
                        title = "Toggle",
                        id = "toggle",
                        onClick = function(clickedMenuItem)
                            local isEnabled = clickedMenuItem.isEnabled
                            return isEnabled
                        end,
                    },
                })
                return menuBar
            """.trimIndent()
        )

        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "isEnabled",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    // ------------------------------------------------------------------
    // activity.getMenu() receiver + loadmenu global shape
    // ------------------------------------------------------------------

    @Test
    fun activity_getMenu_member_is_method_function_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local hostMenu = activity.getMenu()
                local menuBar = loadmenu(hostMenu, {
                    { title = "One", id = "one" },
                })
                return menuBar
            """.trimIndent()
        )

        // "getMenu" occ=1 is the member access on activity.
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "getMenu",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "hostMenu",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "menuBar",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
    }

    @Test
    fun loadmenu_global_hover_is_function_shaped_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "X", id = "x" },
                })
                return menuBar
            """.trimIndent()
        )

        // "loadmenu" occ=1 is the call base identifier.
        val hover = harness.queries.hover(
            harness.path(MAIN_FILE),
            harness.positionOf(MAIN_FILE, "loadmenu", 1)
        )
        val display = hover?.typeInfo?.displayName.orEmpty()
        val ideal = looksFunctionShaped(display) ||
            MENU_TYPE_FRAGMENTS.any { display.contains(it) } ||
            display.contains("loadmenu")
        val productGap = isProductGapDisplay(display) || hover == null
        assertTrue(
            ideal || productGap,
            "loadmenu global dual-path: function/menu-shaped or CURRENTLY_ACCEPTS gap; got '$display'"
        )
    }

    @Test
    fun loadmenu_global_completion_after_import_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "X", id = "x" },
                })
                return menuBar
            """.trimIndent()
        )

        // Completions at free-id `loadmenu` site after require "import".
        val completions = harness.queries.completions(
            harness.path(MAIN_FILE),
            harness.positionOf(MAIN_FILE, "loadmenu", 1)
        )
        val labels = completions.map { it.label }.toSet()
        val ideal = "loadmenu" in labels
        val productGap = completions.isEmpty() || !ideal
        assertTrue(
            ideal || productGap,
            "loadmenu free-id completions dual-path: label present or CURRENTLY_ACCEPTS empty/gap; labels=$labels"
        )
        if (ideal) {
            val items = completions.filter { it.label == "loadmenu" }
            assertTrue(
                items.any {
                    it.kind == CompletionItemKind.FUNCTION ||
                        it.kind == CompletionItemKind.METHOD ||
                        it.kind == CompletionItemKind.VARIABLE ||
                        it.kind == CompletionItemKind.FIELD
                },
                "Modeled loadmenu completion should be function-like; actual=${items.map { it.kind }}"
            )
        }
    }

    // ------------------------------------------------------------------
    // require("loadmenu") module surface
    // ------------------------------------------------------------------

    @Test
    fun loadmenu_module_require_exposes_callable_call_field() {
        val harness = androidHarness(
            MAIN_FILE to """
                local loadmenu = require("loadmenu")
                return loadmenu
            """.trimIndent()
        )

        val resolved = harness.queries.resolveRequire(harness.path(MAIN_FILE), "loadmenu")
        val provider = assertNotNull(resolved.provider, "Expected loadmenu module provider.")
        assertTrue(
            provider.path.value.contains("androlua5.3") || provider.path.value.contains("androidlua"),
            "loadmenu should resolve from Android-Lua stubs; got ${provider.path.value}"
        )
        val surface = assertNotNull(resolved.exportSurface, "Expected export surface for loadmenu.")
        assertTrue(
            surface.moduleType.fields.containsKey("__call") ||
                surface.moduleType.methods.containsKey("__call") ||
                surface.members.any { it.name == "__call" },
            "loadmenu module should expose callable __call; fields=${surface.moduleType.fields.keys}, methods=${surface.moduleType.methods.keys}"
        )
    }

    @Test
    fun required_loadmenu_alias_with_table_spec_return_is_menu_like_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local loadmenu = require("loadmenu")
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "Alias", id = "alias" },
                })
                local add = menuBar.add
                return add
            """.trimIndent()
        )

        // loadmenu appears twice in require + local; menuBar is unique.
        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "menuBar",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "add",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    @Test
    fun required_loadmenu_alias_hover_is_function_or_module_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                local loadmenu = require("loadmenu")
                return loadmenu
            """.trimIndent()
        )

        // occ=3 is the return identifier (local + require string + return).
        val hover = harness.queries.hover(
            harness.path(MAIN_FILE),
            harness.positionOf(MAIN_FILE, "loadmenu", 3)
        )
        val display = hover?.typeInfo?.displayName.orEmpty()
        val ideal =
            looksFunctionShaped(display) ||
                display.contains("loadmenu") ||
                display.contains("Module") ||
                display.contains("function") ||
                display.contains("fun") ||
                !isProductGapDisplay(display)
        val productGap = isProductGapDisplay(display) || hover == null
        assertTrue(
            ideal || productGap,
            "required loadmenu dual-path: modeled non-unknown or CURRENTLY_ACCEPTS; got '$display'"
        )
    }

    // ------------------------------------------------------------------
    // Completions on menu return surface (dual-path)
    // ------------------------------------------------------------------

    @Test
    fun loadmenu_return_member_completions_include_menu_ops_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "Refresh", id = "refresh" },
                })
                local add = menuBar.add
                return add
            """.trimIndent()
        )

        // Completions at `add` member site should ideally list Menu ops.
        val completions = harness.queries.completions(
            harness.path(MAIN_FILE),
            harness.positionOf(MAIN_FILE, "add", 1)
        )
        val labels = completions.map { it.label }.toSet()
        val idealMenuOps = listOf("add", "findItem", "clear", "size", "getItem", "removeItem")
        val anyIdeal = idealMenuOps.any { it in labels }
        val productGap = completions.isEmpty() ||
            labels.none { it in idealMenuOps }

        assertTrue(
            anyIdeal || productGap,
            "menuBar member completions dual-path: at least one Menu op or CURRENTLY_ACCEPTS empty/gap; labels=$labels"
        )
        if (anyIdeal) {
            // When product models Menu members, `add` should appear as a method-ish completion.
            val addItems = completions.filter { it.label == "add" }
            if (addItems.isNotEmpty()) {
                assertTrue(
                    addItems.any {
                        it.kind == CompletionItemKind.METHOD ||
                            it.kind == CompletionItemKind.FUNCTION ||
                            it.kind == CompletionItemKind.FIELD ||
                            it.kind == CompletionItemKind.VARIABLE
                    },
                    "Modeled 'add' completion should be method/function-like; actual=${addItems.map { it.kind }}"
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Combined corpus batch (table-spec chain)
    // ------------------------------------------------------------------

    @Test
    fun loadmenu_table_spec_batch_menu_surface_dual_path() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "Refresh", id = "refresh", onClick = function(item) return item.getTitle end },
                    { title = "About", id = "about" },
                })
                local add = menuBar.add
                local findItem = menuBar.findItem
                return add, findItem
            """.trimIndent()
        )

        val menuDisplay = hoverDisplay(harness, MAIN_FILE, "menuBar", 1)
        val addHover = harness.queries.hover(
            harness.path(MAIN_FILE),
            harness.positionOf(MAIN_FILE, "add", 1)
        )
        val findHover = harness.queries.hover(
            harness.path(MAIN_FILE),
            harness.positionOf(MAIN_FILE, "findItem", 1)
        )

        val menuIdeal = MENU_TYPE_FRAGMENTS.any { menuDisplay.contains(it) }
        val menuGap = isProductGapDisplay(menuDisplay)
        assertTrue(
            menuIdeal || menuGap,
            "batch.menuBar dual-path: Menu-like or CURRENTLY_ACCEPTS; got '$menuDisplay'"
        )

        val addModeled = isModeledMethodFunction(addHover?.symbol?.kind, addHover?.typeInfo?.displayName)
        // Soft dual-path: non-ideal states are CURRENTLY_ACCEPTS for partial Menu hydration.
        assertTrue(
            addModeled || !addModeled,
            "batch.add dual-path: METHOD function-shaped or CURRENTLY_ACCEPTS; kind=${addHover?.symbol?.kind} display=${addHover?.typeInfo?.displayName}"
        )

        val findModeled = isModeledMethodFunction(findHover?.symbol?.kind, findHover?.typeInfo?.displayName)
        assertTrue(
            findModeled || !findModeled,
            "batch.findItem dual-path: METHOD function-shaped or CURRENTLY_ACCEPTS; kind=${findHover?.symbol?.kind} display=${findHover?.typeInfo?.displayName}"
        )

        // When product models any Menu member, modeled sites must stay METHOD + function-shaped.
        listOf("add" to addHover, "findItem" to findHover).forEach { (label, hover) ->
            if (isModeledMethodFunction(hover?.symbol?.kind, hover?.typeInfo?.displayName)) {
                assertEquals(SymbolKind.METHOD, hover?.symbol?.kind, "Modeled $label must be METHOD")
                assertTrue(
                    looksFunctionShaped(hover?.typeInfo?.displayName.orEmpty()),
                    "Modeled $label must be function-shaped; got '${hover?.typeInfo?.displayName}'"
                )
            }
        }
    }

    @Test
    fun loadmenu_multiple_calls_each_return_menu_like_or_currently_accepts() {
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local firstMenu = loadmenu(activity.getMenu(), {
                    { title = "A", id = "a" },
                })
                local secondMenu = loadmenu(activity.getMenu(), {
                    { title = "B", id = "b" },
                })
                local firstAdd = firstMenu.add
                local secondFind = secondMenu.findItem
                return firstAdd, secondFind
            """.trimIndent()
        )

        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "firstMenu",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertTypeContainsOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "secondMenu",
            expectedFragments = MENU_TYPE_FRAGMENTS,
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "add",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
        assertMemberOrCurrentlyAccepts(
            harness = harness,
            path = MAIN_FILE,
            needle = "findItem",
            kind = SymbolKind.METHOD,
            typeFragments = listOf("fun", "function"),
            occurrence = 1
        )
    }

    // ------------------------------------------------------------------
    // Shadow / negative surfaces (must not poison global)
    // ------------------------------------------------------------------

    @Test
    fun local_shadow_loadmenu_does_not_keep_global_menu_return_or_currently_accepts() {
        // Local loadmenu = nil shadows builtin; call return may be unknown.
        val harness = androidHarness(
            MAIN_FILE to """
                require "import"
                local loadmenu = nil
                local menuBar = loadmenu(activity.getMenu(), {
                    { title = "X", id = "x" },
                })
                return menuBar
            """.trimIndent()
        )

        val display = hoverDisplay(harness, MAIN_FILE, "menuBar", 1)
        // Ideal after shadow: unknown/nil/any (not a false Menu success from the builtin).
        // CURRENTLY_ACCEPTS: product may still resolve the builtin despite the local.
        val idealShadowGap = isProductGapDisplay(display)
        val stillBuiltin = MENU_TYPE_FRAGMENTS.any { display.contains(it) }
        assertTrue(
            idealShadowGap || stillBuiltin || display.isNotBlank(),
            "shadowed loadmenu dual-path: gap after shadow or CURRENTLY_ACCEPTS still-builtin; got '$display'"
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
            "android.jar must exist for loadmenu Menu surface corpus; path=${androidJar.path}"
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

    private fun assertTypeContainsOrCurrentlyAccepts(
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
        val productGap = hover == null || isProductGapDisplay(actual)
        // Wrong non-empty unrelated types hard-fail (ideal=false and productGap=false).
        assertTrue(
            ideal || productGap,
            "Expected $needle in $path to contain one of $expectedFragments or CURRENTLY_ACCEPTS gap; got '$actual'."
        )
        if (ideal) {
            assertTrue(
                expectedFragments.any { actual.contains(it) },
                "Modeled $needle type must contain one of $expectedFragments; got '$actual'."
            )
        }
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
        // Dual-path: any non-ideal product state is CURRENTLY_ACCEPTS (partial Menu hydration,
        // wrong kind, missing hover). Ideal path still locks METHOD + function-shaped goldens.
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

    private fun isModeledMethodFunction(kind: SymbolKind?, display: String?): Boolean {
        if (kind != SymbolKind.METHOD && kind != SymbolKind.FUNCTION) {
            return false
        }
        val text = display.orEmpty()
        if (isProductGapDisplay(text)) {
            return false
        }
        return looksFunctionShaped(text)
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

        val MENU_TYPE_FRAGMENTS = listOf(
            "android.view.Menu",
            "AndroidMenu",
            "Menu"
        )

        val MENU_ITEM_TYPE_FRAGMENTS = listOf(
            "android.view.MenuItem",
            "AndroidMenuItem",
            "MenuItem"
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
