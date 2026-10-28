package com.rikumi.colorosmod.hooks;

import android.graphics.Typeface;
import android.os.Parcel;
import com.rikumi.colorosmod.XposedInit;
import com.rikumi.colorosmod.xposed.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Suppresses replacement instructions in system_server, including clients outside module scope. */
public final class SystemFontDistributionHooks {
    private static boolean installed;
    public static void hook(ClassLoader loader) {
        if (installed) return;
        try {
            Class<?> extra = XposedHelpers.findClass("oplus.content.res.OplusExtraConfiguration", loader);
            java.lang.reflect.Constructor<?> constructor = extra.getDeclaredConstructor();
            constructor.setAccessible(true);
            XposedHelpers.hookChainMethod(extra.getDeclaredMethod("writeToParcel", Parcel.class, int.class), chain -> {
                if (!SystemFontHooks.fontReplacementDisabled()) return chain.proceed();
                // 仅修改发送副本，不临时改写系统持有的配置，避免跨线程读取被污染。
                Object copy = constructor.newInstance();
                XposedHelpers.callMethod(copy, "setTo", chain.getThisObject());
                int variation = XposedHelpers.getIntField(copy, "mFontVariationSettings");
                XposedHelpers.setIntField(copy, "mFontVariationSettings", disabledVariation(variation));
                XposedHelpers.setIntField(copy, "mFlipFont", 0);
                XposedHelpers.setIntField(copy, "mFontUserId", -1);
                return chain.proceedWith(copy);
            });
            XposedBridge.hookAllMethods(Typeface.class, "serializeFontMap", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!SystemFontHooks.fontReplacementDisabled() || !(p.args[0] instanceof Map<?, ?> original)) return;
                    Map<Object, Object> copy = new LinkedHashMap<>(original);
                    copy.remove("individual-font");
                    p.args[0] = copy;
                }
            });
            installed = true;
            XposedInit.log("HOOK OK system font configuration distribution");
        } catch (Throwable error) { XposedInit.log("HOOK FAIL system font configuration distribution: " + error); }
    }
    static int disabledVariation(int original) {
        // [12,16) 是 ColorOS 字体替换模式；低位字重和高位图标配置保持原值。
        return original & ~0xf000;
    }
    private SystemFontDistributionHooks() {}
}
