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
- **Feature 18：悬浮小窗贴边挂机（float_window_edge_hang_enabled）**：作用域扩展到 **`android`（system_server）**。当前实现为在 `TaskExtImpl#moveTaskToBackForPanorama` 的 **beforeHook** 里 `setResult(null)` 跳过 `Task.moveTaskToBack`，任务因此留在台前继续挂机，并在 `Task#prepareSurfaces` 后持续把该任务的 surface 保持隐藏（见 §14 与 §30）。仅对"贴边成浮窗"一路（`isInFloatingList(taskId)` 为真）生效，普通最小化不介入。稳定无 ANR。

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
| 贴边挂机 hook | `hookFloatWindowEdgeHangSystemServer()`（**android/system_server 作用域**）：① hook `com.android.server.wm.TaskExtImpl#moveTaskToBackForPanorama`（`(Lcom/android/server/wm/Task;ZI)V`, before）`setResult(null)` 跳过切后台，任务留台前挂机；② hook `Task#prepareSurfaces` after，对挂机中的任务持续 `getSyncTransaction().hide(surfaceControl)`（见 §14 / §30）。对"贴边成浮窗"一路（`FloatHandleController.isInFloatingList(taskId)` 为真）生效，普通最小化不介入。|
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
- **最终做法（2026-08-30 改版，见 §30）**：`hookFloatWindowEdgeHangSystemServer` **hook `TaskExtImpl#moveTaskToBackForPanorama` 的 beforeHook 直接 `setResult(null)`**，只跳过"切后台"这一步，不阻止 to-float 本身的窗口隐藏动画。任务留在台前（挂机）、surface 保持隐藏，焦点矛盾由"surface 隐藏 + 仍在 floating list"这一状态自身承担，实测无 ANR。
  （2026-08-30 之前的旧做法是 afterHook 里 `moveToFront` 拉回前台，会重新 show 出真实窗口，已废弃。）

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

## 16. 手势滑动条宽度调整

手势白条宽度设置项为 `gesture_bar_width_enabled` / `gesture_bar_width_dp`，默认开启，默认 `100dp`，可调范围 `80–120dp`。设置界面通过 `SwitchItem.sliderMin`、`sliderMax` 映射实际范围，SystemUI hook 对读取值再次限制在 `80–120dp`，避免旧值越界。只修改 `OplusNavigationHandle` 自身的宽度，并设置居中布局，不修改手势区窗口或系统 insets。

## 17. 解锁时关机无需校验密码

设置项 `unlocked_shutdown_noverify_enabled`（标题「解锁时关机无需校验密码」，位于新增的「电源」分组），默认关闭。

### 逆向结论（jadx + dexdump 双重核对）

- 系统「关机校验密码」开关由设置 App 写入：`com.oplus.settings.feature.security.controller.ShutdownVerificationPasswordSwitchController`
  写 `Settings.Secure` 键 **`oplus_shutdown_need_verification_password`**（常量 `CustomSettings.Secure.VERIFICATION_PASSWORD_WHEN_SHUTDOWN`）。
- 该键在 SystemUI 侧的唯一读取点：`com.oplus.systemui.shutdown.ShutdownBiometricPrompt`
  （`SHUTDOWN_NEED_VERIFICATION = "oplus_shutdown_need_verification_password"`）。
  全仓（含 `_framework`、settings、safecenter）除设置外只有它引用该字符串。
- 调用链：`ShutdownViewControl` → `AuthenticationListener.handleAuthentication(onSuccess, onError)`
  → `ShutdownBiometricPrompt.isEnable(mContext)`。返回 `true` 才弹凭据校验，
  `false` 则直接 `runnable.run()` 执行关机/重启（`ShutdownViewControl` 内 6 处调用点）。
  实现者两处：`OplusGlobalActionsDialog$3`、`OplusGlobalActionsDialogSubDisplay$2`。
- 校验 UI 是 `BiometricPrompt`，`setAllowedAuthenticators(32768 = DEVICE_CREDENTIAL)`、
  `setUseDefaultTitle()`、`setConfirmationRequired(true)`，所以 PIN/密码/图案都在这一层。
- dexdump 核对（`SystemUI.apk` 的 `classes8.dex`，方法 #4）：
  静态 `Lcom/oplus/systemui/shutdown/ShutdownBiometricPrompt;.isEnable:(Landroid/content/Context;)Z`，
  另有 `Companion.isEnable` 供内部委托；hook 静态版本即可覆盖两个调用方。

### 实现

`SystemUiHooks#hookUnlockedShutdownNoVerify`：`afterHook` 中运行时门控
`readBool(KEY_UNLOCKED_SHUTDOWN_NOVERIFY_ENABLED, false)`，仅当系统原本返回 `true`、
且设备**已解锁**时才 `setResult(false)` 跳过校验。

「未解锁」判定 `isDeviceLocked(Context)`：`KeyguardManager.isKeyguardLocked() || isDeviceLocked()`，
任一为真即保守不跳过；取不到状态时返回 `true`（退回系统原生校验）。

### 验证

在模块 App 打开开关 → 重载 SystemUI → 解锁状态下长按电源键拖动关机，应直接关机不弹校验；
锁屏状态下长按电源键关机，仍应弹出密码校验。

## 18. 取消解锁界面控件光效

设置项 `keyguard_no_light_effect_enabled`（标题「取消解锁界面控件光效」）。与「解锁时关机无需校验密码」
同属「锁屏」分组（该分组原名「电源」，因本功能加入而改名）。

### 逆向结论（uiautomator 布局树定位 + jadx 源码 + dexdump 签名核对）

用 `adb exec-out uiautomator dump /dev/tty` 抓锁屏 PIN 界面（不落盘，符合只读要求），拿到真实控件 id：

| 界面元素 | 布局 id | 类 |
|---|---|---|
| 已输入圆点 | `colorSimpleLock` | `com.oplus.keyguard.security.widget.PinSimpleLockInputWidget extends COUISimpleLock` |
| 密码按键 | `pinColorNumericKeyboard` | `com.oplus.keyguard.security.widget.NumericKeyboardWidget extends COUINumericKeyboard` |
| SIM 键盘 | `simKeyboard` | 同上 `NumericKeyboardWidget` |
| SIM 输入框 | `securityEditInputWidget` → `pwd_input_layout` | `com.coui.appcompat.input.COUILockScreenPwdInputView` |
| SIM 确定按钮 | 同上 layout 的 `mNextIcon` | `com.coui.appcompat.input.COUILockScreenPwdInputLayout#dispatchDraw` |

布局文件：`res/layout/kgd_security_pin_six_view_layout.xml`、`kgd_security_sim_view_layout.xml`、
`kgd_security_edit_widget_layout.xml`。两个 Widget 子类都**没有**覆写绘制方法，hook 父类私有方法即可。

### COUI 的三类「非纯色」绘制

1. **径向渐变光晕**：`com.coui.appcompat.lockview.LightEffectHelper#drawLightEffect`
   （`RadialGradient` + `BlendMode.LIGHTEN`）。键盘按键经 `COUINumericKeyboard#drawLightEffect`
   （仅 `mPressEffectStyle == 1` 时调用）触发；SIM 确定按钮在 `COUILockScreenPwdInputLayout#dispatchDraw`
   里内联展开，受 `mLightEffectAlpha > 0f` 门控。
2. **内阴影**：`InnerShadowHelper`（lockview / input 各一份）生成的 Bitmap，
   在 `COUINumericKeyboard#drawInnerShadowLayer`、`COUILockScreenPwdInputView#onDraw`、
   `COUILockScreenPwdInputLayout#dispatchDraw` 里 `drawBitmap`。
3. **高光描边**：`COUINumericKeyboard#drawInnerBorder` 中当 `cell.mInnerLightAlpha > 0f` 时，
   用 `mBorderLineHighLightAlpha` + `BlendMode.LUMINOSITY` 再描一圈；常规纯色描边
   （`mBorderLineColor`）在其后无条件绘制，属于纯色需保留。

另有已输入圆点的光晕：`COUISimpleLock#drawGlowEffect`（画 `mGlowEffectDrawable`），
调用点在 493 / 569 行两处。

按下时的 `COUIPressFeedbackHelper` 缩放与 `drawPressCircle` 变色**不是**光效，必须保留。

### dexdump 核对（`classes2.dex` / `classes7.dex`）

```
Lcom/coui/appcompat/lockview/COUINumericKeyboard;.drawLightEffect:(Landroid/graphics/Canvas;II)V
Lcom/coui/appcompat/lockview/COUINumericKeyboard;.drawInnerShadowLayer:(Landroid/graphics/Canvas;FFLcom/coui/appcompat/lockview/COUINumericKeyboard$Cell;IIF)V
Lcom/coui/appcompat/lockview/COUINumericKeyboard;.drawInnerBorder:(Landroid/graphics/Canvas;FFLcom/coui/appcompat/lockview/COUINumericKeyboard$Cell;IIF)V
Lcom/coui/appcompat/lockview/COUISimpleLock;.drawGlowEffect:(Landroid/graphics/Canvas;IIIII)V
```
字段名均为 dex 真名，非 jadx 美化：`COUINumericKeyboard$Cell.mInnerLightAlpha`、
`COUILockScreenPwdInputLayout.{mLightEffectAlpha, mInnerShadowBitmap}`、
`COUILockScreenPwdInputView.mInnerShadowBitmap`。
`COUILockScreenPwdInputLayout` / `View` 定义在 `classes7.dex`（1978050 / 1981793 行起）。
`dispatchDraw` / `onDraw` 是覆写方法，dex 中无 invoke 引用，签名取 `(Landroid/graphics/Canvas;)V`。

### 实现（`SystemUiHooks#hookKeyguardNoLightEffect`）

- `drawLightEffect` / `drawInnerShadowLayer` / `drawGlowEffect`：before 中 `param.setResult(null)` 跳过。
- `drawInnerBorder`：before 中把 `args[3]`（Cell）的 `mInnerLightAlpha` 置 `0f`，只掐高光、保留纯色描边。
- 两个 PwdInput 的 `dispatchDraw` / `onDraw`：before 把 `mInnerShadowBitmap` 换成 1x1 全透明 Bitmap
  （`drawBitmap` 不接受 null，换透明图等价于不绘制），after 换回；layout 额外把
  `mLightEffectAlpha` 在绘制期间置 `0f` 并在 after 恢复（`param.setObjectExtra` 暂存原值）。

### 工具备忘

抓布局一律用 `adb exec-out uiautomator dump /dev/tty`，**不要** `uiautomator dump /sdcard/xxx` 再 `adb pull`，
后者会在设备写入文件，违反只读规则。

### 补充：去掉光效后必须补纯色背景（2026-08-29 追加）

第一版只去掉光效后设备上「没有背景色了」，原因是**内阴影 bitmap 同时充当了这些控件唯一的可见背景**：

- `COUILockScreenPwdInputLayout` 构造里（`mScenesMode == 1 && UIUtil.confirmLevelAnim(...)` 分支）：
  `mNextIcon.setBackgroundColor(0)` + `mInputView.setBackgroundColor(0)` —— 因为内阴影+光晕会提供视觉。
  传统路径（`ScenesMode != 1`）才给 `R.color.coui_input_lock_screen_pwd_view_bg_color_desktop`
  （`#33ffffff` = 20% 白，`res/values/colors.xml:917`，在 res 中除 public.xml 外无引用）；
  非 desktop 路径给 `R.attr.couiColorCard`。
- `COUINumericKeyboard`：`mPressEffectStyle == 1` 时背景由 `mDrawDelegate.getCustomKeyboardPaint()` 提供，
  但 SystemUI 里**没有任何 `setKeyboardDrawDelegate` 调用者** → delegate 为 null；
  传统路径 `mPressEffectStyle == 0` 才用 `mNumberBackground` + `mNumberBackgroundColor`
  （style `LauncherNumericKeyboardStyle` → `@color/kgd_color_numeric_keyboard_setting_background_color`
  = `#33ffffff`，但该 style 只在 `styles.xml:14154` 赋给 `?numericKeyboardStyle`）。

取色顺序（实现为 `resolveSolidBgColor` / `drawKeySolidBackground`）：
1. 系统/传统代码为该控件配置的纯色背景（非透明则用）；
2. 取不到（即「传统界面代码已被新界面替代」，设备上的实际情况）→ **常态 10% 白 `0x1AFFFFFF`，按下 16% 白 `0x29FFFFFF`**。

绘制点：
- 按键：hook `drawInnerShadowLayer` 的 before 里先用系统自己的 `mNumberBackground`
  （OVAL `GradientDrawable`）调私有 `drawBackground(canvas, cx, cy, alpha, tx, ty, mButtonScale)` 画纯色，
  再 `setResult(null)` 跳过内阴影。
- 按下判定与系统 `drawPressCircle` 同口径：`cell.normalAlpha > 0 || cell.blurAlpha > 0`
  （`getTouchIndex(cell)` 只返回 `row*3+col`，**不能**用来判定按下）。
- 输入框：`onDraw` before 里 `canvas.drawPath(mBackgroundPath, fillPaint)`（圆角矩形，Path 在 `onSizeChanged` 构建）。
- 确定按钮：`dispatchDraw` before 里按与系统完全一致的圆心/半径
  （`cx=(right+left)/2`、`cy=(bottom+top)/2`、`r=scaleY*height/2`）画圆，`isPressed()` 决定按出色。

输入控件两个 hook 都加了 `scenesMode(thisObject) == 1` 判断，避免在传统界面多画一层。

### 修正：数字键背景改为在 `drawCell` 里直接画圆（2026-08-29 三修）

**第一轮失败原因**：把纯色背景画在 `drawInnerShadowLayer` 的 beforeHook 里，靠反射调用私有
`drawBackground(canvas, cx, cy, alpha, tx, ty, scale)`（内部走 `mNumberBackground` 这个
OVAL `GradientDrawable` 的 `setSize`/`setBounds`/`setAlpha`/`draw`）。设备上结果仍是「数字键没有背景」。
没有日志可查（`log()` 是空实现），因此改为消除所有不确定环节，而不是继续猜。

**最终方案**：hook `COUINumericKeyboard#drawCell:(Landroid/graphics/Canvas;II)V` 的 beforeHook，
直接 `canvas.drawCircle`。理由：

- `drawCell` 是每一格的绘制入口，`mPressEffectStyle` 为 0 或 1 都会走；
  9/11 号侧边键（删除/确定）虽然会提前 `return`，但 beforeHook 仍会执行，所以侧边键一并覆盖。
- 在 before 里画 = 位于内阴影/描边/数字之下，层次正确；`drawInnerShadowLayer` / `drawLightEffect`
  仍被跳过，`drawInnerBorder` 仍只掐高光。
- 参数全是基本类型，**不依赖 `COUINumericKeyboard$Cell` 类能否 findClass**（此前整段
  innerShadow/innerBorder 的 hook 都排在 `findClass(Cell)` 之后，一旦失败就整体 return）。
- 不再反射调用 `drawBackground`，少一层失败点。

**坐标与状态取值**（全部按系统源码同口径，字段名已 dexdump 核对）：

```java
// drawCell(Canvas canvas, int i, int i2)
Cell cell = sCells[i2][i];                      // sCells 是静态字段(dex 名确为 sCells 而非 mCells)
cx = getCenterXForColumn(i) + cell.cellNumberTranslateX;
cy = getCenterYForRow(i2)   + cell.cellNumberTranslateY;
r  = mNumberBackgroundRadius * cell.mButtonScale;   // 与 drawInnerBorder 的圆心/半径完全一致
alpha = cell.cellNumberAlpha;
pressed = cell.pointerId != -1;                    // down 置触点 id，up/cancel 复位 -1
```

按下判定**不能**用 `normalAlpha`/`blurAlpha`（那是 style 0 的 `drawPressCircle` 用的，style 1 不维护），
也**不能**用 `mInnerLightAlpha`（被我们自己清零了）。`getTouchIndex(cell)` 只返回 `row*3+col`，与按下无关。

兜底：`sCells` 下标顺序、`mNumberBackgroundRadius`、Cell 字段任一取不到时，退回按
`width/3`、`height/4` 计算的几何圆心与半径，保证"至少一定有背景"，只损失按下态与缩放。

