package semantic.types.model

import io.github.dingyi222666.luaparser.semantic.types.FunctionType as LegacyFunctionType
import io.github.dingyi222666.luaparser.semantic.types.GenericType as LegacyGenericType
import io.github.dingyi222666.luaparser.semantic.types.ParameterType as LegacyParameterType
import io.github.dingyi222666.luaparser.semantic.types.PrimitiveType as LegacyPrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.bridges.toModelType
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.LiteralType
import io.github.dingyi222666.luaparser.semantic.types.model.MultiReturnType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.model.unwrapAliases
import io.github.dingyi222666.luaparser.semantic.types.resolve.overloadedFunctionOf
import io.github.dingyi222666.luaparser.semantic.types.resolve.unionTypeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TypeModelTest {

    @Test
    fun instantiatesRepresentativeModelFamilies() {
        val typeParameter = TypeParameterType("T", PrimitiveType.STRING)
        val callable = FunctionType(
            typeParameters = listOf(typeParameter),
            parameters = listOf(
                FunctionParameter("value", typeParameter),
                FunctionParameter("rest", PrimitiveType.NUMBER, vararg = true)
            ),
            returnType = MultiReturnType(listOf(typeParameter, PrimitiveType.NIL))
        )
        val table = TableType(
            fields = mapOf("name" to PrimitiveType.STRING),
            methods = mapOf("call" to callable),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )
        val baseClass = ClassType("Base", fields = mapOf("id" to PrimitiveType.NUMBER))
        val classType = ClassType(
            name = "Widget",
            fields = mapOf("data" to table),
            methods = mapOf("invoke" to callable),
            superClass = baseClass,
            typeParameters = listOf(typeParameter)
        )
        val alias = AliasType("WidgetAlias", classType)
        val union = UnionType(linkedSetOf(alias, LiteralType("ready", PrimitiveType.STRING), ArrayType(TupleType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)))))

        assertEquals("fun<T: string>(value: T, rest: number...): T, nil", callable.displayName)
        assertEquals("{ name: string, call: fun<T: string>(value: T, rest: number...): T, nil, [string]: number }", table.displayName)
        assertEquals("number", classType.getAllFields().getValue("id").displayName)
        assertEquals("WidgetAlias", alias.displayName)
        assertEquals("\"ready\"", (union.types.elementAt(1)).displayName)
    }

    @Test
    fun bridgesLegacyTypesIntoNewModel() {
        val legacy = LegacyFunctionType(
            parameters = listOf(LegacyParameterType("value", LegacyPrimitiveType.STRING)),
            returnType = LegacyPrimitiveType.NUMBER
        )

        val converted = assertIs<FunctionType>(legacy.toModelType())

        assertEquals("fun(value: string): number", converted.displayName)
        assertEquals("string", converted.parameters.single().type.displayName)
    }

    @Test
    fun preservesGenericApplicationsInLegacyBridge() {
        val converted = assertIs<AppliedType>(
            LegacyGenericType(
                baseName = "Result",
                typeParameters = listOf(LegacyPrimitiveType.STRING, LegacyPrimitiveType.NUMBER)
            ).toModelType()
        )

        assertEquals("Result", converted.baseName)
        assertEquals(listOf("string", "number"), converted.typeArguments.map { it.displayName })
        assertEquals("Result<string, number>", converted.displayName)
    }

    @Test
    fun unwrapsAliasesRecursively() {
        val target = PrimitiveType.STRING
        val alias = AliasType("Name", AliasType("InnerName", target))

        assertEquals(target, alias.unwrapAliases())
    }

    @Test
    fun aggregatesInheritedClassFieldsAndMethods() {
        val baseCall = FunctionType(returnType = PrimitiveType.BOOLEAN)
        val childCall = FunctionType(returnType = PrimitiveType.STRING)
        val base = ClassType(
            name = "Base",
            fields = mapOf("id" to PrimitiveType.NUMBER),
            methods = mapOf("isReady" to baseCall)
        )
        val child = ClassType(
            name = "Child",
            fields = mapOf("name" to PrimitiveType.STRING),
            methods = mapOf("render" to childCall),
            superClass = base
        )

        assertEquals(listOf("id", "name"), child.getAllFields().keys.toList())
        assertEquals(listOf("isReady", "render"), child.getAllMethods().keys.toList())
    }

    @Test
    fun modelsOverloadedFunctionsAsSignatureLists() {
        val overloaded = OverloadedFunctionType(
            listOf(
                FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)), returnType = PrimitiveType.NUMBER),
                FunctionType(parameters = listOf(FunctionParameter("value", PrimitiveType.NUMBER)), returnType = PrimitiveType.STRING)
            )
        )

        assertEquals(2, overloaded.callSignatures.size)
        assertEquals(
            listOf("fun(value: string): number", "fun(value: number): string"),
            overloaded.callSignatures.map { it.displayName }
        )
    }

    @Test
    fun exposesTableIndexSignatureStructureAndDisplay() {
        val table = TableType(
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        assertEquals("string", table.indexSignature?.keyType?.displayName)
        assertEquals("number", table.indexSignature?.valueType?.displayName)
        assertEquals("{ [string]: number }", table.displayName)
    }

    @Test
    fun factoryHelpersReturnSimplifiedShapes() {
        val singleSignature = FunctionType(returnType = PrimitiveType.STRING)

        assertEquals(
            PrimitiveType.STRING,
            unionTypeOf(PrimitiveType.STRING, LiteralType("x", PrimitiveType.STRING))
        )
        assertIs<FunctionType>(overloadedFunctionOf(singleSignature))
    }
}
