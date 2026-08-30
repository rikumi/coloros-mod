# coloros-mod 记忆

> 通用硬性规则（设备重启与进程重载、设备操作权限、日志规范、逆向分析流程）见 `.codebuddy/rules/coloros-mod.md`。
> 本文件只记录**当前实现**：每个功能的作用域、hook 点、关键字段与必须遵守的约束。

## 一、工程结构与通用机制

### 目标环境

- 设备：Android 16（API 36）+ ColorOS 16.1.0（PKT110_16.0.10.500(CN01)），density ≈ 2.96。
- 反向分析一律走 `../android-app-mods` 工具链（`pull` → `backward.sh` → JADX → `dexdump`）核对类名/方法名/签名，
  已有反编译产物优先复用。本文档中出现的具体混淆名（如 `d0` / `I` / `A` / `mMaxVisualWidth`）均已核对。

### 模块入口

- 新框架（libxposed）API：入口类 `XposedInit extends XposedModule`，声明在 `META-INF/xposed/java_init.list`，
  作用域在 `META-INF/xposed/scope.list`，配置在 `META-INF/xposed/module.prop`。
- 旧接口 `XposedHelpers` / `XposedBridge` / `XC_MethodHook` / `XC_LoadPackage` 由模块自建兼容层提供，
  位于 `com.rikumi.colorosmod.xposed`，hooks 下代码无需改动（import 一律指向该包）。
- `onPackageLoaded` 与 `onSystemServerStarting` 都会走到 `handleLoadPackage`，用 `sIsSystemServer` + `sSystemServerHooked`
  去重：普通应用进程里也会加载 `android` 包，那里的 system_server hook 找不到类。
- 分发（`handleLoadPackage`）：
  - `com.android.launcher` → `LauncherHooks.hookLauncher`
  - `com.android.systemui` → `SystemUiHooks.hookSystemUi`
  - `com.oplus.safecenter` → `SafecenterHooks.hookSafecenter`
  - `com.android.settings` → `SettingsHooks.hookSettings`
  - `android`（system_server）→ `SystemServerHooks` 的四个入口（贴边挂机、挂机静音、横屏小窗比例、小窗尺寸限制）
- `SystemUiHooks` 只做转发，实现按类别拆分：`QsHooks` / `NotificationHooks` / `StatusBarHooks` /
  `KeyguardHooks` / `PasswordInputHooks` / `StatusBarLyricHooks` / `GestureHooks`。

### 开关读取（跨进程）

- 设置存 `PREF_NAME="settings"`，通过 ContentProvider 暴露给被 hook 进程：`content://com.rikumi.colorosmod.settings/<key>`。
  走 Binder 通道，不受 SELinux 对 `app_data_file` 的限制（这也是不用 `MODE_WORLD_READABLE` / 共享文件的原因）。
- 取值顺序（`XposedInit#settingsValue`）：后台预热的全量快照（零 IPC）→ 首次读取时有限等待预热 → 同步 Provider 查询 → 粘性缓存。
- 后台预热（`startSettingsLoader`）：注入即起守护线程拉全量快照，首成功前每 500ms 重试，之后每 5s 刷新。
  必要性：部分 hook 只在初始化时读一次（如手势条高度在导航栏创建时读取），开机早期模块 App 还没起来拿不到值，
  默认值会被固化；预热让"开机早期 + 只在初始化读一次"的场景也能拿到真实值。
- `readBool(key, def)` / `readInt(key, def)`：Provider 里没有的键回落到调用方默认值。

### 日志

- 只提供 `log(String)` 与 `dbg(String)`，两者都是 `Log.e(TAG, msg)`（`TAG="ColorOSMod"`）。
  必须用 error 级别：ColorOS 会丢弃 `Log.d/v/i/w`。不写文件，避免 IO 卡顿与被 hook 进程无存储权限触发 FUSE 报错。
- 每个功能统一注入成功/失败一行：`log("HOOK OK 类#方法 (功能)")` / `log("HOOK FAIL ...")`。

### 设置界面

- `MainActivity.kt`：COUIX（Miuix 的 COUI 变体）风格 Compose 界面，首页是分类卡片 `CATEGORY_GROUPS`，点进子页面是开关列表。
- `SwitchItem(key, label, subtitle?, sliderKey?, sliderMax, sliderDefault, sliderUnit, sliderMin, dividerBefore)`：
  `sliderKey` 非空时开关打开后下方出现滑条；`dividerBefore` 为界把列表拆成多张卡片。
- 开关无 `defaultOn` 字段，一律默认关闭。滑条默认值见下一节。

### 构建与部署

- 构建：`./gradlew :app:assembleDebug`（或 `assembleRelease`）。
- 部署：`adb install -r app/build/outputs/apk/debug/app-debug.apk`，装完在 LSPosed 里勾选作用域并**强制停止再启动**目标 App。
- 重载：SystemUI `adb shell su -c 'pkill -f com.android.systemui'`；Launcher `pkill -f com.android.launcher`。
- 改 `android`（system_server）作用域的 hook 必须重启 Zygote 才生效（需先征得用户同意，只允许 `setprop ctl.restart zygote`）。
  设置界面「应用小窗」分类的 hint 已提示这一点。

## 二、设置项总览

