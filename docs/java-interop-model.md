# Java Interop Type Model

Status: implementation guide for the current JVM interop lane. Final behavior validation is pending TASK-043, and any Android-Lua integration claims must be reconciled with TASK-037 before this document is treated as a verified compatibility contract.

## Goals

The Java interop model lets Android-Lua and LuaJava-style code expose JVM classes through the workspace type system. It has two layers:

- The intended Java-specific model in `JavaInteropTypes.kt`, with `JavaClassType`, `JavaInstanceType`, constructor/member wrappers, overload sets, primitive types, and array types.
- The currently wired reflection provider surface in `JvmClassModuleProvider`, which projects reflected classes into existing `ModuleType` and `ClassType` values so the current checker, completion, require/import, and member-resolution paths can consume them.

New work should keep those layers aligned. The Java-specific model is the shape to extend when preserving JVM identity matters; the `ModuleType`/`ClassType` projection is the compatibility surface currently used by workspace snapshots and expression evaluation.

## Java Type Names

`JavaTypeName` stores the stable identity of a Java type:

- `packageName`: package prefix, or empty for default-package classes.
- `simpleNames`: top-level simple name followed by nested simple names.
- `canonicalName`: package plus simple names joined with `.`, for example `android.view.View.OnClickListener`.
- `binaryName`: package plus simple names joined with `$`, for example `android.view.View$OnClickListener`.
- `simpleName`, `topLevelName`, `innerClassNames`, and `hasInnerClassName` are derived helpers.

Use canonical names for user-facing text and import targets. Use binary names for reflection identity and cycle guards.

## JavaClass And JavaType Shapes

`JavaInteropType` is a `Type` with a `javaName`. Its default `name` is the Java canonical name.

`JavaClassType` represents the class object/type:

- `constructors`: `JavaOverloadSet<JavaConstructorType>`.
- `staticMembers`: map from member name to `JavaStaticMemberType`.
- `instanceMembers`: map from member name to `JavaInstanceMemberType`.
- `innerClasses`: public nested class surfaces keyed by simple name.
- `superClass` and `interfaces`: inherited class/interface surfaces.
- `typeParameters`: JVM generic parameters when available.
- It implements `CallableType`; calling a class returns `JavaInstanceType(this)`. Constructor parameter lists are preserved, and a no-argument callable fallback is exposed when no constructor overload is present.

`JavaInstanceType` represents an object instance. It points back to its `classType`, keeps optional `typeArguments`, displays as `Class<T>`, and exposes inherited instance members through `allInstanceMembers()`.

`JavaConstructorType` represents one constructor overload. Its `name` is `<canonical>.<init>`, its `signature` stores parameters, and its call signature is the constructor signature. `JavaClassType.callSignatures` rewrites those signatures to return the matching `JavaInstanceType`.

`JavaMemberType` is the shared contract for Java members. Static and instance members both carry:

- `owner`: declaring Java type name.
- `memberName`: field or method name.
- `memberKind`: `FIELD` or `METHOD`.
- `valueType`: field type or callable method/overload type.
- `visibility`: `PUBLIC`, `PROTECTED`, `PACKAGE_PRIVATE`, or `PRIVATE`.

`JavaOverloadType` represents an overloaded method or constructor-like callable with multiple `FunctionType` signatures. `JavaPrimitiveType` and `JavaArrayType` preserve Java primitive and array identity when the Java-specific model is used.

## Reflection Provider Surface

`JvmClassModuleProvider` builds synthetic workspace providers for reflected classes:

- Class providers live under `__jvm__/classes/<class-path>.lua`.
- Package providers live under `__jvm__/packages/<package-path>.lua`.
- Provider source is synthetic and small; the exported `ModuleExportSurface` carries the useful type/member data.

The current provider projection is:

