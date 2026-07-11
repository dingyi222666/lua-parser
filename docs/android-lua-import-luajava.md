# Android-Lua import.lua and LuaJava behavior

Date: 2026-07-12  
Task: TASK-505 (docs-only refresh)

TASK-003 study output, refreshed for **post-TASK-184 / pre-TASK-043** documentation accuracy. Source paths are read-only Android-Lua paths under `/Users/dingyi/projects/java_projects/Android-Lua/app/src/main`.

**Honesty bound (post-TASK-184, pre-TASK-043):** this page documents runtime `import.lua` / native LuaJava behavior and the repository’s static overlay relationship. It does **not** claim final green acceptance, suite green, or inventory finality. Product-surface library stubs and overlay work from the chain ending in **TASK-184** are modeled and review-accepted for focused surfaces; serialized Gradle/test verification remains review-owned under **TASK-043** (`blocked`); final acceptance remains **TASK-037** (`blocked`). Inventory recount / freeze follow-ups (for example TASK-125) are **not final until TASK-043**. No Gradle, compile, or test command was run for this documentation task. Never hard-code Windows `G:/` paths.

## Host analysis environment (macOS only)

Verification notes, JVM metadata examples, and reflective Android framework resolution on this host use **macOS** paths only:

| Item | Host value | Status (2026-07-12 TASK-505 re-check) |
| --- | --- | --- |
| JDK for JVM tests / docs examples | Corretto 17: `/Users/dingyi/Library/Java/JavaVirtualMachines/corretto-17.0.19/Contents/Home` | Present; use as `JAVA_HOME` when review later runs verification |
| Preferred Android platform jar | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | **Present** (27,092,450 bytes; Android SDK Platform 35) |
| Optional drop-in jar | `/Users/dingyi/Downloads/android.jar` | **Absent** on this host; allowed only when present |
| Android SDK root | `/Users/dingyi/Library/Android/sdk` (`ANDROID_HOME` / `ANDROID_SDK_ROOT` when set) | Present |

**Dual-path `android.jar` policy:** only Downloads + SDK `android-35` candidates above. Prefer explicit metadata `jvm.androidJar` pointing at the SDK path for reproducible analysis. Product code may discover jars via `ANDROID_HOME` / well-known roots (see `docs/android-platform-setup.md`); missing jars must **skip** reflective Android mounting — never invent classpath entries. **Never hard-code Windows `G:/` (or other non-host) paths.**

Related honesty documents:

- `docs/android-lua-compatibility-matrix.md` — broader post-184 compatibility claims (awaiting TASK-043)
- `docs/android-lua-import-resolution.md` — static resolution order
- `docs/java-interop-model.md` / `docs/jvm-reflection-classloader-design.md` — JVM reflection projection
- `docs/luajava-helper-surface-matrix.md` — helper support matrix (may still carry older chain wording in places; this page + compatibility matrix own post-184 honesty for import/LuaJava study status)
- `docs/android-platform-setup.md` — SDK path, Corretto 17, skip-when-absent policy

## Runtime entry point

Android-Lua exposes LuaJava by opening the native `luajava` library for each `LuaState`, then loading normal Lua modules through `package.path` and `package.cpath`.

- `java/com/luajava/LuaState.java:123-124`: `System.loadLibrary(LUAJAVA_LIB)` loads the native `luajava` library.
- `jni/luajava/luajava.c:1560-1561`: `_openLuajava` calls `luaL_requiref(L, "luajava", luaopen_luajava, 1)`, making the `luajava` table global.
- `jni/luajava/luajava.c:1508-1521`: the exported Lua table includes `bindClass`, `new`, `newInstance`, `loadLib`, `createProxy`, `newArray`, `createArray`, `astable`, `tostring`, `instanceof`, `getContext`, and `override`.
- `java/com/androlua/LuaActivity.java:1348-1375`: activity states create a new `LuaState`, call `openLibs`, set globals `activity`, `service`, `this`, push the `LuaContext`, and assign `package.path`/`package.cpath`.
- `java/com/androlua/LuaThread.java:236-258`: worker thread states repeat the same pattern and set `activity` or `service` depending on the parent context.

`require "import"` resolves through the configured Lua path. The module is `resources/lua/import.lua`; it mutates the caller environment and returns the `env_import` function.

## Repository overlay source of truth

For static analysis in this repository, the active LuaJava module declaration is:

`src/commonMain/resources/io/github/dingyi222666/luaparser/semantic/workspace/std/androlua53-luajava/luajava.lua`

