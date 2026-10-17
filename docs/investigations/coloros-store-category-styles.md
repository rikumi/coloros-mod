> 历史记录：其中分类模式与空白搜索首页已由 [清空软件页与移除空白首页](coloros-store-empty-software.md) 替代，不代表当前实现。

# 软件商店分类与样式净化

2026-10-17；在首次国行软件商店净化基础上追加 4 项，当前共 16 项独立开关。全部默认关闭，既有 12 个配置键保持不变。本轮没有构建、暂存、commit、push、操作 issue、设备写入/重载/界面操作或联系其它会话。

## 目标与证据

仍仅适配 `com.heytap.market` **26.9.4_CN / 260904**。APK 为 `/private/tmp/coloros-cn-reference-20261017/_26.9.4_CN.apk`，SHA256 `391a2fb1e9bd5d22331656b44d42837ef9048ab754b1a72996f24daeb0a49179`。

已读取当前 `.codebuddy/rules/coloros-mod.md`。参考为前轮使用相邻 android-app-mods 工具链得到的 `/private/tmp/coloros-store-cn/src` 资源/smali 与 `/private/tmp/coloros-store-cn/java/sources` Java。以下方法均核对了实际 DEX 名称、参数和声明；Java 反编译的别名仅帮助阅读。JADX 全包有 224 项错误，未将伪源码等同于可编译源码。

### 榜单

`a.a.a.q6a#generateRankTab` 创建 ID **40**，名称使用 `module_tab_rank=榜单`；`a.a.a.k7a` 对 ID 40 创建 `NormalGroupFragment`。新增 `store_hide_rank`，与首页 ID 10、游戏 ID 30 同时参与 `TabDataPresenter#ނ` 返回结构和 `a.a.a.n3b#ֈ` 导航刷新过滤。保留软件 ID 20、我的 ID 50，非空保护及选中索引重映射沿用现有实现。隐藏首页时优先软件页的规则不变，三项过滤的缓存掩码各占独立位。

### 首页顶部只保留分类

首页顶部不是底部 `TabDto` 导航：`HomeGroupFragment` 创建独立的 `HomeSubTabPresenter`、`ScrollIdleTabLayout` 和 `CdoViewPager`。顶部模型是 `com.heytap.cdo.card.domain.dto.SubTabDto`，不能照搬软件页分类的固定 ID 201。`q6a` 的 201/301 分别是软件/游戏默认分类 ID，不当作首页动态子页 ID。

实际分类路由由 `a.a.a.gq2#Ϳ` 明确定义：`/card/store/v4/cat/app`、`/card/store/v5/cat/app`、`/card/store/v4/cat/game`、`/card/store/v5/cat/game`；`y5a` 将其映射到 `NEW_CATEGORY_PAGE`，`cn2` 同样处理分类页面。实现仅识别 `oap://mk/cardstyle?p=上述路径`，不按中文标题或模糊 category 字符串判断。

完整路径：`HomeSubTabPresenter#ޱ` 在线/缓存结果 → `ee7.extractSubTabDataFromCards` → `ދ(SubTabCardGlobalDto,int,boolean)` → 完整模式 `ࡨ` → `ee7.getHomeSubPageItemList/generatePageItem` → `ޟ(List,List)` → `CdoFragmentPagerAdapter#Ϳ` 更新页列表、原生 DataSetObserver 选中默认页。

实施规则：

