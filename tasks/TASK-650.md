id: TASK-650
title: Windows SemanticPipeline clean analyze diagnosticCount zero product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/SemanticPipeline.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/CheckerPass.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/model/SemanticAnalysisResult.kt
acceptance_criteria:
  - Clear Windows slice s011 failures (evidence run 29202408385 / WINSLICE-29202408385):
    - semantic.SemanticPipelinePublicFacadeTddTest#analyzeFacadeIsCallableRepeatedlyOnSamePipelineInstance[jvm]
    - semantic.SemanticPipelineTest#analyze_returnsSemanticAnalysisResultBackedByRealPipeline[jvm]
  - Observed Windows AssertionError on both: expected:<0> but was:<1> for `result.summary.diagnosticCount`
    on trivial well-formed sources (`local a = 1` / `local b = 2` / `local value = 1`).
  - Product SemanticPipeline.analyze for well-formed local assignments with no unused/type/call issues must
    report diagnosticCount == 0 (and model.getDiagnostics empty or non-error noise filtered consistently
    with summary). Reusing the same pipeline instance must not accumulate diagnostics across analyzes.
  - Prefer product checker/summary policy (suppress false-positive unused/global on simple locals; reset
    pipeline state between analyze calls) over weakening facade hard-locks. Keep real diagnostics for
    intentional error fixtures.
  - Serialize exclusive semantic product files vs other semantic tasks.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s011: `./gradlew.bat jvmTest --tests semantic.SemanticPipelinePublicFacadeTddTest.analyzeFacadeIsCallableRepeatedlyOnSamePipelineInstance --tests semantic.SemanticPipelineTest.analyze_returnsSemanticAnalysisResultBackedByRealPipeline`
notes:
  - Windows slice s011 run 29202408385: 2 reds clustered on clean-analyze diagnosticCount==0.
  - Facade expects 0 diagnostics on `local a = 1` / `local b = 2`; commonTest SemanticPipelineTest same for `local value = 1`.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-650.lock
  - locks/files/tasks__TASK-650.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__SemanticPipeline.kt.lock
related_commits: []
progress:
  - 2026-07-12T17:53:20Z worker-WINSLICE-29202408385-TASK-650: product fix for clean-analyze diagnosticCount 0. Root cause: ExpressionUsageChecker emits unused-local WARNING on binding-only snippets (`local a = 1`). CheckerPass now suppresses checker.local.unused when chunk is binding-only locals (no return / non-local statements); intentional unused fixtures with return keep emission. SemanticPipeline documents per-analyze independence and summary mirrors model diagnostics. status=review owner=unassigned. Workers no Gradle.
  - 2026-07-13T materialize WINSLICE-29202408385: created ready product fix for SemanticPipeline clean-analyze diagnosticCount 0 (2 methods).
  - 2026-07-12T17:46:33Z claim TASK-650 in_progress; investigating clean-analyze diagnosticCount false positive
