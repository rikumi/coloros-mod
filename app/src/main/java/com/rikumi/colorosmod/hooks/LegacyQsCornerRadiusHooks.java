package com.rikumi.colorosmod.hooks;

import android.graphics.drawable.shapes.RoundRectShape;
import android.os.Build;
import android.view.View;
import com.rikumi.colorosmod.xposed.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import static com.rikumi.colorosmod.XposedInit.log;

/** ColorOS 15 uses view-bound outline providers rather than the newer tile resource flow. */
final class LegacyQsCornerRadiusHooks {
    private static final Map<Object, RadiusState> states = Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean active = true;

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT != 35) return;
        try {
            Class<?> provider = XposedHelpers.findClass("com.oplus.systemui.qs.base.widget."
                    + "QsViewOutlineProvider$QsViewRoundRectOutlineProvider", lp.classLoader);
            XC_MethodHook radius = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!active) return;
                    try { sync(p.thisObject); }
                    catch (Throwable error) { log("legacy tile radius update failed: " + error); }
                }
            };
            for (String method : new String[]{"getOutline", "getRoundParams", "refreshSmoothRound"})
                XposedBridge.hookAllMethods(provider, method, radius);
            XposedHelpers.findAndHookMethod("com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView",
                    lp.classLoader, "getShapeForHighlightTile", android.content.Context.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!active) return;
                            Float value = QsHooks.resolveQsCornerRadiusPx(p.thisObject);
                            if (value == null) return;
                            float[] corners = new float[8];
                            java.util.Arrays.fill(corners, value);
                            p.setResult(new RoundRectShape(corners, null, null));
                        }
                    });
        } catch (Throwable error) { log("legacy tile radius hooks unavailable: " + error); }
    }

    private static boolean isHighlightBackground(View view) {
        // The provider belongs to mBg, not the tile's icon frame or label container.
        for (View ancestor = view; ancestor != null;
                ancestor = ancestor.getParent() instanceof View parent ? parent : null) {
            for (Class<?> type = ancestor.getClass(); type != null; type = type.getSuperclass())
                if (type.getName().equals("com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView")) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void sync(Object provider) {
        View host = (View) XposedHelpers.callMethod(provider, "getHostView");
        if (!isHighlightBackground(host)) return;
        QsHooks.watchRadiusSize(host);
        AtomicReference<Object> reference = (AtomicReference<Object>) XposedHelpers.callMethod(provider, "getOutlineShapeReference");
        if (!(reference.get() instanceof Float current)) return;
        RadiusState state = states.get(provider);
        if (state == null) { state = new RadiusState(current); states.put(provider, state); }
        else if (!current.equals(state.applied)) state.nativeRadius = current;
        Float radius = QsHooks.resolveQsCornerRadiusPx(host);
        state.applied = radius == null ? state.nativeRadius : radius;
        reference.set(state.applied);
    }

    static void refresh() {
        if (!active) return;
        ArrayList<Object> providers;
        synchronized (states) { providers = new ArrayList<>(states.keySet()); }
        for (Object provider : providers) {
            try {
                sync(provider);
                XposedHelpers.callMethod(provider, "refreshSmoothRound");
                ((View) XposedHelpers.callMethod(provider, "getHostView")).invalidateOutline();
            } catch (Throwable error) { log("legacy tile radius refresh failed: " + error); }
        }
    }

    @SuppressWarnings("unchecked")
    static void cleanup() {
        active = false;
        synchronized (states) {
            for (Map.Entry<Object, RadiusState> entry : states.entrySet()) {
                try {
                    AtomicReference<Object> reference = (AtomicReference<Object>) XposedHelpers.callMethod(entry.getKey(), "getOutlineShapeReference");
                    if (entry.getValue().applied.equals(reference.get())) reference.set(entry.getValue().nativeRadius);
                    XposedHelpers.callMethod(entry.getKey(), "refreshSmoothRound");
                    ((View) XposedHelpers.callMethod(entry.getKey(), "getHostView")).invalidateOutline();
                } catch (Throwable error) { log("legacy tile radius restore failed: " + error); }
            }
            states.clear();
        }
    }

    private static final class RadiusState {
        Float nativeRadius, applied;
        RadiusState(Float radius) { nativeRadius = applied = radius; }
    }
    private LegacyQsCornerRadiusHooks() {}
}
