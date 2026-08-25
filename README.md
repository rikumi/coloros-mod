# ColorOS Mod

一个针对 ColorOS（Oplus）的 LSPosed / Xposed 模块，作用域覆盖：

| 包名 | 用途 |
|------|------|
| `com.android.launcher` | 系统桌面 |
| `com.android.systemui` | 系统界面（控制中心 / 通知中心） |
| `com.oplus.safecenter` | 安全中心（隐藏应用） |
| `com.android.settings` | 设置（隐藏应用免验证兜底） |

模块提供一个 Jetpack Compose 设置界面（基于 Miuix KMP 魔改的 Couix UI），
顶部为「启用模块」总开关，下方按分组列出各功能。所有开关**默认开启**。

> 带滑条的项：滑条范围 `0` 表示系统默认（不改变），另一端为原硬编码值的两倍，中间（默认值）即此前的固定效果。

---

## 功能列表

### 桌面（`com.android.launcher`）

| 功能 | 说明 | 滑条 |
|------|------|------|
| 增加图标与名称间距 | 增大桌面图标与名称的垂直间距 | 0–8 dp，默认 4 dp |
| 减小页面与 Dock 间距 | 让分页圆点同时靠近页面与 Dock | 0–32 dp，默认 16 dp |
| 缩小图标长按菜单 | 整体缩小长按图标的弹出菜单 | 0–20%，默认 10% |
| 隐藏电话本图标 | 从桌面隐藏电话本图标 | — |
| 隐藏 Gboard 图标 | 从桌面隐藏 Gboard 图标 | — |
| 文件夹展开背景透明 | 展开文件夹时取消整屏模糊变灰 | — |

### 控制中心（`com.android.systemui`）

| 功能 | 说明 | 滑条 |
|------|------|------|
| 自定义控制中心背景亮度 | 压暗控制中心背景（保留壁纸模糊纹理） | 0–20，默认 10（0 全黑，20 系统默认） |
| 去除控制中心运营商显示 | 隐藏经典/合并面板顶部的运营商名称 | — |
| 隐藏控制中心顶部状态图标簇 | 隐藏展开后顶部的状态图标簇（Wi-Fi/信号/电池） | — |
| Wi-Fi / 蓝牙名称单行省略 | 磁贴次级名称过长时单行省略号结尾 | — |

### 通知中心（`com.android.systemui`）

| 功能 | 说明 | 滑条 |
|------|------|------|
| 缩小通知静默区域副标题 | 缩小分组标题（如「静默」）字号并上移/右移 | 0–16 sp，默认 8 sp |
| 非静默通知增加上下内边距 | 给非静默通知卡片增加上下留白 | 0–8 dp，默认 4 dp |

### 隐藏应用（桌面 + 安全中心 + 设置）

| 功能 | 说明 |
|------|------|
| 多任务显示隐藏应用 | 最近任务列表 / 手势概览照常显示被隐藏的应用 |
| 打开隐藏应用文件夹免验证 | 点击桌面隐藏应用图标 / 拨号盘输入隐藏号码后免密码/指纹打开 |
| 桌面双指张开打开隐藏应用 | 双指向外张开（pinch-out）直接打开隐藏应用文件夹 |
| 应用隐藏标题显示文件夹名 | 隐藏应用界面标题改为用户自定义的文件夹名 |

---

## 构建

需要 Android SDK 与 **JDK 21**（项目 `jvmToolchain(21)`，Gradle 亦要求 JVM 17+）。

```sh
# 指定 JDK 21（以 Homebrew 为例）
JAVA_HOME=$(ls -d /opt/homebrew/Cellar/openjdk@21/*/libexec/openjdk.jdk/Contents/Home | head -1) \
  ./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

---

## 安装与使用

```sh
# 1. 安装（-r 覆盖安装）
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. 重载目标进程让新 build 进内存（不要 force-stop、不要重启整机）
adb shell su -c 'pkill -f com.android.systemui'
adb shell su -c 'pkill -f com.android.launcher'
```

- 在 LSPosed / Xposed 管理器中启用 `ColorOS Mod`；作用域已在 `res/values/arrays.xml` 的
  `xposedscope` 中预声明，默认勾选，通常无需手动调整。
- 打开 `ColorOS Mod` 应用即可调整开关与滑条。**改动即时生效**（运行时门控），
  一般无需重启进程；若某功能需重启视图才能重建，可点击界面右上角「重启作用域」按钮，
  或在 LSPosed 中把模块关掉再打开。

### 重要注意事项

- **禁止重启整机**：设备处于越狱（jailbreak）环境，重启会丢失越狱状态，需重新越狱才能恢复。
  让模块生效请使用上面的 `pkill`（只重启单个进程），**切勿 `adb reboot`**。
- **不要用 `am force-stop`**：它对 `com.android.systemui` 等进程往往无法真正重启，
  会导致新代码不进内存；请用 `pkill` / `killall`。
- **不要依赖 logcat 判断模块状态**：模块日志在 ColorOS 上会被过滤、经常为空，不能凭
  「没看到日志」断定未注入。功能是否生效以设备上的真实界面表现为准。

---

## 目录结构

```
coloros-mod/
├── app/
│   ├── build.gradle                  # 应用构建脚本（Compose、JDK 21、Xposed API）
│   └── src/main/
│       ├── AndroidManifest.xml       # Xposed 模块声明 + 设置 Activity + SettingsProvider
│       ├── assets/xposed_init        # Xposed 入口类声明
│       ├── java/com/rikumi/colorosmod/
│       │   ├── XposedInit.java       # 全部 hook 逻辑
│       │   ├── MainActivity.kt       # 设置界面（Compose）
│       │   ├── Couix.kt              # Couix UI 封装（开关 / 滑条 / 分组 / 主开关）
│       │   └── SettingsProvider.java # 跨进程读取设置的 ContentProvider
│       └── res/...                   # 资源 / 字符串 / 作用域数组
├── build.gradle                      # 根构建脚本
├── settings.gradle
└── gradle/wrapper/                   # Gradle wrapper
```

---

## 技术要点

- **跨进程设置读取**：被 hook 的进程（launcher/systemui 等）与模块 App 不同 UID，
  且受 SELinux 限制无法直接读 prefs 文件。模块通过 `SettingsProvider`（`ContentProvider`）
  走 Binder 通道暴露设置，`XposedInit` 内的 `readBool` / `readInt` 以
  「缓存 → ContentProvider → 粘性缓存 → 默认值」的顺序读取，实现 App 内改设置即时生效。
- **运行时门控**：所有功能 hook 均在注入时固定挂载，开关判断放在 hook 回调内部运行时读取，
  因此改设置无需重启目标进程。
- **滑条即时生效**：尺寸/比例类功能（图标间距、指示点间距、长按菜单缩放、副标题字号、
  通知内边距、背景亮度）的值均在每次调用时运行时读取，拖动滑条即刻反映到界面。

---

## 反编译参考

分析被注入 app 时使用 `../android-app-mods` 工具链（见 `android-app-mods/` 仓库）：
- 桌面：`android-app-mods/com.android.launcher/`
- 系统界面：`android-app-mods/com.android.systemui/`
- 安全中心：`android-app-mods/com.oplus.safecenter/`
- 设置：`android-app-mods/com.android.settings/`
