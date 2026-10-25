package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

public final class AppStartConfirmHooks {
    private static final String ACTION_CONFIRM = "oplus.app.action.CHECK_ALLOW_START_ACTIVITY";
    private static final String VERSION = "activity_start_confirm_version";

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplusos.securitypermission.permission.ui.AppStartConfirmDialogActivity",
                    lp.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            Intent original = activity.getIntent();
                            Intent restored = dialogIntent(original,
                                    readBool(KEY_APP_START_CONFIRM_ALWAYS_ALLOW_ENABLED, false));
                            if (restored != original) activity.setIntent(restored);
                        }
                    });
            log("HOOK OK AppStartConfirmDialogActivity#onCreate (restore always allow)");
        } catch (Throwable t) {
            log("HOOK FAIL AppStartConfirmDialogActivity#onCreate: " + t);
        }
    }

    static Intent dialogIntent(Intent original, boolean enabled) {
        if (!enabled || original == null || !ACTION_CONFIRM.equals(original.getAction())
                || original.getIntExtra(VERSION, 0) != 2) return original;
        int type = original.getIntExtra("start_type", -1);

        if (type != 0 && type != 5 && type != 6) return original;

        return new Intent(original).putExtra(VERSION, 0);
    }

    private AppStartConfirmHooks() {}
}
