package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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

// 状态栏歌词(com.android.systemui)。数据源复用 ColorOS 自己的歌词接口: metadata.getString("lyricInfo")
// 返回 {songName, artist, lyric, songId} 的 JSON, lyric 即歌词原文, 故无需私有协议、也无需注入音乐软件进程。
// 控制中心显示歌名而全屏显示歌词并非数据源不同, 而是 displayPolicy 位掩码不同(16 vs 32), 本功能不受其限制。
public final class StatusBarLyricHooks {

    // ---- ColorOS 歌词接口常量(与 OplusMediaLyricData 一致) ----
    private static final String METADATA_KEY_LYRIC_INFO = "lyricInfo";
    private static final String LYRIC_INFO_KEY_LYRIC = "lyric";
    // ---- 解析用常量(与 LyricParser 一致) ----
    private static final String LYRIC_CONTEXT = "c";
    private static final String LYRIC_TEXT = "tx";
    private static final String LYRIC_TIME = "t";

    private static final int STATE_PLAYING = 3;
    /** 歌词轮询间隔(ms)。歌词最短切换间隔通常 >200ms, 250ms 足够跟手且开销很小。 */
    private static final long TICK_INTERVAL_MS = 250L;

    private static final String CLS_PHONE_STATUS_BAR_VIEW =
            "com.android.systemui.statusbar.phone.PhoneStatusBarView";

    // 显示歌词时只隐藏**时钟本身**(不隐藏 status_bar_start_side_except_heads_up 整块):
    // 歌词挂在 notification_icon_area 之后, 隐藏整块会把歌词容器一起藏掉。
    // clock 是 StatClock, 父容器 clock_for_fake 为 wrap_content, 故 clock GONE 后它宽度归零。
    private static final String[] HIDE_VIEW_IDS = {"clock"};

    /** 歌词字体的字重: medium(500)。 */
    private static final int MEDIUM_WEIGHT = 500;

    // 歌词整体**视觉上移**校准(dp)。布局参数已与时钟一致, 但字体基线与时钟存在 1dp 观感差,
    // 用 translationY 做纯绘制偏移: 不影响测量、不参与布局, 不干扰宽度计算。
    private static final float LYRIC_VERTICAL_OFFSET_DP = 1f;

    /** 歌词与左侧通知图标之间的外边距(dp): 位于歌词盒子**之外**, 歌词永远画不到它里面。 */
    private static final int LYRIC_MARGIN_START_DP = 2;
    // 歌词右边缘与流体云之间保留的间距(dp)。流体云(CapsulePluginContainer)覆盖在状态栏最上层,
    // 贴着它裁切会让歌词边缘被压住, 故右边界要再往左让出这一段。
    private static final int LYRIC_GAP_BEFORE_FLUID_DP = 4;

    /** 超长歌词开始横向滚动前的停顿(ms): 先让人看清句首, 再开始滚。 */
    private static final long SCROLL_START_DELAY_MS = 500L;
    // 横向滚动的**固定速率**(px/ms): 时长 = 距离 / 速率, 与歌词长度无关。
    // 按固定速率而非固定时长, 长句才不会越滚越快、短句也不会慢得离谱。0.1f 约合 10px/100ms。
    private static final float SCROLL_SPEED_PX_PER_MS = 0.1f;
    // 全西文(ASCII 占比 100%)时的速率倍率: 西文字母窄、信息密度低, 可以滚得更快;
    // 纯中文/日文等全角字符时保持基准速率。中间按 ASCII 占比线性插值。
    private static final float SCROLL_SPEED_ASCII_FACTOR = 2f;
    /** 歌词字幕容器(FrameLayout, 内部两个 TextView 做向上切换动画)。 */
    private static FrameLayout sLyricView = null;
    /** 当前显示的(将被换下的)字幕视图。 */
    private static TextView sOutgoingView = null;
    /** 即将显示的(换上的)字幕视图。 */
    private static TextView sIncomingView = null;
    /** 当前显示中的单次横向滚动动画。 */
    private static android.animation.ValueAnimator sScrollAnimator = null;
    /** 当前显示中的向上切换动画集。 */
    private static android.animation.AnimatorSet sSwitchAnimator = null;
    /** 缓存的歌词可用宽度(宿主容器内, 到挖孔或流体云为止); 0 表示未测量。 */
    private static int sLyricMaxWidthPx = 0;
    // 当前已显示在状态栏上的歌词文本。tick 每 250ms 调 showLyric, 靠它判断"文本是否真的变了",
    // 相同则直接返回, 避免动画被反复重启(表现为滚到头又回起点、切换动画反复播放)。
    private static volatile CharSequence sCurrentText = null;
    // 当前这句歌词的横向滚动终点(translationX, 非正数)。
    // 可用宽度变化(流体云出现)后据此判断终点是否变窄, 决定要不要接着往下滚。
    private static float sScrollTargetX = 0f;
    private static View[] sHideViews = null;
    private static int[] sHideOrigVisibility = null;
    private static boolean sHiddenByUs = false;

