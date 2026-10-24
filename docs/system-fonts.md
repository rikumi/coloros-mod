# 系统字体

设置位于“系统字体”分类。中文和西文的初始值为“默认”，等宽字体的初始值为 Maple Mono。三个选项互不影响，不参与一键启用。

“自定义”通过系统文件选择器导入 TTC、TTF 或 OTF；再次选择“自定义”可重新导入。取消选择和解析失败时保留先前选择。字体逐项保存于模块的设备加密存储 `files/system_fonts/<key>.font`，成功导入覆盖该项先前文件；不依赖原文件或持久 URI 权限。每项上限 100 MiB。

启动模块设置时通过 libxposed service 同步专用远程配置和字体文件。框架 Hook 使用 `getRemotePreferences` / `openRemoteFile`，可在系统启动早期读取，不向 ContentProvider 同步请求字体。没有可用的 Xposed service 时，保存操作失败并显示提示。

## 系统框架路径

已核对 `../android-app-mods/_framework/framework.jar` 和 `src-services` 中的目标实现：

- `SystemFonts#createFontFamily(List, String, int, int, boolean, Map)`：替换中文无衬线回退族，保留其语言、variant 和默认回退属性。中文识别同时检查 zh 标签和 ColorOS Hans/Hant 字体路径；衬线族不替换。
- `SystemFonts#buildSystemFallback`：替换 `sans-serif` 及其派生族、ColorOS `sys-sans-en`、OnePlus `op-sans-en`、`monospace` 的首个字体族，保留后续回退链。
- `FontManagerService#serializeFontMap`：将上述字体映射序列化，供应用进程共享。

字体序列化依赖可重新打开的文件路径。系统框架将所选字体按 SHA-256 缓存到 `/data/fonts/files/coloros-mod`，沿用父目录的 `font_data_file` 标签及应用读取权限。文件名不可变，避免旧进程引用的字体路径被新导入覆盖。模块 Hook 仅在 `File#listFiles()` 枚举 `/data/fonts/files` 时排除自己的目录，避免 `UpdatableFontDir` 将其判定为无效字体更新并清理原生更新；其它条目、签名和系统更新配置均不修改。

TTC 中各字体的 OS/2 字重、斜体、名称和 fvar 字重轴均参与解析，集合内表偏移按整个文件计算。Hook 根据字重、斜体和中文地区选择对应成员，以真实字重构建字体族；可变字体限制在其声明的字重范围内。缺少的字重和斜体由系统字体匹配机制处理。内置思源黑体使用 SC 地区字形。所有内置字体及授权来源见 `app/src/main/assets/system_fonts/README.md`。

字体映射在系统启动时构建。更改字体后需要由用户重启设备或 Zygote，并重启已有应用进程，才能验证所有界面；仅热加载模块不足以替换已分发的映射。开发过程未执行设备重启或 Zygote 重载。

## 验证

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 python3 scripts/check-system-fonts.py`：验证 TTC 成员索引、粗体/斜体匹配、简繁及香港地区匹配、可变字重范围、损坏输入，以及全部内置字体元数据。
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:assembleDebug`。
- APK 使用项目固定证书签名。

尚未进行设备上的字体显示、Direct Boot 读取、字体缓存权限和与主题字体交互验证。构建和静态校验不表示设备显示已确认。

## WebView 与 Chrome 的字体读取路径（上游源码分析）

Java 系统 Typeface 映射与 Chromium 的原生字体管理器是两条读取路径。仅替换前者不能覆盖网页内容，因此本模块另通过 LSPosed Native Hook 提供进程内字体配置重定向。

Chromium 的常规路径：

1. Blink 将 CSS `font-family`、字重及斜体交给 `FontCache::CreateTypeface` / `MatchFamilyStyle`。
2. `skia::DefaultFontMgr()` 在进程内通过 `std::call_once` 创建独立字体管理器。
3. Android 常规后端为 `SkFontMgr_New_Android`，其 `SkFontMgr_Android_Parser::GetSystemFontFamilies` 解析 `/system/etc/fonts.xml`，并合并 `/product/etc/fonts_customization.xml`，使用对应系统/产品字体路径及 TTC 索引、变化轴。
4. 中文等缺字回退通过 `FontCache::PlatformFallbackFontForCharacter`、`MatchFamilyStyleCharacter` 返回该管理器中的字体，不调用 Java `Typeface.create()`。
5. CSS `@font-face` 下载字体走 `RemoteFontFaceSource` 和自定义字体数据路径；`local()` 另有唯一字体名称匹配路径，不能与通用系统字体替换混为一谈。

