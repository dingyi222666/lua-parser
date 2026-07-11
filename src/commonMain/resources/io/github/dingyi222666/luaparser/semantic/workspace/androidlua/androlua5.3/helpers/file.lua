-- Asset helper model for assets/file.lua.
-- The source is an interactive Android file-picker screen; only reusable helper
-- surfaces are modeled here.

---@class file
local file = {}

---@param path string
---@return boolean
function file.exists(path) end

---@param path string
---@return boolean
function file.isDirectory(path) end

---@param path string
---@return boolean
function file.isFile(path) end

---@param path string
---@return boolean
function file.createFile(path) end

---@param path string
---@return boolean
function file.createDirectory(path) end

---@param path string
---@return boolean
function file.deleteFile(path) end

---@param path string
---@return string[]
function file.getFileList(path) end

---@param path string
---@return JavaObject
function file.loadbitmap(path) end

---@param path string
---@return string|nil
function file.getExtension(path) end

---@param path string
---@return table[]
function file.attrdir(path) end

return file