- A Java class is a `ModuleType` named with `clazz.simpleName`.
- `fields["__class"]` is a `ClassType` for the instance surface.
- `fields["__call"]` is present when public constructors exist and contains a callable/overload whose return type is the reflected class reference.
- Public static fields become module fields.
- Public static methods become module methods, grouped into `OverloadedFunctionType` when needed.
- Public inner classes become module fields whose values are nested class modules.
- Public instance fields and instance methods live on the `ClassType`.
- The `ClassType` includes a superclass reference when reflection reports one other than `java.lang.Object`.

The current reflection-to-type conversion maps `void` to `nil`, booleans to `boolean`, numeric primitives/wrappers to `number`, `char`/`String`/`CharSequence` to `string`, arrays to `ArrayType`, unknown primitives to `any`, and other object references to a `ClassType` by fully qualified class name.

This projection is intentionally conservative. It does not yet expose every Java-specific model field from `JavaInteropTypes.kt` through the provider snapshots.

## Loading And Classloaders

`JvmWorkspaceConfiguration` controls reflection loading:

- `classLoader`: optional caller-provided classloader. When set, it is used directly.
- `classpathEntries`: directories or jars added to a `URLClassLoader`.
- `androidJar`: explicit Android platform jar path.
- `classes`: explicit class names to provide.
- `androluaImports`: import targets supplied through metadata.
- `importPrefixes`: prefixes used for unqualified imports.

Metadata keys:

- `jvm.classes`: comma, semicolon, or newline separated explicit classes.
- `androlua.imports`: newline separated Android-Lua import targets.
- `jvm.classpath`: newline separated classpath entries.
- `jvm.androidJar`: explicit android.jar path.
- `jvm.importPrefixes`: newline separated import prefixes.

If no explicit `androidJar` metadata is set, `reflectionClasspathEntries()` appends `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` when that file exists. This is a convenience fallback, not a portability guarantee; callers should provide `jvm.androidJar` or a custom classloader for reproducible Android analysis.

**Host android.jar policy (docs-only honesty):** only the host paths
`/Users/dingyi/Downloads/android.jar` and
`/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` may be
referenced. Never hard-code `G:/` or other machine-local drive letters. WAVE36E
host re-check: SDK `platforms/android-35/android.jar` is present (~27,092,450
bytes); Downloads copy may be absent. Prefer `jvm.androidJar` → SDK android-35 →
optional Downloads.

Default import prefixes are `java.lang`, `java.util`, `java.io`, `android.app`, `android.content`, `android.view`, `android.widget`, and `com.androlua`.

## Import Resolution

Import targets are normalized by removing an optional leading `import `. A target may include a path prefix before the last `:`, for example `classes.jar:com.example.Plugin` or `dexPath:android.widget.TextView`. The provider preserves both the prefix and the class target for diagnostics and future loaders.

Prefixed imports follow these rules:

- If the prefix names an existing JVM class-directory root or `.jar`, that entry is used as an additional child classloader for that import target.
- If the prefix looks like Android dex/apk input (`dex`, `apk`, `odex`, `vdex`, or literal `dexPath`) or is not an existing JVM classpath entry, the prefix is reported through `JvmClassModuleProvider.importDiagnostics(...)` with code `jvm.import.prefixed.unsupported`.
- Unsupported prefixes do not create a dex classloader. The class target may still resolve through the normal configured classloader, `jvm.classpath`, or `jvm.androidJar`; the diagnostic keeps the original prefix and target so callers can surface an unsupported-dex warning and later add real dex support without changing import parsing.

Class-name resolution then follows these rules:

- Fully qualified targets try candidate class names directly.
- Unqualified targets are tried under configured import prefixes.
- Wildcard targets ending in `.*` scan package contents and build a package module.
- Inner-class candidates include the original text, `_` replaced with `$`, and dot-to-dollar combinations.

Wildcard scanning reads classloader resources, directories, jars, JRT modules, and configured classpath entries. The package listing excludes `$` inner classes so wildcard imports expose top-level package classes by default. When a source imports `foo.bar.*`, `JvmWorkspaceEngine` also adds `foo.bar` to the import-prefix list so later unqualified class names can resolve through that wildcard package.

## LuaJava Helper Mapping

