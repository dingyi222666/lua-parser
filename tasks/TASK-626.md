id: TASK-626
title: Windows LspJavaAndroidFeature android-35 jar hard-lock dual-path product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceConfiguration.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/jvmTest/kotlin/lsp/LspJavaAndroidFeatureTddTest.kt
acceptance_criteria:
  - Clear Windows slice failures (evidence run 29193414885 / WINSLICE-29193414885 / slice s004):
    - lsp.LspJavaAndroidFeatureTddTest#text_document_and_workspace_services_wrap_android_java_feature_queries_after_configuration
    - lsp.LspJavaAndroidFeatureTddTest#android_wildcard_layout_lsp_features_cover_ids_listener_and_cross_file_symbols
    - lsp.LspJavaAndroidFeatureTddTest#android_import_table_lsp_features_resolve_class_static_field_and_member_methods
  - Observed Windows failure: assertAndroidJarExists hard-requires `C:\Users\dingyi\AppData\Local\Android\Sdk\platforms\android-35\android.jar` via JvmWorkspaceConfiguration.DEFAULT_ANDROID_JAR_PATH while that file is absent on CI; suite fails before Android import/wildcard/service feature asserts.
  - Product+test dual-path host jar discovery must resolve a present android.jar (ANDROID_HOME/ANDROID_SDK_ROOT / well-known LOCALAPPDATA Android Sdk / preferred android-35 when present) without inventing missing paths and without hardcoding sole G:/.
  - When no host jar is present after dual-path discovery, Android-only methods soft-skip with an explicit non-G reason; when a jar is present, import-table / wildcard layout / text+workspace service feature hard-locks remain green.
  - Prefer product DEFAULT_ANDROID_JAR_PATH / discovery + test assertAndroidJarExists soft-skip alignment; no CURRENTLY_ACCEPTS weakening of present-jar feature asserts; never hardcode G:/.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s004: `./gradlew.bat jvmTest --tests lsp.LspJavaAndroidFeatureTddTest`
notes:
  - Windows slice s004 run 29193414885: 3 LspJavaAndroidFeature reds share AssertionError Expected Android platform jar at AppData android-35 (assertAndroidJarExists L334 via androidService / text_document test).
  - Distinct from TASK-620 LspAndroidLuaE2e fixtures; this task owns the three LspJavaAndroidFeature android jar reds under WINSLICE-29193414885.
  - Coordinate exclusive claim on JvmWorkspaceConfiguration.kt vs other dual-path tasks when overlapping.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-626.lock
  - locks/files/tasks__TASK-626.md.lock
  - locks/files/src__jvmTest__kotlin__lsp__LspJavaAndroidFeatureTddTest.kt.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__interop__jvm__JvmWorkspaceConfiguration.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29193414885: created ready product fix for 3 LspJavaAndroidFeature android-35 AppData hard-lock reds.
  - 2026-07-12T12:59:57Z worker-WINSLICE-29193414885-TASK-626: claim in_progress; align LspJavaAndroidFeatureTddTest dual-path host jar discovery + soft-skip with product DEFAULT_ANDROID_JAR_PATH / missingAndroidJarSoftSkipReason.
  - 2026-07-12T13:02:37Z worker-WINSLICE-29193414885-TASK-626: dual-path host android.jar discovery + soft-skip for LspJavaAndroidFeatureTddTest (resolveHostAndroidJar + Assume via missingAndroidJarSoftSkipReason TASK-626); product DEFAULT_ANDROID_JAR_PATH KDoc notes Windows CI soft-skip; present-jar feature asserts unchanged; no G: hardcode. status=review.
