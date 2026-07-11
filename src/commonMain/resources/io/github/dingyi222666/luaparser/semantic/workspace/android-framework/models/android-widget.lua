-- Compact android.widget framework model for Android-Lua overlays.
-- Provenance: TASK-056 curated from Android SDK Platform 35 class names and
-- Android-Lua layout-table fixtures.

---@class android.widget.TextView: android.view.View
---@field AUTO_SIZE_TEXT_TYPE_NONE integer
---@field AUTO_SIZE_TEXT_TYPE_UNIFORM integer
---@field BufferType android.widget.TextView.BufferType
---@field OnEditorActionListener any
local TextView = {}

---@param text string|number|JavaObject
function TextView:setText(text) end

---@return string
function TextView:getText() end

---@param color integer|string
function TextView:setTextColor(color) end

---@param size number
function TextView:setTextSize(size) end

---@param typeface android.graphics.Typeface
function TextView:setTypeface(typeface) end

---@param gravity integer
function TextView:setGravity(gravity) end

---@param hint string
function TextView:setHint(hint) end

---@param lines integer
function TextView:setLines(lines) end

---@param singleLine boolean
function TextView:setSingleLine(singleLine) end

---@class android.widget.TextView.BufferType: JavaObject
---@field NORMAL android.widget.TextView.BufferType
---@field SPANNABLE android.widget.TextView.BufferType
---@field EDITABLE android.widget.TextView.BufferType
local TextViewBufferType = {}

---@class android.widget.Button: android.widget.TextView
local Button = {}

---@class android.widget.EditText: android.widget.TextView
local EditText = {}

---@return string
function EditText:getText() end

---@class android.widget.ImageView: android.view.View
---@field ScaleType android.widget.ImageView.ScaleType
local ImageView = {}

---@param bitmap android.graphics.Bitmap
function ImageView:setImageBitmap(bitmap) end

---@param drawable android.graphics.drawable.Drawable
function ImageView:setImageDrawable(drawable) end

---@param resId integer
function ImageView:setImageResource(resId) end

---@param scaleType android.widget.ImageView.ScaleType
function ImageView:setScaleType(scaleType) end

---@class android.widget.ImageView.ScaleType: JavaObject
---@field CENTER android.widget.ImageView.ScaleType
---@field CENTER_CROP android.widget.ImageView.ScaleType
---@field CENTER_INSIDE android.widget.ImageView.ScaleType
---@field FIT_CENTER android.widget.ImageView.ScaleType
---@field FIT_XY android.widget.ImageView.ScaleType
local ImageViewScaleType = {}

---@class android.widget.LinearLayout: android.view.ViewGroup
---@field HORIZONTAL integer
---@field VERTICAL integer
---@field LayoutParams android.widget.LinearLayout.LayoutParams
local LinearLayout = {}

---@param orientation integer|string
function LinearLayout:setOrientation(orientation) end

---@param gravity integer
function LinearLayout:setGravity(gravity) end

---@class android.widget.LinearLayout.LayoutParams: android.view.ViewGroup.MarginLayoutParams
---@field weight number
---@field gravity integer
local LinearLayoutLayoutParams = {}

---@param width integer
---@param height integer
---@return android.widget.LinearLayout.LayoutParams
function LinearLayoutLayoutParams:new(width, height) end

---@param width integer
---@param height integer
---@param weight number
---@return android.widget.LinearLayout.LayoutParams
function LinearLayoutLayoutParams:new(width, height, weight) end

---@class android.widget.FrameLayout: android.view.ViewGroup
---@field LayoutParams android.widget.FrameLayout.LayoutParams
local FrameLayout = {}

---@class android.widget.FrameLayout.LayoutParams: android.view.ViewGroup.MarginLayoutParams
---@field gravity integer
local FrameLayoutLayoutParams = {}

---@class android.widget.RelativeLayout: android.view.ViewGroup
---@field LayoutParams android.widget.RelativeLayout.LayoutParams
local RelativeLayout = {}

---@class android.widget.RelativeLayout.LayoutParams: android.view.ViewGroup.MarginLayoutParams
local RelativeLayoutLayoutParams = {}

