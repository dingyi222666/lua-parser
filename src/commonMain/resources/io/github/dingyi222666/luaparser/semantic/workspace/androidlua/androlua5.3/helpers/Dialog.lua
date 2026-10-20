-- Asset helper model for assets/Dialog.lua.
-- require("Dialog") exports a callable factory table with MyBottomSheetDialog.

---@class MyBottomSheetDialog
---@field layout LuaLayoutSpec|table
local MyBottomSheetDialog = {}

---@param layout LuaLayoutSpec|table
---@return MyBottomSheetDialog
function MyBottomSheetDialog:setLayout(layout) end

---@param value any
---@return MyBottomSheetDialog
function MyBottomSheetDialog:setHeight(value) end

---@param value any
---@return MyBottomSheetDialog
function MyBottomSheetDialog:setWidth(value) end

---@param value any
---@return MyBottomSheetDialog
function MyBottomSheetDialog:setBackground(value) end

---@param value any
---@return MyBottomSheetDialog
function MyBottomSheetDialog:setCornerRadius(value) end

---@param callback function
---@return MyBottomSheetDialog
function MyBottomSheetDialog:setOutsideClick(callback) end

---@return MyBottomSheetDialog
function MyBottomSheetDialog:show() end

---@return MyBottomSheetDialog
function MyBottomSheetDialog:close() end

---@class Dialog
local Dialog = {}

--- Construct a bottom-sheet dialog (colon method keeps SymbolKind.METHOD).
---@return MyBottomSheetDialog
function Dialog:MyBottomSheetDialog() end

return Dialog
