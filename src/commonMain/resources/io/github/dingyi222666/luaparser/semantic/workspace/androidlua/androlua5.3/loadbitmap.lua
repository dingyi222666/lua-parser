-- Android-Lua loadbitmap.lua model.
-- TASK-604: primary return is Bitmap-like with hard-locked member surface (getWidth/...).

---@class Bitmap: JavaObject
---@field getWidth fun(): integer
---@field getHeight fun(): integer
---@field getPixel fun(x: integer, y: integer): integer
---@field recycle fun()
---@field isRecycled fun(): boolean
---@field copy fun(config?: any, isMutable?: boolean): Bitmap
---@field compress fun(format: any, quality: integer, stream: any): boolean
---@field getConfig fun(): any
local Bitmap = {}

---@param path string
---@return Bitmap
local function loadbitmap(path) end

return loadbitmap
