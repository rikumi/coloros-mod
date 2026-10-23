package com.rikumi.colorosmod;

import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class AppNavigationSettings {
    public static final String ENABLED = "app_immersive_navigation";
    public static final String PACKAGES = "app_immersive_navigation_packages";
    public static final String CONFIGURED = "app_immersive_navigation_packages_configured";
    public static final String PREFIX = "app_immersive_navigation_package:";

    public static Set<String> applications(SharedPreferences preferences) {
        return new HashSet<>(preferences.getStringSet(PACKAGES, Collections.singleton("com.tencent.mm")));
    }

    private AppNavigationSettings() {}
}
