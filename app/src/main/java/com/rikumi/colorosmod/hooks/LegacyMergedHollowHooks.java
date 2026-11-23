package com.rikumi.colorosmod.hooks;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextSwitcher;
import android.widget.TextView;
import com.rikumi.colorosmod.xposed.*;
import java.util.ArrayList;
import java.util.WeakHashMap;
import static com.rikumi.colorosmod.XposedInit.*;

/** ColorOS 15 has view-bound background engines and direct ImageView tinting. */
final class LegacyMergedHollowHooks {
    private static final ThreadLocal<View> backgroundOwner = new ThreadLocal<>();
    private static final WeakHashMap<View, Integer> nativeTints = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> applied = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> tiles = new WeakHashMap<>();
    private static boolean active = true, settingTint;
    private static Class<?> highlight, normal;

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            highlight = XposedHelpers.findClass("com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView", lp.classLoader);
            normal = XposedHelpers.findClass("com.oplus.systemui.qs.base.tile.OplusQSTileBaseView", lp.classLoader);
            Class<?> state = XposedHelpers.findClass("com.android.systemui.plugins.qs.QSTile$State", lp.classLoader);
            Class<?> background = XposedHelpers.findClass("com.oplus.systemui.qs.base.widget.QsViewBackgroundProxy", lp.classLoader);
            XC_MethodHook scope = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Object owner = XposedHelpers.getObjectField(p.thisObject, "qsView");
                    if (!(owner instanceof View tile) || !active || !MergedRadiantHooks.circleEnabled(tile)) return;
                    p.setObjectExtra("legacyBackgroundOwner", backgroundOwner.get());
                    p.setObjectExtra("legacyBackgroundScoped", true);
                    backgroundOwner.set(tile);
                    if (p.args.length == 1 && state.isInstance(p.args[0])) {
                        int value = XposedHelpers.getIntField(p.args[0], "state");
                        if (value == 2 || value == 101) {
                            Object copy = XposedHelpers.callMethod(p.args[0], "copy");
                            XposedHelpers.setIntField(copy, "state", 1);
                            p.args[0] = copy;
                        }
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!Boolean.TRUE.equals(p.getObjectExtra("legacyBackgroundScoped"))) return;
                    View previous = (View) p.getObjectExtra("legacyBackgroundOwner");
                    if (previous == null) backgroundOwner.remove(); else backgroundOwner.set(previous);
                }
            };
            XposedHelpers.findAndHookMethod(background, "handleStateChanged", state, scope);
            XposedHelpers.findAndHookMethod(background, "refreshViewBackground", scope);
            XposedHelpers.findAndHookMethod(background, "onBackgroundAttach", scope);
            XposedHelpers.findAndHookMethod(normal, "getTileIconState", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (active && backgroundOwner.get() == p.thisObject && p.getResult() instanceof Integer value
                            && (value == 2 || value == 101)) p.setResult(1);
                }
            });
            XC_MethodHook changed = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View tile = (View) p.thisObject;
                    if (!active || !MergedRadiantHooks.merged(tile)) return;
                    track(tile);
                    applied.remove(tile);
                    if (highlight.isInstance(tile)) MergedRadiantHooks.updateLegacyCard(tile);
                    else refreshTile(tile);
                }
            };
            XposedHelpers.findAndHookDeclaredMethod(highlight, "handleStateChanged", state, changed);
            XposedHelpers.findAndHookDeclaredMethod(normal, "handleStateChanged", state, changed);
            XposedHelpers.findAndHookMethod(highlight, "init", View.OnClickListener.class,
                    View.OnClickListener.class, View.OnLongClickListener.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            View tile = (View) p.thisObject;
                            if (active && MergedRadiantHooks.merged(tile)) {
                                track(tile);
                                MergedRadiantHooks.updateLegacyCard(tile);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(highlight, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (active && highlight.isInstance(p.thisObject))
                        MergedRadiantHooks.onLegacyTouch((View) p.thisObject, (MotionEvent) p.args[0]);
                }
            });
            Class<?> icon = XposedHelpers.findClass("com.android.systemui.qs.tileimpl.QSIconViewImpl", lp.classLoader);
            XposedHelpers.findAndHookMethod(icon, "setTint", ImageView.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!active || settingTint) return;
                    View host = (View) p.thisObject;
                    if (!MergedRadiantHooks.merged(host)) return;
                    nativeTints.put(host, (Integer) p.args[1]);
                    p.args[1] = iconTint(host, (Integer) p.args[1]);
                }
            });
            // The native mix-color and solid engines both obtain their active mask
            // from these view methods; preserve their animation and blur ownership.
            for (Class<?> type : new Class<?>[]{highlight, XposedHelpers.findClass(
                    "com.oplus.systemui.qs.tileimpl.OplusQSTileBaseViewImpl", lp.classLoader)}) {
                XposedHelpers.findAndHookMethod(type, "getActiveColorWithDarkMode", Context.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (active && MergedRadiantHooks.enabled((View) p.thisObject)) p.setResult(activeWhite((View) p.thisObject));
                    }
                });
            }
        } catch (Throwable error) { log("legacy merged hollow hooks unavailable: " + error); }
    }

    static void track(View tile) {
        if (!active || !MergedRadiantHooks.merged(tile)) return;
        tiles.put(tile, true);
        View icon = (View) XposedHelpers.callMethod(tile, "getIcon");
        if (icon != null && !nativeTints.containsKey(icon))
            nativeTints.put(icon, XposedHelpers.getIntField(icon, "mTint"));
    }

    static int activeWhite(View tile) {
        // Use the native active-view white, preserving its translucent alpha.
        int id = tile.getResources().getIdentifier(
                "qs_sep_view_active_color", "color", "com.android.systemui");
        return id == 0 ? 0xe6ffffff : tile.getContext().getColor(id);
    }

    private static int iconTint(View icon, int nativeColor) {
        View tile = MergedRadiantHooks.tileOwner(icon);
        if (tile == null) return nativeColor;
        int state = XposedHelpers.getIntField(tile, "mTileIconState");
        if (state != 2) return nativeColor;
        if (MergedRadiantHooks.enabled(tile)) {
            // Radiant icon colors follow the system palette, as on newer ROMs.
            // The custom whole-tile active color is not an icon-palette fallback.
            int id = tile.getResources().getIdentifier("status_bar_qs_tile_bg_color_active", "color", "com.android.systemui");
            int nativeActiveColor = id == 0 ? nativeColor : tile.getContext().getColor(id);
            Object current = XposedHelpers.getObjectField(tile, "mTempState");
            try {
                int color = current == null ? 0 : XposedHelpers.getIntField(current, "colorfulConfig");
                return Color.alpha(color) == 0 ? nativeActiveColor : color;
            }
            catch (NoSuchFieldError missingLegacyPalette) { return nativeActiveColor; }
        }
        return MergedRadiantHooks.circleEnabled(tile) ? Color.BLACK : nativeColor;
    }

    static void refreshTile(View tile) {
        if (!active) return;
        Object state = XposedHelpers.getObjectField(tile, "mTempState");
        if (state == null) return;
        boolean hollow = MergedRadiantHooks.circleEnabled(tile), radiant = MergedRadiantHooks.enabled(tile);
        int signature = 31 * XposedHelpers.getIntField(state, "state") + (hollow ? 1 : 0) + (radiant ? 2 : 0);
        signature = 31 * signature + tile.getResources().getConfiguration().hashCode();
        if (!Integer.valueOf(signature).equals(applied.get(tile))) {
            applied.put(tile, signature);
            XposedHelpers.callMethod(XposedHelpers.getObjectField(tile, "mBackgroundProxy"), "handleStateChanged", state);
        }
        View icon = (View) XposedHelpers.callMethod(tile, "getIcon");
        Integer nativeTint = nativeTints.get(icon);
        if (nativeTint != null) {
            int tint = iconTint(icon, nativeTint);
            if (XposedHelpers.getIntField(icon, "mTint") != tint) {
                settingTint = true;
                try { XposedHelpers.callMethod(icon, "setTint", XposedHelpers.getObjectField(icon, "mIcon"), tint); }
                finally { settingTint = false; }
            }
        }
        updateLabels(tile, hollow);
    }

    private static void updateLabels(View tile, boolean hollow) {
        Object state = XposedHelpers.getObjectField(tile, "mTempState");
        if (state != null && highlight.isInstance(tile)) {
            int value = XposedHelpers.getIntField(state, "state");
            String field = value == 2 && !hollow ? "mColorLabelActive"
                    : value == 0 || value == 101 ? "mColorLabelUnavailable" : "mColorLabelInActive";
            ColorStateList color = (ColorStateList) XposedHelpers.getObjectField(tile, field);
            TextSwitcher label = (TextSwitcher) XposedHelpers.getObjectField(tile, "mLabel");
            if (color != null && label != null) for (int i = 0; i < label.getChildCount(); i++)
                if (label.getChildAt(i) instanceof TextView text && !color.equals(text.getTextColors())) text.setTextColor(color);
        }
    }

    static void refresh() {
        applied.clear();
        for (View tile : new ArrayList<>(tiles.keySet())) refreshTile(tile);
    }

    static void cleanup() {
        active = false;
        for (View tile : new ArrayList<>(tiles.keySet())) {
            Object state = XposedHelpers.getObjectField(tile, "mTempState");
            if (state != null) XposedHelpers.callMethod(XposedHelpers.getObjectField(tile, "mBackgroundProxy"), "handleStateChanged", state);
            updateLabels(tile, false);
        }
        settingTint = true;
        try {
            for (var entry : nativeTints.entrySet()) {
                View icon = entry.getKey();
                XposedHelpers.callMethod(icon, "setTint", XposedHelpers.getObjectField(icon, "mIcon"), entry.getValue());
            }
        } finally { settingTint = false; nativeTints.clear(); applied.clear(); tiles.clear(); }
    }
    private LegacyMergedHollowHooks() {}
}
