require "import"
import "android.widget.*"
import "android.view.*"


AppUtil={}

function AppUtil.getAppApk(p)

  local pm=activity.getPackageManager().getPackageInfo(p, 0)

  local pms=pm.applicationInfo

  local copyDir,appname="/sdcard/AppManager/Apk",pms.loadLabel(activity.getPackageManager()).."_"..pm.versionName


  local path=pms.publicSourceDir

  local JavaFile=luajava.bindClass "java.io.File"

  JavaFile("/sdcard/AppManager/Apk").mkdirs()

  task(function(copyDir,path,appname)
    os.execute("cp -r "..path.."".." "..copyDir)
    os.rename(copyDir.."/base.apk",copyDir.."/"..appname..".apk")
    end,copyDir,path,appname,function()
    print("已提取到 "..copyDir)
  end)
end

function AppUtil.getAppIcon(p)
  local pm=activity.getPackageManager().getPackageInfo(p, 0).applicationInfo

  local icon,name=pm.loadIcon(activity.getPackageManager()).getBitmap(),pm.loadLabel(activity.getPackageManager())
  local JavaFile=luajava.bindClass "java.io.File"
  JavaFile("/sdcard/AppManager/Icon").mkdirs()
  local i,e=pcall(function()FileUtil.saveBitmap(icon,"/sdcard/AppManager/Icon/"..name) end) 
  if i then
    print("已提取到 /sdcard/AppManager/Icon")
   else
    print(e.."提取图标失败")
  end
end
