package com.rikumi.colorosmod.hooks;

import com.rikumi.colorosmod.WifiBssidPolicy;
import com.rikumi.colorosmod.WifiBssidReadiness;
import android.os.Binder;
import android.os.Parcel;
import com.rikumi.colorosmod.xposed.*;
import org.json.*;
import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.util.*;
import static com.rikumi.colorosmod.XposedInit.*;

/** Filter auto-selection candidates; manual WiFi connection and stored credentials stay native. */
public final class WifiBssidHooks {
    private static final ThreadLocal<Integer> SELECTION = ThreadLocal.withInitial(() -> 0);
    private static final Set<ClassLoader> LOADERS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static volatile JSONArray cached = new JSONArray();
    private static long loadedAt;
    private static volatile boolean active;

    public static void cleanupForHotReload() {
        active = false;
        WifiBssidConnection.clearBypass();
    }

    private static final JSONArray EMPTY_RULES = new JSONArray();

    private static synchronized JSONArray rules() {
        if (!readBoolCached(KEY_WIFI_AP_FILTER_ENABLED, false)) return EMPTY_RULES;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - loadedAt < 250) return cached;
        loadedAt = now;
        File file = new File(WifiBssidPolicy.FILE);
        if (!file.exists()) cached = new JSONArray();
        else try {
            if (file.length() > 1024 * 1024) throw new IllegalStateException("WiFi policy too large");
            cached = new JSONArray(new String(Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception error) { log("WiFi BSSID policy read failed: " + error); }
        return cached;
    }

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        // WiFi APEX services may be wrapped/subclassed by the ROM. Discover the
        // defining loader rather than requiring exact AOSP service class names.
        try {
            Class<?> manager = XposedHelpers.findClass("com.android.server.SystemServiceManager", lp.classLoader);
            XC_MethodHook serviceStarted = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    discover(p.getResult());
                    if (p.args != null) for (Object arg : p.args) if (arg instanceof Class<?>) discoverClass((Class<?>) arg);
                }
            };
            XposedBridge.hookAllMethods(manager, "startServiceFromJar", serviceStarted);
            XposedBridge.hookAllMethods(manager, "startService", serviceStarted);
        } catch (Throwable error) { log("WiFi BSSID startup discovery failed: " + error); }
        // A hot reload happens after service startup. Use the local service list
        // as well as Binder, whose returned object can be a vendor wrapper/proxy.
        try {
            Class<?> manager = XposedHelpers.findClass("com.android.server.SystemServiceManager", lp.classLoader);
            Class<?> local = XposedHelpers.findClass("com.android.server.LocalServices", lp.classLoader);
            Object instance = XposedHelpers.callStaticMethod(local, "getService", manager);
            if (instance != null) {
                Object services = XposedHelpers.getObjectField(instance, "mServices");
                if (services instanceof Iterable<?>) for (Object service : (Iterable<?>) services) discover(service);
            }
        } catch (Throwable error) { log("WiFi BSSID local service discovery: " + error); }
        try {
            Object binder = XposedHelpers.callStaticMethod(Class.forName("android.os.ServiceManager"), "getService", "wifi");
            discover(binder);
        } catch (Throwable error) { log("WiFi BSSID Binder discovery failed: " + error); }
        discoverClassLoader(lp.classLoader);
    }

    private static void discover(Object service) {
        if (service != null) discoverClass(service.getClass());
    }
    private static void discoverClass(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            discoverClassLoader(current.getClassLoader());
    }
    private static void discoverClassLoader(ClassLoader loader) {
        if (loader == null) return;
        try {
            // Resolve only: avoid initializing WiFi internals during unrelated service startup.
            Class<?> manager = Class.forName("com.android.server.wifi.WifiConfigManager", false, loader);
            install(manager.getClassLoader());
        } catch (ClassNotFoundException ignored) { }
    }

    private static synchronized void install(ClassLoader loader) {
        if (!LOADERS.add(loader)) return;
        List<XC_MethodHook.Unhook> installed = new ArrayList<>();
        List<io.github.libxposed.api.XposedInterface.HookHandle> candidateHooks = new ArrayList<>();
        try {
            Class<?> manager = XposedHelpers.findClass("com.android.server.wifi.WifiConfigManager", loader);
            Class<?> candidates = XposedHelpers.findClass("com.android.server.wifi.WifiCandidates", loader);
            Class<?> selector = XposedHelpers.findClass("com.android.server.wifi.WifiNetworkSelector", loader);
            XC_MethodHook selection = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { SELECTION.set(SELECTION.get() + 1); }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    int depth = SELECTION.get() - 1;
                    if (depth <= 0) SELECTION.remove(); else SELECTION.set(depth);
                }
            };
            int selectionHooks = installed.size();
            for (String name : new String[]{"getCandidatesFromScan", "selectNetwork"})
                installed.addAll(XposedBridge.hookAllMethods(selector, name, selection));
            if (installed.size() == selectionHooks) throw new NoSuchMethodException("WiFi automatic network selection");
            Class<?> connectivity = XposedHelpers.findClass("com.android.server.wifi.WifiConnectivityManager", loader);
            for (String name : new String[]{"handleScanResults", "getAllScanOptimizationNetworks", "retrievePnoNetworkList",
                    "startConnectivityScan", "handleConnectionAttemptEnded"})
                installed.addAll(XposedBridge.hookAllMethods(connectivity, name, selection));
            XC_MethodHook externalCopies = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    if (SELECTION.get() <= 0 || p.getResult() == null || p.hasThrowable()) return;
                    Object result = p.getResult();
                    if (result instanceof List<?>) for (Object config : (List<?>) result) enableWhitelistCopy(config);
                    else enableWhitelistCopy(result);
                }
            };
            Set<XC_MethodHook.Unhook> copies = new HashSet<>();
            // These documented getters return external copies. Do not intercept
            // internal getters or the shared copy builder used by UI broadcasts.
            for (String name : new String[]{"getConfiguredNetwork", "getConfiguredNetworks", "getSavedNetworks",
                    "getSavedNetworkForScanDetailAndCache"})
                copies.addAll(XposedBridge.hookAllMethods(manager, name, externalCopies));
            if (copies.isEmpty()) throw new NoSuchMethodException("external WiFi configuration getters");
            installed.addAll(copies);
            int count = 0;
            for (Method method : candidates.getDeclaredMethods()) {
                if (!method.getName().equals("add") || method.getReturnType() != boolean.class) continue;
                int configIndex = -1;
                Class<?>[] types = method.getParameterTypes();
                for (int i = 0; i < types.length; i++) if (types[i].getName().equals("android.net.wifi.WifiConfiguration")) configIndex = i;
                if (configIndex < 0) continue;
                final int index = configIndex;
                candidateHooks.add(XposedHelpers.hookChainMethod(method, chain -> {
                    if (SELECTION.get() <= 0) return chain.proceed();
                    boolean blocked = false;
                    try {
                        Object config = chain.getArg(index);
                        JSONObject rule = config == null ? null : WifiBssidPolicy.find(rules(), WifiBssidPolicy.profile(config));
                        if (rule != null) {
                            Object first = chain.getArg(0);
                            String bssid;
                            if (first.getClass().getName().equals("com.android.server.wifi.ScanDetail")) {
                                Object scan = XposedHelpers.callMethod(first, "getScanResult");
                                bssid = (String) XposedHelpers.getObjectField(scan, "BSSID");
                            } else bssid = String.valueOf(XposedHelpers.getObjectField(first, "bssid"));
                            blocked = !WifiBssidPolicy.allows(rule, bssid)
                                    && !WifiBssidConnection.isBypassed(WifiBssidPolicy.profile(config), bssid);
                        }
                    } catch (ReflectiveOperationException | RuntimeException error) {
                        log("WiFi candidate policy failed: " + error);
                    }
                    return blocked ? false : chain.proceed();
                }));
                count++;
            }
            if (count == 0) throw new NoSuchMethodException("WiFi candidate insertion");
            File directory = new File(WifiBssidPolicy.DIRECTORY);
            if (!directory.isDirectory() && !directory.mkdir()) throw new java.io.IOException("WiFi policy directory unavailable");
            android.system.Os.chmod(directory.getPath(), 0700);
            Class<?> service = XposedHelpers.findClass("com.android.server.wifi.WifiServiceImpl", loader);
            installed.addAll(XposedBridge.hookAllMethods(service, "getConnectionInfo", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (Binder.getCallingUid() == 1000 && p.getResult() instanceof android.net.wifi.WifiInfo)
                        WifiBssidConnection.observeConnection((android.net.wifi.WifiInfo) p.getResult());
                }
            }));
            Method transact = null;
            for (Class<?> owner = service; owner != null && transact == null; owner = owner.getSuperclass()) {
                try { transact = owner.getDeclaredMethod("onTransact", int.class, Parcel.class, Parcel.class, int.class); }
                catch (NoSuchMethodException ignored) { }
            }
            if (transact == null) throw new NoSuchMethodException("WiFi Binder transaction handler");
            candidateHooks.add(XposedHelpers.hookChainMethod(transact, chain -> {
                int uid = Binder.getCallingUid();
                int code = (int) chain.getArg(0);
                if (!active || (code != WifiBssidReadiness.TRANSACTION && code != WifiBssidReadiness.CONNECT_ONCE)
                        || (uid != 1000 && uid != 0)) return chain.proceed();
                Parcel request = (Parcel) chain.getArg(1);
                Parcel response = (Parcel) chain.getArg(2);
                if (response == null) return chain.proceed();
                request.enforceInterface(WifiBssidReadiness.DESCRIPTOR);
                if (code == WifiBssidReadiness.CONNECT_ONCE) {
                    try { WifiBssidConnection.connectOnce(chain.getThisObject(), request.readString(), request.readString()); }
                    catch (Exception error) {
                        response.writeException(new IllegalStateException("Cannot connect to AP: " + error));
                        return true;
                    }
                }
                response.writeNoException();
                response.writeInt(WifiBssidReadiness.VERSION);
                return true;
            }));
            // Old releases used these files as liveness evidence. They are no longer read.
            new File(WifiBssidPolicy.DIRECTORY, "filter.pid").delete();
            new File(WifiBssidPolicy.DIRECTORY, "filter.pid.error").delete();
            active = true;
            log("WiFi BSSID automatic candidate filters installed");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : installed) hook.unhook();
            for (var hook : candidateHooks) hook.unhook();
            LOADERS.remove(loader);
            log("WiFi BSSID filters unavailable: " + error);

        }
    }

    private static void enableWhitelistCopy(Object config) throws ReflectiveOperationException {
        if (config == null) return;
        JSONObject rule = WifiBssidPolicy.find(rules(), WifiBssidPolicy.profile(config));
        if (rule != null && rule.optInt("mode", 0) == 1)
            config.getClass().getField("allowAutojoin").setBoolean(config,
                    (rule.optJSONArray("bssids") != null && rule.optJSONArray("bssids").length() > 0)
                            || WifiBssidConnection.hasBypass(WifiBssidPolicy.profile(config)));
    }
    private WifiBssidHooks() {}
}