**dex 字段真名核对**（`classes2.dex`，COUINumericKeyboard 定义 5287572 行起，Cell 定义 5283262 行起）：
`mDrawDelegate` / `mNumberBackground` / `mNumberBackgroundColor` / `mNumberBackgroundRadius` /
`mPressEffectStyle` / `sCells`；Cell：`blurAlpha` / `cellNumberAlpha` / `cellNumberTranslateX` /
`cellNumberTranslateY` / `mButtonScale` / `mInnerLightAlpha` / `normalAlpha` / `pointerId`。

### 再修正：数字键背景改到 `drawInnerShadowLayer`（2026-08-29 四修）

**第二轮失败原因**：上一轮改到 `drawCell(Canvas,int,int)` 的 beforeHook，自己反射调
`getCenterXForColumn`/`getCenterYForRow` 并从静态 `sCells[row][col]` 取 Cell 再算
`cx += cellNumberTranslateX`。设备表现：圆圈位置与真实按钮不符、且不跟随进出场动画。

用 `adb exec-out uiautomator dump /dev/tty` 抓同一界面做量化核对，发现**几何均分会错位**：

```
pinColorNumericKeyboard bounds = [174,1121][1042,2262]  → w=868 h=1141
虚拟按键中心(相对 view)：x = 119 / 434 / 749；y = 119 / 420 / 721 / 1022
而 width/3、height/4 均分给出：x = 144.7 / 434 / 723.3；y = 142.6 / 427.9 / 713.1 / 998.4
```

即中间列/行刚好重合，两侧各差约 ±25px / ±24px —— 与用户看到的"位置不一样"一致。
说明键盘不是按 view 尺寸均分的，而是 `cellWidth` + `horizontalSpacing` 排布
（`getCenterXForColumn = paddingLeft + cellWidth/2 + col*(cellWidth + hSpacing)`，源码 964 行）。

**重要澄清（dump 反推）**：`pinColorNumericKeyboard` 在 dump 里 class 显示为 `android.view.View`，
其下的 `android.widget.Button` 子节点**不是真实 View**，而是 `COUINumericKeyboard extends View`
内部类 `PatternExploreByTouchHelper extends ExploreByTouchHelper` 提供的**虚拟无障碍节点**
（`getBoundsForVirtualView` 用 `getCenterXForColumn(cell.column)/getCenterYForRow(cell.row)` ± `mCircleRadius`）。
dump 中第 4 行只有 "0" 一个节点，是因为 `row*3+col == 9 / 11` 走 `drawSide` 且
`getVirtualViewIdForHit` 对空侧边样式返回 -1。侧边键（删除/确定）在 `drawSide` 里**自带**
`mNumberBackground.setColor(mSideBackgroundColor) + drawBackground(...)`，不需要补背景。

**最终方案**：彻底不做二次推算，直接沿用系统入参。

```java
// drawInnerShadowLayer(Canvas canvas, float cx, float cy, Cell cell, int tx, int ty, float alpha)
cx_real     = cx + tx                                  // 源码 786/789 行就是这个
cy_real     = cy + ty
radius      = mNumberBackgroundRadius * cell.mButtonScale
alpha       = alpha * 255                              // 源码 775 行
pressed     = cell.pointerId != -1
```

`drawInnerShadowLayer` 在 `mPressEffectStyle == 1` 分支对**全部 10 个数字格无条件调用**
（源码 741 行，紧跟 `drawLightEffect` 之后、`drawInnerBorder` 之前），层次正好在描边与数字之下，
且 `canvas.save()/clipPath/restoreToCount` 自成一体，跳过它不影响外部。
进出场动画的 `cellNumberTranslateX/Y` 与淡入 `cellNumberAlpha` 都是方法入参，天然同步。

半径兜底：`mNumberBackgroundRadius` 读不到时退 `mCircleRadius`（无障碍节点半径，数值一致）。

---

## 锁屏通知区域下移（keyguard_notification_offset）

**需求**：锁屏 group 新增功能，锁屏通知区域下移，范围 0-40dp，默认 20dp（默认开启）。

**设置项**（`MainActivity.kt` 的 `LOCKSCREEN`）：
`keyguard_notification_offset_enabled` + 滑条 `keyguard_notification_offset_dp`（sliderMax=80，
sliderDefault=20）。常量在 `XposedInit`：
`KEYGUARD_NOTIFICATION_OFFSET_DP_DEFAULT=20`、`KEYGUARD_NOTIFICATION_OFFSET_DP_MAX=40`。

**逆向结论（jadx + dexdump 双重核对）**：锁屏上通知区就是 `NotificationStackScrollLayout` 的
top padding，其**顶部位置有且仅有三个来源**，全部必须叠加同一偏移，否则内部差值会错乱：

1. `com.android.systemui.shade.NotificationPanelViewController#getKeyguardNotificationStaticPadding()`
   —— 静止/拖拽/收起态；非锁屏时 `isKeyguardShowing()` 为假直接返回 0，否则返回
   `KeyguardClockPositionAlgorithm.Result.stackScrollerPadding + getNotificationDragAmount()`。
2. `com.oplus.systemui.notification.lockscreen.stack.OplusLockscreenShadeTransitionControllerExImpl
   #getNtfTopPaddingInLockscreenNtfCenter()` —— 锁屏通知中心展开态
   （= 资源 `stacked_notification_shade_margin_top`，96dp；由 `calculateNtfTopPaddingInLockscreenNtfCenter` 缓存）。
3. 同上 impl 的 `#getNtfTopPaddingInLockscreen()` —— 锁屏通知中心收起动画态。
   该字段由 1 在每次请求时 `setNtfTopPaddingInLockscreen(result.stackScrollerPadding)` 写入，
   存的是**未经 hook 处理的原始值**，与 1 的返回值是两条独立数据流，所以也要单独加偏移。

**汇合路径**：`NotificationPanelViewController#requestScrollerTopPaddingUpdate()` →
`mSharedNotificationContainerInteractor.setTopPosition(fMax2)` →
`SharedNotificationContainerViewModel#getBounds`（`Utils.sample(topPosition, isInTransition, qsExpansion)`
+ `flowName`）→ `OplusNotificationContainerExImpl#onNotificationContainerBounds`（Oplus 实现里
无论 `z` 真假都把 top 覆盖成入参 `f`）→ `SharedNotificationContainerBinder` case 3 →
`NotificationStackScrollLayout#updateTopPadding(float, boolean)` → `AmbientState.topPadding`。
`setTopPadding` 在整个 SystemUI 里只被 `updateTopPadding` 调用一次（NSSL 7269 行），所以这三个
getter 就是全部入口。反向验证：`KeyguardNotificationStackedRuler` 用
`topPadding - getKeyguardNotificationStaticPadding()` 算拖拽量，三处同步加偏移后差值不变。

**dexdump 核对**：
- classes2.dex：`NotificationPanelViewController.getKeyguardNotificationStaticPadding:()I`（PUBLIC）、
  `isKeyguardShowing:()Z`（PUBLIC FINAL）。
- classes3.dex：`OplusLockscreenShadeTransitionControllerExImpl.getNtfTopPaddingInLockscreen:()I`、
  `.getNtfTopPaddingInLockscreenNtfCenter:()I`（皆 PUBLIC）。
- 注：抽象父类 `com.android.systemui.statusbar.OplusLockscreenShadeTransitionControllerEx` 也有
  这两个方法，但 Xposed 必须 hook 具体 Impl 类，hook 父类不会触发。

**实现位置**：`SystemUiHooks#hookKeyguardNotificationOffset`（在 `hookSystemUi` 末尾调用），
运行时读 `readBool/readInt`，px = round(clamp(0..40) * density)；仅 `isKeyguardShowing()` 为真时叠加。

**构建**：本机默认 `java` 是 openjdk@11，Gradle 要求 JVM 17+，项目 toolchain 要 21。
用 `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDebug --offline` 构建通过。

### 调色修正：常态 8% 白 + 修复按下不变色（2026-08-29 五修）

最终取值（用户多次调整后定稿）：`SOLID_BG_NORMAL = 0x1AFFFFFF`（10% 白）、
`SOLID_BG_PRESSED = 0x33FFFFFF`（20% 白）。曾短暂改为 8% / 16%，已改回。

**按下不变色的根因**：取色时判据是"系统色非透明就用系统色"，而键盘的
`mNumberBackgroundColor` **确实解析到了非透明值**：

```
kgd_security_pin_six_view_layout.xml:12
  style="?numericKeyboardStyle"
      → styles.xml:14154  <item name="numericKeyboardStyle">@style/LauncherNumericKeyboardStyle</item>
      → styles.xml:4672   LauncherNumericKeyboardStyle
          <item name="couiNumberBackgroundColor">@color/kgd_color_numeric_keyboard_setting_background_color</item>
      → colors.xml:1186   #33ffffff  (20% 白)
```

`0x33FFFFFF` 非透明 → 所有格、所有状态都用这一个恒定色 → 按下态永远不变。
但这个色在 `mPressEffectStyle == 1` 下系统**根本不会使用**（传统路径才走 `mNumberBackground +
mNumberBackgroundColor`），所以应按"系统没给有效背景"处理。

**修正后的取色规则**（`isOpaqueColor`）：只有 **alpha == 0xFF（完全不透明实色）** 才算
"系统给了有效背景"才采用；半透明的 `#33ffffff` 一律跳过，走兜底 8% / 16%。
`resolveSolidBgColor`（SIM 输入框、确定按钮）同样规则 —— 它们的 background 在 ScenesMode==1
时被 `setBackgroundColor(0)`，本就透明，走兜底。

**SIM 确定按钮的按下判定也要改**：原先用 `mNextIcon.isPressed()`，但 `mNextIcon` 是 ImageView，
系统从不对它 `setPressed`（触摸由 layout 自己处理），所以恒为 false。
改用 `mLightEffectAlpha > 0f` —— 它由按下动画拉起、抬起回落为 0，是本控件唯一的按下态信号；
调用方在画完背景之后才把它清零去光晕，所以这里读到的是原始值，顺序正确。

**数字键按下判定沿用 `cell.pointerId != -1`**（已验证正确）：
`handleActionDown` 1862 行 `cellCheckForNewHit.pointerId = i`；
`executeLightEffectAnimator(cell, false)` 947 行 `cell.pointerId = -1`（抬起/取消时调用）。
注意不能用 `normalAlpha`/`blurAlpha`（那是 style 0 的 `drawPressCircle` 用的，style 1 不维护），
也不能用 `mInnerLightAlpha`（被我们自己清零了）。

### 追加：选项开启时一并去除密码按键边框（2026-08-29）

`drawInnerBorder` 的 hook 由「只清零 `mInnerLightAlpha` 掐高光」改为**整段 `setResult(null)`**。

`drawInnerBorder` 内画两层描边：
1. `cell.mInnerLightAlpha > 0` 时用 `mBorderLineHighLightAlpha` + `BlendMode.LUMINOSITY` 的高光描边；
2. 之后无条件用 `mBorderLineColor` + `alpha = mBorderLineAlpha * f3` 的常规描边。

两层都不要，直接跳过整个方法最干净。

侧边键（删除/确定）在 `drawSide` 里也调 `drawInnerBorder`，但传入 `alpha = 0.0f`，
常规描边 alpha 本就为 0 不可见，所以一并跳过不产生观感差异。

### 追加：去除密码圆点的缩放动画（2026-08-29）

hook `COUISimpleLock#drawFilledRectangleWithScale:(Landroid/graphics/Canvas;IIIII)V`
（dex 核对于 `classes2.dex`；`mCircleScales` / `mOpacitys` / `mFilledRectangleDrawable`
字段名同为 dex 真名）。

**为什么是它**：COUISimpleLock 里对圆点做 `canvas.scale` 的**只有这一个方法**
（`drawFilledRectangleWithScale`，449 行），且只在 `isTransparentStyle()` 为真时被调用
（调用点 492 / 568 行，分别在 `drawFilledToOutLined` 与 `drawOutLinedToFilled` 内）。
其余 `drawFilledRectangle` 各重载（419 / 505 / 1307 行）都不缩放。

**做法**：before 中把 `mCircleScales[args[5]]` 临时置 `1.0f`，after 还原。
系统内部取 `fMax = max(0, min(1.2f, mCircleScales[i]))` 做 `canvas.scale(fMax, fMax, cx, cy)`，
置 1.0f 后缩放退化为恒等变换，缩放动画消失；透明度淡入（`mOpacitys`）、位移
（`mTransitionX`）、`save`/`restoreToCount` 全部保持系统原样。
方法开头还有 `if (mCircleScales[i] <= 0f) return;` 的早退，置 1.0f 后也顺带不会早退。

不直接改 spring 动画（`mDotSpringAnimations` / `createPreAnimationOutLinedToFilled`）：
spring 每帧都会重写 `mCircleScales[i]`，而且删除态走的是 `mCircleScales[i] - |f - mLastValues[i]|`
的递减分支，改 spring 会连带破坏删除逻辑。置 1.0f 只在绘制瞬间生效，后立即还原，无副作用。

注意：圆点的**透明度淡入不是缩放**，未做处理，仍保留。

## 19. 取消密码界面背景遮罩

设置项 `keyguard_no_bouncer_scrim_enabled`（标题「取消密码界面背景遮罩」），默认关闭，
位于「锁屏」分组、「取消解锁界面控件光效」之下。

### 逆向结论（jadx + dexdump 核对）

密码界面（bouncer）的遮罩来自 `com.android.systemui.statusbar.phone.ScrimState` 的两个枚举状态，
用哪个由 `StatusBarKeyguardViewManager#primaryBouncerNeedsScrimming()` 决定
（`ScrimStartable` 272 行：`needsScrimming ? BOUNCER_SCRIMMED : BOUNCER`）：

| 状态 | 枚举类 | 遮罩层 | alpha 字段 |
|---|---|---|---|
| `BOUNCER` | `ScrimState$3` | bouncer 之下 | `mBehindAlpha()`、`mNotifAlpha` |
| `BOUNCER_SCRIMMED` | `ScrimState$4` | bouncer 之上 | `mFrontAlpha` |

`ScrimState$3.prepare` 里：`mBehindAlpha = mExt.adjustBouncerBehindAlpha(mBehindAlpha)`、
`mBehindTint = mExt.adjustBouncerBehindTint(...)`、`mNotifAlpha = mExt.adjustBouncerNotifAlpha(...)`、
`mNotifTint = 0`、`mFrontAlpha = 0`。
`ScrimState$4.prepare` 里：`mBehindAlpha = 0`、`mFrontAlpha = mExt.adjustBouncerScrimmedFrontAlpha(mDefaultScrimAlpha)`、
`mFrontTint = mExt.adjustBouncerScrimmedFrontTint(...)`、`mAnimateChange = true`、`mAnimationDuration = 180`。

Oplus 侧实现是 `com.oplus.systemui.statusbar.phone.ScrimStateExImp`（classes4.dex，
由 `OplusNotificationDependencyEx` 多处 `new ScrimStateExImp(context)` 提供），
它把这四个 adjust* 委托给 `KeyguardBouncerScrimDecorator.Companion`：
`getBouncerScrimAlpha()` 返回 `1.0f`（高斯模糊关闭时）或 `0.2f`（正常），
`getBouncerScrimTintLegacy()` 返回 `-16777216`（黑）。

dex 核对（classes2.dex，ScrimState 定义在 2407665 行起）：
两个 `prepare` 均为 `(Lcom/android/systemui/statusbar/phone/ScrimState;)V`、PUBLIC FINAL；
字段真名 `mBehindAlpha:F`、`mBehindTint:I`、`mFrontAlpha:F`、`mFrontTint:I`、
`mNotifAlpha:F`、`mNotifTint:I`、`mExt:Lcom/.../ScrimStateEx;`、`mDefaultScrimAlpha:F`。

### 实现（`SystemUiHooks#hookKeyguardNoBouncerScrim`）

hook `ScrimState$3.prepare` 与 `ScrimState$4.prepare` 的 afterHook，把对应 alpha 置 0
（`$3`：`mBehindAlpha` + `mNotifAlpha`；`$4`：`mFrontAlpha`）。tint 不动 —— alpha=0 即完全透明。

**为什么选 prepare 而不是 hook `ScrimStateExImp#adjustBouncer*`**：
字段定义在基类 `ScrimState` 上，不依赖 `mExt` 的具体实现（换 vendor 实现也不失效）；
且只影响这两个状态，`KEYGUARD` / `UNLOCKED` 等其它状态不受影响。

## 21. 设置界面改为「首页分类入口 + 分类子页面」

需求：模块设置主界面作为主入口，每条为一个分类，点击进入子页面承载该类功能；
子页面带返回标题栏，右侧保留重启按钮；首页每个分类 item 使用 Miuix 图标。

