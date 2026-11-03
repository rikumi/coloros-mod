package com.rikumi.colorosmod.hooks;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Toast;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static com.rikumi.colorosmod.XposedInit.*;

/** ColorOS 17 clear-all: retain the native cleanup, report only after our kills complete. */
public final class RecentsClearMemoryHooks {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean installed;
    private static volatile Batch active;
    private static volatile boolean live = true;

    /** Athena 7.0.0 ignores IsShowCleanFinishToast and emits its result from o0(Bundle). */
    public static void hookAthena(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT < 37) return;
        try {
            Class<?> service = XposedHelpers.findClass("com.oplus.athena.client.AthenaService", lp.classLoader);
            XposedHelpers.findAndHookMethod(service, "o0", Bundle.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!readBool(KEY_RECENTS_SWIPE_UP_KILL_ENABLED, false)) return;
                    Bundle result = (Bundle) p.args[0];
                    if (result != null && "oplus.intent.action.REQUEST_APP_CLEAN_RUNNING"
                            .equals(result.getString("action"))) p.setResult(null);
                }
            });
        } catch (Throwable error) { log("Athena clear-result toast hook unavailable: " + error); }
    }

    private static final class Batch {
        final Object owner;
        final Context context;
        final long before;
        final Set<String> targets;
        final CountDownLatch nativeRequest = new CountDownLatch(1);
        volatile boolean started;
        Batch(Object owner, Context context, Set<String> targets) {
            this.owner = owner;
            this.context = context.getApplicationContext();
            this.targets = targets;
            before = available(context);
        }
    }

    static boolean handlesClearAll() { return installed; }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (Build.VERSION.SDK_INT < 37 || !"com.android.launcher".equals(lp.processName)) return;
        try {
            Class<?> recents = XposedHelpers.findClass(
                    "com.android.quickstep.views.OplusRecentsViewImpl", lp.classLoader);
            Method finish = recents.getDeclaredMethod("onClearAllAnimationFinished", Set.class,
                    List.class, XposedHelpers.findClass(
                            "com.android.quickstep.views.OplusTaskViewImpl", lp.classLoader));
            // Both transports are used by KillAppWrapper in Launcher 17.3.12.
            hookNativeRequest(XposedHelpers.findClass("android.app.ContextImpl", lp.classLoader),
                    "startService", Intent.class);
            hookNativeRequest(XposedHelpers.findClass(
                    "com.oplus.osense.OsenseResEventClient", lp.classLoader),
                    "requestSceneAction", Bundle.class);
            XposedBridge.hookMethod(finish, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Batch batch = active;
                    if (!live || batch == null || batch.owner != p.thisObject || batch.started) return;
                    try {
                        Set<String> keep = new HashSet<>();
                        if (p.args[1] instanceof List<?> keptTasks)
                            for (Object task : keptTasks) addTask(keep, task);
                        if (p.args[0] instanceof Set<?> keptViews)
                            for (Object view : keptViews) addTaskView(keep, view);
                        if (p.args[2] != null) addTaskView(keep, p.args[2]);
                        batch.targets.removeAll(keep);
                        if (batch.targets.isEmpty()) { active = null; return; }
                        batch.started = true;
                        new Thread(() -> complete(batch), "ColorOSMod-ClearMemory").start();
                    } catch (Throwable error) { active = null; log("clear-all targets unavailable: " + error); }
                }
            });
            XposedHelpers.findAndHookMethod(recents, "dismissAllTasks", View.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!live) return;
                    if (active != null) { p.setResult(null); return; }
                    if (!readBool(KEY_RECENTS_SWIPE_UP_KILL_ENABLED, false)) return;
                    try {
                        Set<String> targets = new HashSet<>();
                        int count = (Integer) XposedHelpers.callMethod(p.thisObject, "getTaskViewCount");
                        for (int i = 0; i < count; i++) {
                            addTaskView(targets, XposedHelpers.callMethod(p.thisObject, "getTaskViewAt", i));
                        }
                        if (targets.isEmpty()) return;
                        Batch batch = new Batch(p.thisObject,
                                (Context) XposedHelpers.callMethod(p.thisObject, "getContext"), targets);
                        active = batch;
                        MAIN.postDelayed(() -> {
                            if (active == batch && !batch.started) active = null;
                        }, 5000L); // a cancelled dismissal must not leave a pending batch
                    } catch (Throwable error) { active = null; log("clear-all memory baseline failed: " + error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Batch batch = active;
                    if (p.hasThrowable() && batch != null && batch.owner == p.thisObject) active = null;
                }
            });
            installed = true;
        } catch (Throwable error) { log("clear-all memory hooks unavailable: " + error); }
    }

    private static void addTask(Set<String> targets, Object task) {
        if (task != null) addKey(targets, XposedHelpers.getObjectField(task, "key"));
    }

    private static void addTaskView(Set<String> targets, Object view) {
        if (view == null) return;
        // This includes both halves of grouped cards and embedded tasks on 17.3.12.
        for (Object key : (List<?>) XposedHelpers.callMethod(view, "getAllTaskKeysIncluded")) addKey(targets, key);
    }

    private static void addKey(Set<String> targets, Object key) {
        if (key == null) return;
        String pkg;
        int user;
        try { pkg = (String) XposedHelpers.callMethod(key, "getPackageName"); }
        catch (Throwable legacy) { pkg = (String) XposedHelpers.callMethod(key, "e"); }
        try { user = XposedHelpers.getIntField(key, "userId"); }
        catch (Throwable legacy) { user = XposedHelpers.getIntField(key, "q"); }
        if (pkg != null && !pkg.isEmpty() && user >= 0) targets.add(user + ":" + pkg);
    }

    private static void hookNativeRequest(Class<?> type, String name, Class<?> argument) {
        XposedHelpers.findAndHookMethod(type, name, argument, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Batch batch = active;
                if (!live || batch == null || !batch.started) return;
                Bundle params;
                Intent intent = p.args[0] instanceof Intent ? new Intent((Intent) p.args[0]) : null;
                Bundle request = p.args[0] instanceof Bundle ? new Bundle((Bundle) p.args[0]) : null;
                if (intent != null) {
                    if (!"com.oplus.athena".equals(intent.getPackage())
                            || !"oplus.intent.action.REQUEST_APP_CLEAN_RUNNING".equals(intent.getAction())) return;
                    params = intent.getExtras();
                } else {
                    if (request == null || !"SCENE_SPECIAL_REQUEST".equals(request.getString("action"))
                            || !"clean".equals(request.getString("policy"))) return;
                    Bundle original = request.getBundle("clear_params");
                    params = original == null ? null : new Bundle(original);
                }
                if (params == null || !"com.oplus.recents".equals(params.getString("caller_package"))
                        || !params.getBoolean("clear_task") || !params.getBoolean("IsShowCleanFinishToast")) return;
                if (intent != null) {
                    intent.putExtra("IsShowCleanFinishToast", false);
                    p.args[0] = intent;
                } else {
                    params.putBoolean("IsShowCleanFinishToast", false);
                    request.putBundle("clear_params", params);
                    p.args[0] = request;
                }
                p.setObjectExtra("colorosmod_clear_memory_batch", batch);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Object batch = p.getObjectExtra("colorosmod_clear_memory_batch");
                if (!p.hasThrowable() && batch instanceof Batch submitted) submitted.nativeRequest.countDown();
            }
        });
    }

    private static long available(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) throw new IllegalStateException("ActivityManager unavailable");
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return info.availMem;
    }

    private static void complete(Batch batch) {
        try {
            ActivityManager am = (ActivityManager) batch.context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) throw new IllegalStateException("ActivityManager unavailable");
            for (String target : batch.targets) am.killBackgroundProcesses("cmpkg:" + target);
            // This call runs on our worker, never the Launcher/UI thread. The service
            // waits for all registered kill workers, including kernel process exits.
            am.killBackgroundProcesses("cmwait:");
            if (!batch.nativeRequest.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Native clear-all request did not complete");
            long released = Math.max(0L, available(batch.context) - batch.before);
            MAIN.post(() -> {
                if (!live || active != batch) return; // discard an older click/reloaded process
                active = null;
                try { showResult(batch.context, released); }
                catch (Throwable error) { log("clear-all memory result unavailable: " + error); }
            });
        } catch (Throwable error) {
            if (active == batch) active = null;
            log("clear-all completion failed; no success memory result: " + error);
        }
    }

    private static void showResult(Context context, long bytes) throws Exception {
        // Reuse the installed cleaning service's translations instead of adding UI copy.
        Context athena = context.createPackageContext("com.oplus.athena", 0);
        String resource = bytes < 5L * 1024 * 1024 ? "memory_clean_empty_tips" : "memery_clean_result";
        int id = athena.getResources().getIdentifier(resource, "string", "com.oplus.athena");
        if (id == 0) throw new IllegalStateException("Native memory-result resource missing");
        String message = athena.getString(id);
        if (bytes >= 5L * 1024 * 1024) message = String.format(message, Formatter.formatFileSize(athena, bytes));
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    static boolean isBusy() { return active != null; }
    static void cleanup() { live = false; active = null; }
    private RecentsClearMemoryHooks() {}
}
