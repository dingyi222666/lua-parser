require "import"
import { "android.widget.TextView", "android.widget.Button" }

local mapper = lambda (view, index) -> view:getId() + index
local ids = [mapper(button, 1), mapper(label, 2), [3, 4]]

when ready print "ready" else print { state = "blocked" }

switch current do
case 1, 2 then
    print(ids[1])
case 3 then
    continue
default
    print "fallback"
end

return mapper, ids