    /** 状态栏根布局(PhoneStatusBarView): tick 里要按 id 找 cutout/流体云, 必须从根布局找。 */
    private static View sStatusBarRoot = null;
    /** 时钟 View: 歌词显示期间强制隐藏, 见 {@link #hookClockVisibility}。 */
    private static volatile View sClockView = null;
    /** 系统最近一次想给时钟设置的可见性; 歌词结束后按它还原。 */
    private static int sClockDesiredVisibility = View.VISIBLE;
    /** 已经 hook 过 setVisibility 的类, 避免重复 hook。 */
    private static final Set<Class<?>> sVisibilityHooked = new HashSet<>();

    // ---- 下拉通知栏/控制中心时隐藏歌词: 复用 QsHooks#hookQsTopMargin 同一个展开进度回调 ----
    /** 当前是否处于下拉/展开状态(由 QS 展开进度驱动)。 */
    private static boolean sPanelExpanded = false;
    /** 通知图标区当前是否已被我们切成"只显示数字"(仅用于避免每拍重复下发)。 */
    private static boolean sNumberModeOn = false;

    private static volatile Handler sMainHandler = null;
    private static volatile MediaSessionManager sSessionManager = null;
    private static volatile boolean sMediaInited = false;
    private static final Set<MediaController> sRegistered = new HashSet<>();

    /** 当前正在播放的会话。tick 每次读取它, 这样切换播放器后无需重建轮询。 */
    private static volatile MediaController sController = null;
    /** 当前歌词行; 为 null 表示无歌词。 */
    private static List<Line> sLines = null;
    /** 歌词缓存标识: 歌词原文整体变化才重新解析, 避免每次 metadata 更新都重解析。 */
    private static String sLyricSource = null;
    private static boolean sTicking = false;

    // 歌词的裁切窗口。字幕 TextView 比容器宽(整句不换行), 靠 translationX 滚动, 故左右两侧都必须裁在盒子里:
    // 左侧 = 通知图标右侧 + 4dp 外边距, 右侧 = 右边界(挖孔 / 流体云左侧再让 8dp)。不依赖 setClipChildren
    // (它在被 RenderNode 动画驱动的子 View 上不可靠), 直接在 dispatchDraw 里 clipRect, 保证每帧都按盒子裁。
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

