id: TASK-652
title: Windows load* host android.jar dual-path soft-skip product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceConfiguration.kt
  - src/jvmTest/kotlin/semantic/androidlua/LoadbitmapReturnSurfaceTddTest.kt
  - src/jvmTest/kotlin/semantic/androidlua/LoadlayoutIdFieldSurfaceTddTest.kt
  - src/jvmTest/kotlin/semantic/androidlua/LoadmenuTableSpecSurfaceTddTest.kt
acceptance_criteria:
  - Clear Windows slice s012 failures (evidence run 29203984725 / WINSLICE-29203984725):
    - semantic.androidlua.LoadbitmapReturnSurfaceTddTest#host_android_jar_resolves_to_allowed_macos_paths_only[jvm]
    - semantic.androidlua.LoadlayoutIdFieldSurfaceTddTest#host_android_jar_resolves_to_allowed_macos_paths_only[jvm]
    - semantic.androidlua.LoadmenuTableSpecSurfaceTddTest#host_android_jar_resolves_to_allowed_macos_paths_only[jvm]
  - Observed Windows AssertionError on all three: android.jar must exist for load* surface corpus;
    path=C:\Users\dingyi\AppData\Local\Android\Sdk\platforms\android-35\android.jar (DEFAULT_ANDROID_JAR_PATH /
    preferred candidate is not a file on CI).
  - host_android_jar_resolves_to_allowed_macos_paths_only must dual-path: when a present host jar is discovered
    (Downloads / DEFAULT / SDK platforms/android-35|34 under ANDROID_HOME/ANDROID_SDK_ROOT / well-known roots),
    assert path is allowed (never G:/ inventing defaults); when no present jar, soft-skip via
    JvmWorkspaceConfiguration.missingAndroidJarSoftSkipReason (or Assume) with explicit non-G reason instead of hard-failing
    File.isFile on the preferred messaging candidate.
  - Prefer aligning resolveAndroidJar + host contract tests with product discovery + TASK-626 soft-skip pattern;
    do not hardcode sole G:/; do not invent missing AppData android-35 as present.
  - Present-jar feature goldens in the three suites remain hard-locks; only the host-path contract method soft-skips when absent.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s012: `./gradlew.bat jvmTest --tests semantic.androidlua.LoadbitmapReturnSurfaceTddTest.host_android_jar_resolves_to_allowed_macos_paths_only --tests semantic.androidlua.LoadlayoutIdFieldSurfaceTddTest.host_android_jar_resolves_to_allowed_macos_paths_only --tests semantic.androidlua.LoadmenuTableSpecSurfaceTddTest.host_android_jar_resolves_to_allowed_macos_paths_only`
notes:
  - Windows slice s012 run 29203984725: 3 reds cluster on host_android_jar_resolves_to_allowed_macos_paths_only hard-requiring missing AppData android-35.
  - Lineage: TASK-626 dual-path soft-skip for LspJavaAndroidFeature; product DEFAULT_ANDROID_JAR_PATH already returns preferred candidate when absent — tests must not assert isFile without soft-skip.
  - Clustered three methods into one product+test dual-path task. Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-652.lock
  - locks/files/tasks__TASK-652.md.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__interop__jvm__JvmWorkspaceConfiguration.kt.lock
  - locks/files/src__jvmTest__kotlin__semantic__androidlua__LoadbitmapReturnSurfaceTddTest.kt.lock
  - locks/files/src__jvmTest__kotlin__semantic__androidlua__LoadlayoutIdFieldSurfaceTddTest.kt.lock
  - locks/files/src__jvmTest__kotlin__semantic__androidlua__LoadmenuTableSpecSurfaceTddTest.kt.lock
related_commits: []
progress:
  - 2026-07-13T materialize WINSLICE-29203984725: created ready product fix clustering 3 host_android_jar_resolves_to_allowed_macos_paths_only reds (loadbitmap/loadlayout/loadmenu).
  - 2026-07-12T18:45:36Z worker-WINSLICE-29203984725-TASK-652: claim in_progress; dual-path soft-skip for load* host_android_jar_resolves_to_allowed_macos_paths_only (TASK-652 / WINSLICE-29203984725).
  - 2026-07-12T18:54:30Z worker-WINSLICE-29203984725-TASK-652: implemented dual-path soft-skip on three load* host contracts (Assume + missingAndroidJarSoftSkipReason TASK-652; resolveAndroidJar multi-OS present-non-G preference); product API already sufficient; status=review owner=unassigned.
