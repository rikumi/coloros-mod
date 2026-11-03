package com.rikumi.colorosmod.hooks;

import com.rikumi.colorosmod.WifiBssidPolicy;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Method;
import android.net.wifi.WifiInfo;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Dispatch a BSSID-specific connection without writing WifiConfiguration.BSSID. */
public final class WifiBssidConnection {
    private static final class Lease {
        final Object client;
        final String profile, bssid;
        final int networkId;
        final long expiresAt = SystemClock.elapsedRealtime() + 15000;
        volatile boolean connected;
        Lease(Object client, String profile, String bssid, int networkId) {
            this.client = client; this.profile = profile; this.bssid = bssid; this.networkId = networkId;
        }
    }
    private static final AtomicReference<Lease> bypass = new AtomicReference<>();

    public static void clearBypass() { bypass.set(null); }

    /** Only the explicitly selected profile/AP receives the one-time exception. */
    public static boolean isBypassed(String profile, String bssid) {
        Lease lease = currentLease();
        return lease != null && lease.profile.equals(profile) && lease.bssid.equals(WifiBssidPolicy.bssid(bssid));
    }
    public static boolean hasBypass(String profile) {
        Lease lease = currentLease();
        return lease != null && lease.profile.equals(profile);
    }
    public static void observeConnection(WifiInfo info) {
        Lease lease = bypass.get();
        if (lease != null) observe(lease, info);
    }
    private static void observe(Lease lease, WifiInfo info) {
        if (info != null && info.getNetworkId() == lease.networkId && lease.bssid.equals(WifiBssidPolicy.bssid(info.getBSSID())))
            lease.connected = true;
        else if (lease.connected) bypass.compareAndSet(lease, null);
    }
    private static Lease currentLease() {
        Lease lease = bypass.get();
        if (lease == null) return null;
        if (!lease.connected && SystemClock.elapsedRealtime() >= lease.expiresAt) {
            bypass.compareAndSet(lease, null); return null;
        }
        // Candidate evaluation runs on the WiFi owner thread. Inspect the actual
        // client so a disconnect removes the exception before automatic selection.
        try { observe(lease, (WifiInfo) XposedHelpers.callMethod(lease.client, "getConnectionInfo")); }
        catch (RuntimeException ignored) { }
        return bypass.get() == lease ? lease : null;
    }

    public static void connectOnce(Object service, String profile, String address) throws Exception {
        String bssid = WifiBssidPolicy.bssid(address);
        if (profile == null || profile.isEmpty() || bssid == null) throw new IllegalArgumentException("Invalid AP");
        Object runner = XposedHelpers.getObjectField(service, "mWifiThreadRunner");
        FutureTask<Void> task = new FutureTask<>(() -> {
            Object configManager = XposedHelpers.getObjectField(service, "mWifiConfigManager");
            Object configurations = XposedHelpers.callMethod(configManager, "getConfiguredNetworks");
            Object selected = null;
            if (configurations instanceof Iterable<?>) for (Object config : (Iterable<?>) configurations)
                if (profile.equals(WifiBssidPolicy.profile(config))) { selected = config; break; }
            if (selected == null) throw new IllegalStateException("Saved WiFi no longer exists");
            int networkId = ((Number) XposedHelpers.getObjectField(selected, "networkId")).intValue();
            Object warden = XposedHelpers.getObjectField(service, "mActiveModeWarden");
            if (((Number) XposedHelpers.callMethod(warden, "getWifiState")).intValue() != 3)
                throw new IllegalStateException("WiFi is disabled");
            Object client = XposedHelpers.callMethod(warden, "getPrimaryClientModeManager");
            Object connectivity = XposedHelpers.getObjectField(service, "mWifiConnectivityManager");
            Lease lease = new Lease(client, profile, bssid, networkId);
            bypass.set(lease);
            try {
                XposedHelpers.callMethod(connectivity, "prepareForForcedConnection", networkId);
                XposedHelpers.callMethod(client, "startConnectToNetwork", networkId, 1000, bssid);
            } catch (Throwable error) {
                bypass.compareAndSet(lease, null);
                throw error;
            }
            return null;
        });
        try {
            Method post = null;
            for (Class<?> owner = runner.getClass(); owner != null && post == null; owner = owner.getSuperclass())
                for (Method method : owner.getDeclaredMethods()) {
                    Class<?>[] types = method.getParameterTypes();
                    if (method.getName().equals("post") && types.length >= 1 && types[0] == Runnable.class
                            && (types.length == 1 || (types.length == 2 && types[1] == String.class))) { post = method; break; }
                }
            if (post == null) throw new NoSuchMethodException("WiFi thread dispatcher");
            post.setAccessible(true);
            Object accepted = post.getParameterCount() == 1 ? post.invoke(runner, task)
                    : post.invoke(runner, task, "ColorOSMod#connectOnce");
            if (Boolean.FALSE.equals(accepted)) throw new IllegalStateException("WiFi thread unavailable");
            task.get(4, TimeUnit.SECONDS);
        } finally { task.cancel(false); }
    }
    private WifiBssidConnection() {}
}
