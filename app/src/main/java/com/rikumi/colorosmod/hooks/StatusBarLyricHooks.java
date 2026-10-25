package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.Context;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;

public final class StatusBarLyricHooks {

    // ---- ColorOS 歌词接口常量(与 OplusMediaLyricData 一致) ----
    private static final String METADATA_KEY_LYRIC_INFO = "lyricInfo";
    private static final String LYRIC_INFO_KEY_LYRIC = "lyric";
    // ---- 解析用常量(与 LyricParser 一致) ----
    private static final String LYRIC_CONTEXT = "c";
    private static final String LYRIC_TEXT = "tx";
    private static final String LYRIC_TIME = "t";

    private static final int STATE_PLAYING = 3;

    private static final long TICK_INTERVAL_MS = 250L;

    private static final long TRACK_LYRIC_SETTLE_MS = 5000L;

    private static final String CLS_PHONE_STATUS_BAR_VIEW =
            "com.android.systemui.statusbar.phone.PhoneStatusBarView";

    private static final String[] HIDE_VIEW_IDS = {"clock"};

    private static final int MEDIUM_WEIGHT = 500;

    private static final float LYRIC_VERTICAL_OFFSET_DP = 1f;

    private static final int LYRIC_MARGIN_START_DP = 6;

    private static final int LYRIC_GAP_BEFORE_FLUID_DP = 4;

    private static final long SCROLL_START_DELAY_MS = 500L;

    private static final float SCROLL_SPEED_PX_PER_MS = 0.1f;

    private static final float SCROLL_SPEED_ASCII_FACTOR = 2f;

    private static FrameLayout sLyricView = null;

    private static TextView sOutgoingView = null;

    private static TextView sIncomingView = null;

    private static android.animation.ValueAnimator sScrollAnimator = null;

    private static android.animation.AnimatorSet sSwitchAnimator = null;

    private static int sLyricMaxWidthPx = 0;

    private static volatile CharSequence sCurrentText = null;

    private static float sScrollTargetX = 0f;
    private static View[] sHideViews = null;
    private static int[] sHideOrigVisibility = null;
    private static boolean sHiddenByUs = false;

    private static boolean sHiddenByThirdParty = false;

    private static View sStatusBarRoot = null;

    private static volatile View sClockView = null;

    private static int sClockDesiredVisibility = View.VISIBLE;

    private static final Set<Class<?>> sVisibilityHooked = new HashSet<>();

    // ---- 下拉通知栏/控制中心时隐藏歌词: 复用 QsHooks#hookQsTopMargin 同一个展开进度回调 ----

    private static boolean sPanelExpanded = false;

    private static boolean sNumberModeOn = false;

    private static volatile Handler sMainHandler = null;
    private static boolean sThirdPartyMonitorStarted = false;
    private static volatile boolean sGenerationActive = true;

    private static final long THIRD_PARTY_MONITOR_INTERVAL_MS = 5000L;
    private static boolean sThirdPartyObserverRegistered = false;
    private static ContentObserver sThirdPartyWindowObserver = null;
    private static volatile MediaSessionManager sSessionManager = null;
    private static volatile boolean sMediaInited = false;

    private static volatile boolean sSessionListenerRegistered = false;
    private static final Set<MediaController> sRegistered = new HashSet<>();
    private static volatile long sSessionListenerGeneration = 0L;
    private static volatile MediaSessionManager.OnActiveSessionsChangedListener sSessionListener =
            createSessionListener(sSessionListenerGeneration);
    private static volatile boolean sListenersPreparedForHotReload = false;
    private static boolean sSessionListenerDetachedForHotReload = false;
    private static boolean sThirdPartyObserverDetachedForHotReload = false;
    private static Context sThirdPartyObserverContextForHotReload = null;
    private static Runnable sHotReloadRollbackRetry = null;
    private static int sHotReloadRollbackAttempts = 0;
    private static final int HOT_RELOAD_ROLLBACK_MAX_ATTEMPTS = 5;
    private static final long HOT_RELOAD_ROLLBACK_INITIAL_DELAY_MS = 250L;
    private static final long HOT_RELOAD_ROLLBACK_MAX_DELAY_MS = 4000L;
    private static final List<MediaController> sControllersDetachedForHotReload =
            new ArrayList<>();

    private static volatile MediaController sController = null;

    private static List<Line> sLines = null;

    private static String sLyricSource = null;
    private static boolean sTicking = false;
    private static Runnable sLyricTick;
    private static String sTrackIdentity;
    private static boolean sCloudLyricWidthReserved;
    private static boolean sCloudLyricTrackPending;
    private static long sCloudLyricTrackChangedAt;
    private static int sLastLyricSettings = -1;

