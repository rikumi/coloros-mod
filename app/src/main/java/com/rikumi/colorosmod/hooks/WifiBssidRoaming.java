package com.rikumi.colorosmod.hooks;

import android.net.wifi.WifiInfo;
import android.os.Handler;
import com.rikumi.colorosmod.WifiBssidPolicy;
import com.rikumi.colorosmod.xposed.*;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;
import org.json.JSONObject;
import static com.rikumi.colorosmod.XposedInit.log;

/** Keep restricted networks on framework-selected BSSIDs, including firmware roaming. */
final class WifiBssidRoaming {
    private static final Map<Object, State> clients = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Integer> framework = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Boolean> writing = ThreadLocal.withInitial(() -> false);

    private static final class State {
        final WeakReference<Object> client;
        final Object nativeWifi;
        final String iface;
        final boolean firmwareSupported;
        boolean restricted;
        int requestedRoaming = 1;
        String pinned;
        Object config;
        State(Object client) {
            this.client = new WeakReference<>(client);
            nativeWifi = XposedHelpers.getObjectField(client, "mWifiNative");
            iface = (String) XposedHelpers.getObjectField(client, "mInterfaceName");
            int previous = framework.get();
            framework.remove();
            try {
                firmwareSupported = Boolean.TRUE.equals(XposedHelpers.callMethod(
                        XposedHelpers.getObjectField(client, "mWifiConnectivityHelper"), "isFirmwareRoamingSupported"));
            } finally { if (previous != 0) framework.set(previous); }
        }
    }

