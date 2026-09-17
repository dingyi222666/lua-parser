package io.github.dingyi222666.luaparser.interop.jvm

import io.github.dingyi222666.luaparser.interop.dex.DexClass
import io.github.dingyi222666.luaparser.interop.dex.DexField
import io.github.dingyi222666.luaparser.interop.dex.DexMethod
import io.github.dingyi222666.luaparser.semantic.types.model.FunctionType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaArrayType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaClassType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaMemberKind
import io.github.dingyi222666.luaparser.semantic.types.model.JavaOverloadType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaStaticMemberType
import io.github.dingyi222666.luaparser.semantic.types.model.ModuleType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hand-built [DexClass] fixtures (D1 model: `commonMain/.../interop/dex/`)
 * driving [DexClassModelAdapter] into the same shape [JvmClassModuleProvider]
 * produces for reflected android.jar classes.
 *
 * Binary names use the dex model's slash form (`com/foo/Bar`); the adapter
 * normalizes them to reflection-style dotted names in the type model.
 */
class DexClassModelAdapterTest {

    // Dex access flags, documented independently of the adapter's private constants.
    private val accPublic = 0x0001
    private val accPrivate = 0x0002
    private val accProtected = 0x0004
    private val accStatic = 0x0008
    private val accBridge = 0x0040
    private val accVarargs = 0x0080
    private val accSynthetic = 0x1000
    private val accConstructor = 0x10000

    /** Dex-model convention: simpleName is the last `/` segment of binaryName. */
    private fun dexClass(
        binaryName: String,
        simpleName: String = binaryName.substringAfterLast('/'),
        accessFlags: Int = accPublic,
        superbinaryName: String? = null,
        interfaceBinaryNames: List<String> = emptyList(),
        fields: List<DexField> = emptyList(),
        methods: List<DexMethod> = emptyList()
    ): DexClass = DexClass(
        binaryName = binaryName,
        simpleName = simpleName,
        accessFlags = accessFlags,
        superbinaryName = superbinaryName,
        interfaceBinaryNames = interfaceBinaryNames,
        fields = fields,
        methods = methods
    )

    @Test
    fun primitiveDescriptors_mapToParserPrimitives() {
        // Mirror of JvmClassModuleProvider.javaClassToType's primitive table.
        assertEquals(PrimitiveType.NUMBER, DexClassModelAdapter.descriptorToType("I"))
        assertEquals(PrimitiveType.NUMBER, DexClassModelAdapter.descriptorToType("J"))
        assertEquals(PrimitiveType.NUMBER, DexClassModelAdapter.descriptorToType("D"))
        assertEquals(PrimitiveType.NUMBER, DexClassModelAdapter.descriptorToType("F"))
        assertEquals(PrimitiveType.NUMBER, DexClassModelAdapter.descriptorToType("S"))
        assertEquals(PrimitiveType.NUMBER, DexClassModelAdapter.descriptorToType("B"))
        assertEquals(PrimitiveType.BOOLEAN, DexClassModelAdapter.descriptorToType("Z"))
        assertEquals(PrimitiveType.STRING, DexClassModelAdapter.descriptorToType("C"))
        assertEquals(PrimitiveType.NIL, DexClassModelAdapter.descriptorToType("V"))
    }

    @Test
    fun objectDescriptors_mirrorJvmPrimitiveTable() {
        // String/CharSequence-family stay string primitives so dex strings behave
        // exactly like reflected android.jar strings; boxed numerics stay numbers.
        assertEquals(PrimitiveType.STRING, DexClassModelAdapter.descriptorToType("Ljava/lang/String;"))
        assertEquals(PrimitiveType.STRING, DexClassModelAdapter.descriptorToType("Ljava/lang/CharSequence;"))
        assertEquals(PrimitiveType.BOOLEAN, DexClassModelAdapter.descriptorToType("Ljava/lang/Boolean;"))
        assertEquals(PrimitiveType.NUMBER, DexClassModelAdapter.descriptorToType("Ljava/lang/Integer;"))
        assertEquals(PrimitiveType.NUMBER, DexClassModelAdapter.descriptorToType("Ljava/lang/Double;"))
    }

