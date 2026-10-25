package com.rikumi.colorosmod.hooks;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Xml;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

final class ColorOsNavigationConfig {
    private boolean loaded;
    private long version = -1;
    private ColorOsNavigationPolicy defaults = ColorOsNavigationPolicy.DEFAULT;
    private Map<String, JSONObject> packages = new HashMap<>();

    ColorOsNavigationPolicy resolve(Context context, String pkg, String activity) {
        if (!loaded) {
            loaded = true;
            readFile("/system_ext/oplus/sys_navbar_color_white_list.xml");
            readFile("/data/oplus/os/sys_navbar_color_white_list.xml");
            try (Cursor cursor = context.getContentResolver().query(
                    Uri.parse("content://com.oplus.romupdate.provider.db/update_list"),
                    new String[]{"version", "xml"}, "filtername=\"sys_navbar_color_white_list\"", null, null)) {
                if (cursor != null) while (cursor.moveToNext()) parse(cursor.getString(1));
            } catch (Exception ignored) {
                // The system/data XML remains authoritative if the provider denies this caller.
            }
        }
        JSONObject policy = packages.get(pkg);
        if (policy == null) return defaults;
        ColorOsNavigationPolicy base = apply(policy, defaults);
        JSONArray entries = policy.optJSONArray("policy");
        if (entries == null && policy.has("policy")) {
            try { entries = new JSONArray(policy.optString("policy")); }
            catch (Exception ignored) {}
        }
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null && activity.equals(entry.optString("activity_name"))) return apply(entry, base);
        }
        return base;
    }

    private void readFile(String path) {
        try {
            if (Files.isReadable(Paths.get(path))) parse(new String(Files.readAllBytes(Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private void parse(String xml) throws Exception {
        if (xml == null || xml.length() > 1024 * 1024) return;
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        long nextVersion = -1;
        ColorOsNavigationPolicy nextDefaults = ColorOsNavigationPolicy.DEFAULT;
        Map<String, JSONObject> nextPackages = new HashMap<>();
        for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event != XmlPullParser.START_TAG) continue;
            if ("version".equals(parser.getName())) nextVersion = Long.parseLong(parser.nextText().trim());
            else if ("config".equals(parser.getName())) {
                nextDefaults = new ColorOsNavigationPolicy(integer(parser, "fullscreen_variance", 100),
                        integer(parser, "variance", 25), integer(parser, "min_frame_rate", 10),
                        integer(parser, "start_reduce_time", 3000), integer(parser, "input_reduce_time", 1500),
                        integer(parser, "move_reduce_time", 100));
            } else if ("r".equals(parser.getName()) && "1".equals(parser.getAttributeValue(null, "status"))) {
                String pkg = parser.getAttributeValue(null, "package_name"), body = parser.getAttributeValue(null, "body");
                if (pkg == null || body == null) continue;
                JSONObject config = new JSONObject(body.replace('^', '"'));
                JSONObject policy = config.optJSONObject("activity_color_policy");
                if (policy == null && config.has("activity_color_policy")) {
                    try { policy = new JSONObject(config.optString("activity_color_policy")); }
                    catch (Exception ignored) {}
                }
                if (policy != null) nextPackages.put(pkg, policy);
            }
        }
        if (nextVersion > version) { version = nextVersion; defaults = nextDefaults; packages = nextPackages; }
    }

    private static int integer(XmlPullParser xml, String key, int fallback) {
        String value = xml.getAttributeValue(null, key);
        return value == null || value.isEmpty() ? fallback : Integer.parseInt(value);
    }

    private static ColorOsNavigationPolicy apply(JSONObject json, ColorOsNavigationPolicy base) {
        return new ColorOsNavigationPolicy(json.optInt("fullscreen_variance", base.fullscreenVariance),
                json.optInt("variance", base.variance), base.minFrameRate,
                json.optLong("start_reduce_time", base.startReduceTime),
                json.optLong("input_reduce_time", base.inputReduceTime),
                json.optLong("move_reduce_time", base.moveReduceTime));
    }
}
