local json = require "json"
local xml = require "xml"
local base64 = require "base64"
local http = require "http"
local socketUrl = require "socket.url"
local permission = require "permission"
local files = require "file"

-- Call-result locals: names avoid method-needle substrings so occ=1 is the member.
---@type string
local jsonText = json.encode({ ok = true })
---@type string
local plainText = base64.decode(jsonText)
local urlInfo = socketUrl.parse("https://example.test/path")
---@type string
local hostName = urlInfo.host
local node = xml.new("root")
local response = http.get("https://example.test")
local pathOk = files.exists("/sdcard/main.lua")
local permissions = permission.permission

-- Secondary member refs (occ=2+).
local jsonEncodeRef = json.encode
local base64DecodeRef = base64.decode
local urlParseRef = socketUrl.parse
local httpGetRef = http.get
local fileExistsRef = files.exists

return {
    encoded = jsonText,
    decoded = plainText,
    host = hostName,
    node = node,
    response = response,
    exists = pathOk,
    permissions = permissions,
    encode = jsonEncodeRef,
    decode = base64DecodeRef,
    parse = urlParseRef,
    get = httpGetRef,
    existsFn = fileExistsRef,
}
