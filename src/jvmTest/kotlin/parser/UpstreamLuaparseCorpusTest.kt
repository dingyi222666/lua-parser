package parser

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.CommentStatement
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Original upstream sources, independently classified by luaparse in Lua 5.3 mode. */
@RunWith(Parameterized::class)
class UpstreamLuaparseCorpusTest(private val id: String, private val fixture: JsonObject) {
    @Test
    fun matches_upstream_acceptance_and_top_level_statement_kinds() {
        val source = fixture.get("source").asString
        val parser = LuaParser(LuaVersion.LUA_5_3, errorRecovery = false,
            extendedIdentifiers = fixture.get("extendedIdentifiers")?.asBoolean ?: false)
        if (!fixture.get("accept").asBoolean) {
            assertFailsWith<IllegalStateException>("$id should reject: $source") { parser.parse(source) }
            return
        }
        val chunk = parser.parse(source)
        val statements = chunk.body.statements.filterNot { it is CommentStatement }
            .map { it::class.simpleName } + listOfNotNull(chunk.body.returnStatement?.let { "ReturnStatement" })
        assertEquals(fixture.getAsJsonArray("body").map { it.asString }, statements, "$id: $source")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val text = checkNotNull(UpstreamLuaparseCorpusTest::class.java.getResourceAsStream(
                "/parser/upstream/luaparse/cases.json"
            )).bufferedReader().use { it.readText() }
            return JsonParser.parseString(text).asJsonArray.map { element ->
                val fixture = element.asJsonObject
                arrayOf(fixture.get("id").asString, fixture)
            }
        }
    }
}
