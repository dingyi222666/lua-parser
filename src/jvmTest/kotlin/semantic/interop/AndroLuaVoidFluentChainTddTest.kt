package semantic.interop

import io.github.dingyi222666.luaparser.interop.jvm.JvmWorkspaceEngine
import io.github.dingyi222666.luaparser.parser.LuaParser
import io.github.dingyi222666.luaparser.parser.LuaVersion
import semantic.support.WorkspaceSemanticHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AndroLua luajava fluent chains through void-returning methods.
 *
 * ALua's luajava returns the receiver from void instance methods, so chains continue
 * after them (`animator.setDuration(500).addListener{...}.start()` — addListener returns
 * void, yet `.start()` still runs on-device). Two facts broke the chain before: `f{table}`
 * sugar arrives as a bare CallExpression wrapping the TableCallExpression (the wrapper used
 * to be evaluated as "call the result again"), and void returns recovered through
 * reflection fell through to unknown instead of the receiver.
 */
class AndroLuaVoidFluentChainTddTest {

    @Test
    fun void_method_result_types_as_the_receiver_for_every_argument_form() {
        data class Case(val name: String, val body: String)
        val cases = listOf(
            Case("fluentNonVoid", "local x = animator.setDuration(500)"),
            Case("voidFunctionArg", "local x = animator.addListener(fn2)"),
            Case("voidTableArg", "local x = animator.addListener { }"),
            Case("voidNoArg", "local x = animator.cancel()"),
            Case("voidTableArgChained", "local x = animator.setDuration(500).addListener { }")
        )
        cases.forEach { case ->
            val harness = androluaHarness(
                """
                import "android.view.*"
                import "android.animation.*"
                local animator = ViewAnimationUtils.createCircularReveal(nil, 0, 0, 0, 0)
                local fn2 = function() end
                ${case.body}
                local watch = x
                return watch
                """.trimIndent()
            )
            val main = harness.path("main.lua")
            val hover = assertNotNull(
                harness.queries.hover(main, harness.positionOf("main.lua", "watch")),
                "${case.name}: watch must resolve"
            )
            assertEquals(
                "android.animation.Animator",
                hover.typeInfo?.displayName,
                "${case.name}: void chain must keep the receiver surface"
            )
        }
    }

    @Test
    fun member_completion_offers_animator_methods_after_listener_table_chain() {
        val harness = androluaHarness(
            """
            import "android.view.*"
            import "android.animation.*"
            local animator = ViewAnimationUtils.createCircularReveal(nil, 0, 0, 0, 0)
            animator.setDuration(500)
            .addListener {
              onAnimationEnd = function()
              end
            }
            animator.start()
            """.trimIndent()
        )
        val main = harness.path("main.lua")
        val dot = harness.positionOf("main.lua", ".start()")
        val labels = harness.queries.completions(main, dot).map { it.label }
        assertTrue("start" in labels, "completion after .addListener{...} must offer start; got $labels")
        assertTrue("setDuration" in labels, "completion after .addListener{...} must offer setDuration; got $labels")
    }

    private fun androluaHarness(source: String) = WorkspaceSemanticHarness.build(
        "main.lua" to source,
        standardLibraryOverlayVersion = LuaVersion.ANDROLUA_5_3,
        engine = JvmWorkspaceEngine(
            workspaceParserFactory = { LuaParser(luaVersion = LuaVersion.ANDROLUA_5_3, errorRecovery = false) }
        )
    )
}
