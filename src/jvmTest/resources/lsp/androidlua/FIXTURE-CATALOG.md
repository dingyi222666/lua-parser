# Android-Lua LSP E2E fixture catalog (TASK-170)

Repository-local fixtures for `lsp.LspAndroidLuaE2eTddTest`. No machine-local
Android SDK checkout, `android.jar` host path, or Android-Lua source tree is required.

## Boundary map

| Boundary | Fixture | Expected LSP surface |
| --- | --- | --- |
| Parser diagnostics | `broken_activity.lua` | Non-empty `lua-parse` error diagnostics |
| Semantic diagnostics (runtime-only) | `runtime_only.lua` | Parses cleanly; publishes `checker.luajava.target.unresolved` for reflective `luajava.bindClass` / `createProxy` / `loadLib` targets that need a real JVM classpath |
| Java/Android provider surfaces | `main_activity.lua`, `layout_screen.lua`, `android_layout.aly`, `details_fragment.lua` | Navigation/hover/completion against repository Android framework overlay providers (`file:///__jvm__/classes/...`) after `import` / `require "import"` |
| Unsupported runtime-only behavior | (documented here; not asserted as green) | Host SDK reflection, dex-path imports, full platform-jar workspace-symbol indexing of framework classes without opened workspace files |

## Configuration sentinel

- `no-android-runtime.jar` path string (see test) is configuration metadata only.
- It is **not** shipped as a binary jar and must stay repository-relative.
- Android class members come from ANDROLUA_5_3 framework models, not reflection of that sentinel.

## Fixture inventory

### `main_activity.lua`
- Clean import-table Android activity-shaped script.
- Exercises TextView/Context/View provider definition, hover, member completion, document symbols, and local references.
- Must not emit `lua-parse` or unresolved LuaJava diagnostics.

### `layout_screen.lua`
- Wildcard widget import + underscore inner-class import (`View_OnClickListener`).
- loadlayout + ids table locals; listener table; document symbols for layout locals.
- Id-table *member* completion is a library-stub surface (TASK-184); E2E asserts symbols + View-like hover without claiming empty id completion as success.

### `android_layout.aly`
- Minimal `.aly` layout table returning a LinearLayout/TextView/Button tree.
- Parser/provider clean open + document symbol `layout`.

### `details_fragment.lua`
- Cross-file workspace symbol (`attach`) and TextView references companion.

### `broken_activity.lua`
- Intentionally invalid syntax after a valid import.
- Parser diagnostic boundary only.

### `runtime_only.lua`
- Valid Lua that only uses `luajava.bindClass` / `createProxy` / `loadLib` against targets that require reflective classpath resolution.
- Distinguishes semantic unresolved-target diagnostics from parser errors.

## Non-goals (do not hide regressions)

- Do not assert empty diagnostics for runtime-only LuaJava loads.
- Do not require a host `android.jar` for this suite.
- Do not index framework providers as workspace symbols without a reflective classpath.
- Do not treat missing loadlayout id-member completion as a passing product claim.
