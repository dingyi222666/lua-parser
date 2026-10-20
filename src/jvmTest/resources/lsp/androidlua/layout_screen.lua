require "import"

import "android.widget.TextView"
import "android.widget.Button"
import "android.widget.LinearLayout"
import "android.view.View"
import "android.view.View_OnClickListener"

local importedListener = OnClickListener
local ids = {}
-- Explicit key/value fields avoid array-style layout heads that the recovery
-- parser rewrites into incomplete string-key fields under nested braces.
local layout = {
    class = LinearLayout,
    orientation = "vertical",
    message = {
        class = TextView,
        id = "messageText",
        text = "Ready",
        onClick = function(view)
            view:setVisibility(View.VISIBLE)
        end,
    },
    action = {
        class = Button,
        id = "submitButton",
        text = "Send",
    },
}

local root = loadlayout(layout, ids)
-- Explicit locals keep document-symbol expectations deterministic without relying
-- on reflective loadlayout id-table expansion (library-stub surface / TASK-184).
local messageText = ids.messageText
local submitButton = ids.submitButton
local click = {
    onClick = function(view)
        messageText:setText("Clicked")
    end,
}

submitButton:setOnClickListener(click)
-- Single-value return keeps the LSP parse-diagnostics path free of multi-return residuals.
return root
