id: TASK-620
title: Windows LspAndroidLuaE2e missing-jar skip + fixture locality product fix
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceConfiguration.kt
  - src/jvmTest/kotlin/lsp/LspAndroidLuaE2eTddTest.kt
  - src/jvmTest/resources/lsp
acceptance_criteria:
  - Clear Windows slice failures (evidence run 29175624965 / WINSLICE-29175624965 / slice s002):
    - lsp.LspAndroidLuaE2eTddTest#missing_android_jar_skip_documents_explicit_reason
    - lsp.LspAndroidLuaE2eTddTest#loadlayout_fixture_documents_layout_id_locals_and_view_like_return
    - lsp.LspAndroidLuaE2eTddTest#fixture_resources_are_repository_local_and_do_not_reference_machine_checkouts
  - missing_android_jar_skip_documents_explicit_reason: skip/soft-skip reason must be explicit and must never hardcode G:/ machine paths.
  - loadlayout_fixture_documents_layout_id_locals_and_view_like_return: repository-local loadlayout fixture must document layout id locals + view-like return surface when product path is available; honest soft-skip only when android.jar truly absent after dual-path discovery.
  - fixture_resources_are_repository_local_and_do_not_reference_machine_checkouts: fixtures/catalog must stay repository-local (no G: checkout references).
  - Prefer product+fixture fix; no CURRENTLY_ACCEPTS that invents jar presence; never hardcode G:/.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s002: `./gradlew.bat jvmTest --tests lsp.LspAndroidLuaE2eTddTest`
notes:
  - Windows slice s002 run 29175624965: 3 LspAndroidLuaE2e reds (skip reason G:/, loadlayout fixture surface, machine-local checkout references).
  - Distinct from TASK-521 multi-doc provider regression locks (still review); this task owns the three Windows failures.txt methods.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-620.lock
  - locks/files/tasks__TASK-620.md.lock
  - locks/files/src__jvmTest__kotlin__lsp__LspAndroidLuaE2eTddTest.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29175624965: created ready product fix for 3 LspAndroidLuaE2e reds.
  - 2026-07-12T01:49:10Z worker-WINSLICE-29175624965-TASK-620: claimed; inspecting LspAndroidLuaE2e reds (missing jar skip, loadlayout fixture, repo-local fixtures).