### 结构（`MainActivity.kt`）

- `Category(id, title, icon: ImageVector, items)` + `CATEGORIES` 七个分类：
  桌面 `GridView`、控制中心 `Tune`、通知中心 `Community`、隐藏应用 `Hide`、
  小窗 `Copy`、导航与手势 `Backup`、锁屏 `Lock`。
  图标由用户指定；`Messages`/`Recent`/`Layers` 是填充图标，用户明确要求换成线条风格的
  `Community`/`Backup`/`Copy`。
- `SettingsScreen` 只持有跨页面状态：`prefs` / `version` / `masterOverride` /
  `openCategoryId`，用 `AnimatedContent(openCategoryId)` 在 `HomeScreen` 与
  `CategoryScreen` 之间做左右滑入淡入切换（300ms，CSS ease 曲线），
  `BackHandler` 仅在子页面启用。
- `HomeScreen`：大标题 + 重启菜单 → 分组标题 → 分类入口卡片（每组一张卡）。
- `CategoryScreen`：`CouixTopAppBar`（返回按钮 + 小标题 + 重启菜单 + 滚动分割线）
  → `CategoryMasterToggle` → 该分类的 `CouixGroup`。
- 重启菜单抽成 `RestartMenu(ctx)`，首页与子页面共用，行为不变。

### 首页/分类页的最终形态（2026-08-29 用户追加要求）

- **分类分组**：`CATEGORY_GROUPS: List<List<Category>>`，`CATEGORIES = flatten()`。
  第一组：桌面、控制中心、通知中心、锁屏；第二组：隐藏应用、小窗、导航与手势。
  首页对每组渲染一张 `CouixCard`。
- **首页分组标题**「By Rikumi / Couix 基于 Miuix 魔改 / 让 Flyme 精神永续」保留在首页，
  不进入子页面。
- **一键启用：首页与子页面各一个（2026-08-29 最终定稿）**。
  - 首页：全局主开关，作用于 `CATEGORIES.flatMap { it.items }`，副标题
    `HOME_MASTER_HINT` = 「点击无脑启用全部，注意下方隐藏应用的设置」。
  - 子页面：`CategoryMasterToggle(checked, onCheckedChange)` 一处配置、各页复用，
    只作用于 `category.items`，**不带副标题**。
  - 「滑块最左/最右为系统值，中间通常为推荐值」= `SLIDER_GROUP_HINT`，用 `CouixSmallTitle`
    渲染在子页面**主开关卡片的上方**（代码注释标为"第一个 group 的 header"）。
  - **主开关标题三档常量（首页与子页面的"开启态"文案不同，别合并）**：
    `MASTER_TITLE_OFF = "一键启用"`（两处共用）、
    `HOME_MASTER_TITLE_ON = "启用模块"`（仅首页）、
    `CATEGORY_MASTER_TITLE_ON = "启用功能"`（仅子页面）。
    开启后隐藏副标题：`subtitle = if (masterChecked) null else HOME_MASTER_HINT`。
- **Category 的两类附加文案（2026-08-29 追加，两者用途不同别混用）**：
  - `subtitle: String?`：显示在**首页入口行右侧**（标题 weight(1f) 之后、箭头左边，
    body2 + `TextAlign.End`），**不占第二行**。箭头左侧间距仅 subtitle 非空时才加
    （`if (subtitle != null) COUIX_CATEGORY_SUBTITLE_GAP else 0.dp`），避免无副标题行变窄。
    渲染能力保留，目前无分类使用（小窗曾用「需重启 Zygote」，用户后来撤下）。
  - `hint: String?`：显示在**子页面设置项 group 的 header**——即主开关卡片之后、
    设置项卡片之前，用 `CouixSmallTitle` 渲染，仅配置了 hint 的分类才有。
    目前仅小窗使用：`hint = "更改小窗设置需重启 Zygote 生效"`。
- **masterOverride 按 scope 隔离**：`private data class MasterOverride(scope: String?, value: Boolean)`，
  `scope = null` 表示首页全局（覆盖所有分类），否则只覆盖该分类。
  取值统一走 `MasterOverride?.valueFor(scope)`：
  `this?.takeIf { it.scope == null || it.scope == scope }?.value`。
  首页传 `valueFor(null)`、子页面传 `valueFor(category.id)`。
  落盘完成后仅当 `masterOverride == MasterOverride(scope, target)` 时才清除，
  避免 A 开关的延迟协程误清 B 开关刚设置的覆盖。
  `masterChecked` 的 `remember(version)` **必须无条件调用**（不能写在 `override ?: remember{}` 里，
  短路会导致 slot 数量随分支变化）。
- **子页面标题字号**：`CouixTopAppBar` 用 `textStyles.title3`（20sp）+ `FontWeight.Bold`，
  原为 `body1`（16sp）。Miuix 默认字号：main/paragraph/button 17、body1 16、body2/subtitle 14、
  footnote1 13、footnote2 11、headline1 17、headline2 16、title1 32、title2 24、
  title3 20、title4 18。`couixTextStyles()` 只覆盖了 body1（16sp Bold）和 body2（14sp），
  其余沿用 Miuix 默认值，且默认 textStyles **不带 fontWeight**（需显式 Bold）。
- **页面切换动画**：`PAGE_TRANSITION_MS = 300`（原 220），
  `PAGE_TRANSITION_EASING = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)`（CSS ease）。
  注意 Compose `tween()` 默认 easing 就是 `FastOutSlowInEasing`，所以"改成 ease"要显式传
  `CubicBezierEasing` 才有区别。

### `Couix.kt` 新增/调整

- `CouixTopAppBar`：子页面标题栏（navigationIcon + title + actions + 分割线）。
- `CouixTopBarDivider(progress)` + `couixTopBarDividerProgress(listState)`：
  把原先内嵌在 `SettingsScreen` 里的分割线逻辑下沉复用；`CouixLargeTitle`
  新增 `dividerProgress` 参数，内部自行绘制分割线。
- `CouixCard`：抽出与 `CouixGroup` 同款的分组卡片容器，`CouixGroup` /
  `CouixSelectGroup` / `CouixMasterToggle` 全部改为复用它。
- `CouixCategoryRow`：分类入口行（图标 + 标题 + `ChevronForward`）。
  **最终样式（用户指定）**：图标无底色容器、无容器内边距，直接绘制，22dp；
  图标 `tint = onSurface`（与标题文字同色，**不用主题色**）；图标与标题间距 14dp。
- `CouixItemDivider(modifier, startInset = COUIX_DIVIDER_INSET)`：新增 `startInset`
  参数。分类列表传 `startInset = COUIX_CATEGORY_TEXT_START`
  （= `COUIX_ROW_HPADDING 16 + 图标 22 + 间距 14 = 52dp`），
  使分割线从标题左缘起、避开图标；普通设置项仍用默认 16dp。

### 设置项列表的 divider 分组（2026-08-29 追加）

- `SwitchItem` 增加 `dividerBefore: Boolean = false`。标记了它的项会与前面的项
  **拆成两张独立卡片**（`CouixGroup` 两张），中间自然留出 group 间距 16dp；
  不是卡片内的一条细线。
- 切分逻辑 `List<SwitchItem>.splitByDivider(): List<List<SwitchItem>>`：
  遇 `dividerBefore` 且已有前段则另起一段；首项标记也不会产生空段。
- `CategoryScreen` 在**可组合函数体内**（不是 `LazyListScope` 里）调用
  `remember(category) { category.items.splitByDivider() }`，再 `groups.forEach { item { CouixGroup(...) } }`。
  **坑：`remember` 不能写在 `LazyColumn { }` 的 `LazyListScope` lambda 内**——
  它不是 `@Composable` 上下文，会报 "@Composable invocations can only happen
  from the context of a @Composable function"。
- 目前使用处：隐藏应用分类，第 5 项「彻底隐藏电话本图标」标 `dividerBefore = true`，
  把最后三项（电话本 / Gboard / GhostLock）与前四项隔开。

### 坑

- `CouixCategoryRow(icon, title, onClick, modifier)` 的 `modifier` 在最后且带默认值，
  **不能用尾随 lambda 传 `onClick`**（会被解析到 `modifier`），必须写成具名参数
  `onClick = { ... }`。`CouixCard(modifier) { }` 则可以用尾随 lambda（content 在最后）。
- Miuix 官方图标文档页（`compose-miuix-ui.github.io/miuix/zh_CN/guide/icons`）**不展示图标图形**，
  只有名称列表；jsCanvas 示例页用 canvas+wasm 渲染，不便抓取。挑选图标图形时不要再走这条路，
  直接让用户指定即可。

## 20. 输入密码界面支持侧滑或下滑返回（keyguard_bouncer_swipe_back_enabled）

设置项 `keyguard_bouncer_swipe_back_enabled`（标题「输入密码界面支持侧滑或下滑返回」），默认关闭，无滑条，
位于「锁屏」分组。由"密码界面允许指纹解锁"方案在 2026-08-29 改为本方案（指纹方案判定链路太长、
实测不生效，改为更直接的"手势返回"）。`MainActivity.kt` 的 `LOCKSCREEN` 列表加
`SwitchItem("keyguard_bouncer_swipe_back_enabled", "输入密码界面支持侧滑或下滑返回")`。

**需求**：开启后，在锁屏密码/PIN 界面(bouncer)：
1. 键盘区下滑手势穿透到背景层，和背景层下滑一样收起 bouncer 返回锁屏；
2. 放行系统侧滑返回手势（取消 bouncer 对侧滑的屏蔽）；
3. 把"上滑使用指纹解锁"提示改为"下滑返回指纹解锁"。

### 真实类路径（新鲜 src-java 核对）

- **返回总入口**：`com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager#onBackPressed()`（707 行）。
  bouncer 显示时它经 `PrimaryBouncerExpansionCallback.onVisibilityChanged(true)` 调
  `registerOnBackInvokedCallback(0, mOnBackInvokedCallback)`（199 行），该 callback 的
  `onBackInvokedCompat()`（225 行）直接调 `onBackPressed()`。系统 back 收起 bouncer 就走这里。
  另有 `reset(boolean)`（873 行）是背景层下滑收起用的入口。
- `com.oplus.securitykeyboardui.SecurityKeyboardView#onTouchEvent(MotionEvent)`（1364 行）：自定义 View，
  触摸全被消费。`getKeyIndices(int x, int y, int[] nearby)`（435 行）在 `isSecurityNumericKeyboard()`
  下，落点 x<=mSpecialKeyWidth 且 y<=特殊列高时返回 -1（621-624 行），非按键区也返回 -1，
  **只有落在数字键上才返回索引** —— 用来区分"0 两侧不可见按键/删除键"与数字键。
- **侧滑真实实现**：`com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector`
  （继承 `com.android.systemui.navigationbar.SideGestureDetectorEx`，**后者是空壳基类，方法体全空**）。
  `EdgeBackGestureHandler#onInputEvent$1`（708 行）按 `isGestureUpMode()` 二选一分发：
  上滑模式→`NavigationBarGestureUpExlmpl#onMotionEvent`，否则→`SideGestureDetector#onMotionEventImpl`。
  设备 `settings get secure hide_navigationbar_enable` = **3 → 侧滑模式**，所以走 `SideGestureDetector`。
  ⚠️ 首版 hook `EdgeBackGestureHandler#isHandlingGestures()` 无效：它只是
  `mIsEnabled && mIsGestureHandlingEnabled && mIsBackGestureAllowed` 的只读 getter（677 行），
  不参与逐点判定。

### 设备实测结论（2026-08-29）

侧滑返回、键盘下滑返回（0 两侧 / 删除键 / 按键间隙）、文案替换**三项均已生效**。
布局实测：键盘节点 bounds `[174,1121][1042,2262]`，屏幕 1216×2640（键盘下方仍有约 378px），
所以此前"底行紧贴屏幕滑不够距离"的推断是错的，阈值从来不是瓶颈。

### 实现（`SystemUiHooks#hookBouncerSwipeBack`，`hookSystemUi` 末尾调用）

四个 hook，统一门控 `readBool(KEY_KEYGUARD_BOUNCER_SWIPE_BACK_ENABLED, false)`：

0. `StatusBarKeyguardViewManager` **构造 hook**：`XposedBridge.hookAllConstructors`（免签名）在 after 里
   把实例缓存到静态 `sKeyguardViewManager`。
   ⚠️ 首版改为 hook `isBouncerShowing()` 的 before 来缓存是**不可靠**的：若该方法恰未被调用，实例恒为
   null，下滑会静默失效且无任何日志。构造函数方式在 SystemUI 启动时必然命中（该类是单例）。
1. **PIN/数字键盘** `com.coui.appcompat.lockview.COUINumericKeyboard#onTouchEvent` **beforeHook**：
   这是 PIN 界面的键盘（`NumericKeyboardWidget extends COUINumericKeyboard`），**必须单独 hook**。
   - `ACTION_DOWN` 记 `bouncerSwipeStartY`，用 `checkForNewHit(x, y)` 取落点 Cell，按
     `idx = row*3+col` 判定：null（间隙）/ 9（0 左侧键）/ 11（右侧删除键）允许下滑，
     数字键 0..8、10 不拦截；存 `bouncerSwipeAllowed`；
   - `ACTION_MOVE` 且允许、且 `ev.getY() - startY >= 48dp` → `dismissBouncer()` 成功则置
     `bouncerSwipeFired` 并 `setResult(true)`，之后所有事件都吞掉避免误触按键；
   - `UP/CANCEL` 复位 `bouncerSwipeFired`。手指滑出 View 边界后仍会收到 MOVE（Android 在 UP 前保持
     同一目标 View），所以从最底行起手也有足够滑动距离。
2. **字母键盘** `SecurityKeyboardView#onTouchEvent` **beforeHook**：逻辑同上，落点用
   `isKeyboardSwipeStartAllowed()`（按 `mKeys[idx].codes[0]` 排除数字键 '0'-'9'）。
   ⚠️ 首版"下滑时 `setResult(false)` 让事件上抛父容器"无效：一旦 `ACTION_DOWN` 被键盘消费，
     后续 MOVE 固定发给它，父容器 `KeyguardSecurityContainer` 拿不到这些点，无法判定 fling。
2. `SideGestureDetector#onMotionEventImpl(MotionEvent)` **before/afterHook**（放行系统侧滑）：
   开启且 `mKeyguardStateController` 判定 bouncer 显示时，before 存下 `mSysUiFlags` 并临时改写为
   `(flags & ~(1<<6)) | (1<<17)`，after 恢复。原理：`mAllowGesture` 判定链为
   `!mDisabledForQuickstep && mIsBackGestureAllowed && !z14 && z15`，其中
   `z14 = (mSysUiFlags & 64) != 0`（64 = SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING），命中时日志输出
   "back gesture disabled by sysui flags" —— **这就是系统屏蔽侧滑的确切位置**；
   置 bit17（131072 = SYSUI_STATE_ALLOW_GESTURE_IGNORING_BAR_VISIBILITY）让
   `shouldRespondToGesture()` 恒为真（否则 nav bar 隐藏时手势不响应）。
   手势走完后由 BackAnimation 派发到 bouncer 已注册的 OnBackInvokedCallback → `onBackPressed()`。
3. `KeyguardMessageAreaController#setOplusBouncerMessage(int, String, boolean)` **beforeHook**：开启且
   `args[1]` 文本含"上滑"且含"指纹" → 替换为常量 `BOUNCER_SWIPE_BACK_HINT = "下滑返回指纹解锁"`。
   （`displayDefaultSecurityMessage` 经此显示 `getString(getInitialMessageResId())` 结果。）

**辅助**：`isPrimaryBouncerShowing(Object ksc)` 先 `callMethod(ksc,"isPrimaryBouncerShowing")` 兜底读
`mPrimaryBouncerShowing` 字段；`dismissBouncer()` 校验 `isBouncerShowing()` 后调 `onBackPressed()`；
`isKeyboardSwipeStartAllowed(view, ev)` 见下。

### ⚠️ 关键：两种锁屏键盘是**不同的类**（导致首版下滑完全失效）

| 界面 | View 类 | 持有关系 |
| --- | --- | --- |
| PIN / 数字密码 | `com.coui.appcompat.lockview.COUINumericKeyboard`（extends View） | `OplusKeyguardPinBaseInputView#onFinishInflate` → `findViewById(getKeyboardWidgetId())` 得到 `NumericKeyboardWidget`（extends `COUINumericKeyboard`） |
| 字母密码 | `com.oplus.securitykeyboardui.SecurityKeyboardView` | 仅由 `AlphabetKeyboardWidget`（extends `COUIKeyboardView`）内部持有 |

