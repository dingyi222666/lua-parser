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
 *   V to [PrimitiveType.NIL]; every other `L...;` descriptor becomes a
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
    // dalvik/dex access flags (cf. java.lang.reflect.Modifier values they mirror).
    private const val ACC_PUBLIC = 0x0001
    private const val ACC_PROTECTED = 0x0004
    private const val ACC_STATIC = 0x0008
    private const val ACC_BRIDGE = 0x0040
    private const val ACC_VARARGS = 0x0080
    private const val ACC_SYNTHETIC = 0x1000
    private const val ACC_CONSTRUCTOR = 0x10000

    /** Mirrors [JvmClassModuleProvider] `MAX_REFLECTED_INNER_CLASS_DEPTH`. */
    private const val MAX_INNER_CLASS_DEPTH = 1

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
     * is present in [dexClasses] hydrate a one-level member surface (the
     * referenced class carries its own members; its own references stay
     * shells), mirroring the provider's bounded expansion. Super/interface
     * edges stay name-only references in both modes.
     */
    fun toJavaClassType(cls: DexClass, dexClasses: Collection<DexClass> = emptyList()): JavaClassType {
        return javaClassTypeFor(
            cls = cls,
            dexByName = dexClasses.associateBy(DexClass::binaryName),
            resolving = emptySet(),
            referenceDepth = 0
        )
    }

    /**
     * Adapt one dex class into the provider-shaped module table:
     * `__class` (instance shell), `__call` (when constructors exist), public
     * static field values, static method overloads, and direct inner classes
     * from [dexClasses] keyed by simple name (depth-1 policy).
     */
    fun toModuleType(cls: DexClass, dexClasses: Collection<DexClass> = emptyList()): ModuleType {
        return toModuleType(cls, dexClasses.associateBy(DexClass::binaryName), innerClassDepth = 0)
    }

    /**
     * Translate a single field/method descriptor into the parser type model.
     * See the class KDoc for the primitive table. Arrays use nested
     * single-rank [JavaArrayType] wrappers so index peeling yields intermediate
     * arrays (mirroring the [JavaArrayType] nested-wrapper guidance).
     */
    fun descriptorToType(descriptor: String): Type {
        return descriptorToType(descriptor, emptyMap(), emptySet(), referenceDepth = 0)
    }

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
    private fun typeReferenceForBinaryName(binaryName: String): JavaClassType {
        return JavaClassType(javaName = javaTypeNameFor(binaryName))
    }

    /**
     * Canonical cycle-guard key for a binary name: normalized to dots so the
     * resolving stack is spelling-independent (dex binary names are slash-form,
     * descriptor bodies keep the same form, but every guard/lookup below
     * normalizes first so a form mismatch can never break the guard).
     */
    private fun binaryNameKey(binaryName: String): String = binaryName.replace('/', '.')

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
        val javaName = javaTypeNameFor(cls.binaryName)
        // Members of cls resolve L-descriptors with cls itself on the resolving
        // stack so self-referential fields/methods degrade to shells, and one
        // level past the adaptation target (referenceDepth > 0) every further
        // reference stays a shell — the same bounded-surface policy as the
        // provider's shallow wildcard modules.
        val memberResolving = resolving + clsKey
        val constructors = JavaOverloadSet(constructorTypesFor(cls, javaName, dexByName, memberResolving, referenceDepth))
        val staticMembers = linkedMapOf<String, JavaStaticMemberType>()
        val instanceMembers = linkedMapOf<String, JavaInstanceMemberType>()

        cls.fields.forEach { field ->
            if (!isVisibleMember(field.accessFlags, isMethod = false)) {
                return@forEach
            }
            val valueType = descriptorToType(field.typeDescriptor, dexByName, memberResolving, referenceDepth)
            if (field.accessFlags and ACC_STATIC != 0) {
                staticMembers[field.name] = JavaStaticMemberType(
                    owner = javaName,
                    memberName = field.name,
                    valueType = valueType,
                    memberKind = JavaMemberKind.FIELD
                )
            } else {
                instanceMembers[field.name] = JavaInstanceMemberType(
                    owner = javaName,
                    memberName = field.name,
                    valueType = valueType,
                    memberKind = JavaMemberKind.FIELD
                )
            }
        }

        groupedVisibleMethods(cls).forEach { (name, overloads) ->
            val signatures = overloads.map { overload ->
                functionTypeFor(overload, dexByName, memberResolving, referenceDepth)
            }
            val signatureMetadata = overloads.map(::signatureMetadataFor)
            val member = if (signatures.size == 1) {
                signatures.single()
            } else {
                JavaOverloadType(
                    javaName = javaName,
                    overloadName = name,
                    callSignatures = signatures,
                    signatureMetadata = signatureMetadata
                )
            }
            val isStatic = overloads.first().accessFlags and ACC_STATIC != 0
            if (isStatic) {
                staticMembers[name] = JavaStaticMemberType(
                    owner = javaName,
                    memberName = name,
                    valueType = member,
                    memberKind = JavaMemberKind.METHOD,
                    signatureMetadata = signatureMetadata
                )
            } else {
                instanceMembers[name] = JavaInstanceMemberType(
                    owner = javaName,
                    memberName = name,
                    valueType = member,
                    memberKind = JavaMemberKind.METHOD,
                    signatureMetadata = signatureMetadata
                )
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
        val prefix = cls.binaryName + "$"
        val innerClasses = linkedMapOf<String, JavaClassType>()
        dexByName.forEach { (binaryName, inner) ->
            if (!binaryName.startsWith(prefix)) {
                return@forEach
            }
            val relativeName = binaryName.removePrefix(prefix)
            // Direct children only: the remaining segment must not nest further.
            if ('$' in relativeName || relativeName.isBlank()) {
                return@forEach
            }
            innerClasses[relativeName] = typeReferenceForBinaryName(binaryName)
        }
        return innerClasses.toSortedMap()
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

    private fun toModuleType(
        cls: DexClass,
        dexByName: Map<String, DexClass>,
        innerClassDepth: Int
    ): ModuleType {
        val classType = javaClassTypeFor(cls, dexByName, resolving = emptySet(), referenceDepth = 0)
        val fields = linkedMapOf<String, Type>("__class" to JavaInstanceType(classType))
        val methods = linkedMapOf<String, Type>()

        if (classType.constructors.isNotEmpty) {
            fields["__call"] = classType
        }

        // Public/protected static field values as raw module fields (File.separator,
        // TextView.VISIBLE shape), static method overloads as module methods —
        // the classModuleTypeFor layout.
        classType.staticMembers.forEach { (name, member) ->
            when (member.memberKind) {
                JavaMemberKind.FIELD -> fields[name] = member.valueType
                JavaMemberKind.METHOD -> methods[name] = member.valueType
            }
        }

        if (innerClassDepth < MAX_INNER_CLASS_DEPTH) {
            dexByName.forEach { (binaryName, _) ->
                if (!binaryName.startsWith(cls.binaryName + "$")) {
                    return@forEach
                }
                val relativeName = binaryName.removePrefix(cls.binaryName + "$")
                if ('$' in relativeName || relativeName.isBlank()) {
                    return@forEach
                }
                fields[relativeName] = toModuleType(dexByName.getValue(binaryName), dexByName, innerClassDepth + 1)
            }
        }

        return ModuleType(
            moduleName = moduleSimpleNameFor(cls),
            fields = fields,
            methods = methods
        )
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
     * [PrimitiveType.STRING], V to [PrimitiveType.NIL]; String and the
     * CharSequence family stay [PrimitiveType.STRING] on the `L` branch so dex
     * strings behave exactly like reflected android.jar strings. Every other
     * `L<binary>;` becomes [JavaInstanceType] over a name-only shell — or, when
     * the binary name is in the same dex set at [referenceDepth] 0, over a
     * one-level member-carrying [JavaClassType]. `[X` nests single-rank
     * [JavaArrayType] wrappers. Malformed descriptors degrade to [UnknownType].
     *
     * [resolving] holds binary names whose members are currently being built
     * (cycle guard so self-referential dex classes terminate); depths above 0
     * stop hydrating further dex-set members.
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
            'V' -> PrimitiveType.NIL
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
        when (binaryNameKey(binaryName)) {
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
        if (binaryNameKey(binaryName) in resolving) {
            return JavaInstanceType(shell)
        }
        val dexClass = dexByName[binaryName]
            ?: dexByName[binaryNameKey(binaryName)]
            ?: dexByName[binaryName.replace('.', '/')]
            ?: return JavaInstanceType(shell)
        // Same-dex-set references carry their member surface (one-hop hydration
        // is the product contract); the cycle guard above, not a depth budget,
        // bounds recursion — a cycle degrades to a shell at the repeated key.
        return JavaInstanceType(
            javaClassTypeFor(
                cls = dexClass,
                dexByName = dexByName,
                resolving = resolving + binaryNameKey(binaryName),
                referenceDepth = 0
            )
        )
    }
}
