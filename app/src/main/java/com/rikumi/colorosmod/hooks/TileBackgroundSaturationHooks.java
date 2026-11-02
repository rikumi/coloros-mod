package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.graphics.RuntimeShader;
import com.rikumi.colorosmod.TileBackgroundEnhancement;
import com.rikumi.colorosmod.xposed.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Add a surface-specific color correction using the ROM's own shader-effect lifecycle. */
final class TileBackgroundSaturationHooks {
    enum Surface {
        TILE(TileBackgroundEnhancement.ENABLED, TileBackgroundEnhancement.VALUE),
        NOTIFICATION(TileBackgroundEnhancement.NOTIFICATION_ENABLED, TileBackgroundEnhancement.NOTIFICATION_VALUE);

        final String enabledKey, valueKey;
        Surface(String enabledKey, String valueKey) {
            this.enabledKey = enabledKey;
            this.valueKey = valueKey;
        }
    }

    private static Class<?> effectInterface;
    private static final WeakHashMap<Object, Boolean> shaders = new WeakHashMap<>();
    private static boolean failed;
    private static int lastDiagnostic = -1;

    static void hook(ClassLoader loader) {
        failed = false;
        lastDiagnostic = -1;
        try {
            effectInterface = XposedHelpers.findClass("com.oplus.posteffect.agsl.effects.IShaderEffect", loader);
            Class<?> shader = XposedHelpers.findClass("com.oplus.posteffect.agsl.DrawableShader", loader);
            XposedHelpers.findAndHookMethod(shader, "copyAllEffectsFrom", shader, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable() && effects(p.thisObject).containsKey(TileBackgroundEnhancement.EFFECT))
                        shaders.put(p.thisObject, true);
                }
            });
        } catch (Throwable error) {
            failed = true;
            log("background saturation effect unavailable: " + error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> effects(Object shader) {
        return (Map<String, Object>) XposedHelpers.getObjectField(shader, "effects");
    }

    static void apply(Object drawable, Surface surface) {
        if (failed || effectInterface == null) return;
        try {
            Object shader = XposedHelpers.callMethod(drawable, "getDrawableShader");
            boolean enabled = surface != null && readBoolCached(surface.enabledKey, false);
            int percent = enabled ? TileBackgroundEnhancement.clamp(
                    readIntCached(surface.valueKey, TileBackgroundEnhancement.DEFAULT)) : 100;
            update(shader, percent);
        } catch (Throwable error) {
            failed = true;
            cleanup();
            log("background saturation effect failed: " + error);
        }
    }

    private static void update(Object shader, int percent) {
        Map<String, Object> effects = effects(shader);
        Object effect = effects.get(TileBackgroundEnhancement.EFFECT);
        // 100% and switch-off use the unmodified native shader, including its cache.
        if (percent == 100) {
            if (effects.remove(TileBackgroundEnhancement.EFFECT) != null)
                XposedHelpers.callMethod(shader, "updateShaderBlendParams", true);
            shaders.remove(shader);
        } else {
            shaders.put(shader, true);
            if (effect == null || !TileBackgroundEnhancement.updateEffect(effect, percent)) {
                effect = TileBackgroundEnhancement.createEffect(effectInterface, percent);
                effects.put(TileBackgroundEnhancement.EFFECT, effect);
                XposedHelpers.callMethod(shader, "updateShaderBlendParams", true);
            }
            // Native rebuild catches compilation errors internally. Check the actual
            // resulting shader's uniform too, so a rejected effect cannot fail silently.
            RuntimeShader runtime = (RuntimeShader) XposedHelpers.callMethod(shader, "getShaderDirect");
            if (runtime == null) throw new IllegalStateException("Background RuntimeShader is unavailable");
            XposedHelpers.callMethod(effect, "pushUniforms", runtime);
            if (lastDiagnostic != percent) {
                lastDiagnostic = percent;
                log("background saturation shader applied: native enhancement=" + percent + "%");
            }
        }
    }

    static void cleanup() {
        for (Object shader : new ArrayList<>(shaders.keySet())) {
            try { update(shader, 100); }
            catch (Throwable error) { log("background saturation cleanup failed: " + error); }
        }
        shaders.clear();
    }

    private TileBackgroundSaturationHooks() {}
}
