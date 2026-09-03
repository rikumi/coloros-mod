package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.KEY_ANC_TILE_ENABLED;
import static com.rikumi.colorosmod.XposedInit.MODULE_PACKAGE;
import static com.rikumi.colorosmod.XposedInit.currentApplication;
import static com.rikumi.colorosmod.XposedInit.log;
import static com.rikumi.colorosmod.XposedInit.readBool;
import static com.rikumi.colorosmod.XposedInit.sAppContext;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.rikumi.colorosmod.R;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import org.json.JSONArray;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 控制中心蓝牙磁贴显示降噪控制(com.android.systemui)。
//
// 形态直接复用系统三段式静音磁贴(ringermode)的整套实现, 不做自绘。逆向结论(classes3/classes8):
//   1) 形态选择   TileDataViewModelMapper$Companion#convertTileConfigToViewModel 里硬编码
//      tileSpec.equals("ringermode") ? TileUiType.THREE_STAGE : TileUiType.DEFAULT;
//      其结果经 TileEditViewModel#4$3.emit -> mapper.map -> EditTileAdapter 决定
//      getItemViewType(5=三段式), 再由 EditableTileViewHolder.Companion.fetch 造出
//      OplusQSResizeableThreeStageView(OplusQSThreeStageIconView)。合并/分离两套控制中心
//      共用这条链路(OplusQSTilesComponent / OplusQSLeftBTilesComponent), 故改一处即可。
//   2) 状态下发   OplusQSResizeableThreeStageView#handleTileStateChange 调用
//      OplusQSThreeStageLayout.setSelectedIndex(state.threeStageMode, ...),
//      即把 QSTile.State.threeStageMode 当段索引用。
//   3) 点击回传   OplusQSThreeStageLayout 在触摸/点击时给自己 setTag(段值),
//      OplusQSResizeableTileView$init$tileClick$1.onClick 把它搬到外层 view 的
//      R.id.qs_three_stage_tag, 再调 tile.click(); ThreeStageRingerModeTile#handleClick
//      正是从这里取值。
//   4) 段值与屏幕位置(LTR) 左=2 中=1 右=0。静音磁贴即 响铃=2(左) 振动=1(中) 静音=0(右)。
//      本功能沿用同一约定: 降噪=2(左) 关闭=1(中) 通透=0(右), 与需求一致。
//
// 降噪状态的读写走欢律(com.oplus.melody)的 EarphoneControlProvider —— 系统给设备卡片/
// SystemUI 用的正式接口, 声明了 android:permission="com.oplus.permission.safe.IOT"
// (signature|privileged), SystemUI 持有该权限, 无需 root、无需注入欢律进程。
//   query  content://com.oplus.melody.provider.EarphoneControlProvider/melody_method_active_device
//          -> 列 name, address(当前活动耳机, 未连接时为空游标)
//   query  .../melody_method_noise_reduction  selection="address" args=[mac]
//          -> 列 name, address, type(当前模式), supports(支持模式的 JSON 数组)
//   call   .../melody_method_noise_reduction  extras: name / address / type(int) -> 切换模式
// 欢律状态变化会 notifyChange 该 provider 的根 Uri, 故用 ContentObserver 做近实时回显。
//
// 模式类型(欢律 WhitelistConfigDTO.NoiseReductionMode.getModeType):
//   1=关闭 2=通透 3=轻度降噪 4=深度降噪 5=降噪 10=自适应
// 图标沿用欢律 melody_ui_reduction_noise_* 的矢量路径, 与欢律详情页完全一致。
public final class AncTileHooks {

    // ---- 欢律 ContentProvider ----
    private static final String MELODY_AUTHORITY = "com.oplus.melody.provider.EarphoneControlProvider";
    private static final String METHOD_ACTIVE_DEVICE = "melody_method_active_device";
    private static final String METHOD_NOISE_REDUCTION = "melody_method_noise_reduction";
    private static final String COLUMN_NAME = "name";
    private static final String COLUMN_ADDR = "address";
    private static final String COLUMN_TYPE = "type";
    private static final String COLUMN_SUPPORTS = "supports";

    private static final String SYSUI_PACKAGE = "com.android.systemui";
    /** 蓝牙磁贴的 tile spec(OplusBluetoothTile#getTileSpec)。 */
    private static final String BT_SPEC = "bt";

    // ---- SystemUI 侧目标类 ----
    private static final String CLS_MAPPER =
            "com.oplus.systemui.plugins.qs.customize.viewmodel.TileDataViewModelMapper$Companion";
    private static final String CLS_TILE_UI_TYPE =
            "com.oplus.systemui.plugins.qs.customize.viewmodel.model.TileUiType";
    private static final String CLS_TILE_VIEW_MODEL =
            "com.oplus.systemui.plugins.qs.customize.view.viewholder.TileViewModel";
    private static final String CLS_BT_TILE = "com.oplus.systemui.qs.tiles.OplusBluetoothTile";
    private static final String CLS_THREE_STAGE_ICON_VIEW =
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSThreeStageIconView";
    private static final String CLS_THREE_STAGE_LAYOUT =
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSThreeStageLayout";
    private static final String CLS_THREE_STAGE_LOTTIE =
            "com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSThreeStageLottieView";
    private static final String CLS_BT_CONTROLLER_IMPL =
            "com.android.systemui.statusbar.policy.BluetoothControllerImpl";
    private static final String CLS_SEP_CLICK_MANAGER =
            "com.oplus.systemui.plugins.qs.OplusSeparateClickTileManager";
    private static final String CLS_TEMP_INFO_MANAGER =
            "com.oplus.systemui.qs.base.temporarilyinfo.TemporarilyInfoManager";
    private static final String CLS_INFO_SHOW_STYLE =
            "com.oplus.systemui.qs.base.temporarilyinfo.TemporarilyInfoManager$InfoShowStyle";
    // 音量条底部图标(OplusQsVolumeIconView)在蓝牙路由下的状态对象: 连着蓝牙耳机时,
    // 音量图标会切到 status_bar_qs_icon_volume_media_bt(_mute), 我们要换的就是它。
    private static final String CLS_VOLUME_BT_ICON_STATE =
            "com.oplus.systemui.qs.base.seek.OplusQsVolumeIconView$bluetoothRouteIconState$1";
    // 音量条图标的点击监听器(原本是"音量切到最小/恢复", 即静音切换)。
    private static final String CLS_VOLUME_ICON_CLICK =
            "com.oplus.systemui.qs.base.seek.OplusQsVolumeSliderController$volumeIconClickListener$1";
    private static final String CLS_VOLUME_STATE =
            "com.android.systemui.plugins.qs.QSVolume$VolumeState";

