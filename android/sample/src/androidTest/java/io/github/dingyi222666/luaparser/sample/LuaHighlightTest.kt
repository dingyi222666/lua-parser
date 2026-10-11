package io.github.dingyi222666.luaparser.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LuaHighlightTest {
    @Test
    fun editing_multiline_delimiters_recolors_following_lines() {
        val analyzer = LuaHighlightAnalyzer()
        val comment = analyzer.highlight("--[=[\nlocal value = 1\n]=]\nreturn value")!!
        assertEquals(EditorColorScheme.COMMENT.toLong(), style(comment, 1, 0))
        assertEquals(EditorColorScheme.KEYWORD.toLong(), style(comment, 3, 0))

        val edited = analyzer.highlight("-- comment\nlocal value = 1\n-- end\nreturn value")!!
        assertEquals(EditorColorScheme.KEYWORD.toLong(), style(edited, 1, 0))
        assertEquals(EditorColorScheme.LITERAL.toLong(), style(edited, 1, 14))
        val string = analyzer.highlight("-- comment\nlocal value = [=[\nreturn value\n]=]")!!
        assertEquals(EditorColorScheme.LITERAL.toLong(), style(string, 2, 0))
    }

    @Test
    fun appended_identifier_and_crlf_keep_correct_styles_and_line_count() {
        val analyzer = LuaHighlightAnalyzer()
        analyzer.highlight("local value = 1\r\nret")
        val edited = analyzer.highlight("local value = 1\r\nreturn value\r\n")!!
        assertEquals(EditorColorScheme.KEYWORD.toLong(), style(edited, 1, 0))
        assertEquals(EditorColorScheme.TEXT_NORMAL.toLong(), style(edited, 1, 7))
        assertEquals(3, edited.spans.lineCount)
    }

    private fun style(styles: Styles, line: Int, column: Int): Long =
        styles.spans.read().getSpansOnLine(line).last { it.column <= column }.style
}
