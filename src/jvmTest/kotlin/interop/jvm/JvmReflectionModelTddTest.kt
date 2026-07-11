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
    fun reflected_provider_cache_key_is_present() {
        val file = providerFile("java.lang.String")

        assertFalse(file.snapshot.cacheKey.isNullOrBlank())
    }

    @Test
    fun reflected_provider_public_fingerprint_is_present() {
        val file = providerFile("java.lang.String")

        assertFalse(file.snapshot.publicFingerprint?.value.isNullOrBlank())
    }

    @Test
    fun public_fingerprint_provides_simple_module_name() {
        val file = providerFile("java.util.Locale")

        assertEquals(linkedSetOf("Locale"), file.snapshot.publicFingerprint?.providedModuleNames)
    }

    @Test
    fun different_jdk_classes_have_different_public_fingerprints() {
        val string = providerFile("java.lang.String")
        val locale = providerFile("java.util.Locale")

        assertNotEquals(string.snapshot.publicFingerprint?.value, locale.snapshot.publicFingerprint?.value)
    }

    @Test
    fun repeated_reflection_of_same_jdk_class_has_stable_fingerprint() {
        val first = providerFile("java.lang.String")
        val second = providerFile("java.lang.String")

        assertEquals(first.snapshot.publicFingerprint?.value, second.snapshot.publicFingerprint?.value)
    }

    @Test
    fun class_module_exposes_class_field() {
        val module = module("java.lang.String")

        assertIs<JavaInstanceType>(module.fields["__class"])
    }

    @Test
    fun class_field_uses_fully_qualified_class_name() {
        val classType = classType("java.lang.String")

        assertEquals("java.lang.String", classType.name)
    }

    @Test
    fun reflected_class_field_preserves_java_instance_type_metadata() {
        val instanceType = classType("java.lang.String")

        assertEquals("java.lang.String", instanceType.classType.javaName.canonicalName)
        assertTrue("substring" in instanceType.allInstanceMembers())
    }

    @Test
    fun reflected_constructor_surface_preserves_java_class_type_metadata() {
        val classType = assertIs<JavaClassType>(module("java.lang.String").fields.required("__call"))

        assertEquals("java.lang.String", classType.javaName.canonicalName)
        assertTrue("CASE_INSENSITIVE_ORDER" in classType.staticMembers)
    }

    @Test
    fun string_static_case_insensitive_order_field_maps_to_class_type() {
        val module = module("java.lang.String")

        assertClassNamed("java.util.Comparator", module.fields.required("CASE_INSENSITIVE_ORDER"))
    }

    @Test
    fun integer_static_max_value_field_maps_to_number() {
        val module = module("java.lang.Integer")

        assertEquals(PrimitiveType.NUMBER, module.fields.required("MAX_VALUE"))
    }

    @Test
    fun boolean_static_true_field_maps_to_boolean() {
        val module = module("java.lang.Boolean")

        assertEquals(PrimitiveType.BOOLEAN, module.fields.required("TRUE"))
    }

    @Test
    fun system_static_out_field_maps_to_print_stream_class() {
        val module = module("java.lang.System")

        assertClassNamed("java.io.PrintStream", module.fields.required("out"))
    }

    @Test
    fun locale_static_root_field_maps_to_locale_class() {
        val module = module("java.util.Locale")

        assertClassNamed("java.util.Locale", module.fields.required("ROOT"))
    }

    @Test
    fun reflected_static_method_is_callable() {
        val module = module("java.lang.System")

        assertIs<CallableType>(module.methods.required("currentTimeMillis"))
    }

    @Test
    fun static_method_void_return_maps_to_nil() {
        val method = callable(module("java.lang.System").methods.required("gc"))

        assertTrue(method.callSignatures.any { it.returnType == PrimitiveType.NIL })
    }

    @Test
    fun static_method_string_return_maps_to_string() {
        val method = callable(module("java.lang.System").methods.required("lineSeparator"))

        assertTrue(method.callSignatures.any { it.returnType == PrimitiveType.STRING })
    }

    @Test
    fun static_method_primitive_number_return_maps_to_number() {
        val method = callable(module("java.lang.Integer").methods.required("parseInt"))

        assertTrue(method.callSignatures.any { it.returnType == PrimitiveType.NUMBER })
    }

    @Test
    fun static_method_boolean_return_maps_to_boolean() {
        val method = callable(module("java.lang.Boolean").methods.required("parseBoolean"))

        assertTrue(method.callSignatures.any { it.returnType == PrimitiveType.BOOLEAN })
    }

    @Test
    fun overloaded_static_methods_expose_multiple_signatures() {
        val method = callable(module("java.lang.Integer").methods.required("valueOf"))

        assertTrue(method.callSignatures.size >= 2)
    }

    @Test
    fun overloaded_static_method_parameters_keep_reflection_order_names() {
        val method = callable(module("java.lang.Integer").methods.required("parseInt"))

        assertTrue(method.callSignatures.any { signature ->
            signature.parameters.map { it.name } == listOf("arg1", "arg2")
        })
    }

    @Test
    fun static_method_string_parameter_maps_to_string() {
        val method = callable(module("java.lang.Integer").methods.required("parseInt"))

        assertTrue(method.callSignatures.any { signature ->
            signature.parameters.firstOrNull()?.type == PrimitiveType.STRING
        })
    }

    @Test
    fun static_method_primitive_parameter_maps_to_number() {
        val method = callable(module("java.lang.Integer").methods.required("toString"))

        assertTrue(method.callSignatures.any { signature ->
            signature.parameters.firstOrNull()?.type == PrimitiveType.NUMBER
        })
    }

    @Test
    fun static_method_class_parameter_maps_to_class_reference() {
        val method = callable(module("java.util.Arrays").methods.required("asList"))

        assertTrue(method.callSignatures.any { signature ->
            signature.parameters.any { parameter -> parameter.type is ArrayType }
        })
    }

    @Test
    fun instance_method_is_exposed_on_class_type() {
        val classType = classType("java.lang.String")

        assertIs<CallableType>(classType.allInstanceMembers().required("substring").valueType)
    }

    @Test
    fun instance_method_number_return_maps_to_number() {
        val method = callable(classType("java.lang.String").allInstanceMembers().required("length").valueType)

        assertTrue(method.callSignatures.any { it.returnType == PrimitiveType.NUMBER })
    }

    @Test
    fun instance_method_string_return_maps_to_string() {
        val method = callable(classType("java.lang.String").allInstanceMembers().required("trim").valueType)

        assertTrue(method.callSignatures.any { it.returnType == PrimitiveType.STRING })
    }

    @Test
    fun instance_method_boolean_return_maps_to_boolean() {
        val method = callable(classType("java.lang.String").allInstanceMembers().required("isEmpty").valueType)

        assertTrue(method.callSignatures.any { it.returnType == PrimitiveType.BOOLEAN })
    }

    @Test
    fun overloaded_instance_methods_expose_multiple_signatures() {
        val method = callable(classType("java.lang.String").allInstanceMembers().required("substring").valueType)

        assertTrue(method.callSignatures.size >= 2)
    }

    @Test
    fun instance_method_class_return_maps_to_class_reference() {
        val method = callable(classType("java.lang.String").allInstanceMembers().required("toUpperCase").valueType)

        assertTrue(method.callSignatures.any { it.returnType is JavaInstanceType || it.returnType == PrimitiveType.STRING })
    }

    @Test
    fun public_instance_fields_are_exposed_on_class_type() {
        val classType = classType("java.awt.Point")

        assertEquals(PrimitiveType.NUMBER, classType.allInstanceMembers().required("x").valueType)
        assertEquals(PrimitiveType.NUMBER, classType.allInstanceMembers().required("y").valueType)
    }

    @Test
    fun inherited_instance_methods_are_available_through_get_all_methods() {
        val classType = classType("java.lang.StringBuilder")

        assertTrue("wait" in classType.allInstanceMembers())
    }

    @Test
    fun reflected_class_type_records_superclass_reference() {
        val classType = classType("java.lang.StringBuilder")

        assertJavaClassNamed("java.lang.AbstractStringBuilder", classType.classType.superClass)
    }

    @Test
    fun reflected_class_type_hydrates_transitive_superclass_for_assignability() {
        val source = classType("java.lang.StringBuilder")
        val objectClass = assertNotNull(source.classType.findJavaClass("java.lang.Object"))

        assertTrue(JavaInstanceType(objectClass).isAssignableFrom(source))
    }

    @Test
    fun reflected_class_type_hydrates_transitive_interfaces_for_assignability() {
        val source = classType("java.util.ArrayList")
        val iterable = assertNotNull(source.classType.findJavaClass("java.lang.Iterable"))

        assertTrue(JavaInstanceType(iterable).isAssignableFrom(source))
    }

    @Test
    fun reflected_class_type_hierarchy_hydration_is_bounded() {
        val classType = classType("java.util.ArrayList").classType

        assertTrue(classType.maxHierarchyDepth() <= 33)
    }

    @Test
    fun inherited_object_method_export_is_available_on_provider_surface() {
        val file = providerFile("java.lang.StringBuilder")

        assertTrue(file.surface.members.any { it.exportPath == listOf("__class", "wait") })
    }

    @Test
    fun provider_surface_marks_class_field_as_field_symbol() {
        val file = providerFile("java.lang.String")

        assertEquals(SymbolKind.FIELD, file.member("__class").kind)
    }

    @Test
    fun provider_surface_marks_static_method_as_method_symbol() {
        val file = providerFile("java.lang.System")

        assertEquals(SymbolKind.METHOD, file.member("currentTimeMillis").kind)
    }

    @Test
    fun provider_surface_marks_instance_method_as_nested_class_method_symbol() {
        val file = providerFile("java.lang.String")

        assertEquals(SymbolKind.METHOD, file.member("__class", "substring").kind)
    }

    @Test
    fun provider_surface_marks_instance_field_as_nested_class_field_symbol() {
        val file = providerFile("java.awt.Point")

        assertEquals(SymbolKind.FIELD, file.member("__class", "x").kind)
    }

    @Test
    fun primitive_byte_array_maps_to_number_array() {
        val method = callable(classType("java.lang.String").allInstanceMembers().required("getBytes").valueType)
        val arrayReturns = method.callSignatures.mapNotNull { it.returnType as? ArrayType }

        assertTrue(arrayReturns.any { it.elementType == PrimitiveType.NUMBER })
    }

    @Test
    fun object_array_return_maps_to_class_array() {
        val method = callable(module("java.util.Locale").methods.required("getAvailableLocales"))
        val arrayReturns = method.callSignatures.mapNotNull { it.returnType as? ArrayType }

        assertTrue(arrayReturns.any { (it.elementType as? JavaInstanceType)?.javaName?.canonicalName == "java.util.Locale" })
    }

    @Test
    fun array_parameter_maps_to_array_type() {
        val method = callable(module("java.util.Arrays").methods.required("sort"))

        assertTrue(method.callSignatures.any { signature ->
            signature.parameters.any { it.type is ArrayType }
        })
    }

    @Test
    fun reflected_non_vararg_array_parameters_remain_array_parameters() {
        val method = callable(module("java.util.Arrays").methods.required("sort"))
        val arraySignature = method.callSignatures.firstOrNull { signature ->
            signature.parameters.singleOrNull()?.type is ArrayType
        } ?: fail("Missing single-array Arrays.sort overload")
        val parameter = arraySignature.parameters.single()
        val sortMember = classType("java.util.Arrays").classType.allStaticMembers().required("sort")

        assertIs<ArrayType>(parameter.type)
        assertFalse(parameter.vararg)
        assertTrue(sortMember.signatureMetadata.any { metadata ->
            !metadata.isVarArgs && metadata.genericParameterTypeNames.any { it.endsWith("[]") }
        })
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
    fun reflected_parameterized_collection_receiver_substitutes_instance_method_return() {
        val arrayListClass = classType("java.util.ArrayList").classType
        val stringList = JavaInstanceType(arrayListClass, listOf(PrimitiveType.STRING))
        val get = callable(stringList.allInstanceMembers().required("get").valueType)

        assertTrue(get.callSignatures.any { signature ->
            signature.parameters.singleOrNull()?.type == PrimitiveType.NUMBER &&
                signature.returnType == PrimitiveType.STRING
        })
    }

    @Test
    fun reflected_parameterized_collection_receiver_substitutes_instance_method_parameters() {
        val arrayListClass = classType("java.util.ArrayList").classType
        val stringList = JavaInstanceType(arrayListClass, listOf(PrimitiveType.STRING))
        val set = callable(stringList.allInstanceMembers().required("set").valueType)

        assertTrue(set.callSignatures.any { signature ->
            signature.parameters.map { it.type } == listOf(PrimitiveType.NUMBER, PrimitiveType.STRING) &&
                signature.returnType == PrimitiveType.STRING
        })
    }

    @Test
    fun reflected_raw_collection_receiver_keeps_unresolved_type_parameter_surface() {
        val arrayListClass = classType("java.util.ArrayList").classType
        val rawList = JavaInstanceType(arrayListClass)
        val get = callable(rawList.allInstanceMembers().required("get").valueType)

        assertTrue(get.callSignatures.any { signature ->
            (signature.returnType as? TypeParameterType)?.name == "E"
        })
    }

    @Test
    fun self_bounded_generic_type_references_do_not_recurse_indefinitely() {
        val classType = classType("java.lang.Enum").classType
        val typeParameter = classType.typeParameters.single()
        val constraint = assertIs<JavaInstanceType>(typeParameter.constraint)
        val constraintArgument = assertIs<TypeParameterType>(constraint.typeArguments.single())
        val referencedTypeParameter = constraint.classType.typeParameters.single()

        assertEquals("E", typeParameter.name)
        assertEquals("java.lang.Enum", constraint.javaName.canonicalName)
        assertEquals("E", constraintArgument.name)
        assertEquals("E", referencedTypeParameter.name)
        assertNull(referencedTypeParameter.constraint)
    }

    @Test
    fun primitive_char_maps_to_string() {
        val method = callable(classType("java.lang.String").allInstanceMembers().required("charAt").valueType)

        assertTrue(method.callSignatures.any { it.returnType == PrimitiveType.STRING })
    }

    @Test
    fun primitive_void_maps_to_nil_for_instance_method() {
        val method = callable(classType("java.lang.StringBuilder").allInstanceMembers().required("setLength").valueType)

        assertTrue(method.callSignatures.any { it.returnType == PrimitiveType.NIL })
    }

    @Test
    fun boxed_number_return_maps_to_number() {
        val method = callable(module("java.lang.Integer").methods.required("valueOf"))

        assertTrue(method.callSignatures.any { it.returnType == PrimitiveType.NUMBER })
    }

    @Test
    fun char_sequence_return_maps_to_string() {
        val method = callable(classType("java.lang.StringBuilder").allInstanceMembers().required("subSequence").valueType)

        assertTrue(method.callSignatures.any { it.returnType == PrimitiveType.STRING })
    }

    @Test
    fun explicit_metadata_accepts_multiple_classes() {
        val files = provider.providersFor(
            mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "java.lang.String;java.util.Locale,java.io.File")
        )

        assertTrue(jvmClassPath("java.lang.String") in files)
        assertTrue(jvmClassPath("java.util.Locale") in files)
        assertTrue(jvmClassPath("java.io.File") in files)
    }

    @Test
    fun androlua_import_metadata_resolves_jdk_simple_class() {
        val files = provider.providersFor(
            mapOf(JvmClassModuleProvider.IMPORTS_METADATA_KEY to "String")
        )

        assertTrue(jvmClassPath("java.lang.String") in files)
    }

    @Test
    fun custom_import_prefix_resolves_simple_jdk_class() {
        val files = provider.providersFor(
            JvmWorkspaceConfiguration(
                androluaImports = listOf("BigDecimal"),
                importPrefixes = listOf("java.math")
            )
        )

        assertTrue(jvmClassPath("java.math.BigDecimal") in files)
    }

    @Test
    fun missing_class_is_not_mounted() {
        val files = provider.providersFor(
            mapOf(JvmClassModuleProvider.CLASSES_METADATA_KEY to "missing.DoesNotExist")
        )

        assertTrue(files.isEmpty())
    }

    @Test
    fun constructor_surface_exposes_callable_module_class_value() {
        val module = module("java.lang.StringBuilder")

        assertIs<JavaClassType>(
            module.fields["__call"],
            "JavaClass<T> providers should expose public constructors as a callable module field named __call"
        )
    }

    @Test
    fun constructor_surface_models_no_arg_constructor_returning_instance_class() {
        val constructor = callable(module("java.lang.StringBuilder").fields.required("__call"))

        assertTrue(constructor.callSignatures.any { signature ->
            signature.parameters.isEmpty() && (signature.returnType as? JavaInstanceType)?.javaName?.canonicalName == "java.lang.StringBuilder"
        })
    }

    @Test
    fun constructor_surface_models_string_constructor_parameter() {
        val constructor = callable(module("java.lang.StringBuilder").fields.required("__call"))

        assertTrue(constructor.callSignatures.any { signature ->
            signature.parameters.singleOrNull()?.type == PrimitiveType.STRING &&
                (signature.returnType as? JavaInstanceType)?.javaName?.canonicalName == "java.lang.StringBuilder"
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