上游另有 `UseAndroidNDKFontAPI` 分支。当前上游源码默认关闭该特性，支持的 Android 版本上可使用 `SkFontMgr_New_AndroidNDK`。Android 16 的 `ASystemFontIterator_open` 优先读取 Minikin 已注册的字体集合，集合为空时才回退解析 XML；`AFontMatcher_match` 也会访问 Minikin 的族集合。因此不能把 NDK 分支也简单描述成“始终直接读取 fonts.xml”。必须根据实际 Chromium 版本和后端核对。

## LSPosed 原生字体注入

无需配套 KernelSU 模块。设置进程将所选字体、改写后的字体 XML 和路径清单作为不可变快照，通过 libxposed service 发布。已注入的应用进程启动时读取快照和文件描述符，再注册 LSPosed Native Hook，拦截 libc 的只读 open/openat/fopen、stat 和 access 路径。仅对清单内的字体配置和虚拟字体路径返回模块提供的文件，其它访问透传，不挂载或修改系统分区。

XML 保留原生回退链、emoji 和衬线字体，选中字体按真实字重、斜体、TTC 成员索引和变量字重轴生成；字体路径使用相对形式，兼容 Skia 将其与字体目录拼接的行为。系统及产品目录的配置均纳入快照。

首次安装后先打开模块设置，连接 Xposed service 并完成字体同步。LSPosed 作用域可配置。由于当前框架未向独立渲染进程注入模块，字体替换不能覆盖 Chrome/WebView 的网页渲染，推荐作用域已移除 Chrome、Google WebView 和 Android WebView；不再为网页字体替换推荐添加这些包或其它 WebView 宿主。更新推荐列表不会自动取消 LSPosed 已保存的用户勾选，既有选择需在管理器中取消。更改字体后由用户重启目标应用；Java 共享映射仍需用户重启 Zygote。原生字体桥按进程生命周期保留。Java hooks 热重载时交接最初的库持有者，不重复加载库或拦截 libc；原生字体快照或原生库代码本身的更新仍需重启目标进程。

原生 XML 后端可通过上述文件入口消费新配置；使用已注册 Minikin 集合的 NDK 路径则依赖系统框架生成的新字体映射，不能把两者视为同一调用路径。直接系统调用、应用自带字体管理器、网页下载字体及未被 LSPosed 注入的独立渲染进程不在文件入口 Hook 的保证范围内。隔离进程注入和沙箱内文件描述符重开仍需设备验证。

## ColorOS 自定义等宽字体保护

目标 `OplusFontUtils.flipTypeface` 会将系统字体替换为 OSans，`replaceFakeBoldToColorMedium` 也可能将粗体等宽字体换成比例字体。选择自定义等宽字体时，模块在已注入的进程中保留等宽族及其派生字形，跳过这两条替换路径；其它选择保留系统处理方式。

自定义等宽字体按 cmap 与 hmtx 核对 ASCII 可打印字符的实际 advance，不仅依赖字体名称或 post 标记。TTC 中非等宽成员不参与等宽族匹配；集合内没有可用等宽成员时拒绝导入并保留原选择。该校验不要求 CJK 字符也与拉丁字符同宽。

本地检查包含字体解析、XML 生成和原生路径规则，共 162 项。四种 ABI 的原生库均参与 APK 构建；这些检查不能替代设备上的 LSPosed Native Hook、Chrome/WebView 渲染及隔离进程验证。

### 2026-10-22 实机读取结果

设备为 OPPO PKT110，固件 `PKT110_16.0.10.500(CN01)`，LSPosed `2.2.1 (7912)`。当前 Chrome 为 `155.0.8059.84`，系统选用的 Google WebView 为 `138.0.7204.179`。

只读检查已确认：

- 模块本地已选思源黑体与 Inter；LSPosed 远程存储存在对应字体、改写后的 XML 和路径清单，清单分隔符按字节核对有效。
- Chrome 主进程映射了本模块的原生代码，并持有七个 `ColorOSModFont` memfd。其 XML 描述符内容包含 Inter 对应的替换路径和真实字重轴。
- Chrome 的 app Zygote、独立 `sandboxed_process`，以及从 `webview_zygote` 分叉的 WebView 独立渲染进程，均未观察到本模块的代码映射或字体桥描述符。
- 两类独立渲染进程仍映射系统 Roboto、SysSans 等字体，未观察到替换字体的映射。

