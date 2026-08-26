> 本文件仅保存实现记录、排查过程和已验证结论，不是操作规则。若其中历史记录与 `.codebuddy/rules/coloros-mod.md` 冲突，以规则文件为准。
>
> 当前新增的手势条高度最终实现见文末第 15 节。

## 1. 模块已扩展：不止桌面图标间距

`XposedInit.java` 目前 hook 多个目标，作用域不止 `com.android.launcher`：

- **桌面图标间距**（原功能）：hook `IconParam#getIconDrawablePaddingPx` / `AllAppsParam#getAllAppsIconDrawablePaddingPx`。
- **SystemUI 运营商文本（qs_carrier）**：`com.oplus.systemui.qs.OplusQuickStatusBarHeader#onFinishInflate`。
- **SystemUI QS 顶栏间距（qs_topmargin）**：`OplusQuickStatusBarHeader#updateHeadersPadding`（afterHook）+
  `OplusQSFooterImpl#updateResources$15`（页脚日期/设置按钮）。
- **隐藏应用免验证打开**（安全中心 / `com.oplus.safecenter`）：见 §8。
- **Feature 10：控制中心背景压暗（qs_scrim_translucent_enabled）**：hook `OplusQSContainerImpl#onFinishInflate` + `#onVisibilityChanged`，把 `mBackground`（`quick_settings_background`）染成半透明黑（见 §12）。
- **Feature 18：悬浮小窗贴边挂机（float_window_edge_hang_enabled）**：作用域扩展到 **`android`（system_server）**。在系统原生 to-float（缩成竖条把手 + 切后台）完成后，于 `TaskExtImpl#moveTaskToBackForPanorama` 的 **afterHook** 主动 `moveToFront` 把任务拉回前台，使其持续前台运行（挂机），而不是最小化到后台（见 §14）。仅对"贴边成浮窗"一路（`isInFloatingList(taskId)` 为真）生效，普通最小化不介入。稳定无 ANR。

本笔记聚焦第 3 项（QS 状态栏图标「双重下沉 / 太偏下」）、Feature 10 及 Feature 18 的排查。

---

## 2. 现象与目标（qs_topmargin）

用户需求：控制中心（经典/合并模式）顶栏与屏幕顶部之间要有一点间距，让状态图标整行「下沉一点点」；
但电池百分比相对 wifi/信号**不能额外多沉一次**（即「双重下沉」）。

最终期望：状态图标整行（wifi/信号 + 电池图标 + 电量百分比）**同一高度**，整行位置合理（不过低）。

---

## 3. 根因（已用 uiautomator 布局树验证）

`uiautomator dump` 抓取控制中心展开后的真实坐标（本机，density≈2.96，即 `24dp ≈ 71px`）：

- `quick_qs_status_icons`（状态图标簇，ConstraintLayout）：`[844,110][1154,190]` → 高 80px，顶在 y=110
- `icons`（wifi/信号）：`[844,181][1054,190]` → **top 在簇内 71px 处，贴簇底**
- `battery_icon_view` / `battery_percentage_view`：`[1057,181]…` / `[1094,181]…` → 与 wifi **同高**（y=181），本不偏
- `qs_clock_container`（时钟/日期）：`[1,95][780,128]` → 顶在 y=95

**真相**：簇是 `wrap_content` + 子视图 `0dp`，配合 `status_bar_padding_top`（≈71px）顶部 padding，
使那约 9px 高的图标被**底对齐在簇底**（y≈181）。簇顶在 110、时钟在 95，于是图标相对时钟低了约 86px，
看起来像「电池多沉一次 / 整行太偏下」。

**关键推论**：直接改 `quick_qs_status_icons` 的 `paddingTop` 只会改变图标**上方空白**，图标本身（底对齐）纹丝不动 ——
所以「加 padding 让整行下沉」的方向是错的，只会越推越低。

---

## 4. 走过的弯路（已废弃，勿复用）

1. **`afterHook` 里给簇叠加 `paddingTop += extraPx`**
   → 实际把整行越推越低，正是「太偏下」的元凶（旧版 `24dp=71` 仍在设备上生效时用户看到的样子）。

2. **`alignBatteryToIcons()` 用 `getLocationInWindow()` 算位移对齐电量到图标簇中心**
   - `updateHeadersPadding` 在布局完成前就触发，`getLocationInWindow` 返回 0 → 直接 bail，对齐从未生效；
   - 偶尔生效时面板展开窗口坐标在变，会算出错误 `need` 把电量**再往下推** → 加重「双重下沉」。
   → 废弃。电量与 wifi 本就同高，根本不需要单独对齐电量。

3. **`raiseStatusRow()` 初版用 `v.setTranslationY(v.getTranslationY() + need)`（累加）**
   - 致命 bug：`getTop()` 不含 `translationY`，`curTop` 恒定，`need` 恒定，每帧 `+=` 会把图标一路往上推**飞出屏幕**。
   - 已改为**绝对赋值** `v.setTranslationY(need)`，幂等、每帧收敛。

---

## 5. 当前生效的修法（已验证）

`raiseStatusRow(cluster, res, desiredTopPx)`（`XposedInit.java`）：

- 在 `updateHeadersPadding` 的 `afterHook` 里调用，拿到 `quick_qs_status_icons` 簇；
- `post()` 到布局完成后执行（保证 `getTop()/getHeight()` 有效，且不受展开动画/窗口滚动影响）；
- 对 `icons` 与 `batteryRemainingIcon` 两个视图**等比**上移：
  `need = desiredTopPx - v.getTop()`（相对簇顶的恒定偏量），`v.setTranslationY(need)`（**绝对**，非累加）。
- 二者同 `curTop` → 等比上移 → **电池永远与 wifi 同高**，整行不再过低。

常量（在文件顶部）：
- `QS_TOP_MARGIN_DP = 12` → 状态图标行相对簇顶的目标偏移（本机 12dp ≈ 35px，把 `curTop` 从 71 提到 35，整行上移约 36px）。
- `QS_FOOTER_MARGIN_DP = 12` → 页脚日期/设置按钮小幅下沉。

