package com.rikumi.colorosmod.hooks;

import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.*;

/** Reapply cached panel colours when settings arrive after SystemUI creates its scrim. */
final class QsBackgroundSettingsHooks {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<Object, Settings> controllers = Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean active = true;
    private record Settings(boolean enabled, int brightness) { }

    private static Settings settings() {
        return new Settings(active && readBoolCached(KEY_QS_SCRIM_TRANSLUCENT_ENABLED, false),
                readIntCached(KEY_QS_SCRIM_BRIGHTNESS, QS_SCRIM_BRIGHTNESS_DEFAULT));
    }

    static boolean active() { return active; }

    static void track(Object controller) { controllers.put(controller, settings()); }

    static void refresh() {
        main.post(() -> {
            if (!active) return;
            Settings current = settings();
            for (Object controller : hosts()) {
                if (current.equals(controllers.get(controller))) continue;
                try {
                    XposedHelpers.callMethod(controller, "updateBehindMixConfig");
                    controllers.put(controller, current);
                } catch (Throwable error) { log("qs background settings refresh failed: " + error); }
            }
        });
    }

    static ArrayList<Object> hosts() {
        synchronized (controllers) { return new ArrayList<>(controllers.keySet()); }
    }

    static void restore(Object saved) {
        if (saved instanceof Iterable<?> hosts) for (Object host : hosts) {
            controllers.put(host, null);
        }
        refresh();
    }

    static void cleanup() {
        active = false;
        main.removeCallbacksAndMessages(null);
        for (Object controller : hosts()) {
            try { XposedHelpers.callMethod(controller, "updateBehindMixConfig"); }
            catch (Throwable error) { log("qs background restore failed: " + error); }
        }
        controllers.clear();
    }
}
