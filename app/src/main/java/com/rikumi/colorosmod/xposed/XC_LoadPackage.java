package com.rikumi.colorosmod.xposed;

public final class XC_LoadPackage {

    private XC_LoadPackage() {
    }

    public static final class LoadPackageParam {

        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public android.content.pm.ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
