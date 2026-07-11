# JVM reflection and classloader design

Status: design note for the **post-TASK-184 / pre-TASK-043** product tree. TASK-184 library-stub and related Android-Lua surface work is accepted in-tree; **TASK-043 serialized verification remains open**. This document is **not** a final-green claim, not a pass/fail ledger, and does not unlock TASK-043.

This note describes the JVM reflection provider introduced by TASK-030 (and later host-path / discovery work such as TASK-245) and how its output feeds the common Java interop and workspace analysis model. The loading boundary is JVM-only; reflected class surfaces are converted into the common semantic model before workspace queries, completion, hover, definition, references, and LSP features consume them.

## Scope and entry points

The JVM-specific implementation is centered on three types:

- `JvmWorkspaceConfiguration` stores classpath and import configuration and performs host `android.jar` discovery for reflection defaults.
- `JvmClassModuleProvider` reflects Java classes and packages into synthetic workspace providers.
- `JvmWorkspaceEngine` extends `LuaWorkspaceEngine` and connects document facts from Lua source to the reflective provider.

The provider emits synthetic virtual files under:

- `__jvm__/classes/<binary-class-name>.lua` for concrete classes.
- `__jvm__/packages/<package-name>.lua` for wildcard package imports.

These files are not source files on disk. They are `WorkspaceSnapshot.FileSnapshot` entries whose `ModuleExportSurface` contains the reflected Java surface.

## Classpath configuration

`JvmWorkspaceConfiguration` is the authoritative configuration object. It can be supplied directly to `JvmWorkspaceEngine` or serialized through `LuaWorkspaceInput.metadata` with these keys:

- `jvm.classes`: explicit class names to reflect. Values may be separated by commas, semicolons, or newlines when read from metadata.
- `androlua.imports`: Android-Lua import targets to pre-resolve. Values are read line by line.
- `jvm.classpath`: additional classpath entries, one per line. Entries may be directories or jar files.
- `jvm.androidJar`: an explicit Android platform jar path (metadata override; highest precedence when non-blank).
- `jvm.importPrefixes`: import prefixes used to resolve short class names, one per line.

The default import prefixes are:

- `java.lang`
- `java.util`
- `java.io`
- `android.app`
- `android.content`
- `android.view`
- `android.widget`
- `com.androlua`

Configuration is normalized before use: blank strings are removed, lists are trimmed, explicit classes are de-duplicated in insertion order, and default import prefixes are restored when none are provided. `JvmWorkspaceEngine` overlays metadata configuration on top of its constructor configuration, then adds wildcard import packages discovered from source files to the effective import prefixes so later unqualified Android-Lua names can resolve.

## Classloader lifecycle

The reflection provider does not mutate the process classpath. It builds the effective class loader as follows:

1. If `JvmWorkspaceConfiguration.classLoader` is provided, that loader is used directly.
2. Otherwise, `reflectionClasspathEntries()` combines configured classpath entries, the configured Android jar path (when set), and — only when `jvm.androidJar` is unset — a **discovered existing** platform jar (see below). Missing jars are never invented onto the reflective classpath.
3. If the effective classpath is empty, the provider uses its base class loader.
4. If entries exist, the provider creates a `URLClassLoader` with those entries and the base class loader as parent.

Class loading uses `Class.forName(name, false, classLoader)`. Passing `false` keeps class inspection metadata-oriented and avoids class initialization side effects. The implementation creates class loaders on demand for provider construction and import resolution; there is no shared lifecycle cache or close hook today. Consumers should treat the provider as a per-workspace snapshot builder, not as a long-lived mutable runtime classpath manager.

## JDK class inspection

JDK classes are available through the base class loader and, for wildcard package enumeration, through the JDK runtime image. Reflection maps Java classes to the common type model:

- A reflected class provider exports a `ModuleType` named by the class simple name.
- The module field `__class` contains a `ClassType` named by the binary class name.
- Public constructors become `__call`, returning the declaring class type.
- Public static fields become module fields.
- Public static methods become module methods.
- Public instance fields and methods become `ClassType` fields and methods.
- Public inherited members are available through `ClassType.getAllFields()` and `ClassType.getAllMethods()`.
- Multiple constructors or methods with the same name become `OverloadedFunctionType`; a single signature remains a `FunctionType`.
- Java arrays become `ArrayType(componentType)`.
- Java primitives and common boxed/string types map to common primitive types: boolean, number, string, nil for `void`, and `any` for otherwise unsupported primitive cases.
- Other object types become lightweight `ClassType` references by binary name.

The provider currently uses public reflection surfaces (`fields`, `methods`, `constructors`, and `classes`). Private, protected, package-private, generic type arguments, annotations, checked exceptions, Kotlin metadata, and JavaDoc are not modeled.

## Host `android.jar` dual-path policy (never `G:/`)

