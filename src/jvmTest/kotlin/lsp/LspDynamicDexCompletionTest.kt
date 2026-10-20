package lsp

import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import io.github.dingyi222666.luaparser.interop.dex.parseDex
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class LspDynamicDexCompletionTest {
    @Test
    fun real_d8_fixture_preserves_separate_field_and_method_index_lists() {
        val result = parseDex(fixture().readBytes())
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val cls = result.classes.single()
        assertEquals("fixture/dynamic/Greeter", cls.binaryName)
        assertEquals(setOf("Z_COUNT", "aLabel"), cls.fields.map { it.name }.toSet())
        assertTrue(cls.fields.single { it.name == "Z_COUNT" }.accessFlags and 8 != 0)
        assertTrue(cls.fields.single { it.name == "aLabel" }.accessFlags and 8 == 0)
        assertEquals(setOf("<init>", "create", "libraryMarker", "greet"), cls.methods.map { it.name }.toSet())
        assertTrue(cls.methods.single { it.name == "create" }.accessFlags and 8 != 0)
        assertTrue(cls.methods.single { it.name == "greet" }.accessFlags and 8 == 0)
    }

    @Test
    fun cold_live_table_preserves_dynamic_dex_factory_return_type() = checkFactoryReturn(true)

    @Test
    fun synchronous_table_preserves_dynamic_dex_factory_return_type() = checkFactoryReturn(false)

    @Test
    fun cold_live_library_catalog_hydrates_selected_class_factory() = checkFactoryReturn(true, catalog = true)

    private fun checkFactoryReturn(deferred: Boolean, catalog: Boolean = false) {
        val library = fixture()
        check(library.isFile)
        val service = LuaLanguageService(deferDocumentAnalysis = deferred)
        val documents = LuaTextDocumentService(service)
        val uri = "file:///workspace/dynamic.lua"
        val source = (if (catalog) "local catalog=import('${library.path}')\nlocal Greeter=catalog.Greeter\n"
            else "import '${library.path}'\nlocal Greeter=luajava.bindClass('fixture.dynamic.Greeter')\n") +
            "local holder={value=Greeter.create()}\nholder.value:"
        try {
            documents.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "lua", 1, source)))
            val labels = documents.completion(CompletionParams(TextDocumentIdentifier(uri), Position(3, 13)))
                .get(15, TimeUnit.SECONDS).right.items.map { it.label }
            assertTrue("greet" in labels, "Java factory result in Lua table lost its members: $labels")
        } finally {
            service.close()
        }
    }

    private fun fixture() = File("android/sample/src/androidTest/assets/runtime-greeter.dex").absoluteFile
}
