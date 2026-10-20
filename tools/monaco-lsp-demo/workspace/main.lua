require "import"
import "android.app.*"
import "android.os.*"
import "android.widget.*"
import "android.view.*"
import "android.graphics.drawable.*"
import "android.support.v7.widget.*"
import "android.support.v7.widget.StaggeredGridLayoutManager"
import "android.text.format.Formatter"
import "android.animation.*"
import "android.view.animation.*"
import "android.content.res.*"
import "android.net.Uri"
import "com.LuaRecyclerAdapter"
import "com.LuaRecyclerHolder"
import "com.AdapterCreator"
import "mods.dingyi"
import "mods.util"
import "adapter.MyLuaAdapter"
import "model.AppListStream"
import "views.MyTabLayout"
import "views.MyPopMenu"
import "android.content.Intent"

--不做了 开源


activity.actionBar.elevation=1
activity.setContentView(loadlayout("layout/main"))
activity.actionBar.hide()

ViewUtil.setStatusBarColor(0xFFF4A7B9)

local adapters={
  MyLuaAdapter(activity,"layout/item"),
  MyLuaAdapter(activity,"layout/item")
}

_taskFinlshFunction=function(v,type)
  local v=luajava.astable(v,true)

  adapters[type]:addAll(v)
  local ids=parent.getPageIds(type)
  ids.progress.visibility=8
  ids.recy.visibility=0
  ids.recy.y=30
  ObjectAnimator.ofFloat(ids.recy,"y",{30,0})
  .setInterpolator(DecelerateInterpolator())
  .setDuration(250)
  .start()
end

local roundDrawable=GradientDrawable()
.setShape(GradientDrawable.RECTANGLE)
.setCornerRadius(16)
.setColor(0xffffffff)
.setStroke(2,0x1f000000)


for k,v in pairs(adapters) do
  v.onBindViewHolder = function(data,holder, position)
    local view=holder.view.getTag()
    holder.view.backgroundDrawable=roundDrawable
    view.parent.onClick=function(v)

      local pop=MyPopMenu:new()

      :addItem("打开应用",function(a)
        xpcall(function()
          activity.startActivity(activity.getPackageManager().getLaunchIntentForPackage(data[position+1].package))
          end,function(e)
          print("打开 "..data[position+1].name.."失败,可能是该应用没有启动类")
        end)
      end)
      :addItem("卸载应用",function(a)
       xpcall(function()
         activity.startActivity(Intent(Intent.ACTION_DELETE,Uri.parse("package:"..data[position+1].package)))
           end,function(e)
          print(e.."卸载 "..data[position+1].name.."失败,可能是该应用为系统应用")
        end)
      end)
      :addItem("提取应用",function(a)
        AppUtil.getAppApk(data[position+1].package)
      end)
      :addItem("提取图标",function(a)
        AppUtil.getAppIcon(data[position+1].package)
      end)
      :addItem("应用信息",function(a)
       --TODO 加载应用信息
      end)
      :show()

      local width,height,offsetx=0,0,0
      local p=v.getLocationOnScreen()
      width,height=p[0],p[1]

      if width>activity.width*0.5 then
        offsetx=ViewUtil.dp2px(-26)
       else
        offsetx=ViewUtil.dp2px(-8)
      end


      if height>activity.height*0.55 then
        pop.showAsDropDown(v,offsetx,-v.height-ViewUtil.dp2px(275))
       else
        pop.showAsDropDown(v,offsetx,0)
      end
    end
    view.parent.foreground=ViewUtil.ripple(ViewUtil.MODE.ROUND)
    view.name.text=data[position+1].name
    view.icon.imageDrawable=activity.getPackageManager().getApplicationInfo(data[position+1].package,0).loadIcon(activity.getPackageManager())
    view.info.text="V"..data[position+1].vesionName.."\n\n"..Formatter.formatFileSize(activity,data[position+1].size)
  end
end

for i=1,2  do
  local k,v=i,parent.getIdsTable()[i]
  v.recy.setLayoutManager(StaggeredGridLayoutManager(2,StaggeredGridLayoutManager.VERTICAL))
  v.recy.setAdapter(adapters[k]())
end

function refresh(t)

  if t then
    luajava.clear(AppListStream.tmp or int{})
    AppListStream.tmp=nil --清空缓存
  end

  thread(function()

    require "import"
    import "model.AppListStream"

    local data=AppListStream
    :mode(AppListStream.MODE.GETNOSYSTEMAPP)
    :bulid()

    activity.runOnUiThread {
      run=lambda _:activity.get("_taskFinlshFunction")(data,1)
    }
  end)

  thread(function()
    require "import"
    import "model.AppListStream"
    local data=AppListStream
    :mode(AppListStream.MODE.GETSYSTEMAPP)
    :bulid()

    activity.runOnUiThread {
      run=lambda _:activity.get("_taskFinlshFunction")(data,2)
    }
  end)

end

function search(text,mode)
  if mode==1 then
    thread(function(t)
      require "import"
      import "model.AppListStream"

      local data=AppListStream
      :mode(AppListStream.MODE.SEARCHNOSYSTEMAPP)

      :search(t)
      :bulid()
      activity.runOnUiThread {
        run=lambda _:activity.get("_taskFinlshFunction")(data,1)
      }
    end,text)
   else
    thread(function(t)
      require "import"
      import "model.AppListStream"
      local data=AppListStream
      :mode(AppListStream.MODE.SEARCHSYSTEMAPP)
      :search(t)
      :bulid()
      activity.runOnUiThread {
        run=lambda _:activity.get("_taskFinlshFunction")(data,2)
      }
    end,text)
  end
end

refresh()

--[[
task(function(t)

  return luajava.astable(t):bulid()
end,AppListStream
:mode(AppListStream.MODE.GETALLAPP)
:search(""),_taskFinlshFunction)
]]

a={
  b={a=3}
}
