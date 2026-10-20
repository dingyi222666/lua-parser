-- Android-Lua loadlayout.lua model.

---@alias LuaLayoutValue string|number|boolean|function|JavaObject|table

---@class LuaLayoutStyle: table<string, LuaLayoutValue>

---@param layout LuaLayoutSpec|string
---@param root? table<string, any>
---@param group? JavaClass<any>
---@return AndroidView
local function loadlayout(layout, root, group) end

return loadlayout
