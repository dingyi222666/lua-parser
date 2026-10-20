package io.github.dingyi222666.luaparser.semantic.checker

/**
 * String-literal value domains per AndroLua layout-table property key, mirroring the
 * Android-Lua `loadlayout.lua` runtime converters: the `toint` keyword maps (visibility,
 * orientation, gravity, inputType, imeOptions, autoLink, layerType, scrollbarStyle,
 * layoutDirection, textAlignment, importantForAccessibility, drawingCacheQuality), the
 * RelativeLayout `rules` table (true/false or an id), the ImageView `scaleType` table,
 * `checkType` unit suffixes (dp/sp/px/pt/in/mm), and `checkPercent` %w/%h sizes.
 *
 * Shared by the AST-based completion provider (terminated string literals) and the
 * workspace facade's unterminated-string text fallback.
 */
internal object LuaLayoutValueDomains {
    private val GRAVITY_TOKENS = listOf(
        "left", "top", "right", "bottom", "center", "center_horizontal",
        "center_vertical", "fill", "fill_horizontal", "fill_vertical",
        "clip_horizontal", "clip_vertical", "start", "end", "no_gravity"
    )

    private val SIZE_VALUES = listOf(
        // AndroLua-idiomatic short forms first (loadlayout's toint accepts all aliases).
        "wrap", "fill", "match", "-1", "-2",
        "wrap_content", "fill_parent", "match_parent",
        "8dp", "16dp", "50%w", "50%h", "100%w", "100%h"
    )

    private val DICT: Map<String, List<String>> = buildMap {
        put("layout_width", SIZE_VALUES)
        put("layout_height", SIZE_VALUES)
        put("orientation", listOf("vertical", "horizontal"))
        put("visibility", listOf("visible", "invisible", "gone"))
        put("layout_gravity", GRAVITY_TOKENS)
        put("gravity", GRAVITY_TOKENS)
        put("scaleType", listOf(
            "matrix", "fitXY", "fitStart", "fitCenter", "fitEnd",
            "center", "centerCrop", "centerInside"
        ))
        put("ellipsize", listOf("none", "start", "middle", "end", "marquee"))
        put("autoLink", listOf("none", "web", "email", "phone", "map", "all"))
        put("inputType", listOf(
            "none", "text", "textCapCharacters", "textCapWords", "textCapSentences",
            "textAutoCorrect", "textAutoComplete", "textMultiLine", "textImeMultiLine",
            "textNoSuggestions", "textUri", "textEmailAddress", "textEmailSubject",
            "textShortMessage", "textLongMessage", "textPersonName", "textPostalAddress",
            "textPassword", "textVisiblePassword", "textWebEditText", "textFilter",
            "textPhonetic", "textWebEmailAddress", "textWebPassword", "number",
            "numberSigned", "numberDecimal", "numberPassword", "phone", "datetime",
            "date", "time"
        ))
        put("imeOptions", listOf(
            "normal", "actionUnspecified", "actionNone", "actionGo", "actionSearch",
            "actionSend", "actionNext", "actionDone", "actionPrevious",
            "flagNoFullscreen", "flagNavigatePrevious", "flagNavigateNext",
            "flagNoExtractUi", "flagNoAccessoryAction", "flagForceAscii"
        ))
        put("layerType", listOf("none", "software", "hardware"))
        put("scrollbarStyle", listOf("insideOverlay", "insideInset", "outsideOverlay", "outsideInset"))
        put("layoutDirection", listOf("ltr", "rtl", "inherit", "locale"))
        put("textAlignment", listOf("inherit", "gravity", "textStart", "textEnd", "textCenter", "viewStart", "viewEnd"))
        put("importantForAccessibility", listOf("auto", "yes", "no"))
        put("drawingCacheQuality", listOf("auto", "low", "high"))
        listOf(
            "layout_above", "layout_below", "layout_alignBaseline", "layout_alignBottom",
            "layout_alignEnd", "layout_alignLeft", "layout_alignRight", "layout_alignStart",
            "layout_alignTop", "layout_alignParentBottom", "layout_alignParentEnd",
            "layout_alignParentLeft", "layout_alignParentRight", "layout_alignParentStart",
            "layout_alignParentTop", "layout_alignWithParentIfMissing",
            "layout_centerHorizontal", "layout_centerInParent", "layout_centerVertical",
            "layout_toEndOf", "layout_toLeftOf", "layout_toRightOf", "layout_toStartOf"
        ).forEach { put(it, listOf("true", "false")) }
    }

    fun forKey(key: String): List<String> = DICT[key].orEmpty()
}
