package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-215 — Reflection generic wildcard / raw surface conservative corpus.
 *
 * Encodes the contract that reflected wildcard and raw generic surfaces stay
 * conservative: unbounded `?` collapses to [UnknownType], lower-bound
 * `? super T` does not invent a precise type from the lower bound, raw
 * receivers keep unresolved type parameters, and List/Map wildcard fixtures
 * do not claim concrete element/key/value types that reflection cannot prove.
 *
 * Product code is intentionally out of scope (test-only). Verification is
 * review-owned and serial; this worker does not run Gradle.
 */
class JvmReflectionWildcardTddTest {
    private val provider = JvmClassModuleProvider()

    // -------------------------------------------------------------------------
    // List wildcard fixtures
    // -------------------------------------------------------------------------

    @Test
    fun collections_reverse_list_unbounded_wildcard_argument_is_list_of_unknown() {
        val reverse = callable(module("java.util.Collections").methods.required("reverse"))
        val listArg = reverse.callSignatures
            .flatMap { it.parameters }
            .map { it.type }
            .filterIsInstance<JavaInstanceType>()
            .firstOrNull { it.javaName.canonicalName == "java.util.List" }
            ?: fail("Expected Collections.reverse to expose a java.util.List parameter")

        assertEquals(1, listArg.typeArguments.size, "List<?> should retain a single type argument slot")
        assertEquals(
            UnknownType,
            listArg.typeArguments.single(),
            "Unbounded List<?> must map conservatively to UnknownType, not Object/Any or an invented element type"
        )
    }

    @Test
    fun collections_frequency_collection_unbounded_wildcard_argument_is_collection_of_unknown() {
        val frequency = callable(module("java.util.Collections").methods.required("frequency"))
        val collectionArg = frequency.callSignatures
            .flatMap { it.parameters }
            .map { it.type }
            .filterIsInstance<JavaInstanceType>()
            .firstOrNull { it.javaName.canonicalName == "java.util.Collection" }
            ?: fail("Expected Collections.frequency to expose a java.util.Collection parameter")

        assertEquals(listOf(UnknownType), collectionArg.typeArguments)
    }

    @Test
    fun list_add_all_wildcard_extends_e_preserves_type_parameter_not_invented_concrete() {
        val addAll = callable(classType("java.util.List").allInstanceMembers().required("addAll").valueType)
        // List.addAll(Collection<? extends E>) — upper-bound is the receiver type param E.
        val collectionArg = addAll.callSignatures
            .flatMap { it.parameters }
            .map { it.type }
            .filterIsInstance<JavaInstanceType>()
            .firstOrNull { it.javaName.canonicalName == "java.util.Collection" }
            ?: fail("Expected List.addAll(Collection<? extends E>) parameter")

        val argument = collectionArg.typeArguments.singleOrNull()
            ?: fail("Collection<? extends E> must keep a single type argument")

        val typeParam = assertIs<TypeParameterType>(
            argument,
            "Collection<? extends E> upper-bound E should surface as TypeParameterType, not a concrete invented type"
        )
        assertEquals("E", typeParam.name)
        assertNotEquals(PrimitiveType.ANY, argument)
        assertNotEquals(PrimitiveType.STRING, argument)
        assertNotEquals(UnknownType, argument)
    }

    @Test
    fun collections_unmodifiable_list_parameter_is_list_with_wildcard_extends_t() {
        val method = callable(module("java.util.Collections").methods.required("unmodifiableList"))
        val listArg = method.callSignatures
            .flatMap { it.parameters }
            .map { it.type }
            .filterIsInstance<JavaInstanceType>()
            .firstOrNull { it.javaName.canonicalName == "java.util.List" }
            ?: fail("Expected Collections.unmodifiableList(List<? extends T>) parameter")

        val argument = listArg.typeArguments.singleOrNull()
            ?: fail("List<? extends T> must keep a single type argument")

        val typeParam = assertIs<TypeParameterType>(argument)
        assertEquals("T", typeParam.name)
    }

    // -------------------------------------------------------------------------
    // Map wildcard fixtures
    // -------------------------------------------------------------------------