    /** 一行歌词。 */
    private static final class Line {
        final long timeMs;
        final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    // 下拉通知栏/控制中心的瞬间隐藏歌词。复用 QsHooks 隐藏图标簇已在用的 QS 展开进度回调 onFractionChanged,
    // fraction 第一次 > 0 就触发、早于原生位移动画。自己读面板 translationY 行不通: 面板与状态栏是两个独立
    // 窗口、视图树不相通, 且位移发生在动画开始后, 必然慢一帧。
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
        }
    }

    /** 面板展开状态变化: 切到主线程改歌词可见性。 */
    private static void setPanelExpanded(boolean expanded) {
        if (expanded == sPanelExpanded) return;
        sPanelExpanded = expanded;
        runOnMain(() -> setLyricHiddenForPanel(expanded));
    }

    public static void hookStatusBarLyric(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 通知图标区仓库由 NotificationHooks#hookNativeNotificationIcon 抓取并统一下发显示模式,
        // 这里不再重复 hook, 避免两处各写一份造成互相拉扯(图标 <-> 数字来回跳)。
        hookStatusBarView(lpparam);
        hookQsExpansion(lpparam);
    }

    // 歌词显示期间**强制**时钟保持隐藏。时钟可见性会被 SystemUI 在锁屏、下拉通知、Dock 等时机动态切换,
    // 光靠主动 setVisibility(GONE) 会被改回来。故拦截 setVisibility: 隐藏期间任何想显示时钟的调用都改成 GONE,
    // 并记下系统真正想要的值以便还原。只在时钟所属类链上 hook(子类覆写时父类 hook 不生效), 从子类一路到 View。
    private static final XC_MethodHook CLOCK_VISIBILITY_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!sHiddenByUs) return;
            if (param.thisObject != sClockView) return;
            int visibility = (Integer) param.args[0];
            sClockDesiredVisibility = visibility;
            if (visibility != View.GONE) param.args[0] = View.GONE;
        }
    };

    // 状态栏前景色(深/浅)变化由 DarkIconDispatcher 推给各 DarkReceiver, 时钟是其中之一。
    // 在它刷色之后把颜色同步给歌词, 是最贴近系统口径的做法 —— 不用自己解析 tint/暗色强度。
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
            XposedHelpers.findAndHookMethod(
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
        sStatusBarRoot = root;
    // 宿主容器: 通知图标所在的 status_bar_start_side_content_for_fake(LinearLayout, 高 fill_parent)。
    // 歌词插在 notification_icon_area **之后**, 紧跟通知图标(数字)右边, 垂直位置与时钟一致。
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

    // 挂在 notification_icon_area 之后。用与同排子 View 相同的参数(fill_parent 高 + center_vertical),
    // 再靠 TextView 的 gravity="start|center" 在盒子内居中, 垂直位置与时钟一致, 无需测量/translationY 校正。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        // 歌词与左边的通知图标之间留 4dp(会被 updateLyricWidth 计入起点, 因此可用宽度仍正确)。
        lp.leftMargin = Math.round(dpToPx(ctx, LYRIC_MARGIN_START_DP));
        int index = (icons != null && icons.getParent() == clockHost)
                ? clockHost.indexOfChild(icons) + 1 : -1;
        if (index > 0) clockHost.addView(container, index, lp);
        else clockHost.addView(container, lp);
        final LinearLayout hostFinal = clockHost;
        container.post(new Runnable() {
            @Override
            public void run() {
                updateLyricWidth(root, hostFinal, container);
            }
        });

        sLyricView = container;
        sHideViews = collectHideViews(root);
        sHiddenByUs = false;
        // 时钟会被系统动态显示/隐藏, 必须拦住 setVisibility, 否则歌词旁会冒出时钟。
        hookClockVisibility(clock);
        if (sMainHandler == null) sMainHandler = new Handler(Looper.getMainLooper());
        log("statusbar_lyric view attached");
    }

    /** dp -> px。 */
    private static float dpToPx(Context ctx, float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }

    // 下拉通知栏 / 展开控制中心期间隐藏歌词, 收起后恢复。触发点与 QsHooks 隐藏控制中心图标簇**完全相同**
    // (同一个 onFractionChanged 回调), 故与图标簇同一瞬间消失。这里只动自己的歌词容器, 不碰时钟的强制
    // 隐藏状态、也不还原通知图标模式, 免得下拉过程中状态反复横跳。隐藏不清除 sCurrentText, 收起后直接续滚。
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

    // 测量歌词容器的左边缘与可用宽度(到挖孔/流体云为止)并缓存。容器是 notification_icon_area 的下一个兄弟,
    // 故起点 = 宿主左边缘 + 前面兄弟宽度(含 margin); 不能直接量容器自己(隐藏时 GONE, 坐标不更新)。右边界取三者
    // 最小: 左半容器右边缘、挖孔 cutout_space_view 左边缘、流体云左边缘。不能用 clock 定位(隐藏后坐标返回 0)。
    private static void updateLyricWidth(View root, View host, FrameLayout container) {
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

            // 流体云: 递归找 seeding_card_container 内可见内容的最小左边缘。
            // 右边界要停在它**左侧再让出一段距离**, 否则歌词边缘会贴着/被压在流体云下面。
            // 流体云不存在时 findLeftmost... 返回 MAX_VALUE, 自然不参与收窄。
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

    // 速率倍率随西文(ASCII)字符占比线性插值: 全 ASCII -> SCROLL_SPEED_ASCII_FACTOR 倍, 全非 ASCII -> 1 倍。
    // 西文字符窄、单位像素信息量低, 同速率下读起来偏慢; 中文等全角字符宽, 需保持基准速率。
    // 空白不计入占比统计, 免得英文句子里的空格把倍率顶上去。
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

    /** 某个 View 的左边缘(相对状态栏根布局)。 */
    private static int leftOf(View v, int rootLeft) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return loc[0] - rootLeft;
    }

    // 递归找容器内实际可见内容的最小左边缘(相对根布局)。用于流体云: CapsulePluginContainer 铺满状态栏
    // 但内容只占一小段, 所以**不能**在第一个有宽度的子 View 上就停(外层容器 left 恒为 0), 必须一路下钻。
    // 故对每个 ViewGroup 都继续递归, 取全局最小且大于 minValid 的 left。
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

    // 歌词用 medium 字重: 时钟是 semibold, 直接抄会偏粗。优先按字重 500 从原字体族派生(API 28+, 保留
    // 厂商字体族); 低版本回退到系统 sans-serif-medium。italic 一律关掉, 歌词不需要斜体。
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

    // 歌词颜色跟随状态栏前景色(深浅): 时钟作为 DarkReceiver 会经 StatClock#onDarkChanged 被系统刷色,
    // 这里把它刷新后的最终颜色同步过来, 保证切壁纸/进浅色应用时歌词与时钟同色。
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

    // 把字幕 TextView 的宽度设成**整句文本的真实宽度**, 让它完整承载歌词、自身不被裁切。
    // 裁切只能发生在歌词容器上, 被平移的必须是完整文本; 若 TextView 宽度 = 容器宽度, 文本会先被
    // 截断再整体平移, 就会看到"半个字/半句在窗口里滑"。宽度须 EXACTLY, 否则会被 AT_MOST 压回容器宽度。
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

    private static final MediaSessionManager.OnActiveSessionsChangedListener sSessionListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    refreshSessions(controllers);
                }
            };

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
            };

    private static void initMediaListener(Context ctx) {
        if (sMediaInited) return; // 只初始化一次; 视图重建不重复注册。
        final Context appCtx = ctx.getApplicationContext();
        sMainHandler = new Handler(Looper.getMainLooper());
        sMainHandler.post(new Runnable() {
            @Override
            public void run() {
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
                    refreshSessions(msm.getActiveSessions(null));
                    sMediaInited = true;
                    log("statusbar_lyric media listener inited");
                } catch (Throwable t) {
                    log("statusbar_lyric initMediaListener error: " + t);
                }
            }
        });
    }

    private static void refreshSessions(List<MediaController> controllers) {
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
        for (MediaController c : list) {
            if (!sRegistered.contains(c)) {
                try {
                    c.registerCallback(sControllerCallback);
                    sRegistered.add(c);
                } catch (Throwable t) {
                    log("statusbar_lyric registerCallback error: " + t);
                }
            }
        }
        refreshLyric();
    }

    /** 找到正在播放的会话, 必要时重新解析歌词, 然后刷新显示。 */
    private static void refreshLyric() {
        try {
            if (!readBool(KEY_STATUSBAR_LYRIC_ENABLED, false)) {
                stopTicking();
                runOnMain(StatusBarLyricHooks::hideLyric);
                return;
            }
            MediaSessionManager msm = sSessionManager;
            if (msm == null) return;
            List<MediaController> list = msm.getActiveSessions(null);
            if (list == null || list.isEmpty()) {
                stopTicking();
                runOnMain(StatusBarLyricHooks::hideLyric);
                return;
            }

            MediaController playing = null;
            for (MediaController c : list) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == STATE_PLAYING) {
                    playing = c;
                    break;
                }
            }
            if (playing == null) {
                sController = null;
                stopTicking();
                runOnMain(StatusBarLyricHooks::hideLyric);
                return;
            }
            sController = playing;

            // 歌词: metadata -> "lyricInfo"(JSON) -> "lyric"(原文)
            MediaMetadata md = playing.getMetadata();
            String raw = null;
            if (md != null) {
                String info = md.getString(METADATA_KEY_LYRIC_INFO);
                if (!TextUtils.isEmpty(info)) {
                    try {
                        raw = new JSONObject(info).optString(LYRIC_INFO_KEY_LYRIC, null);
                    } catch (Throwable t) {
                        log("statusbar_lyric parse lyricInfo error: " + t);
                    }
                }
            }
            if (raw == null || raw.trim().length() == 0) {
                stopTicking();
                runOnMain(StatusBarLyricHooks::hideLyric);
                return;
            }
            if (!raw.equals(sLyricSource)) {
                sLines = parseLyric(raw);
                sLyricSource = raw;
            }
            startTicking(playing);
        } catch (Throwable t) {
            log("statusbar_lyric refreshLyric error: " + t);
        }
    }

    /** 按播放进度定时刷新当前行。 */
    private static void startTicking(final MediaController controller) {
        if (sTicking) return;
        sTicking = true;
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                sTicking = false;
                try {
                    if (!readBool(KEY_STATUSBAR_LYRIC_ENABLED, false)) {
                        runOnMain(StatusBarLyricHooks::hideLyric);
                        return;
                    }
                    PlaybackState st = controller.getPlaybackState();
                    if (st == null || st.getState() != STATE_PLAYING) {
                        runOnMain(StatusBarLyricHooks::hideLyric);
                        return;
                    }
                    long pos = estimatePosition(st);
                    CharSequence text = findLineAt(pos);
                    // 流体云会动态出现/消失, 每次 tick 重算可用宽度(内部会跳过无变化的情况)。
                    // 必须从状态栏根布局开始找 cutout/流体云, 宿主只是通知图标那一层。
                    final FrameLayout c = sLyricView;
                    final View root = sStatusBarRoot;
                    if (c != null && root != null && c.getParent() instanceof View) {
                        updateLyricWidth(root, (View) c.getParent(), c);
                    }
                    if (text == null) {
                        runOnMain(StatusBarLyricHooks::hideLyric);
                    } else {
                        final CharSequence t2 = text;
                        runOnMain(() -> {
                            // 每拍都重新声明一次(showLyric 文本未变时会提前返回):
                            // 时钟可见性会被系统动态改, 通知图标模式也可能被系统回滚。
                            setHiddenForLyric(true);
                            setNotificationNumberMode(true);
                            // 兜底同步颜色: 时钟可能经 onDarkChanged 之外的路径刷色。
                            syncLyricTextColor();
                            showLyric(t2);
                        });
                    }
                } catch (Throwable t) {
                    log("statusbar_lyric tick error: " + t);
                }
                // 继续下一拍(无论本次结果如何, 只要还在播放就保持轮询)。
                refreshLyric();
            }
        };
        Handler h = sMainHandler;
        if (h != null) h.postDelayed(tick, TICK_INTERVAL_MS);
    }

    private static void stopTicking() {
        // 轮询靠 startTicking 的 sTicking 标志自终止: 置 false 后下一个 tick 不再续期。
        sTicking = false;
    }

    // 估算当前播放位置: PlaybackState 的 position 是上次更新的快照,
    // 需按 speed 与经过时间外推, 否则歌词会滞后。
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

    /** 占位歌词: 这类文本不是真正的歌词, 命中时不应显示歌词、应恢复时钟。 */
    private static final Pattern PLACEHOLDER_LYRIC_PATTERN =
            Pattern.compile("(该歌曲)?暂?(无|没有)歌词");

    /** 文本是否为"纯音乐/无歌词"类占位串(命中则不显示歌词)。 */
    private static boolean isPlaceholderLyric(CharSequence text) {
        if (text == null) return false;
        String s = text.toString().trim();
        if (s.isEmpty()) return false;
        if (s.contains("纯音乐，请欣赏") || s.contains("纯音乐,请欣赏")) return true;
        Matcher m = PLACEHOLDER_LYRIC_PATTERN.matcher(s);
        return m.find();
    }

    /** 取最后一个 timeMs <= position 的行; 命中占位歌词时返回 null(交给上层隐藏并恢复时钟)。 */
    private static CharSequence findLineAt(long positionMs) {
        List<Line> lines = sLines;
        if (lines == null || lines.isEmpty()) return null;
        CharSequence result = null;
        for (Line l : lines) {
            if (l.timeMs <= positionMs) result = l.text;
            else break;
        }
        if (result != null && isPlaceholderLyric(result)) return null;
        return result;
    }

    // 解析歌词原文, 兼容两种格式(与 SystemUI 的 LyricParser 一致), 解析后按时间升序排序:
    // LRC "[mm:ss.xx]歌词"(时间 = 分*60000 + 秒*1000 + 厘秒*10);
    // JSON 每行 {"t":毫秒, "c":[{"tx":"文本"}, ...]}, c 内 tx 拼接成整句。
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
                if (!times.isEmpty() && i < line.length()) {
                    String text = line.substring(i).trim();
                    if (text.length() > 0) {
                        for (Long t : times) out.add(new Line(t, text));
                    }
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
                        if (sb.length() > 0) out.add(new Line(t, sb.toString()));
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

    /** LRC 时间标签 "[mm:ss.xx]" -> 毫秒。 */
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
        // attachLyricView 之前就可能收到回调(例如 QS 展开进度), 这里兜底建主线程 Handler,
        // 否则事件会被丢弃、展开状态与视图不同步。
        if (sMainHandler == null) sMainHandler = new Handler(Looper.getMainLooper());
        Handler h = sMainHandler;
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else h.post(r);
    }

    /** 歌词文本变化时调用: 向上滚动切换下一句。 */
    private static void showLyric(CharSequence text) {
        FrameLayout container = sLyricView;
        if (container == null) return;
        TextView outgoing = sOutgoingView;
        TextView incoming = sIncomingView;
        if (outgoing == null || incoming == null) return;

        // 【关键】与已显示文本相同则直接返回, 不重启任何动画。
        // tick 每 250ms 调用一次, 若这里不拦住, 横向滚动会被反复 cancel+重启(表现为滚到头又回起点),
        // 垂直切换动画也会被反复打断重建(表现为反复播放)。
        if (sCurrentText != null && TextUtils.equals(sCurrentText, text)) return;
        sCurrentText = text;

        // 每次真正换文本都重新声明一次: 隐藏时钟 + 通知图标切"只显示数字"。
        // 两者内部都有状态判断, 重复调用只是 no-op, 可以防住 SystemUI 把可见性改回去。
        setHiddenForLyric(true);
        setNotificationNumberMode(true);

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

        // ---- 向上切换: 旧字幕向上滚出, 新字幕从下向上滚入。 ----
        // 若上一个切换动画还在播, 先让它收尾(把角色交换完成), 避免角色错乱。
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

    // 立即完成"角色交换": incoming 变为当前显示, outgoing 变为备用并复位。
    // 与动画结束回调共用, 保证动画被打断时状态也一致。
    private static void finishSwitchNow() {
        TextView outgoing = sOutgoingView;
        TextView incoming = sIncomingView;
        if (outgoing == null || incoming == null) return;
        TextView tmp = outgoing;
        sOutgoingView = incoming;
        sIncomingView = tmp;
        resetTv(tmp);
    }

    /** 复位备用字幕: 隐藏、清空、变换归零。 */
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

    // 歌词超长时单向往左滚到尽头停下(不来回、不重复), 只在新文本首次显示/切换动画结束时调用一次。
    // 被平移的是**完整承载整句**的 TextView(见 applyTextWidth), 它比容器宽多少就滚多少;
    // 容器是固定的裁切窗口, 不参与平移。
    private static void startSinglePassScroll(final TextView tv, final CharSequence text) {
        cancelScrollAnimator();
        if (tv == null) return;
        tv.setTranslationX(0f);
        sScrollTargetX = 0f;
        tv.post(new Runnable() {
            @Override
            public void run() {
                if (sOutgoingView != tv) return;
                float target = computeScrollTarget(tv, text);
                sScrollTargetX = target;
                if (target >= 0f) return; // 装得下, 不用滚。
                animateScrollTo(tv, 0f, target, true);
            }
        });
    }

    // 这句歌词的横向滚动终点(translationX, 非正数; 0 表示装得下不用滚)。
    // 终点 = 可用宽度 - 文本宽度, 其中可用宽度是**裁切窗口的宽度**, 不是 TextView 自己的宽度。
    private static float computeScrollTarget(TextView tv, CharSequence text) {
        if (tv == null) return 0f;
        float textWidth = tv.getPaint().measureText(text == null ? "" : text.toString());
        float available = sLyricMaxWidthPx > 0 ? sLyricMaxWidthPx : 0f;
        return Math.min(0f, available - textWidth);
    }

    // 从 from 单向滚到 to 后停下(不来回、不重复), withDelay=true 时带起始停顿。
    // 时长按固定速率 SCROLL_SPEED_PX_PER_MS 由距离算出, 与歌词长度无关。
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

    // 可用宽度变窄(流体云出现)后让已滚到位的歌词**接着往下滚**: 流体云可能在整句滚完后才出现,
    // 右边界被它收窄后原终点不够用, 尾巴会一直被挡住。故检测终点变化后从当前位置续滚到新终点。
    // 只在终点变窄时续滚(变宽则不动, 免得来回抖); 续滚不加起始停顿 —— 它是被打断滚动的延续。
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
        if (target >= sScrollTargetX - 0.5f) return; // 终点没变窄, 无需处理。
        float current = tv.getTranslationX();
        if (current <= target + 0.5f) return;        // 已经滚到位。

        sScrollTargetX = target;
        // 同样是固定速率, 与首次滚动的速度完全一致。
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

    private static void hideLyric() {
        FrameLayout container = sLyricView;
        if (container == null) return;
        cancelSwitchAnimator();
        cancelScrollAnimator();
        // 无论容器当前是否可见都尝试还原, 避免状态残留(内部有判断, 重复调用是 no-op)。
        sCurrentText = null;
        setNotificationNumberMode(false);
        setHiddenForLyric(false);
        if (container.getVisibility() == View.GONE) return;
        container.setVisibility(View.GONE);
        if (sOutgoingView != null) sOutgoingView.setText(null);
        if (sIncomingView != null) sIncomingView.setText(null);
    }

    // 显示歌词期间把通知图标区切成"只显示数字", 结束时还原。下发统一交给 NotificationHooks:
    // 它同时管着"恢复原生通知图标"的强制"显示图标", 两边共用一个目标值才不会互相拉扯。
    // 儿童模式/专注模式下仓库仍输出 NOTIFICATION_NOT_SHOW, 是系统行为, 保留。
    private static void setNotificationNumberMode(boolean on) {
        if (sNumberModeOn == on) return;
        sNumberModeOn = on;
        NotificationHooks.setLyricNumberMode(on);
        log("statusbar_lyric notification number mode: " + on);
    }

    /** 显示歌词时隐藏时钟; 结束时还原。 */
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