| 分类 | 设置项 | key | 滑条（范围 / 默认） |
| --- | --- | --- | --- |
| 桌面 | 增加图标与名称间距 | `icon_gap_enabled` | `icon_gap_dp` 0-8 / 4 |
| 桌面 | 减小页面与 Dock 间距 | `indicator_enabled` | `indicator_dp` 0-32 / 16 |
| 桌面 | 取消编辑模式背景遮罩 | `edit_mode_bg_transparent_enabled` | — |
| 桌面 | 缩小图标长按菜单 | `shrink_popup_menu` | `popup_scale_percent` 0-20 / 10 |
| 桌面 | 长按菜单背景动态模糊 | `popup_dynamic_blur_enabled` | — |
| 桌面 | 自定义桌面长按背景亮度 | `desktop_popup_bg_brightness_enabled` | `desktop_popup_bg_brightness` 0-10 / 0 |
| 桌面 | 文件夹展开背景透明 | `folder_bg_transparent_enabled` | — |
| 桌面 | 调整文件夹动画持续时间 | `folder_anim_duration_enabled` | `folder_anim_duration_ms` 100-500 / 300 |
| 控制中心 | 自定义控制/通知中心背景亮度 | `qs_scrim_translucent_enabled` | `qs_scrim_brightness` 0-20 / 5 |
| 控制中心 | 去除控制中心运营商显示 | `qs_carrier_enabled` | — |
| 控制中心 | 隐藏控制中心顶部状态图标簇 | `qs_topmargin_enabled` | — |
| 控制中心 | 分离版 Wi-Fi / 蓝牙名称单行省略 | `qs_tile_name_ellipsis_enabled` | — |
| 控制中心 | OxygenOS 控制中心恢复正常圆角 | `qs_normal_corner_radius_enabled` | — |
| 状态栏与通知中心 | 通知左滑直接清除 | `notification_swipe_to_dismiss_enabled` | — |
| 状态栏与通知中心 | 通知下滑展开 | `notification_pull_expand_enabled` | — |
| 状态栏与通知中心 | 缩小通知静默区域副标题 | `notification_subtitle_enabled` | `notification_subtitle_sp` 0-16 / 8 |
| 状态栏与通知中心 | 增加通知上下内边距 | `notification_padding_enabled` | `notification_padding_dp` 0-8 / 4 |
| 状态栏与通知中心 | 流体云出现时不隐藏电量百分比 | `fluid_cloud_keep_percent_enabled` | — |
| 状态栏与通知中心 | 状态栏显示歌词 | `statusbar_lyric_enabled` | — |
| 锁屏 | 解锁时关机无需校验密码 | `unlocked_shutdown_noverify_enabled` | — |
| 锁屏 | 取消密码界面控件光效 | `keyguard_no_light_effect_enabled` | — |
| 锁屏 | 密码界面支持侧滑/下滑返回 | `keyguard_bouncer_swipe_back_enabled` | — |
| 锁屏 | 自定义密码界面背景亮度 | `keyguard_bouncer_brightness_enabled` | `keyguard_bouncer_brightness` 0-5 / 0 |
| 锁屏 | 锁屏通知区域下移 | `keyguard_notification_offset_enabled` | `keyguard_notification_offset_dp` 0-40 / 20 |
| 锁屏 | 密码支持滑动输入 | `keyguard_slide_input_enabled` | — |
| 隐藏应用 | 多任务显示已隐藏应用 | `recents_show_hidden_enabled` | — |
| 隐藏应用 | 打开隐藏应用文件夹免验证 | `hide_apps_noverify_enabled` | — |
| 隐藏应用 | 桌面双指张开打开隐藏应用 | `pinch_out_open_hide_apps_enabled` | — |
| 隐藏应用 | 应用隐藏标题显示文件夹名 | `hide_apps_title_folder_enabled` | — |
| 隐藏应用 | 彻底隐藏电话本图标 | `hide_contacts_enabled` | — |
| 隐藏应用 | 彻底隐藏 Gboard 图标 | `hide_gboard_enabled` | — |
| 隐藏应用 | 彻底隐藏 GhostLock 图标 | `hide_ghostlock_enabled` | — |
| 应用小窗 | 多任务隐藏小窗应用 | `recents_hide_freeform_enabled` | — |
| 应用小窗 | 悬浮小窗贴边挂机 | `float_window_edge_hang_enabled` | — |
| 应用小窗 | 小窗贴边挂机静音 | `float_window_edge_hang_mute_enabled` | — |
| 应用小窗 | 锁屏后保持挂机 | `float_window_edge_hang_keep_on_lock_enabled` | — |
| 应用小窗 | 横屏应用小窗保持比例 | `float_window_landscape_keep_ratio_enabled` | — |
| 导航与手势 | 调整手势滑动条宽度 | `gesture_bar_width_enabled` | `gesture_bar_width_dp` 80-120 / 100 |
| 导航与手势 | 增大底部手势区高度 | `gesture_bar_height_enabled` | `gesture_bar_height_dp` 0-24 / 12 |
| 导航与手势 | 禁止手势条动画效果 | `gesture_bar_long_press_disable_enabled` | — |
| 导航与手势 | 启用 mBack | `mback_enabled` | — |
| 导航与手势 | 避免手势区域点击穿透 | `gesture_touch_through_enabled` | — |

未在设置界面暴露、仅存于 prefs 的键：

- `settings_home_icon_style`（0=系统默认 / 1=不规则 / 2=圆形），作用于 `com.android.settings` 首页图标。

## 三、SystemUI 实现（com.android.systemui）

### 控制中心（QsHooks）

**去除控制中心运营商显示** `qs_carrier_enabled`

- hook `com.oplus.systemui.qs.OplusQuickStatusBarHeader#onFinishInflate`，after 里按 id 找 `qs_carrier_text` 与
  `carrier_group` 置 `GONE`（关闭开关时还原 `VISIBLE`，门控即时生效）。
- 只处理经典（合并）模式：再 hook 分离模式的 `SeparateQSFakeStatusController` 会与经典模式叠加。

**隐藏控制中心顶部状态图标簇** `qs_topmargin_enabled`

- 真正执行图标簇位移动画的是 `com.oplus.systemui.qs.fake.OplusQSFakeStatusController$qsPanelExpandFractionListener$1`
  `#onFractionChanged(float)`（该版本没有 `OplusQuickStatusBarHeader#setExpansion`）。
- before/after 都调一次：`fraction > 0` 时把 `quickStatus`、`fakeStatusIconContainer`、
  `statusBarHeader.getMStatusIconsView()` 置 `INVISIBLE`（在原生 translation 之前就阻止移动动画可见；
  展开回调里原生会同时移动状态栏与 QS 顶栏两个节点）。
- 页脚：`com.oplus.systemui.qs.OplusQSFooterImpl` 的 `updateResources$15` / `updateResources`（按顺序尝试），
  after 里给 `mSettingsContainer` 叠加 8dp 顶部 padding，让日期/设置按钮小幅下沉。

**自定义控制/通知中心背景亮度** `qs_scrim_brightness`（0-20，默认 5）

- 承载背景的是"背后 scrim"：默认 `top=LUMINOSITY+#99333333` 把亮度归一化到约 0.2，
  该混色在 AGSL shader 里合成、位于窗口内容之下（这也是纯黑壁纸下背景反而发灰的原因，且不是某个遮罩 View）。
- hook `com.oplus.systemui.statusbar.phone.ScrimControllerExImp#getPanelPlatformMixConfig()`，after 里
  限定 result 类名含 `BlurMixSingleWithShader`，按 `gray = brightness * 0x33 / 20` 构造
  `backgroundShaderParam(mode=5 LUMINOSITY, argb(0x99, gray,gray,gray), bottomMode=2 OVERLAY, 0)` 并写回字段。
  alpha 保持 0x99（与系统默认同强度），结果亮度直接等于 top 层 RGB 亮度。

**分离版 Wi-Fi / 蓝牙名称单行省略** `qs_tile_name_ellipsis_enabled`

- 目标类 `com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileViewTwoXOne`，
  hook `handleTileStateChange(QSTile$State)` 与 `updateLabelDescText(QSTile$State)`，after 里对字段
  `labelTitle`、`labelDesc` 递归强制单行 + 行尾省略（`forceSingleLineEllipsis`，对 `TextSwitcher` 额外覆盖
  `getNextView()`；`applyEllipsis` 里 `setHorizontallyScrolling(false)` 关掉跑马灯）。

**OxygenOS 控制中心恢复正常圆角** `qs_normal_corner_radius_enabled`

- 系统用 `FlavorTwoFeatureOption.isFlavorTwoDeviceExp()`（一加品牌 && 海外 exp 区域）判 OxygenOS，
  命中时高亮磁贴（Wi-Fi/蓝牙）与滑条（音量/亮度）用 60dp 大圆角，否则 16dp。开启后统一强制到
  `QS_CORNER_RADIUS_DIMEN`（默认 `qs_hl_tile_corner_radius_circle`，16dp）那一档。
- 滑条：hook 基类 `com.oplus.systemui.qs.base.seek.OplusQsBaseToggleSliderLayout#setCornerRadius(float)`，
  before 改 `args[0]`。该方法是 final 且合并式/分离式两个子类都继承它，一次 hook 覆盖两种模式。
- 高亮磁贴轮廓：hook `StdQSTileResInteractor$startHighlightTileOutlineCollection$2#invokeSuspend(Object)` 与
  分离式的 `SepQSTileResInteractor$startHighlightTileOutlineCollection$2`，after 里限定返回类型是
  `RoundRectOutlineProvider`，改用 `QSConstant#getSmoothRoundRectOutlineProvider(context, radius)` 构造同口径
  provider（保留"平滑圆角"权重，否则 60dp 这类大半径会被降级成方角）。

### 通知（NotificationHooks）

**缩小通知静默区域副标题** `notification_subtitle_sp`（0-16sp，默认 8）

- hook `com.android.systemui.statusbar.notification.stack.SectionHeaderView#onFinishInflate`，after 里：
  `mLabelView` 字号设为 `(24 - reduceSp) sp`、右移 `8dp * t`、上下各加 `4dp * t` padding；
  `mContents` 的 `paddingTop` 减 `8dp * t`（`t = reduceSp / 8`，缩减为 0 时即系统默认）。
