id: TASK-653
title: Windows binder multi-assign bare LHS must not invent declarations
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/binder/DeclarationBinder.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/binder/BinderPass.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/binder/DeclarationFactories.kt
  - src/jvmTest/kotlin/semantic/binder/BinderMultiAssignRangeTddTest.kt
acceptance_criteria:
  - Clear Windows slice s013 failures (evidence run 29205133158 / WINSLICE-29205133158):
    - semantic.binder.BinderMultiAssignRangeTddTest#multiAssignWithoutLocal_doesNotCreateDeclarationsForLhsNames[jvm]
    - semantic.binder.BinderMultiAssignRangeTddTest#unbalancedAssignmentMultiLhs_stillDoesNotDeclare[jvm]
  - Observed Windows AssertionError:
    - "Assignment multi-LHS must not invent declaration for 'a'" at BinderMultiAssignRangeTddTest.kt:268 for sources `a, b = 1, 2` / `x, y, z = f()`.
    - assertTrue false at BinderMultiAssignRangeTddTest.kt:284 for unbalanced `u, v, w = 1` (non-builtin declarations present for u/v/w).
  - Product binder must treat bare AssignmentStatement multi-LHS identifiers as write/use targets only: do not invent non-builtin DeclarationIndex entries (or position-query declaration hits) for names only introduced via assignment multi-LHS without `local`.
  - Keep multi-`local` declaration ranges/queryability green (other BinderMultiAssignRangeTddTest methods already pass on this Windows run). Prefer fixing `visitAssignmentStatement` / `bindBareGlobalAssignmentTarget` policy over CURRENTLY_ACCEPTS or weakening hard-locks.
  - Serialize exclusive binder product files vs other semantic binder tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s013: `./gradlew.bat jvmTest --tests semantic.binder.BinderMultiAssignRangeTddTest.multiAssignWithoutLocal_doesNotCreateDeclarationsForLhsNames --tests semantic.binder.BinderMultiAssignRangeTddTest.unbalancedAssignmentMultiLhs_stillDoesNotDeclare`
notes:
  - Windows slice s013 run 29205133158: 2 reds clustered on assignment multi-LHS inventing declarations; 16 other methods in the class passed.
  - Distinct from test-only TASK-195 (done corpus); this is product binder fix under must-green-to-advance s013.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-653.lock
  - locks/files/tasks__TASK-653.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__binder__DeclarationBinder.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29205133158: created ready product fix for BinderMultiAssignRange bare multi-assign LHS declaration invention (2 methods).
  - 2026-07-12T19:13:53Z worker-WINSLICE-29205133158-TASK-653: claim in_progress; product fix multi-LHS bare AssignmentStatement to not invent GLOBAL decls (single bare free-name invent policy retained).
  - 2026-07-12T19:14:30Z worker-WINSLICE-29205133158-TASK-653: product fix visitAssignmentStatement — invent AST GLOBAL only for single bare free-name LHS; multi-LHS bare names are write/use only (no DeclarationIndex invent). status->review. No Gradle.