诊断日志（开一次 QS 即写入）：
```
raiseStatusRow clusterH=.. iconsTop=.. iconsH=.. batteryTop=.. desiredTopPx=..
```
若想微调整行高度，改 `QS_TOP_MARGIN_DP` 即可；`desiredTopPx` 越小整行越高。

> 注：`qs_carrier`（运营商文本）是另一套逻辑，与本次「整行上移」无关，勿混淆。

---

## 6. 部署与验证（重要，避免重蹈覆辙）

### 6.1 【绝对红线】禁止私自重启设备
**用户处于「越狱模式」（jailbreak），重启设备会丢失越狱环境，必须重新越狱才能恢复。**
**任何情况下都严禁私自执行 `adb reboot` / `reboot` / 长按电源重启 / 任何会让设备重启的操作，即使为了让 LSPosed 重载模块也绝对不行。**
- 这是用户的明确禁令，**未经用户口头/书面同意，一次都不许重启**。
- 之前已有一次 Agent 擅自 `adb reboot` 的事故，造成用户必须重新越狱，**绝不再犯**。

**重载模块 / 让新 build 生效的正确做法（都不重启设备）：**
- 重启单个进程（不会掉越狱/root，安全）；**必须用 `pkill` / `killall`，严禁 `am force-stop`**（见硬性规则第 7 条，`force-stop` 对 systemui 等常不起效）：
  - SystemUI：`adb shell su -c 'pkill -f com.android.systemui'`
  - Launcher：`adb shell su -c 'pkill -f com.android.launcher'`
  - **system_server（框架重载）**：禁止直接执行 `pkill -f system_server` / `killall system_server`。如确需让 `android` 作用域的新 hook 生效，必须先询问用户并获得明确同意；同意后只能使用项目既有的 `setprop ctl.restart zygote` 方式。
  - **模块设置 App 右上角重启按钮**：现已改为下拉菜单，含「重启作用域」（等价上面的 `kill` 方案，仅杀 `com.android.systemui`/`com.android.launcher`）与「软重启系统」（对齐 KernelSU：`setprop ctl.restart zygote`，重启 zygote 用户空间、内核与 KSU 模块保持生效；弹确认框）。软重启前仍必须先获得用户明确同意。
- 或在 LSPosed 里把本模块**关掉再打开**（让目标进程下次启动时重注）。
- 这些操作即可让新代码进内存，完全不需要重启整机。

### 6.2 `adb install -r` 不会立刻生效
LSPosed 在目标进程**启动时**注入模块。`install -r` 只替换磁盘上的 APK，
**正在运行的 `systemui` 仍是旧模块**。必须随后重启 `systemui` 进程（见 6.1）新代码才进内存。

> 实测：上一轮 `install -r` 后即便 `force-stop`+重启设备，日志仍显示旧 `24dp=71`，
> 最终由用户在 LSPosed 里手动重载才生效。教训：**装完务必确认新 build 真的被加载**。

### 6.3 如何确认当前跑的是哪个 build
**模块 `log()` 只写文件，绝不走 `logcat`（见 § 硬性规则第 6 条）。验证只看文件，不要去 logcat 找：**
```
/sdcard/colorosmod.log         # 主落盘（需 su 读取）
/data/local/tmp/colorosmod.log # 备（通常无写权限，可忽略）
```
读取方式（只读）：
```
adb pull /sdcard/colorosmod.log /tmp/cm.log && cat /tmp/cm.log
# 或
adb shell su -c 'cat /sdcard/colorosmod.log'
```
加载时打印：
```
>>> matched systemui ...
qs_topmargin extraPx(fixed 24dp)=71, footerPx(fixed 12dp)=36   # 旧版
# 或（新版应为）
qs_topmargin extraPx(fixed 12dp)=.., footerPx(fixed 12dp)=36
```
**核对这一行的 dp 值**即可确认部署是否成功，不必猜；logcat 里看不到这些**完全正常**，别误判为未注入。
注意：**日志本身经常为空或不刷新**（见硬性规则第 9 条）。如果日志读出来是空的、或没有某条 `HOOK OK`，**不要据此断定注入失败/没装新版本**——以设备上的真实行为为准。

### 6.4 验证整行位置
```
adb shell cmd statusbar expand-settings
sleep 1
adb shell uiautomator dump /sdcard/ui_qs.xml
adb pull /sdcard/ui_qs.xml /tmp/ui_qs.xml
# 用 python 解析 bounds：quick_qs_status_icons / icons / batteryRemainingIcon / battery_percentage_view / qs_clock_container
```
对比 `icons` 与 `qs_clock_container` 的 `bounds` 顶部，确认整行是否已上移到合理高度、且电池与 wifi 同高。

---

## 7. 速查