`DocumentFactsCollector` records string-literal JVM load facts for the helper surfaces the checker understands:

- `import "java.util.Locale"` and `require("import")` aliases produce `IMPORT_CALL`.
- `luajava.bindClass("java.util.Locale")` produces `BIND_CLASS_CALL`.
- `luajava.newInstance("java.lang.StringBuilder")` produces `NEW_INSTANCE_CALL`.
- `luajava.createProxy("java.lang.Runnable", table)` produces `CREATE_PROXY_CALL`.
- `luajava.loadLib("java.lang.System", "currentTimeMillis")` produces `LOAD_LIB_CALL`.
- `luajava.createArray("java.lang.String", values)` produces `CREATE_ARRAY_CALL` facts.

Local aliases are tracked for `import`, `bindClass`, `newInstance`, `createProxy`, and `loadLib`, including simple alias chains. String-literal targets are required for static analysis; dynamic class-name expressions are intentionally not resolved by this lane.

`JvmWorkspaceEngine` uses source imports and JVM class-load facts to request reflective providers and imported symbols. As currently wired, `IMPORT_CALL`, `BIND_CLASS_CALL`, `NEW_INSTANCE_CALL`, `CREATE_PROXY_CALL`, `CREATE_ARRAY_CALL`, and `LOAD_LIB_CALL` feed provider class collection. For array helpers, the string-literal element class target can request a reflective provider; this provider collection is separate from expression evaluation and should remain in place when helper return inference changes.

## Member, Constructor, And Chained Calls

The intended Java-specific shape is:

- `JavaClassType.staticMembers[name]` for static fields/methods on the class object.
- `JavaClassType.innerClasses[name]` for nested classes.
- `JavaClassType.constructors` for constructor overloads.
- `JavaInstanceType.allInstanceMembers()[name]` for object fields/methods, including inherited surfaces.
- Method overloads should be modeled as `JavaOverloadType` or an equivalent callable with multiple signatures.

The current provider/checker shape is:

- `import("Foo")` and `luajava.bindClass("pkg.Foo")` evaluate to the class `ModuleType`.
- Calling that module as `Foo(...)` returns `Foo.__class`, so constructor-style calls can produce the instance `ClassType` used for member chains.
- `luajava.newInstance("pkg.Foo")` returns `Foo.__class` directly.
- Static access such as `Foo.STATIC_FIELD` or `Foo.staticMethod` resolves from module fields/methods.
- Instance access such as `foo.instanceField` or `foo:instanceMethod(...)` resolves from the instance `ClassType`.
- Colon calls bind `self` when the reflected method signature does not already accept the receiver type.
- `luajava.loadLib("pkg.Foo", "bar")` returns the reflected static method or field named `bar`.
- `luajava.createProxy(...)` returns an intersection of the requested interface `ClassType` surfaces when they resolve (see **createProxy static model** below).
- `luajava.createArray("pkg.Foo", values)` returns a `JavaArrayType` whose element type is the reflected instance surface for `pkg.Foo` when the target resolves; primitive and common boxed/string targets collapse to the Lua-facing primitive element type used by the reflection projection.
- `luajava.newArray(Foo, ...)` returns a `JavaArrayType` whose element type is inferred from the bound class/module value passed as the first argument. Unknown or dynamic class values still fall back to `unknown[]` rather than pretending to know the element surface.

Java array helper results display with the existing array notation, for example `java.util.Locale[]`, `string[]`, or `number[]`. Numeric indexing returns the element type, and `.length` resolves as `number` through the Java array member surface.

Chained calls depend on reflected return types. For example, if `StringBuilder.append` is reflected as returning `java.lang.StringBuilder`, then `builder:append("x"):toString()` can continue through that returned class surface. If reflection only yields an unresolved `ClassType(name)` without populated members, later chain segments may need the class provider to be requested elsewhere or may remain unknown until provider expansion is improved.

## createProxy static model (TASK-454)

`luajava.createProxy` is the **explicit multi-interface / arbitrary-interface proxy helper**. Static analysis models interface targets and the resulting member surface; it does **not** construct JVM proxies, validate the Lua callback table as a complete Java method map, or execute handlers.

