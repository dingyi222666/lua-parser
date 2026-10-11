# 最小兜底审计（2026-10-11）

审计范围：`src/commonMain`、`src/jvmMain` 和 Android 生产源码。搜索 fallback、minimal、
mini、shell、stub、hard-lock，并检查每处的调用路径、数据来源和测试。搜索词并不是删除依据；
判定依据是分支是否伪造能力、绕过完整分析或专门替测试填补缺失数据。

| 路径 | 原行为 | 修改 |
| --- | --- | --- |
| BuiltinOverlayLoader.loadInteractive | 标准库成员全部替成 any 函数，activity 使用临时空类模型 | 删除；前后台使用同一 core 声明加载路径 |
| BuiltinOverlayLoader.loadLive | 用空 FileSnapshot 替代分析并跳过多数 AndroLua 声明 | 删除；分析完整声明集合 |
| BuiltinOverlayLoader 原生模块注册 | Kotlin 另存 Http、Bmob、Bitmap 等小成员表，和声明文件重复 | 删除小表；完整声明经过 CommentAttach、Binder、TypeResolver 和 evaluator |
| BuiltinOverlayLoader pairs/ipairs | 按名字覆盖类型结果，掩盖注释解析问题 | 删除；保留声明文件解析出来的泛型签名 |
| BuiltinOverlayLoader globalsSource | 为缺失名字拼接 name = name 小片段 | 删除；AndroLua 继承完整 Lua 5.3 声明，模块直接来自真实 provider |
| BuiltinOverlayLoader import globals | luajava 缺失时从 import 的零散名字猜出替代模块 | 删除；只使用已解析的 luajava provider |
| ExpressionTypeEvaluator native load* | 手写 Bitmap/Menu/View/Drawable 的少量方法，没有 SDK 也伪装可补全 | 删除；从配置的 Java/DEX provider 取类型，否则保持 unknown |
| ExpressionTypeEvaluator getContext | 拼出固定 AndroidLuaContext 方法表、给其他类型换名字 | 删除；读取已绑定的声明及其真实父类型 |
| ExpressionTypeEvaluator layout class | 按名字后缀猜 android.widget FQCN，再创建空 Java 类型 | 删除；只接受可解析的类 |
| ExpressionTypeEvaluator layout table | 看到几个布局键就跳过整张表，返回 LuaLayoutSpec 空模型 | 删除；完整推导实际表字段、子表及回调 |
| ExpressionTypeEvaluator shared surfaces | 进程级缓存只以名字为键，工作区之间会串类型 | 删除；上下文相关结果属于文档模型，底层元数据仍按 classpath 身份缓存 |
| CheckerPass filterCleanAnalyzeNoise | 为“干净测试片段”删除只有 local 的文件的 unused-local 诊断 | 删除；诊断不依赖文件是否像测试样例 |
| BuiltinSymbolSeeder | 没有声明依据的名字也默认认作函数 | 删除；函数身份只来自已解析声明 |
| ModuleExportCollector .aly | 空导出伪造 __layout、__module 字段 | 删除；模块身份属于元数据，只导出实际字段 |
| ExpressionTypeEvaluator Java 调用 | 类型检查失败后按参数个数猜返回类型，或忽略构造器检查结果 | 删除；统一使用 CallChecker 的有效匹配，基本 Java 转换集中到 TypeRelations，监听器函数/表转换集中到共用适配规则 |
| JavaClassType.callSignatures | 没有构造器也补一个零参数构造器 | 删除；只能使用真实可访问构造器 |
| JvmClassModuleProvider host substitution | 为 G:/ 等测试路径自动替换到另一份主机 SDK | 删除；显式路径是权威配置，测试改用真实 SDK |
| AndroidDexClassLoaderFactory | 将 SDK stub DEX 当执行代码加载，parent-first 掩盖 API 元数据 | 删除；显式 android.dex 走解析器，优先于设备反射 |
| MainActivity framework metadata | android.dex 缺失时静默继续，升级后旧副本不刷新 | 改为必需资源，按 APK 更新时间原子替换 |
| LuaLanguageService diagnostics | 有语法错误就删除全部非 Error 语义发现，截断提示也伪装成 Error | 删除；当前修订的真实诊断共存，过期修订全部不发布，截断通知始终是 Hint |
| LuaLanguageService formatting | AST 打印失败后用文本关键字猜缩进，可能改写字符串 | 删除；只返回通过语法校验的 AST 格式化结果，否则保留原文 |
| ExpressionTypeEvaluator load* 名字分支 | 只看 loadbitmap/loadmenu 等名字，跳过绑定和参数检查 | 删除；使用已解析声明和统一 CallChecker，用户同名函数正常推导 |
| JvmClassModuleProvider classMembers | 按字母序静默截断到 400 个成员 | 删除上限；完整成员集仍线性去重并稳定排序 |
| JvmClassModuleProvider reflection errors | 反射异常缓存为成功的空类，掩盖缺失依赖 | 删除；失败记录独立于已加载元数据，返回 unavailable，显式导入提供 jvm.metadata-unavailable 诊断 |
| LuaLanguageService semantic tokens | 任一函数存在同名参数，就把全文件同名变量染成参数 | 删除；每处标识符使用真实作用域绑定 |
| LuaLanguageService call hierarchy | 缺少引用时按同名函数/调用猜边，范围不匹配也选第一个候选 | 删除；函数项需匹配声明范围，边来自绑定引用，不按名字猜 |
| BuiltinOverlayLoader standalone globals | 用空 FileSnapshot 加载全局，模块覆盖同名函数后仍强行按 FUNCTION 种类绑定 | 删除；保留真实声明修订，声明函数优先于模块注册，函数字符身份来自实际类型 |
| BuiltinOverlayLoader documentedClassTypes | 独立的小类解析器，重复解码注释/继承/方法，异常签名变 any 函数 | 删除；统一 Binder/TypeResolver 加上类表 AST 成员推导，注释明确定义方法类别 |


