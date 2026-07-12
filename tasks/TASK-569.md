id: TASK-569
title: JvmWorkspaceEngine load_lib re-alias static member resolution
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceEngine.kt
  - src/jvmTest/kotlin/interop/jvm/JvmWorkspaceEngineTest.kt
acceptance_criteria:
  - Clear Windows slice failures (evidence run 29175624965 / WINSLICE-29175624965 / slice s002):
    - interop.jvm.JvmWorkspaceEngineTest#realiased_load_lib_helper_resolves_reflected_jvm_static_members
    - interop.jvm.JvmWorkspaceEngineTest#short_string_realiased_load_lib_helper_resolves_reflected_jvm_static_members
  - realiased_load_lib_helper_resolves_reflected_jvm_static_members and short_string variant pass without CURRENTLY_ACCEPTS empty.
  - loadLib / load_lib alias mounts reflected static members when jar/classpath present.
  - Local shadows still do not inherit helper surfaces (pair with helper-shadowing task).
  - Prefer host dual-path android.jar discovery; never hardcode G:/.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s002: `./gradlew.bat jvmTest --tests interop.jvm.JvmWorkspaceEngineTest`
notes:
  - Windows slice s002 run 29175624965 still red for both load_lib re-alias methods.
  - Reused ready product task; AC evidence updated to WINSLICE-29175624965.
  - Workers must not run Gradle/tests/compile; verification review-owned serial (TASK-043).
  - Host android.jar paths only: dual-path discovery — never G:/.
  - One task one agent. No docs.
related_locks:
  - locks/tasks/TASK-569.lock
  - locks/files/tasks__TASK-569.md.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__interop__jvm__JvmWorkspaceEngine.kt.lock
related_commits: []
progress:
  - 2026-07-11T19:06:13Z worker-GOAL-PATH-M100-20260712-B2-TASK-569 blocked: file locks held by live TASK-570 (JvmWorkspaceEngine.kt + JvmWorkspaceEngineTest.kt). Did not steal. Left ready for requeue.
  - 2026-07-12T materialize WINSLICE-29175624965: reused ready; AC evidence updated to run 29175624965 (load_lib re-alias + short_string still red on s002).
