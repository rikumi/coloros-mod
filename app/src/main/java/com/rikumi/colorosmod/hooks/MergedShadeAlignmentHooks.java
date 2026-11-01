package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.res.Configuration;
import android.view.View;
import android.view.WindowInsets;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class MergedShadeAlignmentHooks {
    private static Class<?> stackClass, separateClass;
    private static final WeakHashMap<View, Alignment> stacks = new WeakHashMap<>();
    private static boolean restoring;

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            separateClass = XposedHelpers.findClass("com.android.systemui.util.SeparateModeHelper", pkg.classLoader);
            stackClass = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", pkg.classLoader);
            XposedHelpers.findAndHookMethod(stackClass, "onLayout", boolean.class, int.class, int.class,
                    int.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { track((View) p.thisObject); }
            });
            XposedHelpers.findAndHookMethod(stackClass, "onDetachedFromWindow", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View stack = (View) p.thisObject;
                    Alignment alignment = stacks.remove(stack);
                    if (alignment != null) alignment.remove();
                }
            });
            Class<?> ext = XposedHelpers.findClass("com.oplus.systemui.statusbar.notification.stack.NotificationStackScrollLayoutExtImpl", pkg.classLoader);
            for (String method : new String[]{"getTopPadding", "getTopPaddingFloat"}) {
                // ColorOS 17 removed these vendor getters. The native updateTopPadding and
                // clipping hooks below must still be installed to keep notifications below the date.
                try { ext.getDeclaredMethod(method); }
                catch (NoSuchMethodException removedGetter) { continue; }
                XposedHelpers.findAndHookMethod(ext, method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        View stack = (View) XposedHelpers.callMethod(p.thisObject, "getView");
                        Float top = targetPadding(stack);
                        if (top == null) return;
                        if (method.equals("getTopPadding")) p.setResult(Math.round(top));
                        else p.setResult(top);
                    }
                });
            }
            XposedHelpers.findAndHookMethod(stackClass, "updateTopPadding", float.class, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Float top = targetPadding((View) p.thisObject);
                    if (top != null) p.args[0] = top;
                }
            });
            XposedHelpers.findAndHookMethod(stackClass, "updateRoundedClipPath", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View stack = (View) p.thisObject;
                    Float padding = targetPadding(stack);
                    if (padding == null) return;
                    int original = XposedHelpers.getIntField(stack, "mRoundedRectClippingTop");
                    p.setObjectExtra("mergedNativeClipTop", original);
                    int translation = XposedHelpers.getIntField(stack, "mRoundedRectClippingYTranslation");
                    // 原生路径仍依据单栏 QS 底部裁切；使用与首条通知相同的窗口坐标。
                    int top = Math.max(0, Math.round(padding + scrimPadding(stack)));
                    XposedHelpers.setIntField(stack, "mRoundedRectClippingTop", top - translation);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object original = p.getObjectExtra("mergedNativeClipTop");
                    if (original != null) XposedHelpers.setIntField(p.thisObject, "mRoundedRectClippingTop", (Integer) original);
                }
            });
            Class<?> position = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel$HorizontalPosition", pkg.classLoader);
            Object edge = XposedHelpers.getStaticObjectField(XposedHelpers.findClass(position.getName() + "$EdgeToEdge", pkg.classLoader), "INSTANCE");
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer", pkg.classLoader,
                    "updateConstraints", position, int.class, int.class, int.class, int.class, float.class, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View shared = (View) p.thisObject;
                    View stack = shared.findViewById(id(shared, "notification_stack_scroller"));
                    boolean split = MergedSplitShadeHooks.enabled(shared.getResources());
                    if (!split && !active(stack)) return;
                    p.args[0] = edge;
                    p.args[2] = 0;
                    // 分栏外层已限定通知宽度；锁屏及编辑切换都不能再次取右半栏或叠加边距。
                    if (split) { p.args[1] = 0; p.args[3] = 0; }
                }
            });
        } catch (Throwable error) { log("merged notification alignment hook failed: " + error); }
    }

    private static int id(View view, String name) {
        return NotificationResourceIds.get(view.getResources(), name, "id", "com.android.systemui");
    }

    private static boolean active(View stack) {
        if (restoring || stack == null || separateClass == null
                || stack.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE
                || XposedHelpers.getIntField(stack, "mStatusBarState") == 1) return false;
        Object separate = XposedHelpers.getStaticObjectField(separateClass, "INSTANCE");
        if (Boolean.TRUE.equals(XposedHelpers.callMethod(separate, "getSeparateMode"))) return false;
        Object ambient = XposedHelpers.getObjectField(stack, "mAmbientState");
        if (XposedHelpers.getBooleanField(ambient, "mDozing")) return false;
        if (MergedSplitShadeHooks.enabled(stack.getResources())) return true;
        View qs = stack.getRootView().findViewById(id(stack, "quick_settings_container"));
        return stack.getResources().getConfiguration().smallestScreenWidthDp < 600
                && MergedLandscapeHooks.enabled(qs);
    }

    private static Float targetPadding(View stack) {
        if (!active(stack) || !stack.isLaidOut()) return null;
        View root = stack.getRootView();
        View editor = root.findViewById(id(stack, "qs_customize"));
        // Both ROM versions retain these fields; ColorOS 17 removed isCustomizing().
        boolean editing = editor != null && (editor.isShown()
                || XposedHelpers.getBooleanField(editor, "mCustomizing")
                || XposedHelpers.getBooleanField(editor, "mOpening"));
        Alignment alignment = stacks.get(stack);
        int[] location = new int[2];
        root.getLocationInWindow(location);
        float rootTop = location[1];
        float top = Float.POSITIVE_INFINITY;
        if (editing && alignment != null && alignment.cardTop != null) {
            // 编辑时卡片变成 INVISIBLE；继续使用进入编辑前的锚点，不跟随隐藏视图的动画。
            top = rootTop + alignment.cardTop;
        } else {
            View cardsView = root.findViewById(id(stack, "oplus_qs_tile_media_container"));
            if (!(cardsView instanceof ViewGroup cards) || (!editing && !cards.isShown())
                    || !cards.isLaidOut()) return null;
            for (String field : new String[]{"mFirstTileContainer", "mSecondTileContainer", "mQsMediaPanelContainer", "mQsSeekBarContainer"}) {
                View card = (View) XposedHelpers.getObjectField(cards, field);
                if (card == null || (editing ? card.getVisibility() == View.GONE
                        : card.getVisibility() != View.VISIBLE) || card.getHeight() <= 0) continue;
                card.getLocationInWindow(location);
                top = Math.min(top, location[1]);
            }
            if (!Float.isFinite(top)) return null;
            if (alignment != null) alignment.cardTop = top - rootTop;
        }
        stack.getLocationInWindow(location);
        WindowInsets insets = stack.getRootWindowInsets();
        if (insets != null) top = Math.max(top,
                insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout()).top);
        return MergedLandscapeGeometry.alignedTopPadding(top, location[1], scrimPadding(stack));
    }

    private static float scrimPadding(View stack) {
        Object algorithm = XposedHelpers.getObjectField(stack, "mStackScrollAlgorithm");
        Object ambient = XposedHelpers.getObjectField(stack, "mAmbientState");
        return ((Number) XposedHelpers.callMethod(algorithm, "getScrimTopPaddingOrZero", ambient)).floatValue();
    }

    private static void track(View stack) {
        if (stacks.containsKey(stack)) return;
        Alignment alignment = new Alignment(stack);
        stacks.put(stack, alignment);
        stack.getViewTreeObserver().addOnPreDrawListener(alignment);
    }

    private static final class Alignment implements ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> stackRef;
        final ViewTreeObserver observer;
        Float applied, cardTop;
        Alignment(View stack) { stackRef = new WeakReference<>(stack); observer = stack.getViewTreeObserver(); }
        @Override public boolean onPreDraw() {
            View stack = stackRef.get();
            if (stack == null) return true;
            try {
                Float top = targetPadding(stack);
                if (top == null) { restore(stack); return true; }
                if (applied != null && Math.abs(applied - top) < .5f) return true;
                applied = top;
                XposedHelpers.callMethod(stack, "updateTopPadding", top, false);
                XposedHelpers.callMethod(stack, "updateStackPosition", false);
                XposedHelpers.callMethod(stack, "requestChildrenUpdate$1");
                XposedHelpers.callMethod(stack, "updateRoundedClipPath");
                stack.invalidate();
            } catch (Throwable error) { log("merged notification alignment failed: " + error); remove(); }
            return true;
        }
        void restore(View stack) {
            cardTop = null;
            if (applied == null) return;
            applied = null;
            // 原生 getter 读取当前时钟/面板位置；不能把进入分栏前的旧 padding 写回锁屏。
            float top = ((Number) XposedHelpers.callMethod(stack, "getTopPadding")).floatValue();
            XposedHelpers.callMethod(stack, "updateTopPadding", top, false);
            XposedHelpers.callMethod(stack, "updateStackPosition", false);
            XposedHelpers.callMethod(stack, "requestChildrenUpdate$1");
            XposedHelpers.callMethod(stack, "updateRoundedClipPath");
        }
        void remove() { if (observer.isAlive()) observer.removeOnPreDrawListener(this); }
    }

    static ArrayList<Object> capture() { return new ArrayList<>(stacks.keySet()); }
    static void restoreHosts(Iterable<?> hosts) {
        for (Object host : hosts) if (stackClass != null && stackClass.isInstance(host)) track((View) host);
    }
    static void cleanup() {
        restoring = true;
        try {
            for (View stack : new ArrayList<>(stacks.keySet())) {
                Alignment alignment = stacks.get(stack);
                alignment.remove();
                alignment.restore(stack);
            }
        } finally { stacks.clear(); restoring = false; }
    }
}
