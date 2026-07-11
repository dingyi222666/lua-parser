-- Module model for resources/lua/socket/tp.lua.

---@class TpConnection
local TpConnection = {}

---@param ok string|function
---@return integer|nil, string?
function TpConnection:check(ok) end

---@param command string
---@param argument? string
---@return any
function TpConnection:command(command, argument) end

---@param snk Ltn12Sink
---@param pattern? string
---@return any
function TpConnection:sink(snk, pattern) end

---@param data string
---@return any
function TpConnection:send(data) end

---@param pattern? string
---@return string|nil, string?
function TpConnection:receive(pattern) end

---@return integer
function TpConnection:getfd() end

---@return boolean
function TpConnection:dirty() end

---@return LuaSocketClient
function TpConnection:getcontrol() end

---@param source Ltn12Source
---@param step? function
---@return any
function TpConnection:source(source, step) end

---@return any
function TpConnection:close() end

---@class socket.tp
---@field TIMEOUT integer
local tp = {}

---@param host string
---@param port integer
---@param timeout? number
---@param create? function
---@return TpConnection|nil, string?
function tp.connect(host, port, timeout, create) end

return tp
