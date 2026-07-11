-- Module model for resources/lua/ftp.lua.

---@class FtpRequest
---@field host? string
---@field port? integer
---@field user? string
---@field password? string
---@field path? string
---@field command? string
---@field source? Ltn12Source
---@field sink? Ltn12Sink
---@field type? string

---@class ftp
---@field TIMEOUT integer
---@field PORT integer
---@field USER string
---@field PASSWORD string
local ftp = {}

---@param server string
---@param port? integer
---@param create? function
---@return JavaObject
function ftp.open(server, port, create) end

---@param request string|FtpRequest
---@param body? string
---@return any
function ftp.put(request, body) end

---@param request string|FtpRequest
---@return any
function ftp.get(request) end

---@param request FtpRequest
---@return any
function ftp.command(request) end

return ftp