---@param verb integer
---@param anchor? integer
function RelativeLayoutLayoutParams:addRule(verb, anchor) end

---@class android.widget.Adapter: JavaObject
local Adapter = {}

---@return integer
function Adapter:getCount() end

---@param position integer
---@return any
function Adapter:getItem(position) end

---@class android.widget.ListAdapter: android.widget.Adapter
local ListAdapter = {}

---@class android.widget.ArrayAdapter<T>: JavaObject
local ArrayAdapter = {}

---@param context android.content.Context
---@param resource integer
---@param objects? table
---@return android.widget.ArrayAdapter<T>
function ArrayAdapter:new(context, resource, objects) end

---@param item T
function ArrayAdapter:add(item) end

function ArrayAdapter:clear() end

---@class android.widget.AdapterView<T>: android.view.ViewGroup
---@field OnItemClickListener android.widget.AdapterView.OnItemClickListener
---@field OnItemLongClickListener any
---@field OnItemSelectedListener any
local AdapterView = {}

---@param adapter android.widget.Adapter
function AdapterView:setAdapter(adapter) end

---@param listener android.widget.AdapterView.OnItemClickListener|function|table
function AdapterView:setOnItemClickListener(listener) end

---@class android.widget.AdapterView.OnItemClickListener: JavaObject
local OnItemClickListener = {}

---@param parent android.widget.AdapterView<any>
---@param view android.view.View
---@param position integer
---@param id integer
function OnItemClickListener:onItemClick(parent, view, position, id) end

---@class android.widget.AbsListView: android.widget.AdapterView<any>
local AbsListView = {}

---@class android.widget.ListView: android.widget.AbsListView
local ListView = {}

---@class android.widget.GridView: android.widget.AbsListView
local GridView = {}

---@class android.widget.ScrollView: android.view.ViewGroup
local ScrollView = {}

---@class android.widget.HorizontalScrollView: android.widget.FrameLayout
local HorizontalScrollView = {}

---@class android.widget.Toast: JavaObject
---@field LENGTH_SHORT integer
---@field LENGTH_LONG integer
local Toast = {}

---@param context android.content.Context
---@param text string
---@param duration integer
---@return android.widget.Toast
function Toast.makeText(context, text, duration) end

function Toast:show() end

function Toast:cancel() end

---@param view android.view.View
function Toast:setView(view) end

---@param gravity integer
---@param xOffset integer
---@param yOffset integer
function Toast:setGravity(gravity, xOffset, yOffset) end

---@class android.widget.PopupWindow: JavaObject
local PopupWindow = {}

---@param contentView android.view.View
function PopupWindow:setContentView(contentView) end

---@param anchor android.view.View
function PopupWindow:showAsDropDown(anchor) end

function PopupWindow:dismiss() end

---@class android.widget.CompoundButton: android.widget.Button
local CompoundButton = {}

---@param checked boolean
function CompoundButton:setChecked(checked) end

---@return boolean
function CompoundButton:isChecked() end

---@class android.widget.CheckBox: android.widget.CompoundButton
local CheckBox = {}

---@class android.widget.RadioButton: android.widget.CompoundButton
local RadioButton = {}

---@class android.widget.ProgressBar: android.view.View
local ProgressBar = {}

---@param progress integer
function ProgressBar:setProgress(progress) end

---@class android.widget.SeekBar: android.widget.ProgressBar
local SeekBar = {}

return {
    TextView = TextView,
    Button = Button,
    EditText = EditText,
    ImageView = ImageView,
    LinearLayout = LinearLayout,
    FrameLayout = FrameLayout,
    RelativeLayout = RelativeLayout,
    Adapter = Adapter,
    ListAdapter = ListAdapter,
    ArrayAdapter = ArrayAdapter,
    AdapterView = AdapterView,
    AbsListView = AbsListView,
    ListView = ListView,
    GridView = GridView,
    ScrollView = ScrollView,
    HorizontalScrollView = HorizontalScrollView,
    Toast = Toast,
    PopupWindow = PopupWindow,
    CompoundButton = CompoundButton,
    CheckBox = CheckBox,
    RadioButton = RadioButton,
    ProgressBar = ProgressBar,
    SeekBar = SeekBar,
}
