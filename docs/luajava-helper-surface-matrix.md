# LuaJava Helper Surface Matrix

Date: 2026-07-12  
Task: TASK-511 (docs-only refresh)

This matrix documents the **static-analysis support status** for the primary LuaJava helper APIs that Android-Lua scripts call through the global `luajava` table. It is a focused companion to:

- `docs/android-lua-import-luajava.md` — runtime `import.lua` / native LuaJava study notes  
- `docs/java-interop-model.md` — JVM type projection and helper mapping guide  
- `docs/android-lua-compatibility-matrix.md` — broader Android-Lua compatibility claims (post-TASK-184 authority for chain status)  
- `docs/android-platform-setup.md` — host SDK / `jvm.androidJar` configuration  

**Honesty bound (post-TASK-184 / pre-TASK-043):** entries describe the current model, fact collection, and expression-evaluation shape in this repository after the Android-Lua / LuaJava product-implementation wave. TASK-184 library-stub restoration is **done** in task metadata; the historical chain **TASK-169 → TASK-140 → TASK-176 → TASK-184** is likewise **done**. That does **not** claim final green verification, does **not** unlock **TASK-043** (serialized Gradle/test verification, still `blocked`), and does **not** unlock **TASK-037** (final acceptance, still `blocked`). **Inventory is not final until TASK-043.** No Gradle, compile, or test command was run for this documentation task.

## Open verification gate (post-chain honesty)

| Task | Title | Task status (metadata) | Relevance to this matrix |
| --- | --- | --- | --- |
| TASK-169 | Collect transitive LuaJava helper alias facts | `done` | Fact collection / provider-mount for source-discovered `bindClass` / `newInstance` loads and transitive local helper-alias document facts. |
| TASK-140 | Complete transitive LuaJava helper alias resolution | `done` | Evaluator/reference resolution for multi-hop local aliases such as `local bindClass = luajava.bindClass; local bind = bindClass; local again = bind`. Focused serial `LuaJavaBindClassTddTest` was accepted green under review. |
| TASK-176 | Preserve Android-Lua import surfaces under scoped activation | `done` | Path-scoped import/workspace context that can feed helper provider mounts; not a LuaJava helper row by itself. |
| TASK-184 | Restore Android-Lua library stub fixture and type surfaces | `done` | Android-Lua globals/overlays that sit beside `luajava` (`activity`, `loadlayout`, …). Does not replace helper-matrix rows. |
| TASK-043 | Serialized Gradle verification phase | **`blocked`** | Full/serial suite verification. **Do not claim final green.** |
| TASK-037 | Final green verification and acceptance audit | **`blocked`** | End-to-end acceptance after honest TASK-043 evidence. |

Claim policy:

- Direct string-literal helpers and multi-hop local alias chains are **modeled** (and, for bindClass chains, focused-review accepted under TASK-140).  
- None of TASK-169 / TASK-140 / TASK-176 / TASK-184 alone unlocks TASK-043 or TASK-037.  
- This page remains **inventory / model documentation**, not suite evidence.

## Host toolchain and `android.jar` dual-path policy (macOS)

Useful concrete helper resolution for Android framework classes still depends on workspace JVM metadata (`jvm.androidJar`, `jvm.classpath`, `jvm.classes`, import prefixes). On **this** macOS host, documentation and operator checks must use **only** these candidates. **Never hard-code Windows `G:/` paths.**

| Priority | Path | Role | Host status (2026-07-12 TASK-511 re-check) |
| --- | --- | --- | --- |
| 1 (preferred for reproducible analysis) | Explicit metadata `jvm.androidJar` | Caller-supplied single platform jar | Set to the SDK path below when configuring analysis/tests |
| 2 | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | Android SDK Platform 35 jar (`ANDROID_HOME` / `ANDROID_SDK_ROOT` typically `/Users/dingyi/Library/Android/sdk`) | **Present** (~27,092,450 bytes) |
| 3 (optional copy) | `/Users/dingyi/Downloads/android.jar` | Operator drop-in copy of a platform jar | **Absent** on this host; allowed when present as an **explicit** metadata override only |

JDK for product/test reflection on this host:

```text
Amazon Corretto 17
/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
```

Note: the shell default `java` on this host may resolve to a newer OpenJDK. Prefer Corretto 17 for Gradle/JVM-test alignment with the project toolchain. Workers for docs tasks must **not** run Gradle/tests/compile; deferred verification remains review-owned under TASK-043.

Without a resolvable `android.jar` / classpath, the parser and overlays still expose the `luajava` helper **signatures**, but class/instance/array element surfaces for Android framework targets remain unresolved/`unknown` per the degrade policy below. JDK `java.lang.*` / `java.util.*` may still resolve from the running JVM reflection path when configured.

