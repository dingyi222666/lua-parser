package semantic.types.resolve

import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.ErrorType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.NeverType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.VarargType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TypeNormalizerTest {

    @Test
    fun aliasChainUnwrapsToFinalTarget() {
        val type = AliasType("Outer", AliasType("Inner", PrimitiveType.STRING))

        assertEquals(PrimitiveType.STRING, TypeNormalizer.normalize(type))
    }

    @Test
    fun aliasNormalizationUnwrapsNestedAliasesAcrossTypeFamilies() {
        val stringAlias = AliasType("StringAlias", PrimitiveType.STRING)
        val numberAlias = AliasType("NumberAlias", PrimitiveType.NUMBER)
        val tupleAlias = AliasType("TupleAlias", TupleType(listOf(stringAlias, numberAlias)))

        val normalized = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        AliasType("Branch", stringAlias),
                        IntersectionType(
                            linkedSetOf(
                                PrimitiveType.ANY,
                                FunctionType(
                                    parameters = listOf(FunctionParameter("value", stringAlias)),
                                    returnType = tupleAlias
                                )
                            )
                        ),
                        TableType(
                            fields = mapOf("field" to AliasType("FieldAlias", numberAlias)),
                            methods = mapOf("method" to FunctionType(returnType = stringAlias))
                        )
                    )
                )
            )
        )

        assertEquals(3, normalized.types.size)
        assertEquals(PrimitiveType.STRING, normalized.types.first())
        val function = normalized.types.filterIsInstance<FunctionType>().single()
        assertEquals(PrimitiveType.STRING, function.parameters.single().type)
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER), (function.returnType as TupleType).elementTypes)
        val table = normalized.types.filterIsInstance<TableType>().single()
        assertEquals(PrimitiveType.NUMBER, table.fields.getValue("field"))
        assertEquals(PrimitiveType.STRING, (table.methods.getValue("method") as FunctionType).returnType)
    }

    @Test
    fun canonicalizesPrimitiveSingletons() {
        assertEquals(UnknownType, TypeNormalizer.normalize(PrimitiveType("u", PrimitiveType.Kind.UNKNOWN)))
        assertEquals(ErrorType, TypeNormalizer.normalize(PrimitiveType("e", PrimitiveType.Kind.ERROR)))
        assertEquals(NeverType, TypeNormalizer.normalize(PrimitiveType("n", PrimitiveType.Kind.NEVER)))
        assertEquals(PrimitiveType.STRING, TypeNormalizer.normalize(PrimitiveType("text", PrimitiveType.Kind.STRING)))
    }

    @Test
    fun simplifiesUnions() {
        val normalized = assertIs<UnionType>(
            TypeNormalizer.normalize(
                UnionType(
                    linkedSetOf(
                        PrimitiveType.STRING,
                        UnionType(linkedSetOf(PrimitiveType.NUMBER, PrimitiveType.STRING)),
                        LiteralType("x", PrimitiveType.STRING),
                        NeverType,
                        PrimitiveType.NUMBER
                    )
                )
            )
        )

        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER), normalized.types.toList())
        assertEquals(PrimitiveType.ANY, TypeNormalizer.normalize(UnionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.ANY))))
        assertEquals(UnknownType, TypeNormalizer.normalize(UnionType(linkedSetOf(PrimitiveType.STRING, UnknownType))))
        assertEquals(PrimitiveType.STRING, TypeNormalizer.normalize(UnionType(linkedSetOf(PrimitiveType.STRING, LiteralType("x", PrimitiveType.STRING)))))
    }

    @Test
    fun simplifiesIntersections() {
        val flattened = assertIs<IntersectionType>(
            TypeNormalizer.normalize(
                IntersectionType(
                    linkedSetOf(
                        PrimitiveType.STRING,
                        IntersectionType(linkedSetOf(PrimitiveType.STRING, CustomType("Named"))),
                        CustomType("Named")
                    )
                )
            )
        )

        assertEquals(listOf(PrimitiveType.STRING, CustomType("Named")), flattened.types.toList())
        assertEquals(PrimitiveType.STRING, TypeNormalizer.normalize(IntersectionType(linkedSetOf(PrimitiveType.ANY, PrimitiveType.STRING))))
        assertEquals(LiteralType("x", PrimitiveType.STRING), TypeNormalizer.normalize(IntersectionType(linkedSetOf(PrimitiveType.STRING, LiteralType("x", PrimitiveType.STRING)))))
        assertEquals(NeverType, TypeNormalizer.normalize(IntersectionType(linkedSetOf(PrimitiveType.STRING, PrimitiveType.NUMBER))))
        assertEquals(PrimitiveType.STRING, TypeNormalizer.normalize(IntersectionType(linkedSetOf(PrimitiveType.STRING))))
        assertEquals(PrimitiveType.STRING, TypeNormalizer.normalize(IntersectionType(linkedSetOf(PrimitiveType.STRING, UnknownType, ErrorType))))
    }

    @Test
    fun recursivelyNormalizesRemainingTypeFamilies() {
        val alias = AliasType("StringAlias", PrimitiveType.STRING)
        val typeParameter = TypeParameterType("T", constraint = alias, defaultType = AliasType("Default", PrimitiveType.NUMBER))
        val classType = ClassType(
            name = "Widget",
            fields = mapOf("value" to alias),
            methods = mapOf("call" to FunctionType(returnType = alias)),
            typeParameters = listOf(typeParameter),
            alias = AliasType("WidgetAlias", alias)
        )

        val normalized = listOf<Type>(
            TypeNormalizer.normalize(ArrayType(alias)),
            TypeNormalizer.normalize(TupleType(listOf(alias, AliasType("NumberAlias", PrimitiveType.NUMBER)))),
            TypeNormalizer.normalize(MultiReturnType(listOf(alias, AliasType("NilAlias", PrimitiveType.NIL)))),
            TypeNormalizer.normalize(VarargType(alias)),
            TypeNormalizer.normalize(AppliedType("Box", listOf(alias))),
            TypeNormalizer.normalize(typeParameter),
            TypeNormalizer.normalize(classType)
        )

        assertEquals(PrimitiveType.STRING, (normalized[0] as ArrayType).elementType)
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER), (normalized[1] as TupleType).elementTypes)
        assertEquals(listOf(PrimitiveType.STRING, PrimitiveType.NIL), (normalized[2] as MultiReturnType).types)
        assertEquals(PrimitiveType.STRING, (normalized[3] as VarargType).elementType)
        assertEquals(listOf(PrimitiveType.STRING), (normalized[4] as AppliedType).typeArguments)
        assertEquals(PrimitiveType.STRING, (normalized[5] as TypeParameterType).constraint)
        val normalizedClass = normalized[6] as ClassType
        assertEquals(PrimitiveType.STRING, normalizedClass.fields.getValue("value"))
        assertEquals(PrimitiveType.STRING, (normalizedClass.methods.getValue("call") as FunctionType).returnType)
        assertEquals(PrimitiveType.STRING, normalizedClass.alias?.target)
    }

    @Test
    fun aliasWrappedOverloadedCallableNormalizesToOverloadedCallable() {
        val type = AliasType(
            "CallableAlias",
            OverloadedFunctionType(
                listOf(
                    FunctionType(returnType = AliasType("StringAlias", PrimitiveType.STRING)),
                    FunctionType(returnType = AliasType("NumberAlias", PrimitiveType.NUMBER))
                )
            )
        )

        val normalized = assertIs<OverloadedFunctionType>(TypeNormalizer.normalize(type))
        assertEquals(PrimitiveType.STRING, normalized.callSignatures[0].returnType)
        assertEquals(PrimitiveType.NUMBER, normalized.callSignatures[1].returnType)
    }
}
