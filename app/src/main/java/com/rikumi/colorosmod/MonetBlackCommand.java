package com.rikumi.colorosmod;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Looper;
import android.util.TypedValue;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

/** Executed only by su/app_process. Uses OMS transactions; never edits theme settings or system files. */
public final class MonetBlackCommand {
    private static final String PREFIX = "colorosmod_monet_oled_";
    private static final String OWNER = "android";

    public static void main(String[] args) {
        try {
            execute(args);
            System.out.println("MONET_BLACK_OK");
            System.exit(0);
        } catch (Throwable error) {
            while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) error = error.getCause();
            System.err.println("MONET_BLACK_ERROR: " + error);
            System.exit(1);
        }
    }

    static void execute(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("enable") || args[0].equals("disable")))
            throw new IllegalArgumentException("Expected enable/disable and module APK");
        if (Build.VERSION.SDK_INT < 34) throw new UnsupportedOperationException("Android 14 or newer required");
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        int user = (Integer) Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null);
        Set<Integer> users = new LinkedHashSet<>(); users.add(user);
        Object userManager = context.getSystemService(Context.USER_SERVICE);
        if (userManager != null) {
            List<?> profiles = (List<?>) userManager.getClass().getMethod("getProfiles", int.class).invoke(userManager, user);
            for (Object profile : profiles) users.add(profile.getClass().getField("id").getInt(profile));
        }
        Class<?> binder = Class.forName("android.os.IBinder");
        Object serviceBinder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "overlay");
        Class<?> serviceType = Class.forName("android.content.om.IOverlayManager");
        Object service = Class.forName("android.content.om.IOverlayManager$Stub").getMethod("asInterface", binder).invoke(null, serviceBinder);
        if (service == null) throw new IllegalStateException("Overlay service unavailable");
        Class<?> identifierType = Class.forName("android.content.om.OverlayIdentifier");
        Class<?> transactionType = Class.forName("android.content.om.OverlayManagerTransaction");
        Class<?> transactionBuilderType = Class.forName("android.content.om.OverlayManagerTransaction$Builder");
        Object transaction = transactionBuilderType.getConstructor().newInstance();
        Method getInfo = serviceType.getMethod("getOverlayInfoByIdentifier", identifierType, int.class);
        List<Object> identifiers = new ArrayList<>();
        List<MonetBlackPalette.Entry> palette = List.of();
        if (args[0].equals("enable")) {
            try (ZipFile apk = new ZipFile(args[1])) {
                String asset = "assets/monet/tuned-oled-black-" + (Build.VERSION.SDK_INT >= 35 ? "35" : "34") + ".tsv";
                if (apk.getEntry(asset) == null) throw new IllegalStateException("Built-in Monet palette missing");
                palette = MonetBlackPalette.read(new InputStreamReader(apk.getInputStream(apk.getEntry(asset)), StandardCharsets.US_ASCII));
            }
        }
        for (String target : new String[]{"android", "com.android.systemui"}) {
            String name = PREFIX + (target.equals("android") ? "framework" : "systemui") + "_u" + user;
            Object identifier = identifierType.getConstructor(String.class, String.class).newInstance(OWNER, name);
            identifiers.add(identifier);
            if (args[0].equals("disable")) {
                // Unregister only overlays owned by this module; other theme tools remain untouched.
                if (getInfo.invoke(service, identifier, user) != null)
                    transactionBuilderType.getMethod("unregisterFabricatedOverlay", identifierType).invoke(transaction, identifier);
                continue;
            }
            Resources resources = context.getPackageManager().getResourcesForApplication(target);
            Class<?> builderType = Class.forName("android.content.om.FabricatedOverlay$Builder");
            Object overlay = builderType.getConstructor(String.class, String.class, String.class).newInstance(OWNER, name, target);
            Method put = builderType.getMethod("setResourceValue", String.class, int.class, int.class, String.class);
            int count = 0;
            for (MonetBlackPalette.Entry entry : palette) {
                if (!entry.target().equals(target)) continue;
                int id = resources.getIdentifier(entry.name(), entry.type(), target);
                // A ROM can omit tokens or inherit framework resources in SystemUI. The framework
                // overlay already handles inherited resources, so never register them twice.
                if (id == 0 || !resources.getResourcePackageName(id).equals(target)) continue;
                if (target.equals("android") && (entry.name().startsWith("settingslib_materialColor")
                        || entry.name().startsWith("materialColor") || entry.name().startsWith("customColor"))) {
                    TypedValue original = new TypedValue(); resources.getValue(id, original, false);
                    if (original.type == TypedValue.TYPE_REFERENCE) continue;
                }
                put.invoke(overlay, resources.getResourceName(id), entry.type().equals("color") ? 0x1c : 0x12,
                        entry.value(), entry.configuration());
                count++;
            }
            if (count == 0) {
                if (target.equals("android")) throw new IllegalStateException("No system Monet resources found");
                identifiers.remove(identifier);
                continue;
            }
            Object fabricated = builderType.getMethod("build").invoke(overlay);
            transactionBuilderType.getMethod("registerFabricatedOverlay", Class.forName("android.content.om.FabricatedOverlay"))
                    .invoke(transaction, fabricated);
            for (int profile : users)
                transactionBuilderType.getMethod("setEnabled", identifierType, boolean.class, int.class).invoke(transaction, identifier, true, profile);
        }
        Object built = transactionBuilderType.getMethod("build").invoke(transaction);
        serviceType.getMethod("commit", transactionType).invoke(service, built);
        for (Object identifier : identifiers) for (int profile : users) {
            Object info = getInfo.invoke(service, identifier, profile);
            if (args[0].equals("enable")) {
                if (info == null || !Boolean.TRUE.equals(info.getClass().getMethod("isEnabled").invoke(info)))
                    throw new IllegalStateException("System rejected Monet overlay for user " + profile);
            } else if (info != null) throw new IllegalStateException("System did not remove Monet overlay for user " + profile);
        }
    }
    private MonetBlackCommand() {}
}
