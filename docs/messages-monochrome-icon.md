# 信息应用的描摹单色图标

来源：ColorOS 信息应用 `com.android.mms` 16.60.10（versionCode 16060010），应用图标资源 `res/drawable-xxhdpi-v4/mms_launcher_icon.png`，180×180。不是同一 APK 内早期的绿色方形机器人 `ic_launcher.png`。

原图 SHA-256：`ce2a635ff719575bed2b56e2da4ab8733ec33cdacb78dd97b82dbad00212242f`，副本位于 `docs/assets/messages-source-16.60.10.png`。

## 提取和描摹

原 PNG 已将半透明白色合成到绿色渐变上，不能直接按 PNG alpha 分层。对白色强度使用 R/G 比值，排除透明圆角后取最大连通区域：强白色阈值 0.66 得到前景，弱阈值 0.50 得到双气泡并集。后者减去前者得到可见背景。阈值提取结果保存在 assets，便于检查选取对象。

按阈值轮廓描摹前景约93×75px主体及左下尾部、背景约93×75px主体及右下尾部；圆角由16px圆弧与线段组成，尾尖使用2px圆弧。背景减去前景向外扩张9px的区域，沿差集内缘使用25px圆弧，得到真实透明分隔，未用覆盖色伪装镂空。

原坐标去掉26px外边距，使用144×144 viewBox（起点−8,−8）；有效图形约120×119px，对应24dp画布约2dp边距、1.5dp气泡分隔。SVG与Android VectorDrawable使用完全相同的两个path，只有M/H/V/L/A/Z指令，无嵌入位图、底板或原圆角矩形。

生成：在安装 Pillow、numpy、scipy、cairosvg 的 Python 环境中运行 `scripts/trace-messages-icon.py <原图PNG> docs/assets`。检查：运行 `scripts/check-messages-vector.py`。实测前景与强阈值区域IoU为0.9933，两个图形互不重叠，最小分隔约9原图像素。

## 单色通知开关

仅在既有单色通知图标开关开启时：

- 状态栏沿用 IconManager 的独立 StatusBarIcon 替换路径，将信息包映射到模块矢量资源，保留尺寸约束、状态栏着色和可用图标计数逻辑。
- 通知中心只匹配实际通知行所属包为com.android.mms、控件为CachingIconView（含NotificationRowIconView与分组子类）且资源名为icon的头部图标。绘制前使用矢量图案和当前通知应用名/标题的前景颜色，移除图标底板；兼容厂商异步分组图标再次绑定。不会修改通知对象、应用图标、头像、正文图像或其它包。
- 保存最新原生Drawable与背景。关闭开关、视图复用或热重载清理时恢复原生图标；原生通知对象及共享StatusBarIcon描述符不变。

预览与阈值掩膜位于 docs/assets。

厂商 notification_icon_filter 仍是适合有色图标底板的深色，且与通知卡片深色背景无关；移除底板后的矢量必须跟随当前通知应用名/标题颜色，并在 ImageView 自身 tint 应用后重申，不能直接套用这个深色资源。
