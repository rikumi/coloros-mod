package com.rikumi.colorosmod.hooks;

import java.lang.reflect.Method;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.log;

/** Translate a pixel radius to the launcher's G2 corner parameter, independently of density. */
final class LauncherFolderCornerUnits {
    private static Method smoothV2, instance, ratio;
    private static boolean reported;

    static void init(ClassLoader loader) {
        smoothV2 = instance = ratio = null;
        try {
            Class<?> smooth = XposedHelpers.findClass("com.android.launcher3.util.SmoothRoundedManager", loader);
            Class<?> config = XposedHelpers.findClass("com.android.launcher.theme.LauncherIconConfig", loader);
            Method version = smooth.getDeclaredMethod("useSmoothCornerV2");
            Method singleton = config.getDeclaredMethod("getInstance");
            Method multiplier = config.getDeclaredMethod("getG2SettingCornerRatio");
            version.setAccessible(true);
            singleton.setAccessible(true);
            multiplier.setAccessible(true);
            smoothV2 = version;
            instance = singleton;
            ratio = multiplier;
        } catch (NoSuchMethodException | XposedHelpers.ClassNotFoundError olderLauncher) {
            // G1 launchers accept the pixel radius directly.
        }
    }

    static float nativeRadius(float pixels) {
        return pixels * multiplier();
    }

    static float pixelRadius(float nativeRadius) {
        return nativeRadius / multiplier();
    }

    private static float multiplier() {
        if (smoothV2 == null) return 1f;
        try {
            if (!Boolean.TRUE.equals(smoothV2.invoke(null))) return 1f;
            float multiplier = ((Number) ratio.invoke(instance.invoke(null))).floatValue();
            if (Float.isFinite(multiplier) && multiplier > 0f) return multiplier;
        } catch (ReflectiveOperationException error) {
            if (!reported) {
                reported = true;
                log("launcher folder corner conversion failed: " + error);
            }
        }
        return 1f;
    }
}
