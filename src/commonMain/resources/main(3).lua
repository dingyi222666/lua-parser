local L0_1, L1_1, L2_1, L3_1, L4_1
L0_1 = _ENV
L1_1 = L0_1.require
L2_1 = "import"
L1_1(L2_1)


L1_1 = L0_1.import
L2_1 = "android.app.*"
L1_1(L2_1)
L1_1 = L0_1.import
L2_1 = "android.os.*"
L1_1(L2_1)
L1_1 = L0_1.import
L2_1 = "android.widget.*"
L1_1(L2_1)
L1_1 = L0_1.import
L2_1 = "android.view.*"
L1_1(L2_1)

L1_1 = L0_1.import
L2_1 = "basicpackge"
L1_1(L2_1)
L1_1 = L0_1.import
L2_1 = "dhk"
L1_1(L2_1)
L1_1 = L0_1.import
L2_1 = "java.io.File"
L1_1(L2_1)
L1_1 = L0_1.import
L2_1 = "android.graphics.Bitmap"
L1_1(L2_1)
L1_1 = L0_1.import
L2_1 = "layout"
L1_1(L2_1)
L1_1 = L0_1.activity
L1_1 = L1_1.setContentView
L2_1 = L0_1.loadlayout
L3_1 = L0_1.layout
L2_1, L3_1, L4_1 = L2_1(L3_1)
L1_1(L2_1, L3_1, L4_1)
--[[
function L1_1(A0_2, A1_2, A2_2)
    local L3_2, L4_2, L5_2, L6_2, L7_2, L8_2
    L3_2 = L0_1
    L4_2 = L3_2.import
    L5_2 = "android.graphics.drawable.GradientDrawable"
    L4_2(L5_2)
    L4_2 = L3_2.GradientDrawable
    L5_2 = L3_2.GradientDrawable
    L5_2 = L5_2.Orientation
    L5_2 = L5_2.TR_BL
    L5_2 = L5_2.TR_BL
    L6_2 = {}
    L7_2 = A0_2
    L8_2 = A1_2
    L6_2[1] = L7_2
    L6_2[2] = L8_2
    L4_2 = L4_2(L5_2, L6_2)
    L3_2.drawable = L4_2
    L4_2 = A2_2.setBackgroundDrawable
    L5_2 = L3_2.drawable
    L4_2(L5_2)


end

L0_1["渐变"] = L1_1
L1_1 = L0_1["渐变"]
L2_1 = 4278223103
L3_1 = 4281243647
L4_1 = L0_1.buttonBack
L1_1(L2_1, L3_1, L4_1)
L1_1 = L0_1["渐变"]
L2_1 = 4278223103
L3_1 = 4281243647
L4_1 = L0_1.buttonBack1
L1_1(L2_1, L3_1, L4_1)
L1_1 = L0_1["渐变"]
L2_1 = 4278223103
L3_1 = 4281243647
L4_1 = L0_1.buttonBack2
L1_1(L2_1, L3_1, L4_1)

]]
