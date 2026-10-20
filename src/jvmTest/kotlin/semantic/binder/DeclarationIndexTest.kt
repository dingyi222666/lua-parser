package semantic.binder

import io.github.dingyi222666.luaparser.parser.ast.node.ASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.BaseASTNode
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import io.github.dingyi222666.luaparser.parser.ast.node.Range
import io.github.dingyi222666.luaparser.semantic.binder.BinderSymbol
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationIndex
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.SymbolId
import io.github.dingyi222666.luaparser.semantic.binder.classDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.fieldDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.functionDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.localDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.parameterDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DeclarationIndexTest {

    @Test
    fun resolvesSymbolsDeclarationsAndOwnershipLookups() {
        val lexicalNode = TestNode()
        val localNodeA = TestNode()
        val localNodeB = TestNode()
        val functionNode = TestNode()

        val firstLocal = localDeclaration(
            id = DeclarationId(1),
            name = "value",
            owner = DeclarationOwner.Lexical(lexicalNode),
            anchorNode = localNodeA,
            symbolId = SymbolId(100)
        )
        val secondLocal = localDeclaration(
            id = DeclarationId(2),
            name = "value",
            owner = DeclarationOwner.Lexical(lexicalNode),
            anchorNode = localNodeB,
            symbolId = SymbolId(100),
            origin = DeclarationOrigin.DOC_COMMENT
        )
        val function = functionDeclaration(
            id = DeclarationId(3),
            name = "value",
            owner = DeclarationOwner.Root,
            anchorNode = functionNode,
            symbolId = SymbolId(101)
        )
        val parameter = parameterDeclaration(
            id = DeclarationId(4),
            name = "arg",
            owner = DeclarationOwner.Declaration(function.id)
        )
        val klass = classDeclaration(id = DeclarationId(5), name = "Widget", symbolId = SymbolId(102))
        val field = fieldDeclaration(
            id = DeclarationId(6),
            name = "field",
            owner = DeclarationOwner.Declaration(klass.id)
        )

        val valueSymbol = BinderSymbol(
            id = SymbolId(100),
            name = "value",
            namespace = DeclarationNamespace.VALUE,
            declarationIds = listOf(firstLocal.id, secondLocal.id),
            primaryDeclarationId = firstLocal.id
        )
        val functionSymbol = BinderSymbol(
            id = SymbolId(101),
            name = "value",
            namespace = DeclarationNamespace.VALUE,
            declarationIds = listOf(function.id)
        )
        val typeSymbol = BinderSymbol(
            id = SymbolId(102),
            name = "Widget",
            namespace = DeclarationNamespace.TYPE,
            declarationIds = listOf(klass.id)
        )

        val index = DeclarationIndex(
            declarations = listOf(firstLocal, secondLocal, function, parameter, klass, field),
            symbols = listOf(valueSymbol, functionSymbol, typeSymbol)
        )

        assertEquals(firstLocal, index.getDeclaration(firstLocal.id))
        assertEquals(valueSymbol, index.getSymbol(valueSymbol.id))
        assertEquals(listOf(firstLocal, secondLocal), index.getDeclarations(valueSymbol.id))
        assertEquals(firstLocal, index.getPrimaryDeclaration(valueSymbol.id))
        assertEquals(listOf(function), index.getDeclarations(functionNode))
        assertEquals(listOf(firstLocal, secondLocal), index.getOwnedDeclarations(DeclarationOwner.Lexical(lexicalNode)))
        assertEquals(listOf(parameter), index.getOwnedDeclarations(DeclarationOwner.Declaration(function.id)))
        assertEquals(listOf(field), index.getOwnedDeclarations(DeclarationOwner.Declaration(klass.id)))
        assertEquals(listOf(valueSymbol, functionSymbol), index.getSymbols("value"))
        assertEquals(listOf(typeSymbol), index.getSymbols("Widget", DeclarationNamespace.TYPE))
        assertEquals(emptyList(), index.getSymbols("Widget", DeclarationNamespace.VALUE))
        assertNull(index.getPrimaryDeclaration(SymbolId(999)))
    }

    @Test
    fun rejectsMissingDeclarationReferencesFromSymbols() {
        val symbol = BinderSymbol(
            id = SymbolId(1),
            name = "value",
            namespace = DeclarationNamespace.VALUE,
            declarationIds = listOf(DeclarationId(999))
        )

        assertFailsWith<IllegalArgumentException> {
            DeclarationIndex(emptyList(), listOf(symbol))
        }
    }

    @Test
    fun rejectsMissingSymbolReferencesFromDeclarations() {
        val declaration = localDeclaration(
            id = DeclarationId(1),
            name = "value",
            symbolId = SymbolId(999)
        )

        assertFailsWith<IllegalArgumentException> {
            DeclarationIndex(listOf(declaration), emptyList())
        }
    }

    @Test
    fun rejectsSymbolReferencesToDeclarationsWithoutMatchingBackReference() {
        val declaration = localDeclaration(
            id = DeclarationId(1),
            name = "value"
        )
        val symbol = BinderSymbol(
            id = SymbolId(1),
            name = "value",
            namespace = DeclarationNamespace.VALUE,
            declarationIds = listOf(declaration.id)
        )

        assertFailsWith<IllegalArgumentException> {
            DeclarationIndex(listOf(declaration), listOf(symbol))
        }
    }

    @Test
    fun rejectsMissingOwnerDeclarations() {
        val declaration = parameterDeclaration(
            id = DeclarationId(1),
            name = "value",
            owner = DeclarationOwner.Declaration(DeclarationId(999))
        )

        assertFailsWith<IllegalArgumentException> {
            DeclarationIndex(listOf(declaration), emptyList())
        }
    }

    @Test
    fun rejectsPrimaryDeclarationOutsideSymbolList() {
        val declaration = localDeclaration(id = DeclarationId(1), name = "value")

        assertFailsWith<IllegalArgumentException> {
            BinderSymbol(
                id = SymbolId(1),
                name = "value",
                namespace = DeclarationNamespace.VALUE,
                declarationIds = listOf(declaration.id),
                primaryDeclarationId = DeclarationId(2)
            )
        }
    }

    @Test
    fun rejectsEmptyDeclarationIdsOnSymbols() {
        assertFailsWith<IllegalArgumentException> {
            BinderSymbol(
                id = SymbolId(1),
                name = "value",
                namespace = DeclarationNamespace.VALUE,
                declarationIds = emptyList()
            )
        }
    }

    @Test
    fun rejectsNamespaceMismatchesBetweenSymbolsAndDeclarations() {
        val declaration = classDeclaration(
            id = DeclarationId(1),
            name = "Widget",
            symbolId = SymbolId(1)
        )
        val symbol = BinderSymbol(
            id = SymbolId(1),
            name = "Widget",
            namespace = DeclarationNamespace.VALUE,
            declarationIds = listOf(declaration.id)
        )

        assertFailsWith<IllegalArgumentException> {
            DeclarationIndex(listOf(declaration), listOf(symbol))
        }
    }

    private class TestNode : ASTNode() {
        init {
            range = Range(Position(1, 1), Position(1, 2))
            parent = this
        }

        override fun <T> accept(visitor: io.github.dingyi222666.luaparser.parser.ast.visitor.ASTVisitor<T>, value: T) {
        }

        override fun clone(): BaseASTNode = TestNode().also { node ->
            node.range = range
            node.bad = bad
        }
    }
}
