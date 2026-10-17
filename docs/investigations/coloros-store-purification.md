# 国行 ColorOS 软件商店净化（2026-10-17）
> 后续已追加分类与样式净化，当前名称、16 项范围与验证以 [追加记录](coloros-store-category-styles.md) 为准；本文保留首次 12 项实现的调用链记录。

## 实施及版本边界

已实现 12 项独立开关，位于“系统净化 → 应用商店净化”，全部默认关闭，提供简体中文、英文、香港繁体及台湾繁体。只针对 `com.heytap.market` **26.9.4_CN / 260904** 注册 hook；其它版本保持原行为并记录不支持日志。未宣称兼容国际版或其它国行版本。

目标 APK：`/private/tmp/coloros-cn-reference-20261017/_26.9.4_CN.apk`，SHA-256：`391a2fb1e9bd5d22331656b44d42837ef9048ab754b1a72996f24daeb0a49179`。包名和版本来自 APK Manifest、apktool 元数据，非按名称推断。

已读取项目规则。通过 `../android-app-mods/_tools/apktool.jar` 解析资源和 DEX，输出 `/private/tmp/coloros-store-cn/src`；JADX 输出 `/private/tmp/coloros-store-cn/java/sources`。JADX 全包有 224 个反编译错误，因此每个 hook 的运行时类、原始 Unicode 方法名和签名另外用 smali 核实，未使用 JADX 生成的 mXXXXX 别名。国际版参考不作为本次 hook 证据。

## Flyme / ColorOS 范围映射与调用链

Flyme 只读参考：`../flyme-mod/app/src/main/java/dev/rikumi/flymemod/SettingsCatalog.kt` 的现行 12 项设置，以及 `StoreAdHooks.java`、`StoreLayoutHooks.java`、`StoreMineEntryHooks.java`；不移植 Flyme 类名、布局或条目枚举。

下表 ColorOS 类名以本次国行 APK 为准，Unicode 名称为 smali 原名。

| Flyme 现行范围 | ColorOS 设置及实际边界 | 国行代码证据 |
|---|---|---|
| 屏蔽开屏广告 | 屏蔽开屏广告；使用原生无广告分支，保留启动初始化 | `a.a.a.w3f#isSupportSplash/hasSplash` → `kp9` 启动协调器；无广告进入 `hue`，后续 `jud` 初始化保留；`SloganRemoveManager` 同时读取这两个判定 |
| 去除精选 Tab | 去除首页 Tab；将应用页移至首位，校正选中索引 | `TabDataPresenter#ނ` → `a.a.a.k7a` pager；ID 10 为 Home、20 为 App、30 为 Game、50 为 Mine；`a.a.a.n3b#ֈ(List)` 独立更新导航 |
| 去除游戏 Tab | 去除游戏 Tab；保留其它导航 | 同上，仅过滤 ID 30，不按显示名称匹配 |
| 去除首页全部内容 | 去除首页及应用首页的已确认内容卡片；保留搜索和父级导航 | `HomeChosenCardAdapterPresenter`、`AppChosenCardFragment`、`HomeChildCardFragment` → `CardFragment#ຑ`；仅标记这些实例的 adapter，在 `setDatas/addDataAndNotifyChanged` 传空列表 |
| 去除我的页推荐 | 去除我的页应用推荐，保留管理、安全、卸载等卡片 | `MineFragment#ࢯ(ViewLayerWrapDto,boolean)`，在线及缓存共用；过滤 `AppCardDto/AppListCardDto/ResourceListCardDto/DailyRecommendListCardDto`；管理卡片使用不同 DTO |
| 去除我的预约及心愿单 | 去除我的预约入口；未确认国行有独立心愿单，不虚设对应功能 | 我的页 `NavCardDto/BannerCardDto` 的 `BannerDto.actionParam`：`oap://mk/booked`、`/new_booked`、`/booked/event` |
| 去除福利活动及快应用 | 去除福利卡片及已确认原生入口 | 卡片 40149、40156；原生 `/welfare`、`/welfare/home`、`/welfare/stimulation`、`/vip/welfare`、`/card/instant`、`/contentflow/instant`；`xn2` 与 `a6g` 确认 `/cardstyle[/recmd]?p=/page/instant` 的快应用路径 |
| 去除手机瘦身入口 | 去除空间清理入口 | `TrashCleanCard` 40080、`SpaceDeepCleanCard` 40087、`/tshcln`；`ToolsCardDto` 100101 中 type=2 为清理、type=1 为安全（`asa`），仅去除 type=2 |
| 去除应用详情页推荐 | 去除详情内联推荐和旧推荐页内容，保留详情本体 | `com.heytap.cdo.client.detail.cn.app.tabcontent.detail.a#ԭ(detail.b)` 在推荐合并之前跳过；`recommend.TabRecommendContentView#ޞ(sn2)` 传 null，使用原生空内容完成分支 |
| 去除下载管理页推荐，包括 Flyme 下载后推荐 | 去除国行下载管理页的推荐与推荐附加间距；独立下载后页面不在本次已确认覆盖范围 | `BaseRecyclerViewDownloadFragment$e/$f#Ԫ(ik3)` 仅推荐结果回调；外部 fragment 必须为 `DownloadManageFragment`，不处理下载任务或文件接口 |
| 去除应用更新页推荐 | 去除更新页推荐，保留更新列表 | 同一推荐回调，但外部 fragment 必须为 `AppUpdateFragment`；更新列表的独立数据链未拦截 |
| 去除搜索框热词及搜索页推荐 | 去除热词轮播，空查询页仅保留历史；保留输入、联想和结果 | `search.core.recommend.a#getNextRecommendInfo/getRecommendWordSize`；`SearchHomeCardAdapterPresenter` 标记的 adapter；`ml2` 注册表确认历史卡片 161、154、40117、100204 |

