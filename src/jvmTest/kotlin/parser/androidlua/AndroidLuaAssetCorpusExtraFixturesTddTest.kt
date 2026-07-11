package parser.androidlua

import io.github.dingyi222666.luaparser.parser.LuaVersion
import io.github.dingyi222666.luaparser.parser.ast.node.AssignmentStatement
import io.github.dingyi222666.luaparser.parser.ast.node.CallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.CallStatement
import io.github.dingyi222666.luaparser.parser.ast.node.ChunkNode
import io.github.dingyi222666.luaparser.parser.ast.node.FunctionDeclaration
import io.github.dingyi222666.luaparser.parser.ast.node.Identifier
import io.github.dingyi222666.luaparser.parser.ast.node.IfStatement
import io.github.dingyi222666.luaparser.parser.ast.node.LocalStatement
import io.github.dingyi222666.luaparser.parser.ast.node.MemberExpression
import io.github.dingyi222666.luaparser.parser.ast.node.StringCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableCallExpression
import io.github.dingyi222666.luaparser.parser.ast.node.TableConstructorExpression
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import parser.parse
import parser.renderShape

/**
 * Additional Android-Lua-like parse fixtures beyond the main asset corpus.
 *
 * Focus: import / luajava / UI stubs / compact string+table calls under
 * AndroLua 5.3 policy. Snippets are distilled from Android-Lua assets
 * (main.lua, main7.lua, main9.lua, main11.lua, main12.lua, main14.lua,
 * loadlayout2.lua, bin.lua, bmob.lua, AndLua.lua, toast.lua) but kept
 * self-contained so this file stays test-only and scope-local.
 *
 * Acceptance (TASK-194):
 * - at least 8 new snippets
 * - parse without throw under AndroLua policy
 */
class AndroidLuaAssetCorpusExtraFixturesTddTest {

    @Test
    fun extraFixtureManifestCoversImportLuajavaUiAndCompactCalls() {
        val fixtures = extraFixtures
        assertTrue(fixtures.size >= 8, "Expected at least 8 extra Android-Lua fixtures, got ${fixtures.size}")
        assertEquals(fixtures.size, fixtures.map { it.id }.distinct().size, "Fixture ids must be unique")

        val tags = fixtures.flatMap { it.tags }.toSet()
        assertTrue("import" in tags, "Must cover import forms")
        assertTrue("luajava" in tags, "Must cover luajava forms")
        assertTrue("ui" in tags, "Must cover UI stub layouts")
        assertTrue("compact" in tags, "Must cover compact string/table calls")

        fixtures.forEach { fixture ->
            assertTrue(fixture.source.isNotBlank(), "${fixture.id} source must not be blank")
            assertTrue(fixture.asset.isNotBlank(), "${fixture.id} must record Android-Lua asset provenance")
            assertTrue(fixture.tags.isNotEmpty(), "${fixture.id} must be tagged")
        }
    }