    @Test
    fun lDescriptors_mapToNamedInstanceShellsWithDottedNames() {
        val type = assertIs<JavaInstanceType>(DexClassModelAdapter.descriptorToType("Landroid/widget/TextView;"))
        // Slash-form dex names normalize to reflection-style dotted binary names.
        assertEquals("android.widget.TextView", type.classType.javaName.binaryName)
        assertEquals("android.widget.TextView", type.classType.javaName.canonicalName)
        assertEquals("TextView", type.classType.javaName.simpleName)
        // Shell: name-only, no members, no hierarchy.
        assertTrue(type.classType.staticMembers.isEmpty())
        assertTrue(type.classType.instanceMembers.isEmpty())
        assertTrue(type.classType.innerClasses.isEmpty())
        assertNull(type.classType.superClass)
        assertTrue(type.classType.interfaces.isEmpty())
        assertEquals("android.widget.TextView", type.classType.displayName)
    }

    @Test
    fun arrayDescriptors_mapToJavaArrayType() {
        val ints = assertIs<JavaArrayType>(DexClassModelAdapter.descriptorToType("[I"))
        assertEquals(PrimitiveType.NUMBER, ints.elementType)
        assertEquals("number[]", ints.name)

        val strings = assertIs<JavaArrayType>(DexClassModelAdapter.descriptorToType("[Ljava/lang/String;"))
        assertEquals(PrimitiveType.STRING, strings.elementType)
        // String elements are the string primitive, so the display carries "string".
        assertEquals("string[]", strings.name)

        // Nested single-rank wrappers so index peeling yields intermediate arrays.
        val nested = assertIs<JavaArrayType>(DexClassModelAdapter.descriptorToType("[[D"))
        val innerRank = assertIs<JavaArrayType>(nested.elementType)
        assertEquals(PrimitiveType.NUMBER, innerRank.elementType)
        assertEquals("number[][]", nested.name)
    }

    @Test
    fun staticAndInstanceMembers_splitAcrossSurfaces() {
        val cls = dexClass(
            binaryName = "com/example/Display",
            fields = listOf(
                DexField(name = "MAX_BRIGHTNESS", typeDescriptor = "I", accessFlags = accPublic or accStatic),
                DexField(name = "brightness", typeDescriptor = "F", accessFlags = accPublic)
            ),
            methods = listOf(
                DexMethod(name = "getBrightness", parameterDescriptors = emptyList(), returnDescriptor = "F", accessFlags = accPublic),
                DexMethod(
                    name = "valueOf",
                    parameterDescriptors = listOf("I"),
                    returnDescriptor = "Z",
                    accessFlags = accPublic or accStatic
                )
            )
        )
        val classType = DexClassModelAdapter.toJavaClassType(cls)
        assertEquals("com.example.Display", classType.javaName.binaryName)

        val staticField = assertIs<JavaStaticMemberType>(classType.staticMembers["MAX_BRIGHTNESS"])
        assertEquals(JavaMemberKind.FIELD, staticField.memberKind)
        assertEquals(PrimitiveType.NUMBER, staticField.valueType)

        val staticMethod = assertIs<JavaStaticMemberType>(classType.staticMembers["valueOf"])
        assertEquals(JavaMemberKind.METHOD, staticMethod.memberKind)
        val valueOfSignature = assertIs<FunctionType>(staticMethod.valueType)
        assertEquals(PrimitiveType.NUMBER, valueOfSignature.parameters.single().type)
        assertEquals(PrimitiveType.BOOLEAN, valueOfSignature.returnType)

        val instanceMember = assertNotNull(classType.instanceMembers["getBrightness"])
        assertEquals(JavaMemberKind.METHOD, instanceMember.memberKind)
        val instanceSignature = assertIs<FunctionType>(instanceMember.valueType)
        assertEquals(PrimitiveType.NUMBER, instanceSignature.returnType)
        assertTrue(instanceSignature.parameters.isEmpty())

        assertNull(classType.instanceMembers["MAX_BRIGHTNESS"])
        assertNull(classType.staticMembers["getBrightness"])

        val module = DexClassModelAdapter.toModuleType(cls)
        val instanceShell = assertIs<JavaInstanceType>(module.fields["__class"])
        assertEquals("com.example.Display", instanceShell.classType.javaName.binaryName)
        assertEquals(PrimitiveType.NUMBER, module.fields["MAX_BRIGHTNESS"])
        // Instance members never leak into the module table.
        assertNull(module.fields["brightness"])
        assertEquals(setOf("valueOf"), module.methods.keys)
        assertIs<FunctionType>(module.methods["valueOf"])
    }

