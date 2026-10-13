package io.github.dingyi222666.luaparser.interop.jvm

import io.github.dingyi222666.luaparser.interop.dex.DexClass
import io.github.dingyi222666.luaparser.interop.dex.DexMethod
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaConstructorType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberKind
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadSet
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaSignatureMetadata
import io.github.dingyi222666.luaparser.semantic.types.model.JavaStaticMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaTypeName
import io.github.dingyi222666.luaparser.semantic.types.model.JavaVisibility
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType

/**
 * Adapter from raw dex class metadata ([DexClass], model in
 * `commonMain/.../interop/dex/`) into the same JVM-shape model that
 * [JvmClassModuleProvider] produces for reflected android.jar classes
 * ([JavaClassType] / [ModuleType]), so dex classes flow through the identical
 * module-provider machinery (module tables with `__class`/`__call`, static vs
 * instance member surfaces, constructor overload sets, nested-type fields).
 *
 * Mirrors of `JvmClassModuleProvider` behaviors (all of its helpers are
 * `private`, so the primitive table and name construction are replicated here
 * rather than reused — keep the two in sync):
 * - [JvmClassModuleProvider.javaClassToType] primitive table: numeric
 *   descriptors (B/S/I/J/F/D) and boxed numeric wrappers map to
 *   [PrimitiveType.NUMBER], Z/[Boolean] to [PrimitiveType.BOOLEAN],
 *   C plus String/CharSequence-family object types to [PrimitiveType.STRING],
 *   V to [PrimitiveType.VOID]; every other `L...;` descriptor becomes a
 *   [JavaInstanceType] over a name-only [JavaClassType] shell.
 * - [JvmClassModuleProvider.shallowJavaClassTypeFor] super/interface policy:
 *   superclasses and interfaces are always shallow, name-only type references.
 * - [JvmClassModuleProvider.classModuleTypeFor] module shape: `__class` field,
 *   `__call` field when constructors exist, public static field values as
 *   module fields, static method overloads as module methods, nested types as
 *   module fields keyed by simple name.
 * - [JvmClassModuleProvider] inner-class depth policy
 *   (`MAX_REFLECTED_INNER_CLASS_DEPTH = 1`): only direct `$` children of the
 *   adapted class are surfaced; deeper nesting never appears.
 * - [JvmClassModuleProvider.classModuleSimpleName] std-library collision
 *   guard: nested classes whose simple name collides with a Lua std module
 *   name (`android.R$string`) claim the qualified `Outer.name` module name.
 *
 * Descriptor-driven dex classes carry no generic signatures, so
 * [JavaClassType.typeParameters] is always empty; dex L-references resolve
 * member-carrying surfaces only for classes present in the same dex set (see
 * [toJavaClassType]'s `dexClasses` parameter) — everything else stays a shell.
 */
