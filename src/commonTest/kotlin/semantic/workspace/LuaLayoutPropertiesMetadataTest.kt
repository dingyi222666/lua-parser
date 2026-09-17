package semantic.workspace

import io.github.dingyi222666.luaparser.semantic.workspace.LuaLayoutPropertiesMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Embedder-facing parser for the `lua.layout.properties` metadata spec — must be resilient to
 * malformed lines and merge duplicate class entries, since a wrong parse silently disables
 * custom-view completions.
 */
class LuaLayoutPropertiesMetadataTest {
    @Test
    fun parses_class_with_details() {
        val result = LuaLayoutPropertiesMetadata.parse(
            mapOf("lua.layout.properties" to "MyBanner: autoScroll|boolean, bannerWidth")
        )
        val entries = result.getValue("MyBanner")
        assertEquals("autoScroll", entries[0].label)
        assertEquals("boolean", entries[0].detail)
        assertEquals("bannerWidth", entries[1].label)
        assertEquals(null, entries[1].detail)
    }

    @Test
    fun merges_duplicate_class_lines() {
        val result = LuaLayoutPropertiesMetadata.parse(
            mapOf(
                "lua.layout.properties" to """
                    MyBanner: autoScroll
                    MyBanner: bannerWidth|dp size
                """.trimIndent()
            )
        )
        val labels = result.getValue("MyBanner").map { it.label }
        assertEquals(listOf("autoScroll", "bannerWidth"), labels)
    }

    @Test
    fun files_fqcn_key_under_simple_name_too() {
        val result = LuaLayoutPropertiesMetadata.parse(
            mapOf("lua.layout.properties" to "com.project.MyBanner: autoScroll")
        )
        assertTrue(result.containsKey("com.project.MyBanner"))
        assertTrue(result.containsKey("MyBanner"))
    }

    @Test
    fun skips_blank_comment_and_malformed_lines() {
        val result = LuaLayoutPropertiesMetadata.parse(
            mapOf(
                "lua.layout.properties" to """
                    # a comment
                    // another comment

                    no-colon line here
                    : orphan props
                    MyView: ok
                """.trimIndent()
            )
        )
        assertEquals(setOf("MyView"), result.keys)
    }

    @Test
    fun empty_metadata_yields_nothing() {
        assertTrue(LuaLayoutPropertiesMetadata.parse(emptyMap()).isEmpty())
        assertTrue(LuaLayoutPropertiesMetadata.parse(mapOf("lua.layout.properties" to "")).isEmpty())
    }
}