`BuiltinOverlayLoader` reads that resource for the `LuaVersion.ANDROLUA_5_3` `luajava` provider and exposes it through the synthetic overlay path `__lua_std__/androlua5.3/luajava.lua`. Treat this file as the authoritative LuaJava overlay for active loading.

The external Android-Lua source studied for this document does not provide a standalone `resources/lua/luajava.lua`. Runtime LuaJava is registered by the native bridge (`luaopen_luajava`) and then extended by `resources/lua/import.lua`, which adds shared tables such as `luajava.loaded`, `luajava.imported`, layout id state, and package-chain metatable behavior. Those raw runtime sources explain behavior; they are not the active static declaration file.

The embedded Kotlin text in `AndroidLua53LuaJavaBuiltinOverlaySources.luajavaSource` is only a fallback used if the resource above cannot be read. It should remain compatible with the resource, but it is not the source of truth while `std/androlua53-luajava/luajava.lua` is present. This TASK-091 note documents that relationship only; it does not require resource, source, or test edits.

Android-Lua library stub / helper overlay surfaces (`activity`, `service`, `loadlayout`, `loadbitmap`, `loadmenu`, helper modules, layout fixtures) were restored under **TASK-184** (focused review acceptance). That is product-surface modeling status only — **not** a substitute for TASK-043 serialized verification or final green.

Representative snippets:

```text
/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/resources/lua/import.lua:1
local require = require

/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/resources/lua/import.lua:485
env_import(_G)

/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/resources/lua/import.lua:503
return env_import
```

## `require "import"` effects

Loading `import.lua` performs these runtime changes:

- Saves `luajava.loaded` and `luajava.imported` tables for class/module cache and import history (`import.lua:5-8`).
- Reads available dex class loaders and native library paths from `activity` or `service` (`import.lua:15-17`).
- Adds a native-library searcher to `package.searchers` (`import.lua:19-28`). The loader maps a module prefix to `libs[path:match("^%a+")]` and calls `package.loadlib(path, "luaopen_" .. path:gsub("%.", "_"))`.
- Installs a metatable on `_G` through `env_import(_G)` so unresolved globals can lazily bind Java classes (`import.lua:141-188`, `import.lua:485`).
- Injects helper functions from `_M`, including `compile`, `enum`, `each`, `dump`, `printstack`, `thread`, `task`, `timer`, and `getids` (`import.lua:189-204`, `import.lua:213-467`).
- Imports helper Lua modules `loadlayout`, `loadbitmap`, and `loadmenu` into the environment (`import.lua:206-208`).
- Adds a metatable to `luajava` itself so property chains such as `luajava.java.lang.String` can progressively resolve classes or package proxy tables (`import.lua:487-501`).

The `assets/javaapi/fiximport.lua` script is not the runtime importer. It is an editor/helper tool that itself does `require "import"` and scans Lua source for likely class names (`assets/javaapi/fiximport.lua:1-9`, `assets/javaapi/fiximport.lua:28-91`).

## `import` function behavior

`env_import` creates an `import` function in the target environment:

```text
/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/resources/lua/import.lua:192-201
local import = function(package, env)
    env = env or _env
    if type(package) == "string" then
        return local_import(env, packages, package)
    elseif type(package) == "table" then
        local ret = {}
        for k, v in ipairs(package) do
            ret[k] = local_import(env, packages, v)
        end
        return ret
    end
end
```

Important cases:

- `import "java.io.File"` extracts the last segment (`File`) and writes that class/module into the environment (`import.lua:123-132`).
- `import "java.io.*"` adds `java.io.` to the environment-local package prefix list and returns a package proxy table (`import.lua:117-121`).
- `import { "java.io.File", "java.util.*" }` imports each array element in order and returns an array-like Lua table of results (`import.lua:196-201`). It uses `ipairs`, so only sequential numeric entries are processed.
- `import "dexname:fully.qualified.ClassName"` loads a class through `luacontext.loadDex(dexname).loadClass(classname)`, binds the simple name into the environment, records the original import string, and returns the class (`import.lua:106-115`).
- For non-wildcard imports, lookup tries Lua `require(package)`, then `luajava.bindClass`, then all loaded dex class loaders (`import.lua:89-95`, `import.lua:123-125`).

Known limits:

- `import` silently ignores non-string and non-table arguments (`import.lua:192-202`).
- Table imports use `ipairs`, so sparse keys and map-style entries are ignored (`import.lua:197-200`).
- Failed explicit imports raise `cannot find <package>` (`import.lua:134-135`).
- A `require` error is rethrown unless the error text contains `no file` (`import.lua:89-94`).

## Wildcards and default package prefixes

Each imported environment keeps a `packages` list:

```text
/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/resources/lua/import.lua:143-148
local packages = {}
append(packages, '')
append(packages, 'java.lang.')
append(packages, 'java.util.')
append(packages, 'com.androlua.')
```

`import "java.io.*"` appends `java.io.` to this list and records the wildcard string in `luajava.imported` (`import.lua:117-121`). Wildcards are lazy package prefixes; they do not enumerate classes. After a wildcard import, an unresolved global like `File` can be resolved by trying `java.io.File`.

Lookup order for direct global class exposure is:

1. Already-loaded exact `loaded[classname]`.
2. `luajava.bindClass(prefix .. classname)` for every prefix in `packages` (`import.lua:150-157`, `import.lua:171-177`).
3. `dex.loadClass(prefix .. classname)` for every prefix in `packages` and every known dex class loader (`import.lua:159-169`, `import.lua:171-177`).

Because the initial package list includes the empty prefix, `java.lang.`, `java.util.`, and `com.androlua.`, scripts can reference names such as `String`, `ArrayList`, or `LuaThread` after `require "import"` without an explicit import, if the runtime class exists.

Package proxy tables also exist. `import "java.io.*"` returns a table with `__name = "java.io."`; indexing `pkg.File` attempts `luajava.bindClass("java.io." .. "File")` and caches the result (`import.lua:68-83`).

Known limits:

- Wildcards only cover later simple-name lookup. They do not populate completion inventories by themselves.
- Prefix order matters. A simple name resolves to the first prefix that binds successfully.
- `_G` direct lookup only happens for unresolved globals; local variables and existing globals shadow it.
- Each `env_import(env)` call has its own package list, but `luajava.loaded` is shared by the module.

## Inner-class naming compatibility

`import.lua` accepts underscores as inner-class separators before binding:

```text
/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/resources/lua/import.lua:30-34
if classname:find('_') then
    classname = classname:gsub('_', '$')
end
```

This applies to explicit class imports and dex class imports (`import.lua:45-64`). For example, `import "android.view.View_OnClickListener"` is massaged to `android.view.View$OnClickListener`.

The Java bridge also resolves nested classes when indexing a Java `Class` userdata:

- `java/com/luajava/LuaJavaAPI.java:1220-1233`: `checkClass` first tries `Class.forName(clazz.getName() + "$" + className)`.
- `java/com/luajava/LuaJavaAPI.java:1237-1241`: if that fails, it scans `clazz.getClasses()` by simple name.

Known limits:

- The underscore-to-dollar rewrite is broad. Any underscore in an explicit imported class name is converted to `$`, which can break real Java class names that contain underscores.
- The rewrite is not used for plain global simple-name lookup before prefixes are applied; it is used by explicit import paths and dex import paths.
- Java nested class lookup is only reached when indexing an already-bound `Class` userdata, not when parsing dotted Lua source.

## LuaJava class binding and construction

`luajava.bindClass(name)` is a native function registered in `jni/luajava/luajava.c:1508`. The native implementation validates one string argument and calls `LuaJavaAPI.javaBindClass` (`jni/luajava/luajava.c:800-822`).

`LuaJavaAPI.bindClass` uses `Class.forName(className)` and has explicit fallbacks for primitive type names:

```text
/Users/dingyi/projects/java_projects/Android-Lua/app/src/main/java/com/luajava/LuaJavaAPI.java:599-630
Class.forName(className)
case "boolean": clazz = Boolean.TYPE
case "byte": clazz = Byte.TYPE
...
default: throw new LuaException("Class not found: " + className)
```

`luajava.newInstance(className, ...)` is registered at `jni/luajava/luajava.c:1510` and routes to `LuaJavaAPI.javaNewInstance` (`jni/luajava/luajava.c:1030-1048`). The Java side binds the class name, converts primitive constructors directly, and otherwise calls constructor resolution (`LuaJavaAPI.java:650-660`).

`luajava.new(classUserdata, ...)` constructs from a bound `Class` userdata (`LuaJavaAPI.java:671-689`). It also special-cases primitive class userdatas, abstract classes with a Lua override table, and normal constructors.

Constructor resolution:

- With no Lua constructor args, `getObjInstance` first tries the no-arg Java constructor, then a single `Context` constructor using `L.getContext().getContext()` (`LuaJavaAPI.java:977-993`).
- With args, it iterates public constructors, requires the same arity, converts each Lua argument with `compareTypes`, invokes the first compatible constructor, and reports candidate constructors on failure (`LuaJavaAPI.java:995-1042`).

## Tables, arrays, maps, lists, and class calls