删除兜底暴露并修复的共用类型问题：同名类表声明未关联唯一的 `@class`；方法推导丢失 optional、
vararg 和 overload；空实现的 nil 覆盖文档返回类型。多组类注释或不同名值不猜测隐式类型，显式
`@type` 保持优先。

仍需后续重构的边界：BuiltinOverlayLoader 的类声明和成员签名解码器均已删除，统一使用 Binder/TypeResolver；
反射失败已不再伪装空类；包目录的延迟类型引用仍需在整体 provider 架构中统一未加载状态；LSP 的未就绪
文档树查询仍有独立的完整文档 parse 路径。这些不能算作已完成的整体架构清理。

已发现但尚未完成的裁剪/猜测路径：

- 布局调用的 4096 节点预算、ID 的 512 节点/32 层预算、属性查询的 1024 节点预算已删除。普通嵌套成员赋值的 4 级路径限制现已删除；改用迭代路径重建，并验证 12 层写入、兄弟字段和同名局部根隔离。
- 全局指纹的声明数/字符数/表达式深度裁剪已删除。修订内 token 前缀索引覆盖完整写入范围；Binder 记录后续同名写入与全局成员赋值，不再遗漏只创建一次声明的后续写入。
- SCC 比较的声明/字段截断已删除；三轮上限及 display-name 比较仍需升级成完整类型状态与明确的未收敛报告。
- SignatureHelpProvider 的泛型/注释/手写 createProxy 恢复分支已删除。泛型方法标签保留调用点方法名前缀，类型参数只来自实际 signature，不解析展示字符串。

这些已进入审计，尚未删除，不能声称全仓兜底已清空。

