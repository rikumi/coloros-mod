package com.rikumi.colorosmod;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

public final class HomeControlsTileSettings {
    public static final String KEY = "module_home_controls_tile_enabled";
    public static final String SERVICE = "com.rikumi.colorosmod.HomeControlsTileService";

    public static boolean enabled(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(KEY, false);
    }

    public static void sync(Context context) {
        PackageManager pm = context.getPackageManager();
        ComponentName service = new ComponentName(context.getPackageName(), SERVICE);
        boolean enabled = enabled(context);
        int current = pm.getComponentEnabledSetting(service);
        if (current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && !enabled) return;
        int desired = enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (current != desired) pm.setComponentEnabledSetting(service, desired, PackageManager.DONT_KILL_APP);
    }
}
