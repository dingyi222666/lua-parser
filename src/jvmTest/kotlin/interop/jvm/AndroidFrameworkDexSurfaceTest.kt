package interop.jvm

import io.github.dingyi222666.luaparser.interop.jvm.DexLibraryMounter
import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import io.github.dingyi222666.luaparser.semantic.checker.javaInstanceSurface
import io.github.dingyi222666.luaparser.semantic.checker.javaClassSurface
import io.github.dingyi222666.luaparser.semantic.checker.ExpressionTypeEvaluator
import io.github.dingyi222666.luaparser.semantic.SemanticPipeline
import io.github.dingyi222666.luaparser.semantic.SemanticWorkspaceContext
import io.github.dingyi222666.luaparser.semantic.workspace.std.BuiltinOverlayLoader
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.semantic.types.model.UnknownType
import io.github.dingyi222666.luaparser.semantic.types.model.JavaInstanceType
import io.github.dingyi222666.luaparser.semantic.types.model.ClassType
import io.github.dingyi222666.luaparser.semantic.types.model.CallableType
import io.github.dingyi222666.luaparser.semantic.types.model.PrimitiveType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.test.assertEquals

class AndroidFrameworkDexSurfaceTest {
    private val dex = File(System.getenv("LUA_ANDROID_DEX")
        ?: "android/sample/src/main/assets/android.dex")

    @Test
    fun native_return_types_do_not_leak_into_workspaces_without_a_provider() {
        val provider = JvmClassModuleProvider()
        val configuration = JvmWorkspaceConfiguration(androidDex = dex.absolutePath)
        val globals = BuiltinOverlayLoader.standaloneGlobals(LuaVersion.ANDROLUA_5_3)
        val chunk = LuaParser(LuaVersion.ANDROLUA_5_3).parse("local bitmap = loadbitmap('picture.png')")
        val binder = SemanticPipeline().bindForGlobalFingerprint(chunk, globals)
        val expression = (chunk.body.statements.single() as LocalStatement).variables.single()
        val mounted = ExpressionTypeEvaluator(binder, SemanticWorkspaceContext(
            overlayGlobals = globals,
            resolveImportTarget = { target -> provider.importedSymbol(target, configuration) }
        )).evaluate(expression) as JavaInstanceType
        assertTrue("getWidth" in mounted.allInstanceMembers())
        val absent = ExpressionTypeEvaluator(binder, SemanticWorkspaceContext(
            overlayGlobals = globals,
            resolveImportTarget = { null }
        )).evaluate(expression)
        val documented = absent as ClassType
        assertTrue(documented.javaClassName == "android.graphics.Bitmap")
        assertTrue("getWidth" !in documented.getAllMethods())
        assertTrue("getWidth" !in documented.getAllFields(), "No metadata means no borrowed Java members.")
    }

    @Test
    fun classes_without_accessible_constructors_do_not_gain_a_default_constructor() {
        val provider = JvmClassModuleProvider()
        val configuration = JvmWorkspaceConfiguration()
        val runnable = assertNotNull(provider.importedSymbol("java.lang.Runnable", configuration)
            ?.moduleType?.javaClassSurface())
        val string = assertNotNull(provider.importedSymbol("java.lang.String", configuration)
            ?.moduleType?.javaClassSurface())
        assertTrue(runnable.callSignatures.isEmpty())
        assertTrue(string.callSignatures.isNotEmpty())
    }

    @Test
    fun action_bar_surface_is_read_from_real_framework_dex() {
        assertTrue(dex.isFile, "Required framework DEX fixture is missing: $dex")
        val library = assertNotNull(DexLibraryMounter.libraryFor(dex))
        val actionBar = assertNotNull(library.classForTarget("android.app.ActionBar"))
        val direct = assertNotNull(library.moduleTypeFor(actionBar).javaInstanceSurface()) as JavaInstanceType
        val provider = JvmClassModuleProvider(deferPackageMemberReflection = true)
        val symbol = assertNotNull(provider.importedSymbol(
            "android.app.ActionBar",
            JvmWorkspaceConfiguration(
                androidJar = File(dex.parentFile, "absent-framework.jar").path,
                androidDex = dex.absolutePath,
                classLoader = object : ClassLoader(null) {
                    override fun loadClass(name: String): Class<*> =
                        throw ClassNotFoundException("Reflection is disabled for $name")
                }
            ),
            deferMembers = false
        ))
        val resolved = assertNotNull(symbol.moduleType.javaInstanceSurface()) as JavaInstanceType
        for (surface in listOf(direct, resolved)) {
            val members = surface.allInstanceMembers().keys
            assertTrue("hide" in members, members.toString())
            assertTrue("show" in members, members.toString())
            assertTrue("getElevation" in members, members.toString())
            val hide = assertNotNull(surface.allInstanceMembers()["hide"])
            assertEquals(PrimitiveType.VOID, (hide.valueType as CallableType).callSignatures.single().returnType)
        }
    }
}