- 上移必须改 `mContents` 的 paddingTop，不能用 `translationY`：文字顶端越界会被 `clipChildren` 裁掉。

**增加通知上下内边距** `notification_padding_dp`（0-8dp，默认 4）

- 非静默通知：hook `ExpandableNotificationRow#onNotificationUpdated`，after 里对 `mPrivateLayout` /
  `mPublicLayout` 的 `mContractedChild` / `mExpandedChild` / `mHeadsUpChild` 上下各加 `padPx`。
  直接改子视图 padding 才能被 `NotificationContentView#getViewHeight` 计入高度，整卡才会增高；
  原始 padding 记录在 view tag（`TAG_NOTIF_PAD_TOP/BOTTOM`）里，保证反复更新幂等、关闭时可还原。
- 合并通知由 `NotificationChildrenContainer` 统一布局，给每个子通知加 padding 只会撑开某一行，
  故另 hook 该容器的 `getIntrinsicHeight`、`onMeasure`（同时补 `mRealHeight`）、`onLayout`（子 View 整体
  `offsetTopAndBottom(padPx)`）、`applyState`（`mGroupHeader` / `mMinimizedGroupHeader` 下移），
  以及两处 ext 实现的 `layoutOplusHeader(NotificationChildrenContainer)`（`oplusHeaderWrapper.mView` 下移）。
- 最小化（`mIsMinimized`）的分组与静默通知保持原样。
- 高频路径（`onMeasure`/`onLayout`/`applyState`）只读 volatile 缓存 `sNotifPadEnabled` / `sNotifPadPx`，
  由低频的 `onNotificationUpdated` 刷新，避免每帧 IPC。

**通知左滑直接清除** `notification_swipe_to_dismiss_enabled`

走海外 exp 分支，两处必须同时改（均已 dexdump 核对，同在 SystemUI classes3.dex）：

- hook `com.oplus.systemui.notification.row.NotificationMenuRowExtImpl#createMenuViewsExt(boolean,
  NotificationMenuRowPlugin, ArrayList, Context, boolean, boolean)`，after 里 `ArrayList.clear()`
  （exp 分支在方法末尾清空），并把 `settingsItem` / `deleteItem` 两个字段置 null —— 否则 `onDismissRow()`
  会拿这两个未挂载的 View 跑移除动画。
- hook `com.oplus.systemui.notification.row.swipe.OplusSwipeHelperExImpl#shouldNotShowMenuExt(MotionEvent,
  View, float, NotificationMenuRowPlugin)`，before 里对 `ExpandableNotificationRow` 直接返回 true，
  使 `NotificationSwipeHelper#handleMenuRowSwipe` 跳过"吸附露出菜单"分支、走 dismiss / snapClosed。
- 不 hook `FeatureOption.isExpRegion()`：全 SystemUI 有 150+ 处调用，影响面不可控。

**通知下滑展开** `notification_pull_expand_enabled`

国内版关掉了 AOSP `ExpandHelper`（单指下拉通知展开），两处判断都要补（均已 dexdump 核对）：

- hook `com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout`
  构造 `(Context, AttributeSet)`，after 里 `setExpandingEnabled(true)`（国内版构造末尾会置 false，
  exp 分支什么都不做，保留 `expandHelper.mEnabled = true`）。
- hook `com.oplus.systemui.statusbar.notification.stack.NotificationStackScrollLayoutExtImpl`
  `#setExpandingEnabled(boolean)`，before 里透传给 `getView()` 并 `setResult(null)`
  （等价接口的 default 实现，跳过 exp 判断；唯一调用方传 `!onKeyguard()`，保留"锁屏上不展开"的原生语义）。

**通知图标区显示模式（状态栏歌词专用）**

- `com.oplus.systemui.statusbar.icon.data.OplusNotificationIconAreaRepository` 的
  `notificationShowMode`（MutableStateFlow）：0=图标 / 1=数字 / 2=不显示，由系统设置观察者写入。
- hook 其构造 `(Context, CoroutineScope, DumpManager)` 抓实例，并起 500ms 轮询：只在目标值与当前值不同时
  `setValue`（歌词状态变化不会触发系统观察者；儿童模式/专注模式下仓库仍输出"不显示"，那是系统行为，保留）。
- 下发统一由本类负责（歌词与别处各写一份会互相拉扯，图标与数字来回跳）。

### 状态栏（StatusBarHooks）

**流体云出现时不隐藏电量百分比** `fluid_cloud_keep_percent_enabled`

- 流体云胶囊出现时 `BatteryStyleModel.capsuleShowing=true`，令 `PercentOutIcon.isVisible=false`，电量百分比被隐藏。
- hook `com.oplus.systemui.statusbar.pipeline.battery.ui.binder.BatteryViewBinder`
  `#bind$updatePercentOutView(TextView, StatBatteryMeterView, PercentOutIcon)`，before 里把 `args[2]` 的 `isVisible` 置 `true`。

### 状态栏歌词 `statusbar_lyric_enabled`（StatusBarLyricHooks）

**挂载**：hook `com.android.systemui.statusbar.phone.PhoneStatusBarView#onAttachedToWindow`，after 里
`attachLyricView(root)` + `initMediaListener(ctx)`（媒体监听只注册一次，开关在 `refreshLyric` 里判断，保证实时生效）。

**视图**：宿主容器为 `status_bar_start_side_content_for_fake`（LinearLayout；取不到时依次退回
`notification_icon_area` / `clock` 的父容器），歌词插在 `notification_icon_area` **之后**，紧跟通知图标右侧。

- 容器 `LyricClipLayout`（FrameLayout 子类），`dispatchDraw` 里 `canvas.clipRect` 到 padding 盒子，
  保证左右都按盒子裁（不依赖 `setClipChildren`，它在被 RenderNode 动画驱动的子 View 上不可靠）。
- 两个 `TextView`（`sOutgoingView` / `sIncomingView`，`MATCH_PARENT` + `gravity=start|center_vertical`）做向上切换动画；
  字号/颜色从 `clock` 复制，字重改 medium(500)（时钟是 semibold，直接抄会偏粗）。
- 整体 `translationY = -1dp` 做视觉校准（不参与测量/布局）。
- 文本宽度：字幕 `TextView` 的宽度设为**整句文本的真实宽度**（`measureText + 2px`，`EXACTLY`），
  裁切只发生在歌词容器上——若 TextView 宽 = 容器宽，文本会先被截断再平移，会看到"半个字在窗口里滑"。

**可用宽度** `updateLyricWidth(root, host, container)`（每次 tick 重算，无变化则跳过）：

- 起点 = 宿主左边缘 + 前面兄弟宽度（含 margin）+ 自身左外边距 4dp。不能直接量容器自己（GONE 时坐标不更新）。
- 右边界取三者最小：`status_bar_start_side_container` 右边缘、`cutout_space_view` 左边缘、
  流体云 `seeding_card_container` 内可见内容的最小左边缘再让开 4dp。
  不能用 clock 定位（隐藏后坐标返回 0）；流体云外层容器铺满状态栏，必须递归下钻找实际内容，
  故对每个 ViewGroup 都继续递归取全局最小 left。

**数据源**（无需注入音乐软件）：`MediaSessionManager#addOnActiveSessionsChangedListener(null, …)`（SystemUI 已具备
`MEDIA_CONTENT_CONTROL`），对每个会话注册 `MediaController.Callback`，取 `PlaybackState == STATE_PLAYING(3)` 的那个。

- 歌词在 `MediaMetadata` 的 `"lyricInfo"`（JSON）→ `"lyric"` 字段原文。
- 解析兼容 LRC（`[mm:ss.xx]文本`）与 JSON 行（`{"t":毫秒,"c":[{"tx":"文本"}]}`，`c` 内 `tx` 拼接成整句），
  解析后按时间升序排序，取最后一个 `timeMs <= position` 的行。