Companion docs: `docs/android-platform-setup.md`, `docs/jvm-reflection-classloader-design.md`, `docs/android-lua-verification.md`.

## Status labels

| Status | Meaning |
| --- | --- |
| Supported (string-literal / modeled) | Direct helper call with statically known string (or bound class) arguments has a declaration overlay, fact path, and evaluator return surface when classpath providers can resolve the target. Final suite green is still pending TASK-043. |
| Modeled (awaiting TASK-043) | Implementation and focused review acceptance exist for the named surface (including transitive local helper-alias resolution after TASK-140), but this matrix still refuses a “final green” claim until serialized TASK-043 evidence is recorded. |
| Partially supported | Common static shapes are modeled; overload fidelity, runtime conversion, provider completeness, or non-alias edge cases remain incomplete or intentionally conservative. |
| Deferred / not modeled as concrete | Runtime behavior exists in Android-Lua/LuaJava, but static analysis only preserves parse/facts or intentionally returns unknown. |

> Note: the older **Pending chain (TASK-140 / TASK-169)** label is **retired** for this refresh because those tasks are `done`. Residual risk for alias/hover/member surfaces is expressed as **Modeled (awaiting TASK-043)** or **Partially supported**, never as final green.

## Primary helper matrix

| Helper | Runtime role (Android-Lua/LuaJava) | Overlay declared | Document facts | Expression evaluation (direct) | Local aliases | Support status | Unknown / degrade policy |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `luajava.bindClass` | Bind a fully qualified class name (including primitive names such as `"int"`) to a Java class userdata/module. | Yes — `std/androlua53-luajava/luajava.lua` returns `JavaClass<any>`. | `BIND_CLASS_CALL` when first arg is a string literal (direct `luajava.bindClass` or unshadowed alias). | Returns resolved class `ModuleType` for string-literal targets when a provider can resolve the class; otherwise analysis does not invent members. | Simple local aliases and multi-hop chains are collected as facts (TASK-169) and resolved on the evaluator/reference path (TASK-140 focused acceptance). | **Supported (string-literal / modeled)** for direct `luajava.bindClass("…")`. **Modeled (awaiting TASK-043)** for transitive local alias chains. | **Dynamic / non-string class name:** no concrete class resolution; no `BIND_CLASS_CALL` fact; result typing falls through to ordinary call typing / `unknown` rather than inventing a class module. **Unresolved class name string:** no provider mount success → no fabricated static members; hover/members stay unknown-ish. **Colon form** `luajava:bindClass(...)`: intentionally **not** treated as the helper (facts skipped; evaluator returns `unknown` for colon helper calls). **Shadowed `luajava` local** or local function named `bindClass` that is not a true helper alias: no helper semantics. |
| `luajava.newInstance` | Bind class name and invoke a matching constructor; returns a Java object instance. | Yes — returns `JavaObject`. | `NEW_INSTANCE_CALL` for string-literal class name (first arg). | Returns instance surface (`ModuleType.__class` / hydrated instance) for string-literal targets when the class module resolves; otherwise `unknown`. | Same alias/fact story as `bindClass` (TASK-169 facts + TASK-140 multi-hop resolution). | **Supported (string-literal / modeled)** for direct calls. **Modeled (awaiting TASK-043)** for transitive aliases. | Same string-literal gate as `bindClass`. Missing/unresolved target → `unknown` instance surface (no invented members). Dynamic class name → no fact, no concrete instance type. Colon-call / shadowing guards apply. Constructor overload fidelity remains permissive (runtime reflection is not fully simulated). |
| `luajava.newArray` | Allocate a typed Java array from a **bound class userdata** plus one or more dimensions. | Yes — `newArray(class, ...integer) → JavaArray<any>`. | **No dedicated `JvmClassLoadKind` for `newArray`.** Facts are not recorded from the array call itself; element-class provider mounting typically comes from a prior `bindClass`/`import` of the component class. | When the first argument evaluates to a bound class/module and dimensions are valid, result is `JavaArrayType` with that element surface (for example `java.util.Locale[]`). Indexing yields the element type; `.length` is `number` via the Java array member surface. | Local aliases of `newArray` are recognized by the evaluator when the alias resolves to the helper. | **Partially supported** (typing path present; fact kind absent; dimension validation is conservative). | **Unknown degrade policy (explicit):** invalid or missing dimensions (arity &lt; 2, zero, negative, nil, non-numeric, unary `-n`) force element type **`unknown`**, so the result is modeled as **`unknown[]`** rather than keeping a known `Class[]` surface. Dynamic/untyped first argument → element type `unknown` → `unknown[]`. This is intentional conservatism for hover/index typing (TASK-246). |
| `luajava.createArray` | Build a typed Java array from a **class name string** and a Lua table of values. | Yes — `createArray(className, values) → JavaArray<any>`. | `CREATE_ARRAY_CALL` for string-literal element class (first arg). Feeds provider class collection. | String-literal class name → `JavaArrayType` with reflected/primitive element surface (`string[]`, `number[]`, `java.util.Locale[]`, …). | Local aliases supported on the evaluator path. | **Supported (string-literal / modeled)** for typing + facts. | Non-string / dynamic class name → no fact targets; element type degrades to **`unknown`** (`unknown[]`). Unresolved class name → `unknown` element. Table value contents are not deeply type-checked as Java conversion. |
| `luajava.createProxy` | Create a Java interface proxy from interface name(s) and a Lua callback table (Android-Lua also accepts comma-separated names and multi-string args before the table). | Yes — returns `JavaProxy`; overloads documented for multi-interface forms. | `CREATE_PROXY_CALL` for each string-literal interface target (comma lists split; multiple string args collected). | Intersection of resolved interface instance surfaces when at least one target resolves; if recognized as createProxy but no interface type resolves → **`unknown`**. | Local aliases of `createProxy` are recognized. Multi-interface TDD coverage exists separately from bindClass chain work. | **Partially supported** — static proxy intersection typing for string-literal interfaces; no runtime proxy construction or method-table validation. | No string-literal interface args → helper typing path does not apply (no invented proxy). Unresolved interface names → `unknown`. Dynamic interface name expressions are not resolved. Colon-call / shadowing guards apply. Callback table shape is not fully validated as a Java method map. |
| `luajava.loadLib` | Call a Java static loader/member by class name + method/field name (`LuaJavaAPI.loadLib` style); returns the static member/result shape used by analysis. | Yes — `(className, methodName) → any`. | `LOAD_LIB_CALL` when **both** class name and member name are string literals (first two args). | Resolves the named static method/field on the reflected class module when both strings are known and the class/member exist; otherwise **`unknown`**. | Local aliases of `loadLib` are recognized when unshadowed. | **Partially supported** — package/function surface for string-literal class + member; not a native `.so` loader. | Missing either string literal → no concrete loadLib typing / no complete fact targets for that call shape. Unresolved class or missing member → **`unknown`**. This is **not** modeled as `package.loadlib` / JNI `.so` search (that path is deferred; see compatibility matrix “Native library loading”). Colon-call / shadowing guards apply. |