    @Test
    fun parsesAllExtraFixturesStrictlyUnderAndroLuaPolicyWithoutThrow() {
        val failures = extraFixtures.mapNotNull { fixture ->
            runCatching {
                val chunk = parse(LuaVersion.ANDROLUA_5_3, fixture.source)
                assertTrue(
                    chunk.body.statements.isNotEmpty() || chunk.body.returnStatement != null,
                    "${fixture.id} produced an empty chunk"
                )
                val shape = renderShape(chunk)
                fixture.expectedShapeFragments.forEach { fragment ->
                    assertTrue(
                        fragment in shape,
                        "${fixture.id} shape missing '$fragment': $shape"
                    )
                }
                fixture.asserter?.invoke(chunk)
            }.exceptionOrNull()?.let { error ->
                "${fixture.id} (asset=${fixture.asset} tags=${fixture.tags})\n${error.message}"
            }
        }

        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n\n"))
        }
    }

    @Test
    fun importStringAndTableFormsParseAsCallStatements() {
        val stringImport = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            require "import"
            import "android.app.*"
            import "android.widget.*"
            import "java.io.File"
            """.trimIndent()
        )

        assertEquals(4, stringImport.body.statements.size)
        stringImport.body.statements.forEach { statement ->
            assertIs<CallStatement>(statement)
        }

        val first = assertIs<CallStatement>(stringImport.body.statements[0]).expression
        assertIs<StringCallExpression>(first.base)

        val tableImport = parse(
            LuaVersion.ANDROLUA_5_3,
            """import { "java.io.File", "java.util.*", "android.view.View" }"""
        )
        val tableCall = assertIs<TableCallExpression>(
            assertIs<CallStatement>(tableImport.body.statements.single()).expression.base
        )
        assertEquals("import", assertIs<Identifier>(tableCall.base).name)
        assertEquals(3, assertIs<TableConstructorExpression>(tableCall.arguments.single()).fields.size)
    }

    @Test
    fun luajavaBindClassCompactAndParenFormsParse() {
        // LocalStatement.variables holds RHS expressions; init holds declared names.
        val compact = parse(
            LuaVersion.ANDROLUA_5_3,
            """local Http = luajava.bindClass "com.androlua.Http""""
        )
        val compactLocal = assertIs<LocalStatement>(compact.body.statements.single())
        val compactCall = assertIs<CallExpression>(compactLocal.variables.single())
        assertIs<StringCallExpression>(compactCall.base)
        assertTrue(renderShape(compact).contains("luajava"))

        val paren = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            local TypedValue = luajava.bindClass("android.util.TypedValue")
            local LuaDrawable = luajava.bindClass "com.androlua.LuaDrawable"
            local files = luajava.astable(File(path).listFiles())
            if luajava.instanceof(view, ViewGroup) then
              walk(view)
            end
            """.trimIndent()
        )
        assertTrue(paren.body.statements.size >= 4)
        assertIs<LocalStatement>(paren.body.statements[0])
        assertIs<LocalStatement>(paren.body.statements[1])
        assertIs<LocalStatement>(paren.body.statements[2])
        assertIs<IfStatement>(paren.body.statements[3])
    }

    @Test
    fun uiStubLayoutTablesAndWidgetTableCallsParse() {
        val layout = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            layout7 = {
              LinearLayout;
              layout_width = "fill";
              layout_height = "fill";
              orientation = "vertical";
              {
                TextView;
                text = "标题";
                textColor = "0xFF03A9F4";
              };
              {
                EditText;
                id = "tzbt";
                singleLine = "true";
              };
            }
            activity.setContentView(loadlayout(layout7))
            """.trimIndent()
        )
        val assign = assertIs<AssignmentStatement>(layout.body.statements[0])
        // AssignmentStatement.init = LHS targets; variables = RHS values (legacy naming).
        val table = assertIs<TableConstructorExpression>(assign.variables.single())
        assertTrue(table.fields.size >= 5)
        assertEquals("LinearLayout", assertIs<Identifier>(table.fields[0].value).name)
        assertIs<CallStatement>(layout.body.statements[1])

        val widgetCall = parse(
            LuaVersion.ANDROLUA_5_3,
            """Button { text = "Save", onClick = function(view) save(view) end }"""
        )
        val call = assertIs<CallStatement>(widgetCall.body.statements.single()).expression
        val tableCall = assertIs<TableCallExpression>(call.base)
        assertEquals("Button", assertIs<Identifier>(tableCall.base).name)
    }

    @Test
    fun compactStringAndTableCallContinuationsParseUnderAndroLua() {
        // Multi-arg compact string calls are exercised in expression (return) position,
        // matching CompactCallAstShapeTddTest.
        val loadLib = parse(
            LuaVersion.ANDROLUA_5_3,
            """return luajava.loadLib "java.util.Locale", "getDefault""""
        )
        val loadLibCall = assertIs<CallExpression>(loadLib.body.returnStatement!!.arguments.single())
        assertIs<StringCallExpression>(loadLibCall.base)
        assertEquals(1, loadLibCall.arguments.size)

        val indexedTableCall = parse(
            LuaVersion.ANDROLUA_5_3,
            """items[1] { text = "mutate", enabled = true }"""
        )
        val stmt = assertIs<CallStatement>(indexedTableCall.body.statements.single()).expression
        assertIs<TableCallExpression>(stmt.base)

        val chainedActivity = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            activity.getWindow()
              .addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
              .setStatusBarColor(color)
            """.trimIndent()
        )
        val chain = assertIs<CallStatement>(chainedActivity.body.statements.single()).expression
        assertIs<MemberExpression>(assertIs<CallExpression>(chain).base)
    }

    @Test
    fun compileStringCallAndNestedSecureImportParse() {
        val compileChunk = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            require "import"
            compile "libs/android-support-v4"
            import "android.support.v4.widget.*"
            """.trimIndent()
        )
        assertEquals(3, compileChunk.body.statements.size)
        compileChunk.body.statements.forEach { assertIs<CallStatement>(it) }
        val compileCall = assertIs<CallStatement>(compileChunk.body.statements[1]).expression
        assertIs<StringCallExpression>(compileCall.base)

        val nested = parse(
            LuaVersion.ANDROLUA_5_3,
            """
            function deviceId()
              import "android.provider.Settings${'$'}Secure"
              return Secure.getString(activity.getContentResolver(), Secure.ANDROID_ID)
            end
            """.trimIndent()
        )
        assertIs<FunctionDeclaration>(nested.body.statements.single())
    }

    private data class ExtraFixture(
        val id: String,
        val asset: String,
        val tags: Set<String>,
        val source: String,
        val expectedShapeFragments: List<String> = emptyList(),
        val asserter: ((ChunkNode) -> Unit)? = null,
    )

    private companion object {
        /**
         * Thirteen distilled Android-Lua asset snippets covering the TASK-194 themes.
         * Provenance points at Android-Lua app assets (read-only reference).
         */
        val extraFixtures = listOf(
            ExtraFixture(
                id = "import-bootstrap-main",
                asset = "main.lua / main14.lua",
                tags = setOf("import"),
                source = """
                    require "import"
                    import "android.app.*"
                    import "android.os.*"
                    import "android.widget.*"
                    import "android.view.*"
                    import "java.io.File"
                    import "com.androlua.LuaUtil"
                """.trimIndent(),
                expectedShapeFragments = listOf("import", "StringCall"),
                asserter = { chunk ->
                    assertEquals(7, chunk.body.statements.size)
                    chunk.body.statements.forEach { assertIs<CallStatement>(it) }
                }
            ),
            ExtraFixture(
                id = "import-table-multi-package",
                asset = "main.lua (table import form)",
                tags = setOf("import"),
                source = """
                    import { "java.io.File", "java.util.*", "android.content.Context" }
                    local imported = import { "android.graphics.Color" }
                """.trimIndent(),
                expectedShapeFragments = listOf("import", "TableCall"),
                asserter = { chunk ->
                    assertIs<CallStatement>(chunk.body.statements[0])
                    assertIs<LocalStatement>(chunk.body.statements[1])
                }
            ),
            ExtraFixture(
                id = "import-compile-support-v4",
                asset = "main.lua / main11.lua / main12.lua / bin.lua",
                tags = setOf("import", "compact"),
                source = """
                    require "import"
                    compile "libs/android-support-v4"
                    import "android.support.v4.widget.*"
                    compile "mao"
                    compile "sign"
                """.trimIndent(),
                expectedShapeFragments = listOf("compile", "import", "StringCall"),
                asserter = { chunk ->
                    assertEquals(5, chunk.body.statements.size)
                    chunk.body.statements.forEach { assertIs<CallStatement>(it) }
                }
            ),
            ExtraFixture(
                id = "luajava-bindclass-compact-and-paren",
                asset = "loadlayout2.lua / bmob.lua",
                tags = setOf("luajava", "compact"),
                source = """
                    local new = luajava.new
                    local bindClass = luajava.bindClass
                    local ViewGroup = bindClass("android.view.ViewGroup")
                    local TypedValue = luajava.bindClass("android.util.TypedValue")
                    local LuaDrawable = luajava.bindClass "com.androlua.LuaDrawable"
                    local Http = luajava.bindClass "com.androlua.Http"
                """.trimIndent(),
                expectedShapeFragments = listOf("luajava", "bindClass"),
                asserter = { chunk ->
                    assertEquals(6, chunk.body.statements.size)
                    chunk.body.statements.forEach { assertIs<LocalStatement>(it) }
                }
            ),
            ExtraFixture(
                id = "luajava-astable-instanceof-walk",
                asset = "main9.lua / main12.lua / bin.lua",
                tags = setOf("luajava"),
                source = """
                    local a = luajava.astable(File(packDir).listFiles())
                    local libs = luajava.astable(libs)
                    function walk(v)
                      if luajava.instanceof(v, ViewGroup) then
                        walk(v)
                      elseif luajava.instanceof(v, TextView) then
                        v.setText("ok")
                      end
                    end
                """.trimIndent(),
                expectedShapeFragments = listOf("luajava", "astable", "instanceof"),
                asserter = { chunk ->
                    assertIs<LocalStatement>(chunk.body.statements[0])
                    assertIs<LocalStatement>(chunk.body.statements[1])
                    assertIs<FunctionDeclaration>(chunk.body.statements[2])
                }
            ),
            ExtraFixture(
                id = "ui-linearlayout-textview-edittext-stub",
                asset = "main7.lua",
                tags = setOf("ui"),
                source = """
                    layout7 = {
                      LinearLayout;
                      layout_width = "fill";
                      layout_height = "fill";
                      orientation = "vertical";
                      backgroundColor = color2;
                      {
                        LinearLayout;
                        orientation = "vertical";
                        {
                          TextView;
                          text = "标题";
                          textColor = "0xFF03A9F4";
                          layout_marginLeft = "10dp";
                        };
                        {
                          EditText;
                          id = "tzbt";
                          singleLine = "true";
                          textSize = "15sp";
                        };
                      };
                    }
                """.trimIndent(),
                expectedShapeFragments = listOf("LinearLayout", "TextView", "EditText", "Table"),
                asserter = { chunk ->
                    val assign = assertIs<AssignmentStatement>(chunk.body.statements.single())
                    val table = assertIs<TableConstructorExpression>(assign.variables.single())
                    assertEquals("LinearLayout", assertIs<Identifier>(table.fields[0].value).name)
                }
            ),
            ExtraFixture(
                id = "ui-loadlayout-contentview-and-toast",
                asset = "main.lua / AndLua.lua / toast.lua",
                tags = setOf("ui", "import"),
                source = """
                    require "import"
                    import "android.widget.*"
                    activity.setTheme(R.AndLua5)
                    activity.setContentView(loadlayout(layout))
                    local toast = Toast.makeText(activity, nil, Toast.LENGTH_SHORT)
                    toast.setView(loadlayout(toasts))
                    toast.show()
                """.trimIndent(),
                expectedShapeFragments = listOf("setContentView", "loadlayout", "Toast"),
                asserter = { chunk ->
                    assertTrue(chunk.body.statements.size >= 6)
                    assertIs<CallStatement>(chunk.body.statements[0])
                    assertIs<LocalStatement>(chunk.body.statements[4])
                }
            ),
            ExtraFixture(
                id = "ui-widget-table-call-button-textview",
                asset = "AndroLua UI table-call style (aly-like)",
                tags = setOf("ui", "compact"),
                source = """
                    TextView { text = "Title", textSize = "16sp" }
                    Button {
                      text = "Save";
                      onClick = function(view)
                        save(view)
                      end
                    }
                    CardView {
                      radius = 25;
                      {
                        TextView;
                        id = "mess";
                        text = str;
                      };
                    }
                """.trimIndent(),
                expectedShapeFragments = listOf("TextView", "Button", "CardView", "TableCall"),
                asserter = { chunk ->
                    assertEquals(3, chunk.body.statements.size)
                    chunk.body.statements.forEach { statement ->
                        val call = assertIs<CallStatement>(statement).expression
                        assertIs<TableCallExpression>(call.base)
                    }
                }
            ),
            ExtraFixture(
                id = "ui-toast-cardview-stub",
                asset = "AndLua.lua / toast.lua",
                tags = setOf("ui"),
                source = """
                    toasts = {
                      CardView;
                      id = "toastb";
                      CardElevation = ele;
                      radius = rad;
                      backgroundColor = color;
                      {
                        TextView;
                        layout_margin = "7dp";
                        textSize = "13sp";
                        TextColor = color2;
                        text = str;
                        layout_gravity = "center";
                        id = "mess";
                      };
                    }
                    local toast = Toast.makeText(activity, nil, Toast.LENGTH_SHORT)
                    toast.setView(loadlayout(toasts))
                    toast.show()
                """.trimIndent(),
                expectedShapeFragments = listOf("CardView", "TextView", "Toast", "loadlayout"),
                asserter = { chunk ->
                    assertIs<AssignmentStatement>(chunk.body.statements[0])
                    assertIs<LocalStatement>(chunk.body.statements[1])
                    assertIs<CallStatement>(chunk.body.statements[2])
                    assertIs<CallStatement>(chunk.body.statements[3])
                }
            ),
            ExtraFixture(
                id = "compact-luajava-loadlib-return",
                asset = "compact call forms (luajava.loadLib multi-arg)",
                tags = setOf("compact", "luajava"),
                source = """return luajava.loadLib "java.util.Locale", "getDefault"""",
                expectedShapeFragments = listOf("loadLib", "StringCall"),
                asserter = { chunk ->
                    assertNotNull(chunk.body.returnStatement)
                    val call = assertIs<CallExpression>(chunk.body.returnStatement!!.arguments.single())
                    assertIs<StringCallExpression>(call.base)
                }
            ),
            ExtraFixture(
                id = "compact-statement-table-and-string-calls",
                asset = "compact statement forms",
                tags = setOf("compact", "ui", "import"),
                source = """
                    items[1] { text = "mutate", enabled = true }
                    TextView { text = "Hello" }
                    import "android.app.*"
                    local Http = luajava.bindClass "com.androlua.Http"
                    require "import"
                """.trimIndent(),
                expectedShapeFragments = listOf("TableCall", "StringCall", "import"),
                asserter = { chunk ->
                    assertEquals(5, chunk.body.statements.size)
                    assertIs<CallStatement>(chunk.body.statements[0])
                    assertIs<CallStatement>(chunk.body.statements[1])
                    assertIs<CallStatement>(chunk.body.statements[2])
                    assertIs<LocalStatement>(chunk.body.statements[3])
                    assertIs<CallStatement>(chunk.body.statements[4])
                }
            ),
            ExtraFixture(
                id = "activity-window-chain-and-result-table-call",
                asset = "main7.lua / main14.lua / AndLua.lua",
                tags = setOf("ui", "compact"),
                source = """
                    activity.getWindow()
                      .addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                      .setStatusBarColor(color2)
                    if tonumber(Build.VERSION.SDK) >= 23 then
                      activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
                    end
                    activity.result { true, tzbt.text, tzbq.text, tznr.text }
                """.trimIndent(),
                expectedShapeFragments = listOf("getWindow", "setStatusBarColor", "result"),
                asserter = { chunk ->
                    assertIs<CallStatement>(chunk.body.statements[0])
                    assertIs<IfStatement>(chunk.body.statements[1])
                    val resultCall = assertIs<CallStatement>(chunk.body.statements[2]).expression
                    assertIs<TableCallExpression>(resultCall.base)
                }
            ),
            ExtraFixture(
                id = "import-inner-function-and-luajava-newinstance",
                asset = "AndLua.lua (nested import + luajava)",
                tags = setOf("import", "luajava"),
                source = """
                    function deviceId()
                      import "android.provider.Settings${'$'}Secure"
                      return Secure.getString(activity.getContentResolver(), Secure.ANDROID_ID)
                    end
                    function openFile(path)
                      return luajava.newInstance("java.io.File", path)
                    end
                    function stringArray()
                      return luajava.createArray("java.lang.String", { "a", "b" })
                    end
                """.trimIndent(),
                expectedShapeFragments = listOf("import", "luajava", "newInstance", "createArray"),
                asserter = { chunk ->
                    assertEquals(3, chunk.body.statements.size)
                    chunk.body.statements.forEach { assertIs<FunctionDeclaration>(it) }
                }
            ),
        )
    }
}
