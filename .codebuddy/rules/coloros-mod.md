---
description: coloros-mod 项目硬性规则（Xposed/LSPosed 模块开发与调试）。历史实现记录见 .codebuddy/memory/coloros-mod.md。
alwaysApply: true
enabled: true
---

# coloros-mod 硬性规则

## 1. 设备重启与进程重载

1. **禁止私自重启设备。**
   - 严禁执行 `adb reboot`、`reboot`、长按电源重启或任何会让设备重启的操作。
   - 未经用户明确同意，一次都不许重启。

2. **SystemUI/Launcher 重载：**
   - SystemUI 使用 `adb shell su -c 'pkill -f com.android.systemui'` 或 `killall`。
   - Launcher 使用 `adb shell su -c 'pkill -f com.android.launcher'`。
   - 不要使用 `am force-stop` 代替上述方式。
   - 也可在 LSPosed 中关闭再打开本模块。

3. **禁止擅自重载 `system_server`。**
   - 禁止使用 `pkill -f system_server` 或 `killall system_server`。
   - 如确需让 `android` 作用域的新 hook 生效，必须先询问用户并获得明确同意。
   - 获得同意后，只能使用项目已有的 `setprop ctl.restart zygote` 方式，不得使用其它 system_server 重载命令。

## 2. 设备操作权限

4. **默认只允许提取和读取设备数据。**
   - 允许 `adb pull`、`dumpsys`、`pm list packages`、`cmd package query-activities` 等只读命令。
   - 禁止 `adb push` 写入设备分区。
   - 禁止通过 `adb shell` 执行 `rm`、`mv`、`cp`、`chmod`、`pm clear`、`pm disable`、`pm hide` 等修改设备状态或文件系统的命令。
   - 禁止使用 `input`、`uiautomator`、辅助功能或其它自动化方式操作设备界面。
   - 截图或布局抓取只能针对用户当前正在使用的界面，不得切换界面或后台拉起应用。
   - 修改代码、构建 APK、部署 APK 在本机完成；需要设备验证时优先请求用户自行确认，或只执行用户明确授权的单次只读提取。

## 3. 代码分析与实现

5. **分析被注入 app 必须使用 `../android-app-mods` 工具链。**
   - 不得凭记忆猜测类名、方法名、字段名或签名。
   - 已有反编译产物优先使用；新 app 按 `pull`、`backward.sh`、JADX、`dexdump` 流程核对。

6. **任何开关不生效，先检查 prefs 权限和跨进程读取。**

7. **新增日志只允许使用 `Log.e`，通过项目的 `log()` 输出。**
   - 不得新增 `Log.d`、`Log.v`、`Log.i`、`Log.w`。
   - 不要仅凭日志判断注入或功能是否生效；最终依据是设备真实行为和只读布局数据。
   - 不要为了确认 hook 是否运行而反复添加诊断日志。

8. **不要擅自添加设置项描述文本。**
   - 未经用户要求，不得添加 `subtitle`、说明文字、营销文案或其它描述性文本。
   - 设置项只保留用户明确给出的标题和字段。

9. **尊重用户对视觉结果的判断。**
   - 用户明确指出尺寸或位置没有变化时，以该反馈为事实，直接检查实际渲染链路，不得用未经验证的推测反驳。

## 4. 修改与验证原则

10. 不要擅自重写或重构用户的大文件；优先做局部修改。
11. 改动后检查相关文件诊断并构建验证。
12. 设备验证优先使用真实行为、`dumpsys` 和布局数据；不依赖日志空窗判断。

历史实现过程、逆向结论、已废弃方案、部署记录和手势条高度功能的最终实现见：

`.codebuddy/memory/coloros-mod.md`
