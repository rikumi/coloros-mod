package com.rikumi.colorosmod;

import android.net.wifi.*;
import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Explicit foreground scan; do not present old cached results as a completed scan. */
public final class WifiBssidScanner {
    public static JSONArray scan(WifiManager wifi, String ssid) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        WifiManager.ScanResultsCallback callback = new WifiManager.ScanResultsCallback() {
            @Override public void onScanResultsAvailable() { finished.countDown(); }
        };
        // A standalone app_process has no running ActivityThread main loop.
        // Receive WiFi's Binder callback directly, bypassing ActivityManager's
        // registered-receiver dispatch and its application-thread scheduling.
        wifi.registerScanResultsCallback(Runnable::run, callback);
        try {
            if (!wifi.startScan()) throw new IOException("WIFI_BSSID_SCAN_REJECTED");
            if (!finished.await(20, TimeUnit.SECONDS)) throw new IOException("WIFI_BSSID_SCAN_TIMEOUT");
            return results(wifi, ssid);
        } finally {
            wifi.unregisterScanResultsCallback(callback);
        }
    }

    public static JSONArray results(WifiManager wifi, String ssid) throws Exception {
        Map<String, ScanResult> unique = new HashMap<>();
        List<ScanResult> scans = wifi.getScanResults();
        if (scans == null) throw new IOException("Cannot read WiFi scan results");
        for (ScanResult scan : scans) {
            String mac = WifiBssidPolicy.bssid(scan.BSSID);
            if (mac == null || !ssid.equals(scan.SSID)) continue;
            ScanResult previous = unique.get(mac);
            if (previous == null || scan.level > previous.level) unique.put(mac, scan);
        }
        List<Map.Entry<String, ScanResult>> sorted = new ArrayList<>(unique.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, ScanResult>>comparingInt(e -> e.getValue().level)
                .reversed().thenComparing(Map.Entry::getKey));
        JSONArray result = new JSONArray();
        for (Map.Entry<String, ScanResult> entry : sorted) {
            int rssi = entry.getValue().level;
            int frequency = entry.getValue().frequency;
            result.put(new JSONObject().put("bssid", entry.getKey()).put("rssi", rssi)
                    .put("level", Math.max(0, Math.min(4, wifi.calculateSignalLevel(rssi))))
                    .put("frequency", frequency).put("channel", ScanResult.convertFrequencyMhzToChannelIfSupported(frequency)));
        }
        return result;
    }
    private WifiBssidScanner() {}
}
