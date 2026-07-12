id: TASK-622
title: Windows LSP definition nested block local over parameter product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic
  - src/jvmTest/kotlin/lsp/LspDefinitionMultiLocalShadowTddTest.kt
acceptance_criteria:
  - Clear Windows slice failure (evidence run 29191587333 / WINSLICE-29191587333 / slice s003):
    - lsp.LspDefinitionMultiLocalShadowTddTest#definition_nested_block_inside_function_prefers_block_local_over_parameter
  - Observed failure: block-local pick use expected same-file hit on line 3 (block `local name = 2`) but got parameter range `@1:22-1:26` on `local function render(name)`.
  - Product gotoDefinition for use of `name` inside nested `do` block must prefer the block-local binding over the enclosing function parameter; use after the block must still resolve to the parameter (not the outer chunk local).
  - Prefer product binder/scope/definition fix; no CURRENTLY_ACCEPTS weakening of hard-lock asserts; never invent provider virtual paths for plain locals.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s003: `./gradlew.bat jvmTest --tests lsp.LspDefinitionMultiLocalShadowTddTest`
notes:
  - Windows slice s003 run 29191587333: 12 reds; this task owns the single LspDefinitionMultiLocalShadow method.
  - Distinct from test-only TASK-356 (done corpus); this is product resolution fix.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-622.lock
  - locks/files/tasks__TASK-622.md.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__lsp__LuaLanguageService.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29191587333: created ready product fix for definition nested block local over parameter red.
  - 2026-07-12T11:57:57Z: worker-WINSLICE-29191587333-TASK-622 claimed; investigating nested block local vs parameter definition resolution.
  - 2026-07-12T12:05:00Z: worker-WINSLICE-29191587333-TASK-622 product fix in ReferenceQueries: parent-chain walk keeps innermost VALUE binding; PARAMETER kind-rank no longer beats nested block LOCAL. status=review.
