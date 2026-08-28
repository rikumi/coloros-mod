package com.rikumi.colorosmod;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import android.graphics.Canvas;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.TextSwitcher;

import com.rikumi.colorosmod.hooks.GestureHooks;
import com.rikumi.colorosmod.hooks.LauncherHooks;
import com.rikumi.colorosmod.hooks.SafecenterHooks;
import com.rikumi.colorosmod.hooks.SettingsHooks;
import com.rikumi.colorosmod.hooks.SystemServerHooks;
import com.rikumi.colorosmod.hooks.SystemUiHooks;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

// ColorOS (Oplus) 系统界面调整, 经 LSPosed 注入。
// 每个功能由 prefs 中各自的开关控制; 各 hook 的目标类/方法与逆向结论见对应方法上的注释。
public class XposedInit implements IXposedHookLoadPackage {

    public static final String TAG = "ColorOSMod";
    public static final String MODULE_PACKAGE = "com.rikumi.colorosmod";
    public static final String PREF_NAME = "settings";

    public static final String KEY_ICON_GAP_ENABLED = "icon_gap_enabled";
    public static final String KEY_ICON_GAP_DP = "icon_gap_dp";
    public static final String KEY_INDICATOR_DP = "indicator_dp";
    public static final String KEY_POPUP_SCALE_PERCENT = "popup_scale_percent";
    public static final String KEY_NOTIFICATION_SUBTITLE_SP = "notification_subtitle_sp";
    public static final String KEY_NOTIFICATION_PADDING_DP = "notification_padding_dp";
    public static final String KEY_INDICATOR_ENABLED = "indicator_enabled";
    public static final String KEY_QS_CARRIER_ENABLED = "qs_carrier_enabled";
    public static final String KEY_QS_TOPMARGIN_ENABLED = "qs_topmargin_enabled";
    public static final String KEY_NOTIFICATION_SUBTITLE_ENABLED = "notification_subtitle_enabled";
    public static final String KEY_NOTIFICATION_PADDING_ENABLED = "notification_padding_enabled";
    public static final String KEY_RECENTS_SHOW_HIDDEN_ENABLED = "recents_show_hidden_enabled";
    public static final String KEY_RECENTS_HIDE_FREEFORM_ENABLED = "recents_hide_freeform_enabled";
    public static final String KEY_HIDE_APPS_NOVERIFY_ENABLED = "hide_apps_noverify_enabled";
    public static final String KEY_HIDE_APPS_TITLE_FOLDER_ENABLED = "hide_apps_title_folder_enabled";
    // 通用设置 — 设置首页图标样式: 0=系统默认, 1=不规则图标, 2=圆形图标。
    public static final String KEY_SETTINGS_HOME_ICON_STYLE = "settings_home_icon_style";
    public static final int SETTINGS_HOME_ICON_STYLE_DEFAULT = 0;
    public static final int SETTINGS_HOME_ICON_STYLE_IRREGULAR = 1;
    public static final int SETTINGS_HOME_ICON_STYLE_CIRCLE = 2;
    // 缩小桌面图标长按菜单: 在资源层按比例缩放菜单的图标、文字、宽高与内外边距。
    public static final String KEY_SHRINK_POPUP_MENU = "shrink_popup_menu";
    // 长按菜单缩小比例的默认值(百分比, 0=系统原始大小)。实际值由滑条 KEY_POPUP_SCALE_PERCENT
    // 在运行时读取(0..2*默认值), 缩放系数 = 1 - pct/100。
    public static final int POPUP_SHRINK_PERCENT_DEFAULT = 10;
    // Feature 9 — 桌面双指张开(pinch-out)手势打开隐藏应用文件夹 (com.android.launcher)
    public static final String KEY_PINCH_OUT_OPEN_HIDE_APPS_ENABLED = "pinch_out_open_hide_apps_enabled";
    // 桌面文件夹展开背景透明化: 展开时系统对壁纸施加 blur=1.0 + 暗色, 看起来像一层灰。
    // 所有壁纸模糊都汇入 OplusDepthController.setBlur(float, boolean), hook 它并在有文件夹
    // 打开(含动画)时把模糊强制为 0; 不影响多任务/应用抽屉等其它场景。
    public static final String KEY_FOLDER_BG_TRANSPARENT_ENABLED = "folder_bg_transparent_enabled";
    // 调整桌面文件夹展开/收起动画持续时间: 时长来自 OplusFolderAnimationManager 构造时读取的
    // 4 个 integer 资源(850/800/600/600ms) + 基类的 config_materialFolderExpandDuration(200ms)。
    // 开启时统一替换为滑条值(100-500ms, 默认 300), 关闭时返回原值。
    public static final String KEY_FOLDER_ANIM_DURATION_ENABLED = "folder_anim_duration_enabled";
    public static final String KEY_FOLDER_ANIM_DURATION_MS = "folder_anim_duration_ms";
    // Feature 16 — 桌面编辑模式背景遮罩透明化 (com.android.launcher):
    // ToggleBarState/PagePreviewState 原生把编辑态壁纸 blur 固定为 1.0f, 同时可能叠加页面背景 alpha。
    public static final String KEY_EDIT_MODE_BG_TRANSPARENT_ENABLED = "edit_mode_bg_transparent_enabled";
    // Feature 10 — 合并控制中心背景 scrim 亮度 (com.android.systemui)
    public static final String KEY_QS_SCRIM_TRANSLUCENT_ENABLED = "qs_scrim_translucent_enabled";
    // 背景亮度滑条键(0-20, 默认 5): 0=全黑, 20=系统默认 lumin(不压暗)。
    public static final String KEY_QS_SCRIM_BRIGHTNESS = "qs_scrim_brightness";
    // 控制中心 WLAN/蓝牙 名称单行省略: 可伸缩 tile 的次级名称(SSID / 蓝牙设备名)承载在
    // labelDesc(TextSwitcher, R.id.tile_label_desc), 由 updateLabelDescText 经 TextSwitcherExtKt
    // .setContent 写入; 这里在每次 setContent 之后强制单行 + 行尾省略号。
    public static final String KEY_QS_TILE_NAME_ELLIPSIS_ENABLED = "qs_tile_name_ellipsis_enabled";
    // Feature 17 — 流体云出现时不隐藏电量百分比 (com.android.systemui):
    // 系统在流体云胶囊出现时会把 BatteryStyleModel.capsuleShowing=true,
    // 进而令 PercentOutIcon.isVisible=false, 隐藏状态栏电量百分比数字。
    // hook BatteryViewBinder.bind$updatePercentOutView, 强制 isVisible=true。
    public static final String KEY_FLUID_CLOUD_KEEP_PERCENT_ENABLED = "fluid_cloud_keep_percent_enabled";
    // 悬浮小窗贴边挂机: 拖到边缘松手时系统把窗口缩成边缘竖条并把任务切后台, 这里在 to-float
    // 结束后 moveToFront 拉回前台; 不能在提交中途拦截 —— 此时窗口已 hide 但任务仍前台, 会触发
    // "Application does not have a focused window" -> Input dispatching timed out -> ANR。
    // 需把模块作用域加入 "android"(system_server), 旧版 SystemUI 内 hook 路径已废弃。
    public static final String KEY_FLOAT_WINDOW_EDGE_HANG_ENABLED = "float_window_edge_hang_enabled";
    // 横屏应用小窗保持比例: 系统对横屏应用硬编码 ratio=0.5625f(9:16), 与设备真实比例不符,
    // 这里在 system_server 内接管该 ratio 与 launchBounds, 让小窗 宽:高 = 屏幕 高:宽。
    public static final String KEY_FLOAT_WINDOW_LANDSCAPE_KEEP_RATIO_ENABLED =
            "float_window_landscape_keep_ratio_enabled";
    public static final String KEY_GESTURE_BAR_HEIGHT_ENABLED = "gesture_bar_height_enabled";
    public static final String KEY_GESTURE_BAR_HEIGHT_DP = "gesture_bar_height_dp";
    public static final String KEY_GESTURE_BAR_WIDTH_ENABLED = "gesture_bar_width_enabled";
    public static final String KEY_GESTURE_BAR_WIDTH_DP = "gesture_bar_width_dp";
    public static final String KEY_MBACK_ENABLED = "mback_enabled";
    public static final String KEY_GESTURE_TOUCH_THROUGH_ENABLED =
            "gesture_touch_through_enabled";
    public static final String KEY_GESTURE_BAR_LONG_PRESS_DISABLE_ENABLED =
            "gesture_bar_long_press_disable_enabled";
    // 从桌面隐藏指定的单个 LAUNCHER 活动: 系统"隐藏应用"按包隐藏会误伤多入口应用(如电话本+拨号),
    // 故只过滤目标组件。配置表见 HIDDEN_LAUNCHER_TARGETS: { 门控偏好键, 包名, 活动类名 }。
    public static final String KEY_HIDE_CONTACTS_ENABLED = "hide_contacts_enabled";
    public static final String KEY_HIDE_GBOARD_ENABLED = "hide_gboard_enabled";
    // Feature 15 — 隐藏 GhostLock 图标(com.ghostlock.app): 已有 root 时无需再 root。
    public static final String KEY_HIDE_GHOSTLOCK_ENABLED = "hide_ghostlock_enabled";
    // 解锁时关机无需校验密码(com.android.systemui): 系统"关机校验密码"(Settings.Secure
    // oplus_shutdown_need_verification_password) 开启后, 电源菜单里关机/重启都会先弹凭据校验;
    // 唯一闸门是 ShutdownBiometricPrompt.isEnable(Context), 设备已解锁时返回 false 跳过校验。
    public static final String KEY_UNLOCKED_SHUTDOWN_NOVERIFY_ENABLED =
            "unlocked_shutdown_noverify_enabled";
    // 取消解锁界面控件光效(com.android.systemui): COUI 给锁屏密码控件叠了三类非纯色绘制,
    // 去掉后只剩背景填充色与描边(纯色), 按下时的缩放/变色反馈不受影响。
    public static final String KEY_KEYGUARD_NO_LIGHT_EFFECT_ENABLED =
            "keyguard_no_light_effect_enabled";
    // 自定义密码界面背景亮度(com.android.systemui): 密码界面(bouncer)的背景 = 模糊壁纸 + 平台混色。
    // 混色 top 层为 LUMINOSITY(mode 5) + #99262626, 会把亮度归一化到 RGB 0x26(38), 相当于给模糊
    // 加了"最低亮度" —— 即使壁纸是纯黑也会抬出 (26,26,26) 的灰底, 表现为一层去不掉的遮罩。
    // 实测确认它并非真正的遮罩 view(三块 scrim 的 alpha 均为 0), 改这个 MixColor 才是正解。
    public static final String KEY_KEYGUARD_BOUNCER_BRIGHTNESS_ENABLED =
            "keyguard_bouncer_brightness_enabled";
    // 亮度滑条键(0-5, 默认 0): 0=全黑(去掉系统抬的最低亮度), 5=系统默认 lumin(0x26=38)。
    public static final String KEY_KEYGUARD_BOUNCER_BRIGHTNESS = "keyguard_bouncer_brightness";
    // 锁屏通知区域下移(com.android.systemui): 锁屏上通知区顶部位置有三个来源(见
    // SystemUiHooks#hookKeyguardNotificationOffset), 三处统一叠加同一下移量。
    public static final String KEY_KEYGUARD_NOTIFICATION_OFFSET_ENABLED =
            "keyguard_notification_offset_enabled";
    public static final String KEY_KEYGUARD_NOTIFICATION_OFFSET_DP =
            "keyguard_notification_offset_dp";
    public static final int KEYGUARD_NOTIFICATION_OFFSET_DP_DEFAULT = 20;
    public static final int KEYGUARD_NOTIFICATION_OFFSET_DP_MAX = 40;
    // 输入密码界面支持侧滑或下滑返回(com.android.systemui): 开启后密码界面允许键盘区下滑手势穿透到
    // bouncer 容器以收起返回锁屏, 放行系统侧滑返回手势; 并把"上滑使用指纹解锁"提示改为"下滑返回指纹解锁"。
    public static final String KEY_KEYGUARD_BOUNCER_SWIPE_BACK_ENABLED = "keyguard_bouncer_swipe_back_enabled";
    // 密码支持滑动输入(com.android.systemui): 开启后密码数字键盘支持滑动连续输入。
    // 手指进入某数字键"中间 2/3 半径"的圆形区域即视为按下该键: 立即输入该字符并显示按下态;
    // 手指离开该键的范围时取消按下态(不重复输入)。实现见
    // SystemUiHooks#hookKeyguardSlideInput —— 接管 COUINumericKeyboard 的
    // handleActionDown / handleActionMove / handleActionUp(float, float, int) 三个私有方法,
    // 把系统"矩形命中 + 抬起才输入"改为"圆形命中 + 进入即输入"。
    public static final String KEY_KEYGUARD_SLIDE_INPUT_ENABLED = "keyguard_slide_input_enabled";

