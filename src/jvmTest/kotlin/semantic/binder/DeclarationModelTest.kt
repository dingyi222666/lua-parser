package semantic.binder

import io.github.dingyi222666.luaparser.semantic.binder.DeclarationId
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationDocumentation
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationNamespace
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOrigin
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationOwner
import io.github.dingyi222666.luaparser.semantic.binder.SymbolId
import io.github.dingyi222666.luaparser.semantic.binder.aliasDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.classDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.fieldDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.functionDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.localDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.methodDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.parameterDeclaration
import io.github.dingyi222666.luaparser.semantic.binder.typeParameterDeclaration
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CustomType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionParameter
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionParameterSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.FunctionTypeSyntax
import io.github.dingyi222666.luaparser.semantic.types.syntax.NamedTypeSyntax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class DeclarationModelTest {

    @Test
    fun declarationKindsExposeExpectedNamespaces() {
        assertEquals(DeclarationNamespace.VALUE, DeclarationKind.LOCAL.namespace)
        assertEquals(DeclarationNamespace.VALUE, DeclarationKind.FUNCTION.namespace)
        assertEquals(DeclarationNamespace.VALUE, DeclarationKind.PARAMETER.namespace)
        assertEquals(DeclarationNamespace.TYPE, DeclarationKind.CLASS.namespace)
        assertEquals(DeclarationNamespace.TYPE, DeclarationKind.TYPE_ALIAS.namespace)
        assertEquals(DeclarationNamespace.TYPE, DeclarationKind.TYPE_PARAMETER.namespace)
        assertEquals(DeclarationNamespace.MEMBER, DeclarationKind.FIELD.namespace)
        assertEquals(DeclarationNamespace.MEMBER, DeclarationKind.METHOD.namespace)
    }

    @Test
    fun instantiatesRepresentativeDeclarationShapes() {
        val local = localDeclaration(
            id = DeclarationId(1),
            name = "value",
            declaredType = PrimitiveType.STRING,
            symbolId = SymbolId(10)
        )
        val function = functionDeclaration(
            id = DeclarationId(2),
            name = "render",
            declaredType = FunctionType(
                parameters = listOf(FunctionParameter("input", PrimitiveType.STRING)),
                returnType = PrimitiveType.BOOLEAN
            )
        )
        val klass = classDeclaration(
            id = DeclarationId(3),
            name = "Widget",
            declaredType = ClassType("Widget")
        )
        val alias = aliasDeclaration(
            id = DeclarationId(4),
            name = "WidgetName",
            declaredType = PrimitiveType.STRING,
            origin = DeclarationOrigin.DOC_COMMENT
        )
        val parameter = parameterDeclaration(
            id = DeclarationId(5),
            name = "input",
            owner = DeclarationOwner.Declaration(function.id),
            declaredType = PrimitiveType.STRING
        )
        val field = fieldDeclaration(
            id = DeclarationId(6),
            name = "id",
            owner = DeclarationOwner.Declaration(klass.id),
            declaredType = PrimitiveType.NUMBER
        )
        val method = methodDeclaration(
            id = DeclarationId(7),
            name = "toString",
            owner = DeclarationOwner.Declaration(klass.id),
            declaredType = FunctionType(returnType = PrimitiveType.STRING)
        )
        val typeParameter = typeParameterDeclaration(
            id = DeclarationId(8),
            name = "T",
            owner = DeclarationOwner.Declaration(klass.id)
        )

        assertEquals(DeclarationKind.LOCAL, local.kind)
        assertEquals(DeclarationKind.FUNCTION, function.kind)
        assertEquals(DeclarationKind.CLASS, klass.kind)
        assertEquals(DeclarationKind.TYPE_ALIAS, alias.kind)
        assertEquals(DeclarationOwner.Declaration(function.id), parameter.owner)
        assertEquals(DeclarationOwner.Declaration(klass.id), field.owner)
        assertEquals(DeclarationOwner.Declaration(klass.id), method.owner)
        assertEquals(DeclarationOwner.Declaration(klass.id), typeParameter.owner)
    }

    @Test
    fun storesResolvedAndUnresolvedTypeSlots() {
        val declaredTypeSyntax = FunctionTypeSyntax(
            parameters = listOf(FunctionParameterSyntax(name = "value", type = NamedTypeSyntax("string"))),
            returnType = NamedTypeSyntax("Widget")
        )
        val declaredType = FunctionType(
            parameters = listOf(FunctionParameter("value", PrimitiveType.STRING)),
            returnType = ClassType("Widget")
        )

        val declaration = functionDeclaration(
            id = DeclarationId(1),
            name = "buildWidget",
            declaredTypeSyntax = declaredTypeSyntax,
            declaredType = declaredType
        )

        assertEquals(declaredTypeSyntax, declaration.declaredTypeSyntax)
        assertIs<FunctionType>(declaration.declaredType)
        assertEquals("Widget", declaration.declaredType?.let { (it as FunctionType).returnType.displayName })
    }

    @Test
    fun storesResolvedDocumentationPayload() {
        val documentation = DeclarationDocumentation(
            inlineTypeText = "Widget",
            resolvedInlineType = ClassType("Widget"),
            resolvedParameterTypes = mapOf("value" to PrimitiveType.STRING),
            resolvedReturnTypes = listOf(PrimitiveType.BOOLEAN, PrimitiveType.NIL),
            resolvedParentType = CustomType("Base")
        )

        val declaration = classDeclaration(
            id = DeclarationId(9),
            name = "Widget",
            documentation = documentation
        )

        assertEquals("Widget", declaration.documentation?.resolvedInlineType?.name)
        assertSame(PrimitiveType.STRING, declaration.documentation?.resolvedParameterTypes?.get("value"))
        assertEquals(listOf("boolean", "nil"), declaration.documentation?.resolvedReturnTypes?.map { it.name })
        assertEquals("Base", declaration.documentation?.resolvedParentType?.name)
    }
}
