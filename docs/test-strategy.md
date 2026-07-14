# Test Strategy

Tests live under `src/commonTest/kotlin` and `src/jvmTest/kotlin`. Common tests
also execute through the JVM target, while JVM tests cover host-specific LSP,
reflection, classpath, Android SDK, and filesystem behavior.

This document intentionally does not maintain exact source-file or `@Test`
totals. Those totals become stale whenever ordinary product tests are added or
removed and are not a substitute for compiling and running the suite. Historical
acceptance ledgers may retain a dated source recount as evidence for a completed
campaign, but there is no inventory test or lockstep count assertion.

## Coverage Areas

| Area | Source set | Coverage focus |
| --- | --- | --- |
| Parser/source | Common + JVM | Lua 5.3/5.4 and Android-Lua syntax, parser recovery, comments, AST visitors/modifiers, AST-to-Lua round trips, lexer corpora. |
| Semantic core | Common + JVM | Binder, declarations, completion, checker expression evaluation, comments, type syntax/resolution, call/member/return checking, public API compatibility. |
| Workspace | Common + JVM | Document facts, module graphs/exports, overlays, incremental updates, path styles, Android imports, JVM workspace behavior. |
| Interop | JVM | JVM class providers, reflection-backed models, classloader configuration, Android jar reflection, package enumeration, LuaJava imports. |
| LSP | JVM | Initialization/lifecycle, synchronization, diagnostics, hover, completion, navigation, symbols, signature help, configuration, shutdown, Monaco integration. |
| Integration | JVM | Android-Lua corpora and mixed parser/workspace/interop scenarios. |

## Test Rules

- Product tests use Kotlin files whose names end with `Test.kt` so Gradle/JUnit
  discovery and focused `--tests` filters remain predictable.
- New behavior or regressions should normally start with a focused `*TddTest.kt`
  test near the affected parser, semantic, workspace, interop, or LSP package.
- Small synthetic Lua snippets belong inline in focused tests. Larger reusable
  corpora belong under the matching `src/*Test/resources` directory.
- Resource files do not count as independently executed tests; the Kotlin test
  method that loads and asserts them is the executable coverage.
- Generated Gradle/build directories and production sources are never treated as
  tests.
- `src/commonTest/kotlin/parser.common.kt` and
  `src/jvmTest/kotlin/parser.jvm.kt` are historical annotated smoke files whose
  names do not follow current discovery conventions. Rename or retire them only
  in a dedicated change.

## Verification

Use the narrowest relevant Gradle filter while developing, then run the broader
JVM suite when the change affects shared semantic/workspace behavior. Coordinated
verification runs and their host/JDK constraints are documented in
`docs/serialized-verification.md`; dated final evidence is recorded in
`docs/final-verification.md` and `docs/acceptance-traceability.md`.

Source recounts may be generated for a one-time audit, but they must be labeled
with the date and must not become product test assertions. Passing Gradle/JUnit
commands, not source annotation totals, are the authority for suite health.
