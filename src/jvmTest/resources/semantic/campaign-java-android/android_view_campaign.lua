require "import"
import "android.widget.*"
import "android.view.*"

local TextViewClass = luajava.bindClass("android.widget.TextView")
local textView = TextViewClass(activity)
local setText = textView.setText
local text = textView.getText()

local visibility = View.VISIBLE
local listener = luajava.createProxy("android.view.View.OnClickListener", {})
local listenerCall = listener.onClick

return textView, setText, text, visibility, listenerCall