`SecurityKeyboardView` **只用于字母密码界面**；PIN 界面根本不加载它。首版只 hook 了它，所以在 PIN 界面
下滑全程无效果 —— hook 不是"没拦截成功"，而是**压根没被调用**。

`COUINumericKeyboard` 关键成员（4 行 x 3 列网格，索引 = row*3+col）：

- `callback(int i)`：`0..8 -> onClickNumber(i+1)`（数字 1-9）；`10 -> onClickNumber(0)`（数字 0）；
  `9 -> onClickLeft`（0 左侧键）；`11 -> onClickRight`（0 右侧 / 删除键）。
- `checkForNewHit(float x, float y)`：私有，返回 `Cell`，`getRow()`/`getColumn()` 取行列；未命中返回 null。
  `getVirtualViewIdForHit` 用它做无障碍命中，并对空侧键返回 -1。
- `isEmptyStyle(SideStyle)`：`mDrawable == null && TextUtils.isEmpty(mText)` 或 `mAlpha == 0.0f` → 该侧键
  不可见（正是"0 两侧的不可见按键"）。
- `onTouchEvent` **反编译失败**（只有 smali，`Method not decompiled`），但方法本身存在于 dex，
  `(MotionEvent)boolean` 签名可正常 hook；内部拆分为 `handleActionDown/Move/Up(float, float, int)`。

### 键盘落点判定（关键，首版踩坑）

`SecurityKeyboardView#getKeyIndices(int x, int y, int[] nearby)`（435 行）末尾：

```621:624:SecurityKeyboardView.java
if (!isSecurityNumericKeyboard() || i > this.mSpecialKeyWidth || i2 > (this.mSpecialKeyHeight + this.mVerticalCorrection) - this.mLineWidth) {
    return i22;
}
return -1;
```

**只有落点落在 `x <= mSpecialKeyWidth` 的左侧特殊符号竖列内才返回 -1**；0 两侧的按钮、删除键、按键
空隙都返回**有效索引**。所以"返回 -1 才允许下滑"会把 0 两侧全部排除（用户实测 0 两侧滑不动）。

正确做法：取 `mKeys[idx].codes[0]`（`mKeys` 为 `SecurityKeyboard.Key[]`，`codes` 为 `int[]`），
**只排除数字键 '0'-'9'**，其余（特殊符号、删除键 -5、空隙）均允许下滑。

### 验证结果

### ⚠️ 阈值必须自适应（第三、四版下滑无反应的根因）

"0 两侧按钮"与删除键位于键盘**最底行**，紧贴屏幕底部，从那里往下到屏幕边缘通常只剩几十 px。

- 第 2 版用屏高 12%（约 290px）：滑不到。
- 第 3 版用固定 48dp（约 144px）：**仍然滑不到** —— 手指滑出屏幕也到不了。

手指成为某 View 的触摸目标后，滑出 View 边界仍会持续收到 MOVE（可超出 View），但**超出屏幕就收不到**，
所以可用滑动距离 = 起始点到屏幕底部的距离。

正确做法 `bouncerSwipeThresholdPx(View v, float startY)`：

```java
int[] loc = new int[2];
v.getLocationOnScreen(loc);
float toScreenBottom = dm.heightPixels - (loc[1] + startY);
return Math.min(48.0f * dm.density, toScreenBottom * 0.6f);
```

取可用距离的 60%（上限 48dp），保证任何位置起手都滑得到；起手越低阈值越小。

### ⚠️ 项目 log() 是空实现（无任何日志输出）

`XposedInit#log(String)`（170 行）在 **HEAD 提交里函数体就是空的**，所有 `log("HOOK OK/FAIL ...")`
全部静默，排查时拿不到任何信号。需要临时诊断请用 `XposedInit#dbg(String)`（内部是 `Log.e(TAG, msg)`，
符合"只允许 Log.e"的规则），定位后移除。

### ⚠️ 改 SystemUI hook 后必须重载才生效（曾白折腾两轮）

改完 `SystemUiHooks` 只 `adb install -r` **不会生效**，必须重载 SystemUI：
`adb shell su -c 'pkill -f com.android.systemui'`（或 LSPosed 关闭再打开模块）。

2026-08-29 曾连续两轮误判为"代码逻辑错误"（改落点判定、改阈值），实际是**用户忘记重载**，
装了新 APK 但跑的还是旧代码。以后遇到"改动无效"先确认是否已重载，再怀疑逻辑。

同理，功能"突然好了"而期间只重装未改逻辑时，优先考虑上次没重载。

### 已核对无误（不要再重复排查）

- `COUINumericKeyboard#onTouchEvent` 签名 `(Landroid/view/MotionEvent;)Z`，PUBLIC，定义在 classes2.dex。
   enabled 时 DOWN/MOVE/UP **全部 return 1**（`|0026: return v3` 仅 `!isEnabled()` 时返回 0），
   所以 View 会认领手势、MOVE 能收到，hook 点正确。
- `NumericKeyboardWidget`（final）只有 `Companion/<clinit>/<init>/onFinishInflate`，**未重写 onTouchEvent**，
   hook 基类有效（别再怀疑是不是该 hook 子类）。
- `checkForNewHit` 签名 `(FF)Lcom/coui/appcompat/lockview/COUINumericKeyboard$Cell;`，PRIVATE。
- `StatusBarKeyguardViewManager` 定义在 classes2.dex，`isBouncerShowing()`(645)、`onBackPressed()`(707)
   均存在；`onBackPressed()` 在 `isFullyShowing()` 后无论走 z 分支还是 else 都会收起。
- `OplusKeyguardPinBaseInputViewController#onViewAttached` 设的 OnTouchListener **对所有 action 都返回
   false**（仅在 DOWN 调 `falsingCollector.avoidGesture()`），不会吞掉事件，不影响 onTouchEvent。
- 不要 hook `dispatchTouchEvent` 替代：`COUINumericKeyboard` 未声明它，会解析到 `View.dispatchTouchEvent`
   并把进程内**所有 View** 都 hook 上。

### 验证

`adb install -r` 后重载 `com.android.systemui`（`pkill -f com.android.systemui`，勿重启设备）。
锁屏密码界面开启时：提示变"下滑返回指纹解锁"（首版此项已确认生效）；键盘非数字键区下滑应返回锁屏；
侧滑应返回锁屏。关闭时全部恢复系统原生。

**排查入口**：若侧滑仍无效，用 `adb shell dumpsys` 取 `SideGestureDetector.dump()` 输出核对
`mAllowGesture` / `mExcludeRegion`（该类 `dump` 会打印这两项）；若键盘下滑无反应，先确认
`getKeyIndices` 是否被调用、返回值是否 -1（下滑起点必须在非数字键区）。

### 19 节重大修正：真正的"密码界面背景遮罩"是沉浸式渐变层（2026-08-29）

首版 `hookKeyguardNoBouncerScrim` 只 hook 了 `ScrimState$3/$4` 的 `mBehindAlpha/mFrontAlpha`
（暗色 scrim），用户实测"没有生效"。用 `adb exec-out uiautomator dump /dev/tty` 抓当前密码界面核对，
遮罩节点是 `oplus_kgd_immersed_mask`（`com.oplus.systemui.keyguard.gradientmask.view.GradientBlurImageView`），
这是 ColorOS 的 **沉浸式渐变遮罩**，由 `OplusKeyguardGradientMaskController` 控制的两个
`OplusKeyguardGradientMaskView`（capsuleMaskView 底部通知 + immersedMaskView 顶部渐变）渲染。
用户看到的就是 `immersedMaskView`。

**教训：本地 base.apk 与设备不一致**（`md5` 本地 `6aab…` vs 设备 `c44b…`）。执行 `cp /tmp/sysui_device.apk base.apk`
+ `sh backward.sh com.android.systemui` 重建参考后（src-java 刷新到 02:53，枚举序号已变），
确认当前版本 ScrimState 枚举顺序：`KEYGUARD=2, BOUNCER=3, BOUNCER_SCRIMMED=4, UNLOCKED=9`
（与之前假设一致，所以暗色 scrim hook 序号没错，是对象错了）。**设备 OTA 后务必重新 pull + backward.sh。**

**最终实现（单一开关 `keyguard_no_bouncer_scrim_enabled` 同时去两层）**：
1. 暗色 scrim：`ScrimState$3/$4#prepare` afterHook 把 `mBehindAlpha/mFrontAlpha` 归零（保留）。
2. 渐变遮罩：`OplusKeyguardGradientMaskController#setVisibility(int)`（private final）beforeHook，
   当 `sBouncerScrimActive`（由 $3/$4 prepare 置 true、$2 KEYGUARD/$9 UNLOCKED prepare 置 false）时
   `setResult(null)` 并只把 `immersedMaskView.setVisibility(GONE)`，`capsuleMaskView` 维持原样。
   仅 bouncer 显示时隐藏 immersed 渐变层 —— 锁屏（KEYGUARD）下不拦截，胶囊遮罩与时钟渐变正常。

字段/方法名均来自重建后的设备 APK（jadx 字段名即 dex 真名）：
`OplusKeyguardGradientMaskController.immersedMaskView`（src-java line 62），
`private final void setVisibility(int i)`（line 367）。两个 mask view 的可见性只在这一处被改。

### 19 节【作废】第 1 次误判：WallpaperBlurDrawable（已证伪，勿用）

曾认定是 `WallpaperBlurDrawable#draw` 叠加 `overColor`，数值"吻合"（128×52/255≈26）。
**证伪**：该类在本机**根本没被实例化**。`refreshBehindDrawable()` 走哪条分支由
`ScrimUtil.isLowGaussianLevel(context)` 决定，而设备属性 `persist.sys.oplus.anim_level = 1`，
`isLowGaussianLevel()` 要求 `ANIM_LEVEL >= 3` → 返回 **false** → 走 **AutoBlurDrawable** 分支。
=> hook `WallpaperBlurDrawable#draw` 永远不命中。教训：数值"吻合"可能是巧合，必须先验证代码路径可达。

### 19 节【作废】第 2 次误判：改 MixColor 的 final 字段（已证伪）

改 `getPanelPlatformMixConfig()` 返回的 `BlurMixSingle.mixColor` / `MixColor.topLayerColor` 无效。
**证伪**（dexdump 核对）：
`BlurMixSingle.mixColor` = **PRIVATE FINAL**(0x0012)；
`MixColor.mode/topLayerColor/bottomLayerColor` = **PUBLIC FINAL**(0x0011)。
对 final 字段反射写入会被 ART 内联，读取仍用旧值。

### 19 节最终结论（第 3 版，设备属性 + dexdump 双重确认）

用户的判断正确：**不是遮罩，是模糊壁纸被加了一个最低亮度**。前几轮全部找错对象。

**真正的机制（源码链路 + 数值精确吻合）**：
```
WallpaperBlurManager#setOverColor(Bitmap):
    overColor = 壁纸亮度 > 38.25d
        ? R.color.oplus_qs_panel_bg_light_color   (#b3404040)
        : R.color.oplus_qs_panel_bg_dark_color    (#b3808080, RGB=128)
  本机壁纸纯黑 -> 亮度 0 -> 取 dark 分支 RGB=128

WallpaperBlurDrawable#draw(Canvas):
    bitmapDrawable.draw(canvas);                       // 先画模糊壁纸(纯黑)
    if (isOverlayEnabled && drawableColor != 0)
        canvas.drawColor(Color.argb(currentAlpha * 0.3, R, G, B));   // 再叠一层纯色提亮
```
数值验证：实测反推 currentAlpha≈172 → alpha=172*0.3≈52 → **128 × 52/255 = 26.1 ≈ 实测 (26,26,26)**。
这就是那个"最低亮度参数"（`overColor` 由 `BitmapExtKt.getBitmapBrightness()` 判断得出）。

**已排除（勿再排查）**：
- 三块 scrim 的 alpha/tint：归零后背景**仍是 26**，证明与 scrim 无关。
- immersedMaskView / capsuleMaskView：隐藏后仍是 26。
- `ScrimControllerExImp#getPanelPlatformMixConfig` 的 BOUNCER MixColor
  （`BOUNCER_MIX_COLOR = MixColor(5, 0x99262626, 0x66A6A6A6)`）：**改了无效**，
  它只影响 scrim 的平台混色，不是 bouncer 背景来源。
- `oplus_qs_panel_bg_dark_color` 的 alpha 0xb3 在 draw() 中**被忽略**，
  draw() 用的是 `currentAlpha * 0.3`，不要按 0xb3 计算。

**真正的来源（本机路径）**：
```
ScrimControllerExImp#refreshBehindDrawable():
  if (!isWallpaperBlurDisable() && ScrimUtil.isLowGaussianLevel(ctx)) -> WallpaperBlurDrawable
  else -> AutoBlurDrawable     ← 本机走这条(anim_level=1)
AutoBlurDrawable 分支(line 1315):
  new BlurConfig(panelBlurRadius, 0, null, true, getPanelPlatformMixConfig(), ...)
bouncer 时 getPanelPlatformMixConfig() -> NotifiAndQsPlatformBlurExKt.panelBouncerMixConfig(z)
  = new BlurMixConfig.BlurMixSingle(BOUNCER_MIX_COLOR)
  BOUNCER_MIX_COLOR = new MixColor(5 /*LUMINOSITY*/, #99262626, #66A6A6A6)
LUMINOSITY 把亮度归一化到 top 层 RGB(0x26=38) => 给模糊壁纸加了"最低亮度"，纯黑壁纸也被抬亮。
```
**正确实现**：hook `panelBouncerMixConfig(boolean)` 的 **afterHook，整体替换返回值**
（不写任何 final 字段），见 20 节。

**方法论教训（本项目最重要）**：
1. `log()` 曾为空实现 → 所有 HOOK OK/FAIL 与异常静默丢失，导致多轮盲改。已恢复 `Log.e`。
   **任何改动前先确认 log() 有效**。
2. 判断走哪条代码路径，用 **`adb shell getprop` 读设备属性** 比读源码更快更准
   （如 `persist.sys.oplus.anim_level`）。
3. 改对象字段前，**先 dexdump 核对字段是否 final**（access 0x0011/0x0012 末尾带 FINAL）。
   final 字段反射写入会被 ART 内联失效 → 应改为整体替换对象返回值。
4. 数值"吻合"不等于定位正确，必须先证明该代码路径**可达**。

**实现**：见 20 节「自定义密码界面背景亮度」。

只隐藏 `immersedMaskView` 仍残留背景色。**必须用像素实测定位，不要继续靠类名猜。**

**定位手法（可复用，成本极低）**：
1. 取壁纸真值：`adb exec-out su -c 'cat /data/system/users/0/wallpaper' > wp.png` + PIL 统计。
   本机壁纸是**纯黑**，3278880 个像素全为 (0,0,0)。
2. 抓当前屏：`adb exec-out screencap -p > s.png`，PIL 取多点 + `getcolors()` 直方图。
3. 同一时刻 `uiautomator dump` 配对，确认抓的是哪个界面（曾误抓到模块 App 的 ComposeView，
   必须 dump + 截图配对，否则结论全错）。

**实测结论**：
- 锁屏：纯黑 (0,0,0) —— 无残留。
- bouncer：均匀 (26,26,26)，占 85% 像素 = **恰好 10% 白叠在黑壁纸上**（0x1A=26）。
  次色 (49,49,49) 占 13%（键盘按键背景）。行扫描 y≈1800 / y≈2150 有白色数字 = 确认是键盘界面。
- 三块 scrim：`dumpsys` 显示 `viewAlpha=0.0 alpha=0.0 tint=0x0`，**已排除**。
- bouncer 上唯一全屏可见 view = `oplus_kgd_capsule_mask` [0,0][1216,2640]；锁屏上它没有全屏。

**结论**：`capsuleMaskView` 名为"胶囊"（锁屏上是底部通知胶囊），但在 bouncer 上会被撑成全屏并可见，
是残留背景色真源。`immersedMaskView` 隐藏已生效（bouncer dump 里已不存在）。
→ 新增 `forceBouncerMasksGone(controller)`，对 `immersedMaskView` + `capsuleMaskView` **一起** GONE，
三处隐藏点（updateMaskViewState afterHook / setVisibility afterHook / BOUNCER prepare）统一调用它。

