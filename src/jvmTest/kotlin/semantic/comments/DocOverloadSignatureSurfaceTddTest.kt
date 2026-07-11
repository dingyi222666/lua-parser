package semantic.comments

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.checker.CallChecker
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.comments.OverloadTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ParamTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.ReturnTagSyntax
import io.github.dingyi222666.luaparser.semantic.comments.UnknownTagSyntax
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * TASK-290 corpus: EmmyLua `@overload` must surface as extra callable signatures
 * when the model/resolution pipeline supports it, and missing or malformed overload
 * tags must degrade to an empty overload list without crashing.
 *
 * Layers covered (test-only; no production edits):
 * - Comment attach: [OverloadTagSyntax] binds to the following function target.
 * - Type resolution: [DeclarationDocumentation.resolvedOverloadTypes] stores extra
 *   [FunctionType]s parsed from `@overload fun(...)`.
 * - Call surface: [CallChecker.resolveCallable] merges primary + resolved overloads.
 * - Semantic model: [SemanticModel.getSignatureHelpAt] exposes multi-signature help
 *   at call sites when overloads resolve; absent overloads stay single-primary.
 *
 * Verification is review-owned (workers must not run Gradle).
 */
class DocOverloadSignatureSurfaceTddTest {

    private val luaParser = LuaParser()
    private val attachPass = CommentAttachPass()
    private val pipeline = SemanticPipeline()

    // -------------------------------------------------------------------------
    // Attach surface
    // -------------------------------------------------------------------------

    @Test
    fun attachesOverloadTagsToLocalFunctionWithPrimaryParamReturn() {
        val chunk = parse(
            """
            --- normalize value
            ---@overload fun(value: string): string
            ---@param value number
            ---@return number
            local function normalize(value)
                return value
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        assertEquals("normalize", assertIs<Identifier>(function.identifier).name)

        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        assertEquals("normalize value", doc.description)
        assertEquals(listOf("overload", "param", "return"), doc.tags.map { it.tagName })

        val overload = assertIs<OverloadTagSyntax>(doc.tags[0])
        assertEquals("fun(value: string): string", overload.signatureText)

        assertEquals("value", assertIs<ParamTagSyntax>(doc.tags[1]).name)
        assertEquals(listOf("number"), assertIs<ReturnTagSyntax>(doc.tags[2]).typeTexts)
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesMultipleOverloadTagsInSourceOrder() {
        val chunk = parse(
            """
            ---@overload fun(): nil
            ---@overload fun(value: string): string
            ---@overload fun(value: string, count: number): string
            ---@param value number
            ---@return number
            local function pack(value)
                return value
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))
        val overloads = doc.tags.filterIsInstance<OverloadTagSyntax>()

        assertEquals(3, overloads.size)
        assertEquals(
            listOf(
                "fun(): nil",
                "fun(value: string): string",
                "fun(value: string, count: number): string"
            ),
            overloads.map { it.signatureText }
        )
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesOverloadOnGlobalAndColonMethodFunctions() {
        val chunk = parse(
            """
            ---@overload fun(value: string): string
            ---@param value number
            ---@return number
            function globalNormalize(value)
                return value
            end

            local widget = {}

            ---@overload fun(self: table, value: string): string
            ---@param value number
            ---@return number
            function widget:pick(value)
                return value
            end
            """.trimIndent()
        )

        val functions = chunk.body.statements.filterIsInstance<FunctionDeclaration>()
        assertEquals(2, functions.size)

        val index = attachPass.attach(chunk)

        val globalDoc = assertNotNull(index.getDocComment(functions[0]))
        assertEquals(
            listOf("fun(value: string): string"),
            globalDoc.tags.filterIsInstance<OverloadTagSyntax>().map { it.signatureText }
        )

        val methodDoc = assertNotNull(index.getDocComment(functions[1]))
        assertEquals(
            listOf("fun(self: table, value: string): string"),
            methodDoc.tags.filterIsInstance<OverloadTagSyntax>().map { it.signatureText }
        )
        assertEquals(0, index.orphanAttachments.size)
    }

