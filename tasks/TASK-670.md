id: TASK-670
title: Windows LuaWorkspaceQueryFacade signature help preserves generic type-parameter labels
status: review
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
  - Clear FULL jvmTest failure (evidence run 29227224781 / FULLJVM-29227224781):
    - semantic.workspace.LuaWorkspaceQueryFacadeTest#signature_help_preserves_generic_type_parameter_labels_after_type_resolution[jvm]
  - Observed full-suite ComparisonFailure / assert on generic type-parameter labels after type resolution (expected labels like `fun[<T>](value: T): T`, not dropped `fun[](value: T): T`).
  - Clear Windows slice s017 failure lineage (evidence runs 29213080707 / WINSLICE-29213080707; prior 29212217687):
    - semantic.workspace.LuaWorkspaceQueryFacadeTest#signature_help_preserves_generic_type_parameter_labels_after_type_resolution[jvm]
  - Observed Windows ComparisonFailure: expected `fun[<T>](value: T): T` but was `fun[](value: T): T` — generic type-parameter labels dropped after type resolution.
  - Product signature help must preserve generic type-parameter labels (e.g. `<T>`) after type resolution for workspace query surfaces.
  - Prefer SignatureHelpProvider / type-bridge / facade product fix; no CURRENTLY_ACCEPTS weaken.
  - Shared hot file LuaWorkspaceQueryFacade / SignatureHelpProvider — honor locks; leave ready if blocked.
required_tests:
  - Deferred to TASK-043 / full jvmTest run 29227224781: `./gradlew.bat jvmTest --tests semantic.workspace.LuaWorkspaceQueryFacadeTest.signature_help_preserves_generic_type_parameter_labels_after_type_resolution`
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
  - 2026-07-13T05:56:11Z materialize FULLJVM-29227224781: still red on full suite signature_help_preserves_generic_type_parameter_labels_after_type_resolution; reuse TASK-670; status→ready; AC/required_tests updated for evidence 29227224781.
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix for signature-help generic type-parameter labels.
  - 2026-07-12T21:58:00Z worker-WINSLICE-29210211230-TASK-670: claim in_progress; preserve generic type-parameter labels in signature help after type resolution.
  - 2026-07-12T22:00:00Z worker-WINSLICE-29210211230-TASK-670: product fix SignatureHelpProvider preserve/prefer generic typeParameters for fun<T> labels; status=review.
  - 2026-07-13T materialize WINSLICE-29212217687: still red; reset review→ready; reuse for product re-fix.
  - 2026-07-12T22:56:39Z worker-WINSLICE-29212217687-TASK-670: claim in_progress; preserve generic type-parameter labels in signature help after type resolution.
  - 2026-07-12T23:13:29Z worker-WINSLICE-29212217687-TASK-670: product re-fix SignatureHelpProvider final-label recovery (declaredType/owned/doc tags) + ExpressionTypeEvaluator merge/evaluateFunctionDeclaration keep typeParameters; status=review.
  - 2026-07-13T materialize WINSLICE-29213080707: compileFailure blocked tests; reset review→ready for retained red + evidence 29213080707.
  - 2026-07-13T05:58:09Z worker-FULLJVM-29227224781-TASK-670: claim in_progress; fix generic signature labels so bare Lua identity stays fun<T>(value: T): T (no identity prefix); only MemberExpression gets method-name prefix for Java asList hard-lock.
  - 2026-07-13T05:58:52Z worker-FULLJVM-29227224781-TASK-670: product fix SignatureHelpProvider.callableMethodName — only MemberExpression prefixes method name; bare Lua identity keeps fun<T>(value: T): T (fixes ComparisonFailure identity prefix). status=review.
