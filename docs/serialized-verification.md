# Serialized Verification Workflow

## Permanent Rule

Parallel code, task metadata, and documentation waves are allowed when workers hold non-overlapping file locks. Gradle, tests, compile commands, and any command that deletes or writes Gradle build outputs may run only in a dedicated serialized verification phase.

During a parallel wave, each task's `required_tests` entries are deferred acceptance references. They describe what the later verification phase must run; they are not permission for that worker to execute Gradle, tests, compile tasks, or build-output cleanup.

The current serialized verification task is TASK-043. A later review-created verification task may replace it, but the same one-at-a-time policy and lock requirements apply.

## Locks

Every worker must create locks before editing. Lock files use this metadata shape:

```text
task_id: TASK-...
owner_agent: ...
scope: ...
reason: ...
created_at: ...
last_heartbeat_at: ...
lease_timeout: 30m
status: active
```

Parallel wave workers hold only their task and scoped file locks, such as:

- `locks/tasks/TASK-049.lock` for task ownership.
- `locks/files/docs__serialized-verification.md.lock` for the edited documentation file.
- `locks/files/tasks__TASK-049.md.lock` for task progress recording.

Serialized verification workers must additionally hold the build and execution-slot locks for the whole verification run:

- `locks/tasks/TASK-043.lock` or the active serialized verification task lock.
- `locks/files/build.lock` for `build/` outputs.
- `locks/files/.gradle.lock` for `.gradle/` state.
- `locks/files/gradle-daemon.lock` for Gradle daemon ownership.
- `locks/files/tasks__TASK-043.md.lock` or the active verification task progress file.

If verification needs another shared generated path, the review agent must add an explicit lock before the command is started.

## Build Output, Daemon, And Test Slot Handling

The verification task owns one execution slot. While that slot is active, no other worker may start `./gradlew`, `gradle`, `compile*`, `test`, `jvmTest`, or cleanup commands that touch Gradle outputs.

The verifier must treat `build/`, `.gradle/`, daemon processes, and test JVMs as shared infrastructure. They are locked together because Gradle commands can delete, recreate, or reuse state across those paths and processes.

The verifier may stop or restart daemons only while holding the daemon and build-output locks. Parallel wave workers must not stop daemons as part of their task cleanup unless a review agent has converted the wave into serialized verification or recovery.

## One Command At A Time

The verifier runs exactly one Gradle command at a time. Do not open multiple terminals, spawn background Gradle work, or start a second filter while the first command is still running.

For each command:

1. Confirm the serialized verification locks are active and current.
2. Record the command in the verification task progress before or immediately after starting it.
3. Wait for the command to exit.
4. Record the result before starting the next command.

If a command hangs or times out, resolve that command and record the outcome before any later command starts.

## Focused Command Matrix (TASK-243 refresh)

Snapshot date: 2026-07-11 (docs-only matrix from task metadata and REVIEW19 accepts; this page does **not** authorize parallel workers to run Gradle).

Environment shape used by review-owned serial verification (Windows coordinated wave path):

```bash
JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11
# PATH must include %JAVA_HOME%\bin before other JDKs
```

On macOS hosts the same filters apply with a local JDK 17 `JAVA_HOME` and `./gradlew` instead of `./gradlew.bat`.

### Compile gate (always first when released)

| Order | Command | Role |
| --- | --- | --- |
| 1 | `JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11 ./gradlew.bat compileTestKotlinJvm` | Shared JVM test compile gate. If this fails, skip focused `jvmTest` filters until a review-created compile fix lands. |

### Review-accepted focused filters (TASK-144 / TASK-152 / TASK-156)

These filters are the current **local** accepts recorded by REVIEW19-WAVE-20260711-031147. They are deferred acceptance references for TASK-043; they do **not** clear global green and do **not** replace the full suite.