| 项 | 值/位置 |
|----|---------|
| 状态栏整行上移逻辑 | `XposedInit.java` → `raiseStatusRow()` |
| 触发点 | `hookQsTopMargin()` hook `OplusQuickStatusBarHeader#updateHeadersPadding`（afterHook）|
| 整行偏移常量 | `QS_TOP_MARGIN_DP = 12`（文件顶部）|
| 页脚偏移常量 | `QS_FOOTER_MARGIN_DP = 12` |
| 日志落盘 | `/sdcard/colorosmod.log` |
| 构建 | `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |
| 部署后重载 | `am force-stop com.android.systemui` / `com.android.launcher`（**严禁 `adb reboot`，用户越狱模式重启需重新越狱**）|
| 隐藏应用免验证 hook | `hookSafecenter()` → hook `com.oplus.safecenter.privacy.view.space.AppHideNewCheckActivity#d0()`，`beforeHookedMethod` 中把实例字段 `I`(noNeedCheckPrivacyPwd) 置 true |
| 反混淆验证法 | JADX 合成的 `d0`/`I` 等短名**可能就是真实混淆名**；用 `$SDK/build-tools/30.0.3/dexdump` 对提取的 `SafeCenter.apk` 核对类名/方法名/字段名 |
| 提取并反编译系统 app | `adb pull /system_ext/app/SafeCenter/SafeCenter.apk` + `jadx -d <out> <apk>`（已存于 `android-app-mods/com.oplus.safecenter`）|
| 应用隐藏标题改文件夹名 hook | **真正生效：launcher `hookLauncher` 内 hook `DeepProtectedAppsManager#createVirtualFolder`（afterHook 改 `folderInfo.title`）**；安全中心 `hookSafecenterTitleFolder()`（setTitle）仅覆盖列表界面，用户通常看不到（见 §9/§10）|
| 桌面双指张开打开隐藏应用 hook | `hookLauncher` 内 hook `com.android.launcher3.dragndrop.DragLayer#dispatchTouchEvent`，被动 `ScaleGestureDetector` 检测 `accum>1.5` → `openHideAppsFolder()` → `DeepProtectedAppsManager.getInstance(ctx).showHideApps(ctx,false)`（见 §10）|
| Feature 10 背景变暗 | `hookQsScrimTranslucent()` hook `ScrimView#setDrawable`，把 `WallpaperBlurDrawable` 替换为 `TranslucentBlackDrawable`（见 §12）|
| 贴边挂机 hook | `hookFloatWindowEdgeHangSystemServer()`（**android/system_server 作用域**）hook `com.android.server.wm.TaskExtImpl#moveTaskToBackForPanorama`（`(Lcom/android/server/wm/Task;ZI)V`, after）；对"贴边成浮窗"一路（`FloatHandleController.isInFloatingList(taskId)` 为真）在系统原生 to-float 完成后 `moveToFront` 把任务拉回前台（挂机=前台运行）。不拦截 to-float 本身，故无 focused+hidden ANR（见 §14）。|
| 框架 jar 反编译/签名核对 | `adb pull /system/framework/oplus-services.jar` + `$SDK/build-tools/30.0.3/dexdump`（`FlexibleTaskController` 在 `classes3.dex`，签名 `(Lcom/android/server/wm/AbsFlexibleTaskExitStrategy;)V`）|
| 重载 system_server（仅用户同意后） | 只能使用项目既有的 `setprop ctl.restart zygote` 方式；禁止 `pkill -f system_server` / `killall system_server`，执行前必须先询问用户。|

---

## 8. 隐藏应用免验证打开（安全中心 / 设置）

需求：点击桌面隐藏应用图标 / 拨号盘输入隐藏号码后，打开隐藏应用界面时**无需密码或指纹验证**。

### 入口与闸门（已用 jadx + dexdump 核对 SafeCenter.apk / Settings.apk）
- 桌面入口 `com.oplus.safecenter.privacy.view.space.AppHideLauncherActivity`（extends `AppHideNewCheckActivity`）。
- `AppHideNewCheckActivity#d0()`(checkPrivacyPwd) 顶部 `if (this.I) { e0(); return; }`（`I`=noNeedCheckPrivacyPwd）；
  否则 `v.h(this,5,1)` 拉起设置里的校验界面 `com.oplus.settings.privacy.ConfirmNumberPrivacy`（或其指纹变体 `ConfirmBiometricInfo`，均 extends `ConfirmAbstractPrivacy`）。
- 校验成功时 `ConfirmAbstractPrivacy#confirmComplete` 做 `setResult(-1, intent{CHALLENGE})` 并 finish；
  结果回到 `AppHideNewCheckActivity#onActivityResult`（requestCode 1/2, resultCode -1）→ 调 `e0()` → `i0()` 打开隐藏应用。
  **`e0()/i0()` 只用启动 Intent 里的 `this.H`，并不读取校验 challenge**，因此只要设置侧返回 -1 即可打开。

### 两个 hook（同一开关 `KEY_HIDE_APPS_NOVERIFY_ENABLED`）
1. **`hookSafecenter`**：hook `AppHideNewCheckActivity#d0()`，`beforeHookedMethod` 把实例字段 `I`(boolean) 置 true → 直接走 `e0()` 打开，**校验界面根本不出现**（最干净，无闪屏）。
2. **`hookSettings`（兜底/更直接）**：hook `com.oplus.settings.privacy.ConfirmAbstractPrivacy#onCreate`，对 `ConfirmNumberPrivacy`/`ConfirmBiometricInfo` 实例在 afterHook 直接 `setResult(-1); finish()`。
   - **只需把模块作用域加入 `com.android.settings` 即可生效**，不依赖 `com.oplus.safecenter` 作用域。
   - 校验界面本身就在 `com.android.settings` 进程，所以这个 hook 直接命中当前屏幕上的验证界面。

### 注意（关键！之前"没生效"的根因）
- **必须在本机 LSPosed 把模块作用域加入 `com.android.settings`（设置）和 `com.oplus.safecenter`（安全中心）**。
  本模块新增了这两个目标包，旧作用域（只有 launcher/systemui）不会自动覆盖，导致 hook 从未注入（日志 `com.oplus.safecenter`/`com.android.settings` matched 数为 0）。
- `com.android.settings`/`com.oplus.safecenter` 均非常驻进程，hook 在真正打开隐藏应用时才注入；日志 `>>> matched com.android.settings` 仅在该进程启动时出现。
- 若某机型类名/字段名不同，用 `$SDK/build-tools/30.0.3/dexdump` 对提取的 apk 重新核对（`d0`/`I`/`e0`/`ConfirmNumberPrivacy` 均为真实混淆名）。
- 反编译产物已存于 `android-app-mods/com.oplus.safecenter` 与 `android-app-mods/com.android.settings`。

---

## 9. 应用隐藏界面标题改为隐藏文件夹自定义名（安全中心）

需求：应用隐藏列表界面（`com.oplus.safecenter.privacy.view.AppProtectListActivity`，标题"应用隐藏"）把标题换成**用户在桌面给隐藏应用入口命名的文件夹名**。

### 名称存储（已用 jadx + 设备上 provider query 核对 SafeCenter.apk）
- 桌面隐藏应用入口 `AppHideLauncherActivity` 的显示名由用户自定义，存于 launcher：
  `content://com.android.launcher.OplusFavoritesProvider/desktopappedit`，列 `title`，
  行键 `componentName = "com.oplus.safecenter_<AppHideLauncherActivity 全类名>_<userId>"`。
- 安全中心内部 `com.oplus.safecenter.privacy.utils.k#b(Context, String)` 读这个（默认回退 `R.string.privacy_app_hide_name`="应用隐藏"）。本模块 `readAppHideFolderName()` 复刻该查询。
- 设备实测：`componentName=com.oplus.safecenter_com.oplus.safecenter.privacy.view.space.AppHideLauncherActivity_0` → `title=etc`（用户已改名）。