## Related helpers (declared, lighter analysis)

These appear on the same `luajava` overlay and in the Android-Lua native export list but are **not** the five primary matrix rows above. Status is summarized so the surface inventory is not incomplete by omission.

| Helper | Overlay | Static analysis notes |
| --- | --- | --- |
| `luajava.new` | Yes — construct from bound class userdata. | Constructor-style calling is largely covered by treating bound class modules as callable (`Foo(...)` / `__call`), not by a dedicated `NEW` fact kind. |
| `luajava.astable` | Yes | Declaration-only / generic conversion; no deep collection typing. |
| `luajava.tostring` | Yes | Declaration-only. |
| `luajava.instanceof` | Yes | Declaration-only boolean surface. |
| `luajava.getContext` | Yes | Declaration-only Android-Lua context surface. |
| `luajava.override` | Yes | Declaration-only override/subclass sugar; not full runtime enhancement modeling. |
| Package-chain indexing (`luajava.java.lang.String`) | Runtime via `import.lua` metatable | Static completion may expose package/class providers when JVM indexes are configured; not the same as `bindClass` fact collection. |

## Unknown degrade policy (summary)

Static analysis prefers **honest unknown** over invented Java types. The shared rules:

1. **String-literal (or bound class value) gate.** Concrete JVM class/interface/array element resolution requires a static string leaf for name-based helpers, or a previously typed bound class/module value for `newArray`. Dynamic expressions (`local n = ...; luajava.bindClass(n)`) do **not** resolve to concrete classes.
2. **No provider ⇒ no members.** A recognized helper call with a literal name that is not on the configured classpath/`jvm.androidJar`/`jvm.classes` still must not fabricate fields or methods. Results stay `unknown` or an empty/unresolved class surface.
3. **`newArray` dimension gate.** Invalid dimensions always degrade the **element** type to `unknown` (`unknown[]`), even if the component class is known. Valid dimensions keep the element surface.
4. **Array helpers without a known element type** produce `unknown[]`, not a pretend `any[]` with rich members.
5. **`createProxy` without resolvable interfaces** returns `unknown`, not a synthetic empty proxy with guessed methods.
6. **`loadLib` without both literal names or without a reflected member** returns `unknown`.
7. **Colon-form helper calls** (`luajava:bindClass`, etc.) are **not** the LuaJava helper surface; analysis degrades to `unknown` / skips helper facts so colon sugar cannot accidentally mount JVM providers.
8. **Shadowing.** A local named `luajava`, or a local function that merely shares a helper name without aliasing `luajava.*`, must not inherit helper semantics.
9. **Permissive where runtime is reflection-heavy.** Constructor overload choice, Lua table→Java conversion, and exact proxy validation stay useful-but-permissive; failures degrade to diagnostics/`unknown` rather than hard false runtime claims.
10. **Native / dex loading** (`import.lua` `libsloader`, dex class loaders, `package.loadlib`) is outside these helpers’ static success path; do not treat `loadLib` success as proof of native library resolution.

