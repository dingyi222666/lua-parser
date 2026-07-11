-- Compact android.content framework model for Android-Lua overlays.
-- Provenance: TASK-056 curated from Android SDK Platform 35 class names and
-- Android-Lua fixtures. This is not an exhaustive android.jar API dump.

---@class android.content.Context: JavaObject
---@field WINDOW_SERVICE string
---@field LAYOUT_INFLATER_SERVICE string
---@field CLIPBOARD_SERVICE string
---@field ACTIVITY_SERVICE string
---@field NOTIFICATION_SERVICE string
---@field INPUT_METHOD_SERVICE string
---@field CONNECTIVITY_SERVICE string
---@field POWER_SERVICE string
---@field VIBRATOR_SERVICE string
---@field MODE_PRIVATE integer
---@field MODE_APPEND integer
---@field BindServiceFlags android.content.Context.BindServiceFlags
local Context = {}

---@return android.content.res.Resources
function Context:getResources() end

---@return android.content.res.AssetManager
function Context:getAssets() end

---@param name string
---@return JavaObject
function Context:getSystemService(name) end

---@return string
function Context:getPackageName() end

---@return android.content.pm.PackageManager
function Context:getPackageManager() end

---@param intent android.content.Intent
function Context:startActivity(intent) end

---@param intent android.content.Intent
---@return boolean
function Context:startService(intent) end

---@param name string
---@param mode integer
---@return android.content.SharedPreferences
function Context:getSharedPreferences(name, mode) end

---@class android.content.Context.BindServiceFlags: JavaObject
local ContextBindServiceFlags = {}

---@class android.content.ContextWrapper: android.content.Context
local ContextWrapper = {}

---@return android.content.Context
function ContextWrapper:getBaseContext() end

---@class android.content.Intent: JavaObject
---@field ACTION_VIEW string
---@field ACTION_SEND string
---@field ACTION_MAIN string
---@field ACTION_PICK string
---@field ACTION_GET_CONTENT string
---@field ACTION_DIAL string
---@field ACTION_CALL string
---@field CATEGORY_DEFAULT string
---@field CATEGORY_LAUNCHER string
---@field EXTRA_TEXT string
---@field EXTRA_STREAM string
---@field FLAG_ACTIVITY_NEW_TASK integer
---@field FLAG_ACTIVITY_CLEAR_TOP integer
---@field FLAG_ACTIVITY_SINGLE_TOP integer
---@field FilterComparison android.content.Intent.FilterComparison
---@field ShortcutIconResource android.content.Intent.ShortcutIconResource
local Intent = {}

---@param action? string
---@param uri? android.net.Uri
---@return android.content.Intent
function Intent:new(action, uri) end

---@param action string
---@return android.content.Intent
function Intent:setAction(action) end

---@param category string
---@return android.content.Intent
function Intent:addCategory(category) end

---@param flags integer
---@return android.content.Intent
function Intent:addFlags(flags) end

---@param packageName string
---@param className string
---@return android.content.Intent
function Intent:setClassName(packageName, className) end

---@param uri android.net.Uri
---@return android.content.Intent
function Intent:setData(uri) end

---@param type string
---@return android.content.Intent
function Intent:setType(type) end

---@param name string
---@param value any
---@return android.content.Intent
function Intent:putExtra(name, value) end

---@class android.content.BroadcastReceiver: JavaObject
local BroadcastReceiver = {}

---@param context android.content.Context
---@param intent android.content.Intent
function BroadcastReceiver:onReceive(context, intent) end

---@class android.content.DialogInterface: JavaObject
---@field OnCancelListener any
---@field OnClickListener android.content.DialogInterface.OnClickListener
---@field OnDismissListener any
---@field OnKeyListener any
---@field OnMultiChoiceClickListener any
---@field OnShowListener any
local DialogInterface = {}

function DialogInterface:dismiss() end

function DialogInterface:cancel() end

---@class android.content.DialogInterface.OnClickListener: JavaObject
local DialogInterfaceOnClickListener = {}

---@param dialog android.content.DialogInterface
---@param which integer
function DialogInterfaceOnClickListener:onClick(dialog, which) end

---@class android.content.ServiceConnection: JavaObject
local ServiceConnection = {}

---@param name android.content.ComponentName
---@param service JavaObject
function ServiceConnection:onServiceConnected(name, service) end

---@param name android.content.ComponentName
function ServiceConnection:onServiceDisconnected(name) end

---@class android.content.ComponentName: JavaObject
local ComponentName = {}

---@return string
function ComponentName:getPackageName() end

---@return string
function ComponentName:getClassName() end

---@class android.content.SharedPreferences: JavaObject
---@field Editor android.content.SharedPreferences.Editor
local SharedPreferences = {}

---@param key string
---@param defValue string
---@return string
function SharedPreferences:getString(key, defValue) end

---@param key string
---@param defValue boolean
---@return boolean
function SharedPreferences:getBoolean(key, defValue) end

---@return android.content.SharedPreferences.Editor
function SharedPreferences:edit() end

---@class android.content.SharedPreferences.Editor: JavaObject
local SharedPreferencesEditor = {}

---@param key string
---@param value string
---@return android.content.SharedPreferences.Editor
function SharedPreferencesEditor:putString(key, value) end

---@param key string
---@param value boolean
---@return android.content.SharedPreferences.Editor
function SharedPreferencesEditor:putBoolean(key, value) end

---@return boolean
function SharedPreferencesEditor:commit() end

function SharedPreferencesEditor:apply() end

---@class android.content.pm.PackageManager: JavaObject
---@field GET_ACTIVITIES integer
---@field GET_SERVICES integer
---@field GET_META_DATA integer
---@field PERMISSION_GRANTED integer
---@field PERMISSION_DENIED integer
local PackageManager = {}

---@param packageName string
---@param flags integer
---@return android.content.pm.PackageInfo
function PackageManager:getPackageInfo(packageName, flags) end

---@class android.content.pm.PackageInfo: JavaObject
---@field packageName string
---@field versionName string
---@field versionCode integer
local PackageInfo = {}

---@class android.content.res.Resources: JavaObject
---@field Theme any
local Resources = {}

---@param id integer
---@return string
function Resources:getString(id) end

---@param id integer
---@return integer
function Resources:getColor(id) end

---@return android.util.DisplayMetrics
function Resources:getDisplayMetrics() end

---@class android.content.res.AssetManager: JavaObject
local AssetManager = {}

---@param path string
---@return string[]
function AssetManager:list(path) end

---@class android.content.res.ColorStateList: JavaObject
local ColorStateList = {}

---@param states table
---@param colors integer[]
---@return android.content.res.ColorStateList
function ColorStateList:new(states, colors) end

---@param color integer
---@return android.content.res.ColorStateList
function ColorStateList.valueOf(color) end

return {
    Context = Context,
    ContextWrapper = ContextWrapper,
    Intent = Intent,
    BroadcastReceiver = BroadcastReceiver,
    DialogInterface = DialogInterface,
    ComponentName = ComponentName,
    ServiceConnection = ServiceConnection,
    SharedPreferences = SharedPreferences,
    PackageManager = PackageManager,
    PackageInfo = PackageInfo,
    Resources = Resources,
    AssetManager = AssetManager,
    ColorStateList = ColorStateList,
}
