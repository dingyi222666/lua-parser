require "import"
import "android.widget.*"
import "android.view.*"

---@class AppListItem
---@field package string
---@field vesionName string
---@field size number
---@field name string

---@class AppListMode
---@field GETSYSTEMAPP integer
---@field GETALLAPP integer
---@field GETNOSYSTEMAPP integer
---@field SERACHALLAPP integer
---@field SEARCHSYSTEMAPP integer
---@field SEARCHNOSYSTEMAPP integer

---@class AppListReadyBuild
---@field mode? integer
---@field search? string

---@class AppListReadyData
---@field system table<string, AppListItem>
---@field nosystem table<string, AppListItem>
---@field fastfindsystem string[]
---@field fastfindnosystem string[]

---@param self AppListStream
local function readyLoad(self)
  import "android.content.pm.ApplicationInfo"
  local pm = activity.getPackageManager();
  local applist=luajava.astable(pm.getInstalledPackages(0))--获取本机所有安装程序信息table


  local isSystemApp=function(app)
    return bit32.band(app.applicationInfo.flags,ApplicationInfo.FLAG_SYSTEM)~=0
  end


  for i=1,#applist do
    local app=applist[i]--读取table
    local 包名=app.packageName
    local 应用名称=app.applicationInfo.loadLabel(pm)--获取应用名称
    -- 不加载drawable省内存 local 图标=app.applicationInfo.loadIcon(pm) --获取程序图标
    local 版本号 = app.versionName or ""
    local 长度=io.open(app.applicationInfo.sourceDir,"r"):seek("end")

    if isSystemApp(app) then
      table.insert(self.readyData.fastfindsystem,应用名称)
      self.readyData.system[应用名称]={package=包名,vesionName=版本号.."",size=长度,name=应用名称..""}--添加到列表
     else
      table.insert(self.readyData.fastfindnosystem,应用名称)

      self.readyData.nosystem[应用名称]={package=包名,vesionName=版本号.."",size=长度,name=应用名称..""}--添加到列表
    end

  end
end


---@param self AppListStream
---@param data AppListReadyBuild
---@return AppListItem[]
local function buildFunction(self,data)
  if #self.readyData.fastfindnosystem==0 then
    readyLoad(self)
  end

  ---@type AppListItem[]
  local result={}
  switch data.mode do
   case self.MODE.GETSYSTEMAPP
    table.foreach(self.readyData.system,function(k,v)
      result[#result+1]=v
    end)
   case self.MODE.GETNOSYSTEMAPP
    table.foreach(self.readyData.nosystem,function(k,v)
      result[#result+1]=v
    end)
   case self.MODE.SEARCHNOSYSTEMAPP
    for k,v in pairs(self.readyData.fastfindnosystem) do
      if utf8.lower(v):find(utf8.lower(data.search)) then
        result[#result+1]=self.readyData.nosystem[v]
      end
    end
   default
    for k,v in pairs(self.readyData.fastfindsystem) do
      if utf8.lower(v):find(utf8.lower(data.search)) then
        result[#result+1]=self.readyData.system[v]
      end
    end
  end



  table.sort(result,function(a,b)
   return a.name<b.name
  end)

  return result
end

---@class AppListStream
---@field MODE AppListMode
---@field readyLoad fun(self: AppListStream)
---@field readyBuild AppListReadyBuild
---@field readyData AppListReadyData
---@type AppListStream
local t={
  MODE={
    GETSYSTEMAPP=0x1f,
    GETALLAPP=0x2f,
    GETNOSYSTEMAPP=0x6f,
    SERACHALLAPP=0x3f,
    SEARCHSYSTEMAPP=0x4f,
    SEARCHNOSYSTEMAPP=0x5f,
  },
  readyLoad=readyLoad,
  readyBuild={},
  readyData={
    system={},
    nosystem={},
    fastfindsystem={},
    fastfindnosystem={},
  },
}

---@param self AppListStream
---@param mode integer
---@return AppListStream
function t:mode(mode)
  table.clear(self.readyBuild)
  self.readyBuild.mode=mode
  return self
end


---@param self AppListStream
---@param text string
---@return AppListStream
function t:search(text)
  self.readyBuild.search=text
  return self
end


---@param self AppListStream
---@return AppListItem[]
function t:build()
  return buildFunction(self,self.readyBuild)
end

---@deprecated Use build instead.
---@param self AppListStream
---@return AppListItem[]
function t:bulid()
  return self:build()
end

return t