- 进度估算 `estimatePosition()`：`PlaybackState#getPosition` 是快照，需按 `speed` 与经过时间外推，否则歌词滞后。
- 轮询 `TICK_INTERVAL_MS = 250`。

**时钟隐藏**：显示期间只隐藏**时钟本身**（id `clock`）——歌词挂在通知图标之后，隐藏整个 start_side 块会把歌词一起藏掉。

- 沿时钟类链（子类一路到 `View`，只 hook 自己声明了 `setVisibility` 的类）拦截 `setVisibility`：
  隐藏期间一律改 `GONE`，并记下系统期望值以便还原（锁屏、下拉通知、Dock 等时机系统会改回）。

**颜色**：hook 时钟类的 `onDarkChanged(ArrayList, float, int)`，after 里同步歌词文字色
（时钟是 `DarkReceiver`，切壁纸/进浅色应用会经此刷色；先把比对再赋值，避免无谓 invalidate）。

**下拉隐藏**：复用 QS 展开进度回调 `com.oplus.systemui.qs.fake.OplusQSFakeStatusController$qsPanelExpandFractionListener$1`
`#onFractionChanged(float)`，`fraction > 0` 即隐藏（面板与状态栏是两个独立窗口、视图树不相通，
读面板 translationY 行不通）。隐藏不清除 `sCurrentText`，收起后直接续滚。

**横向滚动**：超长歌词停顿 500ms 后开滚，固定速率 0.1 px/ms（全 ASCII 时 2 倍，按 ASCII 占比线性插值，空白不计入统计；
西文字符窄、同速率读起来偏慢）。用固定速率而非固定时长，避免长句越滚越快。
`sCurrentText` 用于判断文本是否真变化，防止动画被反复重启；宽度变窄（流体云出现）后调用
`syncScrollToAvailableWidth()` 接着往下滚，否则尾巴一直被挡住。

**通知图标模式**：歌词显示期间由 `NotificationHooks` 统一把通知图标区切到"显示数字"
（见「通知（NotificationHooks）」一节），且每拍重新声明一次（系统会回滚该模式）。

### 锁屏（KeyguardHooks）

**解锁时关机无需校验密码** `unlocked_shutdown_noverify_enabled`

- 唯一闸门是 `com.oplus.systemui.shutdown.ShutdownBiometricPrompt#isEnable(Context)`。
- hook 该方法，after 里：返回 true 且设备已解锁（`KeyguardManager.isKeyguardLocked()` 与 `isDeviceLocked()` 均为 false）时改 false。
- `isDeviceLocked` 取不到状态时返回 true（保守，退回系统原生校验）。

**锁屏通知区域下移** `keyguard_notification_offset_dp`（0-40dp，默认 20）

- 通知区顶部位置有三个来源，三者汇入 `updateTopPadding` 且参与内部差值计算，故叠加同一偏移量以保持差值与动画起止值不变：
  1. `com.android.systemui.shade.NotificationPanelViewController#getKeyguardNotificationStaticPadding`
     —— 非锁屏时原始返回 0，只在 `isKeyguardShowing()` 为真时叠加；
  2. `com.oplus.systemui.notification.lockscreen.stack.OplusLockscreenShadeTransitionControllerExImpl#getNtfTopPaddingInLockscreen`
  3. 同上类的 `#getNtfTopPaddingInLockscreenNtfCenter`

**密码界面支持侧滑/下滑返回** `keyguard_bouncer_swipe_back_enabled`

- 收起走系统同一入口：`StatusBarKeyguardViewManager#onBackPressed()`。实例通过 `XposedBridge.hookAllConstructors`
  缓存（免签名，且不依赖某个方法被调用）。
- 下滑（数字键盘）：`com.coui.appcompat.lockview.COUINumericKeyboard#onTouchEvent`。
  起点判定主用私有 `checkForNewHit(FF)`（命中为空=间隙，或索引 9 / 11=侧键才允许），反射失败时几何兜底
  （底行 `y > 0.75h` 且左右侧列）。
- 下滑（字母键盘）：`com.oplus.securitykeyboardui.SecurityKeyboardView#onTouchEvent`。
  起点判定用 `getKeyIndices(x, y, null)` → `mKeys[idx].codes[0]`，只排除数字键 `'0'-'9'`。
- 阈值自适应：`min(48dp, 起始点到屏幕底距离 * 0.6)`。固定 48dp 时最底行的 0 两侧键与删除键滑不出阈值。
- 侧滑：`com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector#onMotionEventImpl`，
  primary bouncer 显示时临时改 `mSysUiFlags`：清 bit6（`1<<6` STATUS_BAR_KEYGUARD_SHOWING）+ 置 bit17
  （`1<<17` ALLOW_GESTURE_IGNORING_BAR_VISIBILITY），after 还原。
- 提示文案：`com.android.keyguard.KeyguardMessageAreaController#setOplusBouncerMessage(int, String, boolean)`，
  含"上滑"且"指纹"的文案替换为"下滑返回指纹解锁"。

### 密码界面（PasswordInputHooks）

**取消密码界面控件光效** `keyguard_no_light_effect_enabled`

COUI 给锁屏密码控件叠了三类非纯色绘制，分别跳过，去掉后只留纯色背景与描边（按下缩放/变色反馈不受影响）：

| hook 目标 | 处理 |
| --- | --- |
| `com.coui.appcompat.lockview.COUINumericKeyboard#drawLightEffect(Canvas,int,int)` | `setResult(null)`（径向渐变光晕） |
| 同类 `#drawInnerShadowLayer(Canvas,float,float,Cell,int,int,float)` | 先按入参画纯色圆再 `setResult(null)` |
| 同类 `#drawInnerBorder(Canvas,float,float,Cell,int,int,float)` | `setResult(null)`（高光描边 + 常规描边） |
| `com.coui.appcompat.lockview.COUISimpleLock#drawGlowEffect(Canvas,int,int,int,int)` | `setResult(null)`（已输入圆点光晕） |
| 同类 `#drawFilledRectangleWithScale(Canvas,int,int,int,int)` | 前后把 `mCircleScales[args[5]]` 临时置 1.0f（去圆点缩放动画） |

- 纯色圆完全沿用系统入参：圆心 `(cx+tx, cy+ty)`、半径 `mNumberBackgroundRadius * mButtonScale`、透明度 `alpha*255`，
  动画天然同步。
- 取色：`mNumberBackgroundColor` 完全不透明时用它，否则常态 `0x1AFFFFFF` / 按下 `0x33FFFFFF`；
  只认不透明色（系统该值实际是 `#33ffffff`，直接用会导致按下态恒不变）。按下判定用 `cell.pointerId != -1`。
- SIM 卡界面（`mScenesMode == 1` 才走光效分支）：
  - `com.coui.appcompat.input.COUILockScreenPwdInputView#onDraw(Canvas)`：边框 `mBorderLineColor` 置 0 且
    `mBorderPaint = null`（关闭开关时还原缓存原色），`mInnerShadowBitmap` 临时换成 1x1 全透明图，按 `mBackgroundPath` 补纯色底。
  - `com.coui.appcompat.input.COUILockScreenPwdInputLayout#dispatchDraw(Canvas)`：同样处理确定按钮边框与内阴影，
    `mLightEffectAlpha` 临时置 0（>0 才走光晕分支），按 `mNextIcon` 的几何补圆形纯色底
    （按下判定不能用 `nextIcon.isPressed()`，系统从不 setPressed；用 `mLightEffectAlpha > 0`）。
  - 确定按钮的 `mBorderLineColor` 是 final，但反射写入运行时仍生效（值来自资源，非编译期常量）。

**自定义密码界面背景亮度** `keyguard_bouncer_brightness`（0-5，默认 0）

