package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceConfiguration
import java.io.File
import kotlin.test.Test

/**
 * Provider-level probe for the dynamic-dex import chain: mount a library by bare
 * path, then resolve a class from it through the same entry points the engine's
 * `resolveImportTarget` lambda uses.
 */
class DynamicDexProviderProbeTest {

    @Test
    fun library_import_then_class_resolution() {
        val repoRoot = File(System.getProperty("user.dir"))
        val runtimeDex = File(repoRoot, "android/sample/src/androidTest/assets/runtime-greeter.dex")
        if (!runtimeDex.isFile) {
            println("PROBE: runtime dex fixture missing")
            return
        }
        val provider = JvmClassModuleProvider()
        val configuration = JvmWorkspaceConfiguration()

        // Step 1: bare library import (the source-import fact form).
        val librarySymbol = provider.importedSymbolForTarget(runtimeDex.absolutePath, configuration)
        println("PROBE: librarySymbol=" + (librarySymbol?.alias ?: "null") +
            " fields=" + (librarySymbol?.moduleType?.fields?.size ?: -1) +
            " methods=" + (librarySymbol?.moduleType?.methods?.size ?: -1))

        // Step 2: class resolution through the same entry the engine's
        // resolveImportTarget lambda uses (deferMembers = false).
        val classSymbol = provider.importedSymbolForTarget("fixture.dynamic.Greeter", configuration)
        println("PROBE: classSymbol=" + (classSymbol?.alias ?: "null") +
            " fields=" + (classSymbol?.moduleType?.fields?.size ?: -1) +
            " methods=" + (classSymbol?.moduleType?.methods?.size ?: -1))

        // Step 3: names visible after the library import.
        val names = provider.importedClassNames(runtimeDex.absolutePath, configuration)
        println("PROBE: importedClassNames=" + names)
    }
}