### hook（开关 `KEY_HIDE_APPS_TITLE_FOLDER_ENABLED`）
**真正生效的修复在 `com.android.launcher`（不是安全中心）**：
- 桌面隐藏应用入口打开的是 launcher 渲染的"虚拟文件夹"，标题由
  `com.android.launcher.filter.DeepProtectedAppsManager#createVirtualFolder()` 硬编码
  `folderInfo.title = getString(R.string.app_hidden_title)`（"应用隐藏"），再 `bindVirtualFolder` 渲染。
  → **hook `createVirtualFolder`（afterHook）把 `folderInfo.title` 替换为 `readAppHideFolderName(ctx)` 的自定义名**（实测 → `etc`）。
- 该文件夹由广播 `oplus.intent.action.SHOW_DEEP_PROTECT_APPS`（permission `oplus.permission.OPLUS_COMPONENT_SAFE`）
  触发：`AppProtectManager.j0()` 发送 → launcher `DeepProtectedAppsManager` 接收 → `showHideApps(ctx,false)` → `createVirtualFolder`。

**安全中心侧的 `AppProtectListActivity#setTitle` hook 是冗余的**：
- 之前误以为"应用隐藏"是 Activity 标题栏文字，去 hook `AppProtectListActivity#setTitle(CharSequence)`
  （用 `findClass().getMethod(...)` + `XposedBridge.hookMethod`，因是继承方法 `exact` 找不到）。
  但该 Activity 在此流程里根本不显示（203 路径的列表界面用户很少抵达），文件夹视图的标题由 launcher 决定，
  所以那个 hook 对"用户看到的标题"毫无影响。**保留它仅覆盖"万一抵达列表界面"的情况，真正起作用的是上面 launcher 的 hook。**

### 注意
- `AppHideNewCheckActivity#i0()` 按 `this.H` 分派：202 直接开文件夹、201→`AppHideNewMainSettingsActivity`、203→`AppProtectListActivity(app_protect_type=2)`。
  桌面图标/拨号盘入口最终多为 203 到 `AppProtectListActivity`（设"应用隐藏"标题处，即本 hook 目标）。
- 单独 `am start ...AppProtectListActivity --ei app_protect_type 2` 可触发 hook 验证（日志 `safecenter title -> folder name: etc`），
  但该 Activity 缺前置会崩溃；**真实入口（桌面图标/拨号盘）才正常显示**。
- 反编译产物：`android-app-mods/com.oplus.safecenter`。

---

## 10. 桌面双指张开（pinch-out）手势打开隐藏应用文件夹

需求：在桌面用双指向外张开（pinch-out）直接打开隐藏应用文件夹（与系统自带"双指捏合 pinch-in"相反，互不冲突）。

### 实现（开关 `KEY_PINCH_OUT_OPEN_HIDE_APPS_ENABLED`，默认 true）
- 在 `com.android.launcher` 作用域里 hook `com.android.launcher3.dragndrop.DragLayer#dispatchTouchEvent(MotionEvent)`，
  `beforeHook` 把每个事件喂给一个**被动** `ScaleGestureDetector`（**不消费事件**，仅观测，存在 DragLayer 实例的 additional field `colorosmod_pinch`）。
- `OnScaleGestureListener`：`onScaleBegin` 重置累计缩放 `accum=1`、触发标志 `fired=false`；
  `onScale` 中 `accum *= getScaleFactor()`，当 `accum > 1.5` 且未触发过 → `openHideAppsFolder(ctx)`（置 `fired=true` 防抖动重复）。
- `openHideAppsFolder(ctx)`：`DeepProtectedAppsManager.getInstance(ctx).showHideApps(ctx, false)`（静态 `getInstance(Context)` + 实例 `showHideApps(Context, boolean openByDesktop)`）。ctx 取 `DragLayer.getContext()`（即 Launcher）。

### 为什么这样
- 文件夹由 launcher 渲染（见 §9），所以触发入口必须在 `com.android.launcher` 进程内。
- 直接调 `showHideApps` 比重发广播更稳：广播 `oplus.intent.action.SHOW_DEEP_PROTECT_APPS` 要求发送方持有
  `oplus.permission.OPLUS_COMPONENT_SAFE`，launcher 不一定有；而 `showHideApps` 是 launcher 内部 API，进程内可直调。
- `dispatchTouchEvent` 用 beforeHook 观测，原始分发不受影响，不会破坏桌面其它手势（滚动/捏合等）。
- 阈值 1.5 需"刻意张开"，避免误触；若想更灵敏可调小。

### 验证
- 装完 `adb install -r` 后必须重启 `com.android.launcher` 进程（`am force-stop com.android.launcher` 即可，launcher 会被系统自动拉起）新代码才生效。
- 日志：`HOOK OK launcher DragLayer#dispatchTouchEvent (pinch-out)` / `pinch-out -> open hide apps folder`。
- 真机在桌面双指张开应直接打开隐藏应用文件夹。

---

## 11. 开关在 App 内修改后「不生效」的根因与修法（关键！曾两次踩坑）

### 现象
App 里改任意开关（如最后一个 pinch-out），即使重启所有作用域（force-stop launcher/systemui）仍不生效。

### 真正的根因（两层，都已修）
1. **加载时门控**（旧写法）：每个 feature 在模块注入进程时 `readBool(...)` 读一次开关，再决定是否 `findAndHookMethod`。
   → 改设置必须重启进程才生效。已改为**始终注入 hook + 在 hook 内部运行时 `readBool` 门控**（见各 `hookXxx`）。
2. **prefs 文件不可读（致命）**：`MainActivity` 用 `MODE_PRIVATE` 写 prefs → 文件权限 `-rw-------`(600)，属主是 `com.rikumi.colorosmod`。
   被 hook 的进程（launcher/systemui 等）以**不同 UID** 运行，无法读取该文件 → `XSharedPreferences` 读不到 → `readBool` 永远返回**默认 true**。
   （旧代码在 hook 进程里调 `XSharedPreferences.makeWorldReadable()` 想 chmod，但 hook 进程既非属主也非 root，`chmod` 会 EPERM 失败，毫无作用。）

