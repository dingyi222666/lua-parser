-- Compact android.graphics framework model for Android-Lua overlays.
-- Provenance: TASK-056 curated from Android SDK Platform 35 class names and
-- Android-Lua drawing/color/drawable helper usage.

---@class android.graphics.Color: JavaObject
---@field BLACK integer
---@field BLUE integer
---@field CYAN integer
---@field DKGRAY integer
---@field GRAY integer
---@field GREEN integer
---@field LTGRAY integer
---@field MAGENTA integer
---@field RED integer
---@field TRANSPARENT integer
---@field WHITE integer
---@field YELLOW integer
local Color = {}

---@param colorString string
---@return integer
function Color.parseColor(colorString) end

---@param alpha integer
---@param red integer
---@param green integer
---@param blue integer
---@return integer
function Color.argb(alpha, red, green, blue) end

---@param red integer
---@param green integer
---@param blue integer
---@return integer
function Color.rgb(red, green, blue) end

---@class android.graphics.Bitmap: JavaObject
---@field Config android.graphics.Bitmap.Config
---@field CompressFormat android.graphics.Bitmap.CompressFormat
local Bitmap = {}

---@return integer
function Bitmap:getWidth() end

---@return integer
function Bitmap:getHeight() end

---@return boolean
function Bitmap:isRecycled() end

function Bitmap:recycle() end

---@class android.graphics.Bitmap.Config: JavaObject
---@field ARGB_8888 android.graphics.Bitmap.Config
---@field RGB_565 android.graphics.Bitmap.Config
local BitmapConfig = {}

---@class android.graphics.Bitmap.CompressFormat: JavaObject
---@field JPEG android.graphics.Bitmap.CompressFormat
---@field PNG android.graphics.Bitmap.CompressFormat
---@field WEBP android.graphics.Bitmap.CompressFormat
local BitmapCompressFormat = {}

---@class android.graphics.BitmapFactory: JavaObject
local BitmapFactory = {}

---@param pathName string
---@return android.graphics.Bitmap
function BitmapFactory.decodeFile(pathName) end

---@param data string
---@param offset integer
---@param length integer
---@return android.graphics.Bitmap
function BitmapFactory.decodeByteArray(data, offset, length) end

---@class android.graphics.Canvas: JavaObject
local Canvas = {}

---@param bitmap android.graphics.Bitmap
---@return android.graphics.Canvas
function Canvas:new(bitmap) end

---@param color integer|string
function Canvas:drawColor(color) end

---@param left number
---@param top number
---@param right number
---@param bottom number
---@param paint android.graphics.Paint
function Canvas:drawRect(left, top, right, bottom, paint) end

---@param cx number
---@param cy number
---@param radius number
---@param paint android.graphics.Paint
function Canvas:drawCircle(cx, cy, radius, paint) end

---@param text string
---@param x number
---@param y number
---@param paint android.graphics.Paint
function Canvas:drawText(text, x, y, paint) end

---@class android.graphics.Paint: JavaObject
---@field ANTI_ALIAS_FLAG integer
---@field Style android.graphics.Paint.Style
---@field Align android.graphics.Paint.Align
---@field Cap any
---@field Join any
local Paint = {}

---@param flags? integer
---@return android.graphics.Paint
function Paint:new(flags) end

---@param color integer|string
function Paint:setColor(color) end

---@param size number
function Paint:setTextSize(size) end

---@param style android.graphics.Paint.Style
function Paint:setStyle(style) end

---@param width number
function Paint:setStrokeWidth(width) end

---@class android.graphics.Paint.Style: JavaObject
---@field FILL android.graphics.Paint.Style
---@field STROKE android.graphics.Paint.Style
---@field FILL_AND_STROKE android.graphics.Paint.Style
local PaintStyle = {}

---@class android.graphics.Paint.Align: JavaObject
---@field LEFT android.graphics.Paint.Align
---@field CENTER android.graphics.Paint.Align
---@field RIGHT android.graphics.Paint.Align
local PaintAlign = {}

---@class android.graphics.Path: JavaObject
---@field Direction any
---@field FillType any
---@field Op any
local Path = {}

function Path:reset() end

---@param x number
---@param y number
function Path:moveTo(x, y) end

---@param x number
---@param y number
function Path:lineTo(x, y) end

function Path:close() end

---@class android.graphics.Rect: JavaObject
---@field left integer
---@field top integer
---@field right integer
---@field bottom integer
local Rect = {}

---@class android.graphics.RectF: JavaObject
---@field left number
---@field top number
---@field right number
---@field bottom number
local RectF = {}

---@class android.graphics.Typeface: JavaObject
---@field DEFAULT android.graphics.Typeface
---@field DEFAULT_BOLD android.graphics.Typeface
---@field MONOSPACE android.graphics.Typeface
---@field SANS_SERIF android.graphics.Typeface
---@field SERIF android.graphics.Typeface
---@field BOLD integer
---@field ITALIC integer
---@field NORMAL integer
local Typeface = {}

---@param familyName string
---@param style integer
---@return android.graphics.Typeface
function Typeface.create(familyName, style) end

---@class android.graphics.PorterDuff: JavaObject
---@field Mode android.graphics.PorterDuff.Mode
local PorterDuff = {}

---@class android.graphics.PorterDuff.Mode: JavaObject
---@field CLEAR android.graphics.PorterDuff.Mode
---@field SRC android.graphics.PorterDuff.Mode
---@field SRC_IN android.graphics.PorterDuff.Mode
---@field SRC_OVER android.graphics.PorterDuff.Mode
---@field DST_OVER android.graphics.PorterDuff.Mode
---@field MULTIPLY android.graphics.PorterDuff.Mode
local PorterDuffMode = {}

---@class android.graphics.PorterDuffColorFilter: JavaObject
local PorterDuffColorFilter = {}

---@param color integer
---@param mode android.graphics.PorterDuff.Mode
---@return android.graphics.PorterDuffColorFilter
function PorterDuffColorFilter:new(color, mode) end

return {
    Color = Color,
    Bitmap = Bitmap,
    BitmapFactory = BitmapFactory,
    Canvas = Canvas,
    Paint = Paint,
    Path = Path,
    Rect = Rect,
    RectF = RectF,
    Typeface = Typeface,
    PorterDuff = PorterDuff,
    PorterDuffColorFilter = PorterDuffColorFilter,
}
