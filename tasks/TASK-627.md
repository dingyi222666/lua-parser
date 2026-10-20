id: TASK-627
title: Windows LspJavaAndroidFeature Java static member signature help product fix
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/lsp/LuaLanguageService.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/semantic/model/SignatureHelpProvider.kt
  - src/jvmMain/kotlin/io/github/dingyi222666/luaparser/interop/jvm
  - src/jvmTest/kotlin/lsp/LspJavaAndroidFeatureTddTest.kt
acceptance_criteria:
  - Clear FULL jvmTest failure (evidence run 29225343255 / FULLJVM-29225343255):
    - lsp.LspJavaAndroidFeatureTddTest#java_static_member_lsp_features_resolve_from_configured_jvm_provider[jvm]
  - Observed full-suite AssertionError at LspJavaAndroidFeatureTddTest.kt:72 (assertTrue) after configured JVM provider for java.util.Arrays static asList path — signature/hover/definition/references/symbols surface still incomplete for reflected static members.
  - Clear Windows slice failures (evidence run 29193414885 / WINSLICE-29193414885 / slice s004):
    - lsp.LspJavaAndroidFeatureTddTest#java_static_member_lsp_features_resolve_from_configured_jvm_provider
  - Observed Windows failure: after configured JVM provider for java.util.Arrays, signatureHelp.signatures is non-empty enough to pass earlier checks but no signature label contains `asList` or `fun(` (assertTrue L57).
  - Product signature help for reflected Java static members from configured jvm provider must surface method labels that include the member name (asList) and/or fun( form for call sites like Arrays.asList(...).
  - Hover/completion/definition/references/document+workspace symbols for the same configured Arrays path must not regress.
  - Prefer product SignatureHelpProvider / JVM reflection label surface fix; no CURRENTLY_ACCEPTS weakening of hard-lock asserts.
required_tests:
  - Deferred to TASK-043 / full jvmTest run 29225343255: `./gradlew.bat jvmTest --tests lsp.LspJavaAndroidFeatureTddTest.java_static_member_lsp_features_resolve_from_configured_jvm_provider`
  - Deferred to TASK-043 / windows-jvmtest slice s004: `./gradlew.bat jvmTest --tests lsp.LspJavaAndroidFeatureTddTest.java_static_member_lsp_features_resolve_from_configured_jvm_provider`
notes:
  - Windows slice s004 run 29193414885: java_static_member red is distinct from the three android-jar hard-lock reds (this method uses jvmService, not assertAndroidJarExists).
  - Distinct from TASK-626 (android jar dual-path). Serialize exclusive claim on LuaLanguageService.kt if overlapping other LSP product tasks.
  - Workers no Gradle. One task one agent. No docs filler.
related_locks:
  - locks/tasks/TASK-627.lock
  - locks/files/tasks__TASK-627.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__model__SignatureHelpProvider.kt.lock
related_commits: []
progress:
  - 2026-07-13T05:33:41Z FULLJVM local green for java_static_member after signatureHelpLabel fun< prefix fix; awaiting Windows full suite.
  - 2026-07-12T materialize WINSLICE-29193414885: created ready product fix for java_static_member signature help red.
  - 2026-07-12T12:59:59Z worker-WINSLICE-29193414885-TASK-627: claimed in_progress; investigating SignatureHelp for Java static members (asList labels).
  - 2026-07-12T13:03:44Z worker-WINSLICE-29193414885-TASK-627: product fix in SignatureHelpProvider — generic Java fun<T>(...) labels now prefix call-site member name (asList) so L57 asList|fun( hard-lock can pass; plain fun( labels unchanged. No test weakening. status→review owner→unassigned. No Gradle.
  - 2026-07-13T materialize FULLJVM-29225343255: still red on full suite L72 assertTrue; reuse TASK-627; status review→ready; evidence 29225343255.
  - 2026-07-13T05:14:36Z worker-FULLJVM-29225343255-TASK-627: claimed in_progress; investigating full-suite L72 asList|fun( signature label red after prior WINSLICE fun< early-return regression.
  - 2026-07-13T05:16:19Z worker-FULLJVM-29225343255-TASK-627: product fix SignatureHelpProvider.signatureHelpLabel — do not early-return on fun< alone; prefix call-site member name (asList) for generic Java static labels so L72 asList|fun( hard-lock can pass while bare fun<T> Lua generics (TASK-670) stay unprefixed. No test weakening. status→review owner→unassigned. No Gradle.
