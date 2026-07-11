-- Compact android.graphics.drawable framework model for Android-Lua overlays.
-- Provenance: TASK-056 curated from Android SDK Platform 35 class names and
-- Android-Lua GradientDrawable/StateListDrawable helper usage.

---@class android.graphics.drawable.Drawable: JavaObject
---@field Callback any
---@field ConstantState any
local Drawable = {}

---@param alpha integer
function Drawable:setAlpha(alpha) end

---@param color integer|string
function Drawable:setTint(color) end

---@param colorFilter android.graphics.ColorFilter
function Drawable:setColorFilter(colorFilter) end

---@class android.graphics.drawable.BitmapDrawable: android.graphics.drawable.Drawable
local BitmapDrawable = {}

---@param resources android.content.res.Resources
---@param bitmap android.graphics.Bitmap
---@return android.graphics.drawable.BitmapDrawable
function BitmapDrawable:new(resources, bitmap) end

---@class android.graphics.drawable.ColorDrawable: android.graphics.drawable.Drawable
local ColorDrawable = {}

---@param color integer|string
---@return android.graphics.drawable.ColorDrawable
function ColorDrawable:new(color) end

---@class android.graphics.drawable.GradientDrawable: android.graphics.drawable.Drawable
---@field RECTANGLE integer
---@field OVAL integer
---@field LINE integer
---@field RING integer
---@field LINEAR_GRADIENT integer
---@field RADIAL_GRADIENT integer
---@field SWEEP_GRADIENT integer
---@field Orientation android.graphics.drawable.GradientDrawable.Orientation
local GradientDrawable = {}

---@return android.graphics.drawable.GradientDrawable
function GradientDrawable:new() end

---@param orientation android.graphics.drawable.GradientDrawable.Orientation
---@param colors integer[]
---@return android.graphics.drawable.GradientDrawable
function GradientDrawable:new(orientation, colors) end

---@param shape integer
function GradientDrawable:setShape(shape) end

---@param color integer|string
function GradientDrawable:setColor(color) end

---@param radius number
function GradientDrawable:setCornerRadius(radius) end

---@param width integer
---@param color integer|string
function GradientDrawable:setStroke(width, color) end

---@param orientation android.graphics.drawable.GradientDrawable.Orientation
function GradientDrawable:setOrientation(orientation) end

---@param gradientType integer
function GradientDrawable:setGradientType(gradientType) end

---@class android.graphics.drawable.GradientDrawable.Orientation: JavaObject
---@field TOP_BOTTOM android.graphics.drawable.GradientDrawable.Orientation
---@field TR_BL android.graphics.drawable.GradientDrawable.Orientation
---@field RIGHT_LEFT android.graphics.drawable.GradientDrawable.Orientation
---@field BR_TL android.graphics.drawable.GradientDrawable.Orientation
---@field BOTTOM_TOP android.graphics.drawable.GradientDrawable.Orientation
---@field BL_TR android.graphics.drawable.GradientDrawable.Orientation
---@field LEFT_RIGHT android.graphics.drawable.GradientDrawable.Orientation
---@field TL_BR android.graphics.drawable.GradientDrawable.Orientation
local GradientDrawableOrientation = {}

---@class android.graphics.drawable.StateListDrawable: android.graphics.drawable.Drawable
local StateListDrawable = {}

---@param stateSet integer[]
---@param drawable android.graphics.drawable.Drawable
function StateListDrawable:addState(stateSet, drawable) end

---@class android.graphics.drawable.RippleDrawable: android.graphics.drawable.Drawable
local RippleDrawable = {}

---@param color android.content.res.ColorStateList
---@param content android.graphics.drawable.Drawable
---@param mask? android.graphics.drawable.Drawable
---@return android.graphics.drawable.RippleDrawable
function RippleDrawable:new(color, content, mask) end

---@class android.graphics.drawable.LayerDrawable: android.graphics.drawable.Drawable
local LayerDrawable = {}

---@param layers android.graphics.drawable.Drawable[]
---@return android.graphics.drawable.LayerDrawable
function LayerDrawable:new(layers) end

---@class android.graphics.drawable.AnimationDrawable: android.graphics.drawable.Drawable
local AnimationDrawable = {}

---@param frame android.graphics.drawable.Drawable
---@param duration integer
function AnimationDrawable:addFrame(frame, duration) end

function AnimationDrawable:start() end

function AnimationDrawable:stop() end

return {
    Drawable = Drawable,
    BitmapDrawable = BitmapDrawable,
    ColorDrawable = ColorDrawable,
    GradientDrawable = GradientDrawable,
    StateListDrawable = StateListDrawable,
    RippleDrawable = RippleDrawable,
    LayerDrawable = LayerDrawable,
    AnimationDrawable = AnimationDrawable,
}
