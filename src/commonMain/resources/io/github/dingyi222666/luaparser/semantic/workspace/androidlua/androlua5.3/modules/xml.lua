-- Module model for resources/lua/xml.lua.

---@class XmlNode: table
---@field tag? string
---@field attr? table<string, string>
---@field [integer] XmlNode|string

---@class xml
local xml = {}

---@param node XmlNode
---@param tag string
---@return XmlNode
function xml.tag(node, tag) end

---@param arg? table
---@return XmlNode
function xml.new(arg) end

---@param node XmlNode
---@param tag string|XmlNode
---@return XmlNode
function xml.append(node, tag) end

---@param node XmlNode
---@param indent? string
---@param tagValue? any
---@return string
function xml.str(node, indent, tagValue) end

---@param node XmlNode
---@param filename string
function xml.save(node, filename) end

---@param node XmlNode
---@param tag string
---@param attributeKey? string
---@param attributeValue? string
---@return XmlNode|nil
function xml.find(node, tag, attributeKey, attributeValue) end

return xml