- 在 `ދ` 保存原始绑定输入，仅当完整模式、非未成年人模式且实际数据含已确认分类路由时激活。基础隐私模式不越过其限制；服务端没有分类项或使用未知路由时保留原生页面，不能宣称覆盖未知配置。
- 保留原生 fragment 工厂和数据绑定；在最终 `ޟ` 同步将顶部模型、pager 页列表收敛为同一个分类条目。默认索引 `ࢯ`、滚动基准 `ࢳ` 归零，让原生 DataSetObserver 重置到唯一页。未修改服务端/磁盘 DTO。
- 仅该 presenter 的顶部 tabstrip 标记为隐藏。`csf#Ԭ` 对该实例返回零，使原生内容顶部边距不再包含隐藏 tabstrip 高度；不复制 LayoutParams 或手动改内容滚动容器。
- 禁用该顶部切换动画 presenter；分类页生成时使用原生 `setPullBackToDefaultTab(false)`，避免下拉返回被隐藏的默认推荐页。
- 只对该 presenter 标记的 `CdoViewPager` 将 `onInterceptTouchEvent/onTouchEvent` 返回 false。没有拦截子内容分发、RecyclerView 或分类内部 pager，分类纵向滚动仍由原生处理。
- 仅标记 pager 的 `ViewPager#setCurrentItemInternal(int,boolean,boolean,int)` 索引固定为 0，覆盖普通切页、返回首页、DataSetObserver 和 Android 页状态恢复。只有一个可见页，因此没有隐藏页可横划进入。
- `onResume` 检查开关变化：关闭后重放保存的原始 `ދ` 输入，恢复多页数据和原生默认选择；启用后也可通过同一路径重绑。首次载入、网络刷新、缓存刷新均走同一入口。关闭状态不限制任何 pager 触摸或选页。
- 与“去除首页与软件页推荐内容”组合时，已确认分类路径不再标记为待清空的首页 adapter。与“去除首页 Tab”组合时首页本身不可见，分类模式不改变软件/游戏/榜单页；取消隐藏首页后按分类开关工作。

### 禁用动态图标

`ResourceDto#getGifIconUrl` 和 `BaseDetailDto` 的同名覆盖返回动画图标 URL。`l33 → z07#ԫ` 在动画 URL 为空时加载原生 `iconUrl`，并以 `isGif=false` 走普通图片加载。`BigImageMultiIconCard`、`BeautyScrollBannerCard` 也明确以空 GIF URL 回落静态图标；详情头部 `e` 同样传入独立的静态/动态图标地址。

新增 `store_disable_dynamic_icons`：上述两个 getter 返回 null；应用图标入场动画的 `ResourceDto#getIconStyle` 返回原生普通值 0（`ax8` 仅 style=1 才建立动画 presenter）。动态图标挂饰读取 `SuperThemeRightsDto#getIconPendantDynamic` 返回 null：`unf` 的原生分支改用 `getIconPendant` 静态挂饰，详情头部也有对应回退。未修改原始模型、缓存 URL，未全局拦截 GIF 解码、ImageLoader 或 Animator。普通图标与下载操作保持原路径；开关关闭后重新绑定可读取原值。

### 禁用特殊应用样式

不是按 `style_id/style_type` 字段名推断：`oa5` 与 `RecommendAppCard` 中这些字段主要参与统计。本轮针对实际渲染模型：

- `ResourceDto#getSuperThemeRights` → `gg7/unf` 控制图片应用名、文字色、图标/按钮挂饰、列表素材背景及安装按钮样式。模型为空时 `unf#ׯ` 清理挂饰/素材，`gg7` 使用普通应用文字、颜色及原生布局。
- `HorizontalAppItemView` / `HorizontalBookItemView` 在主题模型缺失后仍可能按 `getHighlight()==1` 使用高亮，所以同时让该 getter 返回 0，避免退回另一种特殊外观。
- `ResourceDto#getSpecial` 与 `getBg` → `tyf#Ԫ`、`f14#ՠ`：1=皮肤、2=视频、3/非空背景=背景主题；两者均无时选择 `NROMAL_THEME` / `NORMAL`。`yvh#Ԩ` 据此实例化普通 `peb` presenter，详情头部按同一模型读取普通背景。

新增 `store_disable_special_styles` 仅在启用时返回 null/0，不写模型，不删除应用，不影响搜索、下载、安装或更新接口。与动态图标开关独立：只禁用特殊样式时普通 GIF 图标可保留；只禁用动态图标时静态主题装饰可保留。已显示的详情页需要重新进入才能完整重建原生样式。

## 现有名称逐项复核及前后映射

现有偏好键完全保留；四语言同步。名称描述内容类型时，不冒称服务端卡片有固定本地标题。

