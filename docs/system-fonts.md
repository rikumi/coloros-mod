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

首次安装后先打开模块设置，连接 Xposed service 并完成字体同步。LSPosed 作用域已改为可配置，推荐项含 Chrome 和 WebView；其它使用 WebView 的应用也需加入作用域。更改字体后由用户重启目标应用；Java 共享映射仍需用户重启 Zygote。原生注入已激活的进程不支持模块热加载，需要重启该进程。

原生 XML 后端可通过上述文件入口消费新配置；使用已注册 Minikin 集合的 NDK 路径则依赖系统框架生成的新字体映射，不能把两者视为同一调用路径。直接系统调用、应用自带字体管理器、网页下载字体及未被 LSPosed 注入的独立渲染进程不在文件入口 Hook 的保证范围内。隔离进程注入和沙箱内文件描述符重开仍需设备验证。

## ColorOS 自定义等宽字体保护

目标 `OplusFontUtils.flipTypeface` 会将系统字体替换为 OSans，`replaceFakeBoldToColorMedium` 也可能将粗体等宽字体换成比例字体。选择自定义等宽字体时，模块在已注入的进程中保留等宽族及其派生字形，跳过这两条替换路径；其它选择保留系统处理方式。

自定义等宽字体按 cmap 与 hmtx 核对 ASCII 可打印字符的实际 advance，不仅依赖字体名称或 post 标记。TTC 中非等宽成员不参与等宽族匹配；集合内没有可用等宽成员时拒绝导入并保留原选择。该校验不要求 CJK 字符也与拉丁字符同宽。

本地检查包含字体解析、XML 生成和原生路径规则，共 162 项。四种 ABI 的原生库均参与 APK 构建；这些检查不能替代设备上的 LSPosed Native Hook、Chrome/WebView 渲染及隔离进程验证。

本段核对日期：2026-10-22。设备无线调试断开，尚未取得设备实际 WebView / Chrome 版本和原生后端证据；以上为上游源码分析，未将其表述为该设备已执行的调用栈。

参考源码：

- [Chromium font_utils.cc](https://github.com/chromium/chromium/blob/main/skia/ext/font_utils.cc)
- [Blink font_cache_skia.cc](https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/platform/fonts/skia/font_cache_skia.cc)
- [Blink font_cache_android.cc](https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/platform/fonts/android/font_cache_android.cc)
- [Skia Android XML parser](https://github.com/google/skia/blob/main/src/ports/SkFontMgr_android_parser.cpp)
- [Skia Android NDK backend](https://github.com/google/skia/blob/main/src/ports/SkFontMgr_android_ndk.cpp)
- [Android 16 NDK font API](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/native/android/system_fonts.cpp)
- [Blink RemoteFontFaceSource](https://github.com/chromium/chromium/blob/main/third_party/blink/renderer/core/css/remote_font_face_source.cc)