**教训**：`capsuleMaskView` / `immersedMaskView` 的命名与实际形态/尺寸**严重不符**，
判断某 view 是不是遮罩，要看**实测 bounds + 像素**，不能看名字。

第二次修正（BOUNCER prepare 主动 GONE）仍不生效。源码核对发现
`OplusKeyguardGradientMaskController#updateMaskViewState(String, boolean)` 内部有**两条**改可见性的路径：
```java
if (renderingMaskState.isDefaultKeyguard) {
    setVisibility(0);                       // 走私有 setVisibility(int) —— 被 hook
} else {
    capsuleMaskView.setVisibility(8);
    immersedMaskView.setVisibility(0);      // 直接改 view, 完全绕过私有 setVisibility
}
```
非默认锁屏主题时走 else 分支，把已 GONE 的 immersedMaskView 重新置 VISIBLE，所以只拦 setVisibility 抓不住。

**可靠实现（三处协同，见 hookImmersedMaskView）**：
1. `onViewAttached()`（无参，ViewController 初始化即调用）afterHook 缓存
   `sMaskController` / `sImmersedMaskView` —— 不依赖 setVisibility 是否被调用过，
   否则 BOUNCER prepare 里可能拿不到引用。
2. `updateMaskViewState(String, boolean)` afterHook【主手段】：所有 mask 可见性变更都源自它，
   结束后若 `sBouncerScrimActive` 则统一强制 immersedMaskView GONE（覆盖两条分支）。
3. 私有 `setVisibility(int)` afterHook 兜底 + `ScrimState$3/$4#prepare` 主动 GONE；
   退出 bouncer（$2/$9 prepare）用 `callMethod(controller,"setVisibility",sLastMaskVisibility)` 还原。

**已排除的非嫌疑项（勿重复排查）**：
- ScrimController 三块 scrim 均已归零且确实生效：`ScrimView.setViewAlpha()` 会同步
  `mDrawable.setAlpha((int)(alpha*255))`，故 alpha=0 时连 AutoBlurDrawable/WallpaperBlurDrawable 也不显示。
- `ScrimControllerExImp.applyStateOverride()` 对 BOUNCER 不改 mBehindAlpha（只处理 UNLOCKED/DREAMING
  与 KEYGUARD/SHADE_LOCKED/PULSING）；`setExtraScrimState()` 在 keyguard（statusBarState!=0）直接 return。
- `KeyguardBouncerScrimDecorator.decorate()` 返回 null，无独立 bouncer scrim view。
- `OplusKgdMaskInteractor` 是全景 AOD 壁纸遮罩（KEYGUARD 时动画到 0），与 bouncer 背景无关。
- `StaticBlurManager`（getPwdPanelScrimBlurManager / "entered-Bouncer"）只是给 scrim 的
  AutoBlurDrawable 提供模糊壁纸 bitmap，同样受 view alpha 约束。

拦截 `setVisibility(int)` 仍不生效：锁屏 → bouncer 时 keyguard 仍显示，updateMaskViewState
**未必再次调用 setVisibility**（immersed 在锁屏已是 VISIBLE，alpha 由 spring 控制），因此 hooked 的
before 分支从不命中，遮罩一直挂着。

**最终可靠实现（三者协同）**：
1. `ScrimState$3/$4#prepare` afterHook 进入 bouncer 时，**主动** `immersedMaskView.setVisibility(GONE)`
   （主手段，不依赖 setVisibility 被调用）；退出 bouncer（$2 KEYGUARD/$9 UNLOCKED prepare）时
   通过 `XposedHelpers.callMethod(controller, "setVisibility", sLastMaskVisibility)` 还原immersed 原本可见性
   （缓存 `sMaskController`/`sImmersedMaskView`/`sLastMaskVisibility`，全部 setVisibility 命中即刷新）。
2. 保留 `setVisibility(int)` 钩子作为兜底：bouncer 显示中（afterHook）把 immersed 强制 GONE，
   **原实现照常执行**（capsuleMaskView 不变），不再 `setResult(null)` 跳过。
3. 暗色 scrim（ScrimState$3/$4 mBehindAlpha/mFrontAlpha 归零）不变。
这样无论 updateMaskViewState 是否重跑，bouncer 上 immersed 渐变层都被隐藏，退出即还原。

### 20. 自定义密码界面背景亮度（替换原「取消密码界面背景遮罩」，2026-08-29）

设置项：`keyguard_bouncer_brightness_enabled`（开关）+ `keyguard_bouncer_brightness`（滑条 0-5，默认 0）。
UI：`SwitchItem(..., sliderKey=..., sliderMax=5, sliderDefault=0)`，放在「锁屏」分组。

**实现（最终版）**：hook `com.oplusos.systemui.common.util.NotifiAndQsPlatformBlurExKt#panelBouncerMixConfig(boolean)`，
在 **afterHook 中整体替换返回值**，不写任何 final 字段：

```java
Object mixColor = getObjectField(cfg, "mixColor");
int mode = getIntField(mixColor, "mode");            // 只处理 LUMINOSITY(5)
int top  = getIntField(mixColor, "topLayerColor");   // #99262626
int bottom = getIntField(mixColor, "bottomLayerColor");
float k = brightness / 5f;                            // 0..1
int gray = Math.round(Color.red(top) * k);
int newTop = Color.argb(Color.alpha(top), gray, gray, gray);
Object newMixColor = newInstance(mixColor.getClass(), mode, newTop, bottom);  // MixColor(III)V
Object newCfg = newInstance(cfg.getClass(), newMixColor);                     // BlurMixSingle(MixColor)V
callMethod(newCfg, "setAlphaWithBlurAmount", !((Boolean) param.args[0]));     // 还原系统设置
param.setResult(newCfg);
```

- `brightness=5` → k=1 → 系统默认（实测 26）；`brightness=0` → k=0 → 纯黑。
- 该方法**只在 bouncer 时**被 `getPanelPlatformMixConfig()` 调用，天然只作用于密码界面，
  无需维护 bouncer 状态（`sBouncerScrimActive` 相关代码已删除）。
- 按比例缩放 RGB 而非硬编码目标亮度，对深浅色壁纸与不同 alpha 均成立。

**构造函数签名（dexdump 确认）**：`MixColor.<init>(III)V`、`BlurMixSingle.<init>(MixColor)V`。

```java
float k = brightness / 5f;                       // 0..1
scaled = Color.argb(alpha(dc), red(dc)*k, green(dc)*k, blue(dc)*k);
setIntField(thisObject, "drawableColor", scaled);   // before draw
// after draw 还原原值
```

- `brightness=5` → k=1 → 系统原样（默认效果 26）。
- `brightness=0` → k=0 → 叠加纯黑 → 背景纯黑，最低亮度被去掉。

**为什么按比例缩放而不是硬编码目标亮度**：`draw()` 用 `argb(currentAlpha*0.3, R,G,B)`，
最终亮度 = RGB × currentAlpha×0.3/255，而 `currentAlpha` 随 scrim 动画变化。
按比例缩放消去了 currentAlpha，**不依赖动画状态，也适配深浅色壁纸**（light 分支 #b3404040 同样成立）。

前置条件校验（与 draw() 内条件一致，避免影响不参与叠加的实例）：
`isOverlayEnabled == true` 且 `drawableColor != 0`。

### 21. SIM PIN 输入框 / 确定按钮边框去除（并入「取消解锁界面控件光效」）

`COUILockScreenPwdInputView#onDraw` 与 `COUILockScreenPwdInputLayout#dispatchDraw`（均 `mScenesMode==1` 才画）
用 `mBorderPaint` 以 `mBorderLineColor` 描边绘制边框。开启 `keyguard_no_light_effect_enabled` 时
`setBorderMode(...)` 把 `mBorderLineColor` 置 `0`（全透明）并 `mBorderPaint=null` 强制下次重绘按最新色重建；
关闭时还原系统原色（首次见到时缓存 `sBorderColorInput`/`sBorderColorLayout`），门控即时生效。
`COUILockScreenPwdInputLayout.mBorderLineColor` 为 **final int**，反射 `setIntField` 运行时仍生效
（值来自资源非编译期常量，绘制时按字段值读取）。边框去除与键盘键边框（drawInnerBorder 整段 skip）同源。

### 22. 密码支持滑动输入（2026-08-29）

设置项：`keyguard_slide_input_enabled`（开关，锁屏分组，标题「密码支持滑动输入」）。

**系统原生键盘机制**（`com.coui.appcompat.lockview.COUINumericKeyboard`，签名均经 dexdump 核对）：
```
checkForNewHit(FF): getRowHit/getColumnHit -> 按 **矩形** 命中(cell 宽高 + mAdditionalPressableArea)
handleActionDown(FFI): cell.pointerId = pid, 显示按下态, **不输入**
handleActionUp(FFI):   仅当命中 cell.pointerId == pid 才 callback(输入), 再取消按下态
handleActionMove(FFI): 一旦移出原 cell 就 handleActionCancel(pid) 取消按下态
callback(i): 0-8 -> 数字 1-9, 10 -> 数字 0, 9 -> 左键, 11 -> 右键
```
=> 原生是"按下与抬起落在同一键才输入"，滑动经过其它键不输入。

**实现**：接管 `handleActionDown/Move/Up` 三个 **(FFI)V** 私有方法（MotionEvent 重载内部会调用 FFI 版本，
已核实 line 1095-1108），改为「进入即输入」：
- DOWN: 圆形命中数字键 -> pointerId=pid + 显示按下态 + 震动 + **立即输入**
- MOVE: 命中键变化 -> 取消旧键 + 新键显示按下态 + 震动 + 立即输入；移出所有数字键 -> 取消按下态
- UP:   仅取消按下态（进入时已输入，抬起不重复输入）

命中区域：以按键中心为圆心、`mNumberBackgroundRadius * cell.mButtonScale * 2/3` 为半径的圆
（该半径与 `refreshNumberPaths` / `drawInnerShadowLayer` 绘制按键背景圆一致）。
只认数字键（idx 0-8 / 10），排除左键 9 / 右键 11，避免滑动误触删除/确定。

按下态兼容 `mPressEffectStyle`：0 -> `initShowAnimator`/`initFadeAnimator`（传统圆圈），
1 -> `executeLightEffectAnimator(cell, boolean)`（光效）。同时开启「取消解锁界面控件光效」时，
绘制已替换为按 `cell.pointerId != -1` 判定的纯色底，只要维护好 pointerId 即显示按下态。

**路径可达性已验证**（按 19 节教训，必须先验证）：
- `NumericKeyboardWidget extends COUINumericKeyboard`（`com/oplus/keyguard/security/widget/`），
  且**未重写** onTouchEvent / dispatchTouchEvent / onInterceptTouchEvent / handleAction*
  => hook 父类私有方法完全可达。
- 无障碍触摸探索模式（`mAccessibilityManagerService.isTouchExplorationEnabled()`）下放行原生逻辑。

**注意**：`param.setResult(null)` 可跳过 void 方法的原生实现，是接管这类私有 void 方法的正确手段。

### 23. SystemUiHooks 按功能类别拆分（2026-08-29）

原 `SystemUiHooks.java` 2096 行 -> 拆为 5 个文件（均 `com.rikumi.colorosmod.hooks` 包，public final class）：

| 文件 | 行数 | 职责 |
|---|---|---|
| `SystemUiHooks.java` | 67 | **仅调度入口** `hookSystemUi()`，转发到下列各类 |
| `QsHooks.java` | 303 | 控制中心：运营商名、顶栏间距、背景压暗、磁贴名称省略 |
| `NotificationHooks.java` | 313 | 通知：分组副标题、通知内边距 |
| `StatusBarHooks.java` | 67 | 状态栏：流体云出现时保留电量百分比 |
| `KeyguardHooks.java` | 550 | 锁屏/解锁界面：关机免校验、通知下移、侧滑/下滑返回 |
| `PasswordInputHooks.java` | 934 | 密码输入：控件光效、背景亮度、滑动输入、纯色背景绘制 |

**共享依赖**：各文件都 `import static com.rikumi.colorosmod.XposedInit.*;`
（`log` / `readBool` / `readInt` / 各 KEY_* 常量均来自这里），**无继承关系**，故拆分无需改调用。
各文件使用统一的完整 import 块（未使用的 import 不影响编译）。

**注意事项**：
- `PasswordInputHooks.transparentBitmap()` 里原为 `synchronized (SystemUiHooks.class)`，
  拆分时已改为 `PasswordInputHooks.class`（双重检查锁的监视器对象）。
- `CLS_NUMERIC_KEYBOARD` 常量同时被"控件光效"与"滑动输入"使用，两者都在
  `PasswordInputHooks` 内，未产生跨文件依赖。
- 拆分采用脚本按行区间提取（先打印各段首尾 3 行验证边界未切断方法），
  再补 package/import/class 声明；最后用方法名集合对比校验（65 个方法，拆分前后一致）。

**改功能时去哪个文件**：改通知看 `NotificationHooks`，改锁屏手势/返回看 `KeyguardHooks`，
改密码键盘（光效/滑动输入/背景）看 `PasswordInputHooks`。

### 24. 状态栏歌词（2026-08-29，最终方案：直接读 MediaSession 歌词接口）

设置项：`statusbar_lyric_enabled`（开关），位于分类「状态栏与通知中心」（原「通知中心」，本次改名）。
实现文件：`hooks/StatusBarLyricHooks.java`。

**最终方案（已实测验证）**：不伪装机型、不反射 Notification 私有字段、不在音乐软件进程注入。
直接在 SystemUI 侧读 ColorOS 自己的歌词接口：

```
MediaSessionManager#getActiveSessions(null)   // 需要 MEDIA_CONTENT_CONTROL(SystemUI 已有)
  → 找 STATE_PLAYING 的会话
  → controller.getMetadata()
  → metadata.getString("lyricInfo")           // ColorOS 歌词接口(JSON)
  → JSONObject.optString("lyric")             // 歌词原文
  → parseLyric(raw)                            // 解析 LRC/JSON 时序
  → findLineAt(estimatePosition(playbackState)) // 按播放进度取当前行
  → 显示到状态栏 TextView(挂 PhoneStatusBarView)
```

**ColorOS 歌词接口（已 dexdump 核对 SystemUI 的 OplusMediaDataManagerExImpl#loadLyricInBg 字节码）**：
```java
String lyricInfo = metadata.getString("lyricInfo");     // JSON 字符串
JSONObject j = new JSONObject(lyricInfo);
j.optString("songName"); j.optString("artist");
j.optString("lyric");       // 歌词原文(不是当前行, 是完整歌词)
j.optString("songId");
```

**歌词格式**（LyricParser 逐行解析，逻辑与 SystemUI 一致）：
- LRC：`[mm:ss.xx]歌词` → 时间 = 分*60000 + 秒*1000 + 厘秒*10
- JSON：`{"t":毫秒, "c":[{"tx":"文本"},...]}` → c 数组内 tx 拼接成整句

**控制中心 vs 全屏显示歌名/歌词的区别**：
不是数据源不同，而是 `displayPolicy` 位掩码不同：
`isEnabled(i) = lines非空 && (displayPolicy & i) != 0`。
控制中心 `MediaControlPanelExtImp` 用 `isEnabled(16)`，全屏 `OplusMediaViewPagerAdapter` 用 `isEnabled(32)`。
部分软件只给了 32(全屏) 而没给 16(控制中心)，于是控制中心仍是歌名。
本功能直接用原始歌词数据，不受 displayPolicy 限制。

**锁屏音乐卡片也走这套接口**（`com.oplus.systemui.plugins:SystemUIPlugin` 的 `f7/p5.java` 绑定的
`title_text_a`/`content_text_a`，实测在 `music_viewpager_container` 中显示歌词）。
歌词数据由 SystemUI 主包 `OplusMediaLyricData` 处理后传给插件，而非插件自己解析——
这印证了 `lyricInfo` 是系统唯一歌词通路。

**重要实现细节**：
- 播放位置必须**外推**：`PlaybackState.getPosition()` 是上次更新快照，要
  `pos + (elapsedRealtime - lastPositionUpdateTime) × speed`，否则歌词滞后。
- 轮询 250ms 足够跟手且开销极小；用 `sController` 缓存当前会话，切换播放器无需重建轮询。
- 歌词视图挂在 `PhoneStatusBarView#onAttachedToWindow`（本类自有声明，不扩散到所有 View），
  复用状态栏时钟的字号/字色/字体以跟随主题；显示歌词时隐藏 `clock` + `notification_icon_area`。
