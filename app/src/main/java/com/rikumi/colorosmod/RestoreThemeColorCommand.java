package com.rikumi.colorosmod;

import android.content.res.Configuration;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** 只恢复旧版全局主题色备份，不提供启用或自定义主题色的入口。 */
public final class RestoreThemeColorCommand {
    private static final String[] FILES = {"ux_custom_color.xml", "ux_custom_color_night.xml"};

    public static void main(String[] args) {
        try {
            restore(args);
            System.out.println("THEME_RESTORE_OK");
            System.exit(0);
        } catch (Throwable t) {
            while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) t = t.getCause();
            System.err.println("THEME_RESTORE_ERROR: " + t);
            System.exit(1);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static void restore(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("expected backup directory");
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class.forName("android.app.ActivityThread").getDeclaredMethod("systemMain").invoke(null);
        Class<?> activityManager = Class.forName("android.app.ActivityManager");
        int user = (Integer) activityManager.getDeclaredMethod("getCurrentUser").invoke(null);
        File backup = new File(args[0], Integer.toString(user));
        File stateFile = new File(backup, "original.properties");
        if (!stateFile.exists()) return;
        Properties original = new Properties();
        try (FileInputStream in = new FileInputStream(stateFile)) { original.load(in); }
        File directory = new File("/data/oplus/uxres/uxcolor" + (user > 0 ? "/" + user : ""));
        for (String name : FILES) {
            File destination = new File(directory, name);
            if (Boolean.parseBoolean(original.getProperty(name))) {
                Files.copy(new File(backup, name).toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Process restorecon = new ProcessBuilder("/system/bin/restorecon", destination.getPath()).start();
                if (restorecon.waitFor() != 0) throw new IllegalStateException("cannot restore color file context");
            } else Files.deleteIfExists(destination.toPath());
        }
        writeSetting(user, "system", "material_color_mode", setting(original, "setting"));
        writeSetting(user, "secure", "theme_customization_overlay_packages", setting(original, "palette"));
        Object service = activityManager.getDeclaredMethod("getService").invoke(null);
        Configuration config = (Configuration) invoke(service, "getConfiguration", new Class<?>[0]);
        Object extra = invoke(config, "getOplusExtraConfiguration", new Class<?>[0]);
        extra.getClass().getField("mMaterialColor").setLong(extra, Long.parseLong(original.getProperty("mode")));
        Object manager = Class.forName("android.app.OplusActivityManager").getMethod("getInstance").invoke(null);
        if (!Boolean.TRUE.equals(invoke(manager, "updateConfiguration", new Class<?>[]{Configuration.class}, config)))
            throw new IllegalStateException("system rejected original theme configuration");
        Files.delete(stateFile.toPath());
        for (String name : FILES) Files.deleteIfExists(new File(backup, name).toPath());
    }

    private static String setting(Properties state, String key) {
        return Boolean.parseBoolean(state.getProperty(key + ".exists")) ? state.getProperty(key) : null;
    }

    private static String settingsCommand(int user, String verb, String table, String key, String value) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.Arrays.asList(
                "/system/bin/cmd", "settings", "--user", Integer.toString(user), verb, table, key));
        if (value != null) command.add(value);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (java.io.InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exit = process.waitFor();
        if (exit != 0) throw new IllegalStateException("settings " + verb + " " + key + " failed: " + output.trim());
        return output;
    }

    private static void writeSetting(int user, String table, String key, String value) throws Exception {
        settingsCommand(user, value == null ? "delete" : "put", table, key, value);
        String output = settingsCommand(user, "get", table, key, null);
        if (output.endsWith("\n")) output = output.substring(0, output.length() - 1);
        if (output.endsWith("\r")) output = output.substring(0, output.length() - 1);
        if (!java.util.Objects.equals(value, output.equals("null") ? null : output))
            throw new IllegalStateException("system rejected original setting " + key);
    }
}
