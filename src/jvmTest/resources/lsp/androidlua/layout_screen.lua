require "import"

import "android.widget.*"
import "android.view.View"
import "android.view.View_OnClickListener"

local importedListener = OnClickListener
local ids = {}
local layout = {
    LinearLayout,
    orientation = "vertical",
    {
        TextView,
        id = "messageText",
        text = "Ready",
        onClick = function(view)
            view:setVisibility(View.VISIBLE)
        end,
    },
    {
        Button,
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
return root, messageText, submitButton, click, importedListener
