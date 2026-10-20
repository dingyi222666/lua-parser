-- Module model for resources/lua/socket/url.lua.

---@class ParsedUrl
---@field scheme? string
---@field authority? string
---@field path? string
---@field params? string
---@field query? string
---@field fragment? string
---@field userinfo? string
---@field user? string
---@field password? string
---@field host? string
---@field port? string

---@class socket.url
---@field _VERSION string
local url = {}

---@param s string
---@return string
function url.escape(s) end

---@param s string
---@return string
function url.unescape(s) end

---@param source string
---@param defaultUrl? ParsedUrl
---@return ParsedUrl|nil, string?
function url.parse(source, defaultUrl) end

---@param parsed ParsedUrl
---@return string
function url.build(parsed) end

---@param base_url string
---@param relative_url string
---@return string
function url.absolute(base_url, relative_url) end

---@param path string
---@return string[]
function url.parse_path(path) end

---@param parsed string[]
---@param unsafe? boolean
---@return string
function url.build_path(parsed, unsafe) end

return url