## Alias and fact collection notes

| Concern | Current policy |
| --- | --- |
| Fact kinds recorded | `IMPORT_CALL`, `BIND_CLASS_CALL`, `NEW_INSTANCE_CALL`, `CREATE_PROXY_CALL`, `LOAD_LIB_CALL`, `CREATE_ARRAY_CALL` (`DocumentFacts.JvmClassLoadKind`). |
| `newArray` facts | Not a separate load kind; rely on prior bind/import of the component class for provider mounts. |
| Local helper aliases | `DocumentFactsCollector` tracks aliases for import/bindClass/newInstance/createProxy/loadLib/createArray (and related array alias evaluation). |
| Transitive alias evaluation | Multi-hop chains such as `local bindClass = luajava.bindClass; local bind = bindClass; local again = bind` are **modeled** after TASK-169 (facts) + TASK-140 (evaluator/reference; focused `LuaJavaBindClassTddTest` review-accepted). Status on this matrix: **Modeled (awaiting TASK-043)** — do **not** treat “TASK-140 done” as a substitute for full serialized suite green. |
| Provider mounting | `JvmWorkspaceEngine` uses class-load facts (including the kinds above) to request reflective providers; facts without providers still do not invent members. |

## Fixture / TDD cross-links (verification still TASK-043-owned)

| Surface | Representative tests (under `src/jvmTest/kotlin/semantic/interop/`) |
| --- | --- |
| `bindClass` / aliases / colon guards | `LuaJavaBindClassTddTest`, `LuaJavaHelperShadowingTddTest` |
| `createProxy` | `LuaJavaCreateProxyTddTest`, `LuaJavaCreateProxyMultiInterfaceTddTest` |
| `loadLib` | `LuaJavaLoadLibSurfaceTddTest` |
| `createArray` / array helpers | `LuaJavaArrayHelpersTddTest` |
| `newArray` typing + unknown[] degrade | `LuaJavaNewArrayTypingTddTest` |

Campaign Lua samples also live under `src/jvmTest/resources/semantic/campaign-java-android/` (for example `luajava_helper_campaign.lua`, `luajava_alias_campaign.lua`).

These paths are documentation cross-links only. Listing them does **not** prove current suite green. Any deferred serial command evidence remains TASK-043-owned. Preferred review host form (not run by this docs worker):

```bash
export JAVA_HOME=/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home
# example filter only — selection is review-owned under TASK-043
./gradlew jvmTest --tests semantic.interop.LuaJavaBindClassTddTest
```

## Configuration prerequisites

Useful concrete helper resolution still depends on workspace JVM metadata (`jvm.androidJar`, `jvm.classpath`, `jvm.classes`, import prefixes). Without those, the parser and overlays still expose the `luajava` helper **signatures**, but class/instance/array element surfaces remain unresolved/`unknown` per the degrade policy above.

macOS operator example metadata path (SDK jar present on this host):

```text
jvm.androidJar = /Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar
```

Optional Downloads override only when the file exists (currently **absent** here):

```text
/Users/dingyi/Downloads/android.jar
```

## Related docs

- `docs/android-lua-import-luajava.md`  
- `docs/java-interop-model.md`  
- `docs/android-lua-compatibility-matrix.md`  
- `docs/android-lua-library-models.md`  
- `docs/android-platform-setup.md`  
- `docs/jvm-reflection-classloader-design.md`  
- Overlay source of truth: `src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/std/androlua53-luajava/luajava.lua`

## Docs refresh notes (TASK-511)

- Docs-only; no product/test code edits; **no Gradle/tests/compile**.
- Aligns helper-matrix wording with post-TASK-184 / pre-TASK-043 honesty used by companion WAVE36F docs (for example TASK-499 compatibility matrix).
- Retires “pending TASK-140” product-chain language; residual alias risk is **Modeled (awaiting TASK-043)**.
- Records macOS Corretto 17 + android-35 dual-path jar policy (SDK present, Downloads absent, never `G:/`).
- Explicitly refuses final green / inventory-final claims until TASK-043.
