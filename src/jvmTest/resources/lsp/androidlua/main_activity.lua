require "import"

import {
    "android.app.Activity",
    "android.content.Context",
    "android.view.View",
    "android.widget.TextView",
    "android.widget.Button",
}

---@param context android.content.Context
---@return android.widget.TextView
local function buildTitle(context)
    ---@type android.widget.TextView
    local title = TextView(context)
    title:setText("Hello Android Lua")
    title:setVisibility(View.VISIBLE)
    return title
end

---@param context android.content.Context
local function openService(context)
    return context:getSystemService(Context.WINDOW_SERVICE)
end

---@param view android.view.View
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
    TextView,
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

return title, service, listener, screen, contextStatic