    // 三段式布局里三段图标/动画视图的 id 名(SystemUI 的 oplus_qs_three_stage_layout.xml)。
    // 顺序固定为 左/中/右, 与 iconResIds 一致。
    private static final String[] ICON_ID_NAMES = {
            "left_icon_view", "middle_icon_view", "right_icon_view"};
    private static final String[] LOTTIE_ID_NAMES = {"left_lottie", "middle_lottie", "right_lottie"};

    // 三段的模式类型(欢律 mode type), 与 ICON_ID_NAMES 同序: 降噪 / 关闭 / 通透。
    // 降噪一栏按耳机实际支持情况依次尝试 降噪 / 深度降噪 / 轻度降噪 / 自适应。
    private static final int[] SLOT_ANC = {5, 4, 3, 10};
    /** threeStageMode 段值 -> 段索引(左中右)。 */
    private static final int[] STAGE_TO_SLOT = {2, 1, 0};

    // 切模式后的回显刷新时机(ms): 欢律写耳机是异步的, 一次太快、一次兜底。
    private static final long[] REFRESH_DELAYS = {600L, 1800L};
    // 兜底轮询间隔(ms): 蓝牙断连等活动没有 provider 通知, 只能靠它收尾。
    private static final long POLL_INTERVAL_MS = 5000L;
    // 主线程同步查询的最小间隔(ms), 避免反复展开控制中心时每次都跨进程查欢律。
    private static final long SYNC_QUERY_THROTTLE_MS = 3000L;
    // 三段式点击后抑制蓝牙开关的窗口(ms): 只覆盖同一串调用链, 不会误伤别处的蓝牙操作。
    private static final long BT_TOGGLE_SUPPRESS_MS = 800L;
    // 单次切换最多重试次数: 防止耳机无响应时协调任务无限循环。
    private static final int MAX_SYNC_ATTEMPTS = 4;
    // 三段式图标位移的收敛系数: 系统默认是 ±10% 段宽, 蓝牙磁贴取一半。
    private static final float ICON_OFFSET_SCALE = 0.5f;

    /** 一次查询得到的耳机状态快照。 */
    private static final class State {
        /** 欢律内部名(如 "OnePlus Buds 3"), 写回时必须原样带上, 否则 call 会因名字不匹配被拒。 */
        String name;
        String address;
        int type;
        List<Integer> supports = new ArrayList<>();

        String signature() {
            return name + "|" + address + "|" + type + "|" + supports;
        }
    }

    private static volatile Context sContext;
    private static volatile Resources sRes;
    private static volatile Handler sMain;
    private static volatile ExecutorService sWorker;
    private static volatile int[] sIconIds;
    private static volatile int[] sLottieIds;

    /** 缓存的最新耳机状态; null = 当前没有可控的降噪耳机。所有读取方都只读它。 */
    private static volatile State sState = null;
    /** 蓝牙磁贴实例(弱引用), 用于状态变化时主动 refreshState。 */
    private static volatile WeakReference<Object> sTileRef = new WeakReference<Object>(null);
    /** 已换成降噪图标的三段式 lottie 视图 -> 标记, 用于跳过 ringermode 动画。 */
    private static final Map<Object, Boolean> sAncLottieViews = new WeakHashMap<Object, Boolean>();
    /** 正在服务蓝牙磁贴的三段式布局实例, 用于把段选中事件路由回降噪切换。 */
    private static final Map<Object, Boolean> sAncLayouts = new WeakHashMap<Object, Boolean>();
    /** 抑制蓝牙开关的时间窗(截止时间), 用于吞掉三段式点击顺带触发的开关蓝牙。 */
    private static volatile long sSuppressBtToggleUntil = 0L;
    /** 待替换的提示文案; null = 这次提示不是蓝牙磁贴触发的, 维持系统原文案。 */
    private static volatile String sPendingToast = null;
    /** 音量条底部图标视图(OplusQsVolumeIconView), 降噪状态变化时主动刷新它。 */
    private static volatile WeakReference<Object> sVolumeIconRef = new WeakReference<Object>(null);
    /** 音量条填充是否已漫过图标(决定深色/浅色), 由 onVolumeStateChanged 更新。 */
    private static volatile boolean sVolumeAboveThreshold = false;
    /**
     * 期望的降噪模式(乐观值); null = 没有待兑现的切换。
     * 点击后立即置位并据此刷新 UI, 不等欢律回显 —— 回显期间 sState 会出现中间态
     * (欢律按 address 精确匹配, 切换瞬间可能查不到而返回 null), 若直接用它刷新,
     * 磁贴会在两种形态间瞬跳, 表现为闪烁。
     */
    private static volatile Integer sTargetType = null;
    /** 是否有协调任务在跑, 保证多次点击串行兑现。 */
    private static volatile boolean sSyncing = false;
    private static volatile long sLastSyncQueryMs = 0L;

    // ---- 注入 ----