    // 跨进程读取开关用的应用 Context(被 hook 进程自身)与 ContentProvider 通道所需的字段。
    public static volatile android.content.Context sAppContext;
    public static final String SETTINGS_AUTHORITY = "com.rikumi.colorosmod.settings";
    // 开关值缓存 TTL。长按/拖拽期间会以触摸事件频率反复 readBool, TTL 过短会频繁触发同步
    // ContentProvider IPC 造成主线程卡顿; 5s 内连续读取全部命中内存缓存(零 IPC), 代价是最多 5s 延迟。
    public static final long CACHE_TTL_MS = 5000;
    public static final java.util.concurrent.ConcurrentHashMap<String, Object[]> sCache =
            new java.util.concurrent.ConcurrentHashMap<String, Object[]>(); // key -> {Long ts, Boolean val}

    public static final int ICON_GAP_DP = 4;
    public static final int INDICATOR_REDUCE_DP = 16; // requested page-to-Dock gap reduction in dp
    public static final int QS_FOOTER_MARGIN_DP = 8; // smaller top gap for footer (date/settings) so it sinks a little
    public static final float SUBTITLE_ORIG_SP = 24f; // system default subtitle text size
    public static final int SUBTITLE_REDUCE_SP_DEFAULT = 8; // default reduction (24sp -> 16sp); slider 0..2x
    public static final float SUBTITLE_OFFSET_DP = 8f; // move subtitle up & right by 8dp each (at default reduction)
    public static final int SUBTITLE_PAD_DP = 4; // extra top & bottom padding for the subtitle tv (at default reduction)
    public static final int NOTIFICATION_PADDING_DP = 4; // extra top & bottom padding for non-minimized (non-silent) notifications
    // 控制中心背景亮度: 默认 5(对应约 25% 系统默认 lumin); 系统默认 lumin 的 RGB 值为 0x33(51)。
    public static final int QS_SCRIM_BRIGHTNESS_DEFAULT = 5;
    public static final int QS_SCRIM_LUMIN_MAX = 0x33;
    // 密码界面背景亮度: 默认 0(全黑, 即去掉系统给模糊加的最低亮度); 上限 5 = 系统默认效果。
    // 实现按 overColor RGB 的比例缩放, 无需硬编码目标亮度。
    public static final int KEYGUARD_BOUNCER_BRIGHTNESS_DEFAULT = 0;
    public static final int KEYGUARD_BOUNCER_BRIGHTNESS_MAX = 5;

