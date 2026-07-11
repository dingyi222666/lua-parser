require "import"

import "android.widget.TextView"
import "android.content.Context"

local M = {}

function M.attach(parent)
    local label = TextView(activity)
    label:setText(Context.WINDOW_SERVICE)
    parent:addView(label)
    return label
end

function M.findTitle(root)
    return root:findViewById(1001)
end

return M
