package semantic.types.resolve

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.DoStatement
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.binder.BinderDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.ScopeId
import io.github.dingyi222666.luaparser.semantic.binder.ScopeKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeScopeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REVIEW19 / TASK-237 corpus: TypeScopeGraphBuilder local-function scopes.
 *
 * Acceptance:
 * - Local functions create type scopes with parameter bindings visible inside the body only.
 * - Recursion names bind per documented policy (Lua `local function f` sugar:
 *   the function name is declared in the enclosing lexical scope and is therefore
 *   visible along the parent chain of the body, enabling recursive calls).
 *
 * TypeScopeGraph mirrors binder lexical scopes for type-namespace resolution.
 * Value PARAMETER / FUNCTION declarations remain on the binder ScopeGraph; type
 * scopes only surface CLASS / TYPE_ALIAS (lexical) and TYPE_PARAMETER (declaration
 * scopes). This corpus locks that split for local functions.
 *
 * Fixture note: avoid multi-identifier returns such as `return body, label` —
 * the current parser rejects those with IllegalStateException near eof. Use both
 * parameters via body locals / single returns instead.
 *
 * Test-only; verification deferred to
 * `jvmTest --tests semantic.types.resolve.TypeScopeGraphLocalFunctionTddTest`.
 */
class TypeScopeGraphLocalFunctionTddTest {

    private val parser = LuaParser()

    // -------------------------------------------------------------------------
    // Local functions create mirrored type scopes; parameters body-only
    // -------------------------------------------------------------------------

    @Test
    fun localFunctionCreatesLexicalTypeScopeForBody() {
        val source = """
            local function render(input)
                return input
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()

        val functionLexical = assertNotNull(result.scopeGraph.getScope(function.body!!))
        assertEquals(ScopeKind.FUNCTION, functionLexical.kind)

        val functionTypeScope = assertNotNull(result.typeScopeGraph.getLexicalScope(functionLexical.id))
        assertEquals(TypeScopeKind.LEXICAL, functionTypeScope.kind)
        assertEquals(functionLexical.id, functionTypeScope.lexicalScopeId)
        assertNull(functionTypeScope.ownerDeclarationId)

        val rootTypeScope = result.typeScopeGraph.rootScope
        assertEquals(rootTypeScope.id, functionTypeScope.parentId)
        assertEquals(
            result.typeScopeGraph.getLexicalScope(result.scopeGraph.rootScope.id)?.id,
            rootTypeScope.id
        )
    }

    @Test
    fun localFunctionParameterBindingsVisibleInsideBodyOnlyOnBinderScopeGraph() {
        // Body uses both parameters without multi-identifier return
        // (`return body, label` currently fails parse with IllegalStateException near eof).
        val source = """
            local outer = 1
            local function render(input, label)
                local body = input
                local tag = label
                return body
            end
            local after = outer
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val functionLexical = assertNotNull(result.scopeGraph.getScope(function.body!!))

        val input = nonBuiltin(result, name = "input", kind = DeclarationKind.PARAMETER)
        val label = nonBuiltin(result, name = "label", kind = DeclarationKind.PARAMETER)
        val bodyLocal = nonBuiltin(result, name = "body", kind = DeclarationKind.LOCAL)
        val tagLocal = nonBuiltin(result, name = "tag", kind = DeclarationKind.LOCAL)
        val functionDecl = nonBuiltin(result, name = "render", kind = DeclarationKind.FUNCTION)

        // Parameters live in the function body lexical scope only.
        val bodyDecls = result.scopeGraph.getDeclarations(functionLexical.id)
            .mapNotNull(result.declarationIndex::getDeclaration)
        assertTrue(bodyDecls.any { it.id == input.id })
        assertTrue(bodyDecls.any { it.id == label.id })
        assertTrue(bodyDecls.any { it.id == bodyLocal.id })
        assertTrue(bodyDecls.any { it.id == tagLocal.id })
        assertFalse(bodyDecls.any { it.id == functionDecl.id })

        val rootDecls = result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id)
            .mapNotNull(result.declarationIndex::getDeclaration)
            .filter { it.origin != DeclarationOrigin.BUILTIN }
        assertFalse(rootDecls.any { it.id == input.id })
        assertFalse(rootDecls.any { it.id == label.id })
        assertFalse(rootDecls.any { it.id == bodyLocal.id })
        assertFalse(rootDecls.any { it.id == tagLocal.id })
        assertTrue(rootDecls.any { it.id == functionDecl.id })