    public static void hookAncTile(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        log("anc: hooking in " + lpparam.packageName);
        try {
            // 1. 形态: 蓝牙磁贴在三段式可用时改用 TileUiType.THREE_STAGE。
            final Class<?> mapper = XposedHelpers.findClass(CLS_MAPPER, cl);
            final Class<?> tileVm = XposedHelpers.findClass(CLS_TILE_VIEW_MODEL, cl);
            XposedHelpers.findAndHookMethod(mapper, "convertTileConfigToViewModel",
                    XposedHelpers.findClass(
                            "com.oplus.systemui.plugins.qs.customize.viewmodel.model.QSTileAndConfig", cl),
                    java.util.List.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Object vm = param.getResult();
                                if (vm == null || !tileVm.isInstance(vm)) return;
                                if (!BT_SPEC.equals(XposedHelpers.getObjectField(vm, "spec"))) return;
                                // mapper 只在磁贴列表数据流发射时跑, 而耳机状态是后台异步拉的;
                                // 这里缓存还没建立时当场同步查一次, 避免首次展开时错过形态切换。
                                if (sState == null) syncQueryOnce();
                                boolean on = isAvailable();
                                log("anc: mapper bt, available=" + on + " state=" + describe(sState));
                                if (!on) return;
                                Object threeStage = XposedHelpers.getStaticObjectField(
                                        XposedHelpers.findClass(CLS_TILE_UI_TYPE, cl), "THREE_STAGE");
                                if (threeStage == null) return;
                                if (threeStage.equals(XposedHelpers.getObjectField(vm, "uiType"))) return;
                                // TileViewModel 的字段均为 final, 只能整体重建一个 uiType 不同的实例。
                                param.setResult(XposedHelpers.newInstance(tileVm,
                                        XposedHelpers.getObjectField(vm, "qsTile"),
                                        XposedHelpers.getObjectField(vm, "isSystem"),
                                        XposedHelpers.getObjectField(vm, "sepTileType"),
                                        XposedHelpers.getObjectField(vm, "spec"),
                                        threeStage));
                                log("anc: bt -> THREE_STAGE");
                            } catch (Throwable t) {
                                log("anc: mapper hook fail: " + t);
                            }
                        }
                    });
            log("HOOK OK TileDataViewModelMapper$Companion#convertTileConfigToViewModel (anc)");
        } catch (Throwable t) {
            log("HOOK FAIL TileDataViewModelMapper$Companion#convertTileConfigToViewModel (anc) :: "
                    + Log.getStackTraceString(t));
        }

        try {
            final Class<?> tile = XposedHelpers.findClass(CLS_BT_TILE, cl);
            // 2. 状态: 把当前降噪模式写成 threeStageMode 交给三段式布局。
            XposedHelpers.findAndHookMethod(tile, "handleUpdateState",
                    XposedHelpers.findClass("com.android.systemui.plugins.qs.QSTile$BooleanState", cl),
                    Object.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                sTileRef = new WeakReference<Object>(param.thisObject);
                                State st = sState;
                                if (st == null || !readBool(KEY_ANC_TILE_ENABLED, false)) return;
                                // 用乐观值: 切换过程中 sState 会出现中间态, 直接用它会让
                                // 三段式选中段跟着跳, 表现为磁贴闪烁。
                                int shown = displayType();
                                XposedHelpers.setIntField(param.args[0], "threeStageMode",
                                        stageOfType(shown));
                                log("anc: bt state threeStageMode=" + stageOfType(shown)
                                        + " shown=" + shown + " real=" + st.type);
                            } catch (Throwable t) {
                                log("anc: update state fail: " + t);
                            }
                        }
                    });
            // 3. 单击: 三段式生效时整段吞掉, 不再落到"开关蓝牙"的原逻辑。
            //    真正的切模式由下面 selectSegmentAt 的 hook 负责 —— 那里能直接拿到段索引,
            //    不依赖 qs_three_stage_tag(之前依赖它, tag 取不到时就退回原生点击把蓝牙关了)。
            XposedHelpers.findAndHookMethod(tile, "handleClick",
                    XposedHelpers.findClass("com.android.systemui.animation.Expandable", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (!isAvailable()) return;
                                param.setResult(null);
                                log("anc: bt click swallowed");
                            } catch (Throwable t) {
                                log("anc: click fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusBluetoothTile#handleUpdateState/handleClick (anc)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusBluetoothTile (anc) :: " + Log.getStackTraceString(t));
        }

        try {
            // 3b. 段选中: 用户点/拖到某一段的唯一收敛点(OplusQSThreeStageLayout 的
            //     onTouchEvent 与每个 frame 的 OnClickListener 最终都调 selectSegmentAt)。
            //     只在段值与当前降噪模式不一致时下发, 避免回显(refreshState -> setSelectedIndex)
            //     反向触发写操作形成死循环。
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(CLS_THREE_STAGE_LAYOUT, cl), "selectSegmentAt",
                    int.class, new XC_MethodHook() {
                        // selectSegmentAt 一定是先于磁贴 onClick 执行的(handleTouchEnd 与 frame
                        // 的 OnClickListener 都是先 selectSegmentAt 再 viewClick/onClick),
                        // 所以在这里开一个短窗口, 让紧随其后的 setBluetoothEnabled 被吞掉。
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (!Boolean.TRUE.equals(sAncLayouts.get(param.thisObject))) return;
                                if (!isAvailable()) return;
                                sSuppressBtToggleUntil =
                                        SystemClock.elapsedRealtime() + BT_TOGGLE_SUPPRESS_MS;
                            } catch (Throwable t) {
                                log("anc: segment pre fail: " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (!Boolean.TRUE.equals(sAncLayouts.get(param.thisObject))) return;
                                if (!isAvailable()) return;
                                if (XposedHelpers.getBooleanField(param.thisObject, "isEditMode")) {
                                    return;
                                }
                                State st = sState;
                                if (st == null) return;
                                // selectSegmentAt 的参数是**段索引**(0/1/2 = 左/中/右),
                                // 而 stageOfType 返回的是**段值**(左=2 中=1 右=0), 两者必须经
                                // STAGE_TO_SLOT 换算后再比较 —— 直接拿索引跟段值比会导致
                                // "回显选中段"被误判成"用户点了另一段", 从而反复互相下发。
                                int index = XposedHelpers.getIntField(param.thisObject, "selectedIndex");
                                if (index < 0 || index > 2) return;
                                int stage = STAGE_TO_SLOT[index];
                                if (stage == stageOfType(st.type)) return;
                                log("anc: segment index=" + index + " stage=" + stage + " -> apply");
                                applySegment(index);
                            } catch (Throwable t) {
                                log("anc: segment fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusQSThreeStageLayout#selectSegmentAt (anc)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQSThreeStageLayout#selectSegmentAt (anc) :: "
                    + Log.getStackTraceString(t));
        }

        try {
            // 3c. 兜底: 蓝牙开关的落点(OplusBluetoothTile#handleClick 正是调它)。
            //     只要处于抑制窗口内就吞掉, 这样即便 handleClick 那层的拦截因签名/时机问题没生效,
            //     触摸也不会把蓝牙关掉。
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(CLS_BT_CONTROLLER_IMPL, cl), "setBluetoothEnabled",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (SystemClock.elapsedRealtime() >= sSuppressBtToggleUntil) return;
                                sSuppressBtToggleUntil = 0L;
                                param.setResult(null);
                                log("anc: bluetooth toggle suppressed");
                            } catch (Throwable t) {
                                log("anc: suppress fail: " + t);
                            }
                        }
                    });
            log("HOOK OK BluetoothControllerImpl#setBluetoothEnabled (anc)");
        } catch (Throwable t) {
            log("HOOK FAIL BluetoothControllerImpl#setBluetoothEnabled (anc) :: "
                    + Log.getStackTraceString(t));
        }

        try {
            // 3d. 提示文案: OplusSeparateClickTileManager#onThreeStageChange 按 threeStageMode
            //     硬编码取"已切换到响铃/振动/静音模式"。对蓝牙磁贴换成降噪模式名。
            //     这里只记下目标文案, 由下面 show 的 hook 真正替换 —— 因为文案是在方法体内
            //     从 resources 取的, 中途无法插手。
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(CLS_SEP_CLICK_MANAGER, cl), "onThreeStageChange",
                    Context.class,
                    XposedHelpers.findClass("com.android.systemui.plugins.qs.QSTile$State", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            sPendingToast = null;
                            try {
                                Object state = param.args[1];
                                if (state == null
                                        || !BT_SPEC.equals(XposedHelpers.getObjectField(state, "spec"))) {
                                    return;
                                }
                                if (!isAvailable()) return;
                                sPendingToast = ancToastText(
                                        XposedHelpers.getIntField(state, "threeStageMode"));
                            } catch (Throwable t) {
                                log("anc: three stage change fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusSeparateClickTileManager#onThreeStageChange (anc)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusSeparateClickTileManager#onThreeStageChange (anc) :: "
                    + Log.getStackTraceString(t));
        }

        try {
            // 3e. 真正替换提示文案。onThreeStageChange 里 show 是最后一步, 中间没有别的 show,
            //     故上面的 sPendingToast 只会被这一次调用消费掉。
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(CLS_TEMP_INFO_MANAGER, cl), "show",
                    String.class, String.class, String.class,
                    XposedHelpers.findClass(CLS_INFO_SHOW_STYLE, cl),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            String text = sPendingToast;
                            if (text == null) return;
                            sPendingToast = null;
                            param.args[0] = text;
                            log("anc: toast -> " + text);
                        }
                    });
            log("HOOK OK TemporarilyInfoManager#show (anc)");
        } catch (Throwable t) {
            log("HOOK FAIL TemporarilyInfoManager#show (anc) :: " + Log.getStackTraceString(t));
        }

        try {
            // 4. 音量条图标: 蓝牙路由下音量条底部图标原本是
            //    status_bar_qs_icon_volume_media_bt(_mute) —— 一只耳机, 只反映静音与否。
            //    有可控降噪耳机时换成降噪/通透图标, 反映真实降噪状态。
            //    放在 after: 系统的 setImageResource 已把耳机图标设好, 我们再覆盖图形,
            //    然后交回系统 updateIconColor 决定配色(见 applyVolumeIcon)。
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(CLS_VOLUME_BT_ICON_STATE, cl), "onVolumeStateChanged",
                    XposedHelpers.findClass(CLS_VOLUME_STATE, cl), new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Object view = XposedHelpers.getObjectField(param.thisObject, "this$0");
                                if (view == null) return;
                                sVolumeIconRef = new WeakReference<Object>(view);
                                // aboveThreshold: 音量条填充已漫过图标所在位置(距底 >= 20dp),
                                // 此时图标压在亮色激活区上, 需要深色才看得清。
                                Boolean above = (Boolean) XposedHelpers.getObjectField(
                                        param.thisObject, "aboveThreshold");
                                sVolumeAboveThreshold = Boolean.TRUE.equals(above);
                                applyVolumeIcon(view);
                            } catch (Throwable t) {
                                log("anc: volume icon fail: " + t);
                            }
                        }
                    });
            log("HOOK OK bluetoothRouteIconState#onVolumeStateChanged (anc)");
        } catch (Throwable t) {
            log("HOOK FAIL bluetoothRouteIconState#onVolumeStateChanged (anc) :: "
                    + Log.getStackTraceString(t));
        }

        try {
            // 5. 音量条点击: 原本是在"音量最小 <-> 上次音量"之间切换(即静音)。
            //    有可控降噪耳机时整段吞掉, 改为在降噪/通透之间切换。
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(CLS_VOLUME_ICON_CLICK, cl), "onClick",
                    View.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (!isAvailable()) return;
                                param.setResult(null);
                                log("anc: volume icon click -> toggle anc/transparent");
                                toggleAncTransparent();
                            } catch (Throwable t) {
                                log("anc: volume click fail: " + t);
                            }
                        }
                    });
            log("HOOK OK volumeIconClickListener#onClick (anc)");
        } catch (Throwable t) {
            log("HOOK FAIL volumeIconClickListener#onClick (anc) :: "
                    + Log.getStackTraceString(t));
        }

        try {
            // 3i. 图标位移减半: 系统三段式切换时给图标加 ±10% 段宽的位移
            //     (getIconTargetTranslationX: 左段激活时 LTR 返回 +width, 即整体右移)。
            //     蓝牙磁贴保留这个动效但收敛幅度, 取原值的一半。
            //     indicator 的位置是 calculateIndicatorTargetX(f), 其内部也叠加了同一个 f,
            //     故这里改小后滑块会同步收敛, 图标与滑块仍然对齐。
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(CLS_THREE_STAGE_LAYOUT, cl),
                    "getIconTargetTranslationX", int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (!Boolean.TRUE.equals(sAncLayouts.get(param.thisObject))) return;
                                Object result = param.getResult();
                                if (!(result instanceof Float)) return;
                                param.setResult(Float.valueOf(
                                        ((Float) result).floatValue() * ICON_OFFSET_SCALE));
                            } catch (Throwable t) {
                                log("anc: icon translation fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusQSThreeStageLayout#getIconTargetTranslationX (anc)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQSThreeStageLayout#getIconTargetTranslationX (anc) :: "
                    + Log.getStackTraceString(t));
        }

        try {
            // 4. 图标: 三段式布局的图标是响铃/振动/静音, 对蓝牙磁贴换成降噪/关闭/通透。
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(CLS_THREE_STAGE_ICON_VIEW, cl), "setIcon",
                    XposedHelpers.findClass("com.android.systemui.plugins.qs.QSTile$State", cl),
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Object state = param.args[0];
                                if (state == null
                                        || !BT_SPEC.equals(XposedHelpers.getObjectField(state, "spec"))) {
                                    return;
                                }
                                applyAncIcons(param.thisObject);
                            } catch (Throwable t) {
                                log("anc: set icon fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusQSThreeStageIconView#setIcon (anc)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQSThreeStageIconView#setIcon (anc) :: "
                    + Log.getStackTraceString(t));
        }

        try {
            // 5. 动画: 三段式切段时会播 ringermode/*.json, 对蓝牙磁贴跳过, 改为直接换图标配色。
            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(CLS_THREE_STAGE_LOTTIE, cl), "playLottieAnimation",
                    ImageView.class, String.class,
                    XposedHelpers.findClass("com.oplus.systemui.plugins.qs.customize.view.animation.threestage.ThreeStageIconAlphaSpringAnimator", cl),
                    int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                if (!Boolean.TRUE.equals(sAncLottieViews.get(param.thisObject))) return;
                                View view = (View) param.thisObject;
                                if (view.getParent() == null) return;
                                View layout = findThreeStageLayout(view);
                                if (layout == null) return;
                                int selected = XposedHelpers.getIntField(layout, "selectedIndex");
                                int previous = XposedHelpers.getIntField(layout, "preSelectedIndex");
                                if (previous >= 0 && previous != selected) {
                                    XposedHelpers.callMethod(layout, "tintIconColor", previous, false);
                                }
                                if (selected >= 0) {
                                    XposedHelpers.callMethod(layout, "tintIconColor", selected, true);
                                }
                                param.setResult(Boolean.TRUE);
                            } catch (Throwable t) {
                                log("anc: lottie hook fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusQSThreeStageLottieView#playLottieAnimation (anc)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQSThreeStageLottieView#playLottieAnimation (anc) :: "
                    + Log.getStackTraceString(t));
        }

        // 6. 状态源: 后台线程轮询欢律 + ContentObserver 近实时回显。
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Context ctx = null;
                for (int i = 0; i < 60 && ctx == null; i++) {
                    ctx = sAppContext != null ? sAppContext : currentApplication();
                    if (ctx == null) sleepQuietly(500L);
                }
                if (ctx == null) {
                    log("anc: systemui application not ready, skip");
                    return;
                }
                try {
                    init(ctx);
                } catch (Throwable t2) {
                    log("anc init fail: " + t2);
                }
            }
        }, "ColorOSMod-Anc");
        t.setDaemon(true);
        t.start();
    }

    private static void init(Context sysuiCtx) {
        final Context ctx = sysuiCtx.getApplicationContext();
        sContext = ctx;
        try {
            sRes = ctx.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                    .getResources();
        } catch (Throwable t) {
            log("anc: module resources unavailable: " + t);
            return;
        }
        sMain = new Handler(Looper.getMainLooper());
        sWorker = Executors.newSingleThreadExecutor();

        Resources sysuiRes = ctx.getResources();
        sIconIds = idsOf(sysuiRes, ICON_ID_NAMES);
        sLottieIds = idsOf(sysuiRes, LOTTIE_ID_NAMES);

        try {
            ctx.getContentResolver().registerContentObserver(
                    Uri.parse("content://" + MELODY_AUTHORITY), true,
                    new ContentObserver(sMain) {
                        @Override
                        public void onChange(boolean selfChange, Uri uri) {
                            submit(new Runnable() {
                                @Override
                                public void run() {
                                    refresh();
                                }
                            });
                        }
                    });
        } catch (Throwable t) {
            log("anc: register observer fail: " + t);
        }

        log("anc: inited");
        while (true) {
            try {
                refresh();
            } catch (Throwable t) {
                log("anc tick error: " + t);
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
    }

    private static int[] idsOf(Resources res, String[] names) {
        int[] out = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = res.getIdentifier(names[i], "id", SYSUI_PACKAGE);
        }
        return out;
    }

    /** 三段式形态是否可用: 开关已开、当前耳机支持降噪/关闭/通透三种模式。 */
    private static boolean isAvailable() {
        return readBool(KEY_ANC_TILE_ENABLED, false) && sState != null;
    }

    private static void submit(Runnable r) {
        ExecutorService w = sWorker;
        if (w != null) w.execute(r);
    }

    /** 拉一次状态, 变化时才通知蓝牙磁贴刷新。 */
    private static void refresh() {
        if (!readBool(KEY_ANC_TILE_ENABLED, false)) {
            if (sState != null) {
                sState = null;
                notifyTile();
            }
            return;
        }
        State st = queryState();
        State old = sState;
        if (old != null && st != null && st.signature().equals(old.signature())) return;
        sState = st;
        // 回显已确认到目标, 乐观值可以撤掉(之后的显示回到真实状态)。
        Integer target = sTargetType;
        if (target != null && st != null && st.type == target.intValue()) {
            sTargetType = null;
        }
        log("anc: state -> " + describe(st) + " target=" + sTargetType);
        notifyTile();
        notifyVolumeIcon();
    }

    /** 降噪状态变了就刷新音量条图标(欢律的 notifyChange 不一定带动音量状态刷新)。 */
    private static void notifyVolumeIcon() {
        final Object view = sVolumeIconRef.get();
        if (view == null) return;
        sMain.post(new Runnable() {
            @Override
            public void run() {
                applyVolumeIcon(view);
            }
        });
    }

    // mapper 在主线程跑, 缓存未建立时当场同步查一次欢律(一次 IPC, 几十 ms)。
    // 后台轮询还没跑完就展开控制中心时靠它拿到形态判定所需的状态。
    private static void syncQueryOnce() {
        if (sContext == null) return;
        long now = System.currentTimeMillis();
        if (now - sLastSyncQueryMs < SYNC_QUERY_THROTTLE_MS) return;
        sLastSyncQueryMs = now;
        State st = queryState();
        if (st != null) {
            sState = st;
            log("anc: sync query -> " + describe(st));
        } else {
            log("anc: sync query -> null");
        }
    }

    private static String describe(State st) {
        return st == null ? "null" : (st.name + "/" + st.type + "/" + st.supports);
    }

    private static void notifyTile() {
        final Object tile = sTileRef.get();
        if (tile == null) return;
        sMain.post(new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(tile, "refreshState", (Object) null);
                } catch (Throwable t) {
                    log("anc: refresh tile fail: " + t);
                }
            }
        });
    }

    /**
     * 把首次布局产生的图标位移收敛到 ICON_OFFSET_SCALE。首次布局早于 sAncLayouts 登记,
     * 那时 getIconTargetTranslationX 的 hook 还没生效, 故这里补一次。
     * 走 XposedHelpers.callMethod 调用, hook 已登记, 拿到的就是收敛后的值。
     */
    private static void fixIconOffset(final Object layout) {
        ((View) layout).post(new Runnable() {
            @Override
            public void run() {
                try {
                    int selected = XposedHelpers.getIntField(layout, "selectedIndex");
                    List<View> frames = (List<View>) XposedHelpers.getObjectField(
                            layout, "frameLayoutViews");
                    Float selectedOffset = null;
                    if (frames != null) {
                        for (int i = 0; i < frames.size(); i++) {
                            View frame = frames.get(i);
                            if (frame == null) continue;
                            Float tx = (Float) XposedHelpers.callMethod(layout,
                                    "getIconTargetTranslationX",
                                    Integer.valueOf(i), Integer.valueOf(selected));
                            if (tx == null) continue;
                            frame.setTranslationX(tx.floatValue());
                            if (i == selected) selectedOffset = tx;
                        }
                    }
                    View indicator = (View) XposedHelpers.getObjectField(layout, "indicatorView");
                    if (indicator != null && selectedOffset != null) {
                        indicator.setTranslationX(((Float) XposedHelpers.callMethod(layout,
                                "calculateIndicatorTargetX", selectedOffset)).floatValue());
                    }
                } catch (Throwable t) {
                    log("anc: fix icon offset fail: " + t);
                }
            }
        });
    }

    // ---- 音量条图标 ----

    /**
     * 把音量条底部图标换成当前降噪状态对应的图标(降噪/通透)。
     * 图标矢量沿用欢律 melody_ui_reduction_noise_*, 与欢律详情页一致。
     * 配色交回系统: OplusQsVolumeIconView#updateIconColor(isMute, aboveThreshold) 内部是
     * `z2 ? getUnMuteIconColor() : getMuteIconColor()` 再 setColorFilter —— 第一个参数实际
     * 未参与着色, 只有 aboveThreshold 决定深浅。故换完图形后调它, 图标被音量条激活部分
     * 遮盖时自然变深色, 与系统耳机图标的观感一致。
     */
    private static void applyVolumeIcon(Object view) {
        Resources res = sRes;
        if (res == null || view == null) return;
        if (!isAvailable()) return;
        State st = sState;
        if (st == null) return;
        boolean transparent = isTransparentMode(displayType());
        try {
            Drawable d = res.getDrawable(transparent ? R.drawable.anc_icon_transparent
                    : R.drawable.anc_icon_noise, null).mutate();
            XposedHelpers.callMethod(view, "setImageDrawable", d);
            XposedHelpers.callMethod(view, "updateIconColor",
                    Boolean.FALSE, Boolean.valueOf(sVolumeAboveThreshold));
            log("anc: volume icon -> " + (transparent ? "transparent" : "noise")
                    + " aboveThreshold=" + sVolumeAboveThreshold);
        } catch (Throwable t) {
            log("anc: apply volume icon fail: " + t);
        }
    }

    /** 当前是否是通透(含通透人声)模式。 */
    private static boolean isTransparentMode(int type) {
        return type == 2 || type == 6;
    }

    /** 降噪 <-> 通透 二态切换(目标按乐观值算, 故连点能正确在两者间来回)。 */
    private static void toggleAncTransparent() {
        State st = sState;
        if (st == null) return;
        int current = displayType();
        int target;
        if (isTransparentMode(current)) {
            target = typeOfSlot(0, st.supports);   // 切回降噪
        } else {
            target = contains(st.supports, 2) ? 2 : (contains(st.supports, 6) ? 6 : 0);  // 切到通透
        }
        if (target <= 0) {
            log("anc: toggle target unsupported, type=" + current
                    + " supports=" + st.supports);
            return;
        }
        requestType(target);
    }

    /** 点击某一段后切换模式: slot 为段索引(0=降噪 1=关闭 2=通透)。 */
    private static void applySegment(final int slot) {
        State st = sState;
        if (st == null) return;
        int type = typeOfSlot(slot, st.supports);
        if (type <= 0) return;
        requestType(type);
    }

    // ---- 切换的乐观更新与串行兑现 ----

    /** 当前应当显示的模式类型: 有待兑现的切换时用乐观值, 否则用真实回显值。 */
    private static int displayType() {
        Integer t = sTargetType;
        if (t != null) return t.intValue();
        State st = sState;
        return st == null ? -1 : st.type;
    }

    /**
     * 请求切到指定模式。立即按乐观值刷新 UI, 再交给协调任务串行下发。
     * 快速连点不会互相丢弃: 每次点击只是改写 sTargetType, 协调循环会依次把耳机
     * 兑现到最新目标 —— 即"先切过去, 再切回来"。
     */
    private static void requestType(final int target) {
        State st = sState;
        if (st == null) return;
        sTargetType = Integer.valueOf(target);
        postUiRefresh();
        if (sSyncing) return;   // 已有协调任务在跑, 它会取到新目标
        sSyncing = true;
        submit(new Runnable() {
            @Override
            public void run() {
                try {
                    syncLoop();
                } finally {
                    sSyncing = false;
                    // 循环刚退出时若又来了新目标, 重新起一个任务, 避免丢失最后一次点击。
                    Integer pending = sTargetType;
                    if (pending != null) requestType(pending.intValue());
                }
                postUiRefresh();
            }
        });
    }

    /** 反复把耳机切到最新目标直到达成; 目标中途被改写就接着切, 从而实现排队。 */
    private static void syncLoop() {
        for (int attempt = 0; attempt < MAX_SYNC_ATTEMPTS; attempt++) {
            Integer target = sTargetType;
            if (target == null) return;
            State cur = queryState();
            if (cur != null) sState = cur;
            if (cur != null && cur.type == target.intValue()) {
                sTargetType = null;     // 回显已确认, 乐观值兑现
                return;
            }
            if (cur == null) {
                log("anc: sync lost device, target=" + target);
                sTargetType = null;
                return;
            }
            setNoiseReduction(cur.name, cur.address, target.intValue());
            sleepQuietly(REFRESH_DELAYS[0]);
        }
        // 多次仍未达成, 放弃乐观值, 交回真实状态
        log("anc: sync give up");
        sTargetType = null;
        refresh();
    }

    /** 主线程刷新受本功能影响的 UI: 音量条图标 + 蓝牙磁贴。 */
    private static void postUiRefresh() {
        sMain.post(new Runnable() {
            @Override
            public void run() {
                Object view = sVolumeIconRef.get();
                if (view != null) applyVolumeIcon(view);
                Object tile = sTileRef.get();
                if (tile != null) {
                    try {
                        XposedHelpers.callMethod(tile, "refreshState", (Object) null);
                    } catch (Throwable t) {
                        log("anc: refresh tile fail: " + t);
                    }
                }
            }
        });
    }

    // 先查当前活动耳机, 再用它的 MAC 查降噪模式。注意 provider 对 selection 是精确匹配:
    // 必须是 "address" 且 selectionArgs[0] 为 MAC, 否则直接返回 null(见 EarphoneControlProvider#query)。
    private static State queryState() {
        if (sContext == null) return null;
        ContentResolver cr = sContext.getContentResolver();
        String name = null;
        String addr = null;
        Cursor c = cr.query(Uri.parse("content://" + MELODY_AUTHORITY + "/" + METHOD_ACTIVE_DEVICE),
                null, null, null, null);
        if (c == null) {
            log("anc: active device cursor null");
            return null;
        }
        try {
            int ni = c.getColumnIndex(COLUMN_NAME);
            int ai = c.getColumnIndex(COLUMN_ADDR);
            if (ni < 0 || ai < 0 || !c.moveToFirst()) {
                log("anc: active device empty");
                return null;
            }
            name = c.getString(ni);
            addr = c.getString(ai);
        } finally {
            c.close();
        }
        if (name == null || addr == null) return null;

        Cursor c2 = cr.query(
                Uri.parse("content://" + MELODY_AUTHORITY + "/" + METHOD_NOISE_REDUCTION),
                null, COLUMN_ADDR, new String[]{addr}, null);
        if (c2 == null) {
            log("anc: noise cursor null for " + name);
            return null;
        }
        State st = new State();
        st.name = name;
        st.address = addr;
        try {
            int ti = c2.getColumnIndex(COLUMN_TYPE);
            int si = c2.getColumnIndex(COLUMN_SUPPORTS);
            if (!c2.moveToFirst()) {
                log("anc: noise cursor empty for " + name);
                return null;
            }
            st.type = ti >= 0 ? c2.getInt(ti) : -1;
            if (si >= 0) st.supports = parseSupports(c2.getString(si));
        } finally {
            c2.close();
        }
        // 三段式要求"降噪/关闭/通透"三档都在支持列表里, 缺一档就维持普通蓝牙开关形态。
        if (typeOfSlot(0, st.supports) <= 0 || typeOfSlot(1, st.supports) <= 0
                || typeOfSlot(2, st.supports) <= 0) {
            log("anc: unsupported segments, type=" + st.type + " supports=" + st.supports);
            return null;
        }
        return st;
    }

    /** supports 列是 Gson 序列化的整数数组("[5,1,2]"), 解析失败给空列表。 */
    private static List<Integer> parseSupports(String raw) {
        List<Integer> out = new ArrayList<Integer>();
        if (raw == null) return out;
        try {
            String s = raw.trim();
            if (!s.startsWith("[")) s = "[" + s + "]";
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) out.add(arr.optInt(i, -1));
        } catch (Throwable t) {
            log("anc: parse supports fail: " + raw + " -> " + t);
        }
        return out;
    }

    /** 段索引 -> 该段实际要下发的欢律 mode type; 不支持返回 0。 */
    private static int typeOfSlot(int slot, List<Integer> supports) {
        if (slot == 1) return contains(supports, 1) ? 1 : 0;
        if (slot == 2) return contains(supports, 2) ? 2 : 0;
        for (int t : SLOT_ANC) {
            if (contains(supports, t)) return t;
        }
        return 0;
    }

    /** 当前 mode type -> threeStageMode 段值(左=2 中=1 右=0)。 */
    private static int stageOfType(int type) {
        if (type == 1) return 1;
        if (type == 2) return 0;
        return 2;
    }

    private static boolean contains(List<Integer> supports, int type) {
        return supports.contains(Integer.valueOf(type));
    }

    /**
     * 切段提示的文案。段值(左=2 中=1 右=0)先换算成段索引, 再取该段在当前耳机上
     * 实际对应的模式名 —— 这样支持"深度降噪/轻度降噪/自适应"的耳机会显示真实档位名,
     * 而不是笼统的"降噪"。文案沿用系统的"已切换到X模式"句式。
     */
    private static String ancToastText(int stage) {
        State st = sState;
        if (st == null || stage < 0 || stage > 2) return null;
        int type = typeOfSlot(STAGE_TO_SLOT[stage], st.supports);
        String label = labelForType(type);
        return label == null ? null : "已切换到" + label + "模式";
    }

    private static String labelForType(int type) {
        switch (type) {
            case 1:
                return "关闭";
            case 2:
                return "通透";
            case 3:
                return "轻度降噪";
            case 4:
                return "深度降噪";
            case 5:
                return "降噪";
            case 10:
                return "自适应";
            default:
                return null;
        }
    }

    private static void setNoiseReduction(String name, String addr, int type) {
        try {
            Bundle b = new Bundle();
            b.putString(COLUMN_NAME, name == null ? "" : name);
            b.putString(COLUMN_ADDR, addr);
            b.putInt(COLUMN_TYPE, type);
            sContext.getContentResolver().call(
                    Uri.parse("content://" + MELODY_AUTHORITY + "/" + METHOD_NOISE_REDUCTION),
                    METHOD_NOISE_REDUCTION, null, b);
            log("anc: set noise reduction type=" + type);
        } catch (Throwable t) {
            log("anc: set noise reduction fail: " + t);
        }
    }

    // ---- 三段式图标替换 ----

    /** 把三段式布局(iconView 宿主)里的响铃/振动/静音换成降噪/关闭/通透, 并登记其 lottie 视图。 */
    private static void applyAncIcons(Object iconViewHost) {
        Object segmentLayout = XposedHelpers.callMethod(iconViewHost, "getSegmentLayout");
        if (segmentLayout == null) return;
        View layout = (View) segmentLayout;
        // 登记实例, 让 selectSegmentAt / getIconTargetTranslationX 的 hook 只认这一段
        // 是蓝牙磁贴的三段式布局。
        sAncLayouts.put(layout, Boolean.TRUE);
        fixIconOffset(layout);
        int[] iconIds = sIconIds;
        int[] lottieIds = sLottieIds;
        if (iconIds == null || lottieIds == null) return;
        for (int i = 0; i < 3; i++) {
            ImageView icon = (ImageView) layout.findViewById(iconIds[i]);
            if (icon != null) {
                Drawable d = ancDrawable(i);
                // 只换图形不碰 tint: 颜色由 OplusQSThreeStageLayout#tintIconColor 按选中态下发,
                // 沿用静音磁贴那套取值, 无需我们自己算色。
                if (d != null) icon.setImageDrawable(d);
            }
            View lottie = layout.findViewById(lottieIds[i]);
            if (lottie != null) {
                lottie.setVisibility(View.GONE);
                sAncLottieViews.put(lottie, Boolean.TRUE);
            }
        }
    }

    private static Drawable ancDrawable(int slot) {
        Resources res = sRes;
        if (res == null) return null;
        int id;
        switch (slot) {
            case 0:
                id = R.drawable.anc_icon_noise;
                break;
            case 1:
                id = R.drawable.anc_icon_off;
                break;
            default:
                id = R.drawable.anc_icon_transparent;
                break;
        }
        try {
            return res.getDrawable(id, null).mutate();
        } catch (Throwable t) {
            log("anc: load icon fail: " + t);
            return null;
        }
    }

    /** 从某个 lottie 视图向上找到所属的 OplusQSThreeStageLayout。 */
    private static View findThreeStageLayout(View v) {
        try {
            Class<?> cls = XposedHelpers.findClass(CLS_THREE_STAGE_LAYOUT, v.getContext()
                    .getClassLoader());
            View p = (View) v.getParent();
            while (p != null) {
                if (cls.isInstance(p)) return p;
                p = p.getParent() instanceof View ? (View) p.getParent() : null;
            }
        } catch (Throwable t) {
            log("anc: find layout fail: " + t);
        }
        return null;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
