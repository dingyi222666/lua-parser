package semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeNormalizer
import io.github.dingyi222666.luaparser.semantic.types.resolve.isAssignableFrom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-337 corpus: generic type args surface on hover/completion-oriented type models.
 *
 * Surfaces:
 * - [AppliedType] carries baseName + typeArguments and a stable displayName.
 * - [TypeParameterType] preserves name/constraint/default.
 * - Missing args degrade without throw (empty typeArguments list is valid).
 * - Assignability of AppliedType requires matching base + arg-wise assignability.
 *
 * Test-only. Verification is review-owned (no Gradle).
 */
class GenericTypeArgSurfaceTddTest {

    @Test
    fun appliedTypeDisplayNameIncludesArgs() {
        val applied = AppliedType("Box", listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        assertEquals("Box", applied.baseName)
        assertEquals(2, applied.typeArguments.size)
        assertTrue(applied.displayName.contains("Box"))
        assertTrue(applied.displayName.contains("string") || applied.displayName.contains("String") || "string" in applied.name.lowercase())
    }

    @Test
    fun appliedTypeWithEmptyArgsDoesNotThrow() {
        val applied = runCatching { AppliedType("Raw", emptyList()) }
            .getOrElse { error("empty type args must not throw: $it") }
        assertEquals("Raw", applied.baseName)
        assertTrue(applied.typeArguments.isEmpty())
        assertTrue(applied.displayName.isNotBlank())
    }

    @Test
    fun typeParameterSurfacesNameConstraintAndDefault() {
        val tp = TypeParameterType(
            name = "T",
            constraint = PrimitiveType.STRING,
            defaultType = PrimitiveType.STRING
        )
        assertEquals("T", tp.name)
        assertEquals(PrimitiveType.STRING, tp.constraint)
        assertEquals(PrimitiveType.STRING, tp.defaultType)
    }

    @Test
    fun typeParameterWithoutConstraintStillSurfacesName() {
        val tp = TypeParameterType("U")
        assertEquals("U", tp.name)
        assertEquals(null, tp.constraint)
        assertEquals(null, tp.defaultType)
    }

    @Test
    fun appliedAssignabilityRequiresSameBaseAndMatchingArgs() {
        val boxString = AppliedType("Box", listOf(PrimitiveType.STRING))
        val boxNumber = AppliedType("Box", listOf(PrimitiveType.NUMBER))
        val other = AppliedType("Bag", listOf(PrimitiveType.STRING))
        assertTrue(boxString.isAssignableFrom(boxString))
        assertFalse(boxString.isAssignableFrom(boxNumber))
        assertFalse(boxString.isAssignableFrom(other))
    }

    @Test
    fun appliedWithUnknownArgIsPermissiveWhereUnknownAbsorbs() {
        val target = AppliedType("Box", listOf(PrimitiveType.STRING))
        val source = AppliedType("Box", listOf(UnknownType))
        // isAssignable(string, unknown) is true → Applied assignability holds.
        assertTrue(target.isAssignableFrom(source))
    }

    @Test
    fun missingArgCountMismatchRejectsAppliedAssignability() {
        val one = AppliedType("Pair", listOf(PrimitiveType.STRING))
        val two = AppliedType("Pair", listOf(PrimitiveType.STRING, PrimitiveType.NUMBER))
        assertFalse(one.isAssignableFrom(two))
        assertFalse(two.isAssignableFrom(one))
    }

    @Test
    fun normalizeDoesNotThrowOnAppliedOrTypeParameter() {
        val applied = AppliedType("Box", listOf(PrimitiveType.STRING))
        val tp = TypeParameterType("T", constraint = PrimitiveType.NUMBER)
        val n1 = runCatching { TypeNormalizer.normalize(applied) }.getOrElse { error(it) }
        val n2 = runCatching { TypeNormalizer.normalize(tp) }.getOrElse { error(it) }
        assertTrue(n1.displayName.isNotBlank())
        assertTrue(n2.displayName.isNotBlank())
    }

    @Test
    fun classTypeWithTypeParametersSurfacesParameterList() {
        val cls = ClassType(
            name = "Box",
            typeParameters = listOf(TypeParameterType("T")),
            fields = mapOf("value" to TypeParameterType("T"))
        )
        assertEquals(1, cls.typeParameters.size)
        assertEquals("T", cls.typeParameters.single().name)
        assertTrue(cls.fields.containsKey("value"))
    }
}
