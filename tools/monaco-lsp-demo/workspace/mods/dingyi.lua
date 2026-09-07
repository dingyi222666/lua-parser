require "import"
import "android.widget.*"
import "android.view.*"


--独家扩展函数 by dingyi
--qq 2960586094




-------string库扩展函数 开始


--转java string
string.toJavaString=function(t)return String(t) end

--基于java string的split
string.split=function(t,s)
  return luajava.astable(t:toJavaString().split(s))
end




-------string库扩展函数 结束


-------table扩展函数 开始

--table映射函数
table.map=function(t,f)
  table.foreach(t,function(k,v)
    t[k]=f(v)
  end)
end


if table.find then else
  table.find=function(t,s)
    for k,v in pairs(t) do
      if k==s then return true end
    end
    return false
  end
end

if table.clone then else
  table.clone=function(t)
    local n={}
    for k,v in pairs(t) do
      if type(v)=="table" then
        n[k]=table.clone(v)
       else
        n[k]=v
      end
    end
    return n
  end
end


table.addObserver=function(old,func)
  local lister=old
  local result={}
  result.getAllData=function()
    return lister
  end
  setmetatable(result,{
    __newindex=function(self,key,value)
      func(key,value)
      lister[key]=value
      rawset(self,key,nil)
    end,
    __index=function(self,key) return lister[key] end
  })
  return result
end

--自定义view系列


ViewUtil={}

ViewUtil.createView=function(t,n)
  local result={}

  local function p(a) return result(a) end

  result.__call=function(self,values)
    if values==nil then return self end
    local view=self.createView()

    self.bulidView(view,self.attrValues)
    return view
  end

  setmetatable(result,result)


  if type(t)=="table" then
    pcall(function()
      result.createView=t.createView
      result.bulidView=t.bulidView
      result.useAttrs=t.useAttrs:split(",")
    end)
  end

  if n then _ENV[n]=p end

  return p

end

ViewUtil.MODE={
  ROUND=0x1f,
  SQUARE=0x2f,
}

ViewUtil.CONST={
  ripple = activity.obtainStyledAttributes({android.R.attr.selectableItemBackgroundBorderless}).getResourceId(0,0),
  ripples = activity.obtainStyledAttributes({android.R.attr.selectableItemBackground}).getResourceId(0,0)
}

if table.const then table.const(ViewUtil.MODE) end

ViewUtil.dp2px=function(dpValue)
  local scale = activity.getResources().getDisplayMetrics().scaledDensity
  return dpValue * scale + 0.5
end


ViewUtil.setViewHeight=function(v,h)
  v.layoutParams=apply(v.layoutParams,function(_ENV)height=h end)
end

ViewUtil.setViewWidth=function(v,w)
  v.layoutParams=apply(v.layoutParams,function(_ENV)width=h end)
end


ViewUtil.loadlayout=function(t,e,p)
  if type(t)=="string" then t=require(t) end

  local tmp=table.clone(t)

  local function setView(s)

    local useAttrs=s[1]().useAttrs
    s[1]().attrValues={}

    for k,v in pairs(s) do
      if table.find(useAttrs,k) then
        s[1]().attrValues[k]=v
        s[k]=nil
      end
    end


  end

  local function foreach(tt)
    if type(tt[1])=="function" then
      setView(tt)
    end
    for k,v in pairs(tt) do
      if type(v)=="table" then
        foreach(v)
      end
    end
  end

  foreach(t)


  return loadlayout(t,e,p)


end



ViewUtil.ripple=function(mode,color)
  switch mode do
   case ViewUtil.MODE.ROUND
    return activity.Resources.getDrawable(ViewUtil.CONST.ripple).setColor(ColorStateList(int[0].class{int{}},int{color or 0x3f000000}))
   default
    return activity.Resources.getDrawable(ViewUtil.CONST.ripples).setColor(ColorStateList(int[0].class{int{}},int{color or 0x3f000000}))
  end

end

ViewUtil.setStatusBarColor=function(color)
  activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS).setStatusBarColor(color);
end

--------FileUtil


FileUtil={}


FileUtil.saveBitmap=function(bitmap,path)
  local FileOutputStream=luajava.bindClass "java.io.FileOutputStream"
  local Bitmap=luajava.bindClass "android.graphics.Bitmap"
  use(FileOutputStream(path..".png"),function(out)
    bitmap.compress(Bitmap.CompressFormat.PNG,100, out)
    out.flush()
    out.close()
    bitmap.recycle()
  end)
end

--------TextUtil


TextUtil={}


function TextUtil.copy(t)


end


-------io流扩展函数


if io.readall else
  io.readall=function(path)
    return io.open(path,"rb"):read("*a")
  end
end

io.exec=function(cmd)
  local p=io.popen(string.format('%s',cmd))
  local s=p:read("*a")
  p:close()
  return s
end

io.ls=function(path)
  local t=io.exec("ls "..path):split("\n")
  table.foreach(t,function(k,v)
    t[k]=path.."/"..v
  end)
  return t
end


-------全局函数   开始



function createTable(f)
  local t={_G=_G}
  setmetatable(t,{__index=_ENV})
  f(t)
  return t
end

function use(input,f)
  f(input)
  pcall(input.close)
end



function apply(v,f)

  if type(v)=="userdata" then
    local result={_G=_G,_ENV=_ENV}

    result.__index=function(self,key)

      return _ENV[key] or v[key]
    end

    result.__newindex=function(self,key,value)
      if pcall(function()return v[key] end) then
        v[key]=value
        rawset(self,key,nil)
       else
        rawset(self,key,value)
      end
    end

    setmetatable(result,result)

    f(result)

    return v
  end
  f(v)
  return v
end