- 开关必须在运行时（而非注入时）判断，否则初始化时关闭后开启永不生效。

**已废弃的弯路（勿再走）**：
- 伪装 `Build.BRAND`：实测无效且副作用大（影响 app 机型分支）。
- 反射 `Notification.FLAG_ALWAYS_SHOW_TICKER`：那是 Flyme 私有字段，ColorOS 没有，
  反射会抛 NoSuchFieldException，无论怎么 hook 都救不回来。
- 逐个逆向音乐软件找"判定逻辑"：判定方式不统一且混淆类名随版本变化，维护成本极高。

**协议（关键结论：是通知，不是广播）**。官方 open.flyme.cn/docs?id=239 需登录，抓不到正文；
从搜索引擎 snippet + 掘金逆向文（moriafly）确认：
```
音乐软件每换一句歌词 -> 用同一通知 id 再发一条通知
  Notification.tickerText = 歌词(由 setTicker(lyrics) 写入)
  flags |= 0x1000000 (FLAG_ALWAYS_SHOW_TICKER)    // Flyme 私有, "ticker 常驻状态栏"
  flags |= 0x2000000 (FLAG_ONLY_UPDATE_TICKER)    // 可选, 只更新 ticker
  extras: ticker_icon(int) / ticker_icon_switch(boolean)
  另需 FLAG_NO_CLEAR 常驻(软件侧负责)
```
**系统侧已无 ticker 链路**：全 SystemUI 源码无 tickerText 消费点（仅 NotificationCompat.Builder 有
setTicker），但 `Notification.tickerText` 字段仍由 setTicker() 填充，直接读该字段即可。

**本机 hook 点（dexdump 核对，classes2.dex）**：
- `com.android.systemui.statusbar.phone.PhoneStatusBarView#onAttachedToWindow()` —— 本类自有声明
  （非继承 View），hook 不会扩散到所有 View。`PhoneStatusBarView extends FrameLayout`，
  挂歌词 TextView 用 FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, gravity=START|CENTER_VERTICAL)。
  时钟 id=`clock`（`com.oplus.systemui.statusbar.widget.StatClock`），通知图标区 id=`notification_icon_area`，
  均用 `getResources().getIdentifier(name,"id","com.android.systemui")` 取。
- `com.android.systemui.statusbar.notification.collection.NotifCollection$1`
  - `onNotificationPosted(StatusBarNotification, RankingMap)V`（新增与更新都走这里）
  - `onNotificationRemoved(SBN, RankingMap)V` 与 `(SBN, RankingMap, int)V`
  （该类是 `NotificationListener.NotificationHandler` 的实现，内部 `Assert.isMainThread()`）

**实现要点**：
- 歌词 TextView 复用状态栏时钟的 textSize / textColors / typeface -> 自动跟随深浅色主题。
  单行 + `ellipsize=MARQUEE` + `marqueeRepeatLimit(-1)`；换文本时要 `setSelected(false)` 再 `true`
  才会重新滚动。
- 显示歌词时隐藏 `clock` + `notification_icon_area`（记录原可见性，结束时还原），避免重叠。
- TextView 必须 `setClickable(false)`/`setFocusable(false)`，否则会吃掉状态栏下拉手势。
- 同一条通知后续若不再带 flag（软件恢复成普通媒体通知），要顺手清掉歌词：用 `sLyricKey` 比对。

**音乐软件侧【已证伪并改正】**：原以为要改 `Build.BRAND`，**错**。网易云的判定方式见
StatusBarLyricHooks 注释：反射读 `android.app.Notification` 的 Flyme 私有字段
`FLAG_ALWAYS_SHOW_TICKER`。逆向出的完整链路（jadx 反编译 classes3/4/21）：

```
pj0.b (music_base_lyric_release, 混淆名)  [classes21]
  static { if (!n3.U()) { FLAG_ALWAYS_SHOW_TICKER=0; ...; return; }
           FLAG_ALWAYS_SHOW_TICKER = dp.c(Notification.class, null, "FLAG_ALWAYS_SHOW_TICKER"); ... }
  public static boolean a() { return FLAG_ALWAYS_SHOW_TICKER > 0; }

pj0.a (StatusBarLyricSettingManager)      [classes21]
  supportStatusBarLyric = pj0.b.a()
  d() = sp("status_bar_lyric_setting").getBoolean("status_bar_lyric_setting_key", false)
  c(z) 保存 + 广播 "status_bar_lyric_setting_change"

h82.c (StatusBarLyricController)          [classes4]
  line64: private boolean supportStatusBarLyricFlag = pj0.b.a();   // 决定是否初始化
  m(Context){ if(supportStatusBarLyricFlag){ switchOpenCache = pj0.a.a.d(); 注册广播 } }

mu1/k.java (设置项, 字段由服务端下发)      [classes3]
  "meizuStatusBarLyricSwitchDisplay" -> pj0.a.a.b()   // 是否显示
  "meizuStatusBarLyricSwitch"        -> pj0.a.a.d()   // 开关值
```

**⚠️ 关键修正（2026-08-29）：官方接入是"两道关"，缺一不可**

网易云 `pj0.b` 的 static 块：
```java
static {
    if (!n3.U()) { FLAG_ALWAYS_SHOW_TICKER = 0; FLAG_ONLY_UPDATE_TICKER = 0; return; }  // ① 机型关
    FLAG_ALWAYS_SHOW_TICKER = dp.c(Notification.class, null, "FLAG_ALWAYS_SHOW_TICKER"); // ② 字段关
    FLAG_ONLY_UPDATE_TICKER = dp.c(Notification.class, null, "FLAG_ONLY_UPDATE_TICKER");
}
public static boolean a() { return FLAG_ALWAYS_SHOW_TICKER > 0; }
```
而 **`n3.U()` 就是品牌检查**（`com.netease.cloudmusic.utils.n3`，定义在 **classes5.dex**，
jadx 反编译单 dex 时会把它误放到 `view/n3.java`，路径不可信；用 dexdump 定位最可靠）：
```java
public static boolean U() {
    if (S()) return true;
    String str = Build.BRAND;
    if (TextUtils.isEmpty(str)) return false;
    return str.toLowerCase().equals("meizu");      // ← 就是 Build.BRAND
}
```
=> **只做字段 spoofing 不做机型伪装时，第 ① 关提前 return，`dp.c()` 根本不会被调用，
字段 spoofing 完全没机会触发**（曾因此误判"字段 spoofing 无效"）。两者必须同时启用。

**最终实现（两道关都过）**：
1. `hookFlymeNotificationFlags` —— hook `Class#getDeclaredField` / `Class#getField`，
   在 afterHooked 且 `param.getThrowable() != null` 且 `param.thisObject == Notification.class`
   且字段名匹配时，`param.setResult(FlymeFlags.class.getDeclaredField(name))`。
   仅在原生抛 NoSuchFieldException 时介入，真实 Flyme 上不干扰。
   值必须是真实 0x1000000（软件发通知时要用它置 flags）。
2. `hookMeizuBrand` —— **延迟到 `Application#attach(Context)`** 再把
   `Build.BRAND="meizu"` / `MANUFACTURER="Meizu"`。

**⚠️ 为什么必须延迟到 Application.attach（血泪教训）**：
`readBool` 的 ContentProvider 通道依赖 `currentApplication()`，而 `handleLoadPackage` 阶段它为
null → 回退 XSharedPreferences → 往往读到 false → **开关判断把伪装挡掉，代码从未执行**。
`Application.attach` 是应用启动后最早的可注入点，且早于 pj0.b 等类初始化。

两者都对**所有非 android 包**生效（用户勾选谁就对谁生效），排除 system_server（反射极高频）。

**副作用提示**：改 BRAND 会影响 app 机型分支（推送通道、支付渠道等）。只改 BRAND/MANUFACTURER，
不动 MODEL/FINGERPRINT/DISPLAY；仅对用户在 LSPosed 勾选的包生效。

```java
public static final class FlymeFlags {              // 字段名必须与 Flyme 私有字段完全同名
    public static int FLAG_ALWAYS_SHOW_TICKER   = 0x1000000;
    public static int FLAG_ONLY_UPDATE_TICKER   = 0x2000000;
}
// hook Class#getDeclaredField / Class#getField(String)
// afterHooked: 仅当 param.getThrowable() != null(系统无该字段)
//              && param.thisObject == Notification.class
//              && name 匹配  ->  param.setResult(FlymeFlags.class.getDeclaredField(name))
//              (setResult 会清除 throwable; 真 Flyme 上字段存在则不介入)
```
原理：Field 与声明类绑定，应用拿到后 `field.getInt(null)` 读到的就是 FlymeFlags 的值。
**必须在 afterHooked 判断 throwable 后置入**，避免在真实 Flyme 上干扰原生行为。
**值必须是真实的 0x1000000**，因为软件发送通知时要用它置 flags，为 0 则 SystemUI 端收不到标志位。

调用位置：`XposedInit#handleLoadPackage` 末尾，对**所有**包调用（仅排除 `android`/system_server：
音乐软件是独立进程，且 system_server 反射极高频不宜拦截）+ 已知音乐包额外做品牌伪装兜底。
开关必须在 beforeHooked/afterHooked **运行时**判断（handleLoadPackage 阶段 ContentProvider 不可用，
只能回退 XSharedPreferences，往往读不到）。

**逆向技巧（踩坑记录）**：
- macOS 的 grep 是 BSD grep，**不支持 `\|` 做 alternation**！必须用 `grep -E`，
  否则会静默漏掉全部匹配（曾因此误判"网易云没有状态栏歌词代码"）。
- **jadx 反编译单个 dex 时包路径不可信**（会把 `utils/n3` 输出成 `view/n3.java`）。
  定位"某个类定义在哪个 dex"要用脚本解析 dex 的 class_defs（见下），或用 dexdump。
- **dex header 偏移**: string_ids_off=0x3c, type_ids_off=0x44,
  **class_defs_size=0x60, class_defs_off=0x64**（我记成 0x78/0x7c 导致全部解析失败）。
  用 python struct 遍历 class_def_item(32B, 首字段 class_idx -> type_ids -> string) 可精确列出
  每个 dex 定义了哪些类，比 dexdump 快得多，且能区分"定义"与"引用"。
- **ColorOS 的 logcat 不可信**：`notification_subtitle applied` 之类的高频日志会迅速填满
  环形缓冲区，把 HOOK OK 等关键日志挤掉，造成"模块没运行"的假象。
  重载 SystemUI 后必须在 **3 秒内**抓取才看得到注册日志。
- jadx 不能直接反编译单个 .dex，要 `zip` 包一层再喂给它（`zip -q /tmp/x.zip classesN.dex`）。
- 208MB/24 个 dex 的 app，全量反编译太慢；先用 `grep -ac` 在 dex 里定位目标类在哪个 dex，
  再只反编译那一个 dex，然后用 `grep -rl "特征字符串"` 定位混淆后的类名。
- 混淆类名（`pj0.b`）随版本变化，已用 try-catch 包裹；升级失效时按上述方法重新定位。

**未验证部分**：其余 5 个音乐软件（QQ/酷狗/酷我/咪咕/汽水）是否只靠 `Build.BRAND` 判断，
尚未逆向确认（无反编译产物）。它们目前仍走 `hookMeizuBrand` 兜底。

## 桌面长按背景亮度（Feature 24，2026-08-29 实现）

设置项：桌面 → `desktop_popup_bg_brightness_enabled`「自定义桌面长按背景亮度」，
滑条 `desktop_popup_bg_brightness`（0-10，默认 0，无单位）。0 = 去掉系统抬的最低亮度，
10 = 系统默认效果。常量在 `XposedInit`，hook 在 `LauncherHooks#hookPopupBgBrightness`。

**逆向结论（com.android.launcher，classes2.dex）**：

- 长按图标菜单 `OplusPopupContainerWithArrow` → `PopupBlurView.Companion#getPopBlurView`
  → `PopupBlurHelper#loadPopupBlurBg(Launcher, PopupBlurView)`（blur 不可用才走
  `loadPopupNoBlurBg`，那条路只设 dragLayerDrawable，不设壁纸）。
- `loadPopupBlurBg` → `LauncherWallpaperManager#getBlurredWallpaper(launcher, 0.7f, callback)`
  → 回调里 `new BitmapDrawable(res, result.copy(ARGB_8888, true))` + `setBounds(0,0,wPx,hPx)`
  → `PopupBlurView#setWallpaperDrawable(Drawable)`（public final，`(Landroid/graphics/drawable/Drawable;)V`，
  PopupBlurView extends FrameLayout）。`dispatchDraw` 先画 mWallpaperDrawable 再画 mDragLayerDrawable。
- 背景 = 模糊壁纸以 **blendMode=ONLY_MASK** 混入 `popup_blur_blend_color`（**#4d1c2634**）：
  `col.rgb = mix(col.rgb, blendColor.rgb, blendColor.a)`（a = 0x4d/255 ≈ 0.302）。
  纯黑壁纸因此被抬到约 (8.5, 11.5, 15.7)，即一层去不掉的"最低亮度"。

**为什么不能直接改 blurBitmap 的 blendColor 参数（关键坑）**：
`WallpaperBlur#getBlurredWallpaper` 在 `BlurCache` 命中时**直接把缓存 bitmap 交给回调**，
完全不走 `PopupBlurHelper#blurBitmap`，所以改参数只在首次长按生效；后续长按走缓存。
唯一汇合点是 `setWallpaperDrawable`，hook 它并替换 args[0]。

**算法**：混入式 `out = wp*(1-a) + blendRGB*a`，缩放 blendRGB 到 k 倍等价于
`out_k = out + (k-1)*a*blendRGB`，即对最终 bitmap 叠加常量偏移，用 `ColorMatrix` 的
offset 项 + `ColorMatrixColorFilter` 一次 drawBitmap 完成（k=0 为负偏移，自动 clamp 到 0）。
不原地改像素：新建 bitmap + 新 BitmapDrawable（复制 bounds），避免污染壁纸模糊缓存。

## 25. 修复「重启后模块失效，重启作用域才恢复」（2026-08-29）

**现象**：重启后所有功能回到默认，App 里点「重启作用域」（杀 systemui/launcher）后全部恢复。

**根因（两层）**：

1. **设置存在 CE 存储，开机锁定态读不到。** 模块 prefs 在 `/data/user/0/com.rikumi.colorosmod/shared_prefs/`（CE），
   开机到首次解锁前（Direct Boot）该目录未挂载；而 SystemUI 正是在锁定态启动的。此时
   `SettingsProvider` 被拉起也只读到空 prefs，而部分 hook **只在初始化时读一次**
   （如手势条高度在 `NavigationBar#getBarLayoutParams` 里读一次即固化到窗口 LayoutParams），
   解锁后不会重读，于是"重启后失效，重启作用域（解锁后重建）才恢复"。
2. **读取通道是一次性同步 IPC，失败即回落默认值。** 早期模块 App 尚未被拉起时查询失败，
   返回值被当作最终值。

**修法（三处）**：

1. **设置改存设备加密（DE）存储**：`MainActivity.settingsPrefs()` 用
   `createDeviceProtectedStorageContext().getSharedPreferences("settings", MODE_PRIVATE)`；
   `SettingsProvider.prefs()` 同样走 DE 存储。旧 CE 设置由 `migrateLegacyPrefs()` 在
   DE 为空时搬一次（bool/int/long/float/String/Set 全类型）。
   已删除 `makePrefsWorldReadable()`（CE 文件不再是数据源，chmod 无意义）。
2. **`AndroidManifest.xml`**：`<application>` 与 `<provider>` 均加 `android:directBootAware="true"`，
   否则锁定态 AMS 不允许拉起模块进程，DE 存储也读不到。
3. **`XposedInit` 后台设置预热**：注入时 `startSettingsLoader()` 起守护线程，
   `content://com.rikumi.colorosmod.settings/__all__`（新增，返回两列 `k`/`v` 的全量设置）
   ——首成功前每 500ms 重试，成功后每 5s 刷新（与原 `CACHE_TTL_MS` 同口径，改设置仍 5s 内生效）。
   `readBool/readInt` 改为优先读内存快照（**零 IPC**，不再阻塞主线程），
   首次读取若预热未完成最多等 `FIRST_LOAD_WAIT_MS = 5000`（每进程只等一次，超时后不再等），
   仍取不到才回退旧的同步查询 `queryProviderSync()`（保留 TTL + 粘性缓存）。
   `XSharedPreferences` 回退已删除（SELinux 下从不可读，且它读的是已废弃的 CE 文件）。

