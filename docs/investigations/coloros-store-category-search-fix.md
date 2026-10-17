> 历史记录：其中分类模式与空白搜索首页已由 [清空软件页与移除空白首页](coloros-store-empty-software.md) 替代，不代表当前实现。

# 商店分类模式与搜索框边距修复

目标仅为 `com.heytap.market` 26.9.4_CN / 260904；APK SHA256 为 `391a2fb1e9bd5d22331656b44d42837ef9048ab754b1a72996f24daeb0a49179`。本轮保留共享暂存内容，只修改两个 Store hook、针对性检查脚本及本文档；不新增设置、配置键或用户可见文案。

## 分类模式：代码证据及修复

旧实现将整个模式的启用条件写成“偏好开启且在线/缓存 DTO 中存在严格匹配 `oap://mk/cardstyle?p=…` 的项”。未命中即不隐藏顶栏、不切换页面、不禁用横划，因此存在一个同时使全部预期行为失效的提前退出条件。

这个条件既不等价于原生路由，也不能保证首页响应包含分类：

- `oh3#Ϳ(String)` 解析所有查询参数；`a6g#ࢤ()` 再进行 Uri.decode 并补齐前导斜线，`ࢥ()` 读取 pt；`y5a#Ϳ(int,String)` 优先按页面类型，再由 `gq2#Ϳ` 判断分类路径。旧代码没有复用这些步骤。
- `HomeSubTabPresenter#ޱ` 首次缓存/在线结果和实时回调均进入 `ދ(SubTabCardGlobalDto,int,boolean)`；`ࡨ` 生成页面，最后统一进入 `ޟ(List,List)`。`firstPagePos` 位置被原生强制插入 HomeChosen，并不保证 actionParam 所指的页面就是实际生成的页面。
- 分类的本地模型实际在 `q6a#generateAppTab(Resources)`：软件主导航 ID20，分类子项 ID201，标题取原生 sub_tab_category，路由为 `/card/store/v4/cat/app`。此 ID 属于主导航模型，不能用来判断在线首页的另一种 SubTabDto。
- `k7a#ԩ → x5a → b6a → NormalGroupFragment` 是“软件”主导航的独立路径，并非 ID10 首页。此前检查发现这条不同路径，但本轮没有把首页设置扩大到软件/游戏导航。

修复后，完整模式开启时不再以首页数据是否提供分类作为启用前提。最终绑定时先检查真实生成的分类 Fragment（`cq2 → eq2#isSecondCategoryGroupFragment`），或核对 HomeChild 的原生路由和实际 requestPath；缺少分类时，从 q6a 取得本地分类模型，交由 `b6a#Ϳ → y57#Ԭ → ap6#Ԩ → Fragment.instantiate` 创建原生分类页。不会复制 Flyme 类名、猜测在线分类 ID，或将推荐页改名冒充分类。

只将首页最终绑定的 tab/page 列表替换为单项，索引置零；原始 DTO、网络数据和缓存不修改。分类实例在该 presenter 内复用。顶部高度仍由原生 bg#Ԭ 计算；隐藏 strip 时其 inset 为零。只阻止该 presenter 标记的外层 CdoViewPager 触摸和位置恢复，分类内部控件及纵向滚动不拦截。隐私基础模式、未成年人模式保留原生限制。

关闭后 onResume 重放已保存的原生输入；补上原输入为 null 时也需要恢复的分支。冷启动、在线刷新、缓存刷新均走统一绑定点。主导航的搜索首页选择、Tab 隐藏规则不修改；隐藏原首页时分类设置没有可见目标，新增搜索页仍由原来的默认选择逻辑管理。“清空首页推荐”只清空被标为 home 的卡片 adapter；原生分类 Fragment 不属于该范围。

Prefs 检查：设置键和 hook 均为 store_home_category_only，默认 false；hook 使用 XposedInit.readBool，经 settingsValue/SettingsProvider 获取跨进程整数值，Provider 的全量查询没有 store 键白名单。hook 参数、字段及新增反射调用均对照目标 smali。当前没有设备，不能确认实机当时的 DTO、偏好快照、注入版本，也不能声称已确定设备具体命中了哪一种失配；以上是已证实并修复的代码缺陷。

## 搜索框：资源证据及修复

`MainActionBar#getSearchLayout()` 返回 actionbar_content_inner_bg_ll。其 XML 在 `res/layout/card_page_main_tab_actionbar.xml`：左边距16dp，右边距引用 uk_appbar_searchbar_margin_end（values 为8dp，values-v37 为12dp），并保留 `layout_toStartOf=vs_ai_search`。背景 drawable 是没有 inset/padding 的圆角 shape，所以左右可见背景边界本身就不对称。旧实现隐藏右侧按钮后没有调整这些参数。

只在空白搜索页新建的 MainActionBar 实例上，移除右侧按钮 anchor，按原生起始边距统一 start/end 并相对父容器两端对齐。没有修改字号、40dp 搜索容器高度、圆角背景、原生 byf 点击绑定和搜索路由，没有影响其它页面搜索框。无新增文案，现有四语言保持不变。

## 验证与验收边界

- `python3 scripts/check-store-purification.py`：373 项 APK digest、DEX 签名/调用边、配置/资源静态检查通过；新增检查覆盖路由解码、原生分类工厂、null 输入恢复及可见背景边距来源。
- `git diff --check` 通过。未进行编译，不能将静态检查称为编译或运行通过。
- 只读 `adb devices -l` 返回无设备；未安装、重载、切换界面或进行设备验收。

主会话统一构建后，设备应验收：

1. 开启分类模式，分别冷启动、缓存启动、联网刷新、返回首页和恢复页面状态：只显示分类内容，无顶部 strip，外层横划不能换页；分类纵向滚动和点击仍可用。
2. 关闭分类模式并返回首页，恢复原生 tab、默认位置和横划；尤其覆盖此前首页响应未提供 subtab DTO 的情况。
3. 同时启用空白搜索首页：首次/返回/状态恢复仍默认新增搜索 Tab；手动进入原首页才看到分类。分别组合隐藏首页、软件、游戏、榜单和清空首页推荐。
4. 空白搜索页比较背景左右可见间距（含横竖屏/系统资源变体）；搜索高度和字号不变，点击仍打开正常原生搜索，结果下载和更新不受影响。

阻塞：设备实测因当前无连接设备尚未完成；代码及静态验证无已知阻塞。
