require "import"
import "android.widget.TextView"
import "android.widget.ImageView"
import "android.widget.LinearLayout"

local layoutIds = {}
local layout = {
    LinearLayout,
    orientation = "vertical",
    id = "root",
    {
        TextView,
        id = "title",
        text = "Android Lua",
    },
    {
        ImageView,
        id = "icon",
    },
}

-- Unique local names avoid substring collisions with android.view.* / id literals.
local rootView = loadlayout(layout, layoutIds)
local titleSetter = layoutIds.title.setText
return rootView, layoutIds, titleSetter
