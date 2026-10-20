-- Module model for resources/lua/logcat.lua.

---@class logcat
local logcat = {}

---@param menu AndroidMenu
function logcat.onCreateOptionsMenu(menu) end

---@param id integer
---@param item AndroidMenuItem
function logcat.onMenuItemSelected(id, item) end

---@param filter? string
---@return string
function logcat.readlog(filter) end

function logcat.clearlog() end

---@param filter? string
function logcat.show(filter) end

return logcat
