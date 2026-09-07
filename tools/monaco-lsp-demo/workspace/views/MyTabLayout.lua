require "import"
import "android.widget.*"
import "android.view.*"

local _ids={}

local _pageids={}

local function addTab(t,s)
  return {
    CardView;
    cardBackgroundColor=0x00000000,
    elevation="0";
    radius="38dp";
    id="___",
    layout_marginLeft="12dp",
    onClick=function(v)
      pcall(s,v.getChildAt(0).getChildAt(0))
      for i=0,v.parent.getChildCount()-1 do
        if v.parent.getChildAt(i).id==v.id then
          _ids.pg.setCurrentItem(i)
          return
        end
      end
    end,
    {
      LinearLayout;
      layout_width="-2";
      layout_height="-2";
      padding="6dp",
      paddingLeft="10dp",
      paddingRight="10dp",
      orientation="vertical";
      {
        TextView;
        textSize="15sp",
        textColor=0xFFFFD6E2,
        gravity="center";
        text=t;
      };
    };
  };
end

local parentLayout=
{
  LinearLayout,
  layout_width="fill",
  orientation="vertical",
  {
    LinearLayout,
    layout_height="65dp",
    id="parent",
    backgroundColor=0xFFF4A7B9,
    layout_width="fill",
    {
      HorizontalScrollView,
      layout_height="fill",
      layout_width="fill",
      layout_marginTop="12dp",
      {
        FrameLayout,
        layout_height="fill",
        layout_width="fill",
        {
          LinearLayout,
          layout_height="fill",
          layout_width="fill",
          id="choose",
        },
        {
          LinearLayout,
          layout_height="fill",
          layout_width="fill",
          id="bar",
        },

      }
    }

  },
  {
    PageView,
    id="pg",
    layout_width="fill",
    layout_height="fill",
    pages={},
  }
}




local function setWidth(a,b)

  local q=a.layoutParams
  q.width=b
  a.layoutParams=q
end

local function dp2px(dpValue)
  local scale = activity.getResources().getDisplayMetrics().scaledDensity
  return dpValue * scale + 0.5
end




local function layout(arg)

  if arg.bottomBar_marginTop then
    parentLayout[2][2].layout_marginTop=arg.bottomBar_marginTop
  end


  local parent=loadlayout(parentLayout,_ids)

  if arg.id then
    _G[arg.id]={
      getPageView=(lambda self:_ids.pg),
      getPageIds=(lambda v:_pageids[v]),
      getIdsTable=(lambda _:_pageids)
    }
  end

  _ids.choose.addView(loadlayout({
    CardView;
    cardBackgroundColor=0xFFF2BECA,
    elevation="0";
    radius="42dp";
    padding="4dp",
    layout_height="32dp",
    layout_marginLeft="14dp",
  },nil,_ids.choose.class))




  for i=1,#arg.texts do
    _ids.bar.addView(loadlayout(addTab(arg.texts[i],arg.tagOnClick),nil,_ids.bar.class))
  end

  for i=1,#arg.pages do
    _pageids[i]={}
    arg.pages[i]=loadlayout(arg.pages[i],_pageids[i],_ids.pg.class)
    _ids.pg.adapter.add(arg.pages[i])
  end



  local data={
    scrollData={},
  }

  local pg,bar,choose=_ids.pg,_ids.bar,_ids.choose

  pg.setOnPageChangeListener{
    onPageSelected=function(t)
      for i=0,bar.getChildCount()-1 do
        bar.getChildAt(i).getChildAt(0).getChildAt(0).textColor=0xFFFFD6E2
      end
      bar.getChildAt(t).getChildAt(0).getChildAt(0).textColor=0xffffffff
      setWidth(choose.getChildAt(0),bar.getChildAt(t).width)
      choose.getChildAt(0).x=bar.getChildAt(t).x
    end,
    onPageScrollStateChanged=function(i)
      data.scrollData.scroll=i>0
    end,
    onPageScrolled=function(a,b,c)
      local nowView=bar.getChildAt(a)

      local nextView=bar.getChildAt(a==bar.getChildCount() and bar.getChildCount() or a+1)

      if data.scrollData.scroll and b~=0 then
        if data.scrollData.last and data.scrollData.last<b then
          if nextView.width<nowView.width then
            setWidth(choose.getChildAt(0),nowView.width-((nowView.width-nextView.width)*b))
           else
            setWidth(choose.getChildAt(0),nowView.width+((nextView.width-nowView.width)*b))
          end
          choose.getChildAt(0).x=nextView.x-((nextView.x-nowView.x)*(1-b))
         else

          local lastView=bar.getChildAt(a)
          local nowView=bar.getChildAt(a+1)

          if lastView.width>nowView.width then
            setWidth(choose.getChildAt(0),nowView.width+((lastView.width-nowView.width)*(1-b)))
           else
            setWidth(choose.getChildAt(0),nowView.width-((nowView.width-lastView.width)*(1-b)))
          end

          choose.getChildAt(0).x=lastView.x+((nowView.x-lastView.x)*b)
        end

      end


      data.scrollData.page=a
      data.scrollData.last=b
    end,
  }

  return lambda k:parent
end


return layout
