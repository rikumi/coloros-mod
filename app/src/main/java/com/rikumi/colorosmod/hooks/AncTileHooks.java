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

    private static final String CLS_VOLUME_BT_ICON_STATE =
            "com.oplus.systemui.qs.base.seek.OplusQsVolumeIconView$bluetoothRouteIconState$1";
    // 音量条图标的点击监听器(原本是"音量切到最小/恢复", 即静音切换)。
    private static final String CLS_VOLUME_ICON_CLICK =
            "com.oplus.systemui.qs.base.seek.OplusQsVolumeSliderController$volumeIconClickListener$1";
    private static final String CLS_VOLUME_STATE =
            "com.android.systemui.plugins.qs.QSVolume$VolumeState";

    private static final String[] ICON_ID_NAMES = {
            "left_icon_view", "middle_icon_view", "right_icon_view"};
    private static final String[] LOTTIE_ID_NAMES = {"left_lottie", "middle_lottie", "right_lottie"};

    private static final int[] SLOT_ANC = {5, 4, 3, 10};

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

    private static final class State {

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
    private static volatile ContentObserver sObserver;

    private static volatile boolean sObserverDetachedForHotReload;
    private static final int OBSERVER_ROLLBACK_MAX_ATTEMPTS = 4;
    private static final long OBSERVER_ROLLBACK_BASE_DELAY_MS = 250L;
    private static int sObserverRollbackAttempts;
    private static Runnable sObserverRollbackRetry;
    private static volatile Thread sPollThread;
    private static volatile boolean sActive;
    private static volatile int[] sIconIds;
    private static volatile int[] sLottieIds;

    private static volatile State sState = null;

    private static volatile WeakReference<Object> sTileRef = new WeakReference<Object>(null);

    private static final Map<Object, Boolean> sAncLottieViews = new WeakHashMap<Object, Boolean>();

    private static final Map<Object, Boolean> sAncLayouts = new WeakHashMap<Object, Boolean>();

    private static volatile long sSuppressBtToggleUntil = 0L;

    private static volatile String sPendingToast = null;

    private static volatile WeakReference<Object> sVolumeIconRef = new WeakReference<Object>(null);

    private static volatile boolean sVolumeAboveThreshold = false;

    private static volatile Integer sTargetType = null;

    private static volatile boolean sSyncing = false;
    private static volatile long sLastSyncQueryMs = 0L;

    // ---- 注入 ----

    public static void hookAncTile(final XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        log("anc: hooking in " + lpparam.packageName);
        try {

            XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClass(CLS_VOLUME_BT_ICON_STATE, cl), "onVolumeStateChanged",
                    XposedHelpers.findClass(CLS_VOLUME_STATE, cl), new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Object view = XposedHelpers.getObjectField(param.thisObject, "this$0");
                                if (view == null) return;
                                sVolumeIconRef = new WeakReference<Object>(view);

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

        // 6. 状态源: 后台线程轮询欢律 + ContentObserver 近实时回显。
        startInitThread();
    }

    private static synchronized void startInitThread() {
        Thread current = sPollThread;
        if (current != null && current.isAlive()) return;
        sActive = true;
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                Context ctx = null;
                for (int i = 0; i < 60 && ctx == null && sActive; i++) {
                    ctx = sAppContext != null ? sAppContext : currentApplication();
                    if (ctx == null) sleepQuietly(500L);
                }
                if (!sActive) return;
                if (ctx == null) {
                    log("anc: systemui application not ready, skip");
                    return;
                }
                try { init(ctx); }
                catch (Throwable t) { log("anc init fail: " + t); }
            }
        }, "ColorOSMod-Anc");
        thread.setDaemon(true);
        sPollThread = thread;
        thread.start();
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
            ContentObserver observer = new ContentObserver(sMain) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    submit(new Runnable() {
                        @Override public void run() { refresh(); }
                    });
                }
            };
            ctx.getContentResolver().registerContentObserver(
                    Uri.parse("content://" + MELODY_AUTHORITY), true, observer);
            sObserver = observer;
        } catch (Throwable t) {
            log("anc: register observer fail: " + t);
        }

        log("anc: inited");
        while (sActive) {
            try {
                refresh();
            } catch (Throwable t) {
                log("anc tick error: " + t);
            }
            if (!sActive) break;
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ignored) {

                if (!sActive) break;
            }
        }
    }

    public static boolean canHotReload() {

        if (sObserverDetachedForHotReload) {
            log("anc observer rollback still pending, rejecting reload");
            return false;
        }
        sActive = false;
        Thread poll = sPollThread;
        if (poll != null && poll.isAlive()) {
            poll.interrupt();
            try {
                poll.join(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelHotReloadPreflight();
                return false;
            }
            if (poll.isAlive()) {
                cancelHotReloadPreflight();
                return false;
            }
        }

        ExecutorService worker = sWorker;
        if (worker != null && !worker.isShutdown()) {
            java.util.concurrent.CountDownLatch idle = new java.util.concurrent.CountDownLatch(1);
            try {
                worker.execute(idle::countDown);
                if (!idle.await(1500L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    cancelHotReloadPreflight();
                    return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelHotReloadPreflight();
                return false;
            } catch (Throwable t) {
                // A concurrently stopped executor is already quiescent; other failures reject.
                if (!worker.isShutdown()) {
                    cancelHotReloadPreflight();
                    return false;
                }
            }
        }
        ContentObserver observer = sObserver;
        Context context = sContext;
        if (observer != null && context != null && !sObserverDetachedForHotReload) {
            try {
                context.getContentResolver().unregisterContentObserver(observer);
                sObserverDetachedForHotReload = true;
            } catch (Throwable t) {
                log("anc hot reload observer preflight failed: " + t);
                cancelHotReloadPreflight();
                return false;
            }
        }
        return true;
    }

    public static synchronized void cancelHotReloadPreflight() {
        restoreObserverAfterHotReloadPreflight();
        if (sActive) return;
        sActive = true;
        Thread current = sPollThread;
        if (current != null && current.isAlive()) return;
        if (sContext == null || sWorker == null || sMain == null) {
            // Preflight may happen before initialization reached init(); full init is safe only then.
            startInitThread();
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                while (sActive) {
                    try { refresh(); }
                    catch (Throwable t) { log("anc tick error: " + t); }
                    if (!sActive) break;
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException ignored) {
                        if (!sActive) break;
                    }
                }
            }
        }, "ColorOSMod-Anc");
        thread.setDaemon(true);
        sPollThread = thread;
        thread.start();
    }

    private static void restoreObserverAfterHotReloadPreflight() {
        if (!sObserverDetachedForHotReload) {
            cancelObserverRollbackRetry();
            return;
        }
        ContentObserver observer = sObserver;
        Context context = sContext;
        try {
            if (observer == null || context == null) {
                throw new IllegalStateException("observer rollback target unavailable");
            }
            context.getContentResolver().registerContentObserver(
                    Uri.parse("content://" + MELODY_AUTHORITY), true, observer);
            sObserverDetachedForHotReload = false;
            cancelObserverRollbackRetry();
        } catch (Throwable t) {
            log("anc hot reload observer rollback failed: " + t);
            scheduleObserverRollbackRetry();
        }
    }

    private static void scheduleObserverRollbackRetry() {
        if (!sObserverDetachedForHotReload || sObserverRollbackRetry != null
                || sObserverRollbackAttempts >= OBSERVER_ROLLBACK_MAX_ATTEMPTS) return;
        Handler main = sMain;
        if (main == null) return;
        final int attempt = ++sObserverRollbackAttempts;
        Runnable retry = new Runnable() {
            @Override public void run() {
                synchronized (AncTileHooks.class) {
                    if (sObserverRollbackRetry != this) return;
                    sObserverRollbackRetry = null;
                    restoreObserverAfterHotReloadPreflight();
                }
            }
        };
        sObserverRollbackRetry = retry;
        long delay = OBSERVER_ROLLBACK_BASE_DELAY_MS << (attempt - 1);
        if (!main.postDelayed(retry, delay)) {
            sObserverRollbackRetry = null;
            log("anc hot reload observer rollback retry post failed");
        }
    }

    private static void cancelObserverRollbackRetry() {
        Runnable retry = sObserverRollbackRetry;
        Handler main = sMain;
        if (retry != null && main != null) main.removeCallbacks(retry);
        sObserverRollbackRetry = null;
        sObserverRollbackAttempts = 0;
    }

    public static Object captureHotReloadHosts() {
        State current = sState;
        Object state = current == null ? null : new Object[] {
                current.name, current.address, Integer.valueOf(current.type),
                current.supports.toArray(new Integer[0])
        };
        return new Object[] {
                null, sVolumeIconRef.get(), Boolean.valueOf(sVolumeAboveThreshold),
                new Object[0], new Object[0], state,
                sTargetType
        };
    }

    public static void restoreHotReloadHosts(Object saved) {
        if (!(saved instanceof Object[])) return;
        Object[] state = (Object[]) saved;
        if (state.length < 5) return;
        sTileRef = new WeakReference<Object>(null);
        sVolumeIconRef = new WeakReference<Object>(state[1]);
        if (state[2] instanceof Boolean) sVolumeAboveThreshold = (Boolean) state[2];
        if (state.length > 5 && state[5] instanceof Object[]) {
            Object[] savedState = (Object[]) state[5];
            if (savedState.length >= 4 && savedState[2] instanceof Integer
                    && savedState[3] instanceof Object[]) {
                State restored = new State();
                restored.name = savedState[0] instanceof String ? (String) savedState[0] : null;
                restored.address = savedState[1] instanceof String ? (String) savedState[1] : null;
                restored.type = ((Integer) savedState[2]).intValue();
                for (Object supported : (Object[]) savedState[3]) {
                    if (supported instanceof Integer) restored.supports.add((Integer) supported);
                }
                sState = restored;
            }
        }
        if (state.length > 6 && state[6] instanceof Integer) {
            sTargetType = (Integer) state[6];
        }
        Handler main = sMain;
        if (main != null) postUiRefresh();
    }

    public static synchronized void cleanupForHotReload() {
        sActive = false;
        cancelObserverRollbackRetry();
        // The provider observer was detached during the rollback-safe preflight.
        sObserverDetachedForHotReload = false;
        ExecutorService worker = sWorker;
        if (worker != null) {
            try { worker.shutdownNow(); }
            catch (Throwable t) { log("anc hot reload worker cleanup failed: " + t); }
        }
        sPollThread = null;
        sObserver = null;
        sWorker = null;
        if (sMain != null) {
            try { sMain.removeCallbacksAndMessages(null); }
            catch (Throwable t) { log("anc hot reload main callback cleanup failed: " + t); }
        }
        sMain = null;
        sContext = null;
        sRes = null;
        sState = null;
        sTargetType = null;
        sSyncing = false;
        sTileRef = new WeakReference<Object>(null);
        sVolumeIconRef = new WeakReference<Object>(null);
        sAncLottieViews.clear();
        sAncLayouts.clear();
    }

    private static int[] idsOf(Resources res, String[] names) {
        int[] out = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = res.getIdentifier(names[i], "id", SYSUI_PACKAGE);
        }
        return out;
    }

    private static boolean isAvailable() {
        return readBool(KEY_ANC_TILE_ENABLED, false) && sState != null;
    }

    private static void submit(Runnable r) {
        ExecutorService w = sWorker;
        if (sActive && w != null && !w.isShutdown()) w.execute(r);
    }

    private static void refresh() {
        if (!readBool(KEY_ANC_TILE_ENABLED, false)) {
            if (sState != null) {
                sState = null;
                notifyVolumeIcon();
            }
            return;
        }
        State st = queryState();
        State old = sState;
        if (old != null && st != null && st.signature().equals(old.signature())) return;
        sState = st;

        Integer target = sTargetType;
        if (target != null && st != null && st.type == target.intValue()) {
            sTargetType = null;
        }
        log("anc: state -> " + describe(st) + " target=" + sTargetType);
        notifyVolumeIcon();
    }

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

    private static boolean isTransparentMode(int type) {
        return type == 2 || type == 6;
    }

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

    private static void applySegment(final int slot) {
        State st = sState;
        if (st == null) return;
        int type = typeOfSlot(slot, st.supports);
        if (type <= 0) return;
        requestType(type);
    }

    // ---- 切换的乐观更新与串行兑现 ----

    private static int displayType() {
        Integer t = sTargetType;
        if (t != null) return t.intValue();
        State st = sState;
        return st == null ? -1 : st.type;
    }

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

    private static void syncLoop() {
        for (int attempt = 0; attempt < MAX_SYNC_ATTEMPTS; attempt++) {
            Integer target = sTargetType;
            if (target == null) return;
            State cur = queryState();
            if (cur != null) sState = cur;
            if (cur != null && cur.type == target.intValue()) {
                sTargetType = null;
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

    private static void postUiRefresh() {
        sMain.post(new Runnable() {
            @Override
            public void run() {
                Object view = sVolumeIconRef.get();
                if (view != null) applyVolumeIcon(view);

            }
        });
    }

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

    private static int typeOfSlot(int slot, List<Integer> supports) {
        if (slot == 1) return contains(supports, 1) ? 1 : 0;
        if (slot == 2) return contains(supports, 2) ? 2 : 0;
        for (int t : SLOT_ANC) {
            if (contains(supports, t)) return t;
        }
        return 0;
    }

    private static int stageOfType(int type) {
        if (type == 1) return 1;
        if (type == 2) return 0;
        return 2;
    }

    private static boolean contains(List<Integer> supports, int type) {
        return supports.contains(Integer.valueOf(type));
    }

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

    private static void applyAncIcons(Object iconViewHost) {
        Object segmentLayout = XposedHelpers.callMethod(iconViewHost, "getSegmentLayout");
        if (segmentLayout == null) return;
        View layout = (View) segmentLayout;

        sAncLayouts.put(layout, Boolean.TRUE);
        fixIconOffset(layout);
        int[] iconIds = sIconIds;
        int[] lottieIds = sLottieIds;
        if (iconIds == null || lottieIds == null) return;
        for (int i = 0; i < 3; i++) {
            ImageView icon = (ImageView) layout.findViewById(iconIds[i]);
            if (icon != null) {
                Drawable d = ancDrawable(i);

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
            Thread.currentThread().interrupt();
        }
    }
}
