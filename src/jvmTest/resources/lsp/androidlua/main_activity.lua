require "import"

-- Explicit string imports (DocumentFacts + JvmWorkspaceEngine supported form).
-- Keep this fixture free of multi-identifier returns and Emmy ---@type overrides on
-- constructed TextView locals (those drop inherited View member surfaces on the LSP path).
-- Avoid ---@return annotations that currently false-positive checker.function.return.typeMismatch
-- even when the concrete and annotated types are both android.widget.TextView.
import "android.app.Activity"
import "android.content.Context"
import "android.view.View"
import "android.widget.TextView"
import "android.widget.Button"

local function buildTitle(context)
    local title = TextView(context)
    title:setText("Hello Android Lua")
    title:setVisibility(View.VISIBLE)
    return title
end

local function openService(context)
    return context:getSystemService(Context.WINDOW_SERVICE)
end

local function bindListener(view)
    local listener = {
        onClick = function(v)
            return v:getId()
        end,
    }
    view:setOnClickListener(listener)
    return listener
end

local screen = {
    class = TextView,
    id = "titleView",
    text = "Hello",
    onClick = function(v)
        v:setVisibility(View.GONE)
    end,
}

local title = buildTitle(activity)
local service = openService(activity)
local listener = bindListener(title)
local contextStatic = Context.WINDOW_SERVICE

-- Single-value return: multi-identifier retstats leave residual tokens / lua-parse noise
-- on the LSP diagnostics path and can hide provider + symbol surfaces.
return title
