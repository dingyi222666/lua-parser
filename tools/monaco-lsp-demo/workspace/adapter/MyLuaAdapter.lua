require "import"
import "android.widget.*"
import "android.view.*"

local t={
  data={},
  adapter=""
}

setmetatable(t,t)

t.new=function(self)
  local r= table.clone(self)
  setmetatable(r,r)
  return r
end

t.buildAdapter=function(self) 
  import "com.LuaRecyclerAdapter"
  import "com.LuaRecyclerHolder"
  import "com.AdapterCreator"
  self.adapter=LuaRecyclerAdapter(AdapterCreator({
  getItemCount = function()
    return #self.data
  end,
  getItemViewType = function(position) return 0 end,
  onCreateViewHolder = function(parent, viewType)
    local views={}
    local holder=LuaRecyclerHolder(loadlayout(self.layout,views,parent.class))
    holder.view.setTag(views)
    return holder
  end,
  onBindViewHolder = function(holder, position)
    pcall(self.onBindViewHolder,self.data,holder,position)
  end,
}))  
end

t.getData=lambda self:self.data

t.add=function(self,data)
  table.insert(self.data,data)
  self.adapter.notifyItemChanged(#data-1)
end


t.addAll=function(self,data)
  self.data=data  
  self.adapter.notifyDataSetChanged()
end

t.notifyDataSetChanged=lambda self:self.adapter.notifyDataSetChanged()

t.getAdapter=lambda self:self.adapter
  

t.__call=function(self,activity,layout)
  local mself=self:new()
  mself.__call=lambda self:self.adapter
  setmetatable(mself,mself)
  mself.layout=layout
  mself:buildAdapter()  
  return mself
end


return t