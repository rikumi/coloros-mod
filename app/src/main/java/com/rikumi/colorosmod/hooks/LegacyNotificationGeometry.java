package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import java.util.WeakHashMap;
import org.xmlpull.v1.XmlPullParser;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Preserve the geometry supplied by the Android 15 templates across Android 17 binding. */
final class LegacyNotificationGeometry {
    private static final WeakHashMap<Object, Boolean> parsers = new WeakHashMap<>();
    private static final WeakHashMap<View, Geometry> originals = new WeakHashMap<>();
    private record Geometry(int width, int height, int start, int top, int end, int bottom,
            int paddingStart, int paddingTop, int paddingEnd, int paddingBottom) {
        Geometry(View v) {
            this(v.getLayoutParams().width, v.getLayoutParams().height,
                    v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams m ? m.getMarginStart() : 0,
                    v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams m ? m.topMargin : 0,
                    v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams m ? m.getMarginEnd() : 0,
                    v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams m ? m.bottomMargin : 0,
                    v.getPaddingStart(), v.getPaddingTop(), v.getPaddingEnd(), v.getPaddingBottom());
        }
        void restore(View v) {
            ViewGroup.LayoutParams p = v.getLayoutParams();
            p.width = width; p.height = height;
            if (p instanceof ViewGroup.MarginLayoutParams m) {
                m.setMarginStart(start); m.topMargin = top; m.setMarginEnd(end); m.bottomMargin = bottom;
            }
            v.setLayoutParams(p);
            v.setPaddingRelative(paddingStart, paddingTop, paddingEnd, paddingBottom);
        }
    }
    static void mark(Object parser) { synchronized (parsers) { parsers.put(parser, true); } }
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (android.os.Build.VERSION.SDK_INT < 37) return;
        try {
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate", XmlPullParser.class,
                    ViewGroup.class, boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            synchronized (parsers) { if (!parsers.containsKey(p.args[0])) return; }
                            if (p.getResult() instanceof View view) capture(view, false);
                        }
                    });
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper",
                    lp.classLoader, "onContentUpdated",
                    XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", lp.classLoader),
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) != 1) return;
                            restore((View) XposedHelpers.getObjectField(p.thisObject, "mView"));
                        }
                    });
        } catch (Throwable e) { log("legacy geometry hook failed: " + e); }
    }
    private static void capture(View v, boolean header) {
        String name = "";
        if (v.getId() != View.NO_ID) try { name = v.getResources().getResourceEntryName(v.getId()); } catch (Exception ignored) { }
        header |= name.equals("notification_header");
        if (v.getLayoutParams() != null && (header || name.equals("notification_main_column") || name.equals("text"))) {
            synchronized (originals) { originals.putIfAbsent(v, new Geometry(v)); }
        }
        if (v instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) capture(group.getChildAt(i), header);
    }
    private static void restore(View v) {
        synchronized (originals) {
            Geometry original = originals.get(v);
            if (original != null) original.restore(v);
        }
        if (v instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) restore(group.getChildAt(i));
    }
}