保留的正常语义：解析错误恢复、输入位置校验、取消与过期修订检查、递归边界、未加载的真实
Java 类型引用、URI 编解码、按依赖优先级查找。这些不生成假的成员或成功结果。

记录的整体起点为 53,562 行生产 Kotlin；本轮代码为 49,634 行（测试和二进制资产不计入）。
净减少 3,928 行，距离 7,000 行目标仍未达标。验证结果另行记录，不能用旧报告验收新修改。

## 查询修订与共享全局失效（当前）

发现 resolver 已支持 require 边之外的共享全局读取，但 dirty planner 只追踪模块边；已
预热的查询可保留另一个文件旧全局类型。ScopeGraph/global public fingerprint 现在携带
真正的环境写入名（含 table.foo 扩展）；dirty planner 为 referencedNames 建立名字索引，
共享全局新增、修改、删除、注释改变及传递别名都会进入反向闭包。静态表字段键不再被
误记成读取，动态 [name] 键仍是读取；修正旧事实测试中的 ignored 字段键断言。

新分析的模型与其 SemanticPipelineSnapshot 现在使用同一最终 workspace context；之前
仅替换 snapshot.workspaceContext，但 model/evaluator 仍绑定中间上下文。未受影响的
文档保留已预热模型，同时诊断 snapshot 上下文仍遵守单个最终 resolver 的现有契约。
无关编辑不清理 query cache，涉及真实输入的编辑必须换修订。SharedGlobalRevisionTest
验证预热结果更新、旧快照不变、无关模型保留、新增/删除 provider、注释与传递边，以及
静态/动态键区别。没有为了通过测试去掉最终 resolver 的现有约束。

远程 `/tmp/lua-query-revision-corrected-acceptance.log` 完整 2,908 个 JVM 用例通过，0 失败/错误/跳过；JS、APK、Android test APK 构建通过。中间 query revision 版 Android 5/6，冷进程动态 DEX 超时；重复冷测试同样失败。
线程栈 `/tmp/lua-query-active-cold-trace.txt` 显示前台耗费在 BuiltinOverlayLoader 的类补充
再次调用 TypeNormalizer.normalizeUnion，并非 socket 等待。resource binding 缓存完整类
面，hydration 保留 canonical union 的分支，仅替换 nominal class references，不再再次
递归标准化完整注释类图。`/tmp/lua-query-resource-types-acceptance.log` 全套 JVM/JS/APK
构建通过。修正版 `/tmp/lua-query-resource-types-e2e.log` Android 6/6 通过，300.968 秒；
`/tmp/lua-query-resource-types-monaco.log` smoke 通过真实补全和已广告的协议能力。
失败轮次不算结果。修正版 APK 已直接下载，SHA256
`2caab2e7862d6863e4a93d74f468cd95f4c0ac0056f6f6f411bfad8e076c3b64`。
APK DEX 包含 globalWriteNames、withWorkspaceContext、resolvedClassTypes；framework DEX
SHA256 仍为 7710e54e52acd25addffebf926bdf00b5c493920d0fed4d19f556d279abd655e。
当前代码 49,634 行，净减 3,928；7,000 行目标与性能目标保持 active。

修正版软件模拟器样本：首次有效 table 补全 6,711ms（上轮 6,409ms），增量 8,088ms
（上轮 7,976ms），编辑后诊断 45,689ms（上轮 21,209ms），布局切换 3,471ms
（上轮 5,351ms），热补全 441–1,000ms。诊断明显退步，不能用正确失效修复或整体通过
冒充加速完成。首次 Activity onCreate 到 open complete 20,573ms，不含前置启动时间。

## 上轮 Import 激活复用

JvmWorkspaceEngine 的配置 import 和文档 import 改用同一 activateImports 路径，删除
两套激活循环以及可直接得到 symbol 时的重复类名解析/再次激活。缓存键由规范化配置（包括
ClassLoader）、目标列表、类引用名集合、classpath/framework/imported DEX 的存在/长度/
修改时间组成；不包含源码位置和注释。最多保留 32 份激活；exploded class directory
不以根目录时间戳缓存。取消检查在真实激活之前运行。