- bouncer 背景 = 模糊壁纸 + 平台混色，混色是 LUMINOSITY 模式叠加 `#99262626`，相当于给模糊加了一层去不掉的"最低亮度"。
- hook `com.oplusos.systemui.common.util.NotifiAndQsPlatformBlurExKt#panelBouncerMixConfig(boolean)`，
  after 里取 result 的 `mixColor`（`mode == 5` 即 LUMINOSITY 才处理），把 `topLayerColor` 的 RGB 按 `k = brightness / 5`
  缩放到灰度，newInstance 出新 `mixColor` 与新 cfg，并补调 `setAlphaWithBlurAmount(!args[0])` 还原方法内的系统行为，
  最后 `setResult(newCfg)`（整体替换，不写 final 字段）。

**密码支持滑动输入** `keyguard_slide_input_enabled`

- 系统原生是"抬起与按下同键才输入"，改为"进入即输入"：接管 `COUINumericKeyboard` 的
  `handleActionDown` / `handleActionMove` / `handleActionUp`（签名同为 `(float,float,int)`），
  圆形命中半径 = `mNumberBackgroundRadius * 2/3`（用 `mNumberBackgroundRadius` 而不乘 `mButtonScale`，
  否则按下后命中区缩小会误触发取消）。
- 只接管数字键（索引 `row*3+column` 为 0-8、10）；索引 9（删除）/ 11（确定）侧键一旦命中或当前按下即交还原生，
  否则原生输入路径被跳过会导致点击失效。
- DOWN 命中即 `callback(idx)` 输入 + 显示按下态 + 震动；MOVE 换键时取消旧键、输入新键；UP 仅取消按下态。
- 按下态按 `mPressEffectStyle`：0 → `initShowAnimator/initFadeAnimator`，1 → `executeLightEffectAnimator(Cell, boolean)`。
- 绘制期缩小：hook `#drawCell(Canvas,int,int)`，前后临时替换 `cell.mButtonScale` 与 `mKeyboardNumberTextAlpha`
  （150ms ValueAnimator 过渡，侧键 9/11 不参与），只影响绘制、不影响命中判定与系统动画写入的真实值。
- 复位看真实 `MotionEvent`（hook `onTouchEvent` 的 UP / POINTER_UP / CANCEL，键盘 disabled 时也复位），
  不能看 `handleActionCancel`：滑经侧键时会误触发。另在 `getEnterAnim` 里复位，避免上次滑动残留。
- 无障碍触摸探索开启时保持系统原生行为。
- 反射调用键盘方法必须 `sKeyboardClass.getDeclaredMethod(...)`（父类 `COUINumericKeyboard`）：
  `XposedHelpers.callMethod` 会把基础类型装箱后匹配，运行时实例是子类 `NumericKeyboardWidget`，会抛 `NoSuchMethodError`。

### 手势与导航（GestureHooks）

**增大底部手势区高度** `gesture_bar_height_dp`（0-24dp，默认 12）

- hook `com.android.systemui.navigationbar.views.NavigationBar#getBarLayoutParams(int)`，after 里：
  `lp.height += extraPx`，并同步 `paramsForRotation[0..3]` 各自的 `height`；两处都调 `bumpInsetsIfPresent`，
  遍历 `providedInsets`，只改 `getInsetsSize()` 返回 bottom > 0 的 provider（`Insets.of(left, top, right,
  bottom + extraPx)`，同时 `setInsetsSize` 与写 `mInsetsSize` 字段）。按 provider 当前 bottom 值筛选最可靠。
- 白条绘制：hook `com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle#onDraw(Canvas)`，
  before 里 `canvas.save()` + `translate(0, -half)`，after 里 `restore()`，让白条随窗口一起长高而位置不变。
- 上移量 `getGestureBarCanvasLiftPx()` = `round(10dp * density * 0.5)`，mBack 定位与防穿透热区都引用它，口径必须一致。

**调整手势滑动条宽度** `gesture_bar_width_dp`（80-120dp，默认 100）

- hook `OplusNavigationHandle#setVertical(boolean)` 与 `#onAttachedToWindow`，after 里改
  `LinearLayout.LayoutParams.width` 并置 `gravity = CENTER`。

**禁止手势条动画效果** `gesture_bar_long_press_disable_enabled`

- hook `com.oplus.systemui.navigationbar.gesture.sidegesture.animator.OplusHandleAnimatorController`
  `#doHandleAnimator(OplusHandleAnimType, float)`：枚举名为 `StartLongPressAnim` 时 `setResult(null)`。
- 同类 `#doHandleOldAnimator(OplusHandleOldAnimType)`：`StartOldLongPressAnim` 时 `setResult(null)`。
- 注：这两处的 `readBool` 兜底默认值为 `true`（Provider 不可达时视为开启）。

**启用 mBack** `mback_enabled`（点击返回，长按回桌面）

- hook `OplusNavigationHandle#handleValidTouchEvent(MotionEvent)`，before 里接管：
  - DOWN 时记录 `mback_in_range`（`ev.getX()` 是否落在 `[viewScreenLeft - padding, viewScreenLeft + width + padding]`，
    与系统 `inGestureXRange` 同口径并放宽 padding）；范围外交还原逻辑，只响应系统手势。
  - 按 `ViewConfiguration.getLongPressTimeout()` 区分：短按 UP 时震动 `VIRTUAL_KEY` + 返回；
    长按到时震动 `LONG_PRESS` + 回桌面（松手再补一次 `LONG_PRESS`）。
  - MOVE 位移超过 20dp（`MBACK_SWIPE_DP`，含向上）即放弃接管（不震动、不触发导航），交还系统手势。
- 返回：`NavigationBarView.getBackButton().getCurrentView()` 的 `sendEvent(0, 0, now)` + `sendEvent(1, 0)`。
  回桌面：优先 `InputManager.injectInputEvent` 注入 `KEYCODE_HOME`（带 `FLAG_FROM_SYSTEM | FLAG_VIRTUAL_HARD_KEY`），
  失败则回落 `getHomeButton().getCurrentView().performClick()`。
- 视觉反馈 `MBackSurface`（Ripple）：挂在 `NavigationBarFrame`（找不到则最近的 FrameLayout）里，
  **纯视觉、绝不能 clickable/focusable/设 OnTouchListener** —— 白条 View 铺满整个导航栏窗口，
  一旦参与触摸分发就变成全宽拦截层。
- 定位：完全跟随白条实际绘制位置，与白条中心严格垂直居中，尺寸 = 白条尺寸 + 四周各 padding
  （`getMBackBandPaddingDp()` = `min(手势条高度dp/2 + 4, 10)`）。
  白条中心 y = source 在 host 中的 top + `viewHeight - mHandleBottom - mHeight/2 - getGestureBarCanvasLiftPx()`；
  `getLocationInWindow` 经矩阵映射已含防烧屏的 `translationY`。
- `mHeight` 与 `mHandleBottom`（资源 `navigation_handle_bottom` = 7dp）缓存在
  `sGestureBarHeightPx` / `sGestureBarHandleBottomPx`，取不到时用 3dp / 7dp 兜底 —— 触摸区域的
  dispatch 独立于拦截层创建时机，计算不能依赖拦截层，否则会退化成"完全不设置区域"。

**避免手势区域点击穿透** `gesture_touch_through_enabled`

- 手势导航下 `NavigationBarExImpl.updateInsetsTouchability` 把导航栏窗口的 `touchableRegion` 限制为白条区域，
  手势带其余部分直接透传。故两段配合：
  1. hook `com.android.systemui.navigationbar.views.NavigationBar$$ExternalSyntheticLambda10`
     `#onComputeInternalInsets(ViewTreeObserver$InternalInsetsInfo)`，after 里把 `touchableRegion` 设为
     「mBack 热区顶部 → 窗口底部」并 `setTouchableInsets(3)`（TOUCHABLE_INSETS_REGION）。
     设成整窗口会挡住底部整条，故只取这一段。
  2. `GestureBlockSurface`：同段矩形（host 整宽 × 热区高）的全宽透明 View，`setOnTouchListener` 恒返回 true 消费触摸。