| Source task | Focused filter command | Evidence role |
| --- | --- | --- |
| TASK-144 | `JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11 ./gradlew.bat jvmTest --tests parser.ast.CompactCallAstShapeTddTest` | Compact short-call AST shape |
| TASK-144 | `JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11 ./gradlew.bat jvmTest --tests source.AST2LuaRoundTripTest` | AST-to-Lua roundtrip for compact calls and related printer fixes |
| TASK-152 | `JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11 ./gradlew.bat jvmTest --tests semantic.interop.JavaChainedCallTddTest` | Listener/callback setter assignability modeling |
| TASK-156 | `JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11 ./gradlew.bat jvmTest --tests lsp.LspWorkspaceFoldersTddTest` | Workspace-folder indexing and unopened-file diagnostics |
| TASK-156 | `JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11 ./gradlew.bat jvmTest --tests lsp.LspNavigationSymbolsTddTest` | Navigation/completion/signature/hover surfaces over indexed workspace |

Recommended serial order when review re-checks these accepts after a compile gate:

1. `compileTestKotlinJvm`
2. `parser.ast.CompactCallAstShapeTddTest`
3. `source.AST2LuaRoundTripTest`
4. `semantic.interop.JavaChainedCallTddTest`
5. `lsp.LspWorkspaceFoldersTddTest`
6. `lsp.LspNavigationSymbolsTddTest`

### TASK-043 baseline and additional filters

TASK-043 remains the sole owner of serialized execution. Its baseline `required_tests` currently list:

| Command | Notes |
| --- | --- |
| `JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11 ./gradlew.bat compileTestKotlinJvm` | Compile gate |
| `JAVA_HOME=C:/Users/dingyi/.jdks/temurin-17.0.11 ./gradlew.bat jvmTest --tests lsp.LspNavigationSymbolsTddTest` | Historical LSP gate retained on TASK-043 |

Additional focused filters are selected by the review agent at release time from open or recently accepted tasks' `required_tests` entries (including the TASK-144/152/156 matrix above and any later corpus/fixture tasks). Run each selected filter as its own one-at-a-time command.

### Full suite remains deferred to TASK-043

- Do **not** treat this matrix as permission to run `./gradlew check`, full `jvmTest`, or any broad suite from a parallel worker.
- Full-suite and campaign inventory reconciliation stay deferred to TASK-043 (and post-verification ledger tasks such as TASK-105/TASK-106 once verification is released).
- Parallel workers record their focused commands in task metadata only; execution and pass/fail/infrastructure classification remain review-owned under TASK-043.

## Task Progress Recording

Implementation and documentation workers record what changed, what was intentionally left alone, and that required Gradle verification is deferred. They then release their task and file locks.

The serialized verification worker records:

- The exact command that ran.
- Whether it passed, failed due to production behavior, failed due to test expectations, or failed due to infrastructure.
- Any follow-up task needed for source, test, fixture, or documentation changes.
- Any locks reclaimed or recovery steps taken.

Source or test edits discovered during verification must become separate implementation tasks unless the review agent explicitly expands the verification task scope.

## Recovery Guidance

Build-output deletion contention: if Gradle reports that `build/` files or classes cannot be deleted, assume another process touched shared outputs or a previous command did not release handles. Stop starting new commands. Confirm the verifier owns `build.lock`, `.gradle.lock`, and `gradle-daemon.lock`, inspect for active Gradle or test JVM processes, stop only the processes owned by the serialized verification slot, then rerun the affected command after recording the recovery action.

Daemon disappearance: if the daemon exits or disappears during a command, record it as infrastructure failure unless the command already produced a definitive test failure. Keep the daemon lock, start the next Gradle attempt only after the prior process has fully exited, and record whether the rerun passed or exposed a production/test failure.

Stale verification locks: if a serialized verification lock is `active` but its `last_heartbeat_at` is older than the `lease_timeout`, do not silently overwrite it. A review agent must confirm the owner is gone, record the reclaim decision in task progress, replace or remove the stale lock, and then create fresh active locks for the new verifier.

Wave-to-verification transition: verification may start only after the parallel wave has finished and all wave locks are released or formally reclaimed. If any worker lock remains active and current, the verifier waits.

After verification: release the build, daemon, task, and task-progress locks. Leave task progress notes with the final pass/fail/infrastructure status so later workers do not need to infer results from build output directories.