    @Test
    fun initMethods_mapToConstructorOverloadsAndModuleCallField() {
        val cls = dexClass(
            binaryName = "com/example/Point",
            methods = listOf(
                DexMethod(name = "<init>", parameterDescriptors = emptyList(), returnDescriptor = "V", accessFlags = accPublic or accConstructor),
                DexMethod(
                    name = "<init>",
                    parameterDescriptors = listOf("I", "I"),
                    returnDescriptor = "V",
                    accessFlags = accPublic or accConstructor
                )
            )
        )
        val classType = DexClassModelAdapter.toJavaClassType(cls)
        assertEquals(2, classType.constructors.overloads.size)

        val withArgs = classType.constructors.overloads[1].signature
        assertEquals(PrimitiveType.NUMBER, withArgs.parameters[0].type)
        assertEquals(PrimitiveType.NUMBER, withArgs.parameters[1].type)
        val constructorReturn = assertIs<JavaInstanceType>(withArgs.returnType)
        assertEquals("com.example.Point", constructorReturn.javaName.binaryName)

        val module = DexClassModelAdapter.toModuleType(cls)
        val instanceShell = assertIs<JavaInstanceType>(module.fields["__class"])
        assertEquals("com.example.Point", instanceShell.classType.javaName.binaryName)
        // Constructors present -> module carries the __call field (callable class object).
        assertEquals(classType, module.fields["__call"])
        assertEquals("Point", module.moduleName)
    }

    @Test
    fun classWithoutConstructors_hasNoCallField() {
        val cls = dexClass(
            binaryName = "com/example/Util",
            methods = listOf(
                DexMethod(name = "run", parameterDescriptors = emptyList(), returnDescriptor = "V", accessFlags = accPublic or accStatic)
            )
        )
        val module = DexClassModelAdapter.toModuleType(cls)
        assertNull(module.fields["__call"])
        assertIs<JavaInstanceType>(module.fields["__class"])
        assertTrue(DexClassModelAdapter.toJavaClassType(cls).constructors.isEmpty)
    }

