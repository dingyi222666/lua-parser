id: TASK-670
title: Windows LuaWorkspaceQueryFacade signature help preserves generic type-parameter labels
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/model/SignatureHelpProvider.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/types/bridges/ModelToLegacyTypeBridge.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/checker/ExpressionTypeEvaluator.kt
  - src/jvmTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29213080707 / WINSLICE-29213080707; gate retains prior reds from 29212217687 after compileFailure blocked jvmTest):
    - semantic.workspace.LuaWorkspaceQueryFacadeTest#signature_help_preserves_generic_type_parameter_labels_after_type_resolution[jvm]
  - Observed Windows ComparisonFailure: expected `fun[<T>](value: T): T` but was `fun[](value: T): T` — generic type-parameter labels dropped after type resolution.
  - Product signature help must preserve generic type-parameter labels (e.g. `<T>`) after type resolution for workspace query surfaces.
  - Prefer SignatureHelpProvider / type-bridge / facade product fix; no CURRENTLY_ACCEPTS weaken.
  - Shared hot file LuaWorkspaceQueryFacade — honor locks; leave ready if blocked.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.LuaWorkspaceQueryFacadeTest.signature_help_preserves_generic_type_parameter_labels_after_type_resolution`
notes:
  - Windows slice s017 run 29213080707: compileKotlinJvm failed before jvmTest (LuaWorkspaceQueryFacade.kt:1011 return-type mismatch); no TEST-*.xml. Gate retains prior red from 29212217687.
  - Related overlay generic red is TASK-668 (pairs detail); this is query-facade signature label preservation.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-670.lock
  - locks/files/tasks__TASK-670.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix for signature-help generic type-parameter labels.
  - 2026-07-12T21:58:00Z worker-WINSLICE-29210211230-TASK-670: claim in_progress; preserve generic type-parameter labels in signature help after type resolution.
  - 2026-07-12T22:00:00Z worker-WINSLICE-29210211230-TASK-670: product fix SignatureHelpProvider preserve/prefer generic typeParameters for fun<T> labels; status=review.
  - 2026-07-13T materialize WINSLICE-29212217687: still red; reset review→ready; reuse for product re-fix.
  - 2026-07-12T22:56:39Z worker-WINSLICE-29212217687-TASK-670: claim in_progress; preserve generic type-parameter labels in signature help after type resolution.
  - 2026-07-12T23:13:29Z worker-WINSLICE-29212217687-TASK-670: product re-fix SignatureHelpProvider final-label recovery (declaredType/owned/doc tags) + ExpressionTypeEvaluator merge/evaluateFunctionDeclaration keep typeParameters; status=review.
  - 2026-07-13T materialize WINSLICE-29213080707: compileFailure blocked tests; reset review→ready for retained red + evidence 29213080707.
