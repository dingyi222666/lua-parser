-- Compact android.view framework model for Android-Lua overlays.
-- Provenance: TASK-056 curated from Android SDK Platform 35 class names and
-- Android-Lua layout/listener/window usage.

---@class android.view.View: JavaObject
---@field VISIBLE integer
---@field INVISIBLE integer
---@field GONE integer
---@field NO_ID integer
---@field FOCUSABLE integer
---@field FOCUSABLE_AUTO integer
---@field SYSTEM_UI_FLAG_FULLSCREEN integer
---@field SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN integer
---@field SYSTEM_UI_FLAG_LIGHT_STATUS_BAR integer
---@field MeasureSpec android.view.View.MeasureSpec
---@field OnClickListener android.view.View.OnClickListener
---@field OnLongClickListener android.view.View.OnLongClickListener
---@field OnTouchListener android.view.View.OnTouchListener
local View = {}

---@return integer
function View.generateViewId() end

---@param context android.content.Context
---@param resource integer
---@param root? android.view.ViewGroup
---@return android.view.View
function View.inflate(context, resource, root) end

---@return integer
function View:getId() end

---@param id integer
function View:setId(id) end

---@return android.content.Context
function View:getContext() end

---@param visibility integer
function View:setVisibility(visibility) end

---@return integer
function View:getVisibility() end

---@param listener android.view.View.OnClickListener|function|table
function View:setOnClickListener(listener) end

---@param listener android.view.View.OnLongClickListener|function|table
function View:setOnLongClickListener(listener) end

---@param listener android.view.View.OnTouchListener|function|table
function View:setOnTouchListener(listener) end

---@return boolean
function View:performClick() end

---@return boolean
function View:performLongClick() end

---@param background android.graphics.drawable.Drawable
function View:setBackground(background) end

---@param background android.graphics.drawable.Drawable
function View:setBackgroundDrawable(background) end

---@param color integer|string
function View:setBackgroundColor(color) end

---@param left integer
---@param top integer
---@param right integer
---@param bottom integer
function View:setPadding(left, top, right, bottom) end

---@param widthSpec integer
---@param heightSpec integer
function View:measure(widthSpec, heightSpec) end

---@return integer
function View:getMeasuredWidth() end

---@return integer
function View:getMeasuredHeight() end

---@class android.view.View.MeasureSpec: JavaObject
---@field UNSPECIFIED integer
---@field EXACTLY integer
---@field AT_MOST integer
local ViewMeasureSpec = {}

---@param size integer
---@param mode integer
---@return integer
function ViewMeasureSpec.makeMeasureSpec(size, mode) end

---@param measureSpec integer
---@return integer
function ViewMeasureSpec.getMode(measureSpec) end

---@param measureSpec integer
---@return integer
function ViewMeasureSpec.getSize(measureSpec) end

---@class android.view.View.OnClickListener: JavaObject
local ViewOnClickListener = {}

---@param v android.view.View
function ViewOnClickListener:onClick(v) end

---@class android.view.View.OnLongClickListener: JavaObject
local ViewOnLongClickListener = {}

---@param v android.view.View
---@return boolean
function ViewOnLongClickListener:onLongClick(v) end

---@class android.view.View.OnTouchListener: JavaObject
local ViewOnTouchListener = {}

---@param v android.view.View
---@param event android.view.MotionEvent
---@return boolean
function ViewOnTouchListener:onTouch(v, event) end

---@class android.view.ViewGroup: android.view.View
---@field LayoutParams android.view.ViewGroup.LayoutParams
---@field MarginLayoutParams android.view.ViewGroup.MarginLayoutParams
local ViewGroup = {}

---@param view android.view.View
function ViewGroup:addView(view) end

---@param view android.view.View
---@param params android.view.ViewGroup.LayoutParams
function ViewGroup:addView(view, params) end

---@param view android.view.View
function ViewGroup:removeView(view) end

function ViewGroup:removeAllViews() end

---@param index integer
---@return android.view.View
function ViewGroup:getChildAt(index) end

---@return integer
function ViewGroup:getChildCount() end

---@class android.view.ViewGroup.LayoutParams: JavaObject
---@field MATCH_PARENT integer
---@field WRAP_CONTENT integer
---@field width integer
---@field height integer
local ViewGroupLayoutParams = {}

---@param width integer
---@param height integer
---@return android.view.ViewGroup.LayoutParams
function ViewGroupLayoutParams:new(width, height) end

---@class android.view.ViewGroup.MarginLayoutParams: android.view.ViewGroup.LayoutParams
---@field leftMargin integer
---@field topMargin integer
---@field rightMargin integer
---@field bottomMargin integer
local MarginLayoutParams = {}