### 修法（已在 MainActivity 落地）
- 写入改为 **同步 `commit()`**（不能 `apply()`，否则写盘晚于 chmod，又把权限刷回 600）。
- 每次写入后调 `makeWorldReadable()`：**属主 App 对自己的文件有 chmod 权限，必成功**——
  把 `settings.xml` 设为世界可读（`setReadable(true,false)` → 644），并把父目录 `shared_prefs`、`/data/data/<pkg>` 设为世界可执行（`setExecutable(true,false)`，保证其它进程可遍历进目录）。
- 同时在 `onCreate` 末尾调一次 `makeWorldReadable()`，确保已存在的文件也被修正。

### 验证（实测）
- `ls -l /data/data/com.rikumi.colorosmod/shared_prefs/settings.xml` → `-rw-r--r--`（644），目录 `drwxrwx--x` / `drwx--x--x`。
- 临时在 `readBool` 加 `log("[DBG] readBool "+key+" = "+v)`，手动写 `pinch_out_open_hide_apps_enabled=false` 进文件并 `chmod 644`，
  重启 launcher 后日志确认 `readBool ... = false`（证明文件真被读取，而非返回默认）。验证完已移除该调试日志。
- 反例：修改前文件是 `-rw-------`，且 `cat` 只见 `notification_padding_enabled=true` 一条 → 其它开关从未落盘且外进程读不到。

### 教训
- **任何「开关不生效」先查 prefs 文件权限**，而不是怀疑 hook 逻辑。
- 跨进程读 `SharedPreferences` 的标准做法就是「属主 App 写入后把文件/目录改世界可读」；在 hook 进程里调 `makeWorldReadable()` 是无效的（无权限）。
- 本模块所有功能开关现均为**运行时门控**：App 内改设置即时生效，**无需再 force-stop 进程**（视图修改类关闭后于视图重建时还原）。

---

## 12. Feature 10：控制中心背景压暗（qs_scrim_translucent_enabled，"控制中心背景变暗"）

需求：合并/经典控制中心背景压暗（叠加一层半透明黑），而不是模糊壁纸。开关 `qs_scrim_translucent_enabled`（label "控制中心背景变暗"）。

### 关键逆向结论（已用 jadx 核对 `android-app-mods/com.android.systemui/src-java`）
- **真正承载背景的是"背后 scrim"**（`ScrimController.mScrimBehind`，一个 `com.android.systemui.scrim.ScrimView`）。
  `CentralSurfacesImpl` 初始化时：
  `scrimController.mScrimBehind = scrimView; ... scrimView.setScrimName(scrimController.getScrimName(scrimView));`
  其中 `ScrimController#getScrimName(ScrimView)` 是引用相等判断：`scrimView == mScrimBehind ? "behind_scrim" : (==mScrimInFront?"front_scrim":(==mNotificationsScrim?"notifications_scrim":"unknown_scrim"))`。
  → **凡被 `setScrimName("behind_scrim")` 的 ScrimView 就是我们要压暗的"背后背景"，识别稳定、与混淆/类型无关。**
- ColorOS 在 `ScrimControllerExImp#refreshBehindDrawable()` 里给该 ScrimView 调 `setDrawable(WallpaperBlurDrawable)`（模糊壁纸那一层）。
- `ScrimView#setDrawable(Drawable)` 是 `public` 且**未被子类覆写**（之前的"ScrimViewExImp 覆写"是误判，真实 ext 是组合使用的 `ScrimViewEx`），hook 它即可生效。
- 锁屏安全：`setDrawable` 会把 drawable alpha 设为 `mViewAlpha * 255`；锁屏时 `mBehindAlpha=0` → alpha=0 → 我们的 drawable 不绘制，不污染锁屏。

### 当前生效实现（`hookQsBackgroundDim`，在 `hookSystemUi` 内，加载后打印 `>>> matched systemui`）
1. hook `ScrimView#setScrimName(String)`（afterHook）：`args[0]=="behind_scrim"` 时用附加字段 `colorosmod_behind=Boolean.TRUE` 标记该实例。
2. hook `ScrimView#setDrawable(Drawable)`（beforeHook）：
   - 若本实例已标记 behind 且 `readBool(KEY_QS_SCRIM_TRANSLUCENT_ENABLED, true)` 为真；
   - **按类名字符串** `d.getClass().getName().contains("WallpaperBlur")` 判断入参（**不能**用 `isInstance`，因为 `WallpaperBlurDrawable` 由独立 classLoader 加载，运行时实例的 Class 与 `findClass` 拿到的是不同对象，isInstance 必返 false —— 这是早期 scrim 方案失败的根因）；
   - 命中则把 `param.args[0]` 替换为单例 `TranslucentBlackDrawable`（半透明黑，FACTOR=0.6，跟随 scrim alpha）。
- `TranslucentBlackDrawable`（内部类 `Drawable`）：`draw()` 在 `mAlpha>0` 时画 `Color.argb(round(mAlpha*0.6),0,0,0)`；`getOpacity=TRANSLUCENT`。

### 历史弯路（废弃，勿复用）
1. **hook `refreshBehindDrawable` + `applyAndDispatchState`**：`applyAndDispatchState` 不存在 → `NoSuchMethodError`；`getBehindScrim` 字段混淆返回 null。
2. **`setDrawable` 里用 `WallpaperBlurDrawable.isInstance(d)` 判断**：因 classLoader 不同，isInstance 始终 false，替换从未发生（致命）。
3. **hook `OplusQSContainerImpl#mBackground`（`quick_settings_background`）染半透明黑**：方向看似合理，但实际展开背景的层级/时机没命中（该视图并非用户看到的整片背景），未生效。
4. **`setDrawable` 替换为纯黑 `TranslucentBlackDrawable`**：直接把模糊壁纸整层盖死 → 看起来"没有背景"（丢失了壁纸）。正确语义是"在背景上叠加一层压暗", 故改为 `onDraw` 叠加。
5. **`onDraw` 叠加浓度 = `viewAlpha × QS_BG_DARK × 255`**：ColorOS 展开控制中心时 behind scrim 的 `mViewAlpha` 本身只有约 **0.5**(系统半透明模糊壁纸的设计基础值)，再乘一次 → 叠加层只有约 128 浓度，半透明黑盖在半透明壁纸上混合成**灰色**、壁纸未盖死（用户反馈"纯黑反而变灰、只压暗一半"）。**教训：叠加浓度绝不能乘 viewAlpha，只能把 viewAlpha 当作"是否展开"的开关；固定浓度取 `QS_BG_DARK`。**

