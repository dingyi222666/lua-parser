id: TASK-690
title: FULL DocumentFacts init.lua module name dual VIRTUAL_PATH candidates product lock
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/DocumentFactsCollector.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/DocumentFacts.kt
  - src/commonTest/kotlin/semantic/workspace/DocumentFactsCollectorTest.kt
acceptance_criteria:
  - Clear FULL jvmTest failure (evidence run 29268552687 / WINFULL-29268552687):
    - semantic.workspace.DocumentFactsCollectorTest#derives_module_name_candidates_from_virtual_path_and_module_call[jvm]
  - Observed Windows AssertionError (run 29268552687):
    - expected `[(pkg.runtime, VIRTUAL_PATH), (pkg.runtime.override, LEGACY_MODULE_CALL)]`
    - but was `[(pkg.runtime, VIRTUAL_PATH), (pkg.runtime.init, VIRTUAL_PATH), (pkg.runtime.override, LEGACY_MODULE_CALL)]`
    - for path `pkg/runtime/init.lua` + `module("pkg.runtime.override")`.
  - Product path-derived module name candidates for `…/init.lua` must intentionally claim BOTH:
    - package root form (`pkg.runtime`) via init collapse, and
    - explicit init form (`pkg.runtime.init`) so `require("pkg.runtime.init")` / barrel-style requires resolve,
    plus LEGACY_MODULE_CALL from the `module(...)` call (`pkg.runtime.override`).
  - Prefer DocumentFactsCollector `pathDerivedModuleNameCandidates` product lock; align the unit test golden to the dual VIRTUAL_PATH + LEGACY contract (do not strip intentional `.init` claim — that would regress barrel require resolution). No CURRENTLY_ACCEPTS weaken; never invent G:/.
  - Distinct from TASK-669 (sparse numeric table import) and TASK-682 (dynamic newInstance); this owns module-name candidate derivation only.
required_tests:
  - Deferred to TASK-043 / full jvmTest run 29268552687: `./gradlew.bat jvmTest --tests semantic.workspace.DocumentFactsCollectorTest.derives_module_name_candidates_from_virtual_path_and_module_call`
notes:
  - Cluster document-facts-module-name-candidates from REVIEW-FULL-29268552687 (1 red).
  - Product already emits `pkg.runtime.init` for barrel require-ability; committed test golden lagged (expected 2-tuple only). Lock dual VIRTUAL_PATH claims + LEGACY_MODULE_CALL.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-690.lock
  - locks/files/tasks__TASK-690.md.lock
related_commits: []
progress:
  - 2026-07-14T materialize WINFULL-29268552687: created ready product/test lock for DocumentFacts init.lua dual VIRTUAL_PATH module-name candidates (evidence run 29268552687).
  - 2026-07-13T17:15:44Z worker-WINFULL-29268552687-TASK-690: claim in_progress; lock dual VIRTUAL_PATH init.lua candidates + LEGACY_MODULE_CALL test golden.
  - 2026-07-13T17:15:54Z worker-WINFULL-29268552687-TASK-690: aligned DocumentFactsCollectorTest derives_module_name_candidates golden to dual VIRTUAL_PATH (pkg.runtime + pkg.runtime.init) + LEGACY_MODULE_CALL; product pathDerivedModuleNameCandidates already claims both init forms. status=review.
