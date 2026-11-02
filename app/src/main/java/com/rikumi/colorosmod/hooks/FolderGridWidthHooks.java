package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import static com.rikumi.colorosmod.FolderGridSettings.*;

import android.content.res.Resources;
import com.rikumi.colorosmod.FolderGridSettings;
import com.rikumi.colorosmod.xposed.*;
import java.lang.reflect.Method;

/** Remove page-width caps only when the column count exceeds its master default. */
final class FolderGridWidthHooks {
    static final String NATIVE_COLUMNS = "colorosmodFolderNativeColumns";

    static void hook(Class<?> deviceProfile, Class<?> idpClass) throws NoSuchMethodException {
        Class<?> folderParam = deviceProfile.getDeclaredMethod("folder").getReturnType();
        // ColorOS 17's FolderParam.i(float, Resources, OplusInvariantDeviceProfile)
        // recalculates cells after construction too. Resolve the inspected signature
        // through DeviceProfile.folder() so its obfuscated class name is not fixed.
        for (Method method : folderParam.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 3 || types[0] != float.class || types[1] != Resources.class
                    || !idpClass.isAssignableFrom(types[2])) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Object nativeValue = XposedHelpers.getAdditionalInstanceField(p.args[2], NATIVE_COLUMNS);
                    if (nativeValue instanceof Integer columns)
                        p.setObjectExtra("folderWideMargins", removeMargins(p.args[2], columns));
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    restoreMargins(p.getObjectExtra("folderWideMargins"));
                }
            });
        }
    }

    static Object removeMargins(Object idp, int nativeColumns) {
        if (!readBoolCached(COLUMNS_ENABLED, false)) return null;
        int columns = XposedHelpers.getIntField(idp, "numFolderColumns");
        if (!FolderGridSettings.beyondDefaultColumns(columns, nativeColumns)) return null;
        Object option = XposedHelpers.getObjectField(idp, "folderDisplayOption");
        if (option == null) return null;
        float portrait = XposedHelpers.getFloatField(option, "folderPageMarginLRDp");
        float landscape = XposedHelpers.getFloatField(option, "landscapeFolderPageMarginLRDp");
        XposedHelpers.setFloatField(option, "folderPageMarginLRDp", 0f);
        XposedHelpers.setFloatField(option, "landscapeFolderPageMarginLRDp", 0f);
        return new Object[]{option, portrait, landscape};
    }

    static void restoreMargins(Object saved) {
        if (!(saved instanceof Object[] values)) return;
        XposedHelpers.setFloatField(values[0], "folderPageMarginLRDp", (Float) values[1]);
        XposedHelpers.setFloatField(values[0], "landscapeFolderPageMarginLRDp", (Float) values[2]);
    }

    private FolderGridWidthHooks() {}
}