    // 调试日志: 仅用 Log.e(error 级别), 因为 ColorOS 会丢弃 Log.d/v/i/w 等非 error 日志。
    // 不触碰外部存储, 避免被 hook 的第三方进程(如桌面 com.android.launcher)因无存储权限而
    // 触发 MediaProvider(FUSE) 的 SecurityException 刷屏。
    public static void dbg(String msg) {
        Log.e(TAG, msg);
    }

    // 真实错误日志: Log.e 立即输出, 文件写入异步执行, 避免阻塞 Launcher/SystemUI 主线程。
    // 仅输出到 logcat, 不写文件, 避免 IO 卡顿。
    // 注意: 这里必须是 Log.e —— 曾因被清空实现导致所有 HOOK OK/FAIL 与异常静默丢失,
    // 无法判断 hook 是否命中, 直接造成多轮盲改。禁止再把方法体清空。
    public static void log(String msg) {
        Log.e(TAG, msg);
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        log("handleLoadPackage pkg=" + lpparam.packageName);
        // 缓存被 hook 进程自身的 Application Context, 供 readBool 通过 ContentResolver 跨进程查询设置。
        if (sAppContext == null) {
            sAppContext = currentApplication();
        }
        if ("com.android.launcher".equals(lpparam.packageName)) {
            LauncherHooks.hookLauncher(lpparam);
        } else if ("com.android.systemui".equals(lpparam.packageName)) {
            SystemUiHooks.hookSystemUi(lpparam);
        } else if ("com.oplus.safecenter".equals(lpparam.packageName)) {
            SafecenterHooks.hookSafecenter(lpparam);
        } else if ("com.android.settings".equals(lpparam.packageName)) {
            SettingsHooks.hookSettings(lpparam);
        } else if ("android".equals(lpparam.packageName)) {
            // system_server: 承载"贴边最小化"的真正提交逻辑(com.android.server.wm.FlexibleTaskController)
            SystemServerHooks.hookFloatWindowEdgeHangSystemServer(lpparam);
            // system_server: 横屏应用小窗保持比例(com.android.server.wm.FlexibleTaskController)
            SystemServerHooks.hookFloatWindowLandscapeKeepRatio(lpparam);
        }
    }

