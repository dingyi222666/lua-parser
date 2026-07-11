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
import io.github.dingyi222666.luaparser.semantic.types.syntax.ObjectTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TupleTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParseException
import io.github.dingyi222666.luaparser.semantic.types.syntax.TypeSyntaxParser
import io.github.dingyi222666.luaparser.semantic.types.syntax.UnionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.render
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Focused corpus for union (`|`) and optional/nullable (`?`) type annotations.
 *
 * Covers parse shape, render, round-trip, and invalid inputs that must report
 * without crashing the host (parseOrNull / TypeSyntaxParseException).
 */
class TypeSyntaxUnionOptionalTddTest {

    @Test
    fun parsesSimpleTwoAndThreeWayUnions() {
        assertEquals(
            UnionTypeSyntax(listOf(NamedTypeSyntax("string"), NamedTypeSyntax("number"))),
            TypeSyntaxParser.parse("string | number")
        )
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    NamedTypeSyntax("string"),
                    NamedTypeSyntax("number"),
                    NamedTypeSyntax("boolean")
                )
            ),
            TypeSyntaxParser.parse("string | number | boolean")
        )
    }

    @Test
    fun parsesUnionWithNilAsOptionalPattern() {
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    NamedTypeSyntax("string"),
                    LiteralTypeSyntax("nil")
                )
            ),
            TypeSyntaxParser.parse("string | nil")
        )
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    LiteralTypeSyntax("nil"),
                    NamedTypeSyntax("string"),
                    NamedTypeSyntax("number")
                )
            ),
            TypeSyntaxParser.parse("nil | string | number")
        )
    }

    @Test
    fun parsesUnionWithLiteralsAndQualifiedNames() {
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    LiteralTypeSyntax("\"ok\""),
                    LiteralTypeSyntax("\"err\""),
                    LiteralTypeSyntax("nil")
                )
            ),
            TypeSyntaxParser.parse("\"ok\" | \"err\" | nil")
        )
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    NamedTypeSyntax("pkg.mod.A"),
                    NamedTypeSyntax("pkg.mod.B")
                )
            ),
            TypeSyntaxParser.parse("pkg.mod.A | pkg.mod.B")
        )
    }

    @Test
    fun parsesUnionRespectingIntersectionAndArrayPrecedence() {
        // Intersection binds tighter than union: A | B & C == A | (B & C)
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    NamedTypeSyntax("A"),
                    IntersectionTypeSyntax(listOf(NamedTypeSyntax("B"), NamedTypeSyntax("C")))
                )
            ),
            TypeSyntaxParser.parse("A | B & C")
        )

        // Postfix array binds tighter than union: A | B[] == A | (B[])
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    NamedTypeSyntax("A"),
                    ArrayTypeSyntax(NamedTypeSyntax("B"))
                )
            ),
            TypeSyntaxParser.parse("A | B[]")
        )

        // Parentheses override: (A | B)[]
        assertEquals(
            ArrayTypeSyntax(
                UnionTypeSyntax(listOf(NamedTypeSyntax("A"), NamedTypeSyntax("B")))
            ),
            TypeSyntaxParser.parse("(A | B)[]")
        )
    }

    @Test
    fun parsesNestedAndGroupedUnions() {
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    NamedTypeSyntax("A"),
                    UnionTypeSyntax(listOf(NamedTypeSyntax("B"), NamedTypeSyntax("C")))
                )
            ),
            TypeSyntaxParser.parse("A | (B | C)")
        )
        // Grouped left arm is preserved in the AST even though render flattens left-assoc unions.
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    UnionTypeSyntax(listOf(NamedTypeSyntax("A"), NamedTypeSyntax("B"))),
                    NamedTypeSyntax("C")
                )
            ),
            TypeSyntaxParser.parse("(A | B) | C")
        )
    }

    @Test
    fun parsesSimpleOptionalAndChainedOptionalPostfix() {
        assertEquals(
            NullableTypeSyntax(NamedTypeSyntax("string")),
            TypeSyntaxParser.parse("string?")
        )
        assertEquals(
            NullableTypeSyntax(
                ArrayTypeSyntax(
                    NullableTypeSyntax(NamedTypeSyntax("Foo"))
                )
            ),
            TypeSyntaxParser.parse("Foo?[]?")
        )
        assertEquals(
            ArrayTypeSyntax(NullableTypeSyntax(NamedTypeSyntax("number"))),
            TypeSyntaxParser.parse("number?[]")
        )
    }

    @Test
    fun parsesOptionalOfUnionAndUnionWithOptionalArms() {
        assertEquals(
            NullableTypeSyntax(
                UnionTypeSyntax(listOf(NamedTypeSyntax("A"), NamedTypeSyntax("B")))
            ),
            TypeSyntaxParser.parse("(A | B)?")
        )
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    NullableTypeSyntax(NamedTypeSyntax("A")),
                    NamedTypeSyntax("B")
                )
            ),
            TypeSyntaxParser.parse("A? | B")
        )
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    NamedTypeSyntax("A"),
                    NullableTypeSyntax(NamedTypeSyntax("B"))
                )
            ),
            TypeSyntaxParser.parse("A | B?")
        )
        assertEquals(
            UnionTypeSyntax(
                listOf(
                    NullableTypeSyntax(NamedTypeSyntax("A")),
                    NullableTypeSyntax(NamedTypeSyntax("B")),
                    LiteralTypeSyntax("nil")
                )
            ),
            TypeSyntaxParser.parse("A? | B? | nil")
        )
    }

    @Test
    fun parsesOptionalInsideGenericsTablesTuplesAndMultiReturn() {
        assertEquals(
            GenericTypeSyntax(
                NamedTypeSyntax("List"),
                listOf(NullableTypeSyntax(NamedTypeSyntax("string")))
            ),
            TypeSyntaxParser.parse("List<string?>")
        )
        assertEquals(
            IndexTableTypeSyntax(
                NamedTypeSyntax("string"),
                NullableTypeSyntax(NamedTypeSyntax("number"))
            ),
            TypeSyntaxParser.parse("table<string, number?>")
        )
        assertEquals(
            TupleTypeSyntax(
                listOf(
                    NamedTypeSyntax("A"),
                    NullableTypeSyntax(NamedTypeSyntax("B")),
                    UnionTypeSyntax(listOf(NamedTypeSyntax("C"), LiteralTypeSyntax("nil")))
                )
            ),
            TypeSyntaxParser.parse("[A, B?, C | nil]")
        )
        assertEquals(
            MultiReturnTypeSyntax(
                listOf(
                    NullableTypeSyntax(NamedTypeSyntax("string")),
                    UnionTypeSyntax(listOf(NamedTypeSyntax("number"), LiteralTypeSyntax("nil")))
                )
            ),
            TypeSyntaxParser.parse("string?, number | nil")
        )
    }

    @Test
    fun parsesOptionalFunctionParametersAndObjectFields() {
        assertEquals(
            FunctionTypeSyntax(
                parameters = listOf(
                    FunctionParameterSyntax("x", NamedTypeSyntax("string")),
                    FunctionParameterSyntax("y", NamedTypeSyntax("number"), optional = true),
                    FunctionParameterSyntax(
                        "z",
                        UnionTypeSyntax(listOf(NamedTypeSyntax("boolean"), LiteralTypeSyntax("nil"))),
                        optional = true
                    )
                ),
                returnType = NullableTypeSyntax(NamedTypeSyntax("string"))
            ),
            TypeSyntaxParser.parse("fun(x: string, y?: number, z?: boolean | nil): string?")
        )

        assertEquals(
            ObjectTypeSyntax(
                fields = listOf(
                    ObjectFieldSyntax(IdentifierObjectFieldNameSyntax("id"), NamedTypeSyntax("string")),
                    ObjectFieldSyntax(
                        IdentifierObjectFieldNameSyntax("label"),
                        NullableTypeSyntax(NamedTypeSyntax("string")),
                        optional = true
                    ),
                    ObjectFieldSyntax(
                        IdentifierObjectFieldNameSyntax("status"),
                        UnionTypeSyntax(
                            listOf(
                                LiteralTypeSyntax("\"open\""),
                                LiteralTypeSyntax("\"closed\""),
                                LiteralTypeSyntax("nil")
                            )
                        ),
                        optional = true
                    )
                )
            ),
            TypeSyntaxParser.parse(
                "{ id: string, label?: string?, status?: \"open\" | \"closed\" | nil }"
            )
        )
    }

    @Test
    fun parsesUnionAndOptionalInFunctionReturnAndParamTypes() {
        assertEquals(
            FunctionTypeSyntax(
                parameters = listOf(
                    FunctionParameterSyntax(
                        "value",
                        UnionTypeSyntax(listOf(NamedTypeSyntax("A"), NamedTypeSyntax("B")))
                    )
                ),
                returnType = UnionTypeSyntax(
                    listOf(
                        NullableTypeSyntax(NamedTypeSyntax("A")),
                        NamedTypeSyntax("B")
                    )
                )
            ),
            TypeSyntaxParser.parse("fun(value: A | B): A? | B")
        )

        assertEquals(
            FunctionTypeSyntax(
                parameters = listOf(
                    FunctionParameterSyntax(
                        "items",
                        ArrayTypeSyntax(
                            UnionTypeSyntax(listOf(NamedTypeSyntax("string"), NamedTypeSyntax("number")))
                        )
                    )
                ),
                returnType = NullableTypeSyntax(
                    UnionTypeSyntax(listOf(NamedTypeSyntax("string"), NamedTypeSyntax("number")))
                )
            ),
            TypeSyntaxParser.parse("fun(items: (string | number)[]): (string | number)?")
        )
    }

    @Test
    fun rendersUnionAndOptionalForms() {
        assertEquals(
            "string | number",
            UnionTypeSyntax(listOf(NamedTypeSyntax("string"), NamedTypeSyntax("number"))).render()
        )
        assertEquals(
            "string?",
            NullableTypeSyntax(NamedTypeSyntax("string")).render()
        )
        assertEquals(
            "(A | B)?",
            NullableTypeSyntax(
                UnionTypeSyntax(listOf(NamedTypeSyntax("A"), NamedTypeSyntax("B")))
            ).render()
        )
        assertEquals(
            "A? | B?",
            UnionTypeSyntax(
                listOf(
                    NullableTypeSyntax(NamedTypeSyntax("A")),
                    NullableTypeSyntax(NamedTypeSyntax("B"))
                )
            ).render()
        )
        assertEquals(
            "A | (B | C)",
            UnionTypeSyntax(
                listOf(
                    NamedTypeSyntax("A"),
                    UnionTypeSyntax(listOf(NamedTypeSyntax("B"), NamedTypeSyntax("C")))
                )
            ).render()
        )
        // Multi-return delimits a trailing union arm with parentheses.
        assertEquals(
            "string?, (number | nil)",
            MultiReturnTypeSyntax(
                listOf(
                    NullableTypeSyntax(NamedTypeSyntax("string")),
                    UnionTypeSyntax(listOf(NamedTypeSyntax("number"), LiteralTypeSyntax("nil")))
                )
            ).render()
        )
        assertEquals(
            "((A | B) & C)[]?",
            NullableTypeSyntax(
                ArrayTypeSyntax(
                    IntersectionTypeSyntax(
                        listOf(
                            UnionTypeSyntax(listOf(NamedTypeSyntax("A"), NamedTypeSyntax("B"))),
                            NamedTypeSyntax("C")
                        )
                    )
                )
            ).render()
        )
    }

    @Test
    fun roundTripsUnionAndOptionalCorpusSamples() {
        // Samples must be stable under parse → render (renderer normalizes spacing
        // and inserts required parentheses for right-nested / multi-return unions).
        val samples = listOf(
            "string | number",
            "string | number | boolean",
            "string | nil",
            "nil | string | number",
            "\"ok\" | \"err\" | nil",
            "pkg.mod.A | pkg.mod.B",
            "A | B & C",
            "A | B[]",
            "(A | B)[]",
            "A | (B | C)",
            "string?",
            "Foo?[]?",
            "number?[]",
            "(A | B)?",
            "A? | B",
            "A | B?",
            "A? | B? | nil",
            "List<string?>",
            "table<string, number?>",
            "[A, B?, C | nil]",
            "string?, (number | nil)",
            "fun(x: string, y?: number, z?: boolean | nil): string?",
            "{ id: string, label?: string?, status?: \"open\" | \"closed\" | nil }",
            "fun(value: A | B): A? | B",
            "fun(items: (string | number)[]): (string | number)?",
            "((A | B) & C)[]?"
        )

        samples.forEach { sample ->
            val parsed = TypeSyntaxParser.parse(sample)
            assertEquals(sample, parsed.render(), "round-trip failed for: $sample")
            // Second parse of rendered form must be stable.
            assertEquals(sample, TypeSyntaxParser.parse(parsed.render()).render())
        }
    }

    @Test
    fun invalidUnionAndOptionalSyntaxReportsWithoutThrowViaParseOrNull() {
        val invalid = listOf(
            "A |",
            "| A",
            "A | | B",
            "?",
            "A | ?",
            "|",
            "A? |",
            "(A | B",
            "A | B)",
            "fun(x?: ): number",
            "{ label?: }",
            "A | B |",
            "string? |",
            " | string",
            "A& | B",
            "A | & B"
        )

        invalid.forEach { input ->
            val result = runCatching { TypeSyntaxParser.parseOrNull(input) }
            assertTrue(
                result.isSuccess,
                "parseOrNull must not throw for invalid input: $input; threw ${result.exceptionOrNull()}"
            )
            assertNull(result.getOrNull(), "parseOrNull must return null for invalid input: $input")
        }
    }

    @Test
    fun invalidUnionAndOptionalSyntaxReportsControlledExceptionFromParse() {
        data class Case(val input: String, val messageSnippet: String)

        // Message snippets match TypeSyntaxParser.fail() paths for these shapes:
        // - EOF after `|` uses "Expected type annotation"
        // - operator / non-type tokens at a primary position use "Expected identifier"
        // - unclosed groups / trailing tokens use their dedicated messages
        val cases = listOf(
            Case("A |", "Expected type annotation"),
            Case("| A", "Expected identifier"),
            Case("?", "Expected identifier"),
            Case("A | | B", "Expected identifier"),
            Case("(A | B", "Expected ')' to close grouped type"),
            Case("A | B)", "Unexpected trailing type tokens")
        )

        cases.forEach { case ->
            val ex = assertFailsWith<TypeSyntaxParseException>(
                message = "parse must report TypeSyntaxParseException for: ${case.input}"
            ) {
                TypeSyntaxParser.parse(case.input)
            }
            assertContains(
                ex.message ?: "",
                case.messageSnippet
            )
            // Ensure it is a typed report path, not an unexpected runtime failure type.
            assertIs<TypeSyntaxParseException>(ex)
            assertIs<IllegalArgumentException>(ex)
        }
    }

    @Test
    fun parsePrefixStopsCleanlyAtBrokenUnionContinuation() {
        val parsed = TypeSyntaxParser.parsePrefix("string | trailing prose")
        assertEquals(NamedTypeSyntax("string"), parsed.syntax)
        assertTrue(parsed.remainder.trimStart().startsWith("|"))
        assertTrue(parsed.consumedLength > 0)
    }

    @Test
    fun parsePrefixAcceptsCompleteUnionAndOptionalPrefix() {
        // parsePrefix only keeps a `|` arm when the arm is followed by a type-syntax
        // boundary (EOF or , | & ) ] } >). Trailing Lua comments (`-- ...`) are not
        // boundaries, so "string | number -- note" stops before `|` like prose.
        val union = TypeSyntaxParser.parsePrefix("string | number")
        assertEquals(
            UnionTypeSyntax(listOf(NamedTypeSyntax("string"), NamedTypeSyntax("number"))),
            union.syntax
        )
        assertEquals("", union.remainder.trimStart())

        val unionWithBoundary = TypeSyntaxParser.parsePrefix("string | number) note")
        assertEquals(
            UnionTypeSyntax(listOf(NamedTypeSyntax("string"), NamedTypeSyntax("number"))),
            unionWithBoundary.syntax
        )
        assertEquals(") note", unionWithBoundary.remainder.trimStart())

        val optional = TypeSyntaxParser.parsePrefix("string?  rest")
        assertEquals(NullableTypeSyntax(NamedTypeSyntax("string")), optional.syntax)
        assertEquals("rest", optional.remainder.trimStart())
    }

    @Test
    fun exposesUnionOptionsAndNullableInnerStructure() {
        val union = assertIs<UnionTypeSyntax>(TypeSyntaxParser.parse("A? | B | nil"))
        assertEquals(3, union.options.size)
        assertIs<NullableTypeSyntax>(union.options[0]).also { opt ->
            assertEquals(NamedTypeSyntax("A"), opt.innerType)
        }
        assertEquals(NamedTypeSyntax("B"), union.options[1])
        assertEquals(LiteralTypeSyntax("nil"), union.options[2])

        val optionalUnion = assertIs<NullableTypeSyntax>(TypeSyntaxParser.parse("(string | number)?"))
        val inner = assertIs<UnionTypeSyntax>(optionalUnion.innerType)
        assertEquals(
            listOf(NamedTypeSyntax("string"), NamedTypeSyntax("number")),
            inner.options
        )
    }

    @Test
    fun whitespaceAroundUnionAndOptionalIsNormalizedOnRender() {
        val samples = mapOf(
            "string|number" to "string | number",
            "string  |   number" to "string | number",
            "string  ?" to "string?",
            "( A | B ) ?" to "(A | B)?",
            "A?|B?" to "A? | B?"
        )

        samples.forEach { (input, expectedRender) ->
            val syntax: TypeSyntax = assertNotNull(TypeSyntaxParser.parse(input))
            assertEquals(expectedRender, syntax.render(), "normalized render for: $input")
        }
    }
}
