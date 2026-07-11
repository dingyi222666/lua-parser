-- Compact android.app framework model for Android-Lua overlays.
-- Provenance: TASK-056 curated from Android SDK Platform 35 class names and
-- Android-Lua activity/dialog usage.

---@class android.app.Application: android.content.Context
local Application = {}

---@class android.app.Activity: android.content.Context
---@field RESULT_OK integer
---@field RESULT_CANCELED integer
---@field RESULT_FIRST_USER integer
---@field ScreenCaptureCallback android.app.Activity.ScreenCaptureCallback
local Activity = {}

---@param layoutResID integer
function Activity:setContentView(layoutResID) end

---@param view android.view.View
function Activity:setContentView(view) end

---@param id integer
---@return android.view.View
function Activity:findViewById(id) end

---@return android.view.Window
function Activity:getWindow() end

---@return android.view.LayoutInflater
function Activity:getLayoutInflater() end

function Activity:finish() end

---@param requestCode integer
---@param resultCode integer
---@param data? android.content.Intent
function Activity:onActivityResult(requestCode, resultCode, data) end

---@class android.app.Activity.ScreenCaptureCallback: JavaObject
local ActivityScreenCaptureCallback = {}

function ActivityScreenCaptureCallback:onScreenCaptured() end

---@class android.app.Service: android.content.Context
---@field START_STICKY integer
---@field START_NOT_STICKY integer
---@field START_REDELIVER_INTENT integer
local Service = {}

function Service:onCreate() end

function Service:onDestroy() end

---@param intent android.content.Intent
---@param flags integer
---@param startId integer
---@return integer
function Service:onStartCommand(intent, flags, startId) end

---@class android.app.Dialog: JavaObject
local Dialog = {}

function Dialog:show() end

function Dialog:dismiss() end

function Dialog:cancel() end

---@param view android.view.View
function Dialog:setContentView(view) end

---@param title string
function Dialog:setTitle(title) end

---@class android.app.AlertDialog: android.app.Dialog
---@field Builder android.app.AlertDialog.Builder
local AlertDialog = {}

---@class android.app.AlertDialog.Builder: JavaObject
local AlertDialogBuilder = {}

---@param context android.content.Context
---@return android.app.AlertDialog.Builder
function AlertDialogBuilder:new(context) end

---@param title string
---@return android.app.AlertDialog.Builder
function AlertDialogBuilder:setTitle(title) end

---@param message string
---@return android.app.AlertDialog.Builder
function AlertDialogBuilder:setMessage(message) end

---@param view android.view.View
---@return android.app.AlertDialog.Builder
function AlertDialogBuilder:setView(view) end

---@param text string
---@param listener? android.content.DialogInterface.OnClickListener|function
---@return android.app.AlertDialog.Builder
function AlertDialogBuilder:setPositiveButton(text, listener) end

---@param text string
---@param listener? android.content.DialogInterface.OnClickListener|function
---@return android.app.AlertDialog.Builder
function AlertDialogBuilder:setNegativeButton(text, listener) end

---@return android.app.AlertDialog
function AlertDialogBuilder:create() end

---@return android.app.AlertDialog
function AlertDialogBuilder:show() end

---@class android.app.ProgressDialog: android.app.AlertDialog
local ProgressDialog = {}

---@param message string
function ProgressDialog:setMessage(message) end

---@class android.app.PendingIntent: JavaObject
---@field FLAG_UPDATE_CURRENT integer
---@field FLAG_IMMUTABLE integer
local PendingIntent = {}

---@param context android.content.Context
---@param requestCode integer
---@param intent android.content.Intent
---@param flags integer
---@return android.app.PendingIntent
function PendingIntent.getActivity(context, requestCode, intent, flags) end

---@class android.app.Notification: JavaObject
---@field Builder android.app.Notification.Builder
---@field Action any
local Notification = {}

---@class android.app.Notification.Builder: JavaObject
local NotificationBuilder = {}

---@param context android.content.Context
---@return android.app.Notification.Builder
function NotificationBuilder:new(context) end

---@param title string
---@return android.app.Notification.Builder
function NotificationBuilder:setContentTitle(title) end

---@param text string
---@return android.app.Notification.Builder
function NotificationBuilder:setContentText(text) end

---@param intent android.app.PendingIntent
---@return android.app.Notification.Builder
function NotificationBuilder:setContentIntent(intent) end

---@return android.app.Notification
function NotificationBuilder:build() end

---@class android.app.NotificationManager: JavaObject
local NotificationManager = {}

---@param id integer
---@param notification android.app.Notification
function NotificationManager:notify(id, notification) end

---@param id integer
function NotificationManager:cancel(id) end

return {
    Application = Application,
    Activity = Activity,
    Service = Service,
    Dialog = Dialog,
    AlertDialog = AlertDialog,
    ProgressDialog = ProgressDialog,
    PendingIntent = PendingIntent,
    Notification = Notification,
    NotificationManager = NotificationManager,
}
