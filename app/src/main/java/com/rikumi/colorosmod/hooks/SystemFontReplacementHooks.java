package com.rikumi.colorosmod.hooks;

import android.graphics.Typeface;
import android.widget.TextView;
import com.rikumi.colorosmod.XposedInit;
import com.rikumi.colorosmod.xposed.*;
import java.lang.reflect.Executable;
import java.util.Set;

/** Blocks ColorOS substitutions while retaining Android's requested family, style and fallback. */
public final class SystemFontReplacementHooks {
    private static boolean installed;

    public static void hook(ClassLoader loader) {
        if (installed) return;
        Class<?> utils = XposedHelpers.findClassIfExists("com.oplus.util.OplusFontUtils", loader);
        if (utils == null) return;
        try {
            Class<?> manager = XposedHelpers.findClassIfExists("com.oplus.font.OplusFontManager", loader);
            Class<?> paint = XposedHelpers.findClassIfExists("android.graphics.PaintExtImpl", loader);
            Class<?> text = XposedHelpers.findClassIfExists("android.widget.TextViewExtImpl", loader);
            Class<?> typeface = XposedHelpers.findClassIfExists("android.graphics.TypefaceExtImpl", loader);

            unchangedFont(utils, true, "flipTypeface");
            unchangedFont(manager, true, "flipTypeface");
            unchangedFont(paint, false, "flipTypeface");
            unchangedFont(text, false, "flipTypeface");
            unchangedFont(utils, false, "replaceTypefaceWithVariation", "createTypefaceWithVariation");
            fakeBold(utils, true, "replaceFakeBoldToColorMedium");
            fakeBold(manager, true, "replaceFakeBoldToMedium");
            fakeBold(text, false, "replaceFakeBoldToMedium");
            hook(utils, "shouldReplaceToOSans", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (disabled()) p.setResult(false);
                }
            });

            XC_MethodHook originalIndex = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (disabled() && p.args.length > 0) p.setResult(p.args[0]);
                }
            };
            hook(manager, "getTypefaceIndex", originalIndex);
            hook(text, "getTypefaceIndex", originalIndex);
            XC_MethodHook falseWhenDisabled = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (disabled()) p.setResult(false);
                }
            };
            for (Class<?> owner : new Class<?>[]{utils, manager}) {
                hook(owner, "isOplusSansEnabled", falseWhenDisabled);
                hook(owner, "isFlipFontUsed", falseWhenDisabled);
            }
            for (Class<?> owner : new Class<?>[]{utils, typeface}) {
                hook(owner, "createTypefaceForCustom", falseWhenDisabled);
                hook(owner, "replaceSysSans", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (disabled()) p.setResult(p.args[0]);
                    }
                });
            }
            // 主题注入、配置更新和光学字号修改均属于 OEM 字体替换；关闭时不执行。
            block(utils, "setFlipFont", "setFlipFontWhenUserChange", "updateTypefaceInCurrProcess",
                    "doUpdateTypeface", "createIndividualTypefae",
                    "apendIndividualFontFamily", "initVariationFontVariable", "initFontUtil",
                    "updateConfigurationInUIMode", "updateFontVariationRes", "checkAndCorrectFlipFontLink");
            hook(utils, "updateThemeStoreFontConfig", falseWhenDisabled);
            hook(utils, "updateOSansConfig", falseWhenDisabled);
            hook(utils, "getNeedReplaceAllTypefaceApp", falseWhenDisabled);
            block(manager, "setFlipFont", "setFlipFontWhenUserChange", "updateTypefaceInCurrProcess",
                    "initVariationFontVariable", "initFontsForserializeFontMap", "updateConfigurationInUIMode",
                    "createFontLink", "deleteFontLink");
            block(typeface, "createIndividualTypefae", "initFontsForserializeFontMap");
            block(paint, "replaceTypeface", "hookOnSetTextSize");
            hook(paint, "hookOnFontVariationSettingsChanged", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (disabled()) p.setResult(p.args[0]);
                }
            });

            // OEM wrappers may already be inlined into framework callers loaded before this module.
            Set<String> callers = Set.of("flipTypeface", "replaceTypeface", "replaceFakeBoldToColorMedium",
                    "replaceFakeBoldToMedium", "injectedByOemOS", "setTypeface", "setTypefaceWithoutWarning",
                    "getTypefaceIndex", "setTypefaceFromAttrs", "resolveStyleAndSetTypeface", "setInputType",
                    "setSystemFontMap", "getSystemDefaultTypeface", "initSystemDefaultTypefaces",
                    "hookOnSetTextSize", "hookOnFontVariationSettingsChanged", "createFromTypefaceWithVariation");
            for (String name : new String[]{"android.graphics.PaintExtImpl", "com.oplus.font.OplusFontManager",
                    "android.graphics.Paint", "android.widget.TextViewExtImpl", "android.widget.TextView",
                    "android.graphics.TypefaceExtImpl", "android.graphics.Typeface"}) {
                Class<?> owner = XposedHelpers.findClassIfExists(name, loader);
                if (owner == null) continue;
                for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
                    if (callers.contains(method.getName())) deoptimize(method);
                }
                if (name.equals("android.widget.TextView")) {
                    for (java.lang.reflect.Constructor<?> constructor : owner.getDeclaredConstructors()) deoptimize(constructor);
                }
            }
            installed = true;
            XposedInit.log("HOOK OK ColorOS font substitution controls");
        } catch (Throwable error) { XposedInit.log("HOOK FAIL ColorOS font substitution controls: " + error); }
    }

    private static boolean disabled() { return SystemFontHooks.fontReplacementDisabled(); }
    private static Typeface fromExtension(Object extension) {
        return extension == null ? null : (Typeface) XposedHelpers.callMethod(extension, "getTypeface");
    }
    private static void unchangedFont(Class<?> owner, boolean extension, String... names) {
        for (String name : names) hook(owner, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!disabled()) return;
                Typeface font = extension ? fromExtension(p.args[0]) : (Typeface) p.args[0];
                p.setResult(font);
            }
        });
    }
    private static void fakeBold(Class<?> owner, boolean extension, String name) {
        hook(owner, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!disabled()) return;
                if (p.args[0] == null) { p.setResult(null); return; }
                Typeface font = extension ? fromExtension(p.args[1]) : (Typeface) p.args[1];
                ((TextView) p.args[0]).setTypeface(font, (Integer) p.args[2]);
                p.setResult(null);
            }
        });
    }
    private static void block(Class<?> owner, String... names) {
        for (String name : names) hook(owner, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (disabled()) p.setResult(null);
            }
        });
    }
    private static void hook(Class<?> owner, String name, XC_MethodHook callback) {
        if (owner != null) XposedBridge.hookAllMethods(owner, name, callback);
    }
    private static void deoptimize(Executable method) {
        try { SystemFontHooks.deoptimize(method); }
        catch (Throwable error) { XposedInit.log("font caller deoptimization failed: " + error); }
    }
    private SystemFontReplacementHooks() {}
}
