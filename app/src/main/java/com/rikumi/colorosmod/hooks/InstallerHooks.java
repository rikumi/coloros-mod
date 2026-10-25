package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

public final class InstallerHooks {
    public static final String DISABLE_SCAN = "installer_disable_virus_scan";
    public static final String HIDE_SUGGESTIONS = "installer_hide_finish_suggestions";
    public static final String SHOW_HEADER = "installer_show_first_header";
    private static final String FIRST = "com.android.packageinstaller.oplus.OPlusPackageInstallerActivity";
    private static final java.util.Set<String> SUGGESTIONS = java.util.Set.of(
            "suggest_A_scroll_layout", "install_done_suggest_A", "install_done_suggest_A_title",
            "install_done_suggest_B", "install_done_suggest_C");
    private static final java.util.Set<String> SCAN_VIEWS = java.util.Set.of("scan_area", "virus_area");

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {

            XposedHelpers.findAndHookMethod("e1.k", lp.classLoader, "v", Context.class, Intent.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Intent intent = (Intent) p.args[1];
                            if (intent != null && "oplus.intent.action.CHECK_APK_VIRUS".equals(intent.getAction())
                                    && readBool(DISABLE_SCAN, false)) p.setResult(null);
                        }
                    });
        } catch (Throwable error) { log("installer scan hook failed: " + error); }
        try {
            // Completion Handler callbacks can reveal these sections after initial layout.
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if ((Integer) p.args[0] == View.GONE) return;
                    boolean suggestions = readBoolCached(HIDE_SUGGESTIONS, false);
                    boolean scan = readBoolCached(DISABLE_SCAN, false);
                    boolean header = readBoolCached(SHOW_HEADER, false);
                    if (!suggestions && !scan && !header) return;
                    View view = (View) p.thisObject;
                    if ((view.getId() >>> 24) != 0x7f) return;
                    try {
                        String name = view.getResources().getResourceEntryName(view.getId());
                        if ((suggestions && SUGGESTIONS.contains(name)) || (scan && SCAN_VIEWS.contains(name))
                                || (header && "cancel_button".equals(name) && firstScreen(view.getContext())))
                            p.args[0] = View.GONE;
                    } catch (android.content.res.Resources.NotFoundException ignored) { }
                }
            });
        } catch (Throwable error) { log("installer suggestion hook failed: " + error); }
        try {
            // I1() inflates install_start and wires its controls before asynchronous work.
            XposedHelpers.findAndHookMethod(FIRST, lp.classLoader, "I1", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!readBool(SHOW_HEADER, false)) return;
                    try { addHeader((Activity) p.thisObject); }
                    catch (Throwable error) { log("installer header failed: " + error); }
                }
            });
        } catch (Throwable error) { log("installer header hook failed: " + error); }
    }

    private static boolean firstScreen(Context context) {
        while (context instanceof android.content.ContextWrapper wrapper) {
            if (FIRST.equals(context.getClass().getName())) return true;
            Context next = wrapper.getBaseContext();
            if (next == context) break;
            context = next;
        }
        return false;
    }

    private static int id(Activity activity, String name) {
        return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
    }

    private static void addHeader(Activity activity) {
        View found = activity.findViewById(id(activity, "install_start_layout"));
        if (!(found instanceof RelativeLayout root) || activity.findViewById(id(activity, "toolbar")) != null) return;
        int layout = activity.getResources().getIdentifier("appbar_with_divider_layout", "layout", activity.getPackageName());
        View bar = LayoutInflater.from(activity).inflate(layout, root, false);
        Object toolbar = bar.findViewById(id(activity, "toolbar"));

        int back = activity.getResources().getIdentifier("coui_back_arrow", "drawable", activity.getPackageName());
        XposedHelpers.callMethod(toolbar, "setNavigationIcon", back);
        XposedHelpers.callMethod(toolbar, "setNavigationOnClickListener", (View.OnClickListener) v -> activity.onBackPressed());
        root.addView(bar, 0);
        View cancel = activity.findViewById(id(activity, "cancel_button"));
        if (cancel != null) cancel.setVisibility(View.GONE);
        for (String name : new String[]{"apk_info_view", "virus_scan_panel"}) {
            View view = activity.findViewById(id(activity, name));
            if (view != null && view.getLayoutParams() instanceof RelativeLayout.LayoutParams params) {
                params.addRule(RelativeLayout.BELOW, bar.getId());
                view.setLayoutParams(params);
            }
        }
    }
}