Companion matrix row: `docs/luajava-helper-surface-matrix.md` (`createProxy`). Runtime notes: `docs/android-lua-import-luajava.md` (Proxies and listeners). Overlay signatures live in `std/androlua53-luajava/luajava.lua` / `AndroidLua53LuaJavaBuiltinOverlaySources.kt` and declare return type `JavaProxy` with multi-string overloads.

### Call recognition

`ExpressionTypeEvaluator.resolveCreateProxyCall` treats a call as `createProxy` only when:

| Call base | Recognition rule |
| --- | --- |
| `luajava.createProxy(...)` | Member form with helper name `createProxy` on unshadowed `luajava` (`isLuaJavaHelperMember`). |
| Local alias | Identifier whose declaration resolves to the helper (`isCreateProxyAlias`), including simple alias chains such as `local createProxy = luajava.createProxy`. |
| Colon form `luajava:createProxy(...)` | **Not** a helper call. Facts are skipped and helper typing does not apply (returns through ordinary / `unknown` paths). |
| Shadowed / local function named `createProxy` that is not a true alias | **Not** a helper call. |

### Interface target extraction

Targets come only from **string-literal** arguments (table / function / dynamic expressions are ignored for class mounting):

1. Collect all string-literal call arguments (including string-call sugar arguments when present).
2. Split each literal on `,`, trim, drop empties (`splitCreateProxyTargetList`).
3. Multi-arg forms such as `createProxy("java.lang.Runnable", "java.util.Comparator", {})` yield one target per string arg.
4. Comma-list forms such as `createProxy("java.lang.Runnable, java.util.Comparator", {})` yield the same two targets.

`DocumentFactsCollector` records one `CREATE_PROXY_CALL` fact per split target (never a single fact whose target is the raw comma-joined string). `JvmWorkspaceEngine` uses those facts to request reflective providers for each interface name.

### Return typing

| Situation | Static result |
| --- | --- |
| Helper recognized and at least one string-literal interface resolves to a provider instance surface | `intersectionTypeOf(resolved interface instance surfaces)` — members from each resolved interface are available on the proxy (e.g. `proxy.run` and `proxy.compare`). |
| Helper recognized, string targets present, but **no** interface resolves | **`unknown`** — do not invent an empty `JavaProxy` with guessed methods. |
| No string-literal interface args | Helper typing path does not apply (`null` from `resolveCreateProxyCall`); no invented proxy. |
| Partial resolve (some names missing) | Intersection of the **resolved** subset only; missing names still leave their own diagnostics / unknown member paths when used. |

Overlay `JavaProxy` is the declaration/signature surface for hover and SignatureHelp labels. Expression evaluation prefers the **intersection of reflected interface instance surfaces** so member completion and chained access use real interface methods rather than an opaque `JavaProxy` shell.

### SignatureHelp (TASK-394)

When product SignatureHelp is produced for a recognized `luajava.createProxy` call (direct member or local alias), labels prefer the documented overloads that mention `interfaceNames` / multi-string interface args, `callbacks`, and `JavaProxy`. Null SignatureHelp remains dual-path acceptable when the call is not createProxy or the helper is not recognized. SignatureHelp does **not** validate the callback table body.

### Explicit non-goals

- **No runtime proxy construction.** Analysis never calls `Proxy.newProxyInstance` / `LuaObject.createProxy`.
- **No full callback-table validation.** Missing methods on the Lua table are not exhaustively checked as a Java method map; member access on the typed proxy still uses the intersection surface (invalid members may surface as ordinary missing-member diagnostics).
- **No dynamic interface names.** Non-string expressions do not mount providers or invent interfaces.
- **Not replaced by listener setter assignability.** Setter-style `*Listener` / `*Callback` function/table sugar (TASK-152) is a separate call-argument rule; multi-interface and arbitrary-interface proxies stay on `createProxy`.

### Fixture coverage (cross-links)

