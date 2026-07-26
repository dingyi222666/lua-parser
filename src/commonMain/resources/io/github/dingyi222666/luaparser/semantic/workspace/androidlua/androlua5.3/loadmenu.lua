-- Android-Lua loadmenu.lua model.

---@class AndroidMenuItem: JavaObject
---@java-class android.view.MenuItem
local AndroidMenuItem = {}

---@class AndroidMenu: JavaObject
---@java-class android.view.Menu
local AndroidMenu = {}

---@class LuaMenuSpec: table
---@field [1] JavaClass<AndroidMenuItem>|JavaClass<AndroidMenu>|string
---@field id? string
---@field title? string
---@field group? integer
---@field order? integer
---@field icon? string
---@field enabled? boolean
---@field visible? boolean

---@param menu AndroidMenu
---@param spec LuaMenuSpec[]
---@param root? table<string, any>
---@param actionCount? integer
---@return AndroidMenu
local function loadmenu(menu, spec, root, actionCount) end

return loadmenu