There are several related table/array paths:

- `luajava.newArray(classUserdata, ...)` creates a Java array of a bound class. The native wrapper passes the class userdata and dimensions to `LuaJavaAPI.newArray` (`jni/luajava/luajava.c:866-887`; `LuaJavaAPI.java:566-596`).
- `luajava.createArray(className, table)` creates a typed array by binding `className` then converting the Lua table (`jni/luajava/luajava.c:893-924`; `LuaJavaAPI.java:830-836`).
- Calling a Java class userdata with a Lua table chooses `LuaJavaAPI.javaCreate`: primitive/String/array classes become arrays; `List` and `Map` classes become Java collections; interfaces become proxies; abstract classes become enhanced proxies; otherwise the table either initializes an array or falls back to constructor behavior (`LuaJavaAPI.java:717-748`).
- Calling an existing Java object with a table applies table entries to the object: numeric keys set array values, string keys route through Java setters, and `List` objects append values (`LuaJavaAPI.java:771-807`). This supports patterns such as `Object { ... }` seen in `import.lua:399-405`, `import.lua:439-443`, and `import.lua:459-460`.
- `luajava.astable(javaObject)` recursively converts Java arrays, collections, and maps to Lua tables (`LuaJavaAPI.java:526-563`). `import.lua` uses it to turn `luacontext.getClassLoaders()` into a Lua table (`import.lua:15-17`).
- `LuaObject.asArray` and `LuaObject.asMap` convert Lua tables to Java object arrays/maps for Java-side callers (`LuaObject.java:592-630`).

Type conversion for Java calls is centralized in `compareTypes`:

- `nil` maps to Java `null` (`LuaJavaAPI.java:1751-1752`).
- Lua booleans, strings, numbers, functions, tables, and userdata are converted according to the target Java parameter (`LuaJavaAPI.java:1753-1863`).
- Lua functions and tables can become Java interface proxies (`LuaJavaAPI.java:1772-1779`, `LuaJavaAPI.java:1783-1795`).
- Lua tables can become Java arrays, `List`s, or `Map`s (`LuaJavaAPI.java:1783-1793`).
- Numbers are converted to primitive/wrapper numeric types with integer vs floating detection (`LuaJavaAPI.java:1800-1812`).

Known limits:

- Table-to-array conversion uses Lua sequence length/object length, so non-sequence numeric keys are not guaranteed to be preserved (`LuaJavaAPI.java:1582-1654`).
- `newArray` requires a bound class userdata in argument 1; `createArray` takes a class name string and a table.
- Proxy conversion rejects Lua array-like tables when a table is used as an interface proxy (`LuaObject.java:686-695`).

## Proxies and listeners

`luajava.createProxy(interfaceNames, table)` is exported in the native library (`jni/luajava/luajava.c:1512`). The native wrapper requires two args, with arg 1 a string and arg 2 a Lua table (`jni/luajava/luajava.c:829-859`), then calls `LuaJavaAPI.createProxy`.

Java proxy creation:

- `LuaJavaAPI.createProxy` fetches the Lua object at stack index 2 and delegates to `LuaObject.createProxy` (`LuaJavaAPI.java:822-827`, `LuaJavaAPI.java:1541-1553`).
- `LuaObject.createProxy(String)` requires a Lua table, tokenizes comma-separated interface class names, resolves each with `Class.forName`, and returns a `Proxy` using `LuaInvocationHandler` (`LuaObject.java:670-683`).
- `LuaObject.createProxy(Class)` accepts a Lua table or a Lua function. A function is allowed only for a single-method interface; list-like tables are rejected (`LuaObject.java:686-700`).

Java setter syntax also auto-wires Android-style listeners:

- `LuaJavaAPI.java:1324-1325`: assigning a Lua function to a key beginning with `on` can route to listener setup.
- `LuaJavaAPI.java:1395-1412`: `onClick = function ... end` looks for `setOnClickListener`, wraps the function in a one-method table, creates a proxy for the listener interface, and invokes the setter.

## `loadLib`

There are two load-library paths:

- `luajava.loadLib(className, methodName)` calls a Java static method named by `methodName` on `className`; that method must accept `LuaState` and may return an integer result count (`jni/luajava/luajava.c:1056-1095`; `LuaJavaAPI.java:849-872`).
- The `import.lua` `libsloader` added to `package.searchers` maps Lua `require` names to native `.so` paths registered by the Android-Lua context, then calls `package.loadlib` with `luaopen_<module_name>` (`import.lua:19-28`).

`LuaDexLoader` populates the native library map used by `import.lua`:

