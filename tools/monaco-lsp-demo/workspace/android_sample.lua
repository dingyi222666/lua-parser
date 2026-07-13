--- Android-Lua-ish sample (needs jvm.androidJar + imports for full surface).
require "import"
import "android.widget.*"

local function build()
    local tv = TextView()
    local button = Button()
    button.
    -- Member completion / hover when android.jar is configured
    return tv
end

return {
    build = build,
}