- 热区顶部 `computeGestureBandTop()`：`host 高 - mHandleBottom - mHeight - padding - 画布上移量`，
  与 `MBackSurface` 同口径；不能用 `getLocationInWindow`（`OplusNavigationHandle` 铺满窗口，其 top 相对 host 恒为 0）。
- 通知/控制中心展开时一律不生效（面板窗口已接管触摸）；面板展开/收起不重算 insets，故另 hook
  `com.oplusos.systemui.navigationbar.OplusNavigationBarView#updateSlippery`，after 里同步拦截层并 `requestLayout()`
  触发 traversal 重算 `touchableRegion`。
- 拦截层严格跟随开关运行期取值：关则立即从视图树移除（否则残留到下次 SystemUI 重启）。

## 四、桌面（com.android.launcher）

### 桌面布局

**增加图标与名称间距** `icon_gap_dp`（0-8dp，默认 4）

- `com.android.launcher.layoutparam.IconParam#getIconDrawablePaddingPx` 与
  `AllAppsParam#getAllAppsIconDrawablePaddingPx`（桌面与抽屉两套参数类），after 里 `+ dp * density`。
- 统一走 `hookPxRuntime`：开关关闭返回原值，滑条值运行时读取，拖动即时生效。

**减小页面与 Dock 间距** `indicator_dp`（0-32dp，默认 16）

- hook `com.android.launcher.layoutparam.HotseatParam#getHotseatBarSizePx`。
  系统把 hotseat 高度变化按 `workspaceTopPercentage` 分摊到 Workspace 顶部 padding，页面实际只移动
  `x * (1 - percentage)`，故不能直接减 requestedDp：反推 `hotseatDelta = requestedPx / (1 - topPercentage)`
  （percentage 夹到 0-0.95）；字段不可用时退回直接 dp→px。

### 桌面弹窗

**缩小图标长按菜单** `popup_scale_percent`（0-20%，默认 10）

- 菜单尺寸由布局与主题属性决定，运行时不解析 dimen（实测长按时无相关 dimen 被读），资源 hook 无效。
- hook `com.android.launcher3.popup.OplusPopupContainerWithArrow#onAttachedToWindow`，after 里 `post` 执行
  `scalePopupContainer`：对字段 `mAllPopupShortcutContainer`（承载背景与所有菜单项，打开动画只缩放外层、不碰它）
  整体 `setScaleX/Y = 1 - pct/100`。
- 轴心 = 箭头位置：`calculatePivotX()` 得外层坐标系下箭头 x，用 `getLocationOnScreen` 差值换算到内层坐标系；
  垂直方向 `mIsAboveIcon` 为真则箭头在卡片底边（`pivotY = h - offY`），否则在顶边（`0 - offY`）。
- 分割线 `R.id.divider` 原高 1px，整体缩放后渲染成 sub-pixel 不可见，故预先把高度设为 `ceil(1 / scale)`。

**长按菜单背景动态模糊** `popup_dynamic_blur_enabled`

系统把"预烘焙模糊壁纸（半径 0.7）+ dragLayer 截图（半径 4.0）"装进 `PopupBlurView` 后只做 ALPHA 渐显，
模糊量恒定。要得到"模糊由浅到深"必须三处配合：

1. `com.android.launcher.wallpaper.WallpaperBlur#getBlurredWallpaper(Launcher, float, EffectResultCallbackImp)`：
   `args[1]` 半径置 0，并反射取 `mBlurCache` 调 `setIsBlurCacheGenerated(false)` 作废缓存
   —— 缓存里存的是系统预烘焙的模糊图，命中时直接返回、完全不走 `blurBitmap`。
2. `com.android.launcher3.popup.PopupBlurHelper#blurBitmap` 的两个重载（`Bitmap` / `HardwareBuffer`）：
   `radius == 4.0f` 时置 0（dragLayer 层）。
3. `com.android.launcher3.popup.PopupBlurView#createBlurAnim(boolean)`：after 里用
   `ObjectAnimator.ofFloat(target, POPUP_BLUR_PROGRESS, progress, open ? 1 : 0)` 替换返回值，
   `target`（`PopupBlurTarget`）存于 additionalInstanceField，沿用原生时长与插值器。
   每帧对 View 施加 `RenderEffect.createBlurEffect(r, r, CLAMP)`，`r = progress * 80`
   （与系统 `PopupScrimView.BLUR_RADIUS` 一致），并把 View alpha 固定为 1（否则又变成透明度渐显）。
   - 返回值**必须**是 `ObjectAnimator`：`OplusPopupContainerWithArrow#onCreateCloseAnimation` 用 `ObjectAnimator`
     变量接收，`ValueAnimator` 会 ClassCastException。
   - 用 `Property` 而非字符串属性名，避开反射 setter。

**自定义桌面长按背景亮度** `desktop_popup_bg_brightness`（0-10，默认 0）

- 壁纸层以 `blendMode = 1`（ONLY_MASK）混入 `popup_blur_blend_color`，结果 `out = wp*(1-a) + blendRGB*a`，
  `blendRGB` 就是系统硬加、去不掉的"最低亮度"。把它按 `k = brightness / 10` 缩放即可线性抵消
  （走系统自己的混合链路，与模糊正交；外叠颜色滤镜依赖合成顺序且无效）。
- 实现：`PopupBlurHelper#blurBitmap` 的 `args[4] == 1` 时缩放 `args[5]`（Color）的 RGB。
  同时也要作废壁纸预烘焙缓存——缓存里混的是未调整亮度的颜色（见上一功能第 1 条，`k < 1` 时同样作废）。

### 桌面文件夹与编辑模式

**文件夹展开背景透明** `folder_bg_transparent_enabled`

- 所有壁纸模糊都汇入 `com.android.launcher3.uioverrides.states.OplusDepthController#setBlur(float, boolean)`
  （唯一收口点），before 里当有文件夹打开（`AbstractFloatingView.getOpenFolder(launcher) != null`，含动画期间）
  **且**停留在桌面时把 `args[0]` 置 0。
- 状态判定必须限定为 NORMAL：系统只按"文件夹开着"给模糊，打开文件夹后上滑进多任务会连带多任务遮罩一起被去掉。
  状态取静态 `com.android.launcher3.states.OPlusBaseState#getTargetLauncherState()`（切换动画期间已是新状态），
  取不到时退回 `StateManager#getState()`。

**调整文件夹动画持续时间** `folder_anim_duration_ms`（100-500ms，默认 300，越界视为不生效）

三条路径都要覆盖：

1. spring 路径（普通动画）：`com.android.launcher3.folder.RtSpringAnimatorWrapper`
   构造 `(COUISpringAnimation, View, String)` after 改 spring force 字段 `A` 的 response（调 `d(ms/1000)`）；
   `setBounceAndResponse(float, float)` before 改 `args[1]`。
2. light 路径：`com.android.launcher3.anim.light.FolderAnimUtil#getAnimDuration(boolean, boolean)` after
   `setResult((long) ms)`；`getLightFolderContentAnimation(boolean, Folder, FolderIcon, FolderAnimPropsHolder, boolean)`
   after 遍历 `AnimatorSet` 子动画 `setDuration`；隐藏应用文件夹 spring props 无效时回退到
   `getSuperLightFolderContentAnimation(boolean, Folder)`（硬编码 360/300ms），同样覆盖。
3. 资源路径：`Resources#getInteger` 覆盖 `config_materialFolderExpandDuration`(`0x7f0b0028`)、
   `folder_close_duration`(`0x7f0b0072`)、`folder_light_close_duration`(`0x7f0b0073`)、
   `folder_light_open_duration`(`0x7f0b0074`)、`folder_open_duration`(`0x7f0b0075`)。