- `java/com/androlua/LuaDexLoader.java:70-84`: scans the script `libs` directory for `.so` files and dex/jar artifacts.
- `java/com/androlua/LuaDexLoader.java:84-101`: copies `lib<name>.so` into an app-private directory and records `libCache[fn] = libPath`.
- `java/com/androlua/LuaActivity.java:155-159`: exposes `getClassLoaders()` and `getLibrarys()` to Lua.

Known limits:

- `luajava.loadLib` is for Java static loader methods, not arbitrary native `.so` paths.
- `import.lua` native library lookup only uses an alphabetic prefix from the module name (`path:match("^%a+")`), so library aliases must match that prefix convention.

## Java object indexing, methods, fields, and properties

Java userdata behavior is implemented in native metamethods that delegate to `LuaJavaAPI`:

- `LuaJavaAPI.objectIndex` lookup order is method, public field, JavaBean getter, nested class, then `LuaMetaTable.__index` (`LuaJavaAPI.java:95-124`).
- `LuaJavaAPI.objectNewIndex` assignment order is public field, setter/listener/declared field, then `LuaMetaTable.__newIndex` (`LuaJavaAPI.java:302-326`).
- `LuaJavaAPI.callMethod` resolves overloaded Java methods by arity and `compareTypes`, with small caches for no-arg and one-arg string/boolean/integer/double calls (`LuaJavaAPI.java:127-290`).
- `LuaJavaAPI.checkField` reads public static fields from `Class` userdatas and public fields from instances; final fields return a different cache marker (`LuaJavaAPI.java:1131-1171`).
- `LuaJavaAPI.javaGetter` maps property access to `getName()` or `isName()`, and maps Java `Map` access to `map.get(key)` (`LuaJavaAPI.java:1248-1298`).
- `LuaJavaAPI.javaSetter` maps assignments to `Map.put`, Android listener shorthand, `setName(value)`, or declared-field assignment (`LuaJavaAPI.java:1301-1330`, `LuaJavaAPI.java:1422-1539`).

Lua-to-Java stack conversion outside method resolution is exposed by `LuaState.toJavaObject`: booleans, strings, functions, tables, numbers, Java userdata, Lua userdata references, and nil are mapped to Java values (`LuaState.java:1127-1153`).

## Implications for parser/semantic/LSP work

These are analysis **targets**, not a claim that every surface is suite-green under TASK-043:

- Parse `require "import"` as a normal Lua `require` call, but semantic mode may treat it as enabling Android-Lua import globals for that file/environment (path-scoped activation is the TASK-176 surface; modeled/review-accepted, still awaiting TASK-043 confirmation).
- `import "pkg.Class"` introduces the simple class name into the environment.
- `import "pkg.*"` introduces a lazy package prefix, not concrete symbols.
- `import { ... }` should be modeled over sequential string entries only.
- Unqualified global class names can resolve through the current default/imported package list after `require "import"`.
- Inner-class compatibility should support explicit import strings that use `_` as `$`, plus Java class-member nested lookup.
- Lua table constructors are semantically overloaded when passed to Java class calls, array constructors, collection constructors, proxy creation, object calls, or Java method arguments.
- Completion should separate known imports from wildcard/package prefixes. Wildcards imply possible class resolution but do not provide a complete class inventory without Android/JDK/dex indexes.
- Authoritative Android framework class availability for static analysis prefers reflective `android.jar` when configured (macOS dual-path above). Curated static framework resources may fill gaps when the jar is absent; missing jar ⇒ skip reflection, do not invent members.
- Direct `luajava.bindClass` / `newInstance` string-literal helpers and multi-hop local helper aliases are modeled under the TASK-169 / TASK-140 chain (done in metadata); still **not** final green until TASK-043.
- Android-Lua library stub / layout helper surfaces restored by TASK-184 remain subject to TASK-043 suite confirmation. Inventory is not final until that gate.

## Docs-only acceptance checklist (TASK-505)

| Criterion | Where reflected |
| --- | --- |
| Post-TASK-184 / pre-TASK-043 accuracy | Honesty bound + implications section |
| macOS host paths, Corretto 17, android-35 jar | Host analysis environment table |
| Dual-path Downloads + SDK; never `G:/` | Dual-path policy paragraph |
| Inventory not final until TASK-043 | Honesty bound + implications |
| Do not claim final green | Explicit throughout; no suite-green language |
| Docs-only; no Gradle | Stated in honesty bound |

When later review runs verification, use Corretto 17 `JAVA_HOME` and explicit `jvm.androidJar` to the SDK android-35 path. That work belongs to **TASK-043**, not this docs refresh.
