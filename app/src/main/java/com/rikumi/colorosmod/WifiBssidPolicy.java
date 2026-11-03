package com.rikumi.colorosmod;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Per saved-network profile, never a device-wide BSSID whitelist. */
public final class WifiBssidPolicy {
    public static final String DIRECTORY = "/data/system/colorosmod-wifi";
    public static final String FILE = DIRECTORY + "/bssid-rules.json";
    public static String bssid(String value) {
        if (value == null) return null;
        String mac = value.toLowerCase(Locale.ROOT);
        if (!mac.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}") || mac.equals("02:00:00:00:00:00")
                || mac.equals("00:00:00:00:00:00") || (Integer.parseInt(mac.substring(0, 2), 16) & 1) != 0) return null;
        return mac;
    }
    public static JSONObject find(JSONArray rules, String id) {
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule != null && id.equals(rule.optString("id"))) return rule;
        }
        return null;
    }
    public static boolean allows(JSONObject rule, String address) {
        String mac = bssid(address);
        if (mac == null) return false;
        JSONArray entries = rule.optJSONArray("bssids");
        boolean found = false;
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            if (mac.equals(bssid(entries.optString(i)))) { found = true; break; }
        }
        return rule.optInt("mode", 0) == 0 ? !found : found;
    }
    public static String profile(Object config) throws ReflectiveOperationException {
        for (String name : new String[]{"getProfileKey", "getKey", "configKey"}) {
            try { return (String) config.getClass().getMethod(name).invoke(config); }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException("WiFi configuration profile key");
    }
    private WifiBssidPolicy() {}
}
