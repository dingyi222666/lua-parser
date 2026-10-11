# luaparse grammar corpus

Source: https://github.com/fstirlitz/luaparse

Pinned commit: `0f525b152516bc8afa34564de2423b039aa83bc1`.

`cases.json` contains all 735 source strings from the generated grammar fixtures
under `test/spec/*.js`, excluding `misc.js` (JavaScript API tests, not grammar
fixtures). The original sources and their case IDs are preserved. The adjacent
`LICENSE` is the upstream MIT license.

The fixture set mixes Lua 5.1, 5.2, 5.3 and LuaJIT options. This project supports
Lua 5.3/5.4 and AndroLua 5.3, so expected acceptance and top-level statement kinds
were independently recomputed by **the pinned upstream parser**, on the remote
server, with `luaVersion: '5.3'` and the other original options unchanged.
`originalAccept` and `originalLuaVersion` retain the upstream fixture policy.
The original `extendedIdentifiers: true` option on `local:55` is retained and
passed to the tested parser. All other cases use the upstream default (`false`).
This keeps the identical Unicode sources in `local:54` and `local:55` testing
their distinct original lexical policies; no acceptance expectation was changed.
42 cases change acceptance under the supported dialect; the Lua 5.3 oracle
accepts 395 cases and rejects 340. No failing product cases are excluded.

`UpstreamLuaparseCorpusTest` checks strict parsing and the top-level AST kinds,
and requires grammar errors to be normal parser exceptions rather than crashes.

On 2026-10-10 all 735 cases passed remotely, together with the complete 2860-test
JVM suite. Strict Lua parsing validates loop/function boundaries, vararg usage,
assignment targets, and goto label/local lifetimes. Editor recovery continues to
accept incomplete trees, and AndroLua retains its control-flow extensions.