object DexClassModelAdapter {
    /** Synchronized access-ordered LRU map bounded at [limit] entries (shared with [JvmClassModuleProvider]). */
    internal fun <K, V> boundedCache(limit: Int): MutableMap<K, V> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > limit
        }
    )

    /**
     * Per-key atomic compute over a [boundedCache] WITHOUT holding the map monitor
     * during the (multi-second) computation: `Collections.synchronizedMap.computeIfAbsent`
     * holds the map lock for the whole build, so a completion thread assembling a
     * framework-class surface blocked the diagnostics worker (and every other cache
     * toucher) for its entire duration — measured as monitor contention on the device.
     * Striped locks serialize same-key builders (single computation preserved) while
     * different keys build concurrently.
     */
    private val cacheStripes = Array(16) { Any() }

    private fun <K, V> boundedCacheComputeOrPut(cache: MutableMap<K, V>, key: K, build: () -> V): V {
        val existing = cache[key]
        if (existing != null) return existing
        val stripe = cacheStripes[kernelHash(key)]
        synchronized(stripe) {
            cache[key]?.let { return it }
            val built = build()
            cache[key] = built
            return built
        }
    }

    private fun kernelHash(key: Any?): Int {
        var h = key?.hashCode() ?: 0
        h = h xor (h ushr 16)
        return h and (cacheStripes.size - 1)
    }

    private class DexSetKey(val classes: Collection<DexClass>) {
        override fun equals(other: Any?): Boolean = other is DexSetKey && classes === other.classes
        override fun hashCode(): Int = System.identityHashCode(classes)
    }

    private class ClassIdentity(val cls: DexClass) {
        override fun equals(other: Any?): Boolean = other is ClassIdentity && cls === other.cls
        override fun hashCode(): Int = System.identityHashCode(cls)
    }

    private class DexIndex(classes: Collection<DexClass>) : Map<String, DexClass> by classes.associateBy(DexClass::binaryName) {
        // Index the complete nesting relation once per metadata revision. Each class lookup
        // then visits its actual direct children, not the entire framework catalog.
        val directChildren = classes.filter { '$' in it.binaryName }
            .groupBy { it.binaryName.substringBeforeLast('$') }
        val referenceTypes = java.util.concurrent.ConcurrentHashMap<String, JavaClassType>()
        // The working set per diagnostics/completion pass is the transitive framework
        // closure (Activity/Context/View/Widget chains + fixture classes, a few hundred
        // entries). The former 128-entry bounds let a single completion sweep evict the
        // checker's hot classes, forcing every live-diagnostics check to re-assemble
        // dozens of DEX surfaces (measured 2.4s-7.1s per check on the E2E emulator).
        val classTypes = boundedCache<ClassIdentity, JavaClassType>(512)
        val moduleTypes = boundedCache<Pair<ClassIdentity, Boolean>, ModuleType>(512)

        init {
            // Index construction over a framework-sized dex (~6k classes) measured 2-4s
            // per device stack sample; yield to the interactive checkpoint mid-build.
            classes.forEachIndexed { index, _ -> if (index % 256 == 255) surfaceAssemblyCheckpoint() }
        }
    }

    private val dexIndexes = boundedCache<DexSetKey, DexIndex>(8)

    private fun dexIndex(classes: Collection<DexClass>): DexIndex =
        boundedCacheComputeOrPut(dexIndexes, DexSetKey(classes)) { DexIndex(classes) }
    // dalvik/dex access flags (cf. java.lang.reflect.Modifier values they mirror).
    private const val ACC_PUBLIC = 0x0001
    private const val ACC_PROTECTED = 0x0004
    private const val ACC_STATIC = 0x0008
    private const val ACC_BRIDGE = 0x0040
    private const val ACC_VARARGS = 0x0080
    private const val ACC_SYNTHETIC = 0x1000
    private const val ACC_CONSTRUCTOR = 0x10000

    /**
     * Lua std module names a nested-class simple name must never claim bare
     * (mirror of [JvmClassModuleProvider] `LUA_STD_MODULE_NAMES`).
     */
    private val LUA_STD_MODULE_NAMES = setOf(
        "bit32", "coroutine", "debug", "io", "math", "os", "package", "string", "table", "utf8"
    )

    /**
     * Adapt one dex class into the reflected-shape [JavaClassType].
     *
     * With an empty [dexClasses] every `L...;` member reference is a
     * name-only shell. With a dex set, `L...;` references whose binary name
     * is present in [dexClasses] hydrate member-carrying surfaces
     * for direct references; deeper and cyclic references remain shells.
     * Super/interface edges stay name-only references in both modes.
     */
    fun toJavaClassType(cls: DexClass, dexClasses: Collection<DexClass> = emptyList()): JavaClassType =
        fullClassType(cls, dexIndex(dexClasses))

    private fun fullClassType(cls: DexClass, index: DexIndex): JavaClassType =
        boundedCacheComputeOrPut(index.classTypes, ClassIdentity(cls)) {
            javaClassTypeFor(cls, index, resolving = emptySet(), referenceDepth = 0)
        }

    fun toModuleType(cls: DexClass, dexClasses: Collection<DexClass> = emptyList()): ModuleType =
        toModuleType(cls, dexIndex(dexClasses), includeInnerModules = true)

    /**
     * Translate a single field/method descriptor into the parser type model.
     * See the class KDoc for the primitive table. Arrays use nested
     * single-rank [JavaArrayType] wrappers so index peeling yields intermediate
     * arrays (mirroring the [JavaArrayType] nested-wrapper guidance).
     */
    fun descriptorToType(descriptor: String): Type {
        return descriptorToType(descriptor, emptyMap(), emptySet(), referenceDepth = 0)
    }

    /** Name catalog entry; member queries resolve the complete provider on demand. */
    internal fun shellModuleType(cls: DexClass): ModuleType = ModuleType(
        moduleName = moduleSimpleNameFor(cls),
        fields = mapOf("__class" to JavaInstanceType(typeReferenceForBinaryName(cls.binaryName)))
    )

    /**
     * Build the [JavaTypeName] for a raw dex binary name, splitting `$`-joined
     * nested segments into [JavaTypeName.simpleNames] exactly like
     * [JvmClassModuleProvider] `javaTypeNameFor` does for reflected classes.
     *
     * Dex binary names are slash-form (`com/foo/Bar`), while the JVM-shape
     * model (and every downstream consumer, e.g. dotted `java.util.Map`
     * container detection) uses reflection-style dotted names, so `/` is
     * normalized to `.` before splitting. [JavaTypeName.binaryName] therefore
     * comes out dotted.
     */
    fun javaTypeNameFor(binaryName: String): JavaTypeName {
        val dotted = binaryName.trim().replace('/', '.')
        val lastDot = dotted.lastIndexOf('.')
        val packageName = if (lastDot > 0) dotted.substring(0, lastDot) else ""
        val classPart = if (lastDot > 0) dotted.substring(lastDot + 1) else dotted
        val simpleNames = classPart.split('$').filter(String::isNotBlank).ifEmpty { listOf(dotted) }
        return JavaTypeName(packageName = packageName, simpleNames = simpleNames)
    }

    /**
     * Name-only [JavaClassType] shell for a binary name (mirror of the
     * provider's `typeReferenceForJavaClass`): no members, no hierarchy.
     */
    // Name-only shells are immutable and referenced for every L-descriptor
    // parameter/return of every method of every assembled class: caching them by
    // binaryName removes the per-member JavaTypeName/JavaClassType allocation
    // churn (measured as the dominant per-method cost on the emulator —
    // buildJavaBinaryName/buildJavaCanonicalName samples inside checks).
    private val binaryNameShells = java.util.concurrent.ConcurrentHashMap<String, JavaClassType>()

    private fun typeReferenceForBinaryName(binaryName: String): JavaClassType =
        binaryNameShells.computeIfAbsent(binaryName) { JavaClassType(javaName = javaTypeNameFor(it)) }

    /**
     * Canonical cycle-guard key for a binary name: normalized to dots so the
     * resolving stack is spelling-independent (dex binary names are slash-form,
     * descriptor bodies keep the same form, but every guard/lookup below
     * normalizes first so a form mismatch can never break the guard).
     */
    private fun binaryNameKey(binaryName: String): String = binaryName.replace('/', '.')

    // Interactive cancellation hook for surface assembly: one large framework
    // class (hundreds of methods, each a descriptor-to-type translation) ran
    // 2-8s per device stack sample with no cancellation point, letting a
    // superseded live-diagnostics check outrun every client budget. The LSP
    // service wires this to its per-thread analysis checkpoint.
    @Volatile
    var analysisCheckpoint: () -> Unit = {}
    private var surfaceAssemblyTicks = 0L

    private fun surfaceAssemblyCheckpoint() {
        // NO runCatching: the checkpoint legitimately throws CancellationException
        // to abort a superseded check; swallowing it here would eat the preemption
        // and the check would keep assembling surfaces past every client budget.
        if (++surfaceAssemblyTicks % 16 == 0L) {
            analysisCheckpoint()
        }
    }

    private fun javaClassTypeFor(
        cls: DexClass,
        dexByName: Map<String, DexClass>,
        resolving: Set<String>,
        referenceDepth: Int
    ): JavaClassType {
        val clsKey = binaryNameKey(cls.binaryName)
        if (clsKey in resolving) {
            return typeReferenceForBinaryName(cls.binaryName)
        }
        surfaceAssemblyCheckpoint()
        val javaName = javaTypeNameFor(cls.binaryName)
        // Members of cls resolve L-descriptors with cls itself on the resolving
        // stack so self-referential fields/methods degrade to shells. In-set
        // references beyond cls keep hydrating (transitively); termination is
        // guaranteed because hydration requires the target key to be absent
        // from [resolving] and every javaClassTypeFor entry grows the set its
        // members resolve against.
        val memberResolving = resolving + clsKey
        // Bounded surface: the requested class gets full members; member TYPES
        // resolve one level deeper only (dex hydration of every transitively
        // referenced class cost minutes on-device). Depth > 1 in-set
        // references become name-only shells.
        val memberDepth = referenceDepth + 1
        val constructors = JavaOverloadSet(constructorTypesFor(cls, javaName, dexByName, memberResolving, memberDepth))
        val staticMembers = linkedMapOf<String, JavaStaticMemberType>()
        val instanceMembers = linkedMapOf<String, JavaInstanceMemberType>()

        cls.fields.forEach { field ->
            if (!isVisibleMember(field.accessFlags, isMethod = false)) {
                return@forEach
            }
            val valueType = descriptorToType(field.typeDescriptor, dexByName, memberResolving, memberDepth)
            if (field.accessFlags and ACC_STATIC != 0) {
                staticMembers[field.name] = JavaStaticMemberType(javaName, field.name, valueType)
            } else {
                instanceMembers[field.name] = JavaInstanceMemberType(javaName, field.name, valueType)
            }
        }

        groupedVisibleMethods(cls).forEach { (name, overloads) ->
            surfaceAssemblyCheckpoint()
            val signatures = overloads.map { overload ->
                functionTypeFor(overload, dexByName, memberResolving, memberDepth)
            }
            val signatureMetadata = overloads.map(::signatureMetadataFor)
            val member: Type = if (signatures.size == 1) {
                signatures.single()
            } else {
                JavaOverloadType(javaName, name, signatures, signatureMetadata)
            }
            if (overloads.first().accessFlags and ACC_STATIC != 0) {
                staticMembers[name] = JavaStaticMemberType(javaName, name, member, JavaMemberKind.METHOD,
                    signatureMetadata = signatureMetadata)
            } else {
                instanceMembers[name] = JavaInstanceMemberType(javaName, name, member, JavaMemberKind.METHOD,
                    signatureMetadata = signatureMetadata)
            }
        }

        return JavaClassType(
            javaName = javaName,
            constructors = constructors,
            staticMembers = staticMembers.toSortedMap(),
            instanceMembers = instanceMembers.toSortedMap(),
            innerClasses = innerClassTypeReferencesFor(cls, dexByName),
            // Super/interface edges stay shallow name-only references in every
            // mode (android.jar shallow policy) — never recursive member expand.
            superClass = cls.superbinaryName?.let(::typeReferenceForBinaryName),
            interfaces = cls.interfaceBinaryNames.map(::typeReferenceForBinaryName),
            typeParameters = emptyList()
        )
    }

    /**
     * Direct `$` children of [cls] present in the dex set, keyed by simple
     * name with name-only shell values — the same shape the provider puts on
     * [JavaClassType.innerClasses] (`typeReferenceForJavaClass`). Depth-1
     * policy: grandchildren (`Outer$Inner$Deep`) never appear.
     */
    private fun innerClassTypeReferencesFor(cls: DexClass, dexByName: Map<String, DexClass>): Map<String, JavaClassType> {
        return (dexByName as DexIndex).directChildren[cls.binaryName].orEmpty()
            .associate { child -> child.binaryName.substringAfterLast('$') to typeReferenceForBinaryName(child.binaryName) }
            .toSortedMap()
    }

    private fun constructorTypesFor(
        cls: DexClass,
        owner: JavaTypeName,
        dexByName: Map<String, DexClass>,
        resolving: Set<String>,
        referenceDepth: Int
    ): List<JavaConstructorType> {
        val ownerReference = typeReferenceForBinaryName(cls.binaryName)
        return cls.methods.mapNotNull { method ->
            if (method.name == "<clinit>") {
                // Class initializer: carries ACC_CONSTRUCTOR in real dex files and can pass
                // the visibility gate, but reflection never exposes it and scripts can never
                // call it — it is neither a constructor overload nor a callable member
                // (groupedVisibleMethods drops it from the method surfaces too).
                return@mapNotNull null
            }
            val isConstructor = method.accessFlags and ACC_CONSTRUCTOR != 0 || method.name == "<init>"
            if (!isConstructor || !isVisibleMember(method.accessFlags, isMethod = true)) {
                return@mapNotNull null
            }
            JavaConstructorType(
                owner = owner,
                signature = FunctionType(
                    parameters = parameterTypesFor(method, dexByName, resolving, referenceDepth),
                    // Mirror constructorSignature: instance shell of the declaring class.
                    returnType = JavaInstanceType(ownerReference)
                ),
                signatureMetadata = signatureMetadataFor(method),
                visibility = visibilityFor(method.accessFlags)
            )
        }
    }

    private fun toModuleType(cls: DexClass, index: DexIndex, includeInnerModules: Boolean): ModuleType {
        val key = ClassIdentity(cls) to includeInnerModules
        index.moduleTypes[key]?.let { return it }
        val classType = fullClassType(cls, index)
        val fields = linkedMapOf<String, Type>("__class" to JavaInstanceType(classType))
        val methods = linkedMapOf<String, Type>()
        if (classType.constructors.isNotEmpty) fields["__call"] = classType
        classType.staticMembers.forEach { (name, member) ->
            when (member.memberKind) {
                JavaMemberKind.FIELD -> fields[name] = member.valueType
                JavaMemberKind.METHOD -> methods[name] = member.valueType
            }
        }
        if (includeInnerModules) {
            index.directChildren[cls.binaryName].orEmpty().forEach { child ->
                fields[child.binaryName.substringAfterLast('$')] =
                    toModuleType(child, index, includeInnerModules = false)
            }
        }
        val module = ModuleType(moduleName = moduleSimpleNameFor(cls), fields = fields, methods = methods)
        // Build child wrappers before publishing. The revision's bounded cache retains
        // one winner under concurrent queries without a recursive map computation.
        return index.moduleTypes.putIfAbsent(key, module) ?: module
    }

    /**
     * Module name mirror of the provider's `classModuleSimpleName`: the bare
     * simple name, qualified as `Outer.name` only when it would otherwise
     * shadow a Lua std module (android.R$string -> "R.string").
     *
     * The bare simple name is derived from the binary name's class part rather
     * than [DexClass.simpleName]: the dex model defines simpleName as the last
     * `/` segment (so nested classes carry `Outer$Inner`), while the module
     * table keys nested members by the bare `Inner` form, matching reflection.
     */
    private fun moduleSimpleNameFor(cls: DexClass): String {
        val classPart = cls.binaryName.replace('/', '.').substringAfterLast('.')
        val simple = classPart.substringAfterLast('$').ifBlank { cls.simpleName }
        if (simple !in LUA_STD_MODULE_NAMES) {
            return simple
        }
        val enclosing = classPart.substringBeforeLast('$').substringAfterLast('$')
        return if (enclosing.isNotBlank() && '$' in classPart) "$enclosing.$simple" else simple
    }

    /**
     * Visibility + artifact filter shared by fields and methods: public or
     * protected only; synthetic (and, for methods, bridge) members are dropped
     * so call surfaces reflect real API shapes rather than compiler artifacts.
     */
    private fun isVisibleMember(accessFlags: Int, isMethod: Boolean): Boolean {
        if (accessFlags and (ACC_PUBLIC or ACC_PROTECTED) == 0) {
            return false
        }
        if (accessFlags and ACC_SYNTHETIC != 0) {
            return false
        }
        if (isMethod && accessFlags and ACC_BRIDGE != 0) {
            return false
        }
        return true
    }

    /**
     * Callable methods grouped by name in encounter order: constructor-flagged
     * methods and `<init>` go to the constructor overload set (handled in
     * [constructorTypesFor]); `<clinit>` is a class initializer, not a
     * script-callable member, and is dropped entirely.
     */
    private fun groupedVisibleMethods(cls: DexClass): Map<String, List<DexMethod>> {
        val grouped = linkedMapOf<String, MutableList<DexMethod>>()
        cls.methods.forEach { method ->
            if (method.name == "<clinit>") {
                return@forEach
            }
            val isConstructor = method.accessFlags and ACC_CONSTRUCTOR != 0 || method.name == "<init>"
            if (isConstructor || !isVisibleMember(method.accessFlags, isMethod = true)) {
                return@forEach
            }
            grouped.getOrPut(method.name) { mutableListOf() }.add(method)
        }
        return grouped
    }

    private fun functionTypeFor(
        method: DexMethod,
        dexByName: Map<String, DexClass>,
        resolving: Set<String>,
        referenceDepth: Int
    ): FunctionType {
        return FunctionType(
            parameters = parameterTypesFor(method, dexByName, resolving, referenceDepth),
            // void return maps to nil, mirroring javaClassToType(Void.TYPE).
            returnType = descriptorToType(method.returnDescriptor, dexByName, resolving, referenceDepth)
        )
    }

    private fun parameterTypesFor(
        method: DexMethod,
        dexByName: Map<String, DexClass>,
        resolving: Set<String>,
        referenceDepth: Int
    ): List<FunctionParameter> {
        val isVarargs = method.accessFlags and ACC_VARARGS != 0
        return method.parameterDescriptors.mapIndexed { index, descriptor ->
            FunctionParameter(
                name = "arg${index + 1}",
                type = descriptorToType(descriptor, dexByName, resolving, referenceDepth),
                vararg = isVarargs && index == method.parameterDescriptors.lastIndex
            )
        }
    }

    private fun signatureMetadataFor(method: DexMethod): JavaSignatureMetadata {
        return JavaSignatureMetadata(isVarArgs = method.accessFlags and ACC_VARARGS != 0)
    }

    private fun visibilityFor(accessFlags: Int): JavaVisibility {
        return if (accessFlags and ACC_PUBLIC != 0) JavaVisibility.PUBLIC else JavaVisibility.PROTECTED
    }

    /**
     * Descriptor translation core.
     *
     * Primitive descriptors mirror [JvmClassModuleProvider] `javaClassToType`:
     * B/S/I/J/F/D (and boxed numeric wrappers on the `L` branch) map to
     * [PrimitiveType.NUMBER], Z to [PrimitiveType.BOOLEAN], C to
     * [PrimitiveType.STRING], V to [PrimitiveType.VOID]; String and the
     * CharSequence family stay [PrimitiveType.STRING] on the `L` branch so dex
     * strings behave exactly like reflected android.jar strings. Every other
     * `L<binary>;` becomes [JavaInstanceType] over a name-only shell — or, when
     * the binary name is in the same dex set, over a member-carrying
     * [JavaClassType] hydrated transitively across the in-set reference graph.
     * `[X` nests single-rank [JavaArrayType] wrappers. Malformed descriptors
     * degrade to [UnknownType].
     *
     * [resolving] holds binary names whose members are currently being built
     * (normalized via [binaryNameKey]): a descriptor whose key is already on
     * the stack degrades to a shell, so self-referential and
     * mutually-referential dex classes terminate. Hydration is bounded:
     * the requested class and its direct references carry members; deeper references are
     * member-less shells (transitive hydration cost minutes on-device).
     */
    private fun descriptorToType(
        descriptor: String,
        dexByName: Map<String, DexClass>,
        resolving: Set<String>,
        referenceDepth: Int
    ): Type {
        if (descriptor.isEmpty()) {
            return UnknownType
        }
        return when (descriptor[0]) {
            'B', 'S', 'I', 'J', 'F', 'D' -> PrimitiveType.NUMBER
            'Z' -> PrimitiveType.BOOLEAN
            'C' -> PrimitiveType.STRING
            'V' -> PrimitiveType.VOID
            'L' -> {
                if (!descriptor.endsWith(";")) {
                    return UnknownType
                }
                val binaryName = descriptor.drop(1).removeSuffix(";")
                if (binaryName.isBlank()) {
                    return UnknownType
                }
                lDescriptorToType(binaryName, dexByName, resolving, referenceDepth)
            }
            '[' -> JavaArrayType(descriptorToType(descriptor.substring(1), dexByName, resolving, referenceDepth))
            else -> UnknownType
        }
    }

    /**
     * Object-type branch of the primitive table: Boolean/[String]/
     * CharSequence-family/Character map to primitives, boxed numerics map to
     * [PrimitiveType.NUMBER] (the reflection table reaches the same result via
     * `Number::class.java.isAssignableFrom`), everything else resolves as a
     * [JavaInstanceType] shell — member-carrying only when the referenced
     * class is in the same dex set.
     *
     * [binaryName] is the raw descriptor body (slash-form for dex); dex-set
     * lookups accept both slash and dotted spellings — dex binary names and
     * descriptor bodies come from the same dex string pool so they always
     * share one form, and the fallback makes a form mismatch impossible —
     * while type-model names normalize to dots via [javaTypeNameFor].
     */
    private fun lDescriptorToType(
        binaryName: String,
        dexByName: Map<String, DexClass>,
        resolving: Set<String>,
        referenceDepth: Int
    ): Type {
        val key = binaryNameKey(binaryName)
        when (key) {
            "java.lang.Boolean" -> return PrimitiveType.BOOLEAN
            "java.lang.Character",
            "java.lang.String",
            "java.lang.CharSequence",
            "java.lang.StringBuilder",
            "java.lang.StringBuffer" -> return PrimitiveType.STRING
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Number",
            "java.math.BigInteger",
            "java.math.BigDecimal" -> return PrimitiveType.NUMBER
        }
        val shell = typeReferenceForBinaryName(binaryName)
        if (key in resolving) {
            return JavaInstanceType(shell)
        }
        val dexClass = dexByName[binaryName]
            ?: dexByName[key]
            ?: dexByName[binaryName.replace('.', '/')]
            ?: return JavaInstanceType(shell)
        // Direct same-dex-set references carry their member surface. Deeper references get
        // name-only shells: transitive hydration walked the entire 929-class
        // reference graph on-device (minutes per cold build) and the memo
        // makes repeated requests cheap anyway. Cycle guard still applies.
        if (referenceDepth > 1) {
            return JavaInstanceType(shell)
        }
        val referenceType = if (dexByName is DexIndex) {
            dexByName.referenceTypes.computeIfAbsent(key) {
                javaClassTypeFor(cls = dexClass, dexByName = dexByName,
                    resolving = emptySet(), referenceDepth = 1)
            }
        } else {
            javaClassTypeFor(
                cls = dexClass,
                dexByName = dexByName,
                resolving = resolving,
                referenceDepth = 1
            )
        }
        return JavaInstanceType(referenceType)
    }
}
