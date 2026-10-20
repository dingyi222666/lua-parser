id: TASK-628
title: Windows LspJvmProviderDefinition mount policy for bindClass and unmounted requires
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceEngine.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmClassModuleProvider.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/jvmTest/kotlin/lsp/LspJvmProviderDefinitionTddTest.kt
acceptance_criteria:
  - Clear Windows slice failures (evidence run 29193414885 / WINSLICE-29193414885 / slice s004):
    - lsp.LspJvmProviderDefinitionTddTest#definition_and_hover_on_luajava_bind_class_alias_land_on_provider_virtual_path
    - lsp.LspJvmProviderDefinitionTddTest#without_mounts_require_class_definition_and_hover_degrade_gracefully
    - lsp.LspJvmProviderDefinitionTddTest#without_mounts_empty_metadata_keeps_unknown_short_name_unresolved_while_mounted_peer_still_works
  - Observed failures:
    - bindClass path: definition of Locale alias/member lands on workspace document URI `file:///workspace/provider-def-bind-class.lua` instead of provider virtual path `file:///__jvm__/classes/java/util/Locale.lua`.
    - without mounts: short require("Arrays") alias resolves to `file:///__jvm__/classes/java/util/Arrays.lua` even when CLASSES_METADATA / mounts are absent (must not).
    - empty metadata: unknown short require must stay unresolved for Arrays provider while a mounted peer still works; currently empty metadata still mounts Arrays provider.
  - Product mount policy: source-discovered luajava.bindClass / mounted class providers land definition+hover on `__jvm__/classes/...` virtual paths; unmounted / empty-metadata short requires must not invent provider URIs.
  - Prefer product fix in JvmWorkspaceEngine / class module provider mount gating + definition resolution; no CURRENTLY_ACCEPTS weakening of green-lock asserts.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s004: `./gradlew.bat jvmTest --tests lsp.LspJvmProviderDefinitionTddTest`
notes:
  - Windows slice s004 run 29193414885: 3 LspJvmProviderDefinition reds (under-mount bindClass vs over-mount unmounted/empty-metadata short require). Clustered as one mount-policy product fix.
  - Distinct from done test-only TASK-242 corpus; this is product mount/definition wire fix under WINSLICE evidence.
  - Serialize exclusive claim on JvmWorkspaceEngine.kt vs package-provider tasks when overlapping.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-628.lock
  - locks/files/tasks__TASK-628.md.lock
  - locks/files/src__jvmMain__kotlin__io__github__dingyi222666__luaparser__interop__jvm__JvmWorkspaceEngine.kt.lock
  - locks/files/src__jvmTest__kotlin__lsp__LspJvmProviderDefinitionTddTest.kt.lock
related_commits: []
progress:
  - 2026-07-12T materialize WINSLICE-29193414885: created ready product fix for 3 LspJvmProviderDefinition mount/definition reds.
  - 2026-07-12T13:09:16Z worker-WINSLICE-29193414885-TASK-628: product mount policy — stop free-class auto-mount into extraProviders; bindClass/loadLib local definition lands on __jvm__/classes via LuaWorkspaceQueryFacade. No Gradle.
