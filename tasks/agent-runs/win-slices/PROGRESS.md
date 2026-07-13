# Windows slice progress (must-green-to-advance)


- strategy: must-green-to-advance
- chunkSize: 45 files/run (~3x prior 15)
- summary: success=17 failure=0 pending=1 total=18 filesDone=345/367
- active: s018
- updatedAt: 2026-07-13T02:54:18Z

[x] s001 success files=15 — integration..interop.jvm
[x] s002 success files=15 — interop.jvm..lsp
[x] s003 success files=15 — lsp
[x] s004 success files=15 — lsp
[x] s005 success files=15 — lsp
[x] s006 success files=15 — lsp..parser
[x] s007 success files=15 — parser..parser.ast
[x] s008 success files=15 — parser.ast..parser.lexer
[x] s009 success files=15 — parser.lexer..parser.recovery
[x] s010 success files=15 — parser.recovery
[x] s011 success files=15 — parser.recovery..semantic
[x] s012 success files=15 — semantic..semantic.androidlua
[x] s013 success files=15 — semantic.api..semantic.binder
[x] s014 success files=15 — semantic.binder..semantic.checker
[x] s015 success files=45 — semantic.checker..semantic.interop
[x] s016 success files=45 — semantic.interop..semantic.types.resolve
[x] s017 success files=45 — semantic.types.resolve..semantic.workspace
[ ] s018 pending files=22 — semantic.workspace..testinventory

