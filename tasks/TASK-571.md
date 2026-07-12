id: TASK-571
title: JvmWorkspaceEngine default Android-Lua import prefix fallback
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceEngine.kt
  - src/jvmTest/kotlin/interop/jvm/JvmWorkspaceEngineTest.kt
acceptance_criteria:
  - Clear Windows slice failure (evidence run 29176527021 / WINSLICE-29176527021 / slice s002):
    - interop.jvm.JvmWorkspaceEngineTest#unresolved_short_name_falls_back_to_android_lua_default_import_prefixes
  - unresolved_short_name_falls_back_to_android_lua_default_import_prefixes hard-locks default package prefixes when short name unbound.
  - Fallback only when android.lua default import prefixes configured/present; never invent non-Android packages.
  - Empty result only when jar/prefixes truly absent; dual-path host jar discovery preferred over hard AppData android-35 only.
  - Prefer product fix; never hardcode G:/.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s002: `./gradlew.bat jvmTest --tests interop.jvm.JvmWorkspaceEngineTest.unresolved_short_name_falls_back_to_android_lua_default_import_prefixes`
notes:
  - Windows slice s002 run 29176527021 still red (4/307); default import prefix fallback still fails.
  - Reused ready product task; AC evidence updated to WINSLICE-29176527021.
  - Workers must not run Gradle/tests/compile; verification review-owned serial (TASK-043).
  - Host android.jar dual-path only — never G:/.
  - One task one agent. No docs.
related_locks:
  - locks/tasks/TASK-571.lock
  - locks/files/tasks__TASK-571.md.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__interop__jvm__JvmWorkspaceEngine.kt.lock
related_commits: []
progress:
  - 2026-07-11T19:06:13Z worker-GOAL-PATH-M100-20260712-B2-TASK-571 blocked: live locks held by TASK-570 on JvmWorkspaceEngine.kt and JvmWorkspaceEngineTest.kt (owner=worker-GOAL-PATH-M100-20260712-B2-TASK-570, status=in_progress). Did not steal. status remains ready.
  - 2026-07-12T materialize WINSLICE-29175624965: reused ready; AC evidence updated to run 29175624965 (default import prefix fallback still red on s002).
  - 2026-07-12T01:50:15Z worker-WINSLICE-29175624965-TASK-571 blocked: live lock held by TASK-616 on JvmWorkspaceEngine.kt (owner=worker-WINSLICE-29175624965-TASK-616, status=in_progress). Did not steal. status remains ready.
  - 2026-07-12T materialize WINSLICE-29176527021: reused ready; AC evidence updated to run 29176527021 (s002 still failure 4/307; default import prefix fallback still red).
  - 2026-07-12T07:01:58Z worker-WINSLICE-29176527021-TASK-571 blocked: live locks held by TASK-570 on JvmWorkspaceEngine.kt and JvmWorkspaceEngineTest.kt (owner=worker-WINSLICE-29176527021-TASK-570, status=in_progress, claimed_at=2026-07-12T07:01:41Z). Did not steal. status remains ready.
