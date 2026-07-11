package semantic.types.syntax

import io.github.dingyi222666.luaparser.semantic.types.syntax.ArrayTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionParameterSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.GenericTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IdentifierObjectFieldNameSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IndexTableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.IntersectionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.LiteralTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.MultiReturnTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NamedTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NullableTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.ObjectFieldSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.ObjectIndexerSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.ObjectTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.QuotedObjectFieldNameSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TupleTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeParameterSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParseException
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParser
import io.github.dingyi222666.luaparser.semantic.types.syntax.UnionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.VarargTypeSyntax
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypeSyntaxParserTest {

    @Test
    fun parsesQualifiedIdentifiers() {
        assertEquals(
            NamedTypeSyntax("foo.bar.Baz"),
            TypeSyntaxParser.parse("foo.bar.Baz")
        )
    }

    @Test
    fun parsesUnionAndIntersectionWithCorrectPrecedence() {
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    NamedTypeSyntax("A"),
                    IntersectionTypeSyntax(listOf(NamedTypeSyntax("B"), NamedTypeSyntax("C")))
                )
            ),
            TypeSyntaxParser.parse("A | B & C")
        )
    }

    @Test
    fun parsesGroupedTypesBeforePostfixOperators() {
        assertEquals(
            ArrayTypeSyntax(
                UnionTypeSyntax(listOf(NamedTypeSyntax("A"), NamedTypeSyntax("B")))
            ),
            TypeSyntaxParser.parse("(A | B)[]")
        )
    }

    @Test
    fun parsesGenericArguments() {
        assertEquals(
            GenericTypeSyntax(
                NamedTypeSyntax("Foo"),
                listOf(
                    NamedTypeSyntax("T"),
                    GenericTypeSyntax(NamedTypeSyntax("Bar"), listOf(NamedTypeSyntax("U")))
                )
            ),
            TypeSyntaxParser.parse("Foo<T, Bar<U>>")
        )
    }

    @Test
    fun parsesArrayAndNullablePostfixChains() {
        assertEquals(
            NullableTypeSyntax(
                ArrayTypeSyntax(
                    NullableTypeSyntax(NamedTypeSyntax("Foo"))
                )
            ),
            TypeSyntaxParser.parse("Foo?[]?")
        )
    }

    @Test
    fun parsesLiteralTypes() {
        assertEquals(LiteralTypeSyntax("\"hello\""), TypeSyntaxParser.parse("\"hello\""))
        assertEquals(LiteralTypeSyntax("42"), TypeSyntaxParser.parse("42"))
        assertEquals(LiteralTypeSyntax("true"), TypeSyntaxParser.parse("true"))
        assertEquals(LiteralTypeSyntax("nil"), TypeSyntaxParser.parse("nil"))
    }

    @Test
    fun parsePrefixReportsPreciseBoundaryAndRawRemainder() {
        val parsed = TypeSyntaxParser.parsePrefix("Foo<string>   -- trailing comment")

        assertEquals(
            GenericTypeSyntax(NamedTypeSyntax("Foo"), listOf(NamedTypeSyntax("string"))),
            parsed.syntax
        )
        assertEquals(14, parsed.endIndex)
        assertEquals(14, parsed.consumedLength)
        assertEquals("-- trailing comment", parsed.remainder)
    }

    @Test
    fun parsePrefixStopsAtMalformedOperatorBoundary() {
        val parsed = TypeSyntaxParser.parsePrefix("Foo | trailing prose")

        assertEquals(NamedTypeSyntax("Foo"), parsed.syntax)
        assertEquals("| trailing prose", parsed.remainder.trimStart())
    }

    @Test
    fun parsePrefixKeepsCompleteUnionBeforeCommentAndDelimiterBoundaries() {
        val withComment = TypeSyntaxParser.parsePrefix("string | number -- note")
        assertEquals(
            UnionTypeSyntax(listOf(NamedTypeSyntax("string"), NamedTypeSyntax("number"))),
            withComment.syntax
        )
        assertEquals("-- note", withComment.remainder.trimStart())

        val withDelimiter = TypeSyntaxParser.parsePrefix("string | number) tail")
        assertEquals(
            UnionTypeSyntax(listOf(NamedTypeSyntax("string"), NamedTypeSyntax("number"))),
            withDelimiter.syntax
        )
        assertEquals(") tail", withDelimiter.remainder.trimStart())
    }

    @Test
    fun parseOrNullReturnsNullForInvalidInput() {
        assertEquals(null, TypeSyntaxParser.parseOrNull("Foo<"))
    }

    @Test
    fun parsesFunctionTypeWithOptionalVarargAndGenericParameters() {
        assertEquals(
            FunctionTypeSyntax(
                typeParameters = listOf(TypeParameterSyntax("T")),
                parameters = listOf(
                    FunctionParameterSyntax("x", NamedTypeSyntax("T")),
                    FunctionParameterSyntax("y", NamedTypeSyntax("U"), optional = true),
                    FunctionParameterSyntax("rest", NamedTypeSyntax("T"), vararg = true)
                ),
                returnType = NamedTypeSyntax("T")
            ),
            TypeSyntaxParser.parse("fun<T>(x: T, y?: U, rest: T...): T")
        )
    }

    @Test
    fun parsesObjectTypeWithQuotedFieldsOptionalFieldsAndIndexer() {
        assertEquals(
            ObjectTypeSyntax(
                fields = listOf(
                    ObjectFieldSyntax(IdentifierObjectFieldNameSyntax("name"), NamedTypeSyntax("string")),
                    ObjectFieldSyntax(QuotedObjectFieldNameSyntax("\"display-name\""), NamedTypeSyntax("number"), optional = true)
                ),
                indexers = listOf(
                    ObjectIndexerSyntax(
                        keyName = "key",
                        keyType = NamedTypeSyntax("string"),
                        valueType = IndexTableTypeSyntax(NamedTypeSyntax("string"), NamedTypeSyntax("number"))
                    )
                )
            ),
            TypeSyntaxParser.parse("{ name: string, \"display-name\"?: number, [key: string]: table<string, number> }")
        )
    }

    @Test
    fun parsesTupleTypes() {
        assertEquals(
            TupleTypeSyntax(listOf(NamedTypeSyntax("A"), NullableTypeSyntax(NamedTypeSyntax("B")))),
            TypeSyntaxParser.parse("[A, B?]")
        )
    }

    @Test
    fun parsesMultiReturnTypes() {
        assertEquals(
            MultiReturnTypeSyntax(listOf(NamedTypeSyntax("A"), NamedTypeSyntax("B"))),
            TypeSyntaxParser.parse("A, B")
        )
    }

    @Test
    fun preservesFunctionMultiReturnUnionIntersectionPostfixPrecedence() {
        assertEquals(
            FunctionTypeSyntax(
                parameters = listOf(FunctionParameterSyntax("value", NamedTypeSyntax("A"))),
                returnType = MultiReturnTypeSyntax(
                    listOf(
                        UnionTypeSyntax(
                            listOf(
                                NamedTypeSyntax("B"),
                                IntersectionTypeSyntax(
                                    listOf(
                                        ArrayTypeSyntax(NamedTypeSyntax("C")),
                                        NamedTypeSyntax("D")
                                    )
                                )
                            )
                        ),
                        NamedTypeSyntax("E")
                    )
                )
            ),
            TypeSyntaxParser.parse("fun(value: A): B | C[] & D, E")
        )
    }

    @Test
    fun parsesStandaloneVarargAndTableSyntax() {
        assertEquals(
            VarargTypeSyntax(NamedTypeSyntax("string")),
            TypeSyntaxParser.parse("string...")
        )

        assertEquals(
            IndexTableTypeSyntax(NamedTypeSyntax("string"), NamedTypeSyntax("number")),
            TypeSyntaxParser.parse("table<string, number>")
        )
    }

    @Test
    fun rejectsMalformedAdvancedSyntaxClearly() {
        assertContains(
            assertFailsWith<TypeSyntaxParseException> {
                TypeSyntaxParser.parse("Foo<Bar")
            }.message ?: "",
            "Expected '>' to close generic argument list"
        )

        assertContains(
            assertFailsWith<TypeSyntaxParseException> {
                TypeSyntaxParser.parse("(A | B")
            }.message ?: "",
            "Expected ')' to close grouped type"
        )

        assertContains(
            assertFailsWith<TypeSyntaxParseException> {
                TypeSyntaxParser.parse("fun(x: string")
            }.message ?: "",
            "Expected ')' to close function parameter list"
        )

        assertContains(
            assertFailsWith<TypeSyntaxParseException> {
                TypeSyntaxParser.parse("{ name string }")
            }.message ?: "",
            "Expected ':' after object field name"
        )

        assertContains(
            assertFailsWith<TypeSyntaxParseException> {
                TypeSyntaxParser.parse("[A, B")
            }.message ?: "",
            "Expected ',' or ']' in tuple type"
        )

        assertContains(
            assertFailsWith<TypeSyntaxParseException> {
                TypeSyntaxParser.parse("...string")
            }.message ?: "",
            "Use postfix vararg syntax like 'T...'"
        )

        assertContains(
            assertFailsWith<TypeSyntaxParseException> {
                TypeSyntaxParser.parse("A |")
            }.message ?: "",
            "Expected type annotation"
        )
    }
}
