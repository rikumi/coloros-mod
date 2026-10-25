package com.rikumi.colorosmod.hooks;

import android.graphics.Typeface;
import android.widget.TextView;
import com.rikumi.colorosmod.MonospacePolicy;
import com.rikumi.colorosmod.XposedInit;
import com.rikumi.colorosmod.xposed.*;

public final class MonospaceHooks {
    private static boolean installed;
    public static void hook(ClassLoader loader) {
        if (installed) return;
        Class<?> utils = XposedHelpers.findClassIfExists("com.oplus.util.OplusFontUtils", loader);
        if (utils == null) return;
        try {
            XposedBridge.hookAllMethods(utils, "flipTypeface", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled() || p.args.length == 0 || p.args[0] == null) return;
                    Typeface font = (Typeface) XposedHelpers.callMethod(p.args[0], "getTypeface");
                    if (MonospacePolicy.isMonospace(font)) p.setResult(font);
                }
            });
            XposedBridge.hookAllMethods(utils, "replaceFakeBoldToColorMedium", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!enabled() || p.args[0] == null || p.args[1] == null) return;
                    Typeface font = (Typeface) XposedHelpers.callMethod(p.args[1], "getTypeface");
                    if (!MonospacePolicy.isMonospace(font)) return;
                    ((TextView) p.args[0]).setTypeface(font, (Integer) p.args[2]);
                    p.setResult(null);
                }
            });
            installed = true;
            // These small wrappers are potential inlining callers of the static replacement methods.
            for (String name : new String[]{"android.graphics.PaintExtImpl", "com.oplus.font.OplusFontManager"}) {
                Class<?> caller = XposedHelpers.findClassIfExists(name, loader);
                if (caller != null) for (java.lang.reflect.Method method : caller.getDeclaredMethods()) {
                    if (method.getName().equals("flipTypeface") || method.getName().equals("replaceTypeface")
                            || method.getName().equals("replaceFakeBoldToColorMedium")) {
                        try { SystemFontHooks.deoptimize(method); }
                        catch (Throwable error) { XposedInit.log("font caller deoptimization failed: " + error); }
                    }
                }
            }
            XposedInit.log("HOOK OK OplusFontUtils font replacement (custom monospace protection)");
        } catch (Throwable t) { XposedInit.log("HOOK FAIL custom monospace protection: " + t); }
    }
    private static boolean enabled() { return SystemFontHooks.customMonospaceEnabled(); }
    private MonospaceHooks() {}
}
