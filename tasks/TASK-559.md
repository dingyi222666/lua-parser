id: TASK-559
title: TypeSyntaxParser parsePrefix union/optional boundary completeness
status: review
priority: p0
owner: unassigned
depends_on: []
scope:
  - src/commonMain/kotlin/io/github/dingyi222666/luaparser/semantic/types/syntax/TypeSyntaxParser.kt
  - src/jvmTest/kotlin/semantic/types/syntax/TypeSyntaxUnionOptionalTddTest.kt
  - src/jvmTest/kotlin/semantic/types/syntax/TypeSyntaxParserTest.kt
acceptance_criteria:
  - Clear Windows slice s017 failure (evidence run 29210211230 / WINSLICE-29210211230; same red on 29209854844):
    - semantic.types.syntax.TypeSyntaxUnionOptionalTddTest#parsePrefixAcceptsCompleteUnionAndOptionalPrefix[jvm]
  - Observed Windows AssertionError: expected complete union for `string | number, note` as `UnionTypeSyntax(options=[NamedTypeSyntax(name=string), NamedTypeSyntax(name=number)])` but was `MultiReturnTypeSyntax(types=[UnionTypeSyntax(...), NamedTypeSyntax(name=note)])` — comma after complete union incorrectly continues as multi-return instead of boundary stop with remainder.
  - `parsePrefix("string | number")` yields full UnionTypeSyntax with empty remainder; `parsePrefix("string?  rest")` yields NullableTypeSyntax and remainder `rest`.
  - Broken continuation `string | trailing prose` stops before `|` (or documents remainder starting with `|`) without throw.
  - Trailing comment / boundary chars (`) ] } > , | &`) and prose tails do not drop a complete second union arm nor absorb following identifiers as multi-return arms when the arm is a valid type prefix boundary stop.
  - Invalid full-parse union/optional still throws TypeSyntaxParseException / parseOrNull null without host crash.
  - TypeSyntaxUnionOptionalTddTest parsePrefixAcceptsCompleteUnionAndOptionalPrefix green without CURRENTLY_ACCEPTS weaken.
required_tests:
  - Deferred to TASK-043 / windows-jvmtest slice s017: `./gradlew.bat jvmTest --tests semantic.types.syntax.TypeSyntaxUnionOptionalTddTest.parsePrefixAcceptsCompleteUnionAndOptionalPrefix`
  - Deferred to TASK-043: `... --tests semantic.types.syntax.TypeSyntaxParserTest`
notes:
  - Windows slice s017 run 29210211230: parsePrefix still red (comma tail becomes MultiReturnTypeSyntax absorbing `note`).
  - 2026-07-12T REVIEW-GOAL-PATH-W1R-20260712: NOT ACCEPTED (status remains review). compileKotlinJvm blocked by TASK-538 LuaLanguageService.kt:921 Unresolved reference 'nodeAt'; required_tests not executed this wave. Re-queue after compile green.
  - 2026-07-12 MODULE-REVIEW-semantic-GOAL-PATH: REVIEW20 historically red on parsePrefixAcceptsCompleteUnionAndOptionalPrefix / invalidUnionAndOptionalSyntax. Product parsePrefix + allowBoundaryStop exists; lock hard complete-union prefix for doc-comment consumers.
  - Conflict: TypeSyntaxParser exclusive this wave among type-syntax tasks.
  - Workers no Gradle. One task one agent. No docs.
related_locks:
  - locks/tasks/TASK-559.lock
  - locks/files/tasks__TASK-559.md.lock
  - locks/files/src__commonMain__kotlin__io__github__dingyi222666__luaparser__semantic__types__syntax__TypeSyntaxParser.kt.lock
related_commits: []
progress:
  - 2026-07-12T22:01:00Z worker-WINSLICE-29210211230-TASK-559: product fix — parsePrefix (allowBoundaryStop) disables top-level multi-return so `string | number, note` keeps UnionTypeSyntax and remainder `, note` instead of MultiReturnTypeSyntax absorbing `note`. Nested function multi-return still works via allowMultiReturn=true. Expanded TypeSyntaxParserTest comma-boundary case. No Gradle (worker-forbidden). Status->review.
  - 2026-07-12T21:59:24Z worker-WINSLICE-29210211230-TASK-559: claimed TASK-559; fixing parsePrefix so top-level multi-return is disabled under allowBoundaryStop (comma after complete union stops with remainder instead of absorbing prose as MultiReturnTypeSyntax).
  - 2026-07-13T materialize WINSLICE-29210211230: re-ready from review; AC evidence updated to s017 run 29210211230 (parsePrefixAcceptsCompleteUnionAndOptionalPrefix still red: MultiReturnTypeSyntax absorbs `, note`).
  - 2026-07-11T17:44:08Z worker-GOAL-PATH-W1-20260712-TASK-559: claimed TASK-559; implementing parsePrefix complete-union/optional boundary (incl. trailing comment + delimiter boundaries).
  - 2026-07-11T17:45:24Z worker-GOAL-PATH-W1-20260712-TASK-559: product fix — canParseContinuation treats Lua -- line comments as valid arm boundaries (in addition to EOF and delimiters , | & ) ] } >) so parsePrefix keeps complete union/optional arms before comments and delimiter tails; prose still stops before |. Expanded TypeSyntaxUnionOptionalTddTest + TypeSyntaxParserTest coverage. No Gradle (worker-forbidden). Status->review.
