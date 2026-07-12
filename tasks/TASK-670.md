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
  - src/jvmTest/kotlin/semantic/workspace/LuaWorkspaceQueryFacadeTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29210211230 / WINSLICE-29210211230; same red on 29209854844):
    - semantic.workspace.LuaWorkspaceQueryFacadeTest#signature_help_preserves_generic_type_parameter_labels_after_type_resolution[jvm]
  - Observed Windows ComparisonFailure: expected `fun[<T>](value: T): T` but was `fun[](value: T): T` — generic type-parameter labels dropped after type resolution.
  - Product signature help must preserve generic type-parameter labels (e.g. `<T>`) after type resolution for workspace query surfaces.
  - Prefer SignatureHelpProvider / type-bridge / facade product fix; no CURRENTLY_ACCEPTS weaken.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.LuaWorkspaceQueryFacadeTest.signature_help_preserves_generic_type_parameter_labels_after_type_resolution`
notes:
  - Windows slice s017 run 29210211230: signature-help generic label drop red.
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
