require "import"
import "android.widget.*"
import "android.view.*"

local base={
  list={}

}

local tab={}
local pop=PopupWindow(activity)
--PopupWindow加载布局
pop.setContentView(loadlayout({
  LinearLayout;
  {
    CardView;
    cardElevation="2dp";
    cardBackgroundColor=0xffffffff;
    radius="8dp";
    layout_width="-1";
    layout_height="-2";
    layout_margin="8dp";

    {
      ListView;

      layout_height="-1";
      layout_width="-1";
      dividerHeight=0;
      id="poplist";
      layout_marginTop="9dp",
      layout_marginBottom="9dp",

      adapter=LuaMultiAdapter(activity,
      {
        {
          LinearLayout;
          layout_width="-1";
          layout_height="48dp";
          {
            LinearLayout;
            layout_width="-1";
            orientation="horizontal";
            layout_height="48dp";


            {
              TextView;
              id="popadp_text";
              textColor=0xff0000000,
              layout_width="-1";
              layout_height="-1";
              textSize="15sp";
              gravity="left|center";
              paddingLeft="18dp";
              
            };
          };
        },
        {
          LinearLayout;
          layout_width="-1";
          layout_height="48dp";

          {
            LinearLayout;
            layout_width="-1";
            orientation="horizontal";
            layout_height="48dp";


            {
              TextView;
              id="popadp_text";
           --   textColor=theme.textPrimary;
              layout_width="-2";
              layout_height="-1";
              layout_weight="1",
              textSize="15sp";
              gravity="left|center";
              paddingLeft="18dp";
              
            };
            {
              ImageView,
              padding="11dp",
              layout_height="48dp";
              layout_width="48dp";
              src="res/twotone_arrow_drop_down_black_24dp.png",
              rotation="-90",
            }
          };

        },
        {
          LinearLayout;
          layout_width="-1";
          layout_height="48dp";
          onClick=function()end,

          {
            TextView;
            
            id="popadp_text";
            textColor=0xff000000;
            layout_width="-2";
            layout_height="-1";
            
            textSize="13.6sp";
            gravity="left|center";
            paddingLeft="18dp";
            
          };



        }
      }),
    }
  }

},tab))
pop.setWidth(ViewUtil.dp2px(198))
pop.setHeight(-2)

pop.setOutsideTouchable(true)
pop.setBackgroundDrawable(ColorDrawable(0x00000000))

base.new=function(self)
  return table.clone(self)
end

base.addItem=function(self,item,click)
  table.insert(self.list,{type=1,text=item,onClick=click})
  return self
end


base.addParent=function(self,item,click)
  table.insert(self.list,{type=2,text=item,onClick=click})
  return self
end


base.addTitle=function(self,item)
  self.list.title=item
    return self
end

base.show=function(self,v)
  tab.poplist.adapter.clear()
  if self.list.title then
    
  tab.poplist.adapter.add{__type=3,popadp_text=self.list.title}
 end
  for k,v in ipairs(self.list) do
   if type(v)=="table" then tab.poplist.adapter.add{__type=v.type,popadp_text=v.text} end
  end
  tab.poplist.OnItemClickListener={
    onItemClick=function(i,v,p,l)
      self.list[l].onClick(v.Tag.popadp_text.Text)
      pop.dismiss()
    end,
  },
  tab.poplist.adapter.notifyDataSetChanged()
  if v then
    pop.showAsDropDown(v)
  end
  return pop
end

return base