-- Module model for resources/lua/smtp.lua.

---@class SmtpMessage
---@field headers? table<string, string>
---@field body? string|Ltn12Source|SmtpMessage[]

---@class SmtpSendRequest
---@field from string
---@field rcpt string|string[]
---@field source? Ltn12Source
---@field user? string
---@field password? string
---@field server? string
---@field port? integer
---@field domain? string

---@class smtp
---@field TIMEOUT integer
---@field SERVER string
---@field PORT integer
---@field DOMAIN string
---@field ZONE string
local smtp = {}

---@param server? string
---@param port? integer
---@param create? function
---@return JavaObject
function smtp.open(server, port, create) end

---@param message SmtpMessage
---@return Ltn12Source
function smtp.message(message) end

---@param request SmtpSendRequest
---@return any
function smtp.send(request) end

return smtp
