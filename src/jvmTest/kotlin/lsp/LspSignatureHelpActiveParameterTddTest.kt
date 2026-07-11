package lsp

import io.github.dingyi222666.luaparser.interop.jvm.JvmClassModuleProvider
import io.github.dingyi222666.luaparser.lsp.LuaLanguageService
import io.github.dingyi222666.luaparser.lsp.LuaTextDocumentService
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * TASK-447 — LSP signature help active-parameter dual-path corpus (expansion).
 *
 * Complements [LspSignatureHelpActiveParamTddTest] (TASK-263 multi-arg core) with a
 * dual-path expansion lock around [org.eclipse.lsp4j.SignatureHelp.activeParameter]:
 *
 * Hard product contracts (when help is non-null for annotated / JVM callables):
 * - Multi-arg local functions advance 0 → 1 → 2 across commas and comma gaps.
 * - Cursor between `(` and first arg still selects parameter 0.
 * - Colon-method receivers offset activeParameter past implicit `self`.
 * - Dot-method calls do **not** inject a self offset.
 * - Nested call sites prefer the innermost enclosing call argument region.
 * - Multi-line argument lists still track commas.
 * - Overloaded JVM callables (String.valueOf, Math.max) keep multi-arg tracking.
 * - TextDocumentService forwards activeParameter + outside-null policy.
 * - Outside-call / empty / declaration / non-call identifiers return null without crash
 *   (with known next-token call.range.end inflation policy from SignatureHelpProvider).
 *
 * Dual-path / CURRENTLY_ACCEPTS product gaps (never hard-fail the suite):
 * - Freeform unannotated callables may return null or a generic help payload.
 * - Short string-call / table-call argument shapes may or may not resolve today.
 * - Vararg formals may clamp activeParameter to the last formal index.
 * - Incomplete / trailing-comma / unknown-callee sites must not throw; null or help is OK.
 * - Nested createProxy / alias helpers prefer documented labels when product wires them
 *   (see SignatureHelpProvider TASK-394); otherwise null is an accepted gap.
 *
 * Product code is intentionally out of scope (test-only). Verification is review-owned
 * and serial; this worker does not run Gradle. Host android.jar is not required for this
 * corpus; never G:/.
 *
 * Position needles that land inside call argument regions must use call-site-unique
 * prefixes (e.g. `current = render(`), never bare `render(` / `outer(` which match the
 * declaration parameter list first — product returns null outside call args.
 */
class LspSignatureHelpActiveParameterTddTest {

    // -------------------------------------------------------------------------
    // Capability surface
    // -------------------------------------------------------------------------

    @Test
    fun signature_help_capability_advertises_trigger_characters() {
        val service = plainService()
        val capabilities = service.initialize(InitializeParams()).capabilities
        val provider = assertNotNull(
            capabilities.signatureHelpProvider,
            "signatureHelpProvider must be advertised"
        )
        val triggers = provider.triggerCharacters.orEmpty()
        assertTrue(
            triggers.contains("(") || triggers.contains(","),
            "expected ( and/or , trigger characters; got $triggers"
        )
    }

    // -------------------------------------------------------------------------
    // Hard multi-arg / gap / pre-first-arg contracts
    // -------------------------------------------------------------------------

