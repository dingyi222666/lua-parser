-- Module model for resources/lua/http.lua.

---@class HttpRequest
---@field url? string
---@field sink? Ltn12Sink
---@field source? Ltn12Source
---@field method? string
---@field headers? table<string, string>
---@field proxy? string
---@field redirect? boolean

---@class http
---@field TIMEOUT integer
---@field PORT integer
---@field USERAGENT string
---@field cookie string
---@field header table<string, string>
---@field ua string
local http = {}

---@param host string
---@param port? integer
---@param create? function
---@return JavaObject
function http.open(host, port, create) end

---@param request string|HttpRequest
---@param body? string
---@return string|integer|nil, integer|string?, table<string, string>?, string?
function http.request(request, body) end

---@param url string
---@param data? string|table
---@param cookie? string
---@param userAgent? string
---@param headers? table<string, string>
---@return string, string?, integer?, table<string, string>?
function http.post(url, data, cookie, userAgent, headers) end

---@param url string
---@param data? table
---@param file? string
---@param cookie? string
---@param userAgent? string
---@param headers? table<string, string>
---@return string, string?, integer?, table<string, string>?
function http.upload(url, data, file, cookie, userAgent, headers) end

---@param url string
---@param cookie? string
---@param userAgent? string
---@param headers? table<string, string>
---@return string, string?, integer?, table<string, string>?
function http.get(url, cookie, userAgent, headers) end

---@param url string
---@param path string
---@param cookie? string
---@param userAgent? string
---@param referrer? string
---@param headers? table<string, string>
---@return integer?, table<string, string>?
function http.download(url, path, cookie, userAgent, referrer, headers) end

return http