    // 读取桌面隐藏应用入口文件夹的自定义名称, 与安全中心内部
    // com.oplus.safecenter.privacy.utils.k#b 逻辑一致:
    //   content://com.android.launcher.OplusFavoritesProvider/desktopappedit, column=title,
    //   selection="componentName=?", arg="com.oplus.safecenter_<AppHideLauncherActivity>_<userId>"。
    // 查不到或为空时返回 null(调用方保持原标题"应用隐藏")。
    public static String readAppHideFolderName(android.content.Context context) {
        try {
            android.net.Uri uri = android.net.Uri.parse(
                    "content://com.android.launcher.OplusFavoritesProvider/desktopappedit");
            int userId = 0;
            try {
                java.lang.reflect.Method m = android.os.UserHandle.class.getDeclaredMethod("myUserId");
                m.setAccessible(true);
                userId = (Integer) m.invoke(null);
            } catch (Throwable ignored) {}
            String comp = "com.oplus.safecenter_com.oplus.safecenter.privacy.view.space.AppHideLauncherActivity_" + userId;
            android.database.Cursor c = context.getContentResolver().query(
                    uri, new String[]{"title"}, "componentName=?", new String[]{comp}, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String t = c.getString(c.getColumnIndex("title"));
                        if (t != null && !t.isEmpty()) return t;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            log("readAppHideFolderName fail: " + t);
        }
        return null;
    }

    // 跨进程读取开关, 走 Binder(ContentProvider) 通道, 不受 SELinux 对 app_data_file 的限制。
    // 顺序: 缓存(TTL 内直接返回) -> ContentProvider 取真实值 -> 粘性缓存 -> XSharedPreferences -> def。
    public static boolean readBool(String key, boolean def) {
        // 1) 新鲜缓存(有效期内)直接返回, 避免每次交互都走 IPC 造成卡顿
        Object[] cached = sCache.get(key);
        if (cached != null && System.currentTimeMillis() - (Long) cached[0] < CACHE_TTL_MS) {
            return (Boolean) cached[1];
        }
        // 2) ContentProvider 通道
        try {
            if (sAppContext == null) {
                sAppContext = currentApplication();
            }
            if (sAppContext != null) {
                ContentResolver cr = sAppContext.getContentResolver();
                Uri uri = Uri.parse("content://" + SETTINGS_AUTHORITY + "/" + key);
                Cursor c = cr.query(uri, null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst() && c.getColumnCount() > 0) {
                            int v = c.getInt(0);
                            boolean result = v == 1;
                            sCache.put(key, new Object[]{System.currentTimeMillis(), result});
                            return result;
                        }
                    } finally {
                        c.close();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 3) Provider 取不到(模块 App 未运行等): 回退到上一次成功取到的值(粘性, 即使已过期),
        //    保证设置一旦被写入就会"记住", 不受 App 被杀/重启影响; 仅从未取到过才用默认或 XSP。
        if (cached != null) {
            return (Boolean) cached[1];
        }
        try {
            XSharedPreferences pref = new XSharedPreferences(MODULE_PACKAGE, PREF_NAME);
            return pref.getBoolean(key, def);
        } catch (Throwable ignored) {
        }
        return def;
    }

    // 跨进程读取 int 设置(如滑条值), 通道与 readBool 相同: 缓存 -> ContentProvider -> 粘性缓存 -> 默认。
    // Provider 对不存在的键返回空游标, 此时回落到 def。
    public static int readInt(String key, int def) {
        Object[] cached = sCache.get(key);
        if (cached != null && System.currentTimeMillis() - (Long) cached[0] < CACHE_TTL_MS) {
            return (Integer) cached[1];
        }
        try {
            if (sAppContext == null) {
                sAppContext = currentApplication();
            }
            if (sAppContext != null) {
                ContentResolver cr = sAppContext.getContentResolver();
                Uri uri = Uri.parse("content://" + SETTINGS_AUTHORITY + "/" + key);
                Cursor c = cr.query(uri, null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst() && c.getColumnCount() > 0) {
                            int v = c.getInt(0);
                            sCache.put(key, new Object[]{System.currentTimeMillis(), v});
                            return v;
                        }
                    } finally {
                        c.close();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (cached != null) {
            return (Integer) cached[1];
        }
        return def;
    }

    public static float readDensity() {
        try {
            return android.content.res.Resources.getSystem().getDisplayMetrics().density;
        } catch (Throwable ignored) {
            return 3.0f; // common ColorOS density fallback
        }
    }

    // 反射获取当前进程 Application(Context), 用于 ContentResolver 跨进程查询设置。
    public static android.content.Context currentApplication() {
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread",
                    java.lang.ClassLoader.getSystemClassLoader());
            return (android.content.Context) XposedHelpers.callStaticMethod(at, "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }
}