---@param left integer
---@param top integer
---@param right integer
---@param bottom integer
function MarginLayoutParams:setMargins(left, top, right, bottom) end

---@class android.view.Gravity: JavaObject
---@field TOP integer
---@field BOTTOM integer
---@field LEFT integer
---@field RIGHT integer
---@field START integer
---@field END integer
---@field CENTER integer
---@field CENTER_HORIZONTAL integer
---@field CENTER_VERTICAL integer
---@field FILL integer
local Gravity = {}

---@class android.view.Window: JavaObject
---@field FEATURE_NO_TITLE integer
local Window = {}

---@return android.view.View
function Window:getDecorView() end

---@param flags integer
function Window:addFlags(flags) end

---@param flags integer
function Window:clearFlags(flags) end

---@param color integer|string
function Window:setStatusBarColor(color) end

---@param color integer|string
function Window:setNavigationBarColor(color) end

---@class android.view.WindowManager: JavaObject
---@field LayoutParams android.view.WindowManager.LayoutParams
local WindowManager = {}

---@param view android.view.View
---@param params android.view.WindowManager.LayoutParams
function WindowManager:addView(view, params) end

---@param view android.view.View
function WindowManager:removeView(view) end

---@class android.view.WindowManager.LayoutParams: android.view.ViewGroup.LayoutParams
---@field FLAG_NOT_FOCUSABLE integer
---@field FLAG_NOT_TOUCH_MODAL integer
---@field FLAG_KEEP_SCREEN_ON integer
---@field FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS integer
---@field TYPE_APPLICATION integer
---@field TYPE_APPLICATION_OVERLAY integer
---@field gravity integer
---@field x integer
---@field y integer
---@field format integer
local WindowManagerLayoutParams = {}

---@class android.view.MotionEvent: JavaObject
---@field ACTION_DOWN integer
---@field ACTION_UP integer
---@field ACTION_MOVE integer
---@field ACTION_CANCEL integer
local MotionEvent = {}

---@return number
function MotionEvent:getX() end

---@return number
function MotionEvent:getY() end

---@return integer
function MotionEvent:getAction() end

---@class android.view.Menu: JavaObject
local Menu = {}

---@param title string
---@return android.view.MenuItem
function Menu:add(title) end

---@class android.view.MenuItem: JavaObject
local MenuItem = {}

---@return integer
function MenuItem:getItemId() end

---@return string
function MenuItem:getTitle() end

---@param title string
---@return android.view.MenuItem
function MenuItem:setTitle(title) end

---@class android.view.SubMenu: android.view.Menu
local SubMenu = {}

---@class android.view.animation.Animation: JavaObject
---@field INFINITE integer
---@field RESTART integer
---@field REVERSE integer
---@field AnimationListener android.view.animation.Animation.AnimationListener
local Animation = {}

---@param duration integer
function Animation:setDuration(duration) end

---@param repeatCount integer
function Animation:setRepeatCount(repeatCount) end

---@param listener android.view.animation.Animation.AnimationListener|table|function
function Animation:setAnimationListener(listener) end

---@class android.view.animation.Animation.AnimationListener: JavaObject
local AnimationListener = {}

---@param animation android.view.animation.Animation
function AnimationListener:onAnimationStart(animation) end

---@param animation android.view.animation.Animation
function AnimationListener:onAnimationEnd(animation) end

---@param animation android.view.animation.Animation
function AnimationListener:onAnimationRepeat(animation) end

---@class android.view.animation.AlphaAnimation: android.view.animation.Animation
local AlphaAnimation = {}

---@param fromAlpha number
---@param toAlpha number
---@return android.view.animation.AlphaAnimation
function AlphaAnimation:new(fromAlpha, toAlpha) end

---@class android.view.animation.TranslateAnimation: android.view.animation.Animation
local TranslateAnimation = {}

---@param fromXDelta number
---@param toXDelta number
---@param fromYDelta number
---@param toYDelta number
---@return android.view.animation.TranslateAnimation
function TranslateAnimation:new(fromXDelta, toXDelta, fromYDelta, toYDelta) end

return {
    View = View,
    ViewGroup = ViewGroup,
    Gravity = Gravity,
    Window = Window,
    WindowManager = WindowManager,
    MotionEvent = MotionEvent,
    Menu = Menu,
    MenuItem = MenuItem,
    SubMenu = SubMenu,
    Animation = Animation,
    AlphaAnimation = AlphaAnimation,
    TranslateAnimation = TranslateAnimation,
}