    private static final class LyricClipLayout extends FrameLayout {
        LyricClipLayout(Context context) {
            super(context);
            setClipChildren(true);
            setClipToPadding(true);
            setClickable(false);
            setFocusable(false);
            setFocusableInTouchMode(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void dispatchDraw(android.graphics.Canvas canvas) {
            int save = canvas.save();
            // 裁到 padding 盒子(无 padding 即整个边界), 歌词向左滚出/向右超长都在此处被切掉。
            canvas.clipRect(getPaddingLeft(), getPaddingTop(),
                    getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
        }
    }

    private static final class Line {
        final long timeMs;
        final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private static void hookQsExpansion(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.qs.fake.OplusQSFakeStatusController$qsPanelExpandFractionListener$1",
                    lpparam.classLoader, "onFractionChanged", float.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                float fraction = ((Float) param.args[0]).floatValue();
                                setPanelExpanded(fraction > 0f);
                            } catch (Throwable t) {
                                log("statusbar_lyric qs expansion apply fail: " + t);
                            }
                        }
                    });
            log("HOOK OK OplusQSFakeStatusController$qsPanelExpandFractionListener"
                    + "#onFractionChanged (statusbar_lyric)");
        } catch (Throwable t) {
            log("HOOK FAIL OplusQSFakeStatusController fraction listener (statusbar_lyric) :: "
                    + Log.getStackTraceString(t));
            try {
                XposedHelpers.findAndHookMethod(
                        "com.oplus.systemui.qs.OplusQuickStatusBarHeader",
                        lpparam.classLoader, "setExpansion", float.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                setPanelExpanded((Float) param.args[0] > 0f);
                            }
                        });
                log("HOOK OK OplusQuickStatusBarHeader#setExpansion (legacy statusbar lyric)");
            } catch (Throwable legacyError) {
                log("HOOK FAIL legacy statusbar lyric QS expansion :: "
                        + Log.getStackTraceString(legacyError));
            }
        }
    }

    private static void setPanelExpanded(boolean expanded) {
        if (expanded == sPanelExpanded) return;
        sPanelExpanded = expanded;
        runOnMain(() -> FluidCloudLyricGeometry.setPanelExpanded(expanded));
        runOnMain(() -> setLyricHiddenForPanel(expanded));
    }

    public static void hookStatusBarLyric(final XC_LoadPackage.LoadPackageParam lpparam) {
        sGenerationActive = true;
        FluidCloudLyricHooks.init(lpparam);

        hookStatusBarView(lpparam);
        hookQsExpansion(lpparam);
        startThirdPartyOverlayMonitor();
    }

    public static Object captureHotReloadHost() {
        return new Object[] { sStatusBarRoot, Boolean.valueOf(sPanelExpanded) };
    }

    public static void restoreHotReloadHost(Object host) {
        final FrameLayout root;
        final boolean panelExpanded;
        if (host instanceof FrameLayout) {
            // Compatibility with state saved by an older generation.
            root = (FrameLayout) host;
            panelExpanded = false;
        } else if (host instanceof Object[]) {
            Object[] state = (Object[]) host;
            if (state.length < 1 || !(state[0] instanceof FrameLayout)) return;
            root = (FrameLayout) state[0];
            panelExpanded = state.length > 1 && Boolean.TRUE.equals(state[1]);
        } else {
            return;
        }
        Runnable restore = new Runnable() {
            @Override public void run() {
                try {
                    if (!root.isAttachedToWindow()) return;
                    sPanelExpanded = panelExpanded;
                    attachLyricView(root);
                    setLyricHiddenForPanel(panelExpanded);
                    initMediaListener(root.getContext());
                } catch (Throwable t) {
                    log("statusbar_lyric restore failed: " + t);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) restore.run();
        else new Handler(Looper.getMainLooper()).post(restore);
    }

    public static synchronized boolean prepareHotReloadListeners() {

        if (sListenersPreparedForHotReload) {
            log("statusbar_lyric listener rollback still pending, rejecting reload");
            return false;
        }
        Handler main = sMainHandler;
        if (sHotReloadRollbackRetry != null && main != null) {
            main.removeCallbacks(sHotReloadRollbackRetry);
            sHotReloadRollbackRetry = null;
        }
        sListenersPreparedForHotReload = true;
        sHotReloadRollbackAttempts = 0;
        try {
            if (sSessionManager != null && sSessionListenerRegistered) {
                sSessionManager.removeOnActiveSessionsChangedListener(sSessionListener);
                sSessionListenerRegistered = false;
                // Invalidate deliveries from the removed listener before rollback can reopen the gate.
                sSessionListenerGeneration++;
                sSessionListenerDetachedForHotReload = true;
            }
            for (MediaController controller : new ArrayList<>(sRegistered)) {
                controller.unregisterCallback(sControllerCallback);
                sRegistered.remove(controller);
                sControllersDetachedForHotReload.add(controller);
            }
            Context context = sStatusBarRoot == null ? sAppContext : sStatusBarRoot.getContext();
            if (context != null && sThirdPartyWindowObserver != null
                    && sThirdPartyObserverRegistered) {
                context.getContentResolver().unregisterContentObserver(sThirdPartyWindowObserver);
                sThirdPartyObserverRegistered = false;
                sThirdPartyObserverContextForHotReload = context;
                sThirdPartyObserverDetachedForHotReload = true;
            }
            return true;
        } catch (Throwable t) {
            log("statusbar_lyric listener preflight failed: " + t);
            rollbackHotReloadListeners();
            return false;
        }
    }

    public static synchronized void cancelHotReloadPreflight() {
        rollbackHotReloadListeners();
    }

    private static void rollbackHotReloadListeners() {
        Handler main = sMainHandler;
        if (main == null) main = new Handler(Looper.getMainLooper());
        if (sThirdPartyObserverDetachedForHotReload) {
            try {
                Context context = sThirdPartyObserverContextForHotReload;
                if (context == null || sThirdPartyWindowObserver == null) {
                    throw new IllegalStateException("observer rollback target unavailable");
                }
                context.getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(KEY_STATUSBAR_OVERLAY_EVENT), false,
                        sThirdPartyWindowObserver);
                sThirdPartyObserverRegistered = true;
                sThirdPartyObserverDetachedForHotReload = false;
                sThirdPartyObserverContextForHotReload = null;
            } catch (Throwable t) {
                log("statusbar_lyric observer rollback failed: " + t);
            }
        }

        sControllersDetachedForHotReload.clear();
        if (sSessionListenerDetachedForHotReload) {
            try {
                MediaSessionManager manager = sSessionManager;
                if (manager == null) {
                    throw new IllegalStateException("session manager unavailable");
                }
                MediaSessionManager.OnActiveSessionsChangedListener listener =
                        createSessionListener(sSessionListenerGeneration);
                manager.addOnActiveSessionsChangedListener(listener, null, main);
                sSessionListener = listener;
                sSessionListenerRegistered = true;
                sSessionListenerDetachedForHotReload = false;
            } catch (Throwable t) {
                log("statusbar_lyric session listener rollback failed: " + t);
            }
        }
        boolean complete = !sThirdPartyObserverDetachedForHotReload
                && !sSessionListenerDetachedForHotReload;
        if (complete) {
            finishHotReloadListenerRollback(main, false);
        } else if (sHotReloadRollbackAttempts >= HOT_RELOAD_ROLLBACK_MAX_ATTEMPTS) {
            log("statusbar_lyric listener rollback exhausted; resuming in degraded state");

            sSessionListenerDetachedForHotReload = false;
            sThirdPartyObserverDetachedForHotReload = false;
            sThirdPartyObserverContextForHotReload = null;
            if (!sSessionListenerRegistered) {

                sSessionListener = createSessionListener(sSessionListenerGeneration);
            }
            if (!sThirdPartyObserverRegistered) sThirdPartyWindowObserver = null;
            finishHotReloadListenerRollback(main, true);
        } else if (sHotReloadRollbackRetry == null) {
            final int attempt = ++sHotReloadRollbackAttempts;
            final long delay = Math.min(HOT_RELOAD_ROLLBACK_MAX_DELAY_MS,
                    HOT_RELOAD_ROLLBACK_INITIAL_DELAY_MS << (attempt - 1));
            Runnable retry = new Runnable() {
                @Override public void run() {
                    synchronized (StatusBarLyricHooks.class) {
                        if (sHotReloadRollbackRetry != this) return;
                        sHotReloadRollbackRetry = null;
                        if (sListenersPreparedForHotReload) rollbackHotReloadListeners();
                    }
                }
            };
            sHotReloadRollbackRetry = retry;
            if (!main.postDelayed(retry, delay)) {
                sHotReloadRollbackRetry = null;
                sHotReloadRollbackAttempts = HOT_RELOAD_ROLLBACK_MAX_ATTEMPTS;
                rollbackHotReloadListeners();
            }
        }
    }

    private static void finishHotReloadListenerRollback(Handler main, boolean degraded) {
        sListenersPreparedForHotReload = false;
        sHotReloadRollbackAttempts = 0;
        if (sHotReloadRollbackRetry != null) {
            main.removeCallbacks(sHotReloadRollbackRetry);
            sHotReloadRollbackRetry = null;
        }
        MediaSessionManager manager = sSessionManager;
        if (manager != null && sGenerationActive && sSessionListenerRegistered) {
            try {
                refreshSessions(manager.getActiveSessions(null));
                sMediaInited = true;
            } catch (Throwable t) {
                sMediaInited = false;
                log("statusbar_lyric session rebuild after rollback failed: " + t);
            }
        } else {
            sMediaInited = false;
        }
        if (degraded && sGenerationActive && sStatusBarRoot != null) {
            initMediaListener(sStatusBarRoot.getContext());
            registerThirdPartyWindowObserver(sStatusBarRoot.getContext());
        }
    }

    public static synchronized void cleanupForHotReload() {
        sGenerationActive = false;
        FluidCloudLyricHooks.clear();
        Handler handler = sMainHandler;
        try {
            if (handler != null) handler.removeCallbacksAndMessages(null);
        } catch (Throwable t) {
            log("statusbar_lyric handler cleanup failed: " + t);
        }

        if (sSessionManager != null && sSessionListenerRegistered) {
            try { sSessionManager.removeOnActiveSessionsChangedListener(sSessionListener); }
            catch (Throwable t) { log("statusbar_lyric session listener cleanup failed: " + t); }
            sSessionListenerRegistered = false;
        }

        for (MediaController controller : new ArrayList<>(sRegistered)) {
            try { controller.unregisterCallback(sControllerCallback); }
            catch (Throwable t) { log("statusbar_lyric controller cleanup failed: " + t); }
        }
        sRegistered.clear();
        try {
            setThirdPartyClockHidden(false);
            hideLyric();
        } catch (Throwable t) {
            log("statusbar_lyric view reset failed: " + t);
        }
        try {
            FrameLayout view = sLyricView;
            if (view != null && view.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) view.getParent()).removeView(view);
            }
        } catch (Throwable t) {
            log("statusbar_lyric view removal failed: " + t);
        }
        try { cancelSwitchAnimator(); } catch (Throwable t) {
            log("statusbar_lyric switch animator cleanup failed: " + t);
        }
        try { cancelScrollAnimator(); } catch (Throwable t) {
            log("statusbar_lyric scroll animator cleanup failed: " + t);
        }
        sListenersPreparedForHotReload = false;
        sSessionListenerDetachedForHotReload = false;
        sThirdPartyObserverDetachedForHotReload = false;
        sThirdPartyObserverContextForHotReload = null;
        if (sHotReloadRollbackRetry != null && handler != null) {
            handler.removeCallbacks(sHotReloadRollbackRetry);
        }
        sHotReloadRollbackRetry = null;
        sHotReloadRollbackAttempts = 0;
        sControllersDetachedForHotReload.clear();
        sThirdPartyWindowObserver = null;
        sThirdPartyObserverRegistered = false;
        sThirdPartyMonitorStarted = false;
        sSessionManager = null;
        sMediaInited = false;
        sSessionListenerRegistered = false;
        sController = null;
        sLines = null;
        sLyricSource = null;
        sTicking = false;
        sLyricTick = null;
            sTrackIdentity = null;
            sCloudLyricTrackPending = false;
        sLyricView = null;
        sOutgoingView = null;
        sIncomingView = null;
        sStatusBarRoot = null;
        sClockView = null;
        sHideViews = null;
        sHideOrigVisibility = null;
        sVisibilityHooked.clear();
        sOplusWindowManager = null;
        sMainHandler = null;
    }

    // Keep the SystemUI clock hidden while either this module or a third-party overlay owns it.
    private static final XC_MethodHook CLOCK_VISIBILITY_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (param.thisObject != sClockView) return;
            if (!shouldHideClock()) {
                sClockDesiredVisibility = (Integer) param.args[0];
                return;
            }
            if ((Integer) param.args[0] != View.GONE) {
                sClockDesiredVisibility = (Integer) param.args[0];
                param.args[0] = View.GONE;
            }
        }
    };

    private static boolean shouldHideClock() {
        return sHiddenByUs || sHiddenByThirdParty;
    }

    private static volatile Object sOplusWindowManager = null;

    private static void startThirdPartyOverlayMonitor() {
        if (sThirdPartyMonitorStarted) return;
        sThirdPartyMonitorStarted = true;
        if (sMainHandler == null) sMainHandler = new Handler(Looper.getMainLooper());
        sMainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    checkThirdPartyOverlayNow();
                } catch (Throwable t) {
                    log("statusbar_lyric third-party overlay check error: "
                            + Log.getStackTraceString(t));
                    setThirdPartyClockHidden(false);
                }
                if (sGenerationActive && sMainHandler != null) {
                    sMainHandler.postDelayed(this, THIRD_PARTY_MONITOR_INTERVAL_MS);
                }
            }
        });
    }

    private static synchronized void registerThirdPartyWindowObserver(Context context) {
        if (sListenersPreparedForHotReload || sThirdPartyObserverRegistered || context == null) return;
        try {
            if (sMainHandler == null) sMainHandler = new Handler(Looper.getMainLooper());
            sThirdPartyWindowObserver = new ContentObserver(sMainHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    runOnMain(StatusBarLyricHooks::checkThirdPartyOverlayNow);
                }
            };
            context.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(KEY_STATUSBAR_OVERLAY_EVENT), false,
                    sThirdPartyWindowObserver);
            sThirdPartyObserverRegistered = true;
            log("statusbar_lyric third-party window observer registered");
        } catch (Throwable t) {
            log("statusbar_lyric third-party window observer error: "
                    + Log.getStackTraceString(t));
        }
    }

    private static void checkThirdPartyOverlayNow() {
        boolean hidden = readBool(KEY_STATUSBAR_LYRIC_AVOID_THIRD_PARTY_ENABLED, false)
                && hasThirdPartyOverlayInStatusBar();
        setThirdPartyClockHidden(hidden);
    }

    private static void setThirdPartyClockHidden(boolean hidden) {
        if (hidden == sHiddenByThirdParty) return;
        sHiddenByThirdParty = hidden;
        View clock = sClockView;
        if (clock == null) return;
        if (hidden) {
            clock.setVisibility(View.GONE);
        } else if (!sHiddenByUs) {
            clock.setVisibility(sClockDesiredVisibility);
        }
    }

    private static boolean hasThirdPartyOverlayInStatusBar() {
        View statusBar = sStatusBarRoot;
        if (statusBar == null || statusBar.getVisibility() != View.VISIBLE || !statusBar.isShown()) {
            return false;
        }
        int[] statusLoc = new int[2];
        statusBar.getLocationOnScreen(statusLoc);
        int statusLeft = statusLoc[0];
        int statusRight = statusLeft + statusBar.getWidth();
        int statusTop = statusLoc[1];
        int statusHeight = statusBar.getHeight();
        if (statusHeight <= 0) {
            try {
                int id = statusBar.getResources().getIdentifier(
                        "status_bar_height", "dimen", "android");
                if (id != 0) statusHeight = statusBar.getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) {
            }
        }
        if (statusHeight <= 0) return false;
        int statusBottom = statusTop + statusHeight;

        try {
            Object windowManager = getOplusWindowManager();
            if (windowManager == null) return false;
            Object infosObject = XposedHelpers.callMethod(windowManager, "getAllVisibleWindowInfo");
            if (!(infosObject instanceof List)) return false;
            for (Object info : (List<?>) infosObject) {
                if (info == null) continue;
                String owner = (String) XposedHelpers.getObjectField(info, "packageName");
                if (TextUtils.isEmpty(owner)
                        || MODULE_PACKAGE.equals(owner)
                        || "com.android.systemui".equals(owner)) continue;
                int type = safeGetIntField(info, "type", 0);
                Object attrs = safeGetObjectField(info, "windowAttributes");
                if (attrs != null) type = safeGetIntField(attrs, "type", type);
                if (!isThirdPartyOverlayType(type)) continue;
                Rect frame = safeGetObjectField(info, "mFrame") instanceof Rect
                        ? (Rect) safeGetObjectField(info, "mFrame") : null;
                if (frame == null || frame.isEmpty()) {
                    frame = safeGetObjectField(info, "mBounds") instanceof Rect
                            ? (Rect) safeGetObjectField(info, "mBounds") : null;
                }
                if (frame == null || frame.isEmpty()) continue;
                // 只按左上角判断: 顶部边界在状态栏内即可, 符合第三方歌词悬浮窗的布局特征。
                if (frame.left >= statusLeft && frame.left < statusRight
                        && frame.top >= statusTop && frame.top < statusBottom) return true;
            }
        } catch (Throwable t) {
            log("statusbar_lyric inspect OplusWindowManager error: "
                    + Log.getStackTraceString(t));
        }
        return false;
    }

    private static Object getOplusWindowManager() {
        Object cached = sOplusWindowManager;
        if (cached != null) return cached;
        try {
            Class<?> clazz = XposedHelpers.findClass("android.view.OplusWindowManager", null);
            Object created = XposedHelpers.callStaticMethod(clazz, "getInstance");
            sOplusWindowManager = created;
            return created;
        } catch (Throwable t) {
            log("statusbar_lyric create OplusWindowManager error: "
                    + Log.getStackTraceString(t));
            return null;
        }
    }

    private static Object safeGetObjectField(Object object, String field) {
        try {
            return XposedHelpers.getObjectField(object, field);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int safeGetIntField(Object object, String field, int fallback) {
        try {
            return XposedHelpers.getIntField(object, field);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean isThirdPartyOverlayType(int type) {
        return type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                || type == WindowManager.LayoutParams.TYPE_PHONE
                || type == WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                || type == WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
                || type == WindowManager.LayoutParams.TYPE_PRIORITY_PHONE;
    }

    private static final XC_MethodHook CLOCK_DARK_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (param.thisObject != sClockView) return;
            try {
                syncLyricTextColor();
            } catch (Throwable t) {
                log("statusbar_lyric sync color error: " + t);
            }
        }
    };

    private static void hookClockDarkChanged(View clock) {
        try {
            XposedHelpers.findAndHookMethod(
                    clock.getClass(), "onDarkChanged",
                    ArrayList.class, float.class, int.class, CLOCK_DARK_HOOK);
            log("HOOK OK " + clock.getClass().getName() + "#onDarkChanged (statusbar_lyric)");
        } catch (Throwable t) {
            log("HOOK FAIL clock#onDarkChanged :: " + Log.getStackTraceString(t));
        }
    }

    private static void hookClockVisibility(View clock) {
        if (clock == null) return;
        sClockView = clock;
        sClockDesiredVisibility = clock.getVisibility();
        hookClockDarkChanged(clock);
        Class<?> c = clock.getClass();
        while (c != null && View.class.isAssignableFrom(c)) {
            if (sVisibilityHooked.add(c)) {
                try {
                    c.getDeclaredMethod("setVisibility", int.class);
                    XposedHelpers.findAndHookMethod(
                            c, "setVisibility", int.class, CLOCK_VISIBILITY_HOOK);
                    log("HOOK OK " + c.getName() + "#setVisibility (statusbar_lyric)");
                } catch (Throwable ignored) {
                    // 该类没有自己声明 setVisibility, 交给父类 hook。
                }
            }
            if (c == View.class) break;
            c = c.getSuperclass();
        }
    }

    // ---------------------------------------------------------------- 显示: 状态栏挂歌词 TextView

    private static void hookStatusBarView(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {

            XposedHelpers.findAndHookDeclaredMethod(
                    CLS_PHONE_STATUS_BAR_VIEW, lpparam.classLoader, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                View v = (View) param.thisObject;
                                if (v instanceof FrameLayout) {
                                    attachLyricView((FrameLayout) v);
                                    initMediaListener(v.getContext());
                                }
                            } catch (Throwable t) {
                                log("statusbar_lyric attach error: " + t);
                            }
                        }
                    });
            log("HOOK OK PhoneStatusBarView#onAttachedToWindow (statusbar_lyric)");
        } catch (Throwable t) {
            log("HOOK FAIL PhoneStatusBarView#onAttachedToWindow :: "
                    + Log.getStackTraceString(t));
        }
    }

    private static void attachLyricView(FrameLayout root) {
        FluidCloudLyricHooks.clear();
        sStatusBarRoot = root;
        registerThirdPartyWindowObserver(root.getContext());

        View clock = findSystemUiViewById(root, "clock");
        View icons = findSystemUiViewById(root, "notification_icon_area");
        View hostRaw = findSystemUiViewById(root, "status_bar_start_side_content_for_fake");
        LinearLayout clockHost = null;
        if (hostRaw instanceof LinearLayout) {
            clockHost = (LinearLayout) hostRaw;
        } else if (icons != null && icons.getParent() instanceof LinearLayout) {
            clockHost = (LinearLayout) icons.getParent(); // 兜底: 用通知图标的父容器
        } else if (clock != null && clock.getParent() instanceof LinearLayout) {
            clockHost = (LinearLayout) clock.getParent();
        }

        // 重建 / 配置变化(旋转、挖孔等)后尺寸会变, 先作废缓存以便重新测量。
        sLyricMaxWidthPx = 0;

        // SystemUI 重建 / 配置变化会重新 attach, 避免重复添加。
        if (sLyricView != null && clockHost != null && sLyricView.getParent() == clockHost) {
            updateLyricWidth(root, clockHost, sLyricView);
            return;
        }
        if (sLyricView != null && sLyricView.getParent() != null) {
            ((android.view.ViewGroup) sLyricView.getParent()).removeView(sLyricView);
        }
        if (clockHost == null) {
            log("statusbar_lyric: clock host not found, skip attach");
            return;
        }

        Context ctx = root.getContext();

        // ---- 歌词字幕容器: 两个 TextView 做向上切换动画。 ----
        sOutgoingView = newLyricText(ctx, clock);
        sIncomingView = newLyricText(ctx, clock);

        // 裁切窗口: 保证歌词左右两侧都按盒子裁, 不会溢出到通知图标上、也不会伸到流体云下面。
        LyricClipLayout container = new LyricClipLayout(ctx);
        container.setVisibility(View.GONE);
        // 纯绘制层的视觉校准: 整体上移 1dp。translationY 不参与测量, 不影响宽度计算。
        container.setTranslationY(-dpToPx(ctx, LYRIC_VERTICAL_OFFSET_DP));

        FrameLayout.LayoutParams childLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        // 与状态栏时钟一致: gravity="start|center" —— 文本在盒子内**垂直居中**。
        childLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        container.addView(sOutgoingView, new FrameLayout.LayoutParams(childLp));
        container.addView(sIncomingView, new FrameLayout.LayoutParams(childLp));
        sIncomingView.setVisibility(View.INVISIBLE);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        // 歌词与左边的通知图标之间留 6dp(会被 updateLyricWidth 计入起点, 因此可用宽度仍正确)。
        lp.leftMargin = Math.round(dpToPx(ctx, LYRIC_MARGIN_START_DP));
        int index = (icons != null && icons.getParent() == clockHost)
                ? clockHost.indexOfChild(icons) + 1 : -1;
        if (index > 0) clockHost.addView(container, index, lp);
        else clockHost.addView(container, lp);
        final LinearLayout hostFinal = clockHost;
        Runnable widthUpdate = new Runnable() {
            @Override public void run() { updateLyricWidth(root, hostFinal, container); }
        };
        XposedHelpers.setAdditionalInstanceField(container,
                "colorosmod_lyric_width_update", widthUpdate);
        container.post(widthUpdate);

        sLyricView = container;
        sHideViews = collectHideViews(root);
        sHiddenByUs = false;
        // 时钟会被系统动态显示/隐藏, 必须拦住 setVisibility, 否则歌词旁会冒出时钟。
        hookClockVisibility(clock);
        if (sMainHandler == null) sMainHandler = new Handler(Looper.getMainLooper());
        log("statusbar_lyric view attached");
    }

    private static float dpToPx(Context ctx, float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }

    private static void setLyricHiddenForPanel(boolean hidden) {
        FrameLayout container = sLyricView;
        if (container == null) return;
        if (hidden) {
            if (container.getVisibility() == View.GONE) return;
            cancelSwitchAnimator();
            cancelScrollAnimator();
            container.setVisibility(View.GONE);
            if (sIncomingView != null) resetTv(sIncomingView);
        } else {
            if (container.getVisibility() == View.VISIBLE) return;
            CharSequence text = sCurrentText;
            if (text == null) return; // 没有歌词可显示, 交给 tick 的下一次 showLyric。
            container.setVisibility(View.VISIBLE);
            startSinglePassScroll(sOutgoingView, text);
        }
    }

    private static void updateLyricWidth(View root, View host, FrameLayout container) {
        if (container.getLayoutParams() instanceof FrameLayout.LayoutParams) return;
        try {
            int[] rootLoc = new int[2];
            root.getLocationOnScreen(rootLoc);
            int rootLeft = rootLoc[0];

            // 歌词容器自身的左边缘: 宿主左边缘 + 前面兄弟的宽度(含 margin)。
            int containerLeft = leftOf(host, rootLeft);
            if (host instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) host;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);
                    if (child == container) {
                        // 容器自身的左外边距(歌词与通知图标的间距)也算进起点。
                        android.view.ViewGroup.LayoutParams slp = child.getLayoutParams();
                        if (slp instanceof LinearLayout.LayoutParams) {
                            containerLeft += ((LinearLayout.LayoutParams) slp).leftMargin;
                        }
                        break;
                    }
                    if (child == null || child.getVisibility() == View.GONE) continue;
                    containerLeft += child.getWidth();
                    android.view.ViewGroup.LayoutParams clp = child.getLayoutParams();
                    if (clp instanceof LinearLayout.LayoutParams) {
                        LinearLayout.LayoutParams l = (LinearLayout.LayoutParams) clp;
                        containerLeft += l.leftMargin + l.rightMargin;
                    }
                }
            }

            // 默认右边界: 左半容器右边缘; 取不到则退化为根布局宽度。
            int rightLimit = root.getWidth();
            View startSide = findSystemUiViewById(root, "status_bar_start_side_container");
            if (startSide != null && startSide.getWidth() > 0) {
                rightLimit = leftOf(startSide, rootLeft) + startSide.getWidth();
            }
            View cutout = findSystemUiViewById(root, "cutout_space_view");
            if (cutout != null && cutout.getVisibility() == View.VISIBLE && cutout.getWidth() > 0) {
                int cutLeft = leftOf(cutout, rootLeft);
                if (cutLeft > containerLeft && cutLeft < rightLimit) rightLimit = cutLeft;
            }

            View capsule = findSystemUiViewById(root, "seeding_card_container");
            int fluidLeft = findLeftmostVisibleChildLeft(capsule, rootLeft, containerLeft);
            if (fluidLeft != Integer.MAX_VALUE) {
                fluidLeft -= Math.round(dpToPx(root.getContext(), LYRIC_GAP_BEFORE_FLUID_DP));
                if (fluidLeft > containerLeft && fluidLeft < rightLimit) rightLimit = fluidLeft;
            }

            int width = rightLimit - containerLeft;
            if (width <= 0) return;
            if (width == sLyricMaxWidthPx) return; // 无变化则不重排。
            sLyricMaxWidthPx = width;

            // 宿主是 LinearLayout(时钟所在容器), 故用 LinearLayout.LayoutParams。
            android.view.ViewGroup.LayoutParams lp = container.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                llp.width = width;
                llp.height = LinearLayout.LayoutParams.MATCH_PARENT;
                container.setLayoutParams(llp);
            }

            // 宽度变窄(流体云出现)后, 让已滚到位的歌词接着往下滚, 否则尾巴会一直被挡住。
            syncScrollToAvailableWidth();
        } catch (Throwable t) {
            log("statusbar_lyric updateLyricWidth error: " + t);
        }
    }

    private static float scrollSpeedFactor(CharSequence text) {
        if (text == null) return 1f;
        int total = 0;
        int ascii = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if (c < 128) ascii++;
        }
        if (total == 0) return 1f;
        float ratio = (float) ascii / total;
        return 1f + (SCROLL_SPEED_ASCII_FACTOR - 1f) * ratio;
    }

    private static int leftOf(View v, int rootLeft) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[0] - rootLeft;
    }

    private static int findLeftmostVisibleChildLeft(View group, int rootLeft, int minValid) {
        if (!(group instanceof android.view.ViewGroup)) return Integer.MAX_VALUE;
        android.view.ViewGroup vg = (android.view.ViewGroup) group;
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            if (child.getAlpha() <= 0.01f) continue;
            int left = leftOf(child, rootLeft);
            // 有宽度就可能画了东西, 但要落在歌词起点右侧才有意义。
            if (child.getWidth() > 0 && left > minValid && left < best) best = left;
            // 不管自身有没有宽度, 容器类都继续下钻找更靠左的实际内容。
            int deeper = findLeftmostVisibleChildLeft(child, rootLeft, minValid);
            if (deeper < best) best = deeper;
        }
        return best;
    }

    private static TextView newLyricText(Context ctx, View clock) {
        TextView tv = new TextView(ctx);
        tv.setSingleLine(true);
        // 单行, 不做 ellipsize: 超长靠手动 translationX 单次滚动到尽头(见 startSinglePassScroll)。
        tv.setIncludeFontPadding(false);
        tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tv.setClickable(false);
        tv.setFocusable(false);
        tv.setFocusableInTouchMode(false);
        tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (clock instanceof TextView) {
            TextView ctv = (TextView) clock;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, ctv.getTextSize());
            tv.setTextColor(ctv.getTextColors());
            tv.setTypeface(mediumTypeface(ctv.getTypeface()));
        } else {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            tv.setTextColor(Color.WHITE);
            tv.setTypeface(mediumTypeface(null));
        }
        return tv;
    }

    private static Typeface mediumTypeface(Typeface base) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return Typeface.create(base == null ? Typeface.DEFAULT : base,
                        MEDIUM_WEIGHT, false);
            }
        } catch (Throwable t) {
            log("statusbar_lyric medium typeface fallback: " + t);
        }
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    private static void syncLyricTextColor() {
        View clock = sClockView;
        if (!(clock instanceof TextView)) return;
        ColorStateList colors = ((TextView) clock).getTextColors();
        if (colors == null) return;
        // tick 每 250ms 也会调一次, 先比对再赋值, 避免无谓的 invalidate。
        if (sOutgoingView != null && !colors.equals(sOutgoingView.getTextColors())) {
            sOutgoingView.setTextColor(colors);
        }
        if (sIncomingView != null && !colors.equals(sIncomingView.getTextColors())) {
            sIncomingView.setTextColor(colors);
        }
    }

    private static void applyTextWidth(TextView tv, CharSequence text) {
        if (tv == null) return;
        int w = 0;
        if (text != null && text.length() > 0) {
            // +2px 兜住 measureText 的舍入误差, 免得最后一个字被切掉一丝。
            w = (int) Math.ceil(tv.getPaint().measureText(text.toString())) + 2;
        }
        android.view.ViewGroup.LayoutParams lp = tv.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT);
            ((FrameLayout.LayoutParams) lp).gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        }
        if (lp.width != w) {
            lp.width = w;
            tv.setLayoutParams(lp);
        }
    }

    private static View[] collectHideViews(View root) {
        // 隐藏时钟 + 通知图标区(歌词占用时钟槽位, 故时钟要藏起来)。
        View[] views = new View[HIDE_VIEW_IDS.length];
        int n = 0;
        for (String id : HIDE_VIEW_IDS) {
            View v = findSystemUiViewById(root, id);
            if (v != null) views[n++] = v;
        }
        if (n == HIDE_VIEW_IDS.length) return views;
        View[] trimmed = new View[n];
        System.arraycopy(views, 0, trimmed, 0, n);
        return trimmed;
    }

    private static View findSystemUiViewById(View root, String name) {
        try {
            int id = root.getResources().getIdentifier(name, "id", "com.android.systemui");
            return id != 0 ? root.findViewById(id) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 数据: 监听 MediaSession

    private static MediaSessionManager.OnActiveSessionsChangedListener createSessionListener(
            final long generation) {
        return new MediaSessionManager.OnActiveSessionsChangedListener() {
            @Override
            public void onActiveSessionsChanged(List<MediaController> controllers) {
                refreshSessions(controllers, generation);
            }
        };
    }

    private static final MediaController.Callback sControllerCallback =
            new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    refreshLyric();
                }

                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    refreshLyric();
                }

                @Override
                public void onSessionDestroyed() {
                    refreshLyric();
                }
            };

    private static void initMediaListener(Context ctx) {
        if (sMediaInited || sSessionListenerRegistered || sListenersPreparedForHotReload) return;
        final Context appCtx = ctx.getApplicationContext();
        if (sMainHandler == null) sMainHandler = new Handler(Looper.getMainLooper());
        sMainHandler.post(() -> registerMediaListenerOnMain(appCtx));
    }

    private static synchronized void registerMediaListenerOnMain(Context appCtx) {
        if (sMediaInited || sSessionListenerRegistered
                || sListenersPreparedForHotReload || !sGenerationActive) return;
        try {
            // 不判断开关: 监听始终注册, 开关在 refreshLyric 里判断, 以便开关可实时生效。
            MediaSessionManager msm = (MediaSessionManager)
                    appCtx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) {
                log("statusbar_lyric MediaSessionManager unavailable");
                return;
            }
            sSessionManager = msm;
            // 传 null 表示监听全部会话, 需要 MEDIA_CONTENT_CONTROL(SystemUI 已具备)。
            msm.addOnActiveSessionsChangedListener(sSessionListener, null);
            sSessionListenerRegistered = true;
            refreshSessions(msm.getActiveSessions(null));
            sMediaInited = true;
            log("statusbar_lyric media listener inited");
        } catch (Throwable t) {

            MediaSessionManager manager = sSessionManager;
            if (manager != null && sSessionListenerRegistered) {
                try {
                    manager.removeOnActiveSessionsChangedListener(sSessionListener);
                    sSessionListenerRegistered = false;
                } catch (Throwable cleanupError) {

                    log("statusbar_lyric partial listener cleanup failed: " + cleanupError);
                }
            }
            for (MediaController controller : new ArrayList<>(sRegistered)) {
                try { controller.unregisterCallback(sControllerCallback); }
                catch (Throwable ignored) { }
            }
            sRegistered.clear();
            sMediaInited = sSessionListenerRegistered;
            if (!sSessionListenerRegistered) sSessionManager = null;
            log("statusbar_lyric initMediaListener error: " + t);
        }
    }

    private static void refreshSessions(List<MediaController> controllers) {
        refreshSessions(controllers, sSessionListenerGeneration);
    }

    private static synchronized void refreshSessions(List<MediaController> controllers,
            long listenerGeneration) {
        if (!sGenerationActive || sListenersPreparedForHotReload
                || listenerGeneration != sSessionListenerGeneration) return;
        List<MediaController> list = controllers != null ? controllers
                : new ArrayList<MediaController>();
        for (MediaController c : new ArrayList<>(sRegistered)) {
            if (!list.contains(c)) {
                try {
                    c.unregisterCallback(sControllerCallback);
                } catch (Throwable ignored) {}
                sRegistered.remove(c);
            }
        }
        Handler main = sMainHandler;
        if (main == null) main = new Handler(Looper.getMainLooper());
        for (MediaController c : list) {
            if (!sRegistered.contains(c)) {
                try {
                    c.registerCallback(sControllerCallback, main);
                    sRegistered.add(c);
                } catch (Throwable t) {
                    log("statusbar_lyric registerCallback error: " + t);
                }
            }
        }
        refreshLyric();
    }

    public static void onSettingsSnapshotPublished() {
        if (!sGenerationActive || sListenersPreparedForHotReload || sMainHandler == null) return;
        runOnMain(() -> {
            int settings = lyricMode();
            if (settings == sLastLyricSettings) return;
            sLastLyricSettings = settings;
            if (settings != STATUSBAR_LYRIC_MODE_LIVE_ALERTS) {
                sCloudLyricWidthReserved = false;
                sCloudLyricTrackPending = false;
            }
            stopTicking();
            hideAllLyrics();
            if (sMediaInited) refreshLyric();
        });
    }

    private static boolean lyricsEnabled() {
        return lyricMode() != STATUSBAR_LYRIC_MODE_OFF;
    }

    public static int lyricMode() {
        int mode = readInt(KEY_STATUSBAR_LYRIC_MODE, -1);
        if (mode >= STATUSBAR_LYRIC_MODE_OFF && mode <= STATUSBAR_LYRIC_MODE_LIVE_ALERTS) {
            if (mode == STATUSBAR_LYRIC_MODE_OFF) return STATUSBAR_LYRIC_MODE_OFF;
            return readBool(KEY_FLUID_CLOUD_LYRIC_ENABLED, false)
                    || mode == STATUSBAR_LYRIC_MODE_LIVE_ALERTS
                    ? STATUSBAR_LYRIC_MODE_LIVE_ALERTS : mode;
        }
        if (!readBool(KEY_STATUSBAR_LYRIC_ENABLED, false)) return STATUSBAR_LYRIC_MODE_OFF;
        return readBool(KEY_FLUID_CLOUD_LYRIC_ENABLED, false)
                ? STATUSBAR_LYRIC_MODE_LIVE_ALERTS : STATUSBAR_LYRIC_MODE_NOTIFICATION;
    }

    private static void hideAllLyrics() {
        FluidCloudLyricHooks.clear();
        hideLyric();
    }

    private static void refreshLyric() {
        try {
            if (!lyricsEnabled()) {
                sCloudLyricWidthReserved = false;
                sCloudLyricTrackPending = false;
                stopTicking();
                runOnMain(StatusBarLyricHooks::hideAllLyrics);
                return;
            }
            MediaSessionManager msm = sSessionManager;
            if (msm == null) return;
            List<MediaController> list = msm.getActiveSessions(null);
            if (list == null || list.isEmpty()) {
                sCloudLyricWidthReserved = false;
                sCloudLyricTrackPending = false;
                stopTicking();
                sController = null;
                runOnMain(StatusBarLyricHooks::hideAllLyrics);
                return;
            }

            MediaController playing = null;
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == STATE_PLAYING) {
                    if (playing == null) playing = c;
                }
            }
            if (playing == null) {

                if (sCloudLyricWidthReserved && sController != null) {
                    for (MediaController c : list) {
                        if (sController.getSessionToken().equals(c.getSessionToken())
                                && isPlaybackTransition(c.getPlaybackState())) {
                            sController = c;
                            runOnMain(StatusBarLyricHooks::clearRenderedLyric);
                            startTicking();
                            return;
                        }
                    }
                }
                sCloudLyricWidthReserved = false;
                sCloudLyricTrackPending = false;
                sController = null;
                stopTicking();
                runOnMain(StatusBarLyricHooks::hideAllLyrics);
                return;
            }
            boolean sessionChanged = sController == null
                    || !sController.getSessionToken().equals(playing.getSessionToken());
            sController = playing;

            // 歌词: metadata -> "lyricInfo"(JSON) -> "lyric"(原文)
            MediaMetadata md = playing.getMetadata();
            String track = md == null ? sTrackIdentity : md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                    + "\n" + md.getString(MediaMetadata.METADATA_KEY_TITLE)
                    + "\n" + md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (sessionChanged || !TextUtils.equals(track, sTrackIdentity)) {
                stopTicking();
                sCloudLyricTrackPending = sCloudLyricWidthReserved
                        && lyricMode() == STATUSBAR_LYRIC_MODE_LIVE_ALERTS;
                sCloudLyricTrackChangedAt = SystemClock.elapsedRealtime();
                if (!sCloudLyricTrackPending) sCloudLyricWidthReserved = false;
                sTrackIdentity = track;
                sLines = null;
                sLyricSource = null;
                // Decide whether to shrink only after inspecting the new song's lyric information.
                runOnMain(StatusBarLyricHooks::clearRenderedLyric);
            }
            String raw = null;
            if (md != null) {
                String info = md.getString(METADATA_KEY_LYRIC_INFO);
                if (!TextUtils.isEmpty(info)) {
                    try {
                        JSONObject lyricInfo = new JSONObject(info);
                        raw = lyricInfo.optString(LYRIC_INFO_KEY_LYRIC, null);
                    } catch (Throwable t) {
                        log("statusbar_lyric parse lyricInfo error: " + t);
                    }
                }
            }
            if (raw == null || raw.trim().length() == 0) {
                stopTicking();
                sLines = null;
                sLyricSource = null;
                if (sCloudLyricTrackPending && !trackLyricSettlePending()) {
                    sCloudLyricWidthReserved = false;
                    runOnMain(StatusBarLyricHooks::hideAllLyrics);
                } else if (sCloudLyricWidthReserved) startTicking();
                else runOnMain(StatusBarLyricHooks::hideAllLyrics);
                return;
            }
            if (!raw.equals(sLyricSource)) {
                stopTicking();
                runOnMain(StatusBarLyricHooks::clearRenderedLyric);
                sLines = parseLyric(raw);
                sLyricSource = raw;
            }
            if (sLines != null && !sLines.isEmpty() && FluidCloudLyricHooks.isHosting(sLyricView)) {
                sCloudLyricWidthReserved = true;
                sCloudLyricTrackPending = false;
            }
            startTicking();
        } catch (Throwable t) {
            stopTicking();
            runOnMain(StatusBarLyricHooks::hideAllLyrics);
            log("statusbar_lyric refreshLyric error: " + t);
        }
    }

    private static boolean isPlaybackTransition(PlaybackState state) {
        if (state == null) return false;
        int value = state.getState();
        return value == PlaybackState.STATE_BUFFERING || value == PlaybackState.STATE_CONNECTING
                || value == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || value == PlaybackState.STATE_SKIPPING_TO_NEXT
                || value == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM;
    }

    private static boolean trackLyricSettlePending() {
        if (!sCloudLyricTrackPending || lyricMode() != STATUSBAR_LYRIC_MODE_LIVE_ALERTS) {
            return false;
        }
        return SystemClock.elapsedRealtime() - sCloudLyricTrackChangedAt < TRACK_LYRIC_SETTLE_MS;
    }

    private static void startTicking() {
        if (sTicking) return;
        sTicking = true;
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (sLyricTick != this) return;
                sLyricTick = null;
                sTicking = false;
                if (!sGenerationActive || sListenersPreparedForHotReload) return;
                try {
                    if (!lyricsEnabled()) {
                        runOnMain(StatusBarLyricHooks::hideAllLyrics);
                        return;
                    }
                    MediaController current = sController;
                    PlaybackState st = current == null ? null : current.getPlaybackState();
                    if (st == null || st.getState() != STATE_PLAYING) {
                        if (sCloudLyricWidthReserved && isPlaybackTransition(st)) {
                            runOnMain(() -> {
                                clearRenderedLyric();
                                if (!FluidCloudLyricHooks.show(sStatusBarRoot, sLyricView, sPanelExpanded)) {
                                    hideAllLyrics();
                                }
                            });
                            refreshLyric();
                            return;
                        }
                        sCloudLyricWidthReserved = false;
                        runOnMain(StatusBarLyricHooks::hideAllLyrics);
                        return;
                    }
                    long pos = estimatePosition(st);
                    CharSequence text = findLineAt(pos);
                    if (text == null) {
                        runOnMain(() -> {
                            if (sCloudLyricWidthReserved && FluidCloudLyricHooks.show(
                                    sStatusBarRoot, sLyricView, sPanelExpanded)) {
                                clearRenderedLyric();
                                sLyricView.setVisibility(View.VISIBLE);
                                setHiddenForLyric(true);
                                setNotificationNumberMode(false);
                            } else {
                                hideAllLyrics();
                            }
                        });
                    } else {
                        final CharSequence t2 = text;
                        runOnMain(() -> {
                            boolean wasCloud = sLyricView != null && sLyricView.getLayoutParams()
                                    instanceof FrameLayout.LayoutParams;
                            if (FluidCloudLyricHooks.show(sStatusBarRoot, sLyricView, sPanelExpanded)) {
                                sCloudLyricWidthReserved = true;
                                setNotificationNumberMode(false);
                                setHiddenForLyric(true);
                                syncLyricTextColor();
                                int width = sLyricView.getLayoutParams().width;
                                if (sLyricMaxWidthPx != width) {
                                    sLyricMaxWidthPx = width;
                                    syncScrollToAvailableWidth();
                                }
                                showLyric(t2);
                                return;
                            }
                            if (FluidCloudLyricHooks.enabled()) {
                                hideLyric();
                                return;
                            }
                            // clear() has restored the same lyric view to its status-bar host.
                            if (wasCloud) sLyricMaxWidthPx = 0;
                            if (sLyricView != null && sLyricView.getParent() instanceof View) {
                                updateLyricWidth(sStatusBarRoot, (View) sLyricView.getParent(), sLyricView);
                            }

                            setHiddenForLyric(true);
                            setNotificationNumberMode(!FluidCloudLyricHooks.enabled());
                            // 兜底同步颜色: 时钟可能经 onDarkChanged 之外的路径刷色。
                            syncLyricTextColor();
                            showLyric(t2);
                        });
                    }
                } catch (Throwable t) {
                    runOnMain(StatusBarLyricHooks::hideAllLyrics);
                    log("statusbar_lyric tick error: " + t);
                }
                // 继续下一拍(无论本次结果如何, 只要还在播放就保持轮询)。
                refreshLyric();
            }
        };
        Handler h = sMainHandler;
        if (h != null) {
            sLyricTick = tick;
            h.postDelayed(tick, TICK_INTERVAL_MS);
        } else {
            sTicking = false;
        }
    }

    private static void stopTicking() {
        Runnable tick = sLyricTick;
        sLyricTick = null;
        if (tick != null && sMainHandler != null) sMainHandler.removeCallbacks(tick);
        sTicking = false;
    }

    private static long estimatePosition(PlaybackState st) {
        long pos = st.getPosition();
        if (pos < 0) pos = 0;
        long updateTime = st.getLastPositionUpdateTime();
        float speed = st.getPlaybackSpeed();
        if (updateTime > 0 && speed > 0f) {
            long delta = SystemClock.elapsedRealtime() - updateTime;
            if (delta > 0) pos += (long) (delta * speed);
        }
        return pos;
    }

    private static final Pattern PLACEHOLDER_LYRIC_PATTERN =
            Pattern.compile("(该歌曲)?暂?(无|没有)歌词");

    private static boolean isPlaceholderLyric(CharSequence text) {
        if (text == null) return false;
        String s = text.toString().trim();
        if (s.isEmpty()) return false;
        if (s.contains("纯音乐，请欣赏") || s.contains("纯音乐,请欣赏")) return true;
        Matcher m = PLACEHOLDER_LYRIC_PATTERN.matcher(s);
        return m.find();
    }

    private static CharSequence findLineAt(long positionMs) {
        List<Line> lines = sLines;
        if (lines == null || lines.isEmpty()) return null;
        CharSequence result = null;
        for (Line l : lines) {
            if (l.timeMs <= positionMs) result = l.text;
            else break;
        }
        if (result != null && (result.toString().trim().isEmpty()
                || isPlaceholderLyric(result))) return null;
        return result;
    }

    private static List<Line> parseLyric(String raw) {
        List<Line> out = new ArrayList<>();
        if (raw == null) return out;
        for (String rawline : raw.split("\\r?\\n")) {
            String line = rawline.trim();
            if (line.length() == 0) continue;
            char c0 = line.charAt(0);
            if (c0 == '[') {
                // LRC: 逐个 [..] 时间标签, 剩余部分为歌词文本。
                int i = 0;
                List<Long> times = new ArrayList<>();
                while (i < line.length() && line.charAt(i) == '[') {
                    int end = line.indexOf(']', i + 1);
                    if (end < 0) break;
                    Long t = parseLrcTime(line.substring(i + 1, end));
                    if (t != null) times.add(t);
                    i = end + 1;
                }
                if (!times.isEmpty()) {
                    String text = line.substring(i).trim();
                    for (Long t : times) out.add(new Line(t, text));
                }
            } else if (c0 == '{') {
                try {
                    JSONObject obj = new JSONObject(line);
                    long t = obj.optLong(LYRIC_TIME, -1L);
                    JSONArray arr = obj.optJSONArray(LYRIC_CONTEXT);
                    if (t >= 0 && arr != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < arr.length(); k++) {
                            JSONObject item = arr.optJSONObject(k);
                            String tx = item != null ? item.optString(LYRIC_TEXT, null) : null;
                            if (tx != null) sb.append(tx);
                        }
                        out.add(new Line(t, sb.toString()));
                    }
                } catch (Throwable ignored) {
                    // 单行解析失败不影响其它行。
                }
            }
        }
        Collections.sort(out, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Long.compare(a.timeMs, b.timeMs);
            }
        });
        return out;
    }

    private static Long parseLrcTime(String body) {
        try {
            String[] parts = body.split("[.:]", 3);
            if (parts.length != 3) return null;
            long min = Long.parseLong(parts[0].trim());
            long sec = Long.parseLong(parts[1].trim());
            // 厘秒只取前 2 位(与系统实现一致: take(str, 2))。
            String cs = parts[2].trim();
            if (cs.length() > 2) cs = cs.substring(0, 2);
            long centi = Long.parseLong(cs);
            return min * 60000L + sec * 1000L + centi * 10L;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 视图更新(主线程)

    private static void runOnMain(Runnable r) {

        if (sMainHandler == null) sMainHandler = new Handler(Looper.getMainLooper());
        Handler h = sMainHandler;
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else h.post(r);
    }

    private static void showLyric(CharSequence text) {
        FrameLayout container = sLyricView;
        if (container == null) return;
        TextView outgoing = sOutgoingView;
        TextView incoming = sIncomingView;
        if (outgoing == null || incoming == null) return;

        if (sCurrentText != null && TextUtils.equals(sCurrentText, text)) return;
        sCurrentText = text;

        setHiddenForLyric(true);
        setNotificationNumberMode(!FluidCloudLyricHooks.isHosting(container)
                && !FluidCloudLyricHooks.enabled());

        // 下拉通知栏期间: 只把当前这句更新到视图上, 容器保持隐藏, 等收起时再显示。
        if (sPanelExpanded) {
            cancelSwitchAnimator();
            cancelScrollAnimator();
            resetTv(incoming);
            applyTextWidth(outgoing, text);
            outgoing.setText(text);
            resetTvTransform(outgoing);
            return;
        }

        // 首次显示(容器还不可见): 不做切换动画, 直接放上。
        boolean firstShow = container.getVisibility() != View.VISIBLE;
        if (firstShow) {
            // 清掉可能残留的动画状态, 避免上一次播放在中途被 hideLyric 打断后残留。
            cancelSwitchAnimator();
            cancelScrollAnimator();
            resetTv(incoming);
            applyTextWidth(outgoing, text);
            outgoing.setText(text);
            resetTvTransform(outgoing);
            container.setVisibility(View.VISIBLE);
            startSinglePassScroll(outgoing, text);
            return;
        }

        if (sSwitchAnimator != null) finishSwitchNow();
        cancelScrollAnimator();

        int h = container.getHeight() > 0 ? container.getHeight()
                : (int) outgoing.getTextSize();

        applyTextWidth(incoming, text);
        incoming.setText(text);
        incoming.setVisibility(View.VISIBLE);
        incoming.setTranslationY(h);
        incoming.setAlpha(1f);
        incoming.setTranslationX(0f);
        outgoing.setAlpha(1f);

        android.animation.AnimatorSet set = new android.animation.AnimatorSet();
        android.animation.ObjectAnimator outY = android.animation.ObjectAnimator.ofFloat(
                outgoing, "translationY", 0f, -h);
        android.animation.ObjectAnimator outA = android.animation.ObjectAnimator.ofFloat(
                outgoing, "alpha", 1f, 0f);
        android.animation.ObjectAnimator inY = android.animation.ObjectAnimator.ofFloat(
                incoming, "translationY", h, 0f);
        set.playTogether(outY, outA, inY);
        set.setDuration(280);
        set.setInterpolator(new android.view.animation.DecelerateInterpolator());
        set.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                sSwitchAnimator = null;
                finishSwitchNow();
                startSinglePassScroll(sOutgoingView, sCurrentText);
            }

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                sSwitchAnimator = null;
            }
        });
        sSwitchAnimator = set;
        set.start();
    }

    private static void finishSwitchNow() {
        TextView outgoing = sOutgoingView;
        TextView incoming = sIncomingView;
        if (outgoing == null || incoming == null) return;
        TextView tmp = outgoing;
        sOutgoingView = incoming;
        sIncomingView = tmp;
        resetTv(tmp);
    }

    private static void resetTv(TextView tv) {
        if (tv == null) return;
        tv.setVisibility(View.INVISIBLE);
        tv.setText(null);
        resetTvTransform(tv);
    }

    private static void resetTvTransform(TextView tv) {
        if (tv == null) return;
        tv.setTranslationY(0f);
        tv.setAlpha(1f);
        tv.setTranslationX(0f);
    }

    static void centerCloudLyric(View view) {
        if (view != sLyricView) return;
        Runnable center = () -> {
            if (view != sLyricView || sOutgoingView == null || sIncomingView == null) return;
            cancelSwitchAnimator();
            cancelScrollAnimator();
            CharSequence text = sCurrentText;
            if (text != null) {
                applyTextWidth(sOutgoingView, text);
                sOutgoingView.setText(text);
                sOutgoingView.setVisibility(View.VISIBLE);
                resetTvTransform(sOutgoingView);
                resetTv(sIncomingView);
                startSinglePassScroll(sOutgoingView, text);
            }
            view.setTranslationY(0f);
        };
        runOnMain(center);
    }

    private static void startSinglePassScroll(final TextView tv, final CharSequence text) {
        cancelScrollAnimator();
        if (tv == null) return;
        tv.setTranslationX(0f);
        sScrollTargetX = 0f;
        Runnable startScroll = new Runnable() {
            @Override public void run() {
                if (sOutgoingView != tv) return;
                float target = computeScrollTarget(tv, text);
                sScrollTargetX = target;
                animateScrollTo(tv, 0f, target, true);
            }
        };
        XposedHelpers.setAdditionalInstanceField(tv,
                "colorosmod_lyric_start_scroll", startScroll);
        tv.post(startScroll);
    }

    private static float computeScrollTarget(TextView tv, CharSequence text) {
        if (tv == null) return 0f;
        float textWidth = measureLyricTextWidth(tv, text);
        float available = availableLyricWidth();
        if (available <= 0f) return 0f;
        return Math.min(0f, available - textWidth);
    }

    private static float availableLyricWidth() {
        return sLyricMaxWidthPx > 0 ? sLyricMaxWidthPx : 0f;
    }

    private static float measureLyricTextWidth(TextView tv, CharSequence text) {
        return tv == null ? 0f
                : tv.getPaint().measureText(text == null ? "" : text.toString());
    }

    private static void animateScrollTo(final TextView tv, float from, float to,
                                        boolean withDelay) {
        cancelScrollAnimator();
        tv.setTranslationX(from);
        // 时长 = 距离 / 速率。下限 1ms 只防除零, 不用 min 时长去破坏匀速。
        float speed = SCROLL_SPEED_PX_PER_MS * scrollSpeedFactor(sCurrentText);
        long duration = Math.max(1L, (long) (Math.abs(to - from) / speed));
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(from, to);
        anim.setDuration(duration);
        if (withDelay) anim.setStartDelay(SCROLL_START_DELAY_MS);
        anim.setInterpolator(new android.view.animation.LinearInterpolator());
        // 明确不重复、不回弹: 播完停在终点, 等下一句切换时才重置。
        anim.setRepeatCount(0);
        anim.setRepeatMode(android.animation.ValueAnimator.RESTART);
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                tv.setTranslationX((Float) a.getAnimatedValue());
            }
        });
        anim.start();
        sScrollAnimator = anim;
    }

    private static void syncScrollToAvailableWidth() {
        FrameLayout container = sLyricView;
        TextView tv = sOutgoingView;
        if (container == null || tv == null) return;
        if (container.getVisibility() != View.VISIBLE) return;
        if (sSwitchAnimator != null) return; // 正在换行, 交给切换结束后的 startSinglePassScroll
        if (sPanelExpanded) return;          // 下拉期间不滚
        CharSequence text = sCurrentText;
        if (text == null) return;

        float target = computeScrollTarget(tv, text);
        if (target >= sScrollTargetX - 0.5f) return; // 终点没有变窄, 无需处理。
        float current = tv.getTranslationX();
        if (current <= target + 0.5f) return; // 已滚到位。
        sScrollTargetX = target;
        // 同样使用固定速率, 与首次滚动速度一致。
        animateScrollTo(tv, current, target, false);
    }

    private static void cancelScrollAnimator() {
        if (sScrollAnimator != null) {
            sScrollAnimator.cancel();
            sScrollAnimator = null;
        }
    }

    private static void cancelSwitchAnimator() {
        if (sSwitchAnimator != null) {
            sSwitchAnimator.cancel();
            sSwitchAnimator = null;
        }
    }

    private static void clearRenderedLyric() {
        cancelSwitchAnimator();
        cancelScrollAnimator();
        sCurrentText = null;
        if (sOutgoingView != null) sOutgoingView.setText(null);
        if (sIncomingView != null) resetTv(sIncomingView);
    }

    private static void hideLyric() {
        FluidCloudLyricHooks.clear();
        FrameLayout container = sLyricView;
        if (container == null) return;
        clearRenderedLyric();
        // 无论容器当前是否可见都尝试还原, 避免状态残留(内部有判断, 重复调用是 no-op)。
        setNotificationNumberMode(false);
        setHiddenForLyric(false);
        if (container.getVisibility() == View.GONE) return;
        container.setVisibility(View.GONE);
        if (sOutgoingView != null) sOutgoingView.setText(null);
        if (sIncomingView != null) sIncomingView.setText(null);
    }

    private static void setNotificationNumberMode(boolean on) {
        if (sNumberModeOn == on) return;
        sNumberModeOn = on;
        NotificationHooks.setLyricNumberMode(on);
        log("statusbar_lyric notification number mode: " + on);
    }

    private static void setHiddenForLyric(boolean hidden) {
        View[] views = sHideViews;
        if (views == null) return;
        if (hidden) {
            if (!sHiddenByUs) {
                sHideOrigVisibility = new int[views.length];
                for (int i = 0; i < views.length; i++) {
                    sHideOrigVisibility[i] = views[i].getVisibility();
                }
                sHiddenByUs = true;
            }
            for (View v : views) v.setVisibility(View.GONE);
        } else if (sHiddenByUs) {
            // 先撤销标记, 否则下面的还原会被 CLOCK_VISIBILITY_HOOK 再次拦成 GONE。
            sHiddenByUs = false;
            for (int i = 0; i < views.length; i++) {
                if (sHideOrigVisibility == null || i >= sHideOrigVisibility.length) continue;
                // 时钟按"系统在隐藏期间最后一次想要的可见性"还原, 其余按隐藏前的值还原。
                int restore = (views[i] == sClockView)
                        ? sClockDesiredVisibility : sHideOrigVisibility[i];
                views[i].setVisibility(restore);
            }
        }
    }
}
