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
 *
 * Compile note (REVIEW20): avoid kotlin.test assertNotEquals/@OnlyInputTypes
 * across unrelated concrete type args after assertIs smart-casts; use
 * assertTrue(x != y) / explicit assertIs<T> instead.
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
        // Avoid assertNotEquals/@OnlyInputTypes across PrimitiveType vs TypeParameterType.
        assertTrue(typeParam != PrimitiveType.ANY, "Wildcard extends E must not collapse to ANY")
        assertTrue(typeParam != PrimitiveType.STRING, "Wildcard extends E must not collapse to STRING")
        assertTrue(typeParam != UnknownType, "Wildcard extends E must not collapse to UnknownType")
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
        assertIs<JavaInstanceType>(module(className).fields.required("__class"))

    private fun callable(type: Type): CallableType = assertIs<CallableType>(type)

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
