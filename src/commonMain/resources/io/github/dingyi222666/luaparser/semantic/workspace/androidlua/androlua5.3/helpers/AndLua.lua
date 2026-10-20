-- Asset helper model for assets/AndLua.lua.
-- The source primarily installs app-oriented global helper functions, many with
-- non-ASCII names. This declaration models representative behavior as a module
-- table instead of copying that global identifier inventory.

---@class AndLua
local AndLua = {}

---@param text string
function AndLua.setTitle(text) end

---@param layout LuaLayoutSpec|AndroidView
function AndLua.setContentView(layout) end

---@param text any
function AndLua.toast(text) end

---@param view AndroidView
---@param color any
---@param radius any
function AndLua.roundCorner(view, color, radius) end

---@return string
function AndLua.getDeviceId() end

---@return integer
function AndLua.getScreenWidth() end

---@return integer
function AndLua.getScreenHeight() end

---@param packageName string
---@return boolean
function AndLua.isAppInstalled(packageName) end

---@param file string
---@return boolean
function AndLua.fileExists(file) end

---@param file string
function AndLua.createFile(file) end

---@param file string
function AndLua.createDirectory(file) end

---@param file string
---@param text string
function AndLua.writeFile(file, text) end

---@param text string
function AndLua.shareText(text) end

---@param text string
function AndLua.setClipboard(text) end

---@return string
function AndLua.getClipboard() end

-- Non-ASCII runtime helper aliases commonly installed by AndLua.lua.
---@param text any
function AndLua.MD提示(text) end

---@param text string
function AndLua.窗口标题(text) end

---@param layout LuaLayoutSpec|AndroidView|string
function AndLua.载入界面(layout) end

---@param text any
function AndLua.提示(text) end

---@param min? integer
---@param max? integer
---@return integer
function AndLua.随机数(min, max) end

---@param file string
---@param text string
function AndLua.写入文件(file, text) end

---@param file string
---@return boolean
function AndLua.文件是否存在(file) end

return AndLua