        // Owner linkage: parameters are owned by the function declaration.
        assertEquals(DeclarationOwner.Declaration(functionDecl.id), input.owner)
        assertEquals(DeclarationOwner.Declaration(functionDecl.id), label.owner)

        // Parameter name resolution along parent chain is body-only (not outer/after).
        assertEquals(input.id, resolveValueAlongParentChain(result, functionLexical.id, "input")?.id)
        assertEquals(label.id, resolveValueAlongParentChain(result, functionLexical.id, "label")?.id)
        assertNull(resolveValueAlongParentChain(result, result.scopeGraph.rootScope.id, "input"))
        val afterLocal = nonBuiltin(result, name = "after", kind = DeclarationKind.LOCAL)
        val afterScope = assertNotNull(result.scopeGraph.getDeclarationScope(afterLocal.id))
        assertNull(resolveValueAlongParentChain(result, afterScope.id, "input"))
        assertNull(resolveValueAlongParentChain(result, afterScope.id, "label"))
    }

    @Test
    fun localFunctionTypeScopesExcludeValueParametersAndFunctionName() {
        val source = """
            ---@alias Name string
            local function render(input)
                local value = input
                return value
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val functionLexical = assertNotNull(result.scopeGraph.getScope(function.body!!))
        val functionTypeScope = assertNotNull(result.typeScopeGraph.getLexicalScope(functionLexical.id))
        val rootTypeScope = result.typeScopeGraph.rootScope

        val input = nonBuiltin(result, name = "input", kind = DeclarationKind.PARAMETER)
        val functionDecl = nonBuiltin(result, name = "render", kind = DeclarationKind.FUNCTION)
        val valueLocal = nonBuiltin(result, name = "value", kind = DeclarationKind.LOCAL)
        val nameAlias = nonBuiltin(result, name = "Name", kind = DeclarationKind.TYPE_ALIAS)

        // Type scopes keep type-namespace decls only (CLASS / TYPE_ALIAS on lexical scopes).
        val bodyTypeDecls = result.typeScopeGraph.getDeclarations(functionTypeScope.id)
        assertTrue(bodyTypeDecls.none { it.id == input.id })
        assertTrue(bodyTypeDecls.none { it.id == functionDecl.id })
        assertTrue(bodyTypeDecls.none { it.id == valueLocal.id })
        assertTrue(bodyTypeDecls.none { it.kind == DeclarationKind.PARAMETER })
        assertTrue(bodyTypeDecls.none { it.kind == DeclarationKind.FUNCTION })
        assertTrue(bodyTypeDecls.none { it.kind == DeclarationKind.LOCAL })
        // Body lexical type scope is empty for this fixture (no body-attached type aliases).
        assertTrue(bodyTypeDecls.isEmpty())

        val rootTypeDecls = result.typeScopeGraph.getDeclarations(rootTypeScope.id)
        assertTrue(rootTypeDecls.any { it.id == nameAlias.id })
        assertTrue(rootTypeDecls.none { it.id == input.id })
        assertTrue(rootTypeDecls.none { it.id == functionDecl.id })
        assertTrue(rootTypeDecls.none { it.id == valueLocal.id })

        // Parameters themselves never get a declaration type scope.
        assertNull(result.typeScopeGraph.getDeclarationScope(input.id))
        // Non-generic local functions do not open a declaration type scope.
        assertNull(result.typeScopeGraph.getDeclarationScope(functionDecl.id))
    }

    @Test
    fun nestedLocalFunctionParametersDoNotLeakToOuterFunctionOrChunk() {
        val source = """
            local function outer(a)
                local function inner(b)
                    return a + b
                end
                return inner(a)
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val outerFn = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val innerFn = outerFn.body!!.statements.filterIsInstance<FunctionDeclaration>().single()
        val outerLexical = assertNotNull(result.scopeGraph.getScope(outerFn.body!!))
        val innerLexical = assertNotNull(result.scopeGraph.getScope(innerFn.body!!))
        val outerType = assertNotNull(result.typeScopeGraph.getLexicalScope(outerLexical.id))
        val innerType = assertNotNull(result.typeScopeGraph.getLexicalScope(innerLexical.id))

        assertEquals(outerType.id, innerType.parentId)
        assertEquals(result.typeScopeGraph.rootScope.id, outerType.parentId)

        val a = nonBuiltin(result, name = "a", kind = DeclarationKind.PARAMETER)
        val b = nonBuiltin(result, name = "b", kind = DeclarationKind.PARAMETER)
        val outerDecl = nonBuiltin(result, name = "outer", kind = DeclarationKind.FUNCTION)
        val innerDecl = nonBuiltin(result, name = "inner", kind = DeclarationKind.FUNCTION)

        assertTrue(result.scopeGraph.getDeclarations(outerLexical.id).contains(a.id))
        assertTrue(result.scopeGraph.getDeclarations(outerLexical.id).contains(innerDecl.id))
        assertFalse(result.scopeGraph.getDeclarations(outerLexical.id).contains(b.id))

        assertTrue(result.scopeGraph.getDeclarations(innerLexical.id).contains(b.id))
        assertFalse(result.scopeGraph.getDeclarations(innerLexical.id).contains(a.id))
        assertFalse(result.scopeGraph.getDeclarations(innerLexical.id).contains(outerDecl.id))

        // Inner body can see outer param via parent chain; outer body cannot see inner param.
        assertEquals(b.id, resolveValueAlongParentChain(result, innerLexical.id, "b")?.id)
        assertEquals(a.id, resolveValueAlongParentChain(result, innerLexical.id, "a")?.id)
        assertNull(resolveValueAlongParentChain(result, outerLexical.id, "b"))
        assertEquals(a.id, resolveValueAlongParentChain(result, outerLexical.id, "a")?.id)
        assertNull(resolveValueAlongParentChain(result, result.scopeGraph.rootScope.id, "a"))
        assertNull(resolveValueAlongParentChain(result, result.scopeGraph.rootScope.id, "b"))
    }

    @Test
    fun localFunctionInsideDoBlockParentsTypeScopeToBlockNotRoot() {
        val source = """
            do
                local function helper(x)
                    return x
                end
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)
        val doStatement = chunk.body.statements.filterIsInstance<DoStatement>().single()
        val function = doStatement.body.statements.filterIsInstance<FunctionDeclaration>().single()

        val blockLexical = assertNotNull(result.scopeGraph.getScope(doStatement.body))
        val functionLexical = assertNotNull(result.scopeGraph.getScope(function.body!!))
        val blockType = assertNotNull(result.typeScopeGraph.getLexicalScope(blockLexical.id))
        val functionType = assertNotNull(result.typeScopeGraph.getLexicalScope(functionLexical.id))

        assertEquals(blockType.id, functionType.parentId)
        assertEquals(result.typeScopeGraph.rootScope.id, blockType.parentId)
        assertNotEquals(result.typeScopeGraph.rootScope.id, functionType.parentId)

        val param = nonBuiltin(result, name = "x", kind = DeclarationKind.PARAMETER)
        val helper = nonBuiltin(result, name = "helper", kind = DeclarationKind.FUNCTION)

        assertTrue(result.scopeGraph.getDeclarations(functionLexical.id).contains(param.id))
        assertFalse(result.scopeGraph.getDeclarations(blockLexical.id).contains(param.id))
        assertTrue(result.scopeGraph.getDeclarations(blockLexical.id).contains(helper.id))
        assertFalse(result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(helper.id))
    }

    @Test
    fun localFunctionGenericTypeParametersLiveInDeclarationTypeScopeNotBodyLexical() {
        val source = """
            ---@generic T
            local function identity(value)
                return value
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val functionLexical = assertNotNull(result.scopeGraph.getScope(function.body!!))
        val functionTypeLexical = assertNotNull(result.typeScopeGraph.getLexicalScope(functionLexical.id))
        val functionDecl = nonBuiltin(result, name = "identity", kind = DeclarationKind.FUNCTION)
        val typeParam = nonBuiltin(result, name = "T", kind = DeclarationKind.TYPE_PARAMETER)

        val declarationTypeScope = assertNotNull(result.typeScopeGraph.getDeclarationScope(functionDecl.id))
        assertEquals(TypeScopeKind.DECLARATION, declarationTypeScope.kind)
        assertEquals(functionDecl.id, declarationTypeScope.ownerDeclarationId)
        assertNull(declarationTypeScope.lexicalScopeId)
        assertEquals(listOf(typeParam.id), declarationTypeScope.declarationIds)
        assertEquals(listOf("T"), result.typeScopeGraph.getDeclarations(declarationTypeScope.id).map { it.name })

        // Documented TypeScopeGraphBuilder policy: generic scope parents to the
        // declaration visibility lexical scope (enclosing), not the function body.
        val visibilityTypeScope = assertNotNull(result.typeScopeGraph.getParent(declarationTypeScope.id))
        assertEquals(TypeScopeKind.LEXICAL, visibilityTypeScope.kind)
        val functionDeclLexical = assertNotNull(result.scopeGraph.getDeclarationScope(functionDecl.id))
        assertEquals(functionDeclLexical.id, visibilityTypeScope.lexicalScopeId)
        assertNotEquals(functionTypeLexical.id, visibilityTypeScope.id)

        // Body lexical type scope does not list the type parameter; value param stays body-only.
        assertTrue(result.typeScopeGraph.getDeclarations(functionTypeLexical.id).none { it.id == typeParam.id })
        val valueParam = nonBuiltin(result, name = "value", kind = DeclarationKind.PARAMETER)
        assertTrue(result.scopeGraph.getDeclarations(functionLexical.id).contains(valueParam.id))
        assertNull(result.typeScopeGraph.getDeclarationScope(valueParam.id))
    }

    // -------------------------------------------------------------------------
    // Recursion names bind per documented policy
    // -------------------------------------------------------------------------

    @Test
    fun localFunctionRecursionNameBindsInEnclosingScopeNotBody() {
        // Lua policy: `local function fact(...)` is sugar for
        //   local fact; fact = function(...) ... end
        // so the name is declared in the enclosing lexical scope and remains
        // visible to the body via the parent chain (enabling recursion).
        val source = """
            local function fact(n)
                if n <= 1 then
                    return 1
                end
                return n * fact(n - 1)
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val functionLexical = assertNotNull(result.scopeGraph.getScope(function.body!!))
        val functionType = assertNotNull(result.typeScopeGraph.getLexicalScope(functionLexical.id))
        val rootLexical = result.scopeGraph.rootScope
        val rootType = result.typeScopeGraph.rootScope

        val fact = nonBuiltin(result, name = "fact", kind = DeclarationKind.FUNCTION)
        val n = nonBuiltin(result, name = "n", kind = DeclarationKind.PARAMETER)

        // Name lives on enclosing (chunk) scope; parameters on body scope.
        assertTrue(result.scopeGraph.getDeclarations(rootLexical.id).contains(fact.id))
        assertFalse(result.scopeGraph.getDeclarations(functionLexical.id).contains(fact.id))
        assertTrue(result.scopeGraph.getDeclarations(functionLexical.id).contains(n.id))
        assertFalse(result.scopeGraph.getDeclarations(rootLexical.id).contains(n.id))

        // Body resolves the recursion name through the parent chain.
        assertEquals(fact.id, resolveValueAlongParentChain(result, functionLexical.id, "fact")?.id)
        assertEquals(n.id, resolveValueAlongParentChain(result, functionLexical.id, "n")?.id)

        // Outside the body, the name is still visible (local in enclosing scope);
        // the parameter is not.
        assertEquals(fact.id, resolveValueAlongParentChain(result, rootLexical.id, "fact")?.id)
        assertNull(resolveValueAlongParentChain(result, rootLexical.id, "n"))

        // Type-scope mirror: function body type scope parents to root; neither
        // lists the value recursion name (type namespace isolation).
        assertEquals(rootType.id, functionType.parentId)
        assertTrue(result.typeScopeGraph.getDeclarations(functionType.id).none { it.id == fact.id })
        assertTrue(result.typeScopeGraph.getDeclarations(rootType.id).none { it.id == fact.id })
        assertNull(result.typeScopeGraph.getDeclarationScope(fact.id))
    }

    @Test
    fun nestedLocalFunctionRecursionNameBindsInOuterFunctionScope() {
        val source = """
            local function outer(x)
                local function walk(n)
                    if n <= 0 then
                        return x
                    end
                    return walk(n - 1)
                end
                return walk(x)
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)

        val outerFn = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val walkFn = outerFn.body!!.statements.filterIsInstance<FunctionDeclaration>().single()
        val outerLexical = assertNotNull(result.scopeGraph.getScope(outerFn.body!!))
        val walkLexical = assertNotNull(result.scopeGraph.getScope(walkFn.body!!))
        val outerType = assertNotNull(result.typeScopeGraph.getLexicalScope(outerLexical.id))
        val walkType = assertNotNull(result.typeScopeGraph.getLexicalScope(walkLexical.id))

        val outer = nonBuiltin(result, name = "outer", kind = DeclarationKind.FUNCTION)
        val walk = nonBuiltin(result, name = "walk", kind = DeclarationKind.FUNCTION)
        val n = nonBuiltin(result, name = "n", kind = DeclarationKind.PARAMETER)
        val x = nonBuiltin(result, name = "x", kind = DeclarationKind.PARAMETER)

        // `walk` recursion name is in the outer function body (enclosing) scope.
        assertTrue(result.scopeGraph.getDeclarations(outerLexical.id).contains(walk.id))
        assertFalse(result.scopeGraph.getDeclarations(walkLexical.id).contains(walk.id))
        assertTrue(result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(outer.id))
        assertFalse(result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(walk.id))

        assertEquals(walk.id, resolveValueAlongParentChain(result, walkLexical.id, "walk")?.id)
        assertEquals(n.id, resolveValueAlongParentChain(result, walkLexical.id, "n")?.id)
        assertEquals(x.id, resolveValueAlongParentChain(result, walkLexical.id, "x")?.id)
        assertEquals(walk.id, resolveValueAlongParentChain(result, outerLexical.id, "walk")?.id)
        assertNull(resolveValueAlongParentChain(result, outerLexical.id, "n"))

        // Type-scope hierarchy mirrors lexical nesting for the two function bodies.
        assertEquals(outerType.id, walkType.parentId)
        assertEquals(result.typeScopeGraph.rootScope.id, outerType.parentId)
    }

    @Test
    fun assignmentStyleLocalFunctionExpressionDoesNotBindRecursionNameAsFunctionDecl() {
        // Contrast policy: `local fact = function(n) ... end` is NOT the same as
        // `local function fact(n)`. The anonymous function has no FUNCTION
        // declaration; only the local binding exists in the enclosing scope.
        val source = """
            local fact = function(n)
                return n
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)
        val local = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val anonymous = local.variables.filterIsInstance<FunctionDeclaration>().single()
        val functionLexical = assertNotNull(result.scopeGraph.getScope(anonymous.body!!))
        val functionType = assertNotNull(result.typeScopeGraph.getLexicalScope(functionLexical.id))

        assertEquals(ScopeKind.FUNCTION, functionLexical.kind)
        assertEquals(result.typeScopeGraph.rootScope.id, functionType.parentId)

        assertEquals(
            0,
            result.declarationIndex.declarations.count {
                it.origin != DeclarationOrigin.BUILTIN && it.kind == DeclarationKind.FUNCTION
            }
        )
        val factLocal = nonBuiltin(result, name = "fact", kind = DeclarationKind.LOCAL)
        val n = nonBuiltin(result, name = "n", kind = DeclarationKind.PARAMETER)

        assertTrue(result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(factLocal.id))
        assertFalse(result.scopeGraph.getDeclarations(functionLexical.id).contains(factLocal.id))
        assertTrue(result.scopeGraph.getDeclarations(functionLexical.id).contains(n.id))

        // Local is still visible inside the body via parent chain (Lua assigns after
        // the local is declared; the binder models the local on the enclosing scope).
        assertEquals(factLocal.id, resolveValueAlongParentChain(result, functionLexical.id, "fact")?.id)
        assertNull(result.typeScopeGraph.getDeclarationScope(factLocal.id))
        assertNull(result.typeScopeGraph.getDeclarationScope(n.id))
    }

    @Test
    fun shadowedOuterLocalDoesNotReplaceLocalFunctionRecursionNameInsideBody() {
        val source = """
            local fact = 0
            local function fact(n)
                if n <= 0 then
                    return 1
                end
                return n * fact(n - 1)
            end
            """.trimIndent()
        val chunk = parser.parse(source)
        val result = bind(chunk)
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()
        val functionLexical = assertNotNull(result.scopeGraph.getScope(function.body!!))

        val factLocal = nonBuiltin(result, name = "fact", kind = DeclarationKind.LOCAL)
        val factFn = nonBuiltin(result, name = "fact", kind = DeclarationKind.FUNCTION)

        // Both names share the chunk scope; recursion policy for `local function fact`
        // is that the FUNCTION declaration is present on the enclosing scope and
        // resolvable from the body parent chain as kind FUNCTION.
        assertTrue(result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(factLocal.id))
        assertTrue(result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(factFn.id))
        assertFalse(result.scopeGraph.getDeclarations(functionLexical.id).contains(factFn.id))

        assertEquals(
            factFn.id,
            resolveValueAlongParentChainPreferring(
                result,
                functionLexical.id,
                "fact",
                preferredKind = DeclarationKind.FUNCTION
            )?.id
        )
        assertNotEquals(factLocal.kind, DeclarationKind.FUNCTION)
        assertEquals(DeclarationKind.FUNCTION, factFn.kind)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun bind(chunk: ChunkNode): BinderPassResult =
        BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

    private fun nonBuiltin(
        result: BinderPassResult,
        name: String,
        kind: DeclarationKind
    ): BinderDeclaration =
        result.declarationIndex.declarations.single {
            it.name == name && it.kind == kind && it.origin != DeclarationOrigin.BUILTIN
        }

    private fun resolveValueAlongParentChain(
        result: BinderPassResult,
        startScopeId: ScopeId,
        name: String
    ): BinderDeclaration? {
        var current = result.scopeGraph.getScope(startScopeId)
        while (current != null) {
            val match = result.scopeGraph.getDeclarations(current.id)
                .mapNotNull(result.declarationIndex::getDeclaration)
                .lastOrNull { it.name == name && it.origin != DeclarationOrigin.BUILTIN }
            if (match != null) {
                return match
            }
            current = current.parentId?.let(result.scopeGraph::getScope)
        }
        return null
    }

    private fun resolveValueAlongParentChainPreferring(
        result: BinderPassResult,
        startScopeId: ScopeId,
        name: String,
        preferredKind: DeclarationKind
    ): BinderDeclaration? {
        var current = result.scopeGraph.getScope(startScopeId)
        while (current != null) {
            val matches = result.scopeGraph.getDeclarations(current.id)
                .mapNotNull(result.declarationIndex::getDeclaration)
                .filter { it.name == name && it.origin != DeclarationOrigin.BUILTIN }
            matches.lastOrNull { it.kind == preferredKind }?.let { return it }
            matches.lastOrNull()?.let { return it }
            current = current.parentId?.let(result.scopeGraph::getScope)
        }
        return null
    }
}
