package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.parser.ast.node.Position
import semantic.support.WorkspaceSemanticHarness
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmInheritedSignatureTest {
    open class Parent {
        fun <T : Number> choose(vararg values: T): T = values.first()
        fun convert(value: String): String = value
        fun convert(value: Int): Int = value
        open fun create(): Parent = this
    }

    open class Left : Parent() {
        override fun create(): Left = this
    }

    class Right : Parent()
    class GrandLeft : Left()

    @Test
    fun demand_driven_public_member_surfaces_match_eager_reflection() {
        val eager = JvmClassModuleProvider()
        val deferred = JvmClassModuleProvider(deferPackageMemberReflection = true)
        val configuration = JvmWorkspaceConfiguration()
        for (name in listOf("java.lang.StringBuilder", "java.util.ArrayList", "java.util.HashMap",
            "android.app.Activity", "android.graphics.drawable.GradientDrawable")) {
            val expected = assertNotNull(eager.importedSymbol(name, configuration), name).moduleType
            val actual = assertNotNull(deferred.importedSymbol(name, configuration), name).moduleType
            val expectedType = assertIs<JavaInstanceType>(expected.fields["__class"]).classType
            val actualType = assertIs<JavaInstanceType>(actual.fields["__class"]).classType
            assertEquals(expectedType.allInstanceMembers(), actualType.allInstanceMembers(), "$name instance members")
            // Static fields/methods are exported directly from the selected class's
            // reflection surface. Ancestor JavaClassType nodes stay identity-only in
            // the deferred graph, so do not compare their internal flattened maps.
            assertEquals(expected.fields.keys, actual.fields.keys, "$name exported members")
            assertEquals(expectedType.constructors, actualType.constructors, "$name constructors")
        }
    }

    @Test
    fun demand_driven_members_keep_inherited_signatures_without_expanding_ancestor_members() {
        val provider = JvmClassModuleProvider(deferPackageMemberReflection = true)
        val type = instance(provider, GrandLeft::class.java)
        assertTrue(type.allInstanceMembers().keys.containsAll(listOf("choose", "convert", "create")))
        var ancestor = type.classType.superClass
        val names = mutableListOf<String>()
        while (ancestor != null) {
            names += ancestor.javaName.binaryName
            assertTrue(ancestor.instanceMembers.isEmpty(), "ancestor members must remain unexpanded")
            ancestor = ancestor.superClass
        }
        assertTrue(names.containsAll(listOf(Left::class.java.name, Parent::class.java.name, "java.lang.Object")))
        // Loading an ancestor as a query root must still give it its own full surface.
        assertTrue(instance(provider, Parent::class.java).allInstanceMembers().containsKey("choose"))
    }

    @Test
    fun demand_driven_nested_class_is_hydrated_when_its_members_are_queried() {
        val provider = JvmClassModuleProvider(deferPackageMemberReflection = true)
        val symbol = assertNotNull(provider.importedSymbol("java.util.Map", JvmWorkspaceConfiguration()))
        val entry = assertIs<ModuleType>(symbol.moduleType.fields["Entry"])
        assertTrue(assertIs<JavaInstanceType>(entry.fields["__class"]).classType.staticMembers.isEmpty())
        val harness = WorkspaceSemanticHarness.build("main.lua" to "import 'java.util.Map'\nMap.Entry.",
            engine = JvmWorkspaceEngine(classModuleProvider = provider, deferDependencyGlobals = true))
        val labels = harness.queries.completions(harness.path("main.lua"), Position(2, 11)).map { it.label }
        assertTrue(labels.containsAll(listOf("comparingByKey", "comparingByValue")), labels.toString())
    }

    @Test
    fun inherited_generic_varargs_and_overloads_preserve_metadata_across_subclasses() {
        for (order in listOf(listOf(Left::class.java, Right::class.java), listOf(Right::class.java, Left::class.java))) {
            val provider = JvmClassModuleProvider()
            for (clazz in order) {
                val type = instance(provider, clazz)
                val choose = type.allInstanceMembers().getValue("choose")
                val signature = assertIs<CallableType>(choose.valueType).callSignatures.single()
                assertEquals("T", assertIs<TypeParameterType>(signature.returnType).name)
                assertNotNull(signature.typeParameters.single().constraint)
                assertTrue(signature.parameters.single().vararg)
                val metadata = choose.signatureMetadata.single()
                assertTrue(metadata.isVarArgs)
                assertEquals(listOf("T[]"), metadata.genericParameterTypeNames)
                assertEquals("T", metadata.genericReturnTypeName)
                val convert = type.allInstanceMembers().getValue("convert")
                assertEquals(2, assertIs<CallableType>(convert.valueType).callSignatures.size)
                assertEquals(setOf("int", "java.lang.String"),
                    convert.signatureMetadata.map { it.genericReturnTypeName }.toSet())
            }
        }
    }

    @Test
    fun covariant_override_does_not_reuse_the_parent_return_type() {
        val provider = JvmClassModuleProvider()
        for (clazz in listOf(Right::class.java, Left::class.java)) {
            val create = instance(provider, clazz).allInstanceMembers().getValue("create")
            val signature = assertIs<CallableType>(create.valueType).callSignatures.single()
            val expected = if (clazz == Left::class.java) Left::class.java else Parent::class.java
            assertEquals(expected.name, assertIs<JavaInstanceType>(signature.returnType).javaName.binaryName)
            assertEquals(expected.name, create.signatureMetadata.single().genericReturnTypeName)
        }
    }

    @Test
    fun ancestor_loaded_at_different_depths_retains_its_full_root_surface() {
        val classes = listOf(GrandLeft::class.java, Right::class.java, Parent::class.java, Left::class.java)
        for (order in listOf(classes, classes.reversed())) {
            val provider = JvmClassModuleProvider()
            for (clazz in order) {
                val expected = instance(JvmClassModuleProvider(), clazz)
                val actual = instance(provider, clazz)
                assertEquals(expected.classType, actual.classType, clazz.name)
                assertTrue(actual.allInstanceMembers().keys.containsAll(listOf("choose", "convert", "create")))
            }
        }
    }

    private fun instance(provider: JvmClassModuleProvider, clazz: Class<*>): JavaInstanceType {
        val files = provider.providersFor(JvmWorkspaceConfiguration(classes = setOf(clazz.name)))
        val path = VirtualPath.of("__jvm__/classes/${clazz.name.replace('.', '/')}.lua")
        return assertIs(files.getValue(path).moduleExportSurface!!.moduleType.fields["__class"])
    }
}
