package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bundled androlua-runtime.jar temp extraction must be once per PROCESS, not once per
 * provider instance. Provider instances are created per workspace update (per keystroke in
 * an LSP session); the old per-instance extraction leaked one 1.3MB jar copy per update
 * into java.io.tmpdir — ~36k copies / ~45GiB over long demo sessions.
 */
class RuntimeJarTempLeakTddTest {

    @Test
    fun provider_instances_share_one_extracted_runtime_jar() {
        val first = assertNotNull(JvmClassModuleProvider().bundledRuntimeJarForDiagnostics())
        val second = JvmClassModuleProvider().bundledRuntimeJarForDiagnostics()
        assertEquals(first.absolutePath, second?.absolutePath, "provider instances must share one extraction")
    }

    @Test
    fun repeated_workspace_updates_keep_the_runtime_jar_extraction_stable() {
        val service = LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
        }
        // Engine updates between opens compose fresh provider instances; the extraction must
        // stay one shared file for the whole process.
        repeat(8) { i ->
            service.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem("file:///workspace/leak-probe-$i.lua", "lua", 1, "local x = $i\nreturn x"))
            )
        }
        val first = JvmClassModuleProvider().bundledRuntimeJarForDiagnostics()
        val second = JvmClassModuleProvider().bundledRuntimeJarForDiagnostics()
        assertEquals(first, second)
        first?.let { file ->
            assertTrue(file.isFile && file.length() > 0)
            assertTrue(file.name.startsWith("androlua-runtime"), file.name)
        }
        assertEquals(first, File(first?.absolutePath.orEmpty()))
    }
}