JvmImportActivationReuseTest 验证普通编辑重用、目标变化及前缀 metadata 替换失效；
DexMountingTddTest 新增“显式 DEX 先缺失后创建”的行为测试，确认缺失 import 不永久缓存。
远程 `/tmp/lua-import-activation-acceptance.log`：2,904 JVM 用例全部通过，0 失败/错误/跳过，
JS、debug APK 和 Android test APK 构建通过。Android `/tmp/lua-import-activation-e2e.log`
6/6 通过，299.924 秒。包含动态 DEX、三项成员回归、跨文件未保存依赖、编辑/诊断/切换/
格式化/保存/recreate/高亮。以下旧数据属于旧版。
远程 `/tmp/lua-import-activation-monaco.log` smoke 通过真实补全和协议能力。
新版已直接下载，SHA256 `6438423d871884b37fbc0e05313333c6ae1fc65f1eb5ece8d958f263d23b0744`。
APK DEX 含 ImportActivationKey/activateImports，不含 collectSourceImports/collectConfiguredImports；
android.dex 哈希仍为 7710e54e52acd25addffebf926bdf00b5c493920d0fed4d19f556d279abd655e。
代码现为 49,601 行，净减 3,961；为消除实际重复激活增加少量有界缓存代码，7,000 行目标未达。

本轮软件模拟器样本：首次有效 table 补全 6,409ms（上轮 8,236ms），增量 7,976ms
（上轮 8,917ms），诊断 21,209ms（上轮 49,376ms），布局切换 5,351ms（上轮 3,404ms）。
热 table 补全 578–904ms；ActionBar 热样本第三次达 11,000ms，应继续定位快照切换后
类型缓存重建与后台竞争，不能排除该样本。初次 onCreate 到 open complete 20,908ms。

source-imports 冷构建仍有 3–4.8 秒样本，但同次测试后续 main.lua/mods/dingyi.lua 复用不再
进入最慢列表；真实循环次数测试明确验证 ordinary edits 不重新激活。统计保证与完整性能
验收尚未达到，7,000 行和工业级整体架构目标继续 active。

## 上轮资源修订统一与引用桥接精简

之前 `/tmp/lua-signatures-canonical-logcat.log` 的 ANALYSIS 计时显示，前台 builtins 阶段
约 9,445ms，后台约 19,865ms。两个引擎分别分析同一组静态声明资源，成为首次补全前的大块成本。
BuiltinOverlayLoader.compiledRevision 现在按 dialect 持有一份完整资源修订；前台、后台、
standalone binder 复用相同真实 parse/facts/binder/export，不再各有一份 engine-local overlay。
framework 索引复用原完整 provider 集合，Java/DEX classpath 仍由工作区 provider 单独持有。

ReferenceQueries 删除重复的 LuaJava helper 结果推导、构造器/代理/import alias 桥接，2088
行缩到 1588 行；统一使用 evaluator 完整值类型及 helper 识别。失败 newInstance 不再由引用
路径跳过构造器验证恢复假成员。import 识别共享后，用户同名 local 函数正常推导。

BuiltinRevisionOwnershipTest 验证资源文件具有真实 facts/binder/exports、前后台身份复用和
用户文件隔离；EditorMemberRegressionTest 补拒绝构造器不可补全和 shadowed import 回归。
远程 `/tmp/lua-resource-revision-acceptance.log`：2,901 个用例，0 失败/错误/跳过；JS、APK、
Android test APK 构建通过。Android `/tmp/lua-resource-revision-e2e.log` 6/6 通过，315.521 秒。
APK DEX 核实含 compiledRevision，不含 luaJavaHelperResultType/importCallModuleType/
importPackageAliasType/compileStandaloneGlobals；android.dex 哈希仍为
7710e54e52acd25addffebf926bdf00b5c493920d0fed4d19f556d279abd655e。
APK 已下载 SHA256 `c1d677203712873aac3eb7ef8a04dcb249fb55d94b9e5fcb47b6dfec67ce34ce`。
远程 `/tmp/lua-resource-revision-monaco.log` smoke 通过真实 table.addObserver 补全与已广告的协议能力。
用户更新目标为至少 7,000 行；目前净减 3,965 行，仍未达到。