**取消编辑模式背景遮罩** `edit_mode_bg_transparent_enabled`

- 编辑态模糊由状态类提供目标值（`ToggleBarState` / `PagePreviewState` 固定 1.0f），只改最终 `setBlur` 会错过
  状态切换动画，故直接在状态提供值的方法上返回 0（进出都幂等）：
  `getBlurUnchecked(Context)` → `0f`；`getLauncherRootViewBgAlpha(Context)` → `0`；
  `getCellLayoutBgAlpha(Launcher)` → `0`。
- 另 hook `OplusDepthController` 的 `setBlur(float)` / `setBlur(float, boolean)` / `setBlurWithoutAnim(float)`，
  编辑态（当前状态类名以 `ToggleBarState` / `PagePreviewState` 结尾）时把 `args[0]` 置 0。

### 多任务与手势

**多任务显示已隐藏应用** `recents_show_hidden_enabled`

- 系统"隐藏应用"经 `com.oplus.quickstep.privacy.OplusPrivacyManager#isHiddenPkg(String, int)` 判定。
  hook 它，before 里仅当调用栈包含 `com.android.quickstep` 前缀（且类名不含 `lock`）时 `setResult(false)`，
  把绕过限定在多任务渲染/手势路径内，桌面图标隐藏与应用锁不受影响。

**多任务隐藏小窗应用** `recents_hide_freeform_enabled`

- hook `com.oplus.quickstep.data.OplusRecentTasksFilter#filterTaskInfo(int, int, GroupedTaskInfo, ArrayList)`，
  before 里 `getTaskInfo1()` 后用 `TaskUtils.isFlexibleFloatingWindow(TaskInfo)` 判定（兜底 `windowingMode == 5`），
  为真则 `setResult(true)` 剔除卡片。应用本身仍在前台运行。

**桌面双指张开打开隐藏应用** `pinch_out_open_hide_apps_enabled`

- hook `com.android.launcher3.dragndrop.DragLayer#dispatchTouchEvent`，before 里挂被动 `ScaleGestureDetector`
  （缓存在 additionalInstanceField，监听器返回 false 不消费事件）。
  判定用「当前 span − 起始 span > 100dp」且累计 `scaleFactor > 1.2` 做方向校验，避免轻微张开误触发。
- 仅 `Launcher#isInState(LauncherState.NORMAL)` 时响应，编辑态交回系统（系统 pinch-out 用于退出编辑）。
- 触发后调 `DeepProtectedAppsManager.getInstance(ctx).showHideApps(ctx, false)`。

**应用隐藏标题显示文件夹名** `hide_apps_title_folder_enabled`

- 标题由 `com.android.launcher.filter.DeepProtectedAppsManager#createVirtualFolder()` 硬编码为
  `R.string.app_hidden_title`。hook 它，after 里把返回 `folderInfo` 的 `title` 替换为
  `XposedInit#readAppHideFolderName(context)` 读到的自定义名（读不到保持原标题）。

### 隐藏指定应用（Launcher 侧）

**彻底隐藏电话本 / Gboard / GhostLock** `hide_contacts_enabled` / `hide_gboard_enabled` / `hide_ghostlock_enabled`

- 目标组件表 `HIDDEN_LAUNCHER_TARGETS`：`com.android.contacts/.PeopleActivityAlias`、
  `com.google.android.inputmethod.latin/.…launcher.LauncherActivity`、
  `com.ghostlock.app/.MainActivity`。
- hook `android.content.pm.LauncherApps#getActivityList(String, UserHandle)`，after 里剔除目标组件；
  互补 hook `android.content.pm.PackageManager#queryIntentActivities(Intent, int)`，after 里同样剔除，
  但只处理标准 `ACTION_MAIN + CATEGORY_LAUNCHER` 查询，不影响分享/解析等其它用途。
- 电话本特例：hook `com.android.launcher3.OplusAppFilter#shouldShowApp(ComponentName, UserHandle)`，
  对 `com.android.contacts/.DialtactsActivityAlias`（拨号）恒返回 true —— 系统原生是整包禁用会连拨号一起失效，
  这里只藏电话本图标、保留拨号（安全中心侧配套见第六节）。

## 五、framework / system_server（android 作用域）

改动都落在 `android`（system_server）作用域，必须重启 Zygote 才生效。

### 悬浮小窗贴边挂机 `float_window_edge_hang_enabled`

- 拦截点 `com.android.server.wm.TaskExtImpl#moveTaskToBackForPanorama(Task, boolean, int)`：
  before 里确认 `FloatHandleController.getInstance().isInFloatingList(taskId)` 为真（即"贴边成浮窗"这一路），
  则 `setResult(null)` 跳过 `Task.moveTaskToBack`，任务留在台前、应用继续运行。
- 只拦这一层：图标成形/缩小动画在 `exitFlexibleTaskWindowInnerLocked` 的 `handleEvent()` 内，截断会卡在松手位置。
- 挂机集合：`sHungTaskIds` / `sHungTasks`(弱引用) / `sHungUids`（`Task#effectiveUid`）。
- 表面不变式 `com.android.server.wm.Task#prepareSurfaces()`：锁屏再解锁时 `ActivityRecord#setVisibility` 会连带
  show 所有父容器 surface 并把 `mLastSurfaceShowing` 置 true，真实窗口会重现。故 in before 用
  additionalInstanceField 存下 `mLastSurfaceShowing`，after 里若任务仍在挂机列表则 `getSyncTransaction().hide(getSurfaceControl())`。
  - 隐藏必须在 after（放 before 会被方法内部逻辑覆盖）。
  - 任务已不在浮窗列表（点把手还原/被移除）时：移除记录并把 `mLastSurfaceShowing` 置 false 交还系统
    —— 不置 false，系统认为 surface 已是 shown，不再下发 show，窗口反倒不再显示。
- 焦点不变式 `com.android.server.wm.DisplayContent#setFocusedApp(ActivityRecord)`：目标属于挂机任务时改指
  `FlexibleTaskController#getTaskUnderFlexible(task)` 得到的下方任务（要求 `isTopActivityFocusable()` 且 `isVisible()`）。
  一次性纠正会被后续焦点计算覆盖，故做成不变式。
- 主动交焦点 `focusTaskBehind()`：`DisplayContent#setFocusedApp` + `WindowManagerService#updateFocusedWindowLocked(0, true)`。
  不走 `FlexibleTaskController#setFocusTask` —— 它最终是 `ATMS#setFocusedTask(taskId, null)`，只在
  `moveFocusableActivityToTop` 成功时才转移焦点（实测无效），且一旦移动成功后方任务被 resume、挂机失效。
- 解锁后补纠：hook `FlexibleTaskController#notifyKeyguardStateChanged(boolean, boolean, int)`，解锁时
  异步 500ms / 1500ms 两次 `refocusBehindHungTasks`（只处理主屏 displayId=0；该回调同时维护 `sKeyguardShowing`）。

### 小窗贴边挂机静音 `float_window_edge_hang_mute_enabled`

- 走系统多应用音量通道：`com.android.server.audio.PlaybackActivityMonitorExtImpl#setVolumeForUid(gain, uid, pkg, true)`
  （`isEternalSet=true` 写入 `mMusicVolumeMap`，当前及此后新建的播放器都生效）。实例从构造函数 after 抓取。
- 贴边挂机时记下原 gain 后置 0；解锁/回前台由 `ATMS#setFocusedTask(int, ActivityRecord)` 的 after 异步触发恢复
  （焦点回到该 taskId，或该 task 已不在浮窗列表）。