结合用户反馈，当前进程内重定向没有覆盖这台设备上的独立网页渲染路径。主进程持有配置只能证明快照初始化，不能证明网页已使用替换字体；也不能凭这次映射检查确定每个字体的具体调用栈。仅增加宿主作用域或 Java Typeface Hook 不能作为已修复的依据。

后续按用户要求优先检查 LSPosed 的隔离进程注入，不采用字体挂载方案。主进程继续增加文件入口 Hook 不能弥补缺失的渲染进程注入。本次检查没有执行挂载、重启、进程重载或设备界面操作。

### LSPosed 2.2.1 隔离进程注入检查

本段依据设备上实际安装的 `daemon.apk`、`framework.dex`、`zygisk/arm64-v8a.so` 以及 Chrome/WebView 的 APK Manifest；使用项目既有 JADX 与 Android SDK 工具只读分析，混淆类名只针对当前构建有效。

1. **作用域展开明确排除隔离服务。** `daemon.apk` 的 `defpackage.e5.f(PackageInfo)` 遍历服务时，仅在 `(serviceInfo.flags & 2) == 0` 时加入 `processName`。SDK 的 `ServiceInfo.FLAG_ISOLATED_PROCESS` 常量为 2。Chrome 的 `SandboxedProcessService0` 同时声明 `isolatedProcess=true`、`useAppZygote=true`；WebView 的对应服务声明 `isolatedProcess=true`、`externalService=true`，因此不进入这条普通进程作用域列表。
2. **进程身份不是宿主应用 UID。** 实机 ActivityManager 将 Chrome 渲染进程标为宿主 UID 10362、隔离 UID 90000；WebView 当前渲染进程 UID 为 99000。`defpackage.e5.g(String,int)` 给普通进程列表配对的是 `applicationInfo.uid`；`j7.s(...)` 生成 `g7(processName,uid)` 键，`j8.e()` 按这一精确键取得模块列表，未在这条路径发现把隔离 UID 反查到宿主 UID 的处理。单纯增加进程名称不能解决该身份差异。
3. **没有找到 CLI 强制注入入口。** 设备 `lspctl --help` 与反编译的 `CliCommand` 包含状态、模块启停、包级作用域和 Hook 调试功能，没有向任意 PID 注入模块的命令。包级作用域仍经过上述筛选。
4. **公开原生代码只能作为历史对照。** 公开提交 `df74d83eb03a44cc6ad268841ac2ada28d077c77` 的 `MagiskLoader::OnNativeForkAndSpecializePre` 明确跳过 child Zygote、90000～98999 与 99000～99999 隔离 UID；不能直接将其当作设备 2.2.1 原生库的对应源码。设备原生库已混淆，当前未完成其所有分支的还原。设备进程映射已直接确认 Chrome app Zygote、WebView Zygote 和所检查的渲染子进程均未加载本模块。

沿 LSPosed 路线继续实现，需要框架层提供隔离进程支持：追踪宿主包与隔离 UID 的对应关系，在 Chrome app Zygote / WebView Zygote 的分叉路径保留或重新加载注入代码，并在 Chromium 字体管理器初始化之前提供原生字体快照。还需验证框架服务通信、文件描述符保留和 SELinux 权限。仅调整本模块的 `scope.list`、Native 入口或 Java Hook，不能绕过已确认的守护进程筛选。

本次未修改、替换或重启设备上的 LSPosed，也未关闭 Chromium 隔离机制。当前结果是明确了框架需要支持的入口，不代表已实现渲染进程注入。

提取文件的 SHA-256：

- `daemon.apk`: `a43bb3554721d650356fa23783a4f3757b85e7c703b836a28eeba6e3cc7c960a`
- `framework.dex`: `73cceb77d02950cf7f224900f23a896506a0164ab7008e1430e502ff985d0140`
- `zygisk/arm64-v8a.so`: `2b188f7615b27773f2dadf7725a908d61e27a933cc01ecc353d1b60b439f3492`

参考源码：

- [Chromium font_utils.cc](https://github.com/chromium/chromium/blob/main/skia/ext/font_utils.cc)
- [Blink font_cache_skia.cc](https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/platform/fonts/skia/font_cache_skia.cc)
- [Blink font_cache_android.cc](https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/platform/fonts/android/font_cache_android.cc)
- [Skia Android XML parser](https://github.com/google/skia/blob/main/src/ports/SkFontMgr_android_parser.cpp)
- [Skia Android NDK backend](https://github.com/google/skia/blob/main/src/ports/SkFontMgr_android_ndk.cpp)
- [Android 16 NDK font API](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/native/android/system_fonts.cpp)
- [Blink RemoteFontFaceSource](https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/core/css/remote_font_face_source.cc)
