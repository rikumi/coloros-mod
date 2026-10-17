# 软件页保留搜索与下载管理

当前范围：保留软件页原生搜索框和下载管理按钮，只移除所有顶部 Tab、tabstrip 与下方内容。分类模式及空白搜索首页方案仍已删除。目标严格为 `com.heytap.market` 26.9.4_CN / 260904，沿用已校验 SHA256 的国行 APK；不依赖首页服务端“精选/必备”ID。

## 实际范围及调用链

`q6a.generateAppTab(Resources)` 将 ID20 命名为 module_tab_soft（软件）。`k7a#Ϳ(TabDto)` 的 ID20 分支进入 `ԩ`，通过 x5a/b6a 生成子页面模型，并将 GroupFragmentItem 放入 AppGroupFragment.arguments。AppGroupFragment 继承 NormalGroupFragment，仅覆盖 actionbar 类型。ID10 首页、ID30 游戏、榜单及搜索不属于此类。

NormalGroupFragment.onCreate 将 arguments 保存在私有字段 `ࢰ`。onCreateView 初始化原生 FrameLayout 根节点、actionbar、divider、tabstrip、pager；onViewCreated 从 `ࢰ` 经 y57#ԩ 读取 GroupFragmentItem，再经 y57#Ԭ 生成子 Fragment、绑定 adapter 和选择索引。缺少 GroupFragmentItem 时不生成和绑定子页面。

新开关开启时，仅对精确类名 AppGroupFragment：

1. onViewCreated 前以新的空 Bundle 替换私有绑定字段，不改 Fragment.getArguments() 或主导航模型；原生方法仍执行正常生命周期初始化，但不会生成推荐、分类或其它顶部页面。
2. 同时移除其 ChildFragmentManager 中已恢复的旧子 Fragment，避免状态恢复继续保留旧页面。
3. onViewCreated 后仅移除字段 `ࢫ`（COUITabLayout）和 `ࢮ`（CdoViewPager）的父子关系；不删除根视图或 MainActionBar。PercentTabWithSearchView 只剩原生工具栏，按其原生 onMeasure/onLayout 计算高度和左右布局，不保留 tabstrip 占位或覆盖搜索的透明 pager。getHeight 使用 bg#Ԭ(MainActionBar)，保留工具栏实测高度及原生回退值，不再返回零。
4. 软件页 onResume 检测开关改变，经 Activity.recreate 重走原生生命周期。关闭时从未修改的原生 arguments 重建。若只销毁/重建 View 而保留 Fragment 实例，也会在关闭时恢复其原始绑定 Bundle。

源码调用链支持冷启动、缓存模型和重建统一经过相同绑定点；没有按动态 Tab 名称/ID 猜测，也不保留分类生成、横划拦截或索引钳制代码。独立搜索、下载、更新和其它主导航不经过此精确类名判断。

## 设置及旧偏好处理

| 旧设置 | 现在 | 偏好处理 |
|---|---|---|
| 首页顶部 Tab 只保留分类 | 去除软件页所有内容 | 删除旧入口与逻辑，新增 store_empty_software，默认 false；不迁移旧 true |
| 去除首页与软件页推荐内容 | 去除首页推荐内容 | 保留 store_empty_home，只收窄为首页推荐，不再处理 AppChosenCardFragment；不扩大旧授权 |
| 增加空白搜索 Tab | 删除 | 不再读取 store_blank_search_tab，残留 true 无效 |

四语言名称同步，未增加说明副标题。原始偏好记录可以残留在存储中，但失效的两键不再有读取方，不执行破坏性偏好清理。

删除 StoreSearchHomeHooks.java，移除安装调用、Tab 注入和图标、稳定 ID、默认选中、恢复清理、搜索框布局等专用代码；主导航列表与默认索引回到原生路径，只保留用户独立启用的首页/游戏/榜单隐藏过滤。正常搜索热词/推荐净化开关继续独立存在。

## 验证

- `python3 scripts/check-store-purification.py`：278 项 APK/DEX、四语言、设置及静态约束检查通过；失效的分类/空白首页检查已移除，改为检查旧代码不存在。
- `git diff --check` 通过。
- app/src 中无旧分类键、空白搜索键、StoreSearchHomeHooks、旧模块搜索 ID/稳定 ID 标记引用。
- 针对性检查覆盖精确软件类、私有绑定 Bundle、原生无 GroupFragmentItem 分支、恢复子页面清理、仅 tabstrip/pager 移除、工具栏保留、关闭重建及仅 View 重建恢复；关闭路径不修改导航默认选择。
- 本轮未构建、暂存、commit/push、操作 issue、安装或操作设备界面，保留其它共享改动。

未实机验证：软件页冷启动/缓存/返回与进程状态恢复，开关双向切换，升级前旧空白首页保存状态的实际恢复表现；以及与隐藏首页/游戏/榜单、首页推荐净化的组合。主会话统一构建后需确认软件页仅保留原生搜索框和下载管理按钮，无顶部 Tab、tabstrip、分类或横划页；搜索/下载按钮位置、外观、点击区域和工具栏高度正常；关闭恢复完整原生软件页，原生默认导航及正常搜索、下载、更新可用。不能将本轮静态通过视为编译或设备验收通过。


## 对照 Flyme 及 ColorOS 保留入口

只读参考 `/Users/rikumi/Documents/Code/flyme-mod/app/src/main/java/dev/rikumi/flymemod/StoreLayoutHooks.java` 的 installApplicationPage：替换内容 Fragment，同时由 restoreApplicationHeader 保留/恢复 Activity 的搜索和下载工具栏。本实现仅采用该功能边界，不使用 Flyme 类名或工具栏结构。

ColorOS 实际层级：FrameLayout → CoordinatorLayout →（CdoViewPager、DividerAppBarLayout）；后者包含 PercentTabWithSearchView →（MainActionBar、COUITabLayout）。因此移除 pager 和 tabstrip 即可，保留原生 actionbar 的完整父容器。PercentTabWithSearchView 遍历实际子节点，去掉 tabstrip 后只计算 MainActionBar 高度；没有添加固定高度、左右 margin/padding 或替代控件。宽屏布局仍遵循原生响应式规则。

原生 NormalGroupFragment.onViewCreated 在读取 GroupFragmentItem 之前就注册 MainActionBarPresenter，因而空页面数据不会跳过入口绑定：

- 搜索：MainActionBarPresenter → byf（getSearchLayout/getTextSwitcher）→ 原生点击监听 → z8e#ԩ → `oap://mk/search`。
- 下载管理：同一 presenter → o4a(getMdSwitchView) → 点击监听 → `oap://mk/md`。MDSwitcherView 的下载数量和状态订阅继续由原生生命周期管理。

当前代码既不替换入口，也不接管上述点击路由。未复活额外搜索 Tab，沿用 store_empty_software 和 false 默认值；设置名称、四语言及其它共享功能不变。

本次新增静态证据检查覆盖上述字段、父子层级、原生高度算法与点击调用边。返回软件页仍沿用原生 presenter 生命周期；状态恢复时仍清理子 Fragment，关闭仍从原始 arguments 重建。上述运行行为尚未实机验收，尤其需检查冷启动、从“我的”返回、配置变化/状态恢复、开关关闭，以及两个入口实际点击可达；本轮不构建、不安装或操作设备界面。
