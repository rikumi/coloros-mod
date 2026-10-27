package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.KeyListener;
import android.text.method.TextKeyListener;
import android.widget.EditText;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

public final class SenderIdHooks {
    public static final String KEY = "sms_list_sender_id_enabled";
    private static final String EDITOR = "com.oplus.blacklistapp.fragment.EditNumberFragment";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<EditText, InputState> INPUTS = new WeakHashMap<>();
    private static final ThreadLocal<Integer> SUBMIT = new ThreadLocal<>();
    private static final Runnable REFRESH = () -> {
        boolean enabled = readBoolCached(KEY, false);
        for (var item : INPUTS.entrySet()) update(item.getKey(), item.getValue(), enabled);
    };
    private static final class InputState {
        final int type;
        final KeyListener listener;
        boolean applied;
        InputState(EditText input) { type = input.getInputType(); listener = input.getKeyListener(); }
    }
    private static void update(EditText input, InputState state, boolean enabled) {
        if (enabled == state.applied) return;
        int start = input.getSelectionStart(), end = input.getSelectionEnd();
        if (enabled) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setKeyListener(TextKeyListener.getInstance(false, TextKeyListener.Capitalize.NONE));
        } else {
            input.setInputType(state.type);
            input.setKeyListener(state.listener);
        }
        state.applied = enabled;
        int length = input.length();
        if (start >= 0 && end >= 0) input.setSelection(Math.min(start, length), Math.min(end, length));
    }
    public static void onSettingsSnapshotPublished() {
        MAIN.removeCallbacks(REFRESH); MAIN.post(REFRESH);
    }
    public static void cleanupForHotReload() {
        MAIN.removeCallbacks(REFRESH);
        for (var item : INPUTS.entrySet()) update(item.getKey(), item.getValue(), false);
        INPUTS.clear(); SUBMIT.remove();
    }
    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XC_MethodHook editorCreated = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!EDITOR.equals(p.thisObject.getClass().getName())) return;
                    try {
                        Object pref = XposedHelpers.callMethod(p.thisObject, "findPreference", "edit_number");
                        if (pref == null) return;
                        Object view = XposedHelpers.callMethod(pref, "getEditText");
                        if (view instanceof EditText input) {
                            InputState state = INPUTS.get(input);
                            if (state == null) { state = new InputState(input); INPUTS.put(input, state); }
                            update(input, state, readBoolCached(KEY, false));
                        }
                    } catch (Throwable t) { log("Sender ID editor: " + t); }
                }
            };
            XposedHelpers.findAndHookMethod(EDITOR, lp.classLoader, "onCreate", android.os.Bundle.class, editorCreated);
            XposedHelpers.findAndHookMethod("androidx.fragment.app.Fragment", lp.classLoader, "onResume", editorCreated);
            // Both inserts/updates and blacklist/whitelist lookup SQL use this helper.
            // Empty E164 selects the native, SQL-escaped original-number equality path.
            XposedHelpers.findAndHookMethod("com.oplus.utils.i", lp.classLoader, "f",
                    Context.class, String.class, String.class, String.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (readBoolCached(KEY, false) && SenderIdPolicy.isTextSender((String) p.args[1]))
                                p.setResult("");
                        }
                    });
            log("HOOK OK SMS blacklist/whitelist Sender ID input and exact lookup");
        } catch (Throwable t) { log("HOOK FAIL SMS Sender ID support: " + t); }
        try {
            // Native submission removes every space for phone numbers. Limit the exception
            // to this editor's click callback and text senders; keep its own save/confirm path.
            XposedHelpers.findAndHookMethod(EDITOR + "$onCreate$2", lp.classLoader,
                    "onButtonClick", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            int depth = SUBMIT.get() == null ? 0 : SUBMIT.get();
                            p.setObjectExtra("senderSubmitDepth", depth); SUBMIT.set(depth + 1);
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            Object previous = p.getObjectExtra("senderSubmitDepth");
                            if (previous instanceof Integer depth && depth > 0) SUBMIT.set(depth);
                            else SUBMIT.remove();
                        }
                    });
            XposedHelpers.findAndHookMethod("n7.x", lp.classLoader, "J", String.class, String.class,
                    String.class, boolean.class, int.class, Object.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (readBoolCached(KEY, false) && SUBMIT.get() != null
                                    && " ".equals(p.args[1]) && "".equals(p.args[2])
                                    && SenderIdPolicy.isTextSender((String) p.args[0])) p.setResult(p.args[0]);
                        }
                    });
        } catch (Throwable t) { log("HOOK FAIL Sender ID space preservation: " + t); }
    }
}
