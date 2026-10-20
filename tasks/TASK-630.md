id: TASK-630
title: Windows multi-interface createProxy member resolution product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/model/ReferenceQueries.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm
  - src/jvmTest/kotlin/lsp/LuaLanguageServiceTest.kt
acceptance_criteria:
  - Clear Windows slice failure (evidence run 29194477445 / WINSLICE-29194477445 / slice s006):
    - lsp.LuaLanguageServiceTest#language_service_resolves_multi_interface_create_proxy_helpers
  - Observed Windows failure: for `local proxy = luajava.createProxy("java.lang.Runnable", "java.util.Comparator", {})` then `proxy.compare`, definition URI expected `file:///__jvm__/classes/java/util/Comparator.lua` but was `file:///__jvm__/classes/java/lang/Runnable.lua` (ComparisonFailure at LuaLanguageServiceTest.kt:983).
  - Product multi-interface createProxy resolution must pick the interface branch that actually declares the accessed member (`compare` → Comparator, not first interface Runnable); declaration/references/hover for the same member must land on Comparator virtual path and hover must mention `compare`.
  - Prefer product fix in ReferenceQueries / ExpressionTypeEvaluator multi-interface branch selection (member-owning branch); no CURRENTLY_ACCEPTS weakening of hard-lock asserts; single-interface createProxy helpers must not regress.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s006: `./gradlew.bat jvmTest --tests lsp.LuaLanguageServiceTest.language_service_resolves_multi_interface_create_proxy_helpers`
notes:
  - Windows slice s006 run 29194477445: 3 reds; this task owns the single multi-interface createProxy helper method.
  - Related single/re-aliased createProxy helpers already green in same suite; gap is multi-interface member-owning branch.
  - Serialize exclusive claim on ReferenceQueries.kt / ExpressionTypeEvaluator.kt vs overlapping LuaJava product tasks.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-630.lock
  - locks/files/tasks__TASK-630.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__model__ReferenceQueries.kt.lock
related_commits: []
progress:
  - 2026-07-12T14:21:30Z worker-WINSLICE-29194477445-TASK-630: product fix for multi-interface createProxy member-owning branch (proxy.compare -> Comparator); QueryFacade + ReferenceQueries; status=review.
  - 2026-07-12T13:49:56Z worker-WINSLICE-29194477445-TASK-630: claim in_progress; investigate multi-interface createProxy member-owning branch for proxy.compare -> Comparator.
  - 2026-07-12T materialize WINSLICE-29194477445: created ready product fix for multi-interface createProxy member resolution red.
