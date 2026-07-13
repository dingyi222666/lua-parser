package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.semantic.api.SymbolKind
import io.github.dingyi222666.luaparser.semantic.types.model.ArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import io.github.dingyi222666.luaparser.semantic.types.model.Type
import io.github.dingyi222666.luaparser.semantic.types.model.TypeParameterType
import io.github.dingyi222666.luaparser.semantic.types.resolve.isAssignableFrom
import io.github.dingyi222666.luaparser.semantic.workspace.ModuleExportSurface
import io.github.dingyi222666.luaparser.semantic.workspace.VirtualPath
import io.github.dingyi222666.luaparser.semantic.workspace.WorkspaceSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class JvmReflectionModelTddTest {
    private val provider = JvmClassModuleProvider()

    @Test
    fun loads_explicit_jdk_class_provider_for_string() {
        val file = providerFile("java.lang.String")

        assertEquals(jvmClassPath("java.lang.String"), file.path)
        assertEquals("String", file.module.moduleName)
    }
    @Test
    fun provider_path_uses_jvm_internal_package_segments() {
        val file = providerFile("java.util.Locale")

        assertEquals("__jvm__/classes/java/util/Locale.lua", file.path.value)
    }
    @Test
    fun reflected_class_provider_has_return_table_surface() {
        val file = providerFile("java.lang.String")

        assertEquals(ModuleExportSurface.SourceForm.RETURN_TABLE_LITERAL, file.surface.sourceForm)
    }
    @Test
    fun class_module_exposes_class_field() {
        val module = module("java.lang.String")

        assertIs<JavaInstanceType>(module.fields["__class"])
    }
    @Test
    fun integer_static_max_value_field_maps_to_number() {
        val module = module("java.lang.Integer")

        assertEquals(PrimitiveType.NUMBER, module.fields.required("MAX_VALUE"))
    }
    @Test
    fun reflected_static_method_is_callable() {
        val module = module("java.lang.System")

        assertIs<CallableType>(module.methods.required("currentTimeMillis"))
    }
    @Test
    fun overloaded_static_methods_expose_multiple_signatures() {
        val method = callable(module("java.lang.Integer").methods.required("valueOf"))

        assertTrue(method.callSignatures.size >= 2)
    }
    @Test
    fun instance_method_is_exposed_on_class_type() {
        val classType = classType("java.lang.String")

        assertIs<CallableType>(classType.allInstanceMembers().required("substring").valueType)
    }
    @Test
    fun reflected_class_type_hydrates_transitive_superclass_for_assignability() {
        val source = classType("java.lang.StringBuilder")
        val objectClass = assertNotNull(source.classType.findJavaClass("java.lang.Object"))

        assertTrue(JavaInstanceType(objectClass).isAssignableFrom(source))
    }
    @Test
    fun primitive_byte_array_maps_to_number_array() {
        val method = callable(classType("java.lang.String").allInstanceMembers().required("getBytes").valueType)
        val arrayReturns = method.callSignatures.mapNotNull { it.returnType as? ArrayType }

        assertTrue(arrayReturns.any { it.elementType == PrimitiveType.NUMBER })
    }
    @Test
    fun reflected_vararg_methods_preserve_method_varargs_metadata() {
        val method = callable(module("java.util.Arrays").methods.required("asList"))
        val asListMember = classType("java.util.Arrays").classType.allStaticMembers().required("asList")

        assertTrue(asListMember.signatureMetadata.single().isVarArgs)
        assertTrue(method.callSignatures.any { signature ->
            val parameter = signature.parameters.singleOrNull() ?: return@any false
            val arrayType = parameter.type as? ArrayType ?: return@any false
            parameter.vararg &&
                signature.typeParameters.singleOrNull()?.name == "T" &&
                (arrayType.elementType as? TypeParameterType)?.name == "T"
        })
    }
    @Test
    fun reflected_generic_return_metadata_retains_type_parameter_arguments() {
        val method = callable(module("java.util.Collections").methods.required("emptyList"))
        val emptyListMember = classType("java.util.Collections").classType.allStaticMembers().required("emptyList")

        assertTrue(emptyListMember.signatureMetadata.any { metadata ->
            metadata.typeParameters.map { it.name } == listOf("T") &&
                metadata.genericReturnTypeName == "java.util.List<T>"
        })
        assertTrue(method.callSignatures.any { signature ->
            val returnType = signature.returnType as? JavaInstanceType ?: return@any false
            signature.typeParameters.singleOrNull()?.name == "T" &&
                returnType.javaName.canonicalName == "java.util.List" &&
                (returnType.typeArguments.singleOrNull() as? TypeParameterType)?.name == "T"
            })
    }
    @Test
    fun missing_class_is_not_mounted() {
        val files = provider.providersFor(
            mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "missing.DoesNotExist")
        )

        assertTrue(files.isEmpty())
    }
    @Test
    fun constructor_surface_models_no_arg_constructor_returning_instance_class() {
        val constructor = callable(module("java.lang.StringBuilder").fields.required("__call"))

        assertTrue(constructor.callSignatures.any { signature ->
            signature.parameters.isEmpty() && (signature.returnType as? JavaInstanceType)?.javaName?.canonicalName == "java.lang.StringBuilder"
        })
    }

    private fun providerFile(className: String): ProviderFile {
        val path = jvmClassPath(className)
        val snapshot = provider.providersFor(
            mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to className)
        )[path] ?: fail("Missing provider for $className at ${path.value}")
        val surface = snapshot.moduleExportSurface ?: fail("Missing export surface for $className")
        return ProviderFile(path, snapshot, surface, surface.moduleType)
    }

    private fun module(className: String): ModuleType = providerFile(className).module

    private fun classType(className: String): JavaInstanceType = assertIs(module(className).fields.required("__class"))

    private fun callable(type: Type): CallableType = assertIs(type)

    private fun assertClassNamed(expectedName: String, type: Type?) {
        assertEquals(expectedName, assertIs<JavaInstanceType>(type).javaName.canonicalName)
    }

    private fun assertJavaClassNamed(expectedName: String, type: JavaClassType?) {
        assertEquals(expectedName, assertNotNull(type).javaName.canonicalName)
    }

    private fun JavaClassType.findJavaClass(
        binaryName: String,
        visited: MutableSet<String> = linkedSetOf()
    ): JavaClassType? {
        if (!visited.add(javaName.binaryName)) {
            return null
        }
        if (javaName.binaryName == binaryName) {
            return this
        }
        return superClass?.findJavaClass(binaryName, visited)
            ?: interfaces.firstNotNullOfOrNull { it.findJavaClass(binaryName, visited) }
    }

    private fun JavaClassType.maxHierarchyDepth(visited: MutableSet<String> = linkedSetOf()): Int {
        if (!visited.add(javaName.binaryName)) {
            return 0
        }
        val children = listOfNotNull(superClass) + interfaces
        return 1 + (children.maxOfOrNull { it.maxHierarchyDepth(visited) } ?: 0)
    }

    private fun jvmClassPath(className: String): VirtualPath =
        VirtualPath.of("__jvm__/classes/${className.replace('.', '/')}.lua")

    private fun <T> Map<String, T>.required(name: String): T =
        this[name] ?: fail("Missing reflected member '$name'; available: ${keys.sorted().joinToString()}")

    private fun ProviderFile.member(vararg path: String): ModuleExportSurface.MemberExport =
        surface.members.singleOrNull { it.exportPath == path.toList() }
            ?: fail("Missing exported member path ${path.joinToString(".")}")

    private data class ProviderFile(
        val path: VirtualPath,
        val snapshot: WorkspaceSnapshot.FileSnapshot,
        val surface: ModuleExportSurface,
        val module: ModuleType
    )
}
