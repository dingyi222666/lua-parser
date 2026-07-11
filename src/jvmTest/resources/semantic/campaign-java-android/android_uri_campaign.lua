local Uri = luajava.bindClass("android.net.Uri")
local uri = Uri.parse("content://example/root").buildUpon().path("child").build()
local path = uri.getPath()

return uri, path