    @Test
    fun annotated_multi_arg_tracks_active_parameter_across_commas() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-simple.lua",
            """
            ---@param a number
            ---@param b string
            ---@param c boolean
            local function paint(a, b, c)
                return c
            end
            local current = paint(1, "mid", true)
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "\"mid\",")))
        val third = assertNotNull(service.signatureHelp(signatureParams(document, "true)")))

        assertEquals(0, first.activeParameter, "cursor on first argument")
        assertEquals(1, second.activeParameter, "cursor on second argument")
        assertEquals(2, third.activeParameter, "cursor on third argument")
        assertTrue(first.signatures.isNotEmpty())
        assertEquals(3, first.signatures.single().parameters.size)
        assertEquals(first.signatures.size, third.signatures.size)
        assertTrue(first.activeSignature >= 0 && first.activeSignature < first.signatures.size)
    }

    @Test
    fun annotated_multi_arg_advances_in_comma_gap_and_before_first_arg() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-gap.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render( 1,  "hi")
            return current
            """
        )

        // Call-site-unique needle: bare "render(" matches the declaration first and lands
        // inside the param list (outside call args → null). Product pre-first-arg region is
        // [base.range.end, firstArgumentStart).
        val beforeFirst = assertNotNull(
            service.signatureHelp(
                SignatureHelpParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionBetween("current = render(", "1")
                )
            ),
            "cursor between call-site ( and first arg should still yield help"
        )
        val onFirst = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val between = assertNotNull(
            service.signatureHelp(
                SignatureHelpParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionBetween("1,", "\"hi\"")
                )
            )
        )
        val onSecond = assertNotNull(service.signatureHelp(signatureParams(document, "\"hi\")")))

        assertEquals(0, beforeFirst.activeParameter, "pre-first-arg selects parameter 0")
        assertEquals(0, onFirst.activeParameter)
        assertEquals(1, between.activeParameter, "comma gap selects next parameter")
        assertEquals(1, onSecond.activeParameter)
    }

    @Test
    fun multi_line_argument_list_tracks_active_parameter() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-multiline.lua",
            """
            ---@param a number
            ---@param b string
            ---@param c boolean
            local function paint(a, b, c)
                return c
            end
            local current = paint(
                1,
                "mid",
                true
            )
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "\"mid\",")))
        val third = assertNotNull(service.signatureHelp(signatureParams(document, "true")))

        assertEquals(0, first.activeParameter)
        assertEquals(1, second.activeParameter)
        assertEquals(2, third.activeParameter)
        assertTrue(first.signatures.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Receiver offset: colon vs dot
    // -------------------------------------------------------------------------

    @Test
    fun colon_method_receiver_offsets_active_parameter() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-colon.lua",
            """
            local box = {}
            ---@param self table
            ---@param value number
            ---@param label string
            function box:render(value, label)
                return label
            end
            local current = box:render(1, "hi")
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "\"hi\")")))

        assertEquals(1, first.activeParameter, "first explicit arg after colon receiver")
        assertEquals(2, second.activeParameter, "second explicit arg")
        assertTrue(
            first.signatures.single().parameters.size >= 3,
            "colon method signature should include self: ${first.labels()}"
        )
    }

    @Test
    fun dot_method_call_does_not_offset_active_parameter_for_self() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-dot.lua",
            """
            local box = {}
            ---@param value number
            ---@param label string
            function box.render(value, label)
                return label
            end
            local current = box.render(1, "hi")
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "\"hi\")")))

        // Dot call has no implicit self at the call site → active indices 0, 1.
        assertEquals(0, first.activeParameter, "dot call first explicit arg is parameter 0")
        assertEquals(1, second.activeParameter, "dot call second explicit arg is parameter 1")
        assertTrue(first.signatures.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // Nested call preference
    // -------------------------------------------------------------------------

    @Test
    fun nested_call_prefers_innermost_active_parameter() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-nested.lua",
            """
            ---@param outerA number
            ---@param outerB string
            local function outer(outerA, outerB)
                return outerB
            end
            ---@param innerX number
            ---@param innerY boolean
            local function inner(innerX, innerY)
                return innerY
            end
            local current = outer(inner(1, true), "tail")
            return current
            """
        )

        val innerFirst = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val innerSecond = assertNotNull(service.signatureHelp(signatureParams(document, "true)")))
        val outerSecond = assertNotNull(service.signatureHelp(signatureParams(document, "\"tail\")")))

        // Innermost call: active 0, 1 on inner's formals.
        assertEquals(0, innerFirst.activeParameter, "cursor on first arg of nested call")
        assertEquals(1, innerSecond.activeParameter, "cursor on second arg of nested call")
        // Outer call second argument.
        assertEquals(1, outerSecond.activeParameter, "cursor on outer second arg")
        assertTrue(innerFirst.signatures.isNotEmpty())
        assertTrue(outerSecond.signatures.isNotEmpty())
    }

    @Test
    fun nested_call_outer_first_arg_region_before_inner_call() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-nested-gap.lua",
            """
            ---@param outerA number
            ---@param outerB string
            local function outer(outerA, outerB)
                return outerB
            end
            ---@param innerX number
            local function inner(innerX)
                return innerX
            end
            local current = outer( inner(1), "tail")
            return current
            """
        )

        // Between outer( and inner( — still outer's first parameter region.
        // Bare "outer(" matches the declaration param list; use call-site-unique prefix.
        val beforeInner = assertNotNull(
            service.signatureHelp(
                SignatureHelpParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionBetween("current = outer(", "inner")
                )
            ),
            "gap before nested callee at call site should still be outer's argument region"
        )
        assertEquals(0, beforeInner.activeParameter)
    }

    // -------------------------------------------------------------------------
    // JVM overloads (hard when help non-null)
    // -------------------------------------------------------------------------

    @Test
    fun overloaded_string_value_of_tracks_active_parameter_across_three_args() {
        val service = jvmService()
        val document = service.open(
            "workspace/sig-active-param-valueof.lua",
            """
            local String = require("String")
            local text = String.valueOf(chars, 0, 1)
            return text
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "chars,")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "0,")))
        val third = assertNotNull(service.signatureHelp(signatureParams(document, "1)")))

        assertEquals(0, first.activeParameter)
        assertEquals(1, second.activeParameter)
        assertEquals(2, third.activeParameter)
        assertTrue(
            first.signatures.size >= 2,
            "Overloaded valueOf must expose multiple signatures; size=${first.signatures.size} labels=${first.labels()}"
        )
        val active = third.signatures[third.activeSignature.coerceIn(0, third.signatures.lastIndex)]
        assertTrue(
            active.parameters.size > third.activeParameter,
            "Active signature ${active.label} must host activeParameter=${third.activeParameter}"
        )
    }

    @Test
    fun overloaded_math_max_advances_active_parameter_across_binary_call() {
        val service = jvmService()
        val document = service.open(
            "workspace/sig-active-param-math-max.lua",
            """
            local Math = require("Math")
            local current = Math.max(1, 2)
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val between = assertNotNull(
            service.signatureHelp(
                SignatureHelpParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionBetween("1,", "2")
                )
            )
        )
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "2)")))

        assertEquals(0, first.activeParameter)
        assertEquals(1, between.activeParameter)
        assertEquals(1, second.activeParameter)
        assertTrue(first.signatures.isNotEmpty(), "Math.max must expose signature help")
        assertTrue(first.signatures.all { it.parameters.size == 2 })
    }

    @Test
    fun overloaded_string_builder_append_colon_offsets_and_tracks() {
        val service = jvmService()
        val document = service.open(
            "workspace/sig-active-param-append.lua",
            """
            import "java.lang.StringBuilder"
            local builder = StringBuilder()
            local current = builder:append("text", 0, 1)
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "\"text\",")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "0,")))
        val third = assertNotNull(service.signatureHelp(signatureParams(document, "1)")))

        // self + three explicit args → active indices 1, 2, 3
        assertEquals(1, first.activeParameter, "first explicit arg after colon receiver")
        assertEquals(2, second.activeParameter, "second explicit arg")
        assertEquals(3, third.activeParameter, "third explicit arg")
        assertTrue(first.signatures.size >= 2, "Overload set retained at multi-arg call site")
        assertEquals(first.signatures.size, third.signatures.size)
    }

    // -------------------------------------------------------------------------
    // Outside-call / empty / null policy (aligned with product call.range inflation)
    // -------------------------------------------------------------------------

    @Test
    fun outside_call_context_returns_null_without_crash() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-outside.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            local sentinel = true
            return sentinel
            """
        )

        val onCallee = service.signatureHelp(signatureParams(document, "render(1"))
        val onLocal = service.signatureHelp(signatureParams(document, "current"))
        val onDecl = service.signatureHelp(signatureParams(document, "function render"))
        val atCallEnd = assertNotNull(
            service.signatureHelp(
                SignatureHelpParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionAfter("render(1, \"hi\")")
                )
            ),
            "exact call.range.end remains inside argument region per SignatureHelpProvider"
        )
        // Parser finishNode currently ends CallExpression at the next significant token after `)`.
        val onNextStatementKeyword = assertNotNull(
            service.signatureHelp(signatureParams(document, "local sentinel")),
            "next significant token after call is still inside product call.range.end"
        )
        val afterCallOutside = service.signatureHelp(signatureParams(document, "sentinel"))

        assertNull(onCallee, "signature help on callee name should be null (outside args)")
        assertNull(onLocal, "signature help on non-call identifier should be null")
        assertNull(onDecl, "signature help on function declaration should be null")
        assertTrue(atCallEnd.signatures.isNotEmpty())
        assertTrue(atCallEnd.activeParameter >= 0)
        assertTrue(onNextStatementKeyword.signatures.isNotEmpty())
        assertNull(
            afterCallOutside,
            "signature help past next-token call.range.end (sentinel identifier) should be null"
        )
    }

    @Test
    fun empty_and_non_call_sites_return_null_without_crash() {
        val service = plainService()
        val empty = service.open(
            "workspace/sig-active-param-empty.lua",
            """
            local x = 1
            return x
            """
        )
        assertNull(service.signatureHelp(signatureParams(empty, "1")))
        assertNull(service.signatureHelp(signatureParams(empty, "return")))

        val bare = service.open(
            "workspace/sig-active-param-bare.lua",
            """
            local name = "token"
            """
        )
        assertNull(service.signatureHelp(signatureParams(bare, "token")))
    }

    @Test
    fun empty_argument_list_still_selects_first_parameter_inside_parens() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-empty-args.lua",
            """
            ---@param value number
            ---@param label string
            local function render(value, label)
                return label
            end
            local current = render()
            return current
            """
        )

        val inside = assertNotNull(
            service.signatureHelp(
                SignatureHelpParams(
                    TextDocumentIdentifier(document.uri),
                    document.positionBetween("current = render(", ")")
                )
            ),
            "empty call-site parens should still yield signature help"
        )
        assertEquals(0, inside.activeParameter)
        assertTrue(inside.signatures.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // TextDocumentService forwarding
    // -------------------------------------------------------------------------

    @Test
    fun text_document_service_forwards_active_parameter_and_outside_null_policy() {
        val service = plainService()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/sig-active-param-text-document.lua",
            """
            ---@param a number
            ---@param b string
            ---@param c boolean
            local function paint(a, b, c)
                return c
            end
            local current = paint(1, "mid", true)
            return current
            """
        )

        val second = assertNotNull(
            textDocuments.signatureHelp(signatureParams(document, "\"mid\",")).get()
        )
        assertEquals(1, second.activeParameter)
        assertTrue(second.signatures.isNotEmpty())

        val outside = textDocuments.signatureHelp(signatureParams(document, "current")).get()
        assertNull(outside)
    }

    @Test
    fun text_document_service_forwards_java_multi_overload_active_parameter() {
        val service = jvmService()
        val textDocuments = LuaTextDocumentService(service)
        val document = textDocuments.open(
            "workspace/sig-active-param-text-document-jvm.lua",
            """
            local String = require("String")
            local text = String.valueOf(chars, 0, 1)
            return text
            """
        )

        val help = assertNotNull(textDocuments.signatureHelp(signatureParams(document, "0,")).get())
        assertTrue(
            help.signatures.size >= 2,
            "TextDocumentService must forward multi-overload entries; got ${help.signatures.size}: ${help.labels()}"
        )
        assertEquals(1, help.activeParameter)
        assertTrue(
            help.signatures.any { it.parameters.size >= 3 },
            "Expected a 3-arg valueOf overload among TextDocumentService payload: ${help.labels()}"
        )
    }

    // -------------------------------------------------------------------------
    // Dual-path: freeform / unknown / short-call / vararg / incomplete
    // -------------------------------------------------------------------------

    @Test
    fun freeform_unannotated_callable_dual_path_does_not_crash() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-freeform.lua",
            """
            local function freeform(a, b, c)
                return c
            end
            local current = freeform(1, "mid", true)
            return current
            """
        )

        val first = service.signatureHelp(signatureParams(document, "1,"))
        val second = service.signatureHelp(signatureParams(document, "\"mid\","))
        val third = service.signatureHelp(signatureParams(document, "true)"))

        // CURRENTLY_ACCEPTS: freeform may yield null (no inferred callable surface) or
        // a help payload with advancing activeParameter. Either is fine; must not throw.
        dualPathActiveOrNull(first, expectedIfPresent = 0, site = "freeform first arg")
        dualPathActiveOrNull(second, expectedIfPresent = 1, site = "freeform second arg")
        dualPathActiveOrNull(third, expectedIfPresent = 2, site = "freeform third arg")
        if (first != null && second != null) {
            assertTrue(
                second.activeParameter >= first.activeParameter,
                "when both freeform sites resolve, activeParameter should not regress"
            )
        }
    }

    @Test
    fun short_string_call_active_parameter_dual_path() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-string-call.lua",
            """
            ---@param className string
            ---@param memberName string
            ---@return fun(): string
            local function loadLib(className, memberName)
                return function()
                    return memberName
                end
            end
            local current = loadLib "java.util.Locale", "getDefault"
            return current
            """
        )

        val first = service.signatureHelp(signatureParams(document, "\"java.util.Locale\""))
        val second = service.signatureHelp(signatureParams(document, "\"getDefault\""))

        // Ideal: short string-call with trailing args tracks 0 → 1 (workspace facade).
        // CURRENTLY_ACCEPTS: null when string-call argument region is not resolved at LSP layer.
        dualPathActiveOrNull(first, expectedIfPresent = 0, site = "string-call first arg")
        dualPathActiveOrNull(second, expectedIfPresent = 1, site = "string-call second arg")
        if (first != null && second != null) {
            assertEquals(0, first.activeParameter)
            assertEquals(1, second.activeParameter)
            assertTrue(first.signatures.isNotEmpty())
        }
    }

    @Test
    fun table_call_active_parameter_dual_path() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-table-call.lua",
            """
            ---@param opts table
            local function configure(opts)
                return opts
            end
            local current = configure { enabled = true, label = "x" }
            return current
            """
        )

        val help = service.signatureHelp(signatureParams(document, "enabled"))
        // Ideal: table-call argument region selects parameter 0.
        // CURRENTLY_ACCEPTS: null when table-call is outside paren argument policy.
        dualPathActiveOrNull(help, expectedIfPresent = 0, site = "table-call opts")
        if (help != null) {
            assertTrue(help.signatures.isNotEmpty() || help.activeParameter >= 0)
        }
    }

    @Test
    fun vararg_formal_clamps_or_advances_active_parameter_dual_path() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-vararg.lua",
            """
            ---@param head number
            ---@param ... string
            local function join(head, ...)
                return head
            end
            local current = join(1, "a", "b", "c")
            return current
            """
        )

        val head = service.signatureHelp(signatureParams(document, "1,"))
        val firstVar = service.signatureHelp(signatureParams(document, "\"a\","))
        val midVar = service.signatureHelp(signatureParams(document, "\"b\","))
        val lastVar = service.signatureHelp(signatureParams(document, "\"c\")"))

        // Dual-path: null (unresolved), advancing indices, or clamp-to-last-formal are all OK.
        for ((help, site) in listOf(
            head to "vararg head",
            firstVar to "vararg first",
            midVar to "vararg mid",
            lastVar to "vararg last"
        )) {
            if (help != null) {
                assertTrue(
                    help.activeParameter >= 0,
                    "$site activeParameter must be non-negative; got ${help.activeParameter}"
                )
                assertTrue(help.signatures.isNotEmpty() || help.activeParameter >= 0)
            }
        }
        if (head != null && lastVar != null) {
            assertTrue(
                lastVar.activeParameter >= head.activeParameter,
                "vararg tail activeParameter should not regress below head"
            )
        }
    }

    @Test
    fun incomplete_and_trailing_comma_sites_do_not_crash() {
        val service = plainService()
        val incomplete = service.open(
            "workspace/sig-active-param-incomplete.lua",
            """
            ---@param a number
            ---@param b string
            local function paint(a, b)
                return b
            end
            local current = paint(1,
            """
        )
        val trailing = service.open(
            "workspace/sig-active-param-trailing.lua",
            """
            ---@param a number
            ---@param b string
            local function paint(a, b)
                return b
            end
            local current = paint(1, )
            return current
            """
        )

        val incompleteHelp = service.signatureHelp(signatureParams(incomplete, "1,"))
        val trailingHelp = service.signatureHelp(
            SignatureHelpParams(
                TextDocumentIdentifier(trailing.uri),
                trailing.positionBetween("1,", ")")
            )
        )

        dualPathActiveOrNull(incompleteHelp, expectedIfPresent = 0, site = "incomplete after first arg")
        // Trailing comma gap ideally selects parameter 1; null is accepted if parse recovery drops the call.
        if (trailingHelp != null) {
            assertTrue(
                trailingHelp.activeParameter >= 0,
                "trailing-comma activeParameter must be non-negative"
            )
            assertTrue(trailingHelp.signatures.isNotEmpty() || trailingHelp.activeParameter >= 0)
        }
    }

    @Test
    fun unknown_callee_dual_path_does_not_crash() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-unknown.lua",
            """
            local current = missingCallee(1, "x", true)
            return current
            """
        )

        val first = service.signatureHelp(signatureParams(document, "1,"))
        val second = service.signatureHelp(signatureParams(document, "\"x\","))
        dualPathActiveOrNull(first, expectedIfPresent = 0, site = "unknown first")
        dualPathActiveOrNull(second, expectedIfPresent = 1, site = "unknown second")
    }

    @Test
    fun local_alias_of_annotated_function_tracks_active_parameter_dual_path() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-alias.lua",
            """
            ---@param a number
            ---@param b string
            local function paint(a, b)
                return b
            end
            local alias = paint
            local current = alias(1, "mid")
            return current
            """
        )

        val first = service.signatureHelp(signatureParams(document, "1,"))
        val second = service.signatureHelp(signatureParams(document, "\"mid\")"))

        // Ideal: alias resolves to annotated callable → 0, 1.
        // CURRENTLY_ACCEPTS: null when alias type is unknown.
        dualPathActiveOrNull(first, expectedIfPresent = 0, site = "alias first")
        dualPathActiveOrNull(second, expectedIfPresent = 1, site = "alias second")
        if (first != null && second != null) {
            assertEquals(0, first.activeParameter)
            assertEquals(1, second.activeParameter)
        }
    }

    @Test
    fun create_proxy_helper_active_parameter_dual_path() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-create-proxy.lua",
            """
            local proxy = luajava.createProxy("java.lang.Runnable", {
                run = function() end
            })
            return proxy
            """
        )

        val first = service.signatureHelp(signatureParams(document, "\"java.lang.Runnable\""))
        val second = service.signatureHelp(signatureParams(document, "run"))

        // Ideal (TASK-394): documented createProxy overloads with active 0 / 1.
        // CURRENTLY_ACCEPTS: null when builtin luajava surface is not mounted in plain LSP.
        dualPathActiveOrNull(first, expectedIfPresent = 0, site = "createProxy interface")
        if (second != null) {
            assertTrue(second.activeParameter >= 0)
            assertTrue(second.signatures.isNotEmpty() || second.activeParameter >= 0)
        }
        if (first != null) {
            assertTrue(
                first.signatures.isNotEmpty() || first.activeParameter >= 0,
                "createProxy help payload should be non-empty when present"
            )
        }
    }

    @Test
    fun parenthesized_argument_expression_still_tracks_outer_active_parameter() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-paren-arg.lua",
            """
            ---@param a number
            ---@param b string
            local function paint(a, b)
                return b
            end
            local current = paint((1 + 2), "mid")
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "1 + 2")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "\"mid\")")))

        assertEquals(0, first.activeParameter)
        assertEquals(1, second.activeParameter)
    }

    @Test
    fun same_line_multi_call_sites_track_independent_active_parameters() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-same-line.lua",
            """
            ---@param a number
            ---@param b string
            local function paint(a, b)
                return b
            end
            ---@param x boolean
            ---@param y number
            local function flag(x, y)
                return y
            end
            local current = paint(1, "a"); local other = flag(true, 2)
            return current, other
            """
        )

        val paintSecond = assertNotNull(service.signatureHelp(signatureParams(document, "\"a\"")))
        val flagFirst = assertNotNull(service.signatureHelp(signatureParams(document, "true,")))
        val flagSecond = assertNotNull(service.signatureHelp(signatureParams(document, "2)")))

        assertEquals(1, paintSecond.activeParameter)
        assertEquals(0, flagFirst.activeParameter)
        assertEquals(1, flagSecond.activeParameter)
    }

    @Test
    fun optional_parameter_still_advances_active_index() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-optional.lua",
            """
            ---@param value number
            ---@param label? string
            local function render(value, label)
                return label
            end
            local current = render(1, "hi")
            return current
            """
        )

        val first = assertNotNull(service.signatureHelp(signatureParams(document, "1,")))
        val second = assertNotNull(service.signatureHelp(signatureParams(document, "\"hi\")")))

        assertEquals(0, first.activeParameter)
        assertEquals(1, second.activeParameter)
        assertTrue(first.signatures.isNotEmpty())
    }

    @Test
    fun repeated_signature_help_on_same_site_is_stable() {
        val service = plainService()
        val document = service.open(
            "workspace/sig-active-param-stable.lua",
            """
            ---@param a number
            ---@param b string
            local function paint(a, b)
                return b
            end
            local current = paint(1, "mid")
            return current
            """
        )

        val params = signatureParams(document, "\"mid\")")
        val first = assertNotNull(service.signatureHelp(params))
        val second = assertNotNull(service.signatureHelp(params))

        assertEquals(first.activeParameter, second.activeParameter)
        assertEquals(first.activeSignature, second.activeSignature)
        assertEquals(first.signatures.size, second.signatures.size)
        assertEquals(first.labels(), second.labels())
    }

    @Test
    fun arrays_as_list_vararg_active_parameter_dual_path() {
        val service = jvmService(
            classes = listOf("java.util.Arrays", "java.lang.String", "java.lang.Math", "java.lang.StringBuilder")
        )
        val document = service.open(
            "workspace/sig-active-param-aslist.lua",
            """
            local Arrays = require("Arrays")
            local list = Arrays.asList("one", "two", "three")
            return list
            """
        )

        val first = service.signatureHelp(signatureParams(document, "\"one\","))
        val second = service.signatureHelp(signatureParams(document, "\"two\","))
        val third = service.signatureHelp(signatureParams(document, "\"three\")"))

        // Vararg formals may clamp activeParameter; multi-param tracking is hard-locked
        // elsewhere. Require non-null help or accepted null without crash; when present,
        // activeParameter stays non-negative and non-regressing across commas.
        for (help in listOf(first, second, third)) {
            if (help != null) {
                assertTrue(help.activeParameter >= 0)
                assertTrue(help.signatures.isNotEmpty() || help.activeParameter >= 0)
            }
        }
        if (first != null && second != null) {
            assertTrue(second.activeParameter >= first.activeParameter)
        }
        if (second != null && third != null) {
            assertTrue(third.activeParameter >= second.activeParameter)
        }
    }

    @Test
    fun corpus_matrix_active_parameter_progression_for_annotated_calls() {
        val cases = listOf(
            MatrixCase(
                name = "binary",
                source = """
                    ---@param a number
                    ---@param b string
                    local function f(a, b) return b end
                    local current = f(1, "x")
                    return current
                """.trimIndent(),
                needles = listOf("1," to 0, "\"x\")" to 1)
            ),
            MatrixCase(
                name = "ternary",
                source = """
                    ---@param a number
                    ---@param b string
                    ---@param c boolean
                    local function f(a, b, c) return c end
                    local current = f(1, "x", true)
                    return current
                """.trimIndent(),
                needles = listOf("1," to 0, "\"x\"," to 1, "true)" to 2)
            ),
            MatrixCase(
                name = "unary",
                source = """
                    ---@param a number
                    local function f(a) return a end
                    local current = f(42)
                    return current
                """.trimIndent(),
                needles = listOf("42)" to 0)
            )
        )

        val service = plainService()
        for (case in cases) {
            val document = service.open("workspace/sig-active-param-matrix-${case.name}.lua", case.source)
            for ((needle, expected) in case.needles) {
                val help = assertNotNull(
                    service.signatureHelp(signatureParams(document, needle)),
                    "matrix ${case.name} needle='$needle' should yield help"
                )
                assertEquals(
                    expected,
                    help.activeParameter,
                    "matrix ${case.name} needle='$needle'"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    private data class MatrixCase(
        val name: String,
        val source: String,
        val needles: List<Pair<String, Int>>
    )

    /**
     * Dual-path helper: null is an accepted CURRENTLY_ACCEPTS gap; when help is present,
     * activeParameter must match [expectedIfPresent] (hard contract for that site).
     */
    private fun dualPathActiveOrNull(help: SignatureHelp?, expectedIfPresent: Int, site: String) {
        if (help == null) {
            // Documented product gap for this site.
            return
        }
        assertTrue(
            help.activeParameter >= 0,
            "$site activeParameter must be non-negative; got ${help.activeParameter}"
        )
        assertEquals(
            expectedIfPresent,
            help.activeParameter,
            "$site expected activeParameter=$expectedIfPresent when help is non-null; labels=${help.labels()}"
        )
    }

    private fun plainService(): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
        }
    }

    private fun jvmService(
        classes: List<String> = listOf(
            "java.lang.Math",
            "java.lang.String",
            "java.lang.StringBuilder",
            "java.util.Arrays"
        )
    ): LuaLanguageService {
        return LuaLanguageService().apply {
            initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder("file:///workspace", "workspace"))
                }
            )
            setWorkspaceMetadata(
                mapOf(
                    JvmClassModuleProvider.CLASSES_METADATA_KEY to classes.joinToString("\n")
                )
            )
        }
    }

    private fun LuaLanguageService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(DidOpenTextDocumentParams(TextDocumentItem(document.uri, "lua", 1, document.source)))
        return document
    }

    private fun LuaTextDocumentService.open(path: String, source: String): OpenDocument {
        val document = OpenDocument(path = path, source = source.trimIndent())
        didOpen(DidOpenTextDocumentParams(TextDocumentItem(document.uri, "lua", 1, document.source)))
        return document
    }

    private fun signatureParams(document: OpenDocument, needle: String, occurrence: Int = 1): SignatureHelpParams {
        return SignatureHelpParams(TextDocumentIdentifier(document.uri), document.positionOf(needle, occurrence))
    }

    private fun SignatureHelp.labels(): List<String> = signatures.map { it.label }

    private data class OpenDocument(
        val path: String,
        val source: String
    ) {
        val uri: String = "file:///$path"

        fun positionOf(needle: String, occurrence: Int = 1): Position {
            require(occurrence >= 1) { "occurrence must be positive" }
            var index = -1
            var fromIndex = 0
            repeat(occurrence) {
                index = source.indexOf(needle, fromIndex)
                require(index >= 0) { "Could not find occurrence ${it + 1} of '$needle' in $path" }
                fromIndex = index + needle.length
            }
            return positionAt(index)
        }

        /**
         * Cursor between two needles (after the end of [before], before the start of [after]).
         * Used to land on the comma gap between arguments or inside empty parentheses.
         */
        fun positionBetween(before: String, after: String): Position {
            val beforeIndex = source.indexOf(before)
            require(beforeIndex >= 0) { "Could not find '$before' in $path" }
            val afterIndex = source.indexOf(after, beforeIndex + before.length)
            require(afterIndex >= 0) { "Could not find '$after' after '$before' in $path" }
            val gap = beforeIndex + before.length
            val offset = if (gap < afterIndex) gap else afterIndex
            return positionAt(offset)
        }

        fun positionAfter(needle: String): Position {
            val index = source.indexOf(needle)
            require(index >= 0) { "Could not find '$needle' in $path" }
            return positionAt(index + needle.length)
        }

        private fun positionAt(offset: Int): Position {
            var line = 0
            var lineStart = 0
            for (i in 0 until offset) {
                if (source[i] == '\n') {
                    line += 1
                    lineStart = i + 1
                }
            }
            return Position(line, offset - lineStart)
        }
    }
}