- 包名须与系统口径一致：`PackageManager#getNameForUid(uid)`。`ExtImpl.mContext` 可能为 null，依次尝试
  `mContext` → `mPam.getWrapper().getContext()` → `ActivityThread.currentApplication()` → `AppGlobals.getPackageManager()`。
- 同一 uid 重复贴边时保留最初记录的原始音量，不能把 0 记成原值。

### 锁屏后保持挂机 `float_window_edge_hang_keep_on_lock_enabled`

默认关闭（后台跑前台任务明显增加耗电发热）。开启时逐层拦住系统对挂机应用的"停掉"路径：

| 拦截点 | 处理 | 为什么是这一层 |
| --- | --- | --- |
| `com.android.server.wm.TaskFragment#sleepIfPossible(boolean)` | 挂机任务 `setResult(true)` | 返回值直接决定上层 `sleepInProgress` 计数：不执行 `startPausing`，activity 保持 resumed，且 `checkReadyForSleepLocked` 照常释放 `mGoingToSleepWakeLock` 不拖住休眠 |
| `com.android.server.hans.freeze.HansCGroup#hansFreezeLocked(OplusHansPackage, String)` | 挂机 uid `setResult(false)` | ColorOS 冻结主路径（直接写 `/dev/freezer/frozen/cgroup.procs`），与 AOSP `CachedAppOptimizer` 是两套独立机制；返回 false 表示冻结失败，状态机不进入 F |
| `com.android.server.am.CachedAppOptimizer#freezeAppAsyncInternalLSP(ProcessRecord, long, boolean)` | 挂机 uid `setResult(null)` | AOSP 冻结入口的统一实现，部分场景仍会走到 |
| `com.android.server.am.ActivityManagerService#doStopUidLocked(int, UidRecord)` | 挂机 uid `setResult(null)` | 锁屏时 `mTopProcessState=12`(TOP_SLEEPING) 而 `isProcStateBackground(n)` 判定 `n>=9`，OplusHansManager 判 forceIdle → `uidRec.setIdle(true)` → 停应用。不改全局 `mTopProcessState`（影响所有应用 oom adj，且其中触发 `updateOomAdj()` 与启动流程冲突） |

- 挂机且锁屏时发常驻通知提醒耗电发热：`updateHangNotification()`，主屏锁屏 && 有挂机任务 && 本开关开启才显示。
  **必须异步**（调用点多在 WM 全局锁内，同步发通知会跨 binder 有死锁风险）。
  Context 取 `currentApplication()`，为空时兜底 `ActivityThread.currentActivityThread().getSystemContext()`。

### 横屏应用小窗保持比例 `float_window_landscape_keep_ratio_enabled`

系统 `fillFlexibleTaskInfo` 对横屏应用硬编码 `ratio = 0.5625f`（9:16）。两处配合：

- `FlexibleTaskController#fillFlexibleTaskInfo(FlexibleTaskInfo$Builder, Rect, Intent, ActivityInfo, boolean)`：
  after 里按 `targetRatio = 1 / getFlexibleTaskFullScreenRatio(wH, wW)`（高/宽）重设 `setRatio` / `setScale`，
  并按系统同款公式重算 `launchBounds`（保持系统选定的高度、按目标比例求宽、在 windowBounds 内居中所夹）。
- `FlexibleTaskController#getFlexibleTaskAvailableRatioByActivity(ActivityRecord, String, boolean)`：
  after 里把 targetRatio 加入可选比例列表，使拖拽缩放可达。

### 小窗尺寸限制 `float_window_size_limits`

四处配合（`FLOAT_WINDOW_SIDE_MARGIN_DP` = 左右各留的边距）：

1. `com.android.server.wm.OplusZoomDisplay#getLeftRightLimitInMini()` → 返回边距 px（mini 窗口贴边间距与拖动边界）。
2. `OplusZoom{Small,Middle,Large}ScreenParameter#computeAllRatioData()` after → 改写 `mRatioDataList` 每项的
   `mMaxVisualWidth`（dp）= 当前屏幕宽度 − 左右边距（竖屏用短边、横屏用长边）。该字段是最大可视宽度的唯一来源，
   `getCurrRatioMaxFlexibleScale` 也读它，改字段即同步。
3. `OplusZoomDisplay#getFreeRatioSupportMaxWidthProp()` → 自由比例最大宽度从 `屏宽*0.96` 放开到 `屏宽 − 左右边距`
   （反解 prop）。
4. `OplusFlexibleTaskLayoutPolicy#regulateSizeIfOverScreen(...)` 与 `#getTaskRealSize(boolean, float)`：
   方法执行期间临时把 `mSecurityMarginRight` 改为边距 px，after 还原（ThreadLocal 存备份，防嵌套/并发覆盖）。

## 六、安全中心与设置

### 安全中心（com.oplus.safecenter）

**打开隐藏应用文件夹免验证** `hide_apps_noverify_enabled`

- `com.oplus.safecenter.privacy.view.space.AppHideNewCheckActivity#d0()`（checkPrivacyPwd）前，
  沿类链找字段 `I`（noNeedCheckPrivacyPwd）置 true → 走 `e0()` 的已验证分支。
  （`d0` / `I` 是该版本 SafeCenter.apk 内的真实混淆名，已 dexdump 核对。）

**应用隐藏标题显示文件夹名** `hide_apps_title_folder_enabled`

- hook `com.oplus.safecenter.privacy.view.AppProtectListActivity#setTitle(CharSequence)`
  （`setTitle` 是 Activity 继承方法，须 `getMethod` + `XposedBridge.hookMethod`；hook `setTitle` 而非 `l0(boolean)`
  才能覆盖 Activity 自身与内部 fragment 的任意调用）。
- 仅当字段 `P`（type）!= 1（即"应用隐藏"而非"应用锁"）时替换；名字来自
  `content://com.android.launcher.OplusFavoritesProvider/desktopappedit`，
  selection `componentName=?`、arg `com.oplus.safecenter_com.oplus.safecenter.privacy.view.space.AppHideLauncherActivity_<userId>`
  （`XposedInit#readAppHideFolderName`），取不到保持原标题。

**彻底隐藏电话本图标** `hide_contacts_enabled` 的 system 侧配套

- `com.oplus.safecenter.privacy.utils.PMSHideAppListUtil#t(Context, String)`：对 `com.android.contacts` 返回 true
  （只写隐藏列表并清除整包 PMS 禁用）。
- `com.oplus.safecenter.privacy.sdk.OplusPmsHiddeManager#isApplicationOplusHiddenAsUser(Context, String, int)`：
  对 `com.android.contacts` 返回 true（使安全中心 UI 回读为已隐藏）。

### 设置（com.android.settings）

**打开隐藏应用文件夹免验证** `hide_apps_noverify_enabled`（更直接的第二入口）

- 安全中心会让本进程启动 `com.oplus.settings.privacy.ConfirmNumberPrivacy` 或 `ConfirmBiometricInfo` 作为校验闸门，
  成功时本就 `setResult(-1)` 交回安全中心。hook 基类 `ConfirmAbstractPrivacy#onCreate(Bundle)`，
  after 里对这两个具体类直接 `setResult(-1) + finish()`。不依赖 `com.oplus.safecenter` 作用域。

**设置首页图标样式** `settings_home_icon_style`（0 / 1 不规则 / 2 圆形）

- Oplus 首页来自 `top_level_settings_oplus.xml`，不经过 `DashboardFeatureProviderImpl`；复用 COUI 自带绘制路径：
  `COUIPreference` 的 `couiIconStyle`（0=圆形，1=不规则圆角）。
- hook `com.android.settings.dashboard.DashboardFragment#displayResourceTilesToScreen(PreferenceScreen)`，
  after 里限定 `OplusTopLevelSettings` 实例，递归遍历 PreferenceGroup 对 `COUIPreference` 调 `setIconStyle`。