| 配置键后缀 | 原名称 | 本轮名称 | ColorOS 证据/行为核对 |
|---|---|---|---|
| group | 应用商店净化 | 软件商店净化 | 目标应用实际为软件商店 |
| block_splash | 屏蔽开屏广告 | 不变 | w3f 的 splash 服务及启动协调器无广告分支 |
| hide_featured | 去除首页 Tab | 不变 | module_tab_home=首页；仅保留旧偏好键名称，不将 Flyme 精选当作首页名称 |
| hide_games | 去除游戏 Tab | 不变 | module_tab_game=游戏 |
| empty_home | 去除首页全部内容 | 去除首页与软件页推荐内容 | 原实现同时处理 HomeChosen、AppChosen 和 HomeChild；更正文案并排除分类，避免误清分类页；不再声称删除首页一切内容 |
| hide_mine_recommendations | 去除我的页应用推荐 | 不变 | module_tab_me=我的；MineFragment 最终绑定的应用推荐 DTO，管理/安全/卸载卡片另有模型 |
| hide_mine_booking | 去除我的预约入口 | 不变 | booked_activity_title、book_reserved_desc=我的预约；未引入 Flyme 心愿单 |
| hide_mine_welfare_quick | 去除福利活动及快应用入口 | 去除我的页福利及快应用入口 | label_welfare=福利、mk_welfare_center_title=福利中心；福利卡片与原生福利/快应用路由，限定我的页 |
| hide_mine_clean | 去除空间清理入口 | 去除存储空间清理入口 | card_mine_storage_clean_title、mk_storage_clean_title=存储空间清理；清理卡片及 ToolsCardDto type=2 |
| hide_detail_recommendations | 去除应用详情页推荐 | 不变 | 详情推荐合并回调及旧推荐 Tab 的空内容路径；详情本体保留 |
| hide_download_recommendations | 去除下载管理页推荐 | 不变 | DownloadManageActivity 标题 tab_manager_downloaded=下载管理；只处理下载管理推荐回调 |
| hide_update_recommendations | 去除应用更新页推荐 | 不变 | AppUpdateActivity 标题 upgrade_title=应用更新；只处理推荐回调 |
| clean_search | 去除搜索框热词及搜索页推荐 | 不变 | 搜索热词池和 SearchHomeCardAdapterPresenter；仅保留已核实搜索历史卡片，结果/联想使用独立 adapter |

新增名称为“去除榜单 Tab”“首页顶部 Tab 只保留分类”“禁用动态图标”“禁用特殊应用样式”。四个新配置键分别为 `store_hide_rank`、`store_home_category_only`、`store_disable_dynamic_icons`、`store_disable_special_styles`。

## 静态验证与限制

`python3 scripts/check-store-purification.py` 检查 APK 身份、原始方法签名、字段类型、模型 getter、加载/渲染调用边、16 项设置及默认 false、四语言、作用域/路由。`git diff --check` 检查格式。结果为 **207 项静态检查通过，差异格式检查通过**；这不等于编译或设备验收。

本轮不构建，未安装、启动、操作或重载设备。主会话需要统一构建并发送 APK。现有独立下载后推荐页、未知服务端运营卡片仍不在已证实覆盖范围；没有分类数据时不伪造分类页，基础隐私/未成年人模式保留原限制。没有 APK 获取或源码访问阻塞。

## 设备验收矩阵（全部待执行）

1. 全关闭冷启动：确认原生 Tab、首页顶部切换、动画图标与主题样式。版本不匹配时全部商店 hook 应跳过。
2. 榜单单开，以及首页/游戏/榜单三项任意组合：导航和内容匹配、选中索引有效，软件与我的可达；关闭恢复。
3. 分类单开：有网冷启动、离线已有缓存、网络刷新、详情返回、底部我的返回首页、进程/页状态恢复后均落在分类。顶部条隐藏且没有保留其空白；内容左右划不切页，分类纵向滚动、点击子分类及返回正常。
4. 分类与清空推荐组合：分类不被清空；分类与隐藏首页组合：其它导航不受限制，恢复首页后分类模式仍有效。关闭分类后重新进入首页，恢复原顶部条、默认页和横划；检查活动页恢复与重新启动两种情况。
5. 基础隐私/未成年人模式、服务端缺分类项：保持原生限制/页面，不出现空 adapter、越界或隐藏全部导航。
6. 动态图标单开：列表与详情静态 iconUrl 可见，挂饰有静态资源时回退静态，循环动画与图标入场动画停止；其它横幅/转场/下载进度动画不受影响。关闭并重进页面恢复。
7. 特殊样式单开：主题应用图片标题、挂饰、素材背景、高亮和详情皮肤回到普通原生样式；应用名称、图标、下载按钮、详情正文仍存在。检查列表复用由主题项滚到普通项，及反向滚动。
8. 两种样式开关四种组合分别检查；完成真实搜索、下载、暂停/继续、更新，验证没有影响核心功能。以上设备结果未验证，不能由静态检查代替。