| Test class | Surface |
| --- | --- |
| `LuaJavaCreateProxyTddTest` | Comma-list split facts; single- and multi-interface members via intersection. |
| `LuaJavaCreateProxyMultiInterfaceTddTest` | Varargs multi-string args, aliases, partial missing interfaces, missing-method diagnostics, completion. |
| `LuaJavaCreateProxyColonGuardTddTest` | Colon-form / shadowing must not inherit helper semantics. |
| `LuaJavaCreateProxySurfaceTddTest` | SignatureHelp / surface labels (`interfaceNames` / `callbacks` / `JavaProxy`) after TASK-394. |

Serialized verification remains review-owned under TASK-043 (for example `./gradlew.lf jvmTest --tests semantic.interop.LuaJavaCreateProxy*`).

## Listener And Callback Setter Assignability

TASK-152 accepted a static call-compatibility rule for Java listener/callback setter arguments. The checker does **not** execute Lua callbacks or construct real JVM proxies; it only models assignability when the target interface shape is statically known.

Implementation entry points:

- `CallChecker.isArgumentAssignable` accepts an argument when either normal `Type.isAssignableFrom` succeeds **or** `Type.isJavaListenerAssignableFrom` succeeds.
- `Type.isJavaListenerAssignableFrom` / `Type.javaListenerSignature` live in `JavaSemanticSupport.kt`.

### When a parameter is treated as a listener interface

A parameter type is listener-shaped only when all of the following hold after hydration to a `JavaInstanceType`:

1. The type is a `JavaInstanceType` whose `JavaClassType` has **no constructors** and **no superclass** (interface-like surface; classes and concrete subtypes are excluded).
2. The simple name ends with `Listener` or `Callback` (for example `ValueListener`, `OnClickListener`, `CompletionCallback`). Names such as `Action` or `Runnable` are **not** treated as listener interfaces by this rule, even if they are single-method interfaces.
3. After excluding `java.lang.Object` methods (`equals`, `getClass`, `hashCode`, `notify`, `notifyAll`, `toString`, `wait`), the interface exposes **exactly one** instance method.
4. That method has a single non-vararg, non-optional call signature. Multi-method interfaces, overloaded single methods, and vararg/optional parameters do not qualify.

When those checks pass, the checker builds a `JavaListenerSignature` with the method name and a `FunctionType` matching that method’s parameters and return type.

### Accepted Lua argument shapes

If the parameter is listener-shaped, the following sources may assign:

| Lua argument | Assignability rule |
| --- | --- |
| Function / other `CallableType` | Accepted when the listener method’s `FunctionType` is assignable from the Lua callable (arity and parameter/return compatibility). Typical form: `holder:setValueListener(function(value) ... end)`. |
| Table (`TableType`) | Accepted when the table has a method or field named exactly like the single listener method, and that member is assignable to the listener method type. Typical form: `holder:setValueListener({ onValue = function(value) ... end })`. |
| Module (`ModuleType`) | Same name lookup as tables (`methods` then `fields`). |
| Anything else | Not listener-assignable; falls back to ordinary assignability (usually fails for a Java interface parameter). |

Successful listener assignment does not invent a proxy type for the call result: the setter call still returns the reflected method return type (often `nil` / `void`).

### Explicit non-goals and failure modes

- **Not runtime proxy construction.** Unlike `luajava.createProxy(...)` or Android-Lua’s `onClick = function ... end` property sugar (see `docs/android-lua-import-luajava.md`), this rule only widens call argument compatibility for setter-style methods whose parameter is already a known listener/callback interface.
- **Name-gated.** Interfaces that do not end in `Listener` or `Callback` remain non-listener. Passing a Lua function to such a setter must not be treated as a false-positive success; the call degrades to ordinary incompatibility / `unknown` rather than inventing a conversion.
- **Shape-gated.** Multi-method listeners, classes with constructors/superclasses, and methods with vararg or optional parameters are out of scope for this policy.
- **No callback execution.** Analysis never invokes the Lua function or Java listener method.

### Fixture coverage (cross-links)