Docs, tests, and operator notes on this macOS host may reference **only** these host jar locations (dual-path policy shared with `docs/android-platform-setup.md` and related Android-Lua docs):

| Priority | Path | Host status (2026-07-11 / TASK-500 re-check) |
| --- | --- | --- |
| 1 (metadata) | Explicit `jvm.androidJar` (any existing file the operator supplies) | Operator-controlled |
| 2 (SDK platform) | `/Users/dingyi/Library/Android/sdk/platforms/android-35/android.jar` | **Present** (~27,092,450 bytes; Android SDK Platform 35) |
| 3 (optional host copy) | `/Users/dingyi/Downloads/android.jar` | **Absent** on this host; allowed when present as an **explicit** metadata override only |

Rules:

1. **Never hard-code `G:/Android/Sdk/...`** (or any Windows-only path) in new docs, product paths for this host, or tests. Historical Windows paths may appear only as negative fixtures or as product well-known *candidate* roots on Windows hosts — not as this macOS host's expected path.
2. Prefer SDK `platforms/android-35/android.jar` when the file exists.
3. Use `/Users/dingyi/Downloads/android.jar` only when that file exists **and** is supplied via explicit `jvm.androidJar` (or equivalent operator config). Product discovery does **not** auto-select the Downloads jar.
4. If neither host jar is available, skip reflective Android framework mounting; do not fail Gradle/LSP startup solely because `android.jar` is missing. Curated Android-Lua static framework overlays may still provide a subset of symbols.

## `android.jar` loading and discovery

Android framework classes are loaded by adding an existing `android.jar` to the reflective classpath.

Precedence for reflection (`JvmWorkspaceConfiguration.reflectionClasspathEntries()` / `discoverReflectiveAndroidJarPath()`):

1. Configured non-blank `jvm.androidJar` wins and is appended after `jvm.classpath` entries via `effectiveClasspathEntries()`. When it is set, environment/well-known discovery is skipped for that configuration note path.
2. When `jvm.androidJar` is unset, discovery looks for an **existing** platform jar in order:
   - `ANDROID_HOME` → highest `platforms/android-*/android.jar`
   - `ANDROID_SDK_ROOT` → same (only if `ANDROID_HOME` did not yield a jar)
   - Well-known SDK roots for the host OS (on macOS: `~/Library/Android/sdk`, then other documented roots). Highest API level wins among discovered roots.
3. Missing jars are never invented onto the reflective classpath. `DEFAULT_ANDROID_JAR_PATH` / `resolveDefaultAndroidJarPath()` may still return a preferred **candidate** path (API 35 under a well-known root) for messaging and test skip guards; callers must check `File.isFile` before asserting reflection.

On this macOS host, the SDK path above is present, so reflection defaults can resolve Android framework classes without metadata when env/well-known discovery succeeds. Downloads remains optional and explicit-only.

Once a jar is part of the effective classpath, Android classes follow the same reflection path as JDK classes:

- Explicit imports such as `android.widget.TextView` resolve to a class provider.
- Short names such as `TextView` resolve through default or configured import prefixes.
- Wildcard imports such as `android.widget.*` create a package provider and can also make later unqualified names resolvable.
- Inner Android classes such as `android.widget.TextView.BufferType` resolve to binary-name providers such as `__jvm__/classes/android/widget/TextView$BufferType.lua`.

The provider does not emulate Android runtime behavior. It only reads class metadata from the jar. Methods that require a device runtime, resources, hidden framework APIs, desugaring, or bootclasspath ordering are outside the current model.

Static Android-Lua framework resource models under the Android-Lua overlay remain independent of the local SDK installation and can supply a curated baseline when reflection is unavailable. Reflection is the path for authoritative SDK coverage outside that curated set. See `docs/android-platform-setup.md`.

## Wildcard package enumeration

Wildcard imports are recognized when the normalized import target ends in `.*`. The package name before the suffix is converted to a resource path and searched through several sources:

- `ClassLoader.getResources(package/path)` results with `file`, `jar`, or `jrt` protocols.
- The JDK `jrt:/modules` runtime image.
- Each effective classpath entry, as either a directory root or jar file.

Only direct `.class` files in the requested package are included. Nested package contents are not recursively enumerated, and class files whose simple file name contains `$` are skipped during wildcard enumeration. This means wildcard packages expose top-level classes, while inner classes are still reachable by explicit class import or by public nested class inspection on their enclosing class.

Package providers export a `ModuleType` named by the package name. Its fields map each discovered simple class name to that class module type, and it has a string index signature with `UnknownType` so dynamic package-table indexing can remain permissive.

## Inner-class naming

The resolver accepts several source spellings for nested classes and normalizes them to the JVM binary class name when reflection succeeds:

- Binary names with `$`, for example `java.util.Map$Entry`.
- Dotted nested names, for example `java.util.Map.Entry`.
- Android-Lua underscore aliases, for example `java.util.Map_Entry`.