DTO 完整包前缀为 `com.heytap.cdo.card.domain.dto.`；页面前缀为 `com.heytap.cdo.client.cards.page.`。推荐卡片编号及 DTO 对照来自该 APK 注册表/绑定代码，入口来自实际路由定义。未知网页活动链接不按标题、URL 关键词或泛化正则删除，避免误删核心入口。服务端可能新增其它类型卡片，本次未保证覆盖未知配置。首页二楼等未确认支路也不宣称已覆盖。

## 关闭恢复与核心功能

- 所有行为分支调用项目 `readBool(key, false)`，不使用首次读取可能未就绪的缓存接口。
- 不修改网络或磁盘缓存。我的页复制 DTO 和被过滤列表；导航复制 StructDto，保持原始缓存对象。相同原始结构共享可见副本，以维持导航、pager 与深链接的选择一致性。
- 关闭开关后，下一次原生数据绑定/重新进入页面使用原始内容；已经显示的页面不主动重载。需要用户重进页面或自行重启商店后验收恢复。
- 不拦截搜索结果、联想、详情本体、下载任务、安装或更新列表；不修改 Android View/LayoutParams，不使用隐藏后还原尺寸的快照。
- 核心功能保留是调用链与实现范围的静态结论，尚未经过实际设备验收。

## 修改文件

- `StorePurificationHooks.java`：版本检查及 12 项净化 hook。
- `MainActivity.kt`：按现行分组风格增加 12 个 SwitchItem。
- `XposedInit.java`、`AndroidManifest.xml`、`META-INF/xposed/scope.list`：包名路由、可见性及作用域。
- 四语言 `store_purification.xml`：分组与开关标题，无新增副标题。
- `scripts/check-store-purification.py`：针对目标 APK 的静态契约检查。

共享工作区其它改动保留；未暂存、构建、安装、重载、commit、push、操作 issue 或联系其它子会话。

## 验证结果

执行 `python3 scripts/check-store-purification.py`：**117 项检查通过**，包括 APK 摘要/包名/版本、smali 真实签名及非抽象方法、构造器、DTO getter/setter、12 个偏好键及默认关闭、四语言资源、路由及作用域。`git diff --check` 通过。

这是静态契约验证，不是 Java/Kotlin 编译或运行时测试。JADX 的全包错误不作为本次实现已编译的证据。本轮按要求不构建，交由主会话统一构建并发送 APK。没有设备验收结果，目前无 APK 获取阻塞。

## 设备验收步骤（待用户执行）

1. 主会话构建后，用户自行安装并在模块作用域勾选该版本软件商店，自行重启商店。全关闭检查原始首页、我的、搜索、详情、下载及更新。
2. 逐项独立开启并重新进入对应页：冷启动及缓存启动无开屏广告；首页/游戏 Tab 删除后首选项与点选内容一致，返回和深链接正常。
3. 清空首页后确认搜索仍可输入；搜索净化保留历史、联想及真实搜索结果，热词与空查询推荐消失。
4. 我的页分别开启四项，确认预约、福利/快应用、清理、推荐各自独立；账户、安全、设置、下载、更新入口及剩余条目排布正常；覆盖在线、离线缓存及返回页面。
5. 详情、下载、更新推荐分别测试；完成一次搜索后下载、暂停/继续和更新，检查推荐清除不影响核心列表。独立下载后页面与未知服务器运营卡片需另行取证，不能视为本轮已覆盖。
6. 组合开启全部选项，再逐项关闭并重新进入对应页面，确认原始内容恢复。开屏恢复需自行冷启动。观察导航选中位置、空白间距及历史缓存，不仅凭日志判断生效。