### 诊断原则（重要）
- **日志不可靠**（见硬性规则第 6/8 条）：不要仅凭"日志里没看到某条"就断定模块没注入或代码没跑到。
- **若其它 SystemUI 相关功能都正常，则模块已注入 systemui**，问题一定在**该功能自身代码逻辑**，而非部署/注入。本功能此前多次"没生效"都是代码层面的识别问题（scrim 类型判断/classLoader），不是没注入。

### 验证
- `adb install -r` 后 `adb shell su -c 'pkill -f com.android.systemui'`（**勿 `adb reboot`，勿 `force-stop`**）。
- 直接到设备上手动下拉控制中心肉眼确认：背景应是半透明黑（非模糊壁纸）；关闭开关则恢复壁纸模糊。不依赖日志判断。

---

## 13. 反编译分析工具链：`../android-app-mods`

需求：在给某 app 写 hook 前，必须先看它的真实反编译源码，确认类名/方法名/字段名，再写 `findAndHookMethod`，**禁止凭记忆瞎猜**（ColorOS 大量混淆短名 `d0`/`I`/`e0` 等，JADX 合成名可能就是真名）。

### 仓库结构（`/Users/rikumi/Documents/Code/android-app-mods/`）
```
android-app-mods/
  backward.sh   # 反编译 base.apk -> src(解包) + src-java(jadx 只读 Java 参考)
  forward.sh    # src + patches -> src-patched -> _dist/<pkg>.apk (本模块一般不用，仅改系统 app 时用)
  _tools/       # apktool.jar / apksigner.jar / zipalign
  signkey.keystore
  <package>/    # 每个被分析/修改的 app 一个目录，如：
    com.android.launcher/       # 桌面（已反编译 ✅）
    com.android.systemui/       # 系统界面（已反编译 ✅，src-java 即 29848 个 .java）
    com.oplus.safecenter/       # 安全中心（已反编译 ✅）
    com.android.settings/       # 设置（已反编译 ✅）
    net.oneplus.weather/        # 其它
    com.luckyzyx.luckytool/
    com.micropay.pay/           # 只有 base.apk，未反编译
  <package>/src-java/   # jadx 出的只读 Java 参考（人读、理解逻辑用）
  <package>/src/        # apktool 解包（smali + res），改系统 app 时作为 patch 源头
```

### 本模块已覆盖的 app（直接看，勿重新反编译）
- **桌面** `com.android.launcher` → `android-app-mods/com.android.launcher/src-java/`
- **SystemUI** `com.android.systemui` → `android-app-mods/com.android.systemui/src-java/`
  （本仓库旧的 `sysui_src/` 只是它的 Java 参考快照，最新以 `android-app-mods` 为准）
- **安全中心** `com.oplus.safecenter`、`com.android.settings` 同上均已有 `src-java/`。
- 读代码：`search_content`/`search_file` 直接在这些 `src-java/` 里检索类名、方法、字符串。

### 反编译一个「新」app 的标准流程
仅在目标 app 尚未出现在 `android-app-mods/`、或 `base.apk` 已过时（app 更新）时才需重做：
1. **从设备取出 apk**（不要在电脑上乱找）：
   ```
   pkg=<待分析包名>
   path=$(adb shell pm path "$pkg" | head -1 | cut -d: -f2 | tr -d '\r')
   mkdir -p ../android-app-mods/"$pkg"
   adb pull "$path" ../android-app-mods/"$pkg"/base.apk
   ```
2. **确保 jadx 可用**（出 Java 参考的关键，`backward.sh` 仅在 jadx 在 PATH 且 `ANDROID_MODS_NO_JADX` 未设时才生成 `src-java`）：
   ```
   command -v jadx || brew install jadx
   ```
3. **（可选）同时出 smali**：在 `android-app-mods/<pkg>/` 放一个空文件 `.decompile`，
   `backward.sh` 会去掉 apktool 的 `-s`（skip sources）标志，把 dex 也解码进 `src/`（默认只留 .dex）。
4. **运行反编译**：
   ```
   cd ../android-app-mods && ./backward.sh <pkg>
   ```
   产物：`src/`（apktool 解包）、`src-java/`（jadx Java 参考，可读逻辑）。
5. 之后在 `src-java/` 里检索确认要 hook 的类/方法/字段，再回到本模块写 hook。

### 注意
- `src-java/` 是**只读参考**，不要在这里直接改；若真要改系统 app 走 `patches/` + `forward.sh`（本模块绝大多数场景只写 Xposed hook，不碰系统 app 本体）。
- jadx 合成的短名（`d0`/`I`/`e0`/`a`/`b`）**很可能就是真实混淆名**，写 hook 时直接用它，并用 `$SDK/build-tools/30.0.3/dexdump` 对 `base.apk` 二次核对类名/方法名/字段签名，避免 JADX 误命名。
- 反编译不会重启设备，与 §6.1 红线不冲突。

---

## 14. 用 simpleperf 火焰图定位「真实提交点」的方法论（范例：悬浮小窗贴边挂机 Feature 18）

### 痛点：猜了 3 个 hook 点都"完全没效果"
Feature 18 需求：悬浮小窗拖到屏幕边缘松手时保持前台浮窗（贴边挂机），不要最小化到边缘迷你条。
最初按"SystemUI 里拖拽最小化"的直觉，先后 hook 了 SystemUI(`com.android.wm.shell`) 内 3 个点，全部无效：
1. `OplusPanoramaWorkBranchAnimController#showMinimizedWindow` —— 平板专用（`isTabletPanoramaWorkEnable()` 门控），手机不跑。
2. `ShellTaskOrganizerExt#adjustChangeForFlexibleMinimizeIfNeed` —— 分屏路径，自由窗拖拽不触及。
3. `OplusDragTaskFullAnimation#getChangeStateByPoint` —— 仍不命中。
**猜 3 次都错 → 停止猜测，改用 profiler 抓真实调用链。**

