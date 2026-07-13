id: TASK-689
title: FULL RealProject android missing-jar skip reason no G:/ substring product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmTest/kotlin/lsp/LspRealProjectJavaAndroidInteropTddTest.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceConfiguration.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
acceptance_criteria:
  - Clear FULL jvmTest failure (evidence run 29265516413 / WINFULL-29265516413):
    - lsp.LspRealProjectJavaAndroidInteropTddTest#real_project_android_missing_jar_skip_reason_never_mentions_g_drive[jvm]
  - Observed Windows AssertionError (run 29265516413):
    - `Skip reason must never hardcode G:/; got TASK-REAL-ANDROID soft-skip: android.jar not found at \nonexistent\android-sdk\platforms\android-35\android.jar. android.jar is present at C:\Users\dingyi\AppData\Local\Android\Sdk\platforms\android-35\android.jar; soft-skip is not required. … Never invent presence; never hard-require G:/Android/Sdk alone.`
    - Failure is the literal substring `G:/` inside the skip-reason ban phrase itself (and/or present-host messaging), not an invented G: drive candidate path.
  - Product/test helper `missingAndroidJarSkipReason` (and any product dual-path messaging it quotes) must:
    - remain explicit (task id, missing configured path, host discovery policy under ANDROID_HOME / ANDROID_SDK_ROOT / LOCALAPPDATA Android Sdk / macOS Library / Linux Android Sdk, soft-skip wording)
    - never include the literal substring `G:/` or `G:\` (prefer "never invent Windows drive-letter defaults" / "never G drive roots")
    - never invent G:/ as a discovery candidate; dual-path soft-skip only when configured/host jar honestly missing.
  - Prefer reword helper + any product reason string it embeds; do not weaken the hard ban on G:/. Distinct from TASK-660 (interop surface suites) — this owns RealProject JavaAndroidInterop suite only.
required_tests:
  - Deferred to TASK-043 / full jvmTest run 29265516413: `./gradlew.bat jvmTest --tests lsp.LspRealProjectJavaAndroidInteropTddTest.real_project_android_missing_jar_skip_reason_never_mentions_g_drive`
notes:
  - Cluster android-missing-jar-skip-reason from REVIEW-FULL-29265516413 (1 red).
  - Windows host may have a real local SDK android.jar; reason text must not self-fail the G:/ ban while describing policy.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-689.lock
  - locks/files/tasks__TASK-689.md.lock
related_commits: []
progress:
  - 2026-07-14T materialize WINFULL-29265516413: created ready product/test fix for RealProject android missing-jar skip-reason G:/ ban (evidence run 29265516413).
  - 2026-07-13T16:24:16Z worker-WINFULL-29265516413-TASK-689: claimed; reword RealProject missingAndroidJarSkipReason ban phrase to drop literal G:/ G:\\ substring while keeping multi-OS soft-skip policy explicit.
  - 2026-07-13T16:25:03Z worker-WINFULL-29265516413-TASK-689: reworded RealProject missingAndroidJarSkipReason ban phrase to 'never invent Windows drive-letter defaults; never hard-require G drive roots alone' (no literal G:/ or G:\). Product missingAndroidJarSoftSkipReason already G-clean runtime messaging. status=review.