本轮软件模拟器样本：首次有效 table 补全 8,236ms（前轮 12,395ms），GradientDrawable
首次 7,850ms，布局切换 3,404ms（前轮 7,308ms）；增量 8,917ms（前轮 6,821ms），
编辑后诊断 49,376ms（前轮 24,409ms），两项出现退步。全部行为测试通过不能代替性能验收。
新日志 builtins 阶段在已编译资源后的前后台分析均约 0ms（少量 32ms）；剩余大项是
source-imports/context（main.lua 约 5.9/6.1 秒）、provider 组装及后台重建竞争。
初次 onCreate 到 open complete 20,680ms，未包括进入 onCreate 前启动开销。
目标仍为净减 7,000 行及完整架构、性能验收，继续 active。

## 上轮统一签名提示

SignatureHelpProvider 从 1355 行缩减至 471 行：删除手写 createProxy 重载、从展示字符串
和文档重新恢复泛型、独立函数体返回推导及按名字寻找无关声明的路径。签名与 callable hover
读取 evaluator/CallChecker 的 canonical 类型、重载和泛型；参数展开与 Java 参数转换复用
同一 evaluator 的 callArgumentValues，compact call 使用 parser 的统一解码。

调用查找复用 ScopeGraph.calls，删除请求时对子树和前驱 statement 的重复递归扫描。
NodeTypeIndex 中最后一个只供旧签名路径使用的同名方法判断 helper 已删除。
新增 CanonicalSignatureHelpTest 覆盖同名局部/成员、factory 返回 callable、compact table
调用的参数/self offset。中间版本 `/tmp/lua-signatures-canonical-acceptance.log` 全部通过；
最终版本 `/tmp/lua-signatures-final-acceptance.log` 完整 JVM 2897 个用例全部通过，
0 失败/错误/跳过；JS、debug APK 和 Android test APK 构建通过。
Android `/tmp/lua-signatures-canonical-e2e.log` 6/6 通过，309.153 秒，覆盖用户三项补全回归、
动态 DEX、未保存依赖、编辑/诊断/切文件/格式化/保存/recreate/高亮。
APK 已直接下载，SHA256 `f3f1246b35836a0ff66af42e18025e37f77382631cbd564b0eae962e821e3925`。

APK DEX 核实不含 documentedCreateProxyOverloads、recoverTypeParameters、
typeParametersFromDisplayName、inferReturnedParameterType、collectCallExpressions；
远程 `/tmp/lua-signatures-canonical-monaco.log` smoke 通过真实补全及协议能力探针。
包含共享 callArgumentValues，内置 android.dex SHA256 保持
7710e54e52acd25addffebf926bdf00b5c493920d0fed4d19f556d279abd655e。

本轮软件模拟器样本：首次有效 table 补全 12,395ms（前轮 10,986ms）、增量 6,821ms
（前轮 5,423ms）、热补全 364–527ms、编辑后诊断 24,409ms、布局切换 7,308ms。
这些数据没有显示全面性能改善；统一签名职责与精简代码不能算作交互性能验收完成。

## 上轮 Provider 类型复用与统一模型推导

- WorkspaceModuleResolver 的 provider 语义状态统一拥有 binding/context、候选声明、按别名
  结果及 evaluator。不同名字读取复用完成的公共表达式结果；dependency-order 构建替换
  binding 或 context 时整个状态失效，不再有独立按 path/alias 保留过期结果的缓存。