    @Test
    fun map_put_all_wildcard_extends_key_value_preserves_type_parameters() {
        val putAll = callable(classType("java.util.Map").allInstanceMembers().required("putAll").valueType)
        val mapArg = putAll.callSignatures
            .flatMap { it.parameters }
            .map { it.type }
            .filterIsInstance<JavaInstanceType>()
            .firstOrNull { it.javaName.canonicalName == "java.util.Map" }
            ?: fail("Expected Map.putAll(Map<? extends K, ? extends V>) parameter")

        assertEquals(
            2,
            mapArg.typeArguments.size,
            "Map<? extends K, ? extends V> must retain two type-argument slots"
        )
        val keyArg = assertIs<TypeParameterType>(mapArg.typeArguments[0])
        val valueArg = assertIs<TypeParameterType>(mapArg.typeArguments[1])
        assertEquals("K", keyArg.name)
        assertEquals("V", valueArg.name)
    }

    @Test
    fun collections_unmodifiable_map_parameter_is_map_with_wildcard_extends_kv() {
        val method = callable(module("java.util.Collections").methods.required("unmodifiableMap"))
        val mapArg = method.callSignatures
            .flatMap { it.parameters }
            .map { it.type }
            .filterIsInstance<JavaInstanceType>()
            .firstOrNull { it.javaName.canonicalName == "java.util.Map" }
            ?: fail("Expected Collections.unmodifiableMap(Map<? extends K, ? extends V>) parameter")

        assertEquals(2, mapArg.typeArguments.size)
        assertEquals("K", assertIs<TypeParameterType>(mapArg.typeArguments[0]).name)
        assertEquals("V", assertIs<TypeParameterType>(mapArg.typeArguments[1]).name)
    }

    @Test
    fun collections_singleton_map_return_is_parameterized_map_not_raw_or_any() {
        val method = callable(module("java.util.Collections").methods.required("singletonMap"))
        val mapReturn = method.callSignatures
            .map { it.returnType }
            .filterIsInstance<JavaInstanceType>()
            .firstOrNull { it.javaName.canonicalName == "java.util.Map" }
            ?: fail("Expected Collections.singletonMap to return java.util.Map")

        assertEquals(2, mapReturn.typeArguments.size)
        // Method type params K/V should appear; not Unknown/Any invention of concrete types.
        assertTrue(
            mapReturn.typeArguments.all { it is TypeParameterType },
            "singletonMap return Map<K,V> should keep type parameters; got ${mapReturn.typeArguments}"
        )
        assertEquals("K", (mapReturn.typeArguments[0] as TypeParameterType).name)
        assertEquals("V", (mapReturn.typeArguments[1] as TypeParameterType).name)
    }

    // -------------------------------------------------------------------------
    // Raw generic surfaces stay conservative
    // -------------------------------------------------------------------------

    @Test
    fun raw_array_list_get_keeps_unresolved_type_parameter_e() {
        val rawList = JavaInstanceType(classType("java.util.ArrayList").classType)
        val get = callable(rawList.allInstanceMembers().required("get").valueType)

        assertTrue(
            get.callSignatures.any { (it.returnType as? TypeParameterType)?.name == "E" },
            "Raw ArrayList.get must keep unresolved type parameter E rather than inventing Object/Any/unknown concrete"
        )
        assertFalse(
            get.callSignatures.any {
                it.returnType == PrimitiveType.ANY ||
                    it.returnType == PrimitiveType.STRING ||
                    it.returnType == UnknownType
            },
            "Raw get() must not invent ANY/STRING/Unknown in place of the declared type parameter E"
        )
    }

    @Test
    fun raw_hash_map_get_keeps_unresolved_value_type_parameter() {
        val rawMap = JavaInstanceType(classType("java.util.HashMap").classType)
        val get = callable(rawMap.allInstanceMembers().required("get").valueType)

        assertTrue(
            get.callSignatures.any { (it.returnType as? TypeParameterType)?.name == "V" },
            "Raw HashMap.get must keep unresolved value type parameter V"
        )
        assertFalse(
            get.callSignatures.any { it.returnType == PrimitiveType.ANY || it.returnType == PrimitiveType.STRING },
            "Raw HashMap.get must not invent a concrete value type"
        )
    }

