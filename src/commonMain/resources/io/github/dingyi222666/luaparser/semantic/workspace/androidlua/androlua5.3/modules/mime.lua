-- Module model for resources/lua/mime.lua.

---@class mime
---@field encodet table<string, function>
---@field decodet table<string, function>
---@field wrapt table<string, function>
local mime = {}

---@param name string
---@param opt1? any
---@param opt2? any
---@return Ltn12Filter
function mime.encode(name, opt1, opt2) end

---@param name string
---@param opt1? any
---@param opt2? any
---@return Ltn12Filter
function mime.decode(name, opt1, opt2) end

---@param name string
---@param opt1? any
---@param opt2? any
---@return Ltn12Filter
function mime.wrap(name, opt1, opt2) end

---@param marker? string
---@return Ltn12Filter
function mime.normalize(marker) end

---@return Ltn12Filter
function mime.stuff() end

return mime
