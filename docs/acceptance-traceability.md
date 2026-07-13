# Acceptance Criteria Traceability Matrix

Post-TASK-043 final matrix (TASK-106). Parent criteria map to **Windows tip-coherent full `jvmTest`** evidence owned by TASK-043 run **29228040252**. Ledger: `docs/final-verification.md` (TASK-105). Final audit: TASK-037.

**Honesty bound:** Suite green = 5386 tests, 0 failures, 0 errors, 177 skipped on SHA `cd188239b7a52744c9519e8d15169808a9f7e2c2`. Soft-skips remain possible for missing optional capability paths; hard failures are zero.

Status labels:

- `Pass`: command-level evidence from TASK-043 full suite + supporting docs/tasks
- `Pass (source + suite)`: campaign source floor and suite exit 0 both recorded
- `Deferred product backlog`: optional non-blocking open tasks outside accepted suite evidence

## Host / environment (Windows authority)

| Item | Value |
| --- | --- |
| Branch | `windows-verify` |
| Suite SHA | `cd188239b7a52744c9519e8d15169808a9f7e2c2` |
| Action | https://github.com/dingyi222666/lua-parser/actions/runs/29228040252 |
| JDK | `C:\Users\dingyi\.jdks\temurin-17.0.11` |
| ANDROID_HOME | `C:\Users\dingyi\AppData\Local\Android\Sdk` |
| CPU cap | affinity `0x3F`, workers.max=5 |
| android.jar policy | dual-path discovery; never invent `G:/` |

macOS paths in older drafts are host-local inventory only; they are **not** the CI authority for this matrix.

## Matrix

| ID | Parent acceptance area | Evidence | Status |
| --- | --- | --- | --- |
| AC-01 | ≥500 new campaign tests exist and pass | Source: campaign **307/4857**, `remaining_to_500=0` (`docs/test-strategy.md`, NewTestInventory). Suite: full jvmTest **5386/0 fails** run 29228040252. | **Pass (source + suite)** |
| AC-02 | Lua parser, lexer, Lua 5.3, Android-Lua syntax | Full suite includes `parser.*` / lexer / androidlua classes under tip SHA; 0 hard fails. Slice bar s001–s018 green supporting. | **Pass** |
| AC-03 | AST shape, clone, visitor, AST2Lua round-trip | Full suite includes `parser.ast.*`, `source.*` (incl. AST2LuaIfElseif after TASK-676 policy lock); 0 hard fails. | **Pass** |
| AC-04 | Malformed-input parser recovery | Full suite includes `parser.recovery.*` (duplicate-else / lambda reject fixes in 06d0806 wave); 0 hard fails. | **Pass** |
| AC-05 | Semantic model, binder, checker, types | Full suite includes `semantic.*` packages; 0 hard fails on tip SHA. | **Pass** |
| AC-06 | Workspace engine, require/export, overlays | Full suite includes workspace + BuiltinOverlay; corpus semantic rows green after WrapperLuaLexer fix (TASK-612). | **Pass** |
| AC-07 | JVM interop / LuaJava / android.jar reflection | Full suite includes `interop.jvm.*`, `semantic.interop.*`; dual-path jar policy; 0 hard fails. | **Pass** |
| AC-08 | Android-Lua library stubs / helpers | Full suite includes `semantic.androidlua.*` (loadbitmap surface TASK-680); 0 hard fails. | **Pass** |
| AC-09 | Integration / external corpus | Full suite includes `integration.*` corpus semantic verification; 0 hard fails on 29228040252. | **Pass** |
| AC-10 | LSP lifecycle, diagnostics, navigation, signature | Full suite includes `lsp.*` (SignatureHelp generic labels TASK-627/670); 0 hard fails. | **Pass** |
| AC-11 | Test inventory accounting honesty | NewTestInventory bars aligned (TASK-677); suite includes inventory tests; remaining_to_500=0 documented with suite pass. | **Pass** |
| AC-12 | Serialized verification + final docs chain | TASK-043 **done** (full jvmTest evidence). TASK-105 ledger refreshed. This matrix TASK-106. TASK-037 consumes both. | **Pass** |

## Cascade

| Task | Status | Role |
| --- | --- | --- |
| TASK-043 | **done** | Serialized full jvmTest on Windows |
| TASK-105 | **done** | `docs/final-verification.md` ledger |
| TASK-106 | **done** | This matrix |
| TASK-037 | **done** (audit against 043/105/106) | Final acceptance audit |

## Non-blocking residual

Open product/docs tasks outside the accepted suite (historical `review`/`ready` queue) do **not** reopen run 29228040252 exit code. Future regressions require a new full suite run before revoking green.
