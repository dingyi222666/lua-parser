id: TASK-654
title: Windows binder multi-LHS bare assignment invents GLOBAL per name
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/binder/DeclarationBinder.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/binder/BinderPass.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/binder/DeclarationFactories.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/binder/SymbolTableBuilder.kt
acceptance_criteria:
  - Clear Windows slice s013 failures (evidence run 29205436079 / WINSLICE-29205436079):
    - semantic.binder.BinderGlobalAssignmentDeclarationTddTest#multiLhsBareAssignment_createsGlobalPerBareIdentifier[jvm]
    - semantic.binder.BinderGlobalAssignmentDeclarationTddTest#unbalancedMultiLhsBareAssignment_stillCreatesGlobalsPerName[jvm]
    - semantic.binder.BinderGlobalAssignmentRangeTddTest#multiLineMultiLhsBareAssignment_eachNameDisjointCommaNotARange[jvm]
    - semantic.binder.BinderGlobalAssignmentRangeTddTest#unbalancedMultiLhsBareAssignment_eachNameHasDeclarationRange[jvm]
    - semantic.binder.BinderGlobalAssignmentRangeTddTest#multiLhsBareAssignment_eachNameHasDisjointIdentifierRange[jvm]
    - semantic.binder.BinderGlobalAssignmentRangeTddTest#multiLhsBareAssignment_usesDisjointIdentifierRanges[jvm]
  - Observed Windows AssertionError:
    - Expected GLOBAL declaration for 'alpha' / 'u' on sources `alpha, beta = 1, 2`, `u, v, w = 1`, multi-line multi-LHS, and `left, right = 1, 2` (expected count 1 was 0).
  - Product binder must invent AST GLOBAL DeclarationIndex entries for each bare free-name multi-LHS identifier (including unbalanced multi-LHS), with identifier-only ranges, disjoint across names; commas invent no ranges; positionQueries resolve each name to its GLOBAL decl.
  - Single bare free-name invent policy stays green. Prefer restoring multi-LHS invent in visitAssignmentStatement / bindBareGlobalAssignmentTarget (TASK-558 lineage) over CURRENTLY_ACCEPTS or weakening hard-locks on BinderGlobalAssignment*.
  - Note: TASK-653 restricted invent to single bare LHS only (made BinderMultiAssignRangeTddTest multi-assign non-declarative green). Product goal path is declarative multi-LHS GLOBAL (TASK-558). If BinderMultiAssignRange non-declarative multi-assign hardlocks regress, dual-path or align those corpora in the same exclusive binder claim — do not leave GlobalAssignment multi-LHS red.
  - Serialize exclusive binder product files vs other semantic binder tasks (TASK-653/558).
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s013: `./gradlew.bat jvmTest --tests semantic.binder.BinderGlobalAssignmentDeclarationTddTest.multiLhsBareAssignment_createsGlobalPerBareIdentifier --tests semantic.binder.BinderGlobalAssignmentDeclarationTddTest.unbalancedMultiLhsBareAssignment_stillCreatesGlobalsPerName --tests semantic.binder.BinderGlobalAssignmentRangeTddTest.multiLhsBareAssignment_eachNameHasDisjointIdentifierRange --tests semantic.binder.BinderGlobalAssignmentRangeTddTest.unbalancedMultiLhsBareAssignment_eachNameHasDeclarationRange --tests semantic.binder.BinderGlobalAssignmentRangeTddTest.multiLineMultiLhsBareAssignment_eachNameDisjointCommaNotARange --tests semantic.binder.BinderGlobalAssignmentRangeTddTest.multiLhsBareAssignment_usesDisjointIdentifierRanges`
notes:
  - Windows slice s013 run 29205436079: 6 reds clustered on multi-LHS bare free-name assignment missing GLOBAL invent/ranges after TASK-653 single-LHS-only invent policy. BinderMultiAssignRangeTddTest 18/18 green on this run.
  - Lineage: TASK-558 declarative GLOBAL on bare free-name (incl multi-LHS); TASK-653 opposite MultiAssign non-invent AC now green but regressed GlobalAssignment multi-LHS. Do not reuse TASK-653 for this (its MultiAssign methods are not red).
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-654.lock
  - locks/files/tasks__TASK-654.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__binder__DeclarationBinder.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29205436079: created ready product fix for BinderGlobalAssignment* multi-LHS bare invent/ranges (6 methods). TASK-653 not reused (MultiAssign green).
  - 2026-07-12T19:23:23Z worker-WINSLICE-29205436079-TASK-654: claim in_progress; restore multi-LHS bare free-name GLOBAL invent (TASK-558), align MultiAssign hardlocks if needed.
  - 2026-07-12T19:25:00Z worker-WINSLICE-29205436079-TASK-654: product fix visitAssignmentStatement — invent AST GLOBAL for each bare free-name LHS (multi-LHS + unbalanced); aligned BinderMultiAssignRange multi-assign hardlocks to declarative GLOBAL. status->review. No Gradle.