**验证**：`./gradlew :app:assembleDebug` 通过。装完后需用户在 LSPosed/App 里重载作用域；
最终验证必须整机重启（红线：禁止 Agent 私自重启，需用户自己操作并确认）。
可用 `adb shell content query --uri content://com.rikumi.colorosmod.settings/__all__` 确认全量接口。

## 26. 通知左滑直接清除（Feature 26，2026-08-30 实现）

设置项：状态栏与通知中心 → `notification_swipe_to_dismiss_enabled`「通知左滑直接清除」，默认关。
常量在 `XposedInit`，hook 在 `NotificationHooks#hookNotificationSwipeToDismiss`（由
`SystemUiHooks` 无条件注入，运行时按开关门控）。

**逆向结论（com.android.systemui，classes3.dex，均已 dexdump 核对签名）**：

国内版左滑通知 → 露出「设置」(gear) +「删除」(lottie) 两个侧边按钮，滑到头才清除；
海外版(exp) 一个按钮都不生成、抬手过阈值即清除。区分点在两处 `FeatureOption.isExpRegion()`：

1. `com.oplus.systemui.notification.row.NotificationMenuRowExtImpl`
   `#createMenuViewsExt(Z, NotificationMenuRowPlugin, ArrayList, Context, Z, Z)V`（PUBLIC）。
   源码 265 行 `if (!FeatureOption.isExpRegion())` 内 `addFirst` settingsItem 与 deleteItem；
   exp 分支在末尾（332 行）执行 `arrayList.clear()` → **一个菜单项都不留**。
   菜单项清空后 `NotificationMenuRow#getSpaceForMenu()` 返回 0，
   `getDismissThreshold()` 的 `0.1*width + spaceForMenu` 随之降低（exp 滑动距离更小、更易清除）。
2. `com.oplus.systemui.notification.row.swipe.OplusSwipeHelperExImpl`
   `#shouldNotShowMenuExt(MotionEvent, View, float, NotificationMenuRowPlugin)Z`（PUBLIC）。
   源码 182 行 `(FeatureOption.isExpRegion() && view instanceof ExpandableNotificationRow)`。
   返回 true 时 `NotificationSwipeHelper#handleMenuRowSwipe`（240 行）跳过「吸附露出菜单」分支，
   改走 `dismiss(view, velocity)` / `snapClosed`。

**实现要点（两处 hook 缺一不可）**：

- `createMenuViewsExt` afterHook：开关开时 `((ArrayList) param.args[2]).clear()`，
  并把 `settingsItem` / `deleteItem` 两个字段反射置 null。置 null 很关键：否则
  `onDismissRow()`（`OplusSwipeHelperExImpl#startMenuDismissAnimation` 触发）会拿这两个
  **未挂载**的 menuView 跑移除动画。用 afterHook 而非 beforeHook，是为了保留方法前半段
  的既有副作用（`arrayList.remove(getLongpressMenuItem(context))`、metaBallController 创建），
  与 exp 分支口径一致。
- `shouldNotShowMenuExt` beforeHook：开关开且 `view` 是 `ExpandableNotificationRow` 时
  `param.setResult(Boolean.TRUE)`。按类名逐级向上匹配超类，不用 `findClass().isInstance()`，
  避免 classLoader 差异导致判否。OplusCustomRow 保持原生（与 exp 同口径）。

**明确的反面结论**：不要 hook `FeatureOption.isExpRegion()` 本身——全 SystemUI 有 150+ 处
调用（下拉状态栏、QS、键值配置、主题等），影响面不可控；只需上面两处即可覆盖左滑路径。

**验证**：`./gradlew :app:assembleDebug` 通过；只需重载 SystemUI（`pkill -f com.android.systemui`）。
设备验证：下拉通知中心，左滑一条通知应**看不到**任何侧边按钮，抬手即清除；
关闭开关后恢复「左滑露出设置/删除按钮」的原生行为。

## 27. 通知下滑展开（Feature 27，2026-08-30 实现）

设置项：状态栏与通知中心 → `notification_pull_expand_enabled`「通知下滑展开」，默认关。
常量在 `XposedInit`，hook 在 `NotificationHooks#hookNotificationPullExpand`（由 `SystemUiHooks`
无条件注入，运行时按开关门控）。

**逆向结论（com.android.systemui，均已 dexdump 核对签名）**：ColorOS 国内版关掉了 AOSP 的
`ExpandHelper`（单指下拉通知展开），两处 `FeatureOption.isExpRegion()` 判断：

1. `NotificationStackScrollLayout` 构造末尾（源码 3080-3085 行）
   `if (FeatureOption.isExpRegion() || getView() == null) return; view.setExpandingEnabled(false);`
   —— 国内版构造时即 `mExpandHelper.setEnabled(false)`；exp 分支什么都不做，保留构造里的
   `expandHelper.mEnabled = true`。
2. `com.oplus.systemui.statusbar.notification.stack.NotificationStackScrollLayoutExtImpl`
   `#setExpandingEnabled(Z)V`（PUBLIC，classes4.dex）—— `if (!FeatureOption.isExpRegion() ||
   getView() == null) return;` 直接短路；唯一调用方 `NotificationStackScrollLayoutController`
   （237 行）传 `!onKeyguard()`，国内版永远传不进去。

**实现要点（两处 hook 缺一不可）**：
- NSSL 构造 afterHook（签名 `(Context, AttributeSet)V`，classes2.dex）：开关开时
  `callMethod(thisObject, "setExpandingEnabled", TRUE)`，对齐 exp 的初值。
- ExtImpl#setExpandingEnabled beforeHook：反射取 `getView()`，为 null 则原样返回，
  否则 `callMethod(view, "setExpandingEnabled", args[0])` 并 `setResult(null)` —— 等价于接口
  `NotificationStackScrollLayoutExt` 的 default 实现（Ext.java 152-155 行），保留「锁屏上不展开」
  的原生语义。

**验证**：`./gradlew :app:assembleDebug` 通过；只需重载 SystemUI。

## 28. 「恢复 ColorOS 15 通知布局」可行性排查（2026-08-30，**结论：旧代码不存在，未实现**）

**设备**：Android 16（API 36）+ ColorOS 16.1.0（PKT110_16.0.10.500(CN01)）。

**排查结论：ColorOS 15（Android 15）的旧通知布局代码在设备上已不存在**。证据链（全部实测）：

1. **通知正文 RemoteViews 由 framework 生成，SystemUI 换不了模板。**
   `NotificationContentInflater$$ExternalSyntheticLambda3` 里逐条调用
   `builder.createContentView() / createBigContentView() / createHeadsUpContentView()`
   （framework 的 `android.app.Notification$Builder`）。SystemUI 侧只有两个旁路干预点：
   - `OplusNotificationRowUiImpl#onExpandedViewCreated`：只能返回 null 或原样 RemoteViews；
   - `NotifLayoutInflaterFactory`：只能按**单个 View 类名**替换控件。
2. **framework-res.apk（设备上 pull）里模板只有一套，且已全面换成 TopLineView。**
   - `res/layout/notification_template_material_base.xml`：`NotificationTopLineView`（line=79），
     **没有** `NotificationHeaderView`。
   - `notification_template_header.xml`：`NotificationHeaderView`(line=17) 内部**嵌套**
     `NotificationTopLineView`(line=58) —— 旧 header 只剩一个壳，且只用于自定义通知。
   - 全部 `notification_template_material_*` 模板一律只用 TopLineView。
   - `notification_template_material_big_base.xml` / `_big_text.xml` 只是 `<include>` base。
   - 资源 id：`notification_template_header=0x010900df`、`_material_base=0x010900e0`
     （`aapt2 dump resources framework-res.apk` 实测）。
3. **SystemUI 里没有第二套模板。** res/layout 下无 oplus 的通知正文模板；`NotifLayoutInflaterFactory`
   只有一条实现 `OplusNotificationDateTimeViewFactory`（仅把 `DateTimeView` 换成 Oplus 版）。
4. **误判排除（这两条看起来像、实际不是）：**
   - `isOldWrapper` / `EXTRA_VERSION_CODES_RESULT_NULL`（`OplusNotificationRowUiImpl` L34 →
     `OplusNotificationEntryExImpl.isOldWrapper` L59）：只针对**老 targetSdk app** 的小范围兼容，
     作用是 `OplusNotificationHeaderViewWrapperExImp#setUpdateExpandability` 里控制 `mLabelIcon`
     可见性 + `NotificationContentViewExtImp#isExpandable` 里一条日志，**不切换布局**。
   - `com.oplus.systemui.notification.row.oplusgroup.*`（`OplusNotificationGroupTemplateWrapper`
     等，含 `GroupIconManager`）：只是**分组折叠通知**的 Oplus 自定义样式，不是通用通知布局。
   - `FeatureOption.isExpRegion` / `FlavorOne/TwoFeatureOption.isFlavorXxxDeviceExp()`：
     与通知布局无关（Flavor 类里也只有这两个 exp 方法）。
5. **Android 版本差异才是根因**：C15 = Android 15 模板（NotificationHeaderView，app 小图标 +
   app 名在顶部、标题内容在下，宽松）；C16 = Android 16 模板（统一 NotificationTopLineView）。
   旧模板随 Android 大版本被整体替换，ColorOS 16 未备份旧副本。

**若要自行实现，唯一可行路径**：在 SystemUI 作用域往 `NotifLayoutInflaterFactory` 的
factory 集合里注入一个自定义 `NotifRemoteViewsFactory`，把 `android.view.NotificationTopLineView`
换成模块自带的自定义 View（继承 ViewGroup，复刻 C15 的 header）。因 RemoteViews 的更新是按
**子 View id** 下发的，只要自定义 View 保留相同的子 View id（`app_name_text`、`icon`、`time` 等），
framework 后续的 `setText`/`setImageViewBitmap` 仍会命中。代价：需复刻布局、展开/折叠按钮、
`NotificationHeaderViewWrapper` 依赖的变换动画（`mTransformationHelper` 按 id 登记
TransformState）与圆角（`Roundable`/`RoundableState`）适配，工作量大且易与 Oplus 的
`OplusNotificationHeaderViewWrapperExImp` 冲突。此方案**尚未实现**，等用户确认。

**↓ 后续找到了更简单得多的落点，已改为方案 B 实现，见下节。**

## 29. ColorOS 15 通知布局（**2026-08-30 实现后因效果不佳，已完全移除**）

**最终状态：功能已删除，代码不复存在。** 以下逆向结论保留备查，不要再重复排查。

### 逆向结论（有效，framework 的 classes4.dex，`jadx --single-class` 实测）

`android.view.NotificationTopLineView extends ViewGroup`（**不是** NotificationHeaderView 的子类）：
- `onFinishInflate()`：`mAppName = findViewById(R.id.app_name_text)`、
  **`mTitle = findViewById(R.id.title)`**、`mHeaderText = R.id.header_text_secondary`、
  `mSecondaryHeaderText = R.id.headers`、`mFeedbackIcon = R.id.feedbackAudible`。
  → **标题被收进了 TopLineView 内部**。
- `onMeasure(w,h)`：遍历子 View **水平累加** `totalWidth`，高度取 `maxChildHeight`；
  溢出时用内部 `OverflowAdjuster` 依次压缩 appName → headerText → title。
- `onLayout(...)`：所有子 View 沿 `start += 宽度` 排成**同一行**，baseline 对齐。

**这就是 C15→C16 布局差异的全部来源**：C16 把「应用名/时间/feedback 图标」与「标题」挤在同一行；
C15 里标题是独立一行、位于顶部信息行下方。

补充实测：
- `notification_template_material_base.xml` 里 TopLineView 的子 View 顺序 =
  `<include layout/notification_top_line_views>`（app_name/time/header_text/…）**后接**
  `TextView @0x01020016 = R.id.title`。所以 title 恒为最后一个子 View。
- `android.view.NotificationHeaderView` **仍在 framework 中**，持 `mTopLineView` 字段，
  只为自定义/老 targetSdk 通知保留外壳（`notification_template_header.xml` 内嵌套 TopLineView）。
- SystemUI 侧 `NotificationHeaderViewWrapper.mNotificationTopLine` 只被赋值（359 行）、
  **没有被读取** → 改 TopLineView 高度不会影响 SystemUI 的变换动画。
- 注意：`setMeasuredDimension` 是 protected，hook 代码里必须反射调。

### 曾尝试的实现（方案 B，**已删除，不要再恢复**）

不改视图树，只改 TopLineView 自身的测量与布局，四步：
1. `onMeasure` before：把 `mTitle` 临时 `setVisibility(GONE)`，让 super 以为只有顶部信息行
   —— 这样 super 的 `OverflowAdjuster` 会把整行宽度全部分给 app_name/header 文本，
   而不是被标题挤占（这是临时 GONE 而非只改 onLayout 的原因）。
2. `onMeasure` after：恢复 `mTitle` 为 VISIBLE，按整行宽度单独 `measure` 标题，
   再**反射** `setMeasuredDimension(width, topLineHeight + gapPx + titleHeight)`（protected，
   不能直接调）；`topLineHeight` 存进 additionalInstanceField。
3. `onLayout` before：把 `param.args[4]`（b）改成 `t + topLineHeight`，否则 super 会把整行
   居中在含标题的总高度里、顶部行整体下移。
4. `onLayout` after：用**自身内部坐标**（与 super 一致，不带 t）把 `mTitle` 摆到第二行。

间距 `CLASSIC_TITLE_GAP_DP = 4`。

**效果不佳，用户要求移除（2026-08-30）。** 已清理：
`XposedInit#KEY_NOTIFICATION_CLASSIC_LAYOUT_ENABLED`、`NotificationHooks#hookNotificationClassicLayout`
+ `getTopLineTitle` + `CLASSIC_TITLE_GAP_DP`/`EXTRA_*` 常量 + `android.view.ViewGroup` import、
`SystemUiHooks` 注入调用、`MainActivity.kt` 设置项。已全仓 grep 确认零残留，`installDebug` 通过。

**教训**：只把 title 换到第二行，并没有真正还原 C15 的观感 —— C15 与 C16 的差异不止「title 换行」，
还涉及 app 图标位置、行高、字重/字号的整套 spacing 体系（framework 模板资源随 Android 大版本
整体替换）。若日后再做，应从**替换 TopLineView 为自定义 View** 或**整体覆盖 spacing dimen**
入手，而不是只改单个 View 的测量。

## 恢复原生通知图标（2026-08-30 实现，同日因效果不好已移除）

**功能已删除，仅保留其"通知图标区显示模式"这一基础设施给状态栏歌词用。**
效果不好的原因未定位（三处 hook 都验证过链路正确，但视觉结果用户不满意），勿凭日志重试原方案。

**根因（曾设备验证，结论仍有效）**：替换发生在 **system_server**，不在 SystemUI。
`com.android.server.notification.OplusNotificationFixHelper#fixSmallIcon(Notification,String,String,boolean)`
（由 `NotificationManagerService#enqueueNotification` 里的 `fixNotificationForOplus` 调用）对
「非 Android 包 && 非系统/平台签名 && 非商业通知 && 不在 Oplus 白名单」的通知，执行
`notification.setSmallIcon(Icon.createWithResource(pkg, appInfo.icon))`，同时把原图标存进
`extras["oplus_small_icon"]`、置 `extras["oplus_smallicon_use_app_icon"]=true`。
设备实测 Telegram 通知即带这两个 extra。

**状态栏把小图标再裁成圆角方块的地方**（`StatusBarIconView#updateDrawable` 第 706-712 行）：
`updateStatusBarIconGrayScale(...)` 之后 `updateStatusBarIconDrawable(...)` 返回 true 时，
`RoundRectDrawableUtil#getTargetRoundRectDrawable` 把图标画进
`R.dimen.notification_round_rect_icon_zoom_size` 的**正方形**并按 `status_bar_notification_icon_radius`
比例裁圆角 —— 方形应用图标正好填满（= iOS 式小方块），而原生单色 smallIcon 会被拉伸裁切，
所以这一步必须一起停掉。

**实现（三处 hook + 一套显示模式下发，缺一不可）**：
1. `com.android.systemui.statusbar.notification.icon.IconManager#getIconDescriptor(NotificationEntry, boolean)`
   after：`XposedHelpers.setObjectField(result, "icon", 原生的 oplus_small_icon)`。
   这是状态栏/货架/息屏/chip 四个 `StatusBarIconView` 共用的描述符来源（`createIcons` 与
   `updateIcons` 都调它），改这一处即全部生效。