### simpleperf 是安全的（只读、不重启、不碰文件系统）
- `simpleperf` 是 CPU 采样 profiler，只读取进程调用栈，**不修改设备、不重启、不影响越狱**，符合红线。
- 本机路径 `/system/bin/simpleperf`；`record` 在目标进程采样，`report` 离线分析。

### 关键命令（本设备实测）
```sh
# 录目标进程，手势/释放发生在 --duration 窗口内
PID=$(adb shell pidof com.android.systemui | tr -d '\r')
adb shell su -c "simpleperf record -g -f 999 -p $PID -o /data/local/tmp/perf.data --duration 4"
adb pull /data/local/tmp/perf.data
adb shell su -c 'simpleperf report -i /data/local/tmp/perf.data -g' > report.txt

# 双进程同时录（定位"提交到底在哪一侧"）：-p 接受逗号分隔的多个 pid
SS=$(adb shell pidof system_server | tr -d '\r')
adb shell su -c "simpleperf record -g -f 999 -p $PID,$SS -o /data/local/tmp/perf2.data --duration 8"
adb pull /data/local/tmp/perf2.data
adb shell su -c 'simpleperf report -i /data/local/tmp/perf2.data -g' > report2.txt
```

### 本设备的两个命令参数坑（必看）
- **不支持 `--java`**：`simpleperf record` 报 `Unknown option --java`。系统 app（systemui/system_server）多为 AOT 编译，Java 方法在火焰图里以 native/mangled 符号呈现，`-g`（dwarf 调用图）即可；**不要加 `--java`**。
- **不支持 `--stdio`**（那是 Linux perf 的参数；Android 版 simpleperf 默认输出 stdout），直接 `> report.txt` 收尾即可。

### 采样技巧（决定能否抓到提交点）
- **必须录到"松手那一帧"**：前两次 systemui 采样只抓到拖拽中的 `onTaskInfoChanged`（位置更新）和 `FlexibleController.notifySystemEvent`（收尾通知），没见到提交 —— 因为录制窗口没覆盖松手最小化那一刻。
- 正确做法：开始录 → 立刻拖到边缘 → **松手**让它最小化 → 等采样结束。让"提交动作"落在 `--duration` 内。
- 录 systemui 时，整条 `com.oplus.flexibletask` 链路只有 `notifySystemEvent → hideAllTipsView`（事后通知），**没有任何 `applyTransaction`/`startTransition`/`moveTaskToBack`** → 证明提交不在 systemui。
- 于是转录 `system_server`，火焰图立刻给出完整链。

### 抓到的真实调用链（system_server 内，即 package `android`）
```
FlexibleFloatHandleAnimationSpec.defaultCallAnimationEnd
  → FlexiblePointerHandler$2.onAnimationEnd
    → FlexibleTaskController.exitFlexibleTaskWindowInnerLocked(AbsFlexibleTaskExitStrategy)
      → exitFlexibleTaskWindowInner → ExitFlexibleTaskToFloatStrategy.handleEvent()
          ① floatHandleController.addFloatHandle(...) 把任务加入浮窗列表
          ② 跑最小化动画把窗口缩成边缘竖条/图标形态
          ③ 动画结束 -> exitFlexibleTask(task, needExitTask, 16, ...)
               -> TaskExtImpl.moveTaskToBackForPanorama
                 -> Task.moveTaskToBack -> moveTaskToBackInner -> nativeApplyTransaction
```
- **贴边最小化（变边缘迷你条）**走这条 → `moveTaskToBack` 把任务切后台。其竖条/图标形态由 `handleEvent` 内部的 ②③ **独立建立**，与"松手前是否按住等成形"无关。
- **拖到非边缘松手保持浮窗**走另一条 `finishMovingTask → Task.resize`，**不经过 `exitFlexibleTask*`**，本 hook 不影响它。

### ⛔ ANR / crash 教训（两个早期版本都踩过，务必牢记）
- **现象**：贴边后音量条不出、过一会 `ActivityManager: ANR in <app>` + `Reason: Input dispatching timed out (Application does not have a focused window)`。
- **根因**：贴边后系统会**把真实窗口 `hide()` 隐藏**（只留竖条把手），这一步发生在 ② 的最小化动画里；而任务仍停留在前台。一旦我们**阻止任务切后台**（无论是 ① `exitFlexibleTaskWindowInnerLocked` 里 `setResult(null)` 取消整个提交，还是 ② `moveTaskToBackForPanorama` 里 `setResult(null)` 跳过后台化），结果都是 **"真实窗口已隐藏 + 任务仍 focused"** → 系统找不到有焦点的窗口 → 输入派发超时 → ANR。
- **结论**：**"保持前台" 与 "只显示竖条（隐藏真实窗口）" 在输入焦点上互斥**。竖条形态必然隐藏真实窗口，所以必须让任务随之切后台（失焦）才稳定。系统原生 to-float 退出正是"形成竖条 + 切后台"，最稳定。
- **最终做法**：`hookFloatWindowEdgeHangSystemServer` 不再"取消提交/跳过后台化"（那会 focused+hidden ANR），而是 **hook `TaskExtImpl#moveTaskToBackForPanorama` 的 afterHook**：系统原生 to-float 先把窗口缩成竖条、任务切后台（此时任务已后台、窗口 hidden，无焦点矛盾），**之后**才 `moveToFront` 把任务拉回前台——系统 bringToFront 会重新显示窗口（不再 hidden），任务前台且可见，无 ANR。仅对"贴边成浮窗"一路（`isInFloatingList` 为真）生效，普通最小化不介入。`moveToFront` 用多候选签名兜底（`moveToFront(String)` / `moveToFront(int,boolean,String)` / 经 ATMS `moveTaskToFront`）。

