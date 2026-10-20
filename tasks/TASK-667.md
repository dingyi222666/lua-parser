id: TASK-667
title: Windows Android-Lua inner-class import binary/dotted/underscore provider resolution
status: ready
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmWorkspaceEngine.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm/JvmClassModuleProvider.kt
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/workspace/LuaWorkspaceQueryFacade.kt
  - src/jvmTest/kotlin/semantic/workspace/AndroidLuaImportWorkspaceTddTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failures (evidence run 29210211230 / WINSLICE-29210211230; same reds on 29209854844):
    - semantic.workspace.AndroidLuaImportWorkspaceTddTest#inner_class_binary_name_import_resolves_provider[jvm]
    - semantic.workspace.AndroidLuaImportWorkspaceTddTest#inner_class_dotted_name_import_resolves_binary_provider[jvm]
    - semantic.workspace.AndroidLuaImportWorkspaceTddTest#inner_class_underscore_alias_import_resolves_provider[jvm]
  - Observed Windows failures: all three throw `NoSuchElementException: List is empty` — import of inner classes via binary name (`Outer$Inner`), dotted name, and underscore alias yields empty definition/provider lists.
  - Product must mount/resolve inner-class providers for binary, dotted, and underscore alias import forms so definition surfaces return a single provider path.
  - Prefer candidateClassNames / nested alias path in JvmWorkspaceEngine + provider mount; never invent G:/; dual-path host jar when needed.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.workspace.AndroidLuaImportWorkspaceTddTest.inner_class_binary_name_import_resolves_provider --tests semantic.workspace.AndroidLuaImportWorkspaceTddTest.inner_class_dotted_name_import_resolves_binary_provider --tests semantic.workspace.AndroidLuaImportWorkspaceTddTest.inner_class_underscore_alias_import_resolves_provider`
notes:
  - Windows slice s017 run 29210211230: clustered 3 inner-class import provider reds.
  - Related to TASK-570 underscore nested aliases (interop.jvm engine test) but AC is AndroidLuaImportWorkspaceTddTest workspace surface.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-667.lock
  - locks/files/tasks__TASK-667.md.lock
related_commits: []
progress:
  - 2026-07-12T21:57:50Z worker-WINSLICE-29210211230-TASK-667: blocked; live locks on scope files by TASK-666 (JvmWorkspaceEngine/LuaWorkspaceQueryFacade/AndroidLuaImportWorkspaceTddTest) and TASK-664 residual QueryFacade; released claim to ready
  - 2026-07-13T materialize WINSLICE-29210211230: created ready product fix clustering 3 inner-class import provider reds.