    @Test
    fun raw_list_receiver_has_no_invented_type_arguments() {
        val rawList = JavaInstanceType(classType("java.util.List").classType)

        assertTrue(
            rawList.typeArguments.isEmpty(),
            "Raw List reflection surface must not invent type arguments; got ${rawList.typeArguments}"
        )
    }

    @Test
    fun raw_map_receiver_has_no_invented_type_arguments() {
        val rawMap = JavaInstanceType(classType("java.util.Map").classType)

        assertTrue(
            rawMap.typeArguments.isEmpty(),
            "Raw Map reflection surface must not invent K/V type arguments; got ${rawMap.typeArguments}"
        )
    }

    // -------------------------------------------------------------------------
    // Conservative bounds: lower-bound wildcards / Object upper bounds
    // -------------------------------------------------------------------------

    @Test
    fun collections_add_all_super_wildcard_parameter_stays_conservative() {
        // Collections.addAll(Collection<? super T> c, T... elements)
        val addAll = callable(module("java.util.Collections").methods.required("addAll"))
        val collectionArg = addAll.callSignatures
            .flatMap { it.parameters }
            .map { it.type }
            .filterIsInstance<JavaInstanceType>()
            .firstOrNull { it.javaName.canonicalName == "java.util.Collection" }
            ?: fail("Expected Collections.addAll(Collection<? super T>, T...) collection parameter")

        val argument = collectionArg.typeArguments.singleOrNull()
            ?: fail("Collection<? super T> must keep a single type-argument slot")

        // Lower-bound wildcards are not modeled from the lower bound; upper is Object → UnknownType.
        // Accept either UnknownType (strict lower-bound erase) or TypeParameterType("T") if the
        // reflector projects the method type variable through the bound — both are conservative
        // relative to inventing a concrete element type.
        val isConservative =
            argument == UnknownType ||
                (argument is TypeParameterType && argument.name == "T")
        assertTrue(
            isConservative,
            "Collection<? super T> must stay conservative (UnknownType or T), not invent concrete types; got $argument"
        )
        assertNotEquals(PrimitiveType.STRING, argument)
        assertNotEquals(PrimitiveType.ANY, argument)
        assertFalse(
            argument is JavaInstanceType &&
                argument.javaName.canonicalName == "java.lang.Object",
            "Lower-bound wildcard must not surface as a precise java.lang.Object instance type"
        )
    }

    @Test
    fun unbounded_list_wildcard_does_not_claim_object_instance_type() {
        val reverse = callable(module("java.util.Collections").methods.required("reverse"))
        val listArg = reverse.callSignatures
            .flatMap { it.parameters }
            .map { it.type }
            .filterIsInstance<JavaInstanceType>()
            .first { it.javaName.canonicalName == "java.util.List" }

        val element = listArg.typeArguments.single()
        assertFalse(
            element is JavaInstanceType &&
                element.javaName.canonicalName == "java.lang.Object",
            "List<?> must not claim a precise Object element type; got $element"
        )
        assertEquals(UnknownType, element)
    }

    @Test
    fun parameterized_string_list_is_not_confused_with_unbounded_wildcard_list() {
        val arrayListClass = classType("java.util.ArrayList").classType
        val stringList = JavaInstanceType(arrayListClass, listOf(PrimitiveType.STRING))
        val reverse = callable(module("java.util.Collections").methods.required("reverse"))
        val wildcardList = reverse.callSignatures
            .flatMap { it.parameters }
            .map { it.type }
            .filterIsInstance<JavaInstanceType>()
            .first { it.javaName.canonicalName == "java.util.List" }

        assertEquals(listOf(PrimitiveType.STRING), stringList.typeArguments)
        assertEquals(listOf(UnknownType), wildcardList.typeArguments)
        assertNotEquals(
            stringList.typeArguments,
            wildcardList.typeArguments,
            "Concrete List<String> and reflected List<?> must remain distinct surfaces"
        )
    }

    // -------------------------------------------------------------------------
    // Metadata / signature surface sanity (no invented generics)
    // -------------------------------------------------------------------------