Behavioral fixtures for this policy live in `src/jvmTest/kotlin/semantic/interop/JavaChainedCallTddTest.kt` (TASK-152 scope):

| Test method | Fixture surface | Expected static outcome |
| --- | --- | --- |
| `listener_setter_accepts_lua_function_callback` | `ListenerHolder.setValueListener(ValueListener)` with a Lua function | Call succeeds; no diagnostics; assigned call result hover is `nil`. |
| `listener_setter_accepts_lua_table_with_matching_callback_method` | Same setter with a table that defines `onValue` | Call succeeds; no diagnostics; hover is `nil`. |
| `non_listener_interface_setter_callback_remains_unknown` | `ListenerHolder.setAction(Action)` with a Lua function (`Action` is a single-method interface that does **not** end in `Listener`/`Callback`) | Must **not** claim success via listener conversion; hover may remain `unknown`. |

Supporting Java types defined on the same test class:

- `ListenerHolder` — `setValueListener(ValueListener)` and `setAction(Action)`.
- `ValueListener` — single method `onValue(value: String)` (listener-shaped).
- `Action` — single method `run()` (not listener-named; negative control).

Serialized verification of these fixtures is review-owned under TASK-043 (`./gradlew.bat jvmTest --tests semantic.interop.JavaChainedCallTddTest`).

### Relation to other interop sugar

- **Bean property sugar** (`bean.title = ...` via getters/setters) is separate from listener assignability; property aliases do not automatically treat write-only `setX` methods as listener converters.
- **Android-Lua layout/table `onClick` shorthand** that discovers `setOnClickListener` at runtime is documented in the Android-Lua docs as only partially approximated statically. This section documents the narrower, accepted TASK-152 rule: when a reflected setter parameter is already a known `*Listener` / `*Callback` interface, Lua functions and matching tables are assignable at that call site.
- **`luajava.createProxy`** remains the explicit multi-interface / arbitrary-interface proxy helper path and is not replaced by listener setter assignability (see **createProxy static model** above).

## Extension Points

Prefer these extension points for future interop work:

- Add richer Java identity to reflection output by constructing `JavaClassType`/`JavaInstanceType` and bridging it into the existing `ModuleType`/`ClassType` surfaces.
- Extend `JvmWorkspaceConfiguration` for additional classpath or Android SDK discovery instead of hard-coding paths in provider code.
- Add helper mappings in `DocumentFactsCollector` and corresponding provider collection in `JvmWorkspaceEngine` together; facts without provider collection are useful metadata but may not affect type resolution.
- Keep inner-class resolution centralized in the provider candidate-name logic.
- Keep wildcard package behavior deterministic by filtering package listings consistently and by sorting exported member maps where user-facing order matters.
- When adding Android-Lua-specific helpers, document whether the helper resolves a class module, an instance class type, a static member, a proxy intersection, or only a fact for later analysis.
- When extending listener/callback assignability, keep the name gate, single-method gate, and non-execution constraint explicit; add fixtures to `JavaChainedCallTddTest` rather than broadening silently to arbitrary SAM interfaces.
- When extending `createProxy`, keep the string-literal gate, comma-split multi-target facts, intersection-of-resolved-interfaces return shape, and `unknown` degrade when nothing resolves; do not invent callback-table completeness checks without dedicated fixtures.

## Validation Notes

This guide reflects the code shape reviewed for TASK-052, the listener setter assignability policy accepted for TASK-152 (documented under TASK-227), and the `createProxy` static model note added under TASK-454 (aligned with `ExpressionTypeEvaluator.resolveCreateProxyCall`, `DocumentFactsCollector` `CREATE_PROXY_CALL` split targets, TASK-394 SignatureHelp labels, and the LuaJava createProxy TDD suite). No Gradle, compile, or test command was run for this documentation task. Final verification is deferred to TASK-043, and Android-Lua behavior must be reconciled with TASK-037 before compatibility claims are broadened.

Host android.jar paths only: `/Users/dingyi/Downloads/android.jar` and `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` — never `G:/`.