2. `com.oplus.systemui.statusbar.phone.StatusBarIconControllerExImpl#updateStatusBarIconDrawable(
   Drawable, StatusBarIconView, StatusBarNotification)` before：开启时 `setResult(false)`，
   让 `updateDrawable` 走原生 `setImageDrawable(icon)`。第三参为 null 表示普通系统状态图标，不动。
   类内静态 `sNotificationRoundIconSize/sIconRadiusFraction` 只在本类用，短路后无人读，安全。
3. 同类的 `updateStatusBarIconGrayScale(...)` after：原实现是
   `!useAppIconForSmallIcon(n) && isGrayscaleIcon(d)`，被 extra 短路成恒 false，原生单色图标
   不会被着色（浅色背景会看不见）。这里在 after 里用
   `OplusContrastColorUtil.getInstance(appCtx).isGrayscaleIcon(drawable)` 重判并
   `setIsIconColorable(...)`。
   刻意**不去** hook `OplusNotificationSmallIconUtil#useAppIconForSmallIcon`：它同时被
   `OplusNotificationHeaderViewWrapperExImp`（通知行头图）与胶囊通知用到，只动状态栏这条链更稳。

**通知栏显示方式强制**：`Settings.Secure notification_prompt_mode`，
0=显示图标 / 1=显示数字 / 2=不显示（三处口径一致：设置 `StatusIconDialogItem#getNotificationFromSetting`、
SystemUI `NotificationIconAreaType` 枚举序、仓库
`OplusNotificationIconAreaRepository$notificationIconAreaType$1`）。
- 读：`com.oplusos.systemui.statusbar.util.StatusBarSettingsValueProxy$Companion#
  getNotificationPromptModeState(Context)` after 强制返回 0。
- 写：`OplusNotificationIconAreaRepository` 构造时抓实例，主线程 500ms 轮询比较
  `notificationShowMode`(MutableStateFlow) 的 `getValue()` 与目标值，不同才 `setValue`。
  因为改模块开关不会触发系统的设置观察者，必须轮询才能实时切换。
- 歌词优先：目标值 = `sLyricNumberMode ? 1 : (原生图标开 ? 0 : 读设置)`。
  **`StatusBarLyricHooks` 已不再自己 hook 仓库**，改为调 `NotificationHooks#setLyricNumberMode`；
  两处各写一份会互相拉扯（图标↔数字每 250ms 抖动）。
- 开关翻转时调 `IconManager#updateIcons(entry, false)` 重画全部通知（抓 IconManager 构造实例，
  从其 `notifCollection.getAllNotifs()` 取），否则已存在的图标要等下次更新才变。

**注意**：通知行里的头图走 `notification.getSmallIcon()`（不经过 StatusBarIcon 描述符），
所以当时开启后状态栏是原生图标、通知行仍是应用图标 —— 这是刻意保留的系统行为。

## 通知图标区显示模式（保留，状态栏歌词专用）

即使上面功能删了，这套下发机制仍在 `NotificationHooks`：
`hookNotificationIconAreaRepository` 抓 `OplusNotificationIconAreaRepository` 实例，
主线程 500ms 轮询比对 `notificationShowMode`(MutableStateFlow) 的 `getValue()` 与目标值，
不同才 `setValue`。目标值 = `sLyricNumberMode ? 1(显示数字) : 读 Settings 值`。
`StatusBarLyricHooks#setNotificationNumberMode` 调 `NotificationHooks#setLyricNumberMode`。
**必须只有一处写这个流**：曾两处各写一份，导致图标↔数字每 250ms 抖动。
轮询必要：歌词状态变化不触发系统的设置观察者。
儿童模式/专注模式下仓库仍输出 NOTIFICATION_NOT_SHOW，是系统行为，保留。

## 30. 悬浮小窗贴边挂机：锁屏再解锁后真实窗口重现（2026-08-30 修复）

**现象**：开启「悬浮小窗贴边挂机」后，小窗贴边挂机 → 锁屏 → 解锁，小窗应用的
**真实窗口**会重新出现在屏幕上（应只有边缘把手）。

### 根因

`ActivityRecord#setVisibility` 在锁屏/解锁的可见性重算中会**连带把 Task 的 surface show 出来**
（`getSyncTransaction().show(task)` + `Task.mLastSurfaceShowing = true`）。
贴边挂机只在 to-float 动画里 hide 过一次 surface，属于"一次性动作"，不是"不变式"，
所以解锁后系统重新 show，真实窗口就回来了。

### 修复（`SystemServerHooks#hookFloatWindowEdgeHangSystemServer`）

把"隐藏"从一次性动作改成**恒常不变式**：

1. `TaskExtImpl#moveTaskToBackForPanorama` beforeHook `setResult(null)` 时，把 taskId 记入
   `sHungTaskIds`（`Collections.synchronizedSet`）。
2. 新增 hook `com.android.server.wm.Task#prepareSurfaces()`：
   - **before**：若 `task.mTaskId` 在 `sHungTaskIds` 中，用
     `setAdditionalInstanceField(task, LAST_SHOWING, mLastSurfaceShowing)` 存下当前值
     （用 per-task 附加字段而非 ThreadLocal，避免嵌套 Task 互相覆盖）；
   - **after**：
     - 先无条件取出并 `removeAdditionalInstanceField`（保证任何提前 return 路径都不残留）；
     - 若该 taskId **已不在 `isInFloatingList`** 中（说明用户点把手还原成浮窗、或任务已被移除），
       从 `sHungTaskIds` 移除、把 `mLastSurfaceShowing` 置 **false** 后 return，交还系统；
       —— 置 false 是必需的：否则系统认为 surface 已是 shown，不再下发 show，窗口反倒不再显示；
     - 否则 `getSurfaceControl()` 有效时 `getSyncTransaction().hide(sc)`，每帧重新压住。
   - 检测到 before=false / after=true（系统重新 show 了）时打一条 `Log.e` 便于确认。

### 要点与坑

- 隐藏必须放在 `prepareSurfaces` **之后**：`prepareSurfaces` 内部自己会按
  `hasNoSurfaceShowing()` 等条件下发 hide/show，放 before 会被它覆盖。
- `Task#prepareSurfaces()` 无参，定义在 `services.jar` 的 `classes2.dex`（`WindowContainer`
  的 `prepareSurfaces()` 是 protected，`Task` 覆写为 public，hook `Task` 才不会扩散）。
- 与 §14 旧做法（afterHook `moveToFront`）的区别：旧做法会重新 show 出真实窗口，正是本 bug 的
  另一条触发路径；现在改为只跳过 `moveTaskToBack`，不再动前台状态。
- `sHungTaskIds` 只在 system_server 内存中，zygote 软重载后清空；此时已挂机的任务仍在
  floating list，点把手走 `exitFlexibleTask` 正常还原，无残留风险。

### 验证（用户实机确认通过）

贴边挂机 → 锁屏 → 解锁：只有边缘把手，**不再出现真实窗口**；点把手仍能正常还原成浮窗。
改动只在 `android` 作用域，需重载 zygote 才生效（必须先征求用户同意，且只能用
`setprop ctl.restart zygote`）。

## 31. 悬浮小窗贴边挂机：解锁后焦点落回小窗（2026-08-30 修复，**效果待验证**）

**现象**：贴边挂机 → 锁屏 → 解锁，焦点落到挂机小窗上；小窗 surface 已被隐藏，焦点在不可见
窗口上导致音量键等按键事件 dispatch 失败。

### 关键逆向结论（均已核对源码，勿再走老路）

1. **`FlexibleTaskController#setFocusTask(Task)` 不能用来转移焦点**（曾长期使用，实测无效）。
   它最终是 `ATMS#setFocusedTask(taskId, null)`（FlexibleTaskController.java:9318）；
   而 `setFocusedTask(int, ActivityRecord)`（ActivityTaskManagerService.java:1738）只在
   `r.moveFocusableActivityToTop("setFocusedTask")` 返回 true 时才走
   `resumeFocusedTasksTopActivities()` 真正转移焦点；`touchedActivity == null` 且移不动时
   **什么都不做**（1769 行 else 分支只处理 embedded task）。
   → 要么无效；一旦移动成功，后方任务被顶到最前并 resume，挂机任务被 pause，挂机直接失效。
2. **"只改焦点、不动任务栈"的正确做法**取自 AOSP 自身的 embedded 分支
   （ActivityTaskManagerService.java:1769-1772）：
   `DisplayContent#setFocusedApp(ar)` + `WindowManagerService#updateFocusedWindowLocked(0, true)`。
   `setFocusedApp`（DisplayContent.java:3228）内部会 `getInputMonitor().setFocusedAppLw(newFocus)`，
   input 侧焦点一并更新 —— 这正是音量键 dispatch 所需要的那一环。
3. **判定焦点在哪**：用 `DisplayContent.mFocusedApp`（DisplayContent.java:174）比对
   `getTask()`；`Task` 自身**没有**可靠的 `isFocused()`（勿再用它）。
4. **取 FlexibleTaskController 实例**：`getTaskUnderFlexible` 是它的 **private 实例方法**
   （FlexibleTaskController.java:12689，排除小窗与 windowingMode==2），必须先
   `FlexibleWindowManagerService.getInstance(null).getFlexibleTaskController()` 拿到实例再反射调用；
   直接对 Class 对象调用必抛 NoSuchMethodError（会被 catch 静默吞掉，表现为"无声失效"）。

### 实现（`SystemServerHooks`）

1. `focusTaskBehind(Object floatTask)`：`getTaskUnderFlexible` + 校验
   `isTopActivityFocusable() / isVisible()` → `getTopNonFinishingActivity()` →
   `DisplayContent#setFocusedApp` + `updateFocusedWindowLocked(0, true)`。
2. **不变式 hook** `DisplayContent#setFocusedApp(ActivityRecord)`（beforeHook）：若焦点正指向
   `sHungTaskIds` 中的任务、且它仍在 floating list，就把 `args[0]` 换成后方任务的 ActivityRecord；
   已脱离 floating list 则移除记录、交还系统。
   —— 与 §30 把 surface 隐藏改成不变式同理：**一次性纠正会被后续焦点计算覆盖**。
3. 保留 `FlexibleTaskController#notifyKeyguardStateChanged(boolean,boolean,int)` 的 afterHook：
   仅 `keyguardChanged==true && keyguardShowing==false`（解锁）时，延后 500ms / 1500ms
   各复查一次，兜住"焦点已停在小窗、之后不再有新的 setFocusedApp 调用"的情况。

### 状态

改动在 `android` 作用域，需重载 zygote 才生效。2026-08-30 用户选择延后自行重载，
**行为效果尚未验证**。验证方式：贴边挂机 → 锁屏 → 解锁 → 按音量键应正常响应；
并用只读命令确认焦点：`adb shell dumpsys window | grep -iE 'mCurrentFocus|mFocusedApp'`。

### 排查提示

若仍无效，先看 logcat 里是否有 `>>> edge_hang: redirect focus off hung task`（不变式命中）
与 `>>> edge_hang: refocus behind on unlock`（延后复查命中）；两条都没有则焦点根本没被
设到挂机任务上，问题在别处（注意 ColorOS 日志缓冲区刷新极快，重载后需尽快抓取）。
## 32. Couix 任意界面始终可回弹（2026-08-30 实现）

需求：内容不足一屏的界面（如「导航与手势」「应用小窗」子页面）拖动时毫无反馈，
要求任意界面都能上滑/下滑回弹。

根因（已从 Compose foundation 1.12 字节码确认）：`ScrollingLogic.shouldDispatchOverscroll`
= `canScrollForward || canScrollBackward || overscrollEffect.isInProgress`。
内容不可滚动时 **内置 overscroll 完全不派发**（`applyToScroll` / `applyToFling` 都被跳过），
所以不足一屏的列表拉不动；松手时的 fling 路径仍会走到 `dispatchPreFling`（else 分支直接
调用 `performFling`），所以父级 `NestedScrollConnection` 能拿到 pre/post scroll 与 pre fling。

实现（`Couix.kt`）：
- `Modifier.couixOverscroll(listState)` = `clipToBounds()` +
  `drawWithContent { translate(top = offsetPx) { this@drawWithContent.drawContent() } }` +
  `nestedScroll(connection)`，首页与子页面的 `LazyColumn` 都挂上（在 `.padding(padding)` 之后）。
  用 `drawWithContent` 而非 `graphicsLayer`：绘制阶段的状态读取确定被观察，且不给整个列表
  常驻一个合成层；`clipToBounds` 防止上滑时内容盖住标题栏。
- `onPostScroll`：`listState` 两个方向都不可滚动时，把手势增量转成整列位移
  （`couixOverscrollStep`）。**橡皮筋模型，不做 clamp**：
  `offset' = offset + delta * DRAG * (maxPx - |offset|) / maxPx`。
  连续形式 `d(offset)/d(手指) = DRAG * (1 - offset/maxPx)` 的解是
  `offset = maxPx * (1 - e^(-DRAG * 手指行程 / maxPx))` —— 起点斜率 DRAG（完全跟手），
  随行程指数趋近 `maxPx` 而**永远达不到**，所以行程常量可以取大（现为 180dp）也不会有
  "拖到底"的感觉。手指拖 180dp 时实际位移约 113dp、拖 360dp 时约 156dp。
  **关键：一旦用 `coerceIn` clamp 就等于设了硬停点，手指还在动内容却突然不动，
  这是用户明确否掉的手感**，不要再改回去。当前 `COUIX_OVERSCROLL_DRAG = 1f`（起始 1:1 跟手）。
  可滚动的界面直接让位给系统 overscroll，避免叠加。
- `onPreScroll`：已有位移时反向拖动 1:1 原路返回，只消费到归零为止，余数交还列表。

**符号约定（已从字节码确认，别再搞反）**：nested scroll 的 `available.y` 与**屏幕坐标同向**——
上滑为负、下滑为正。依据是 `ScrollableNode$drag$2$1`：直接触摸时 `dragDelta` 乘以 **1.0f**
（只有 indirect pointer 才乘 -1.0f）后交给 `scrollByWithOverscroll`，全程没有其它取反；
`Offset` 打包为低 32 位 = y（`Offset.getY-impl` 用 `land 0xFFFFFFFF`）。
因此 `offsetPx` 与 `available.y` 同号，直接 `step(offsetPx, available.y, maxPx)` 即可。
第一版写成 `-available.y`，实机表现为"上滑下弹、下滑上弹"，已修正。
- `onPreFling`：位移非零时吃掉全部速度并 `animate` 回 0（`DampingRatioNoBouncy` +
  `StiffnessMediumLow` 的 spring），新一次拖动会 cancel 掉回弹 job。

改动只在模块自己的 App（Compose UI），与 Xposed 作用域无关，重装 APK 即可，不需重启作用域。

## 33. 模块界面状态栏跟随深浅色（2026-08-30 实现）

现象：模块 App 的状态栏图标在日间模式下也是白色，看不清。

**根因（已从 appcompat 1.6.1 资源确认）**：AppCompat 的全部 `values-*`（含 `values-v23`、
`values-night-v8`）**都没有定义 `android:windowLightStatusBar`**。`Theme.AppCompat.DayNight.*`
只在 `values` 下指向 Light 变体、在 `values-night-v8` 下指向 Dark 变体，但没有这项属性，
因此它恒为 false，日间模式图标也是白的。指望 DayNight 主题自动切换是不成立的。

实现（`Couix.kt` 新增 `CouixStatusBar()`，在 `MainActivity` 的 `MiuixTheme { }` 内调用，
位于 `SettingsScreen()` 之前）：
- 背景取 `MiuixTheme.colorScheme.surface` —— **不是 background**，因为 miuix `Scaffold`
  的底色用的是 `Colors.getSurface()`（已从 `ScaffoldKt` 字节码确认），用 surface 才能与界面连成一片。
- 前景用 `surface.luminance() > 0.5f` 判定明暗，再设 `WindowInsetsControllerCompat#isAppearanceLightStatusBars`，
  不依赖主题模式枚举。
- `DisposableEffect(view, surface, lightIcons)`：主题切换/系统深色模式切换导致 `surface`
  变化时自动重设。

首帧前（启动画面期间）状态栏仍由系统按主题默认值绘制，但 `windowSplashScreenBackground`
已随日夜变化，实际无可见闪烁。这是模块 App 自身 UI，重装 APK 即可。