    @Test
    fun attachesOverloadOnAssignmentStyleLocalFunctionExpression() {
        val chunk = parse(
            """
            ---@overload fun(msg: string): nil
            ---@param msg number
            ---@return nil
            local log = function(msg)
            end
            """.trimIndent()
        )

        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(local))

        assertEquals(
            listOf("fun(msg: string): nil"),
            doc.tags.filterIsInstance<OverloadTagSyntax>().map { it.signatureText }
        )
        assertEquals(0, index.orphanAttachments.size)
    }

    // -------------------------------------------------------------------------
    // Resolved overload / call surface
    // -------------------------------------------------------------------------

    @Test
    fun resolvesOverloadTagsOntoFunctionDocumentationAndCallSelection() {
        val resolved = bindAndResolve(
            """
            ---@overload fun(value: string): string
            ---@param value number
            ---@return number
            local function normalize(value)
                return value
            end
            """.trimIndent()
        )

        val declaration = resolved.declarationIndex.declarations.single {
            it.kind == DeclarationKind.FUNCTION && it.name == "normalize"
        }

        val overloadTypes = assertNotNull(declaration.documentation?.resolvedOverloadTypes)
        assertEquals(1, overloadTypes.size)
        assertSame(PrimitiveType.STRING, overloadTypes.single().returnType)
        assertEquals(1, overloadTypes.single().parameters.size)
        assertSame(PrimitiveType.STRING, overloadTypes.single().parameters.single().type)

        // Primary declared type remains the param/return contract (not the overload alone).
        val primary = assertIs<FunctionType>(declaration.declaredType)
        assertSame(PrimitiveType.NUMBER, primary.returnType)

        val checker = CallChecker(resolved)
        val scopeId = resolved.scopeGraph.rootScope.id
        val numberCall = checker.checkCall(primary, listOf(PrimitiveType.NUMBER), scopeId, declaration)
        val stringCall = checker.checkCall(primary, listOf(PrimitiveType.STRING), scopeId, declaration)

        assertSame(PrimitiveType.NUMBER, numberCall.returnType)
        assertSame(PrimitiveType.STRING, stringCall.returnType)

        val surface = checker.resolveCallable(primary, scopeId, declaration)
        assertTrue(surface.isSuccess)
        assertEquals(2, surface.signatures.size)
        assertTrue(surface.signatures.any { it.returnType == PrimitiveType.NUMBER })
        assertTrue(surface.signatures.any { it.returnType == PrimitiveType.STRING })
    }

    @Test
    fun resolvesMultipleOverloadsPreserveOrderAndArityVariants() {
        val resolved = bindAndResolve(
            """
            ---@overload fun(): nil
            ---@overload fun(value: string): string
            ---@overload fun(value: string, count: number): string
            ---@param value number
            ---@return number
            local function pack(value)
                return value
            end
            """.trimIndent()
        )

        val declaration = resolved.declarationIndex.declarations.single {
            it.kind == DeclarationKind.FUNCTION && it.name == "pack"
        }
        val overloads = assertNotNull(declaration.documentation?.resolvedOverloadTypes)
        assertEquals(3, overloads.size)
        assertEquals(listOf(0, 1, 2), overloads.map { it.parameters.size })
        assertEquals(
            listOf(PrimitiveType.NIL, PrimitiveType.STRING, PrimitiveType.STRING),
            overloads.map { it.returnType }
        )

        val checker = CallChecker(resolved)
        val surface = checker.resolveCallable(
            declaration.declaredType!!,
            resolved.scopeGraph.rootScope.id,
            declaration
        )
        // primary + 3 overloads
        assertEquals(4, surface.signatures.size)
    }

    @Test
    fun docOnlyClassMethodOverloadSurfacesAsOverloadedFunctionType() {
        val resolved = bindAndResolve(
            """
            ---@class Widget
            ---@method Widget:pick(): number
            ---@overload fun(self: Widget, value: string): string
            """.trimIndent()
        )

        val method = resolved.declarationIndex.declarations.single {
            it.kind == DeclarationKind.METHOD && it.name == "pick"
        }
        val methodType = assertIs<OverloadedFunctionType>(method.declaredType)
        assertEquals(2, methodType.callSignatures.size)
        assertEquals(1, method.documentation?.resolvedOverloadTypes?.size)
        assertSame(PrimitiveType.STRING, method.documentation?.resolvedOverloadTypes?.single()?.returnType)
        assertTrue(methodType.callSignatures.any { it.returnType == PrimitiveType.NUMBER })
        assertTrue(methodType.callSignatures.any { it.returnType == PrimitiveType.STRING })
    }

    // -------------------------------------------------------------------------
    // Semantic model signature-help surface
    // -------------------------------------------------------------------------

    @Test
    fun signatureHelpSurfacesPrimaryAndDocOverloadAtCallSite() {
        val source =
            """
            ---@overload fun(value: string): string
            ---@param value number
            ---@return number
            local function normalize(value)
                return value
            end
            local a = normalize(1)
            local b = normalize("x")
            """.trimIndent()

        val model = pipeline.analyze(luaParser.parse(source)).model

        val numberHelp = assertNotNull(model.getSignatureHelpAt(positionOf(source, "1)")))
        val stringHelp = assertNotNull(model.getSignatureHelpAt(positionOf(source, "\"x\"")))

        assertTrue(
            numberHelp.signatures.size >= 2,
            "Expected primary + @overload signature help; got ${numberHelp.signatures.size}: ${numberHelp.signatures.map { it.label }}"
        )
        assertTrue(
            numberHelp.signatures.any { it.label.contains("number") },
            "Primary number branch missing: ${numberHelp.signatures.map { it.label }}"
        )
        assertTrue(
            numberHelp.signatures.any { it.label.contains("string") },
            "Doc string overload missing: ${numberHelp.signatures.map { it.label }}"
        )
        assertEquals(numberHelp.signatures.size, stringHelp.signatures.size)
        assertTrue(numberHelp.activeSignature in numberHelp.signatures.indices)
        assertTrue(stringHelp.activeSignature in stringHelp.signatures.indices)

        // TASK-561: argument types must rank activeSignature to the matching overload
        // (not always primary index 0). number arg -> number primary; string arg -> string overload.
        val numberIndex = numberHelp.signatures.indexOfFirst {
            it.label.contains("number") && !it.label.contains("string")
        }.takeIf { it >= 0 }
            ?: numberHelp.signatures.indexOfFirst { it.label.contains("number") }
        val stringIndex = stringHelp.signatures.indexOfFirst {
            it.label.contains("string") && !it.label.contains("number")
        }.takeIf { it >= 0 }
            ?: stringHelp.signatures.indexOfFirst { it.label.contains("string") }
        assertTrue(numberIndex >= 0, "number signature missing: ${numberHelp.signatures.map { it.label }}")
        assertTrue(stringIndex >= 0, "string signature missing: ${stringHelp.signatures.map { it.label }}")
        assertEquals(
            numberIndex,
            numberHelp.activeSignature,
            "normalize(1) should activate number signature; labels=${numberHelp.signatures.map { it.label }} active=${numberHelp.activeSignature}"
        )
        assertEquals(
            stringIndex,
            stringHelp.activeSignature,
            "normalize(\"x\") should activate string overload; labels=${stringHelp.signatures.map { it.label }} active=${stringHelp.activeSignature}"
        )
        assertTrue(
            numberHelp.activeSignature != stringHelp.activeSignature || numberIndex == stringIndex,
            "Discriminating args must not always leave activeSignature stuck at the same index"
        )
    }

    @Test
    fun signatureHelpWithoutOverloadKeepsSinglePrimarySurface() {
        val source =
            """
            ---@param value number
            ---@return number
            local function onlyPrimary(value)
                return value
            end
            local a = onlyPrimary(1)
            """.trimIndent()

        val model = pipeline.analyze(luaParser.parse(source)).model
        val help = assertNotNull(model.getSignatureHelpAt(positionOf(source, "1)")))

        assertEquals(
            1,
            help.signatures.size,
            "Missing @overload must not invent extra signatures: ${help.signatures.map { it.label }}"
        )
        assertTrue(help.signatures.single().label.contains("number"))
        assertEquals(0, help.activeSignature)
    }

    @Test
    fun signatureHelpMultiArityOverloadsExposeArityVariants() {
        val source =
            """
            ---@overload fun(): nil
            ---@overload fun(value: string, count: number): string
            ---@param value number
            ---@return number
            local function pack(value)
                return value
            end
            local a = pack(1)
            local b = pack()
            local c = pack("x", 2)
            """.trimIndent()

        val model = pipeline.analyze(luaParser.parse(source)).model
        val help = assertNotNull(model.getSignatureHelpAt(positionOf(source, "1)")))

        assertTrue(
            help.signatures.size >= 3,
            "Expected primary + multi-arity overloads; got ${help.signatures.size}: ${help.signatures.map { it.label }}"
        )
        val arities = help.signatures.map { it.parameters.size }.toSet()
        assertTrue(0 in arities || help.signatures.any { it.parameters.isEmpty() }, "0-arg overload missing: $arities / ${help.signatures.map { it.label }}")
        assertTrue(arities.any { it >= 2 }, "2-arg overload missing: $arities / ${help.signatures.map { it.label }}")

        // TASK-561: multi-arity sites hard-assert activeSignature selection by argument shape.
        val packEmptyStart = positionOf(source, "pack()")
        // column of 'p' + len("pack(") lands inside the empty argument list.
        val zeroArgHelp = assertNotNull(
            model.getSignatureHelpAt(Position(packEmptyStart.line, packEmptyStart.column + "pack(".length))
        )
        val twoArgHelp = assertNotNull(model.getSignatureHelpAt(positionOf(source, "\"x\"")))

        val oneArgIndex = help.signatures.indexOfFirst { sig ->
            sig.parameters.size == 1 && sig.label.contains("number")
        }.takeIf { it >= 0 } ?: help.signatures.indexOfFirst { it.parameters.size == 1 }
        val zeroArgIndex = zeroArgHelp.signatures.indexOfFirst { it.parameters.isEmpty() }
        val twoArgIndex = twoArgHelp.signatures.indexOfFirst { it.parameters.size >= 2 }

        assertTrue(oneArgIndex >= 0, "1-arg primary missing: ${help.signatures.map { it.label }}")
        assertTrue(zeroArgIndex >= 0, "0-arg overload missing in zeroArgHelp: ${zeroArgHelp.signatures.map { it.label }}")
        assertTrue(twoArgIndex >= 0, "2-arg overload missing in twoArgHelp: ${twoArgHelp.signatures.map { it.label }}")

        assertEquals(oneArgIndex, help.activeSignature, "pack(1) should select 1-arg number primary")
        assertEquals(zeroArgIndex, zeroArgHelp.activeSignature, "pack() should select 0-arg overload")
        assertEquals(twoArgIndex, twoArgHelp.activeSignature, "pack(\"x\", 2) should select 2-arg overload")
        assertTrue(
            help.signatures[help.activeSignature].parameters.isEmpty() ||
                help.activeParameter in help.signatures[help.activeSignature].parameters.indices
        )
        assertEquals(0, zeroArgHelp.activeParameter)
        assertTrue(
            twoArgHelp.activeParameter in twoArgHelp.signatures[twoArgHelp.activeSignature].parameters.indices
        )
    }

    @Test
    fun signatureHelpActiveSignatureRanksByArgumentTypesAcrossOverloads() {
        // Focused TASK-561 corpus: discriminating argument shapes must flip activeSignature.
        val source =
            """
            ---@overload fun(value: string): string
            ---@overload fun(value: boolean): boolean
            ---@param value number
            ---@return number
            local function coerce(value)
                return value
            end
            local n = coerce(1)
            local s = coerce("x")
            local b = coerce(true)
            """.trimIndent()

        val model = pipeline.analyze(luaParser.parse(source)).model
        val numberHelp = assertNotNull(model.getSignatureHelpAt(positionOf(source, "1)")))
        val stringHelp = assertNotNull(model.getSignatureHelpAt(positionOf(source, "\"x\"")))
        val boolHelp = assertNotNull(model.getSignatureHelpAt(positionOf(source, "true)")))

        assertTrue(numberHelp.signatures.size >= 3, numberHelp.signatures.map { it.label }.toString())

        fun indexOfLabel(help: io.github.dingyi222666.luaparser.semantic.api.SignatureHelp, token: String): Int {
            return help.signatures.indexOfFirst { it.label.contains(token) && !it.label.contains(",") }
                .takeIf { it >= 0 }
                ?: help.signatures.indexOfFirst { it.label.contains(token) }
        }

        val numberIndex = indexOfLabel(numberHelp, "number")
        val stringIndex = indexOfLabel(stringHelp, "string")
        val boolIndex = indexOfLabel(boolHelp, "boolean")
        assertTrue(numberIndex >= 0 && stringIndex >= 0 && boolIndex >= 0)

        assertEquals(numberIndex, numberHelp.activeSignature)
        assertEquals(stringIndex, stringHelp.activeSignature)
        assertEquals(boolIndex, boolHelp.activeSignature)
        assertEquals(
            setOf(numberIndex, stringIndex, boolIndex).size,
            3,
            "Expected three distinct active signatures for number/string/boolean args; got n=$numberIndex s=$stringIndex b=$boolIndex labels=${numberHelp.signatures.map { it.label }}"
        )
    }

    // -------------------------------------------------------------------------
    // Missing / malformed degradation
    // -------------------------------------------------------------------------

    @Test
    fun missingOverloadTagResolvesToEmptyOverloadList() {
        val resolved = bindAndResolve(
            """
            ---@param value number
            ---@return number
            local function plain(value)
                return value
            end
            """.trimIndent()
        )

        val declaration = resolved.declarationIndex.declarations.single {
            it.kind == DeclarationKind.FUNCTION && it.name == "plain"
        }

        val overloads = declaration.documentation?.resolvedOverloadTypes.orEmpty()
        assertTrue(overloads.isEmpty(), "Expected empty overload list, got $overloads")

        val checker = CallChecker(resolved)
        val surface = checker.resolveCallable(
            declaration.declaredType!!,
            resolved.scopeGraph.rootScope.id,
            declaration
        )
        assertEquals(1, surface.signatures.size)
        assertSame(PrimitiveType.NUMBER, surface.signatures.single().returnType)
    }

    @Test
    fun blankLineSeparatedOverloadBlockDoesNotAttachAcrossGap() {
        val chunk = parse(
            """
            ---@overload fun(value: string): string

            ---@param value number
            ---@return number
            local function onlyPrimary(value)
                return value
            end
            """.trimIndent()
        )

        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))

        assertTrue(doc.tags.none { it is OverloadTagSyntax })
        assertEquals(listOf("param", "return"), doc.tags.map { it.tagName })

        val orphan = index.orphanAttachments.single()
        val orphanDoc = assertNotNull(orphan.docComment)
        assertEquals(
            listOf("fun(value: string): string"),
            orphanDoc.tags.filterIsInstance<OverloadTagSyntax>().map { it.signatureText }
        )
    }

    @Test
    fun malformedOverloadTagsDoNotCrashAndDegradeEmptyResolvedList() {
        val source =
            """
            ---@overload
            ---@overload (value: string: number
            ---@overload not_a_function_type
            ---@overload fun(
            ---@param value number
            ---@return number
            local function weird(value)
                return value
            end
            local a = weird(1)
            """.trimIndent()

        // Attach must not throw.
        val chunk = parse(source)
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val index = attachPass.attach(chunk)
        val doc = assertNotNull(index.getDocComment(function))
        assertTrue(doc.tags.any { it is OverloadTagSyntax || it is UnknownTagSyntax || it.tagName == "overload" })

        // Resolution must not throw; unparsable overloads drop out.
        val resolved = bindAndResolve(source)
        val declaration = resolved.declarationIndex.declarations.single {
            it.kind == DeclarationKind.FUNCTION && it.name == "weird"
        }
        val overloads = declaration.documentation?.resolvedOverloadTypes.orEmpty()
        assertTrue(
            overloads.isEmpty(),
            "Malformed @overload must degrade to empty resolved list, got ${overloads.map { it.displayName }}"
        )

        // Model signature help still works on the primary contract.
        val model = pipeline.analyze(chunk).model
        val help = assertNotNull(model.getSignatureHelpAt(positionOf(source, "1)")))
        assertEquals(1, help.signatures.size)
        assertTrue(help.signatures.single().label.contains("number"))
    }

    @Test
    fun mixedValidAndMalformedOverloadsKeepOnlyParsableSignatures() {
        val source =
            """
            ---@overload fun(value: string): string
            ---@overload (broken
            ---@overload fun(value: boolean): boolean
            ---@param value number
            ---@return number
            local function mixed(value)
                return value
            end
            local a = mixed(1)
            """.trimIndent()

        val resolved = bindAndResolve(source)
        val declaration = resolved.declarationIndex.declarations.single {
            it.kind == DeclarationKind.FUNCTION && it.name == "mixed"
        }
        val overloads = assertNotNull(declaration.documentation?.resolvedOverloadTypes)
        assertEquals(2, overloads.size)
        assertEquals(
            listOf(PrimitiveType.STRING, PrimitiveType.BOOLEAN),
            overloads.map { it.returnType }
        )

        val model = pipeline.analyze(luaParser.parse(source)).model
        val help = assertNotNull(model.getSignatureHelpAt(positionOf(source, "1)")))
        // primary + 2 valid overloads
        assertTrue(
            help.signatures.size >= 3,
            "Expected primary + valid overloads only; got ${help.signatures.size}: ${help.signatures.map { it.label }}"
        )
        assertTrue(help.signatures.any { it.label.contains("string") })
        assertTrue(help.signatures.any { it.label.contains("boolean") })
        assertTrue(help.signatures.any { it.label.contains("number") })
    }

    @Test
    fun orphanOverloadOnlyBlockDoesNotCrashAttachOrResolution() {
        val source =
            """
            ---@overload fun(value: string): string

            local unrelated = 1
            """.trimIndent()

        val chunk = parse(source)
        val index = attachPass.attach(chunk)
        assertTrue(index.orphanAttachments.isNotEmpty())
        assertNull(
            chunk.body.statements.filterIsInstance<LocalStatement>().singleOrNull()
                ?.let { index.getDocComment(it) }
                ?.tags
                ?.filterIsInstance<OverloadTagSyntax>()
                ?.takeIf { it.isNotEmpty() }
        )

        // Full pipeline must remain quiet for the local (no function overload surface).
        val resolved = bindAndResolve(source)
        assertTrue(
            resolved.declarationIndex.declarations
                .filter { it.kind == DeclarationKind.FUNCTION }
                .all { it.documentation?.resolvedOverloadTypes.orEmpty().isEmpty() }
        )
        assertNotNull(pipeline.analyze(chunk).model)
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun parse(source: String) = luaParser.parse(source)

    private fun bindAndResolve(source: String) =
        parse(source).let { chunk ->
            val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
            TypeResolver().resolve(binder)
        }

    private fun positionOf(source: String, needle: String, occurrence: Int = 1): Position {
        var index = -1
        repeat(occurrence) {
            index = source.indexOf(needle, index + 1)
            check(index >= 0) { "Missing occurrence $occurrence of '$needle' in:\n$source" }
        }
        var line = 1
        var column = 1
        for (i in 0 until index) {
            if (source[i] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return Position(line, column)
    }
}