### 用 dexdump 核对签名（不凭空猜）
```sh
# 拉框架 jar（只读提取，不碰设备运行态）
mkdir -p /tmp/ss && adb pull /system/framework/oplus-services.jar /tmp/ss/
cd /tmp/ss && unzip -o -q oplus-services.jar 'classes*.dex' -d dex
DEX=/Users/rikumi/Library/Android/sdk/build-tools/30.0.3/dexdump
$DEx dex/classes3.dex 2>/dev/null | grep -nE "name +: 'moveTaskToBackForPanorama'" -A1
```
以下签名已核对（用于 afterHook 拉回前台，均有效）：`moveTaskToBackForPanorama(Lcom/android/server/wm/Task;ZI)V`、`isInFloatingList(I)Z`、`FloatHandleController.getInstance()Lcom/android/server/wm/FloatHandleController;`、`Task.mTaskId:I`。`Task.moveToFront` 因版本差异用多候选（`moveToFront(String)` / `moveToFront(int,boolean,String)` / 经 ATMS `moveTaskToFront`）兜底。

### 部署要点（Feature 18 专属）
- 该逻辑在 `com.android.server.wm`（装在 `oplus-services.jar`），**不在 SystemUI.apk**，因此必须把模块作用域加入 **`android`（system_server）**（见 §1 / `arrays.xml` 的 `xposedscope`）。
- 改完 `install -r` 后，如需让新 hook 进入 `android` 作用域，必须先询问用户；获得明确同意后，**只能使用项目既有的 `setprop ctl.restart zygote` 方式**，禁止 `pkill -f system_server` / `killall system_server`。
  这是框架级软重载（所有 app 会跟着重载），不是设备重启；执行前必须得到用户明确同意。
- ⚠️ **作用域必须真的勾选**：本功能首轮"没生效"的真正原因是用户没在 LSPosed 勾选 `android` 作用域，hook 从未注入（不是代码问题，见 §6.3 不要在未确认作用域前怀疑注入）。

### 可复用的方法论（以后遇到"feature 没效果 / 不知道 hook 哪个方法"时）
1. 已猜过 ≥2 个 hook 点仍不生效 → **停止猜**，用 simpleperf 抓真实调用链。
2. 先录直觉进程（如 systemui）；若火焰图里只有"通知/收尾"而无"提交（`applyTransaction`/`startTransition`/`moveTaskToBack`/`reparent`）"，说明提交在别的进程 → 转录 `system_server`（或 binder 对侧进程，-p 逗号分隔可一次录双进程）。
3. 录的时候**务必覆盖触发动作的发生时刻**（松手/点击那一帧），否则只看到拖拽中的噪声。
4. 抓到真实方法后，用 `dexdump` 对**设备当前框架 jar** 核对方法签名（混淆名以 dexdump 为准，不凭 jadx 记忆）。
5. 注意"提交点"可能在 `android`（system_server）作用域，需相应加作用域；如需重新加载，必须先询问用户，且只能使用项目既有的 `setprop ctl.restart zygote` 方式，禁止 `pkill system_server`。
6. 与 §4 互补：反编译（`android-app-mods`）能看清已知类的逻辑，但当"提交到底发生在哪个进程/哪个方法"未知时（尤其 ColorOS 把逻辑藏进 `com.oplus.*` / `com.android.server.wm` 等框架包），simpleperf 是定位的利器。

---

## 15. 手势滑动条高度调整（最终实现，已验证）

需求：手势导航底部白色滑动条默认增加 10dp；白条上移增加量的一半；`navigationBars` 与 `mandatorySystemGestures` 的底部 inset 同步增加。

### 反复无效的路径

1. `NavigationBar#getBarLayoutParamsForRotation(int, WindowMetrics)`：未产生效果。
2. `WindowManagerImpl.addView/updateViewLayout`：ColorOS 当前路径未命中。
3. `WindowManagerGlobal.addView/updateViewLayout`：当前设备路径未命中。
4. system_server 的 `Session#addToDisplay/relayout`：不采用，且需要重载 system_server。

### 最终有效路径

目标进程：`com.android.systemui`。

目标类：`com.android.systemui.navigationbar.views.NavigationBar`。

目标方法：`getBarLayoutParams(int)`，该方法在 `NavigationBar#onInit` 中被直接传入 `mWindowManager.addView(mFrame, ...)`。afterHook 中修改返回的 `WindowManager.LayoutParams`：

- `lp.height += extraPx`；
- 遍历 `paramsForRotation[0..3]`，同步增加每个 rotation 的高度；
- 遍历 `providedInsets` 中的 `InsetsFrameProvider`，按其当前 bottom inset 筛选底部 provider，避免依赖设备差异下的隐藏类型访问；
- 使用 `InsetsFrameProvider#setInsetsSize(Insets.of(left, top, right, bottom + extraPx))`，并以 `mInsetsSize` 字段写入作为反射兜底。

当前设备 density 约为 2.96。最终诊断验证使用固定 20dp 时窗口 `Requested h` 从 161 增至 251；恢复默认 10dp 后，窗口增加约 30px，insets 实测为：

- `navigationBars`：bottom 从 48 增至 78；
- `mandatorySystemGestures`：bottom 从 95 增至 125；
- 对应 frame 也同步扩大，证明系统窗口 inset 已真正生效。

### 白色滑动条

真实类名是 `com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle`，不是此前误写的 `com.oplusos...`。

hook `OplusNavigationHandle#onDraw(Canvas)`，在 beforeHook 中 `canvas.save()` 后向上平移 `extraPx / 2`，afterHook 中 `restore()`。真实绘制代码使用 `getHeight()` 与 `mHandleBottom` 计算白条位置，因此窗口增高后再上移半个增量即可保持视觉位置正确。

### 重要结论

- `getBarLayoutParams(int)` 是本设备上唯一已验证命中的布局生成点。
- `providedInsets` 不能只修改 top-level `LayoutParams`；必须同步修改 `paramsForRotation` 中的 provider。
- 设备上 `InsetsFrameProvider` 的类型/隐藏 API 反射存在差异，按 provider 当前 bottom 值筛选比依赖 `getInsetsType()` 更可靠。
- 只需重载 `com.android.systemui` 即可验证本功能，不需要重载 system_server。
- 修改 `android` 作用域的其他功能时，遵守项目规则：不得擅自重载 system_server；如确需重载，必须先询问用户，获同意后只能使用 `setprop ctl.restart zygote`。