    @Test
    fun syntheticBridgeAndNonVisibleMembers_areFiltered() {
        val cls = dexClass(
            binaryName = "com/example/Filtered",
            fields = listOf(
                DexField(name = "ok", typeDescriptor = "Z", accessFlags = accPublic or accStatic),
                DexField(name = "hidden", typeDescriptor = "Z", accessFlags = accPrivate),
                DexField(name = "generated", typeDescriptor = "Z", accessFlags = accPublic or accStatic or accSynthetic)
            ),
            methods = listOf(
                DexMethod(name = "visible", parameterDescriptors = emptyList(), returnDescriptor = "V", accessFlags = accPublic),
                DexMethod(name = "touchable", parameterDescriptors = emptyList(), returnDescriptor = "V", accessFlags = accProtected),
                DexMethod(
                    name = "bridged",
                    parameterDescriptors = listOf("Ljava/lang/Object;"),
                    returnDescriptor = "V",
                    accessFlags = accPublic or accBridge
                ),
                DexMethod(name = "synthesized", parameterDescriptors = emptyList(), returnDescriptor = "V", accessFlags = accPublic or accSynthetic),
                DexMethod(name = "secret", parameterDescriptors = emptyList(), returnDescriptor = "V", accessFlags = accPrivate),
                DexMethod(
                    name = "<clinit>",
                    parameterDescriptors = emptyList(),
                    returnDescriptor = "V",
                    accessFlags = accPublic or accStatic or accConstructor
                )
            )
        )
        val classType = DexClassModelAdapter.toJavaClassType(cls)

        assertTrue("ok" in classType.staticMembers)
        // Protected stays (public/protected-only policy).
        assertTrue("touchable" in classType.instanceMembers)
        assertFalse("generated" in classType.staticMembers)
        assertFalse("hidden" in classType.instanceMembers)
        assertFalse("bridged" in classType.instanceMembers)
        assertFalse("synthesized" in classType.instanceMembers)
        assertFalse("secret" in classType.instanceMembers)
        // <clinit> is an initializer, not a script-callable member or constructor.
        assertFalse("<clinit>" in classType.staticMembers)
        assertTrue(classType.constructors.isEmpty)
    }

    @Test
    fun innerClasses_nestByKeyUnderDepthPolicy() {
        val outer = dexClass(binaryName = "android/view/Outer")
        val inner = dexClass(binaryName = "android/view/Outer\$Inner")
        val deep = dexClass(binaryName = "android/view/Outer\$Inner\$Deep")
        val dexClasses = listOf(outer, inner, deep)

        val outerClassType = DexClassModelAdapter.toJavaClassType(outer, dexClasses)
        val innerReference = assertIs<JavaClassType>(outerClassType.innerClasses["Inner"])
        assertEquals("android.view.Outer\$Inner", innerReference.javaName.binaryName)
        // Type reference value: shell only (member surfaces live on the module field).
        assertTrue(innerReference.instanceMembers.isEmpty())
        // MAX_REFLECTED_INNER_CLASS_DEPTH=1 policy: grandchildren never surface.
        assertNull(outerClassType.innerClasses["Deep"])

        val outerModule = DexClassModelAdapter.toModuleType(outer, dexClasses)
        val innerModule = assertIs<ModuleType>(outerModule.fields["Inner"])
        assertEquals("Inner", innerModule.moduleName)
        assertIs<JavaInstanceType>(innerModule.fields["__class"])
        assertNull(outerModule.fields["Deep"])
        assertNull(innerModule.fields["Deep"])

        // Without the dex set the adapter cannot know inner classes exist.
        assertTrue(DexClassModelAdapter.toJavaClassType(outer).innerClasses.isEmpty())
        assertNull(DexClassModelAdapter.toModuleType(outer).fields["Inner"])
    }

    @Test
    fun sameDexSetReferences_carryMembers_othersStayShells() {
        val textView = dexClass(
            binaryName = "android/widget/TextView",
            fields = listOf(
                DexField(name = "gravity", typeDescriptor = "I", accessFlags = accPublic or accProtected)
            )
        )
        val holder = dexClass(
            binaryName = "com/example/Holder",
            methods = listOf(
                DexMethod(
                    name = "make",
                    parameterDescriptors = emptyList(),
                    returnDescriptor = "Landroid/widget/TextView;",
                    accessFlags = accPublic
                )
            )
        )

        val hydrated = DexClassModelAdapter.toJavaClassType(holder, listOf(textView))
        val hydratedReturn = assertIs<JavaInstanceType>(
            assertIs<FunctionType>(assertNotNull(hydrated.instanceMembers["make"]).valueType).returnType
        )
        assertEquals("android.widget.TextView", hydratedReturn.classType.javaName.binaryName)
        assertEquals(PrimitiveType.NUMBER, assertNotNull(hydratedReturn.classType.instanceMembers["gravity"]).valueType)

        // No dex set -> shell named by the descriptor's binary name, no members.
        val shell = DexClassModelAdapter.toJavaClassType(holder)
        val shellReturn = assertIs<JavaInstanceType>(
            assertIs<FunctionType>(assertNotNull(shell.instanceMembers["make"]).valueType).returnType
        )
        assertEquals("android.widget.TextView", shellReturn.classType.javaName.binaryName)
        assertTrue(shellReturn.classType.instanceMembers.isEmpty())
    }

