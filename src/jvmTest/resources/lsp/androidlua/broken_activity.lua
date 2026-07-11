require "import"

import "android.widget.TextView"

local title = TextView(activity)
-- Intentionally invalid: missing name after `local` (product-current recovery emits
-- lua-parse for this shape; bare `local broken =\nreturn` recovers silently without
-- a recovery diagnostic in the current parser).
local =
return title
