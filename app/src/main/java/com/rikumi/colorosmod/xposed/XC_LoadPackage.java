package com.rikumi.colorosmod.xposed;

/**
 * 旧版 XC_LoadPackage 的等价结构: 新版 API 把包名/类加载器分散在
 * PackageLoadedParam、SystemServerStartingParam 等回调参数里, 这里统一收敛成
 * 一个 LoadPackageParam 传给 hooks, 业务代码不必感知两套参数。
 */
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
