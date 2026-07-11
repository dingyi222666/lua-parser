-- Android-Lua loadmenu.lua model.
-- TASK-604: primary return is AndroidMenu-like with hard-locked member surface (add/findItem/...).

---@class AndroidMenuItem: JavaObject
---@field getTitle fun(): any
---@field setTitle fun(title: any): AndroidMenuItem
---@field getItemId fun(): integer
---@field setEnabled fun(enabled: boolean): AndroidMenuItem
---@field setVisible fun(visible: boolean): AndroidMenuItem
---@field setIcon fun(icon: any): AndroidMenuItem
---@field isEnabled fun(): boolean
---@field isVisible fun(): boolean
local AndroidMenuItem = {}

---@class AndroidMenu: JavaObject
---@field add fun(...: any): AndroidMenuItem
---@field findItem fun(id: integer): AndroidMenuItem
---@field clear fun()
---@field size fun(): integer
---@field getItem fun(index: integer): AndroidMenuItem
---@field hasVisibleItems fun(): boolean
---@field removeItem fun(id: integer)
---@field setGroupVisible fun(groupId: integer, visible: boolean)
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
