package com.rikumi.colorosmod.hooks;

import android.os.ParcelFileDescriptor;
import com.rikumi.colorosmod.XposedInit;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class NativeFontHooks {
    private static boolean active;

    private static Class<?> libraryOwner;
    private static native boolean configure(String[] paths, int[] descriptors);
    public static boolean isActive() { return active; }

    public static synchronized Object captureHotReloadState() {
        return active ? libraryOwner : null;
    }

    public static synchronized void restoreHotReloadState(Object state) {
        if (state instanceof Class<?> owner && NativeFontHooks.class.getName().equals(owner.getName())) {
            libraryOwner = owner;
            active = true;
        }
    }

    public static synchronized void hook(XposedInit module) {
        if (active || android.os.Build.VERSION.SDK_INT < 29) return;
        List<ParcelFileDescriptor> descriptors = new ArrayList<>();
        try {
            String manifest = module.getRemotePreferences(SystemFontHooks.PREFS).getString("native_manifest", "");
            if (manifest.isEmpty()) return;
            List<String> paths = new ArrayList<>();
            List<String> names = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ParcelFileDescriptor.AutoCloseInputStream(module.openRemoteFile(manifest)), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] fields = line.split("\\t", -1);
                    if (fields.length != 2 || !fields[0].startsWith("/")
                            || !fields[1].matches("native(font|cfg)_[0-9a-f]{64}\\.(font|xml)"))
                        throw new IOException("Invalid native font snapshot");
                    paths.add(fields[0]); names.add(fields[1]);
                    if (paths.size() > 128) throw new IOException("Native font snapshot too large");
                }
            }
            int[] fds = new int[names.size()];
            for (int i = 0; i < names.size(); i++) {
                ParcelFileDescriptor fd = module.openRemoteFile(names.get(i));
                descriptors.add(fd); fds[i] = fd.getFd();
            }
            System.loadLibrary("coloros_font_bridge");
            active = configure(paths.toArray(new String[0]), fds);
            if (!active) throw new IOException("Framework native hook initialization unavailable");
            libraryOwner = NativeFontHooks.class;
        } catch (Throwable error) { XposedInit.log("native font setup failed: " + error); }
        finally { for (ParcelFileDescriptor fd : descriptors) try { fd.close(); } catch (IOException ignored) {} }
    }
    private NativeFontHooks() {}
}