Candidate generation tries the original name, an underscore-to-dollar rewrite, and dot-to-dollar combinations. This supports both Java source-style names and Android-Lua import compatibility. The broad underscore rewrite can conflict with real Java class names that contain underscores, so the first loadable candidate wins.

For reflected enclosing classes, public inner classes are also exposed as module fields using their simple name. For example, a reflected `TextView` module can expose `BufferType` when the nested class is public.

## Connection to common workspace analysis

The common workspace layer discovers Java interop demand before the JVM provider runs:

- `DocumentFacts.sourceImports` records Android-Lua import targets.
- `DocumentFacts.jvmClassLoads` records literal class targets from `import(...)`, `luajava.bindClass(...)`, `luajava.newInstance(...)`, `luajava.createProxy(...)`, and `luajava.loadLib(...)`.
- Wildcard imports remain source imports, but they do not expand into concrete `JvmClassLoadFact` entries in the common facts layer.

`JvmWorkspaceEngine.extraProviders()` collects these facts for every workspace file, resolves imported classes through `JvmClassModuleProvider`, and adds the resulting class and package providers to the workspace snapshot. The common `WorkspaceModuleResolver` can then resolve those providers the same way it resolves Lua module providers.

`JvmWorkspaceEngine.workspaceContext()` also injects imported Java symbols into `SemanticWorkspaceContext`:

- Explicit imports become `WorkspaceImportedSymbol` entries keyed by simple class alias.
- Wildcard imports can resolve through `resolveImportTarget`.
- Missing imported symbols can be resolved lazily through `resolveImportedSymbol`.

Because reflected output is converted to `ModuleExportSurface`, `ModuleType`, `ClassType`, `FunctionType`, `OverloadedFunctionType`, and related common types, the rest of the semantic pipeline does not need JVM reflection APIs. It sees the same model shape used by Lua module analysis, Android-Lua declaration overlays, and workspace navigation.

Post-TASK-184 product surfaces (library stubs, require/import overlays, and related Android-Lua models) feed the same common layer. This design note does not re-inventory those surfaces; see `docs/android-lua-library-models.md`, `docs/android-lua-require-path-matrix.md`, and `docs/java-interop-model.md`.

## TASK-030 output contract

TASK-030's JVM reflection output should be treated as a provider input to the common Java interop model, not as a parallel semantic system. The contract is:

1. Source and metadata identify class or package demand.
2. JVM reflection turns loadable Java classes into synthetic workspace providers.
3. Synthetic providers publish common `ModuleExportSurface` data.
4. Workspace analysis links Lua imports, Java class aliases, package tables, and member accesses through the same query and type APIs used for normal Lua modules.

This lets Android-Lua code such as `import "android.widget.TextView"`, `import "android.widget.*"`, `luajava.bindClass("java.io.File")`, and `luajava.createProxy("android.view.View.OnClickListener", table)` participate in definition, hover, completion, and reference workflows without requiring the common source sets to load JVM classes directly.

## Limitations

Current limitations are intentional and should be preserved until a focused follow-up expands the model:

- Only JVM targets can run reflection. Common source sets should continue to depend only on serialized/common semantic model data.
- Class loaders are built on demand and are not cached or explicitly closed.
- Reflection is limited to public members visible through Java reflection.
- Generic signatures, nullability, annotations, parameter names, checked exceptions, JavaDoc, and Android API-level metadata are not modeled.
- Overload resolution records callable shapes but does not implement full Java conversion ranking.
- Wildcard package enumeration is shallow and skips `$` inner-class files.
- Wildcard enumeration depends on classpath and runtime image visibility; it is not a complete package index for arbitrary class loaders.
- `android.jar` loading is metadata-only and does not simulate Android runtime classes, resources, hidden APIs, or device behavior.
- Underscore inner-class compatibility can misinterpret legitimate class names containing underscores.
- Non-literal dynamic class names remain unresolved unless supplied through workspace metadata.
- Downloads `android.jar` is never auto-discovered; it must be explicit metadata when used.
- Final behavioral acceptance of reflection + Android-Lua integration remains blocked on **TASK-043** (and TASK-037 acceptance audit). Do not treat this note as suite-green evidence.

## Verification

No Gradle, compile, or test commands should be run for **TASK-500**. This task is docs-only: review reads the markdown artifact.

Verification of the reflected JVM provider behavior, classpath discovery, and documentation consistency is deferred to **TASK-043** serialized verification. Do **not** claim final green, do not unlock TASK-043, and do not run `jvmTest` filters from this worker.

Cross-links:

- `docs/android-platform-setup.md` — host SDK paths, skip-when-absent policy, LSP metadata shapes
- `docs/java-interop-model.md` — common interop type model (also pre-043)
- `docs/serialized-verification.md` / `docs/final-verification.md` — inventory and acceptance ownership
