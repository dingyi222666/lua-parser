id: TASK-570
title: JvmWorkspaceEngine AndroLua underscore nested class alias resolution
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceEngine.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmClassModuleProvider.kt
  - src/jvmTest/kotlin/interop/jvm/JvmWorkspaceEngineTest.kt
acceptance_criteria:
  - Clear Windows slice failure (evidence run 29176527021 / WINSLICE-29176527021 / slice s002):
    - interop.jvm.JvmWorkspaceEngineTest#androlua_import_metadata_resolves_nested_androlua_classes_via_underscore_aliases
  - androlua_import_metadata_resolves_nested_androlua_classes_via_underscore_aliases hard-locks nested Map$Entry-style / View$OnClickListener style aliases when host android.jar present via dual-path discovery.
  - Binary and dotted nested names remain resolvable when jar present; when jar truly absent, explicit soft-skip — never hard-require missing AppData android-35 alone if another host jar is discoverable.
  - Prefer product engine alias path; never hardcode G:/.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s002: `./gradlew.bat jvmTest --tests interop.jvm.JvmWorkspaceEngineTest.androlua_import_metadata_resolves_nested_androlua_classes_via_underscore_aliases`
notes:
  - Windows slice s002 run 29176527021 still red (4/307); underscore nested AndroLua alias still fails.
  - Reused ready product task; AC evidence updated to WINSLICE-29176527021.
  - Workers must not run Gradle/tests/compile; verification review-owned serial (TASK-043).
  - Host android.jar dual-path only — never G:/.
  - One task one agent. No docs.
related_locks:
  - locks/tasks/TASK-570.lock
  - locks/files/tasks__TASK-570.md.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__interop__jvm__JvmWorkspaceEngine.kt.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__interop__jvm__JvmClassModuleProvider.kt.lock
  - locks/files/src__jvmTest__kotlin__interop__jvm__JvmWorkspaceEngineTest.kt.lock
related_commits: []
progress:
  - 2026-07-12T07:27:05Z worker-WINSLICE-29176527021-TASK-570: fixed s002 hard-fail (assertTrue missing AppData android-35). Product short-name path: View$OnClickListener / View_OnClickListener and package+View_OnClickListener underscore rewrite. Test: Map$Entry JDK hard-lock always first; dual-path host jar + Assume soft-skip when jar truly absent; never G:/. No Gradle. status→review.
  - 2026-07-12T07:23:50Z worker-WINSLICE-29176527021-TASK-570 claimed; Windows s002 failure is assertTrue host android.jar at AppData android-35 (no soft-skip on evidence SHA).
  - 2026-07-12T07:14:52Z worker-WINSLICE-29176527021-TASK-570: fixed Windows s002 hard-fail (missing AppData android-35 assert). Product: short-name import prefixes try View$OnClickListener / View_OnClickListener before dotted candidates. Test: dual-path host jar discovery + explicit soft-skip when jar truly absent; Map$Entry underscore alias hard-lock. Never G:/. No Gradle. status→review.
  - 2026-07-12T07:13:36Z worker-WINSLICE-29176527021-TASK-570 claimed; Windows s002 hard-failed at missing AppData android-35 assert — soft-skip when jar truly absent + dual-path host discovery (never G:/); product nested alias path View$OnClickListener / Map$Entry.
  - 2026-07-12T01:52:30Z worker-WINSLICE-29175624965-TASK-570 claimed; fixing nested underscore AndroLua alias (View$OnClickListener) via host android.jar dual-path, never G:/.
  - 2026-07-11T19:09:54Z worker-GOAL-PATH-M100-20260712-B2-TASK-570: product soft-fallback for missing G:/ android.jar metadata to host SDK discovery in JvmClassModuleProvider; candidateClassNames already covers binary/dotted/underscore nested aliases (View$OnClickListener / Map$Entry). Hard-locked androlua_import_metadata_resolves_nested_androlua_classes_via_underscore_aliases to host android-35 jar (never G:/). No Gradle. status→review.
  - 2026-07-11T19:06:00Z worker-GOAL-PATH-M100-20260712-B2-TASK-570 claimed; fixing nested underscore alias engine path (OnClickListener via android.view.View prefix) with host android.jar soft-fallback, never G:/.
  - 2026-07-12T materialize WINSLICE-29175624965: re-ready; AC evidence updated to run 29175624965 (underscore nested AndroLua alias still red on s002).
  - 2026-07-12T materialize WINSLICE-29176527021: reused ready; AC evidence updated to run 29176527021 (s002 still failure 4/307; underscore nested AndroLua alias still red).
