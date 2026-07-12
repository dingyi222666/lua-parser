id: TASK-666
title: Windows Android-Lua source import simple-name default and custom metadata prefixes
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceEngine.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/WorkspaceModuleResolver.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/parser/LuaParser.kt
  - src/jvmTest/kotlin/semantic/workspace/AndroidLuaImportWorkspaceTddTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failures (evidence run 29210211230 / WINSLICE-29210211230; same reds on 29209854844):
    - semantic.workspace.AndroidLuaImportWorkspaceTddTest#source_import_with_simple_name_uses_default_prefixes[jvm]
    - semantic.workspace.AndroidLuaImportWorkspaceTddTest#source_import_with_custom_metadata_prefix_resolves_simple_name[jvm]
  - Observed Windows failures: both methods throw `NoSuchElementException: List is empty` on `.single()` over empty goto/definition lists for simple-name source imports under default prefixes and custom metadata prefixes.
  - Product must resolve simple-name source imports via default Android-Lua import prefixes and via custom metadata prefix configuration to a single provider definition (non-empty).
  - Prefer product prefix/candidate resolution; never invent G:/ paths; dual-path host jar discovery when reflection is required.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.AndroidLuaImportWorkspaceTddTest.source_import_with_simple_name_uses_default_prefixes --tests semantic.workspace.AndroidLuaImportWorkspaceTddTest.source_import_with_custom_metadata_prefix_resolves_simple_name`
notes:
  - Windows slice s017 run 29210211230: clustered 2 source-import simple-name prefix reds.
  - Related but distinct from JvmWorkspaceEngineTest default-prefix TASK-571 (interop.jvm harness).
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-666.lock
  - locks/files/tasks__TASK-666.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix clustering 2 source-import simple-name prefix reds.
  - 2026-07-12T21:55:26Z worker-WINSLICE-29210211230-TASK-666: claim in_progress; fix simple-name source import default/custom prefix resolution.
  - 2026-07-12T22:45:34Z worker-WINSLICE-29210211230-TASK-666: product fix for empty goto on import "File"/import "BigDecimal" string-call sites; status=review.
