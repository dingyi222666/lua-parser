# Android-Lua LSP E2E fixture catalog (TASK-170 / TASK-521)

Repository-local fixtures for `lsp.LspAndroidLuaE2eTddTest`. No machine-local
Android-Lua source tree is required.

Provider-surface cases are powered by reflective JVM class providers under
`__jvm__/classes/...` when a host `android.jar` is present (resolved via
`/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar`,
`/Users/dingyi/Downloads/android.jar`,
`JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH`, or
`ANDROID_HOME` / `ANDROID_SDK_ROOT`). Pure-Lua parser, symbol, and semantic-
boundary cases run without that jar. When the jar is missing, provider cases
skip with an explicit TASK-170/TASK-521 reason rather than claiming green
navigation against non-existent surfaces. Never hardcode `G:/`.

Framework models under `android-framework/` still seed overlay types for
View/TextView members when reflection is not mounted; provider *definition*
URIs require the reflective jar path.

Path contract matches `LspJavaAndroidFeatureTddTest` /
`LspAndroidLuaE2eActivityStubTddTest`: synthetic `workspaceFolders =
file:///workspace`, open path `workspace/<file>`, URI `file:///$path`.
TASK-521 multi-doc regression locks assert this contract remains stable when
main/layout/details are open together (definition/hover/completion for
TextView, Context, and OnClickListener).

## Boundary map

| Boundary | Fixture | Expected LSP surface |
| --- | --- | --- |
| Parser diagnostics | `broken_activity.lua` | Non-empty `lua-parse` error diagnostics (missing-RHS recovery) |
| Semantic diagnostics (runtime-only) | `runtime_only.lua` | Parses cleanly; with no-runtime sentinel classpath publishes `checker.luajava.target.unresolved` for reflective `luajava.bindClass` / `createProxy` / `loadLib` targets |
| Java/Android provider surfaces (reflective) | `main_activity.lua`, `layout_screen.lua`, `android_layout.aly`, `details_fragment.lua` | Navigation/hover/completion against reflective providers after `import "..."` / `require "import"` when host `android.jar` is present |
| Multi-doc provider regression (TASK-521) | same provider fixtures co-opened | TextView/Context/OnClickListener definition/hover/completion + workspace `attach` symbol keep `workspace/` URIs and `__jvm__/classes/...` provider URIs under `workspaceFolders` |
| Pure-Lua document/workspace symbols | same fixtures | Locals/functions without needing `android.jar` |
| Unsupported runtime-only behavior | (documented here; not asserted as green) | Dex-path imports; framework workspace-symbol indexing without reflective providers; loadlayout id-table member expansion (TASK-184) |

## Configuration sentinel

- `no-android-runtime.jar` path string (see test) is configuration metadata only.
- It is **not** shipped as a binary jar and must stay repository-relative.
- When set as `jvm.androidJar`, it **blocks** well-known SDK discovery so the
  runtime-only semantic boundary stays deterministic on machines that also have
  a real Android SDK installed.
- Android class members for provider cases come from reflective loads of the
  host `android.jar`, not from that sentinel.

## Fixture inventory

### `main_activity.lua`
- Clean explicit-string imports for Activity/Context/View/TextView/Button.
- Exercises TextView/Context/View provider definition, hover, member completion,
  document symbols, and local references (provider cases skip without jar).
- TASK-521 multi-doc co-open partner for details/layout provider locks.
- Single-value return (avoids multi-identifier retstat parse residuals).
- No `---@type` / `---@return` overrides on constructed TextView locals (preserves
  member surface and avoids false-positive return typeMismatch).
- Must not emit `lua-parse` or unresolved LuaJava diagnostics under pure/no-runtime.

### `layout_screen.lua`
- Explicit widget imports + underscore inner-class import (`View_OnClickListener`).
- loadlayout + ids table locals; listener table; document symbols for layout locals.
- TASK-521 multi-doc OnClickListener definition/hover + TextView completion lock.
- Id-table *member* completion is a library-stub surface (TASK-184); E2E asserts
  symbols + View-like/JavaObject hover without claiming empty id completion as success.

### `android_layout.aly`
- Minimal `.aly` layout table returning a LinearLayout/TextView/Button tree.
- Parser clean open + document symbol `layout`.

### `details_fragment.lua`
- Cross-file workspace symbol (`attach`) and TextView/Context references companion.
- TASK-521 multi-doc partner: provider definition/hover for TextView and Context
  after co-open with main/layout under workspaceFolders.

### `broken_activity.lua`
- Intentionally invalid syntax: missing name after `local` (`local =`), which emits
  product-current `lua-parse` recovery diagnostics.
- Note: `local broken =\nreturn` recovers without a recovery diagnostic today; do not
  use that shape for the parser boundary.
- Parser diagnostic boundary only.

### `runtime_only.lua`
- Valid Lua that only uses `luajava.bindClass` / `createProxy` / `loadLib` against
  targets that require reflective classpath resolution.
- Distinguishes semantic unresolved-target diagnostics from parser errors when
  the no-runtime sentinel is configured.

## Non-goals (do not hide regressions)

- Do not assert empty diagnostics for runtime-only LuaJava loads under the
  no-runtime sentinel.
- Do not invent non-reflective Android framework class models as a substitute
  for provider definition URIs; provider cases skip cleanly when `android.jar`
  is absent (TASK-170/TASK-521 skip reason).
- Do not index framework providers as workspace symbols without reflective
  providers mounted.
- Do not treat missing loadlayout id-member completion as a passing product claim.
- Do not empty `workspaceFolders` or drop the `workspace/` path prefix (WAVE34
  multi-doc collapse pitfall).
- Do not depend on machine-local Android-Lua checkouts or `G:/` paths.
