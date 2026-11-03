package com.rikumi.colorosmod;

import android.content.Context;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import dalvik.system.PathClassLoader;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileLock;
import java.util.*;
import org.json.*;
import org.xmlpull.v1.XmlPullParser;

/** One-time root setup; later boots consume the data XML without this module or root. */
public final class PlayStoreRecoveryCommand {
    private static final File CONFIG = new File("/data/oplus/os/config/sys_pms_defaultpackage_list.xml");
    private static final File STATE_DIR = new File("/data/system/colorosmod-play-store-recovery");
    private static final File STATE = new File(STATE_DIR, "entries.json");
    public static final String DATA_PROPERTY = "persist.sys.colorosmod.play_data_fix";
    public static final String OK = "PLAY_STORE_RECOVERY_OK";

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0 || args.length != 1) throw new IllegalArgumentException("Root and one operation required");
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            if (args[0].equals("verify-current") || args[0].equals("check-current"))
                verifyCurrent(args[0].equals("verify-current") ? 35 : 1);
            else if (args[0].equals("enable") || args[0].equals("disable")) {
                if (!STATE_DIR.isDirectory() && !STATE_DIR.mkdir()) throw new IOException("Cannot create backup directory");
                Os.chmod(STATE_DIR.getPath(), 0700);
                try (RandomAccessFile file = new RandomAccessFile(new File(STATE_DIR, "lock"), "rw");
                     FileLock lock = file.getChannel().lock()) {
                    configure(args[0].equals("enable"));
                }
            } else throw new IllegalArgumentException("Unknown operation");
            System.out.println(OK);
            System.exit(0);
        } catch (Throwable error) {
            while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null)
                error = error.getCause();
            System.err.println("PLAY_STORE_RECOVERY_ERROR: " + error);
            System.exit(1);
        }
    }

    private static ClassLoader nativeLoader() {
        String paths = System.getenv("SYSTEMSERVERCLASSPATH");
        paths = (paths == null ? "" : paths + ":") + "/system/framework/oplus-services.jar:/system/framework/services.jar";
        return new PathClassLoader(paths, PlayStoreRecoveryCommand.class.getClassLoader());
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> defaults(ClassLoader loader) throws Exception {
        Class<?> helper = Class.forName("com.android.server.pm.OplusOsPackageManagerHelper", true, loader);
        var field = helper.getDeclaredField("DEFAULT_SPECIAL_SAFE_MODE_PACKAGE_SET");
        field.setAccessible(true);
        return new LinkedHashSet<>((Set<String>) field.get(null));
    }

    private static byte[] read(File file) throws IOException {
        if (!file.exists()) return new byte[0];
        if (file.length() > 2 * 1024 * 1024) throw new IOException("Configuration is too large");
        return Files.readAllBytes(file.toPath());
    }

    private static List<String> strings(JSONArray array) throws JSONException {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }

    private static void property(String key, String value) throws Exception {
        java.lang.Process process = new ProcessBuilder("/system/bin/setprop", key, value).start();
        if (process.waitFor() != 0) throw new IOException("Cannot save " + key);
        Class<?> properties = Class.forName("android.os.SystemProperties");
        if (!value.equals(properties.getMethod("get", String.class).invoke(null, key)))
            throw new IOException("Property verification failed: " + key);
    }

    private static void atomicWrite(File file, byte[] bytes, boolean vendorConfig) throws Exception {
        File temporary = File.createTempFile("colorosmod-", ".new", file.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.getFD().sync();
            }
            if (vendorConfig && file.exists()) {
                var stat = Os.stat(file.getPath());
                Os.chown(temporary.getPath(), stat.st_uid, stat.st_gid);
                Os.chmod(temporary.getPath(), stat.st_mode & 0777);
                Os.setxattr(temporary.getPath(), "security.selinux", Os.getxattr(file.getPath(), "security.selinux"), 0);
            } else {
                Os.chown(temporary.getPath(), vendorConfig ? 1000 : 0, vendorConfig ? 1000 : 0);
                Os.chmod(temporary.getPath(), vendorConfig ? 0644 : 0600);
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (vendorConfig) {
                java.lang.Process process = new ProcessBuilder("/system/bin/restorecon", file.getPath()).start();
                if (process.waitFor() != 0) throw new IOException("Cannot restore configuration label");
            }
            var parent = Os.open(file.getParent(), OsConstants.O_RDONLY, 0);
            try { Os.fsync(parent); } finally { Os.close(parent); }
        } finally { temporary.delete(); }
    }

    private static void configure(boolean enable) throws Exception {
        if (!CONFIG.getParentFile().isDirectory()) throw new IOException("Vendor PMS configuration directory is unavailable");
        byte[] before = read(CONFIG);
        List<String> current = PlayStoreRecoveryConfig.entries(before);
        JSONObject state = STATE.isFile() ? new JSONObject(new String(read(STATE), java.nio.charset.StandardCharsets.UTF_8)) : null;
        Class<?> properties = Class.forName("android.os.SystemProperties");
        var getProperty = properties.getMethod("get", String.class);
        String oldEnabled = (String) getProperty.invoke(null, PlayStoreUpdatePolicy.PROPERTY);
        String oldData = (String) getProperty.invoke(null, DATA_PROPERTY);
        byte[] changed = before;
        if (enable) {
            if (state == null || !current.equals(strings(state.getJSONArray("applied")))) {
                ClassLoader loader = nativeLoader();
                var valid = Class.forName("com.android.server.pm.PackageInstallerService", true, loader)
                        .getDeclaredMethod("isValidPackageName", String.class);
                Set<String> selected = PlayStoreRecoveryConfig.protectedPackages(before, defaults(loader), name -> {
                    try { return (Boolean) valid.invoke(null, name); }
                    catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                });
                state = new JSONObject().put("original", new JSONArray(current)).put("applied", new JSONArray(selected));
                // Backup before modifying the vendor file. Repeated enable keeps the first backup.
                atomicWrite(STATE, state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
                changed = PlayStoreRecoveryConfig.rewrite(before, selected);
            }
        } else if (state != null && current.equals(strings(state.getJSONArray("applied")))) {
            // Restore only our entries; keep unrelated configuration and later vendor updates.
            changed = PlayStoreRecoveryConfig.rewrite(before, strings(state.getJSONArray("original")));
        }
        if (!Arrays.equals(before, read(CONFIG))) throw new IOException("Vendor configuration changed during setup");
        try {
            if (!Arrays.equals(before, changed)) atomicWrite(CONFIG, changed, true);
            if (enable && PlayStoreRecoveryConfig.entries(read(CONFIG)).stream()
                    .anyMatch(value -> PlayStoreUpdatePolicy.PACKAGE.equals(value.trim())))
                throw new IOException("Play Store remains in the recovery list");
            property(PlayStoreUpdatePolicy.PROPERTY, enable ? "1" : "0");
            property(DATA_PROPERTY, enable ? "1" : "0");
            if (!enable) Files.deleteIfExists(STATE.toPath());
        } catch (Throwable error) {
            try { if (!Arrays.equals(before, changed)) atomicWrite(CONFIG, before, true); }
            catch (Throwable rollback) { error.addSuppressed(rollback); }
            try { property(PlayStoreUpdatePolicy.PROPERTY, oldEnabled); }
            catch (Throwable rollback) { error.addSuppressed(rollback); }
            try { property(DATA_PROPERTY, oldData); }
            catch (Throwable rollback) { error.addSuppressed(rollback); }
            throw error;
        }
    }

    private static void verifyCurrent(int attempts) throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getDeclaredMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getDeclaredMethod("getSystemContext").invoke(thread);
        String expected = null;
        long version = 0;
        for (int attempt = 0; attempt < attempts; attempt++) {
            var info = context.getPackageManager().getPackageInfo(PlayStoreUpdatePolicy.PACKAGE, 0);
            expected = new File(info.applicationInfo.sourceDir).getParent();
            version = info.getLongVersionCode();
            try (InputStream input = new FileInputStream("/data/system/packages.xml")) {
                XmlPullParser parser = (XmlPullParser) android.util.Xml.class
                        .getDeclaredMethod("resolvePullParser", InputStream.class).invoke(null, input);
                for (int type = parser.getEventType(); type != XmlPullParser.END_DOCUMENT; type = parser.next()) {
                    if (type == XmlPullParser.START_TAG && "package".equals(parser.getName())
                            && PlayStoreUpdatePolicy.PACKAGE.equals(parser.getAttributeValue(null, "name"))
                            && expected.equals(parser.getAttributeValue(null, "codePath"))
                            && Long.toString(version).equals(parser.getAttributeValue(null, "version"))) return;
                }
            }
            if (attempt + 1 < attempts) Thread.sleep(1000);
        }
        throw new IOException("Play Store live package was not persisted: " + expected + " version=" + version);
    }

    private PlayStoreRecoveryCommand() {}
}
