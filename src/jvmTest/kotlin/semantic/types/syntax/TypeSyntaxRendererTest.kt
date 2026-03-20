package semantic.types.syntax

import io.github.dingyi222666.luaparser.semantic.types.FunctionParameterSyntax as LegacyFunctionParameterSyntax
import io.github.dingyi222666.luaparser.semantic.types.FunctionTypeSyntax as LegacyFunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.NamedTypeSyntax as LegacyNamedTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionParameterSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.GenericTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IdentifierObjectFieldNameSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IndexTableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NamedTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NullableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.ObjectFieldSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.ObjectIndexerSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.ObjectTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.QuotedObjectFieldNameSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.ArrayTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IntersectionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.MultiReturnTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeParameterSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TupleTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.UnionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.VarargTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParser
import io.github.dingyi222666.luaparser.semantic.types.syntax.render
import io.github.dingyi222666.luaparser.semantic.types.syntax.toSyntaxAst
import kotlin.test.Test
import kotlin.test.assertEquals

class TypeSyntaxRendererTest {

    @Test
    fun rendersFunctionTypeWithTypeParametersAndUnionReturn() {
        val syntax = FunctionTypeSyntax(
            typeParameters = listOf(TypeParameterSyntax("T", NamedTypeSyntax("string"))),
            parameters = listOf(
                FunctionParameterSyntax("value", NamedTypeSyntax("T")),
                FunctionParameterSyntax("fallback", NullableTypeSyntax(NamedTypeSyntax("number")), optional = true)
            ),
            returnType = UnionTypeSyntax(
                listOf(
                    NamedTypeSyntax("T"),
                    NamedTypeSyntax("nil")
                )
            )
        )

        assertEquals("fun<T: string>(value: T, fallback?: number?): T | nil", syntax.render())
    }

    @Test
    fun rendersObjectAndGenericSyntax() {
        val syntax = ObjectTypeSyntax(
            fields = listOf(
                ObjectFieldSyntax(IdentifierObjectFieldNameSyntax("items"), GenericTypeSyntax(NamedTypeSyntax("List"), listOf(NamedTypeSyntax("string")))),
                ObjectFieldSyntax(IdentifierObjectFieldNameSyntax("lookup"), IndexTableTypeSyntax(NamedTypeSyntax("string"), NamedTypeSyntax("number")), optional = true)
            ),
            indexers = listOf(
                ObjectIndexerSyntax(
                    keyName = "key",
                    keyType = NamedTypeSyntax("string"),
                    valueType = GenericTypeSyntax(NamedTypeSyntax("Box"), listOf(NamedTypeSyntax("number")))
                )
            )
        )

        assertEquals(
            "{ items: List<string>, lookup?: table<string, number>, [key: string]: Box<number> }",
            syntax.render()
        )
    }

    @Test
    fun rendersQuotedObjectFieldName() {
        val syntax = ObjectTypeSyntax(
            fields = listOf(
                ObjectFieldSyntax(QuotedObjectFieldNameSyntax("\"foo-bar\""), NamedTypeSyntax("string")),
                ObjectFieldSyntax(QuotedObjectFieldNameSyntax("'x-y'"), NamedTypeSyntax("number"), optional = true)
            )
        )

        assertEquals("{ \"foo-bar\": string, 'x-y'?: number }", syntax.render())
    }

    @Test
    fun rendersTupleMultiReturnAndVarargForms() {
        assertEquals("[A, B?]", TupleTypeSyntax(listOf(NamedTypeSyntax("A"), NullableTypeSyntax(NamedTypeSyntax("B")))).render())
        assertEquals("A, B", MultiReturnTypeSyntax(listOf(NamedTypeSyntax("A"), NamedTypeSyntax("B"))).render())
        assertEquals("string...", VarargTypeSyntax(NamedTypeSyntax("string")).render())
    }

    @Test
    fun normalizesLegacyVarargParameterNamesInBridge() {
        val legacy = LegacyFunctionTypeSyntax(
            parameters = listOf(
                LegacyFunctionParameterSyntax("...", LegacyNamedTypeSyntax("string"), vararg = true)
            ),
            returnTypes = listOf(LegacyNamedTypeSyntax("nil"))
        )

        assertEquals("fun(string...): nil", legacy.toSyntaxAst().render())
    }

    @Test
    fun rendersPrecedenceSensitiveCombinations() {
        val syntax = NullableTypeSyntax(
            ArrayTypeSyntax(
                IntersectionTypeSyntax(
                    listOf(
                        UnionTypeSyntax(listOf(NamedTypeSyntax("A"), NamedTypeSyntax("B"))),
                        NamedTypeSyntax("C")
                    )
                )
            )
        )

        assertEquals("((A | B) & C)[]?", syntax.render())
    }

    @Test
    fun rendersParenthesesForRightNestedSamePrecedenceTypes() {
        assertEquals(
            "A | (B | C)",
            UnionTypeSyntax(
                listOf(
                    NamedTypeSyntax("A"),
                    UnionTypeSyntax(listOf(NamedTypeSyntax("B"), NamedTypeSyntax("C")))
                )
            ).render()
        )

        assertEquals(
            "A, (B, C)",
            MultiReturnTypeSyntax(
                listOf(
                    NamedTypeSyntax("A"),
                    MultiReturnTypeSyntax(listOf(NamedTypeSyntax("B"), NamedTypeSyntax("C")))
                )
            ).render()
        )
    }

    @Test
    fun roundTripsAdvancedSyntaxForms() {
        val samples = listOf(
            "fun<T>(x: T, y?: U): T",
            "{ name: string, \"display-name\"?: number, [key: string]: table<string, number> }",
            "[A, B?]",
            "fun(values: string...): A, B",
            "table<string, number>",
            "A | (B | C)",
            "fun(value: (A | B)[]): A, (B | C)"
        )

        samples.forEach { sample ->
            assertEquals(sample, TypeSyntaxParser.parse(sample).render())
        }
    }
}
