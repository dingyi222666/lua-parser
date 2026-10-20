package semantic.checker

import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.ast.node.ConstantNode
import io.github.dingyi222666.luaparser.semantic.binder.BinderPass
import io.github.dingyi222666.luaparser.semantic.binder.DeclarationKind
import io.github.dingyi222666.luaparser.semantic.comments.CommentAttachPass
import io.github.dingyi222666.luaparser.semantic.checker.MemberAccessKind
import io.github.dingyi222666.luaparser.semantic.checker.MemberFailureReason
import io.github.dingyi222666.luaparser.semantic.checker.MemberResolver
import io.github.dingyi222666.luaparser.semantic.types.model.AppliedType
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.AliasType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.IntersectionType
import io.github.dingyi222666.luaparser.semantic.types.model.NeverType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.TableType
import io.github.dingyi222666.luaparser.semantic.types.model.TupleType
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.model.UnionType
import io.github.dingyi222666.luaparser.semantic.types.resolve.TypeResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class MemberResolverTest {

    private val parser = LuaParser()

    @Test
    fun resolvesTableFieldAndCallableFieldMethodSurfaces() {
        val harness = resolver("")
        val baseType = TableType(
            fields = mapOf("name" to PrimitiveType.STRING, "run" to FunctionType(returnType = PrimitiveType.BOOLEAN)),
            methods = mapOf("push" to FunctionType(returnType = PrimitiveType.NIL))
        )

        val field = harness.resolver.resolveMember(baseType, "name", preferMethod = false, lexicalScopeId = harness.scopeId)
        val callableField = harness.resolver.resolveMember(baseType, "run", preferMethod = true, lexicalScopeId = harness.scopeId)
        val method = harness.resolver.resolveMember(baseType, "push", preferMethod = true, lexicalScopeId = harness.scopeId)

        assertSame(PrimitiveType.STRING, field.type)
        assertEquals(MemberAccessKind.FIELD, field.accessKind)
        assertIs<FunctionType>(callableField.type)
        assertEquals(MemberAccessKind.FIELD, callableField.accessKind)
        assertEquals(MemberAccessKind.METHOD, method.accessKind)
    }

    @Test
    fun resolvesClassFieldMethodInheritedAliasAppliedAndConstrainedMembers() {
        val harness = resolver(
            """
            ---@class Named
            ---@field name string
            ---@class Base<T>
            ---@field parentValue T
            ---@class Box<T>: Base<T>
            ---@field value T
            ---@method Box:get(): T
            ---@alias UserBox Box<string>
            ---@alias Pair<T> { left: T, right: T }

            ---@generic T: Named
            local function read(item)
                return item
            end
            """.trimIndent()
        )

        val boxValue = harness.resolver.resolveMember(
            AppliedType("Box", listOf(PrimitiveType.STRING)),
            "value",
            preferMethod = false,
            lexicalScopeId = harness.scopeId
        )
        val inherited = harness.resolver.resolveMember(
            AppliedType("Box", listOf(PrimitiveType.STRING)),
            "parentValue",
            preferMethod = false,
            lexicalScopeId = harness.scopeId
        )
        val pairLeft = harness.resolver.resolveMember(
            AppliedType("Pair", listOf(PrimitiveType.NUMBER)),
            "left",
            preferMethod = false,
            lexicalScopeId = harness.scopeId
        )
        val aliasWrapped = harness.resolver.resolveMember(
            alias(harness, "UserBox"),
            "value",
            preferMethod = false,
            lexicalScopeId = harness.scopeId
        )
        val constrained = harness.resolver.resolveMember(
            constrainedTypeParameter(harness, "T"),
            "name",
            preferMethod = false,
            lexicalScopeId = harness.scopeId
        )

        assertSame(PrimitiveType.STRING, boxValue.type)
        assertSame(PrimitiveType.STRING, inherited.type)
        assertSame(PrimitiveType.NUMBER, pairLeft.type)
        assertSame(PrimitiveType.STRING, aliasWrapped.type)
        assertSame(PrimitiveType.STRING, constrained.type)
    }

    @Test
    fun unionRequiresMemberOnEveryBranchAndIntersectionCombinesSurface() {
        val harness = resolver("")
        val union = UnionType(linkedSetOf(TableType(fields = mapOf("value" to PrimitiveType.STRING)), TableType(fields = mapOf("value" to PrimitiveType.NUMBER))))
        val missing = UnionType(linkedSetOf(TableType(fields = mapOf("value" to PrimitiveType.STRING)), TableType(fields = emptyMap())))
        val intersection = IntersectionType(linkedSetOf(TableType(fields = mapOf("left" to PrimitiveType.STRING)), TableType(fields = mapOf("left" to PrimitiveType.NUMBER))))

        val unionResult = harness.resolver.resolveMember(union, "value", false, harness.scopeId)
        val missingResult = harness.resolver.resolveMember(missing, "value", false, harness.scopeId)
        val intersectionResult = harness.resolver.resolveMember(intersection, "left", false, harness.scopeId)

        assertEquals(setOf(PrimitiveType.STRING, PrimitiveType.NUMBER), assertIs<UnionType>(unionResult.type).types)
        assertEquals(MemberFailureReason.MISSING_MEMBER, missingResult.failureReason)
        assertSame(NeverType, intersectionResult.type)
    }

    @Test
    fun resolvesIndexAccessAcrossTableArrayTupleAndClass() {
        val harness = resolver(
            """
            ---@class Item
            ---@field name string
            ---@method Item:getName(): string
            """.trimIndent()
        )
        val table = TableType(
            fields = mapOf("name" to PrimitiveType.STRING),
            indexSignature = TableType.IndexSignature(PrimitiveType.STRING, PrimitiveType.NUMBER)
        )

        val tableIndex = harness.resolver.resolveIndex(table, stringNode("name"), PrimitiveType.STRING, harness.scopeId)
        val arrayIndex = harness.resolver.resolveIndex(ArrayType(PrimitiveType.BOOLEAN), intNode(1), PrimitiveType.NUMBER, harness.scopeId)
        val invalidArrayIndex = harness.resolver.resolveIndex(ArrayType(PrimitiveType.BOOLEAN), stringNode("x"), PrimitiveType.STRING, harness.scopeId)
        val tupleIndex = harness.resolver.resolveIndex(TupleType(listOf(PrimitiveType.STRING, PrimitiveType.NUMBER)), intNode(2), PrimitiveType.NUMBER, harness.scopeId)
        val classIndex = harness.resolver.resolveIndex(classType(harness, "Item"), stringNode("getName"), PrimitiveType.STRING, harness.scopeId)

        assertSame(PrimitiveType.STRING, tableIndex.type)
        assertEquals(MemberAccessKind.FIELD, tableIndex.accessKind)
        assertSame(PrimitiveType.BOOLEAN, arrayIndex.type)
        assertEquals(MemberFailureReason.INVALID_INDEX_TYPE, invalidArrayIndex.failureReason)
        assertSame(PrimitiveType.NUMBER, tupleIndex.type)
        assertIs<FunctionType>(classIndex.type)
    }

    @Test
    fun resolvesInheritedGenericFieldThroughAppliedSubclass() {
        val harness = resolver(
            """
            ---@class Base<T>
            ---@field value T
            ---@class Box<T>: Base<T>
            """.trimIndent()
        )

        val result = harness.resolver.resolveMember(
            AppliedType("Box", listOf(PrimitiveType.STRING)),
            "value",
            preferMethod = false,
            lexicalScopeId = harness.scopeId
        )

        assertSame(PrimitiveType.STRING, result.type)
    }

    @Test
    fun resolvesInheritedGenericMethodReturnThroughAppliedSubclass() {
        val harness = resolver(
            """
            ---@class Base<T>
            ---@method Base:get(): T
            ---@class Box<T>: Base<T>
            """.trimIndent()
        )

        val result = harness.resolver.resolveMember(
            AppliedType("Box", listOf(PrimitiveType.STRING)),
            "get",
            preferMethod = true,
            lexicalScopeId = harness.scopeId
        )

        assertSame(PrimitiveType.STRING, (assertIs<FunctionType>(result.type)).returnType)
    }

    @Test
    fun resolvesInheritedMembersThroughAliasToAppliedSubclass() {
        val harness = resolver(
            """
            ---@class Base<T>
            ---@field value T
            ---@class Box<T>: Base<T>
            ---@alias StringBox Box<string>
            """.trimIndent()
        )

        val result = harness.resolver.resolveMember(
            alias(harness, "StringBox"),
            "value",
            preferMethod = false,
            lexicalScopeId = harness.scopeId
        )

        assertSame(PrimitiveType.STRING, result.type)
    }

    @Test
    fun resolvesOverloadedMethodMemberAsCallableSurface() {
        val harness = resolver(
            """
            ---@class Widget
            ---@method Widget:pick(): number
            ---@overload fun(self: Widget, value: string): string
            """.trimIndent()
        )

        val result = harness.resolver.resolveMember(
            classType(harness, "Widget"),
            "pick",
            preferMethod = true,
            lexicalScopeId = harness.scopeId
        )

        val overloaded = assertIs<io.github.dingyi222666.luaparser.semantic.types.model.OverloadedFunctionType>(result.type)
        assertEquals(2, overloaded.callSignatures.size)
    }

    private fun resolver(source: String): Harness {
        val chunk = parser.parse(source)
        val binder = BinderPass().bind(chunk, CommentAttachPass().attach(chunk))
        val resolved = TypeResolver().resolve(binder)
        return Harness(
            resolver = MemberResolver(resolved),
            declarations = resolved,
            scopeId = resolved.scopeGraph.rootScope.id
        )
    }

    private fun alias(harness: Harness, name: String): AliasType = assertIs<AliasType>(
        harness.declarations.declarationIndex.declarations.single { it.kind == DeclarationKind.TYPE_ALIAS && it.name == name }.declaredType
    )

    private fun classType(harness: Harness, name: String): ClassType = assertIs<ClassType>(
        harness.declarations.declarationIndex.declarations.single { it.kind == DeclarationKind.CLASS && it.name == name }.declaredType
    )

    private fun constrainedTypeParameter(harness: Harness, name: String): TypeParameterType = assertIs<TypeParameterType>(
        harness.declarations.declarationIndex.declarations.single {
            it.kind == DeclarationKind.TYPE_PARAMETER &&
                it.name == name &&
                (it.declaredType as? TypeParameterType)?.constraint?.name == "Named"
        }.declaredType
    )

    private fun stringNode(value: String) = ConstantNode(ConstantNode.TYPE.STRING, "\"$value\"")
    private fun intNode(value: Int) = ConstantNode(ConstantNode.TYPE.INTERGER, value)

    private data class Harness(
        val resolver: MemberResolver,
        val declarations: io.github.dingyi222666.luaparser.semantic.binder.BinderPassResult,
        val scopeId: io.github.dingyi222666.luaparser.semantic.binder.ScopeId
    )
}