- evaluator 不再把 active declaration 整个集合复制到每个缓存键。词法 scope、局部覆盖、
  排除声明与 vararg 仍保留在键中；递归环提升 epoch，观察到该临时结果的活动计算不入缓存。
- 统一 local initializer 上下文路径，排除尚不可见声明造成的无关键分裂；保持同语句排除、
  多返回值槽位和自引用语义。新增按真实表达式 cache miss 计数的观测钩子。
- NodeTypeIndex 删除独立的声明推导、函数合并、同名方法查找和失败调用猜返回值；统一调用
  evaluator。getInferredType 保持实现驱动，调用/文档查询保持声明返回优先，共用参数合并。
- 冒号方法 self 参数补充及 lexical owner 查找合并至 CheckerResolutionModels；节点类型
  缓存改用 identity-node map，删除线性双列表查找。

`ProviderTypeReuseTest` 的专项验证证明公共表跨别名仅计算一次、修订替换失效、递归入口顺序
不污染缓存；`SemanticTypeConsistencyTest` 覆盖拒绝调用保持 unknown 和多返回值推导。
远程 `/tmp/lua-provider-types-final-acceptance.log` 完整验收通过：2,894 个 JVM 用例，
0 失败/错误/跳过，JS、debug APK、Android test APK 构建通过。
新版 `/tmp/lua-provider-types-e2e.log` Android E2E 6/6 通过，314.920 秒，包含全部用户补全回归、
动态 DEX、未保存依赖、增量诊断、切换、格式化、保存、recreate 和高亮。新版 APK 已下载，SHA256：
`8150d7f6f7dfe1152b76a889f6c6a6b2579b7db18145135df2a01da8fabef44c`。

APK DEX 核对：不含 EvaluationKey、mergeCallableInference、astMethodDeclarationForBase；
包含 inferDeclarationType、recursionEpoch。远程 `/tmp/lua-provider-types-monaco.log` 的真实补全及协议 smoke 通过。framework DEX 的 SHA256 保持
7710e54e52acd25addffebf926bdf00b5c493920d0fed4d19f556d279abd655e。

本轮软件模拟器样本（非重复统计保证）：首次 table 补全 10,986ms（前轮 12,483ms），
增量补全 5,423ms（前轮 6,200ms），热补全 635–710ms，诊断 24,845ms（前轮 25,224ms），
动态 DEX 3,199ms；布局切换 6,907ms、GradientDrawable 首次 15,410ms。交互仍慢，不能
验收性能目标。查询/诊断之间可变类型缓存的并发归属仍需整体统一，不能声称线程架构已完成。

## 上轮统一内置声明解析

`BuiltinOverlayLoader` 删除第二套 @param/@return/@generic/@overload 正则扫描、函数签名组装、
类型语法->类型转换及赋值字面量解码，2145 行减到 1508 行。标准模块/全局声明读取共享
resource AST/binder 的真实结果，保留调用者分析出的 range 及额外导出。原有 @class/继承
解析此前已经统一，此次覆盖成员签名，不能再用小解析器为标准库兜底。

删除重复解析暴露的共用 bug：`DeclarationBinder` 在首次全局赋值时丢失 `---@type` 文档与
类型语法。修复适用于普通用户代码和内置 activity/service/context，不按变量名补类型。
新增 `CanonicalBuiltinDeclarationTest` 对照 canonical 与实际加载出的标准库签名、短重载，
并验证普通全局类标注。完整远程 `/tmp/lua-overlay-contract-acceptance.log`：2890 个用例，
0 失败/错误/跳过；`/tmp/lua-overlay-unified-acceptance.log` 的 JS、APK 构建通过。

