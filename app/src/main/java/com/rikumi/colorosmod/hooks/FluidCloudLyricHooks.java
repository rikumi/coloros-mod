package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.view.Gravity;
import android.graphics.Rect;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;

import com.rikumi.colorosmod.xposed.XposedHelpers;

public final class FluidCloudLyricHooks {
    private static final String CAPSULE_VIEW =
            "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleView";
    private static View sCapsule;
    private static View sContentView;
    private static ViewGroup sSection;
    private static View sText;
    private static ViewGroup sOriginalHost;
    private static ViewGroup.LayoutParams sOriginalParams;
    private static int sOriginalIndex;
    private static float sOriginalTranslationY;
    private static float sOriginalAlpha;
    private static final Set<Object> sTimeoutTasks = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Class<?>> sTimeoutClasses = new HashSet<>();
    private static final Set<ClassLoader> sPluginLoaders = new HashSet<>();
    private static boolean sApplyFailureLogged;
    private static final ThreadLocal<Boolean> sMusicOccupancy = new ThreadLocal<>();
    private static Object sOccupancyController, sOccupancyLocation;
    private static int sReportedHalfWidth = -2;
    private static boolean showingCloudLyrics() {
        return enabled() && isHosting(sText);
    }

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> location = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.StatusBarLocation", lpparam.classLoader);
            Object homeLocation = XposedHelpers.getStaticObjectField(location, "HOME");
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.statusbar.seeding.CapsulePluginContainerController",
                    lpparam.classLoader, "notifyRect", location, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            boolean home = param.args[0] == homeLocation;
                            if (home) {
                                sOccupancyController = param.thisObject;
                                sOccupancyLocation = param.args[0];
                            }
                            View root = (View) XposedHelpers.getObjectField(param.thisObject, "capsulePluginContainer");
                            sMusicOccupancy.set(home && sCapsule != null && belongsTo(root, sCapsule)
                                    && FluidCloudLyricGeometry.statusIconHalfWidth() >= 0);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            sMusicOccupancy.remove();
                        }
                    });
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.statusbar.seeding.HiddenIconDispatcherImpl",
                    lpparam.classLoader, "setHiddenIconAreas", location, List.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(sMusicOccupancy.get())) return;
                            int half = FluidCloudLyricGeometry.statusIconHalfWidth();
                            if (half < 0) return;
                            List<Rect> corrected = new ArrayList<>();
                            for (Object value : (List<?>) param.args[1]) {
                                Rect rect = new Rect((Rect) value);
                                if (rect.width() > 0) {
                                    int center = rect.centerX();
                                    rect.left = center - half;
                                    rect.right = center + half;
                                }
                                corrected.add(rect);
                            }
                            param.args[1] = corrected;
                        }
                    });
        } catch (Throwable t) { log("fluid_cloud_lyric status occupancy hook failed: " + t); }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.systemui.media.seedling.livealert.MediaLiveAlertController",
                    lpparam.classLoader, "sendLiveAlertData", String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object task = XposedHelpers.getObjectField(param.thisObject, "statusBarTimeoutRunnable");
                            if (task == null) return;
                            sTimeoutTasks.add(task);
                            if (showingCloudLyrics()) {
                                Object handler = XposedHelpers.getObjectField(param.thisObject, "myHandler");
                                XposedHelpers.callMethod(handler, "removeCallbacks", task);
                            }
                            if (sTimeoutClasses.add(task.getClass())) {
                                XposedHelpers.findAndHookMethod(task.getClass(), "run", new XC_MethodHook() {
                                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                                        if (showingCloudLyrics() && sTimeoutTasks.contains(call.thisObject)) {
                                            call.setResult(null);
                                        }
                                    }
                                });
                            }
                        }
                    });
        } catch (Throwable t) { log("fluid_cloud_lyric timeout hook failed: " + t); }
    }

    static void updateStatusIconOccupancy(int halfWidth) {
        if (sOccupancyController == null || sReportedHalfWidth == halfWidth) return;
        sReportedHalfWidth = halfWidth;
        XposedHelpers.callMethod(sOccupancyController, "notifyRect", sOccupancyLocation);
    }

    private static void hookPluginTimeout(ClassLoader loader) {
        if (sPluginLoaders.contains(loader)) return;
        // q.y registers an a5.f(this,1) hideService callback through j6.w.g(key,...).
        try {
            XposedHelpers.findAndHookMethod("j6.w", loader, "g", String.class, long.class,
                    int.class, kotlin.jvm.functions.Function1.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (showingCloudLyrics() && isMusicCapsuleKey((String) param.args[0])) {
                                param.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("fluid_cloud_lyric timer registration hook failed: " + t);
        }
        XposedHelpers.findAndHookMethod("a5.f", loader, "invoke", String.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (showingCloudLyrics() && isMusicCapsuleKey((String) param.args[0])) param.setResult(null);
            }
        });
        sPluginLoaders.add(loader);
    }
    public static boolean enabled() {
        return StatusBarLyricHooks.lyricMode() == STATUSBAR_LYRIC_MODE_LIVE_ALERTS;
    }
    public static boolean isHosting(View lyricView) {
        if (lyricView == null || sText != lyricView || sText.getParent() != sSection
                || sCapsule == null || !sCapsule.isShown()) return false;
        try {
            Object item = XposedHelpers.callMethod(sCapsule, "getViewItem");
            return item != null && isMusicCapsuleKey((String) XposedHelpers.getObjectField(item, "a"));
        } catch (Throwable ignored) { return false; }
    }

    public static boolean show(View root, FrameLayout lyricView,
            boolean panelExpanded) {
        if (!enabled() || root == null || lyricView == null) {
            clear();
            return false;
        }
        try {
            if (panelExpanded) {
                if (sText == lyricView && lyricView.getParent() == sSection && sCapsule != null) {
                    FluidCloudLyricGeometry.setPanelExpanded(true);
                    return true;
                }
                clear();
                return false;
            }
            FluidCloudLyricGeometry.setPanelExpanded(false);

            View capsule = isHosting(lyricView) && belongsTo(root, sCapsule)
                    ? sCapsule : findMusicCapsule(root);
            if (capsule == null) {
                clear();
                return false;
            }
            Object item = XposedHelpers.callMethod(capsule, "getViewItem");
            View content = (View) XposedHelpers.getObjectField(item, "b");
            boolean rebind = capsule != sCapsule || content != sContentView || sText != lyricView
                    || sText.getParent() != sSection;
            if (rebind) {
                boolean retain = sText == lyricView && sOriginalHost != null;
                if (!retain) clear();
                View section = findByName(content, "left_section");
                View space = findByName(content, "space");
                View right = findByName(content, "right_section");
                if (!(section instanceof FrameLayout) || space == null || right == null) return false;
                if (!(section.getParent() instanceof View)) return false;
                View page = (View) section.getParent();
                if (space.getParent() != page || right.getParent() != page) return false;
                sCapsule = capsule;
                sContentView = content;
                hookPluginTimeout(capsule.getClass().getClassLoader());
                sSection = (ViewGroup) section;
                if (!retain) {
                    if (!(lyricView.getParent() instanceof ViewGroup)) { clear(); return false; }
                    sOriginalHost = (ViewGroup) lyricView.getParent();
                    sOriginalIndex = sOriginalHost.indexOfChild(lyricView);
                    sOriginalParams = lyricView.getLayoutParams();
                    sOriginalTranslationY = lyricView.getTranslationY();
                    sOriginalAlpha = lyricView.getAlpha();
                }
                sText = lyricView;
                if (lyricView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) lyricView.getParent()).removeView(lyricView);
                }
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0,
                        FrameLayout.LayoutParams.MATCH_PARENT);
                lp.gravity = Gravity.LEFT | Gravity.CENTER_VERTICAL;
                lyricView.setTranslationY(0f);
                lyricView.setAlpha(0f);
                sSection.addView(lyricView, lp);
                FluidCloudLyricGeometry.bind(root, capsule, page, (ViewGroup) section, space, right,
                        sText, sOriginalAlpha);
            }
            if (!FluidCloudLyricGeometry.refresh()) { clear(); return false; }
            if (rebind && sText == lyricView && sText.getParent() == sSection) {
                StatusBarLyricHooks.centerCloudLyric(lyricView);
            }
            return sText.getParent() == sSection && sCapsule.isShown();
        } catch (Throwable t) {
            clear();
            if (!sApplyFailureLogged) {
                sApplyFailureLogged = true;
                log("fluid_cloud_lyric apply failed: " + t);
            }
            return false;
        }
    }

    private static View findMusicCapsule(View view) {
        if (!view.isShown()) return null;
        if (CAPSULE_VIEW.equals(view.getClass().getName())) {
            if (!(view.getParent() instanceof View)
                    || XposedHelpers.callMethod(view.getParent(), "g") != view) return null;
            Object item = XposedHelpers.callMethod(view, "getViewItem");
            if (item == null) return null;
            String key = (String) XposedHelpers.getObjectField(item, "a");
            if (isMusicCapsuleKey(key)) return view;
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findMusicCapsule(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean belongsTo(View root, View child) {
        while (child != root) {
            if (!(child.getParent() instanceof View)) return false;
            child = (View) child.getParent();
        }
        return true;
    }

    static boolean isMusicCapsuleKey(String key) {
        if (key == null) return false;
        if (key.contains("_sid=268452006_rk=")
                || key.contains("_sid=default_com.android.systemui_268452006_rk=")) return true;

        String[] parts = key.split("\\|", -1);
        return parts.length >= 5 && "com.android.systemui".equals(parts[1])
                && "268452006".equals(parts[2]);
    }

    private static View findByName(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", "com.oplus.systemui.plugins");
        return id == 0 ? null : root.findViewById(id);
    }

    public static void clear() {
        FluidCloudLyricGeometry.clear();
        if (sText != null && sText.getParent() instanceof ViewGroup) {
            ((ViewGroup) sText.getParent()).removeView(sText);
        }
        if (sText != null && sOriginalHost != null && sOriginalParams != null) {
            sText.setTranslationY(sOriginalTranslationY);
            sText.setAlpha(sOriginalAlpha);
            sOriginalHost.addView(sText, Math.min(sOriginalIndex, sOriginalHost.getChildCount()),
                    sOriginalParams);
        }
        sText = null;
        sOriginalHost = null;
        sOriginalParams = null;
        sCapsule = null;
        sContentView = null;
        sSection = null;
    }
}