    static void install(ClassLoader loader, List<XC_MethodHook.Unhook> hooks,
            List<XposedInterface.HookHandle> chains) throws Exception {
        Class<?> client = XposedHelpers.findClass("com.android.server.wifi.ClientModeImpl", loader);
        Class<?> connectivity = XposedHelpers.findClass("com.android.server.wifi.WifiConnectivityManager", loader);
        Class<?> helper = XposedHelpers.findClass("com.android.server.wifi.WifiConnectivityHelper", loader);
        Class<?> nativeWifi = XposedHelpers.findClass("com.android.server.wifi.WifiNative", loader);
        // Scope the firmware-capability override to a specific protected configuration.
        // This also makes the framework compare BSSIDs rather than only network IDs.
        int scoped = 0;
        for (Method method : connectivity.getDeclaredMethods()) {
            if (!Set.of("connectToNetworkUsingCmm", "isClientModeManagerConnectedOrConnectingToCandidate",
                    "shouldUseBssidAny", "triggerConnectToNetworkUsingMbbIfAvailable").contains(method.getName())) continue;
            int configIndex = -1;
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++)
                if (types[i].getName().equals("android.net.wifi.WifiConfiguration")) configIndex = i;
            if (configIndex < 0) continue;
            int index = configIndex;
            chains.add(XposedHelpers.hookChainMethod(method, chain -> {
                if (WifiBssidHooks.ruleFor(chain.getArg(index)) == null) return chain.proceed();
                int previous = framework.get();
                framework.set(previous + 1);
                try { return chain.proceed(); }
                finally { if (previous == 0) framework.remove(); else framework.set(previous); }
            }));
            scoped++;
        }
        if (scoped == 0) throw new NoSuchMethodException("WiFi framework roaming selection");
        require(hooks, helper, "isFirmwareRoamingSupported", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (framework.get() > 0) p.setResult(false);
            }
        });
        require(hooks, client, "startConnectToNetwork", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Object manager = XposedHelpers.getObjectField(p.thisObject, "mWifiConfigManager");
                    Object config = XposedHelpers.callMethod(manager, "getConfiguredNetwork", p.args[0]);
                    restrict(state(p.thisObject), WifiBssidHooks.ruleFor(config) != null, config);
                } catch (Throwable error) { log("WiFi roaming connection policy failed: " + error); }
            }
        });
        XC_MethodHook update = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable()) enforce(p.thisObject);
            }
        };
        require(hooks, client, "updateCurrentConnectionInfo", update);
        require(hooks, client, "clearTargetBssid", update);
        require(hooks, client, "updateLinkLayerStatsRssiSpeedFrequencyCapabilities", update);
        require(hooks, client, "startRoamToNetwork", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Object config = XposedHelpers.callMethod(XposedHelpers.getObjectField(p.thisObject,
                            "mWifiConfigManager"), "getConfiguredNetwork", p.args[0]);
                    JSONObject rule = WifiBssidHooks.ruleFor(config);
                    if (rule == null) return;
                    boolean weak = weakSignal(p.thisObject);
                    if (weak || !allowed(config, rule, (String) p.args[1])) {
                        if (weak) disconnect(p.thisObject);
                        p.setResult(null);
                    }
                } catch (Throwable error) { log("WiFi roaming target policy failed: " + error); p.setResult(null); }
            }
        });
        // Re-check at dispatch: queued roam messages may outlive a rule change.
        require(hooks, nativeWifi, "roamToNetwork", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    Object config = p.args[1];
                    JSONObject rule = WifiBssidHooks.ruleFor(config);
                    if (rule == null) return;
                    Object status = XposedHelpers.callMethod(config, "getNetworkSelectionStatus");
                    String bssid = (String) XposedHelpers.callMethod(status, "getNetworkSelectionBSSID");
                    State state = forInterface(p.thisObject, (String) p.args[0]);
                    Object owner = state == null ? null : state.client.get();
                    boolean weak = owner != null && weakSignal(owner);
                    if (!allowed(config, rule, bssid) || weak) {
                        if (weak) disconnect(owner);
                        p.setResult(false);
                    }
                } catch (Throwable error) { log("WiFi native roaming policy failed: " + error); p.setResult(false); }
            }
        });
        require(hooks, nativeWifi, "enableFirmwareRoaming", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (writing.get()) return;
                State state = forInterface(p.thisObject, (String) p.args[0]);
                if (state != null) {
                    state.requestedRoaming = (Integer) p.args[1];
                    if (state.restricted) p.args[1] = 0;
                }
            }
        });
        // Native clears the target after association; retain the current AP until
        // the framework explicitly dispatches an allowed roam instead of using "any".
        require(hooks, nativeWifi, "setNetworkBSSID", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (writing.get()) return;
                State state = forInterface(p.thisObject, (String) p.args[0]);
                if (state != null && state.restricted && state.pinned != null
                        && (p.args[1] == null || "any".equals(p.args[1]))) p.args[1] = state.pinned;
            }
        });
    }

    private static void require(List<XC_MethodHook.Unhook> hooks, Class<?> owner, String name, XC_MethodHook hook)
            throws NoSuchMethodException {
        Set<XC_MethodHook.Unhook> added = XposedBridge.hookAllMethods(owner, name, hook);
        hooks.addAll(added);
        if (added.isEmpty()) throw new NoSuchMethodException(owner.getName() + '#' + name);
    }

    private static State state(Object client) {
        synchronized (clients) { return clients.computeIfAbsent(client, State::new); }
    }

    private static State forInterface(Object nativeWifi, String iface) {
        synchronized (clients) {
            for (State state : clients.values())
                if (state.nativeWifi == nativeWifi && Objects.equals(state.iface, iface)) return state;
        }
        return null;
    }

    private static boolean allowed(Object config, JSONObject rule, String bssid) throws ReflectiveOperationException {
        return WifiBssidPolicy.allows(rule, bssid)
                || WifiBssidConnection.isBypassed(WifiBssidPolicy.profile(config), bssid);
    }

    private static boolean weakSignal(Object client) {
        WifiInfo info = (WifiInfo) XposedHelpers.getObjectField(client, "mWifiInfo");
        int rssi = info.getRssi(), frequency = info.getFrequency();
        if (rssi <= -127 || frequency <= 0) return false;
        Object params = XposedHelpers.getObjectField(client, "mScoringParams");
        int threshold = (Integer) XposedHelpers.callMethod(params, "getExitRssi", frequency);
        return rssi < threshold;
    }

    private static void disconnect(Object client) {
        // Stop the link immediately on the WiFi thread before an already queued roam.
        XposedHelpers.callMethod(XposedHelpers.getObjectField(client, "mWifiNative"), "disconnect",
                XposedHelpers.getObjectField(client, "mInterfaceName"));
        XposedHelpers.callMethod(client, "disconnect");
    }

    private static void restrict(State state, boolean restricted, Object config) {
        if (config != null) state.config = config;
        if (state.restricted == restricted) return;
        writing.set(true);
        try {
            Object result = state.firmwareSupported ? XposedHelpers.callMethod(state.nativeWifi,
                    "enableFirmwareRoaming", state.iface, restricted ? 0 : state.requestedRoaming) : 0;
            // A failed HAL command must not silently leave autonomous roaming enabled.
            if (restricted && !Integer.valueOf(0).equals(result)) {
                Object owner = state.client.get();
                if (owner != null) disconnect(owner);
                return;
            }
            state.restricted = restricted;
            if (!restricted) {
                String original = config == null ? null : (String) XposedHelpers.getObjectField(config, "BSSID");
                XposedHelpers.callMethod(state.nativeWifi, "setNetworkBSSID", state.iface,
                        original == null ? "any" : original);
                state.pinned = null;
            }
        } finally { writing.remove(); }
    }

    private static void enforce(Object client) {
        try {
            State state = state(client);
            Object config = XposedHelpers.callMethod(client, "getConnectedWifiConfiguration");
            // During an in-flight connect/roam keep the guard installed until the
            // next network is known; a transient null is not a policy removal.
            if (config == null) {
                if (state.config != null && WifiBssidHooks.ruleFor(state.config) == null)
                    restrict(state, false, state.config);
                return;
            }
            JSONObject rule = WifiBssidHooks.ruleFor(config);
            restrict(state, rule != null, config);
            if (rule == null) return;
            WifiInfo info = (WifiInfo) XposedHelpers.getObjectField(client, "mWifiInfo");
            String bssid = WifiBssidPolicy.bssid(info.getBSSID());
            if (weakSignal(client) || (bssid != null && !allowed(config, rule, bssid))) {
                disconnect(client); return;
            }
            if (bssid != null) {
                state.pinned = bssid;
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(state.nativeWifi, "setNetworkBSSID", state.iface, bssid)))
                    disconnect(client);
            }
        } catch (Throwable error) { log("WiFi roaming enforcement failed: " + error); }
    }

    static void cleanup() {
        synchronized (clients) {
            for (State state : clients.values()) {
                Object owner = state.client.get();
                if (owner == null) continue;
                Handler handler = (Handler) XposedHelpers.callMethod(owner, "getHandler");
                handler.post(() -> {
                    try { restrict(state, false, XposedHelpers.callMethod(owner, "getConnectedWifiConfiguration")); }
                    catch (Throwable error) { log("WiFi roaming restoration failed: " + error); }
                });
            }
            clients.clear();
        }
    }

    static void refresh() {
        synchronized (clients) {
            for (State state : clients.values()) {
                Object owner = state.client.get();
                if (owner != null) ((Handler) XposedHelpers.callMethod(owner, "getHandler")).post(() -> enforce(owner));
            }
        }
    }
    private WifiBssidRoaming() { }
}
