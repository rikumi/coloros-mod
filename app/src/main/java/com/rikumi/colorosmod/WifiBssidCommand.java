package com.rikumi.colorosmod;

import android.content.Context;
import android.net.wifi.*;
import android.os.Looper;
import android.system.Os;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Privileged operations are explicit user actions; policy hooks only read the data file. */
public final class WifiBssidCommand {
    private static WifiManager wifi;
    private static boolean autojoinValue(WifiConfiguration config) throws Exception {
        return WifiConfiguration.class.getField("allowAutojoin").getBoolean(config);
    }
    private static List<WifiConfiguration> configurations() throws Exception {
        List<WifiConfiguration> list = wifi.getConfiguredNetworks();
        if (list == null) throw new IOException("Cannot read saved WiFi networks");
        return list;
    }
    private static WifiConfiguration config(String id) throws Exception {
        for (WifiConfiguration c : configurations()) if (id.equals(WifiBssidPolicy.profile(c))) return c;
        throw new IOException("Saved WiFi no longer exists");
    }
    private static JSONObject current() throws Exception {
        WifiInfo info = wifi.getConnectionInfo();
        String bssid = info == null ? null : WifiBssidPolicy.bssid(info.getBSSID());
        if (info == null || info.getNetworkId() < 0 || bssid == null) return null;
        for (WifiConfiguration c : configurations()) if (c.networkId == info.getNetworkId()) {
            String ssid = c.SSID;
            if (ssid != null && ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\""))
                ssid = ssid.substring(1, ssid.length() - 1);
            return new JSONObject().put("id", WifiBssidPolicy.profile(c)).put("ssid", ssid)
                    .put("bssid", bssid).put("networkId", c.networkId)
                    .put("frequency", info.getFrequency())
                    .put("channel", ScanResult.convertFrequencyMhzToChannelIfSupported(info.getFrequency()));
        }
        return null;
    }
    private static void rememberAp(JSONObject rule, JSONObject ap) throws JSONException {
        JSONObject details = rule.optJSONObject("apDetails");
        if (details == null) { details = new JSONObject(); rule.put("apDetails", details); }
        details.put(ap.getString("bssid"), new JSONObject().put("frequency", ap.optInt("frequency"))
                .put("channel", ap.optInt("channel", -1)));
    }
    private static void autojoin(WifiConfiguration c, boolean enabled) throws Exception {
        WifiManager.class.getMethod("allowAutojoin", int.class, boolean.class).invoke(wifi, c.networkId, enabled);
        String key = WifiBssidPolicy.profile(c);
        for (int i = 0; i < 40; i++) {
            if (autojoinValue(config(key)) == enabled) return;
            Thread.sleep(50);
        }
        throw new IOException("System auto-connect setting was not applied");
    }
    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 0 || args.length != 2) throw new SecurityException("Root is required");
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            // Match the android package's UID for WifiService/AppOps attribution.
            Os.setgid(1000); Os.setuid(1000);
            Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
            Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
            wifi = context.getSystemService(WifiManager.class);
            if (wifi == null) throw new IOException("WiFi service unavailable");
            JSONObject request = new JSONObject(new String(android.util.Base64.decode(args[1],
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP), StandardCharsets.UTF_8));
            File dir = new File(WifiBssidPolicy.DIRECTORY);
            if (!dir.isDirectory() && !dir.mkdir()) throw new IOException("Cannot create WiFi policy directory");
            Os.chmod(dir.getPath(), 0700);
            if (args[0].equals("scan") || args[0].equals("connect-once")) {
                // Do not hold the policy writer lock while waiting for a radio scan.
                String id = request.getString("id");
                JSONObject rule;
                try (RandomAccessFile guard = new RandomAccessFile(new File(dir, "lock"), "rw");
                     FileLock lock = guard.getChannel().lock()) {
                    AtomicFile file = new AtomicFile(new File(WifiBssidPolicy.FILE));
                    if (file.getBaseFile().length() > 1024 * 1024) throw new IOException("WiFi policy too large");
                    JSONArray snapshot = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
                    rule = WifiBssidPolicy.find(snapshot, id);
                    if (rule == null) throw new IOException("WiFi policy no longer exists");
                }
                JSONObject response = new JSONObject();
                if (args[0].equals("scan")) {
                    JSONArray scans = WifiBssidScanner.scan(wifi, rule.getString("ssid"));
                    response.put("scanId", id).put("scans", scans);
                } else {
                    String mac = WifiBssidPolicy.bssid(request.getString("bssid"));
                    JSONArray scans = WifiBssidScanner.results(wifi, rule.getString("ssid"));
                    boolean found = false;
                    for (int i = 0; i < scans.length(); i++) found |= scans.getJSONObject(i).getString("bssid").equals(mac);
                    if (!found) throw new IOException("WIFI_BSSID_AP_UNAVAILABLE");
                    WifiBssidReadiness.connectOnce(id, mac);
                    boolean connected = false;
                    for (int i = 0; i < 75; i++) {
                        JSONObject now = current();
                        if (now != null && id.equals(now.optString("id")) && mac.equals(now.optString("bssid"))) {
                            connected = true; break;
                        }
                        Thread.sleep(200);
                    }
                    if (!connected) throw new IOException("WIFI_BSSID_CONNECT_TIMEOUT");
                }
                JSONObject now = current();
                System.out.println(response.put("current", now == null ? JSONObject.NULL : now));
                System.exit(0);
                return;
            }
            try (RandomAccessFile guard = new RandomAccessFile(new File(dir, "lock"), "rw");
                 FileLock lock = guard.getChannel().lock()) {
                AtomicFile file = new AtomicFile(new File(WifiBssidPolicy.FILE));
                if (file.getBaseFile().length() > 1024 * 1024) throw new IOException("WiFi policy too large");
                JSONArray rules = file.getBaseFile().exists()
                        ? new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8)) : new JSONArray();
                JSONObject now = current();
                String operation = args[0], id = request.optString("id");
                WifiConfiguration changed = null;
                boolean previous = false;
                try {
                    if (!operation.equals("list")) {
                        if (!operation.equals("remove") && !operation.equals("delete-ap")) {
                            try { WifiBssidReadiness.requireReady(); }
                            catch (Exception inactive) {
                                throw new IOException("WIFI_BSSID_HOOK_INACTIVE: " + inactive, inactive);
                            }
                        }
                        if (operation.equals("add-wifi")) {
                            if (now == null) throw new IOException("Connect to a saved WiFi first");
                            id = now.getString("id");
                            changed = config(id); previous = autojoinValue(changed);
                            if (WifiBssidPolicy.find(rules, id) == null) {
                                rules.put(new JSONObject().put("id", id).put("ssid", now.getString("ssid"))
                                        .put("mode", 0).put("bssids", new JSONArray()).put("originalAutojoin", previous));
                            }
                            autojoin(changed, WifiBssidPolicy.find(rules, id).optInt("mode", 0) == 0);
                        } else {
                            JSONObject rule = WifiBssidPolicy.find(rules, id);
                            if (rule == null) throw new IOException("WiFi policy no longer exists");
                            if (operation.equals("mode")) {
                                int mode = request.getInt("mode");
                                if (mode != 0 && mode != 1) throw new IllegalArgumentException("Invalid filter mode");
                                changed = config(id); previous = autojoinValue(changed);
                                autojoin(changed, mode == 0); rule.put("mode", mode);
                            } else if (operation.equals("add-ap")) {
                                if (now == null || !id.equals(now.getString("id")))
                                    throw new IOException("Current AP belongs to a different WiFi");
                                JSONArray entries = rule.getJSONArray("bssids");
                                String mac = now.getString("bssid");
                                boolean exists = false;
                                for (int i = 0; i < entries.length(); i++) exists |= mac.equals(entries.getString(i));
                                if (!exists) entries.put(mac);
                                rememberAp(rule, now);
                            } else if (operation.equals("add-scanned-ap")) {
                                String mac = WifiBssidPolicy.bssid(request.getString("bssid"));
                                JSONArray scans = WifiBssidScanner.results(wifi, rule.getString("ssid"));
                                JSONObject selected = null;
                                for (int i = 0; i < scans.length(); i++)
                                    if (scans.getJSONObject(i).getString("bssid").equals(mac)) selected = scans.getJSONObject(i);
                                if (selected == null) throw new IOException("WIFI_BSSID_AP_UNAVAILABLE");
                                JSONArray entries = rule.getJSONArray("bssids");
                                boolean exists = false;
                                for (int i = 0; i < entries.length(); i++) exists |= mac.equals(entries.getString(i));
                                if (!exists) entries.put(mac);
                                rememberAp(rule, selected);
                            } else if (operation.equals("delete-ap")) {
                                String mac = WifiBssidPolicy.bssid(request.getString("bssid"));
                                if (mac == null) throw new IllegalArgumentException("Invalid BSSID");
                                JSONArray entries = rule.getJSONArray("bssids");
                                for (int i = entries.length() - 1; i >= 0; i--) if (mac.equals(entries.getString(i))) entries.remove(i);
                                JSONObject details = rule.optJSONObject("apDetails");
                                if (details != null) details.remove(mac);
                            } else if (operation.equals("remove")) {
                                // Forgetting a policy does not forget the saved WiFi or its credentials.
                                try { changed = config(id); } catch (IOException missing) { changed = null; }
                                if (changed != null) { previous = autojoinValue(changed); autojoin(changed, rule.optBoolean("originalAutojoin", true)); }
                                for (int i = rules.length() - 1; i >= 0; i--)
                                    if (id.equals(rules.getJSONObject(i).getString("id"))) rules.remove(i);
                            } else throw new IllegalArgumentException("Unknown WiFi operation");
                        }
                        byte[] bytes = rules.toString().getBytes(StandardCharsets.UTF_8);
                        if (bytes.length > 1024 * 1024) throw new IOException("WiFi policy too large");
                        FileOutputStream output = file.startWrite();
                        try {
                            Os.fchmod(output.getFD(), 0600);
                            output.write(bytes);
                            file.finishWrite(output);
                        } catch (Throwable error) { file.failWrite(output); throw error; }
                    }
                } catch (Throwable error) {
                    if (changed != null) try { autojoin(changed, previous); } catch (Throwable rollback) { error.addSuppressed(rollback); }
                    throw error;
                }
                System.out.println(new JSONObject().put("rules", rules).put("current", now == null ? JSONObject.NULL : now));
            }
            System.exit(0);
        } catch (Throwable error) {
            while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) error = error.getCause();
            System.err.println("WIFI_BSSID_ERROR: " + error); System.exit(1);
        }
    }
}
