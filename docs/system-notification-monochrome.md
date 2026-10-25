# 系统通知原生单色图案与白色提取

ColorOS 原生通知资源：

- 免打扰：SystemUI DndAlertHelper 发布 channel_dnd_notice，使用 zen_mode_icon。原资源为白月牙与蓝色底板合成的WebP；现按白色轮廓拟合外圆与内圆，改用两段圆弧构成的独立矢量，不再运行时提取位图。
- 电池/省电相关提示：OplusPowerNotificationWarnings、OplusPowerUI、超级省电提示共用 battery_full_notification，白电池/闪电与绿色底板合在WebP中。
- “Android 系统”的 WLAN 共享通知：小图标属于 com.android.wifi.resources/ic_wifi_settings。资源覆盖包 /system_ext/overlay/OplusWifiResource.apk 同时含有蓝色PNG与同形状的灰色单色矢量。模块直接复制该原生矢量path，白色填充后交给当前界面着色，不自行画替代热点符号。framework/stat_sys_tether_wifi 虽是原生单色矢量，但图形不同，未采用。

此处理随既有“单色通知图标”开关，不增加新设置或作用域。状态栏和通知中心共用同一图案；状态栏依赖现有原生前景色、doze和动画，通知模板跟随实际应用名/标题前景色。关闭或热重载仍按已有恢复流程还原。

对白色图案与有色底板合成的已确认电池SystemUI资源，绘制到96×96，提取白色前景。先估计占比至少10%的主有色底板颜色，再按白色合成公式解出覆盖率，并要求其它通道预测误差不超过24，保留原始透明度与抗锯齿；没有可靠主色时只接受通道最小值>=200、色差<32的近白色像素。后者不是对整个图标做灰度化。

拒绝空掩膜、白色面积超过65%的白底图、触及边缘等不可靠结果。成功后保留内部孔洞，按前景包围盒等比居中放大至96像素画布的80像素区域（约2dp边距）。结果按资源、level、配置缓存，最多16项，不逐帧读取像素。

只对白名单实际资源启用：SystemUI/zen_mode_icon、SystemUI/battery_full_notification，以及Android系统引用的Wi-Fi ic_wifi_settings。其它系统图标与第三方应用不盲目提白，继续采用已验证单色图标或原生数字回退。不修改原始Notification、通知发布权限、通道是否显示图标的设置或原生状态栏过滤规则。

`WhiteNotificationIconMaskTest` 为JDK独立检查，覆盖有色底板、白底/空图拒绝、透明像素、白图案、半透明与抗锯齿覆盖率、异色装饰排除及输入不变。另已用当前原始免打扰和电池WebP经过同一Java提取器生成预览 `docs/assets/system-notification-white-mask-preview.png`，确认月牙、电池孔洞和闪电保留。

免打扰矢量：`ic_dnd_monochrome.xml` 与 `docs/assets/dnd-monochrome.svg` 共用path，24×24画布、约2dp边距。通过当前原WebP白色阈值轮廓拟合圆心及半径，再用两圆差集构造月牙；拟合结果与白色阈值像素IoU=0.994329。重现命令为 `scripts/trace-dnd-icon.py <原zen_mode_icon.webp>`，需要Pillow、numpy、scipy、cairosvg。预览为 `docs/assets/dnd-monochrome-preview.png`。

## 无线调试

无线调试的原始状态栏图标为 android 的 stat_sys_adb。framework-res 原图包含灰色 Baklava 格子和洋红描边；通知中心通过原生染色可显示单色，但状态栏原始图标的单色校验会拒绝它。模块矢量保留该原生资源的全部路径和透明区域，仅将填充和描边统一为白色掩膜，继续使用状态栏原生前景色。

同时，UserHandle.ALL（userId=-1）的系统通知使用当前 SystemUI 上下文的 createPackageContext 读取资源，避免将 -1 作为具体用户查询包导致图标遗漏。其它用户仍通过 createPackageContextAsUser 读取。


## 模块测试通知矢量

仅对 com.android.shell / notification_layout_preview 的模块测试通知替换图标，保留 stat_notify_more 原生标签轮廓和透明加号，采用圆弧、二次曲线和线段描摹；不替换其它 Shell 通知。启用单色通知图标时同时作用于状态栏与通知中心，跟随各自原生前景色。原始 framework-res 72px 位图与描摹矢量的 alpha 阈值掩膜 IoU 为 0.98883，可用 scripts/trace-test-notification-icon.py 重新核对并生成预览。

## 单色分组计数圆圈

开启单色通知图标时，已由模块实际替换为单色图形的分组（含信息）不再采用原生从应用图标提取的数字圆圈色板，统一为 #80000000 半透明黑色背景、白色数字。保留形状、计数和布局；原生异步 GroupIconManager 配色完成后重新应用本规则。尚未替换的彩色图标维持原生设计，关闭或图标复用时恢复其原生色板。
