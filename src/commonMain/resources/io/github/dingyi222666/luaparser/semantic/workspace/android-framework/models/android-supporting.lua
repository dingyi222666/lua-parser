-- Supplemental Android framework model for Android-Lua overlays.
-- Provenance: TASK-056 curated for common supporting packages imported next to
-- app/content/view/widget/graphics resources.

---@class android.R: JavaObject
---@field attr android.R.attr
---@field layout android.R.layout
---@field style android.R.style
---@field anim android.R.anim
local AndroidR = {}

---@class android.R.attr: JavaObject
---@field state_pressed integer
---@field state_focused integer
---@field state_selected integer
---@field state_checked integer
---@field textColorPrimary integer
local RAttr = {}

---@class android.R.layout: JavaObject
---@field simple_list_item_1 integer
---@field simple_list_item_2 integer
---@field simple_spinner_item integer
local RLayout = {}

---@class android.R.style: JavaObject
---@field Theme_DeviceDefault integer
---@field Theme_DeviceDefault_Light integer
---@field Theme_DeviceDefault_NoActionBar integer
---@field Theme_DeviceDefault_Light_NoActionBar integer
local RStyle = {}

---@class android.R.anim: JavaObject
---@field fade_in integer
---@field fade_out integer
local RAnim = {}

---@class android.net.Uri: JavaObject
local Uri = {}

---@param uriString string
---@return android.net.Uri
function Uri.parse(uriString) end

---@return string
function Uri:toString() end

---@class android.os.Bundle: JavaObject
local Bundle = {}

---@param key string
---@param value string
function Bundle:putString(key, value) end

---@param key string
---@return string
function Bundle:getString(key) end

---@class android.os.Environment: JavaObject
---@field DIRECTORY_DOWNLOADS string
---@field DIRECTORY_PICTURES string
local Environment = {}

---@return JavaObject
function Environment.getExternalStorageDirectory() end

---@class android.os.Build: JavaObject
---@field MANUFACTURER string
---@field MODEL string
---@field BRAND string
---@field DEVICE string
---@field VERSION android.os.Build.VERSION
---@field VERSION_CODES android.os.Build.VERSION_CODES
local Build = {}

---@class android.os.Build.VERSION: JavaObject
---@field SDK_INT integer
---@field RELEASE string
local BuildVersion = {}

---@class android.os.Build.VERSION_CODES: JavaObject
---@field LOLLIPOP integer
---@field M integer
---@field N integer
---@field O integer
---@field P integer
---@field Q integer
---@field R integer
---@field S integer
---@field TIRAMISU integer
local BuildVersionCodes = {}

---@class android.os.Handler: JavaObject
local Handler = {}

---@param runnable function|JavaObject
---@return boolean
function Handler:post(runnable) end

---@param runnable function|JavaObject
---@param delayMillis integer
---@return boolean
function Handler:postDelayed(runnable, delayMillis) end

---@class android.text.TextUtils: JavaObject
---@field TruncateAt android.text.TextUtils.TruncateAt
local TextUtils = {}

---@param text string
---@return boolean
function TextUtils.isEmpty(text) end

---@class android.text.TextUtils.TruncateAt: JavaObject
---@field END android.text.TextUtils.TruncateAt
---@field MARQUEE android.text.TextUtils.TruncateAt
---@field MIDDLE android.text.TextUtils.TruncateAt
---@field START android.text.TextUtils.TruncateAt
local TruncateAt = {}

---@class android.text.Html: JavaObject
local Html = {}

---@param source string
---@return JavaObject
function Html.fromHtml(source) end

---@class android.util.DisplayMetrics: JavaObject
---@field widthPixels integer
---@field heightPixels integer
---@field density number
---@field scaledDensity number
local DisplayMetrics = {}

---@class android.util.TypedValue: JavaObject
---@field COMPLEX_UNIT_DIP integer
---@field COMPLEX_UNIT_SP integer
local TypedValue = {}

---@param unit integer
---@param value number
---@param metrics android.util.DisplayMetrics
---@return number
function TypedValue.applyDimension(unit, value, metrics) end

---@class android.util.Log: JavaObject
local Log = {}

---@param tag string
---@param message string
---@return integer
function Log.d(tag, message) end

---@param tag string
---@param message string
---@return integer
function Log.e(tag, message) end

return {
    R = AndroidR,
    Uri = Uri,
    Bundle = Bundle,
    Environment = Environment,
    Build = Build,
    TextUtils = TextUtils,
    Html = Html,
    DisplayMetrics = DisplayMetrics,
    TypedValue = TypedValue,
    Log = Log,
}
