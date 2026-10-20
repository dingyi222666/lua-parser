-- Module model for resources/lua/mbox.lua.

---@class mbox
local mbox = {}

---@param message string
---@return string, string
function mbox.split_message(message) end

---@param headers string
---@return string[]
function mbox.split_headers(headers) end

---@param header string
---@return string, string
function mbox.parse_header(header) end

---@param headers string
---@return table<string, string>
function mbox.parse_headers(headers) end

---@param from string
---@return string, string
function mbox.parse_from(from) end

---@param source string
---@return string[]
function mbox.split_mbox(source) end

---@param source string
---@return table[]
function mbox.parse(source) end

---@param message string
---@return table
function mbox.parse_message(message) end

return mbox
