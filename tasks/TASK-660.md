id: TASK-660
title: Windows missing-android.jar skip reason must not contain literal G:/
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmTest/kotlin/semantic/interop/LuaJavaBindClassArrayComponentTddTest.kt
  - src/jvmTest/kotlin/semantic/interop/LuaJavaCodingMethodSurfaceTddTest.kt
  - src/jvmTest/kotlin/semantic/interop/LuaJavaNewArrayElementTypeTddTest.kt
  - src/jvmTest/kotlin/semantic/interop/LuaJavaNewInstanceOverloadSurfaceTddTest.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceConfiguration.kt
acceptance_criteria:
  - Clear Windows slice s016 failures (evidence run 29208645359 / WINSLICE-29208645359):
    - semantic.interop.LuaJavaBindClassArrayComponentTddTest#missing_android_jar_skip_documents_array_component_surface[jvm]
    - semantic.interop.LuaJavaCodingMethodSurfaceTddTest#missing_android_jar_skip_documents_coding_method_surface[jvm]
    - semantic.interop.LuaJavaNewArrayElementTypeTddTest#missing_android_jar_skip_documents_new_array_element_type_surface[jvm]
    - semantic.interop.LuaJavaNewInstanceOverloadSurfaceTddTest#host_android_jar_present_or_skipped_with_explicit_reason_surface[jvm]
  - Observed Windows AssertionError: `Skip reason must never mention G:/` while skip helpers embed the ban phrase itself
    (e.g. "Never use Windows-only G:/ paths." / "(never G:/)") so `reason.contains("G:/")` self-fails even when no
    Windows drive-letter invent path is used.
  - missingAndroidJarSkipReason / host present-or-skipped soft-skip text must remain explicit (task id, android.jar, missing
    path or tried candidates, Downloads + SDK android-35|34 / ANDROID_HOME|ANDROID_SDK_ROOT policy) but must not include
    the literal substring `G:/`. Prefer wording like "never invent Windows drive-letter defaults" / "never G drive roots".
  - Host discovery must still never invent G:/ as a candidate path; DEFAULT_ANDROID_JAR_PATH / resolveAndroidJar stay non-G.
  - Present-jar feature goldens in these suites remain hard-locks; only skip-reason / host dual-path contract methods change.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s016: `./gradlew.bat jvmTest --tests semantic.interop.LuaJavaBindClassArrayComponentTddTest.missing_android_jar_skip_documents_array_component_surface --tests semantic.interop.LuaJavaCodingMethodSurfaceTddTest.missing_android_jar_skip_documents_coding_method_surface --tests semantic.interop.LuaJavaNewArrayElementTypeTddTest.missing_android_jar_skip_documents_new_array_element_type_surface --tests semantic.interop.LuaJavaNewInstanceOverloadSurfaceTddTest.host_android_jar_present_or_skipped_with_explicit_reason_surface`
notes:
  - Windows slice s016 run 29208645359: 4 reds share the same false-positive G:/ ban in skip-reason strings.
  - Lineage: TASK-474/488/489/473 corpus helpers + TASK-626/652 dual-path soft-skip policy.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-660.lock
  - locks/files/tasks__TASK-660.md.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29208645359: created ready product+test fix clustering 4 missing/host android.jar skip-reason reds (literal G:/ self-mention).
  - 2026-07-12T21:05:29Z worker-WINSLICE-29208645359-TASK-660: claim in_progress; fix skip-reason literal G:/ self-mention across 4 interop surface suites + host config if needed.
  - 2026-07-12T21:10:00Z worker-WINSLICE-29208645359-TASK-660: reworded missingAndroidJarSkipReason in 4 interop surface suites to drop literal G:/ (use "never invent Windows drive-letter defaults"); product DEFAULT_ANDROID_JAR_PATH/discovery left non-G; status=review.
