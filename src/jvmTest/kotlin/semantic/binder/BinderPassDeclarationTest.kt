package semantic.binder

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AttributeIdentifier
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BinderPassDeclarationTest {

    private val parser = LuaParser()
    private val lua54Parser = LuaParser(LuaVersion.LUA_5_4)

    @Test
    fun bindsLocalGlobalAndBuiltinDeclarations() {
        val localChunk = parser.parse("local value = 1")
        val localResult = BinderPass().bind(localChunk, CommentAttachPass().attach(localChunk))
        assertEquals(1, localResult.declarationIndex.declarations.count { it.kind == DeclarationKind.LOCAL && it.name == "value" })

        val localFunctionChunk = parser.parse("local function render(a) end")
        val localFunctionResult = BinderPass().bind(localFunctionChunk, CommentAttachPass().attach(localFunctionChunk))
        assertEquals(1, localFunctionResult.declarationIndex.declarations.count { it.kind == DeclarationKind.FUNCTION && it.name == "render" })
        assertEquals(1, localFunctionResult.declarationIndex.declarations.count { it.kind == DeclarationKind.PARAMETER && it.name == "a" })

        val globalFunctionChunk = parser.parse("function render(a) end")
        val globalFunctionResult = BinderPass().bind(globalFunctionChunk, CommentAttachPass().attach(globalFunctionChunk))
        assertEquals(1, globalFunctionResult.declarationIndex.declarations.count { it.kind == DeclarationKind.GLOBAL && it.name == "render" })
        assertEquals(1, globalFunctionResult.declarationIndex.declarations.count { it.kind == DeclarationKind.PARAMETER && it.name == "a" })

        val printDeclaration = assertNotNull(globalFunctionResult.declarationIndex.declarations.find { it.name == "print" })
        assertEquals(DeclarationOrigin.BUILTIN, printDeclaration.origin)
    }

    @Test
    fun bindsMemberFunctionDeclarationsAndCreatesBodyScope() {
        val chunk = parser.parse("function obj:render() end")
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val function = chunk.body.statements.filterIsInstance<FunctionDeclaration>().single()

        assertEquals(1, result.declarationIndex.declarations.count { it.kind == DeclarationKind.METHOD && it.name == "render" && it.origin != DeclarationOrigin.BUILTIN })
        assertNotNull(result.scopeGraph.getScope(function.body!!))
    }

    @Test
    fun bareAssignmentIntroducesGlobalDeclaration() {
        // TASK-558: first bare free-name write invents AST GLOBAL (identifier-only range).
        val chunk = parser.parse("a = 1")
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))

        assertEquals(1, result.declarationIndex.declarations.count {
            it.name == "a" && it.kind == DeclarationKind.GLOBAL && it.origin == DeclarationOrigin.AST
        })
        assertTrue(result.declarationIndex.declarations.any { it.name == "print" })
    }

    @Test
    fun bindsAttributeLocalsAsLocalDeclarations() {
        val chunk = lua54Parser.parse("local x<const> = 1")
        val result = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val localStatement = chunk.body.statements.filterIsInstance<LocalStatement>().single()
        val attributeIdentifier = assertIs<AttributeIdentifier>(localStatement.init.single())
        val declaration = result.declarationIndex.declarations.single {
            it.name == "x" && it.kind == DeclarationKind.LOCAL
        }

        assertEquals("const", attributeIdentifier.attributeName)
        assertEquals(attributeIdentifier, declaration.anchorNode)
        assertEquals(DeclarationOwner.Lexical(chunk.body), declaration.owner)
        assertTrue(result.scopeGraph.getDeclarations(result.scopeGraph.rootScope.id).contains(declaration.id))
    }
}