    @Test
    fun collections_reverse_signature_metadata_retains_wildcard_generic_name() {
        val reverseMember = classType("java.util.Collections").classType
            .allStaticMembers()
            .required("reverse")

        assertTrue(
            reverseMember.signatureMetadata.any { metadata ->
                metadata.genericParameterTypeNames.any { name ->
                    name.contains("List") && name.contains("?")
                }
            },
            "Collections.reverse metadata should retain List<?> generic parameter spelling; " +
                "got ${reverseMember.signatureMetadata.map { it.genericParameterTypeNames }}"
        )
    }

    @Test
    fun map_put_all_signature_metadata_retains_wildcard_extends_spelling() {
        val putAllMember = classType("java.util.Map").classType
            .allInstanceMembers()
            .required("putAll")

        assertTrue(
            putAllMember.signatureMetadata.any { metadata ->
                metadata.genericParameterTypeNames.any { name ->
                    name.contains("Map") && name.contains("?") && name.contains("extends")
                }
            },
            "Map.putAll metadata should retain Map<? extends K, ? extends V> spelling; " +
                "got ${putAllMember.signatureMetadata.map { it.genericParameterTypeNames }}"
        )
    }

    @Test
    fun list_add_all_does_not_collapse_wildcard_collection_type_arguments() {
        val addAll = callable(classType("java.util.List").allInstanceMembers().required("addAll").valueType)
        val collectionParams = addAll.callSignatures
            .flatMap { it.parameters }
            .map { it.type }
            .filterIsInstance<JavaInstanceType>()
            .filter { it.javaName.canonicalName == "java.util.Collection" }

        assertTrue(collectionParams.isNotEmpty(), "Expected Collection wildcard parameter on List.addAll")
        // Collection<? extends E> must remain a Collection surface — not rewritten into ArrayType.
        assertTrue(
            collectionParams.all { param -> param.typeArguments.size == 1 },
            "Collection<? extends E> must keep a single type-argument slot"
        )
        // Guard that none of the Collection-typed parameters were rewritten as ArrayType
        // (type is already JavaInstanceType via filterIsInstance above).
        assertTrue(
            collectionParams.none { it.typeArguments.isEmpty() },
            "List.addAll Collection<? extends E> must keep type-argument surface, not collapse to raw"
        )
    }

    @Test
    fun collections_empty_map_return_keeps_method_type_parameters_not_unknown_kv() {
        val method = callable(module("java.util.Collections").methods.required("emptyMap"))
        val mapReturn = method.callSignatures
            .map { it.returnType }
            .filterIsInstance<JavaInstanceType>()
            .firstOrNull { it.javaName.canonicalName == "java.util.Map" }
            ?: fail("Expected Collections.emptyMap to return java.util.Map")

        assertEquals(2, mapReturn.typeArguments.size)
        assertTrue(
            mapReturn.typeArguments.all { it is TypeParameterType },
            "emptyMap Map<K,V> must keep type parameters rather than inventing Unknown/Any K/V; got ${mapReturn.typeArguments}"
        )
        assertFalse(
            mapReturn.typeArguments.any { it == UnknownType || it == PrimitiveType.ANY },
            "emptyMap must not invent Unknown/Any for K/V"
        )
    }

    // -------------------------------------------------------------------------
    // Helpers (mirror JvmReflectionModelTddTest style)
    // -------------------------------------------------------------------------

    private fun providerFile(className: String): ProviderFile {
        val path = jvmClassPath(className)
        val snapshot = provider.providersFor(
            mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to className)
        )[path] ?: fail("Missing provider for $className at ${path.value}")
        val surface = snapshot.moduleExportSurface ?: fail("Missing export surface for $className")
        return ProviderFile(path, snapshot, surface, surface.moduleType)
    }

    private fun module(className: String): ModuleType = providerFile(className).module

    private fun classType(className: String): JavaInstanceType =
        assertIs(module(className).fields.required("__class"))

    private fun callable(type: Type): CallableType = assertIs(type)

    private fun jvmClassPath(className: String): VirtualPath =
        VirtualPath.of("__jvm__/classes/${className.replace('.', '/')}.lua")

    private fun <T> Map<String, T>.required(name: String): T =
        this[name] ?: fail("Missing reflected member '$name'; available: ${keys.sorted().joinToString()}")

    private data class ProviderFile(
        val path: VirtualPath,
        val snapshot: WorkspaceSnapshot.FileSnapshot,
        val surface: ModuleExportSurface,
        val module: ModuleType
    )
}