    @Test
    fun superClassAndInterfaces_areShallowNameOnlyReferences() {
        val cls = dexClass(
            binaryName = "com/example/Button",
            superbinaryName = "android/view/View",
            interfaceBinaryNames = listOf("java/lang/Runnable")
        )
        val classType = DexClassModelAdapter.toJavaClassType(cls)

        val superReference = assertIs<JavaClassType>(assertNotNull(classType.superClass))
        assertEquals("android.view.View", superReference.javaName.binaryName)
        assertTrue(superReference.instanceMembers.isEmpty())
        assertTrue(superReference.staticMembers.isEmpty())

        assertEquals(1, classType.interfaces.size)
        assertEquals("java.lang.Runnable", classType.interfaces.single().javaName.binaryName)
    }

    @Test
    fun overloadedMethods_becomeOverloadTypes() {
        val cls = dexClass(
            binaryName = "com/example/Parsers",
            methods = listOf(
                DexMethod(
                    name = "parse",
                    parameterDescriptors = listOf("Ljava/lang/String;"),
                    returnDescriptor = "J",
                    accessFlags = accPublic or accStatic
                ),
                DexMethod(
                    name = "parse",
                    parameterDescriptors = listOf("Ljava/lang/String;", "I"),
                    returnDescriptor = "J",
                    accessFlags = accPublic or accStatic
                )
            )
        )
        val classType = DexClassModelAdapter.toJavaClassType(cls)
        val parse = assertIs<JavaStaticMemberType>(classType.staticMembers["parse"])
        assertEquals(JavaMemberKind.METHOD, parse.memberKind)
        val overloads = assertIs<JavaOverloadType>(parse.valueType)
        assertEquals(2, overloads.callSignatures.size)
        assertEquals("com.example.Parsers", overloads.javaName.binaryName)

        val module = DexClassModelAdapter.toModuleType(cls)
        val moduleOverloads = assertIs<JavaOverloadType>(module.methods["parse"])
        assertEquals(2, moduleOverloads.callSignatures.size)
    }

    @Test
    fun varargsMethod_flagsLastParameterAndMetadata() {
        val cls = dexClass(
            binaryName = "com/example/Formatter",
            methods = listOf(
                DexMethod(
                    name = "format",
                    parameterDescriptors = listOf("Ljava/lang/String;", "[Ljava/lang/Object;"),
                    returnDescriptor = "Ljava/lang/String;",
                    accessFlags = accPublic or accStatic or accVarargs
                )
            )
        )
        val classType = DexClassModelAdapter.toJavaClassType(cls)
        val format = assertIs<JavaStaticMemberType>(classType.staticMembers["format"])
        val signature = assertIs<FunctionType>(format.valueType)
        assertEquals(2, signature.parameters.size)
        assertFalse(signature.parameters[0].vararg)
        assertTrue(signature.parameters[1].vararg)
        assertTrue(format.signatureMetadata.single().isVarArgs)
        // String return stays the string primitive per the mirrored table.
        assertEquals(PrimitiveType.STRING, signature.returnType)
    }

    @Test
    fun moduleName_claimsSimpleName_withStdCollisionGuard() {
        assertEquals("Point", DexClassModelAdapter.toModuleType(
            dexClass(binaryName = "com/example/Point")
        ).moduleName)
        // Nested std-colliding simple names claim the qualified form (R$string -> "R.string"),
        // even though the dex model's simpleName convention keeps the "R$string" segment.
        assertEquals("R.string", DexClassModelAdapter.toModuleType(
            dexClass(binaryName = "android/R\$string")
        ).moduleName)
    }
}
