require "import"
import "android.view.View_OnClickListener"
import {
    "java.io.File",
    "java.util.*",
    "android.widget.TextView"
}

local listener = lambda view -> view:getId()
local ids = [1, 2, listener(button)]

while running do
    if stopped then
        continue
    end
    when ready print(ids) else fallback()
end

switch state do
case 0, 1 then
    print("idle")
case 2 then
    print("busy")
default
    print("other")
end

return ids