新版 APK 已直接下载，SHA256：
`bb16b2f7fec796df76b45e772ab6e5ad43727889ee0aa6f4ad3c419131dcacff`。
新版 Android `/tmp/lua-overlay-unified-e2e.log` 6/6 通过，319.828 秒，覆盖动态 DEX、
未保存依赖、三项成员补全回归、诊断、切文件、格式化、保存、recreate 和高亮。
`/tmp/lua-overlay-unified-monaco.log` smoke 通过真实 table.addObserver 与已广告的协议探针。
APK DEX 中 parseMemberDoc/resolveDocumentedTypeSyntax/parseDocumentedCompleteType 均不存在；
内置 android.dex 哈希保持 7710e54e52acd25addffebf926bdf00b5c493920d0fed4d19f556d279abd655e。

本轮软件模拟器样本：首次有效 table 补全 12,483ms，热补全 410–697ms，增量补全 6,200ms，
编辑后诊断 25,224ms；动态 DEX 3,772ms，布局切换 7,231ms，主文件切换 867–1,283ms。
onCreate 到首次 open complete 20,986ms，不包括进入 onCreate 之前的启动开销。
相比上轮并无全面性能改善：诊断较上轮 20,959ms 变慢，布局仍慢。不能把结构精简等同于
完成交互性能目标，完整 goal 保持 active。

## 上轮索引重构

- `DocumentRevision` 缓存一次完整 lexical token 索引。范围指纹用滚动前缀与长度权重取得；
  trivia 不参与，因此无语义的排版/普通注释修改保留消费者绑定；字符串内容、深部表字段、
  函数体与第 513 个之后的全局仍参与失效。常规注解由公开注解指纹处理。
- Binder 现有遍历产生 `ScopeGraph.calls/globalWrites`。布局调用不再单独遍历全文件；全局
  指纹不再扫描/推导类型。目录节点、共享解析与绑定的所有者仍是同一修订。
- ID 名字、ID 类型和布局属性使用同一可取消的迭代布局行索引，不再对同一表递归三次。
- Binder 的正在构造的作用域有 `valuesByName` 索引，已有符号有 ID->list index，删除嵌套
  线性扫描。声明顺序、重复声明、类型/值命名空间和局部遮蔽继续由完整回归覆盖。
- compact 调用统一使用 parser 的 `compactCallBase/compactCallArguments`；删除 evaluator
  只处理 string call 的副本，恢复 table call 包裹布局行。

当前完整远程 `/tmp/lua-binder-index-acceptance.log` 已通过：2,888 个 JVM 用例，0 失败、0 错误、0 跳过；JS、debug APK 和 Android test APK 构建通过。新版 Android `/tmp/lua-binder-index-e2e.log` 6/6 通过，305.535 秒；包含动态 DEX、三个成员补全回归、增量编辑/诊断、跨文件依赖、格式化、保存、recreate 和高亮。
新版已下载 `sample-debug-latest.apk`，SHA256 `b54192842c1b5070ed535126e25c92768906aa070a98be68edc46bea17ab01d0`。
实际 APK 包含 SourceTokenFingerprint、globalWrites、valuesByName、layoutRows；不包含旧布局节点预算和全局指纹字符上限。
远程 `/tmp/lua-binder-index-monaco.log` smoke 通过，完整能力探针和真实 table.addObserver 补全保持有效。

本轮软件模拟器数据（同一整套测试中的样本，不是统计性能保证）：

| 指标 | 上轮共享绑定版 | 本轮索引版 |
| --- | ---: | ---: |
| 首次有效 table 补全 | 11,981ms | 12,346ms |
| 热补全 | 300–840ms | 411–773ms |
| 增量补全 | 6,591ms | 7,248ms |
| 编辑后语义诊断 | 54,226ms | 20,959ms |
| 动态 DEX 补全 | 4,427ms | 3,979ms |
| 主文件后续切换 | 572–806ms | 887–1,584ms |
| 布局切换 | 3,990ms | 7,259ms |

诊断明显降低，但补全与切换没有全面改善；布局切换较慢，需要继续定位。初次 Activity
onCreate 到 open complete 20,820ms，前置启动时间未计入。目标仍未完成。

## 上轮共享声明绑定版验证

远程 `/tmp/lua-fallback-shared-binding-acceptance.log`：完整 JVM 回归 2,881 个用例，0 失败、
0 错误、0 跳过；JS 编译、debug APK 与 Android test APK 构建通过。包含上游 Lua parser
corpus、Android 真实 DEX、遮蔽绑定、完整成员导出、缺失依赖及声明修订回归。

本地新版 `sample-debug-latest.apk` 已下载，SHA256：
`a5519f51e41d6adf2600ef1f05035583553fb422303b801d78f07956aae8557c`。
远程 HTML/Monaco smoke `/tmp/lua-fallback-shared-monaco.log` 通过：真实 `table.addObserver`
补全及 references、highlight、selectionRange、rangeFormatting、rename、inlayHint、semantic
full/delta、workspace symbol；补全往返 200.4ms，服务内部 33ms。HTML 比较再次说明 Android
首次/增量/诊断延迟远未达到目标，不能以接口测试通过代替性能验收。

内置 `assets/android.dex` SHA256：
`7710e54e52acd25addffebf926bdf00b5c493920d0fed4d19f556d279abd655e`。
检查实际 APK 的代码 DEX：不含 loadInteractive、loadLive、javaChainedCallReturnType、
shellModuleTypeFor、resolveLoadlayoutFamilyCall、MAX_CLASS_EXPORT_MEMBERS，包含
真实 declarationSnapshot 和独立 reflection metadata failure 处理。
新版 `/tmp/lua-fallback-shared-binding-e2e.log`：6/6 Android E2E 全部通过，335.142 秒。
包含冷进程动态 DEX、未保存跨文件依赖、三项补全回归、增量诊断、文件切换、格式化、保存、
Activity recreate 和 Lua 高亮。中间 methods 版冷 DEX 超时已通过职责分离、延迟模块注册和
共享资源 AST/绑定修复，没有放宽测试预算。

新版软件模拟器结果：动态 DEX 补全 4,427ms，首次有效 table 补全 11,981ms，热补全
300–840ms，增量补全 6,591ms，编辑后诊断 54,226ms；冒号补全 1,063ms，ActionBar
2,514ms，GradientDrawable 首次 13,922ms。后续主文件切换 572–806ms，布局切换
3,990ms。最初 Activity onCreate 到 open complete 28,014ms，不包含进入 onCreate
前的启动开销。性能仍未达标，goal 继续 active。

## 先前 nominal 图修复版记录

`/tmp/lua-fallback-nominal-e2e.log`：6/6 Android E2E 通过，包含 `a:b`、GradientDrawable
换行点号、ActionBar、动态 DEX、增量诊断、切文件、格式化、保存及 Activity recreate。
这些是后续删改前的结果，不能代替新版验收。

软件模拟器性能仍未达标：首次有效 table 补全 11,059ms，热补全 500–725ms，增量补全
7,086ms，编辑后诊断 54,704ms。相同冷进程单测的首次补全仍有 58,152ms，诊断 42,967ms；
不能把整套测试后面的热进程数据称为冷启动。完整流程虽然通过，交互性能不能验收为完成。

线程栈 `/tmp/lua-fallback-diagnostics-trace.txt` 曾显示诊断线程耗费约 50 秒 CPU 在
TypeNormalizer 对 Java 成员图的递归复制。标准化器现保留完整 provider-owned nominal
Java 定义，仅规范化实际使用的成员签名及泛型参数；不裁剪成员、不产生替代模型。
新共享 DAG 回归通过。更新后的采样 `/tmp/lua-fallback-nominal-trace.txt` 中诊断线程仅
累计约 0.46 秒 CPU，已不在递归标准化路径，但编辑至诊断的全链路延迟仍需继续定位。
