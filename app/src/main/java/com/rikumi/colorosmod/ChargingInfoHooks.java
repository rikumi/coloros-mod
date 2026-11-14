package com.rikumi.colorosmod;

import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.rikumi.colorosmod.xposed.*;
import com.rikumi.colorosmod.hooks.StatusBarExtrasHooks;
import com.rikumi.colorosmod.hooks.StatusBarFontHooks;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.rikumi.colorosmod.XposedInit.*;

/** Flyme charging-input sampling, attached to ColorOS's native status-bar battery layout. */
public final class ChargingInfoHooks {
    public static final String KEY = "status_bar_charging_info";
    private static ChargingInfoHooks instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "ColorOSChargingInput"); thread.setDaemon(true); return thread;
    });
    private final Map<View, WeakReference<State>> states = new WeakHashMap<>();
    private final Map<TextView, View.OnAttachStateChangeListener> percentListeners = new WeakHashMap<>();
    private ChargingInfo.Sample sample;
    private String protocol = "";
    private long sampledAt;
    private int generation;
    private boolean active = true, polling, inFlight, screenOn = true, charging;
    private Context receiverContext;
    private String lastReadStatus;
    private final Runnable tick = this::read;
    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) screenOn = false;
            else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) screenOn = true;
            else if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) batteryChanged(intent);
            updateAll();
        }
    };

    private static int mode() { return readIntCached(KEY, ChargingInfo.OFF); }

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (instance != null || Build.VERSION.SDK_INT < 33) return;
        ChargingInfoHooks hooks = new ChargingInfoHooks();
        try {
            Class<?> binder = XposedHelpers.findClass(
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.binder.BatteryViewBinder", lp.classLoader);
            XposedBridge.hookAllMethods(binder, "bind", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!hooks.active || p.hasThrowable()) return;
                    View primary = null;
                    TextView reference = null;
                    for (Object arg : p.args) {
                        if (arg instanceof TextView text) reference = text;
                        else if (arg instanceof View view) {
                            if (primary == null || batteryView(view)) primary = view;
                        }
                        else if (arg != null && arg.getClass().getSimpleName().endsWith("Binding")) try {
                            Object root = XposedHelpers.callMethod(arg, "getRoot");
                            if (root instanceof View view) primary = view;
                        } catch (RuntimeException | NoSuchMethodError ignored) {}
                    }
                    if (primary != null) hooks.observe(primary, reference);
                    else if (reference != null) hooks.observePercent(reference);
                }
            });
            XposedBridge.hookAllMethods(binder, "bind$updatePercentOutView", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!hooks.active || p.hasThrowable() || p.args.length == 0
                            || !(p.args[0] instanceof TextView percent)) return;
                    hooks.observePercent(percent);
                }
            });
            // Cover bindings that begin before the percent view is attached, and native rebuilds.
            for (String name : new String[]{"com.android.systemui.battery.BatteryMeterView",
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.view.BatteryView",
                    "com.oplus.systemui.statusbar.pipeline.battery.ui.view.BatteryMeterView",
                    "com.oplus.systemui.statusbar.widget.OplusBatteryMeterView"}) {
                Class<?> battery = XposedHelpers.findClassIfExists(name, lp.classLoader);
                if (battery != null) XposedBridge.hookAllConstructors(battery, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (hooks.active && p.thisObject instanceof View view) hooks.observe(view, null);
                    }
                });
            }
            for (String name : new String[]{"com.android.systemui.statusbar.phone.PhoneStatusBarView",
                    "com.android.systemui.statusbar.phone.KeyguardStatusBarView",
                    "com.oplus.systemui.statusbar.phone.OplusPhoneStatusBarView"}) {
                Class<?> root = XposedHelpers.findClassIfExists(name, lp.classLoader);
                if (root != null) XposedBridge.hookAllMethods(root, "onFinishInflate", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (hooks.active && p.thisObject instanceof View view) hooks.discover(view);
                    }
                });
            }
            instance = hooks;
            log("HOOK OK ColorOS status bar charging information");
        } catch (Throwable error) {
            hooks.close();
            log("HOOK FAIL status bar charging information: " + error);
        }
    }

    private static boolean batteryView(View view) {
        String type = view.getClass().getSimpleName();
        String id = resourceName(view);
        return !(view instanceof TextView) && (type.contains("Battery") || id.contains("battery"));
    }

    private static String resourceName(View view) {
        if (view.getId() == View.NO_ID) return "";
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (android.content.res.Resources.NotFoundException ignored) { return ""; }
    }

    private static boolean statusBar(View battery) {
        boolean status = false;
        for (View view = battery; view != null;
                view = view.getParent() instanceof View parent ? parent : null) {
            String type = view.getClass().getName(), id = resourceName(view);
            if (type.contains(".qs.") || type.contains("QuickStatusBarHeader") || "qs_header".equals(id)) return false;
            if (type.contains("PhoneStatusBarView") || type.contains("KeyguardStatusBarView")
                    || type.contains("CollapsedStatusBar") || "status_bar".equals(id)
                    || "keyguard_header".equals(id) || "keyguard_status_bar".equals(id)
                    || "system_icon_area".equals(id) || "status_bar_system_icon_area".equals(id)) status = true;
        }
        return status;
    }

    private void observePercent(TextView percent) {
        if (!active) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> observePercent(percent)); return; }
        if (!percentListeners.containsKey(percent)) {
            View.OnAttachStateChangeListener listener = new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { observePercent((TextView)view); }
                @Override public void onViewDetachedFromWindow(View view) {}
            };
            percentListeners.put(percent, listener);
            percent.addOnAttachStateChangeListener(listener);
            // Native binding can run before inflation has added the percentage to its parent.
            main.post(() -> observePercent(percent));
        }
        for (View view = percent.getParent() instanceof View parent ? parent : null; view != null;
                view = view.getParent() instanceof View parent ? parent : null) {
            if (batteryView(view)) { observe(view, percent); return; }
        }
        // Some ColorOS layouts place the outer percentage next to the battery widget.
        for (View parent = percent.getParent() instanceof View view ? view : null; parent != null;
                parent = parent.getParent() instanceof View view ? view : null) {
            if (parent instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
                View sibling = group.getChildAt(i);
                if (batteryView(sibling)) { observe(sibling, percent); return; }
            }
            if (statusBar(parent) && parent.getParent() == null) break;
        }
        if (percent.getParent() instanceof View parent && statusBar(parent)) observe(parent, percent);
    }

    private State state(View battery) {
        WeakReference<State> reference = states.get(battery);
        return reference == null ? null : reference.get();
    }

    private record Placement(LinearLayout parent, View anchor) {}
    private static Placement placement(View battery) {
        for (View anchor = battery; anchor != null;
                anchor = anchor.getParent() instanceof View parent ? parent : null) {
            if (anchor.getParent() instanceof LinearLayout parent
                    && parent.getOrientation() == LinearLayout.HORIZONTAL) return new Placement(parent, anchor);
            String type = anchor.getClass().getSimpleName(), id = resourceName(anchor);
            if (type.contains("PhoneStatusBarView") || type.contains("KeyguardStatusBarView")
                    || "status_bar".equals(id) || "keyguard_header".equals(id)) break;
        }
        if (battery instanceof LinearLayout parent && parent.getOrientation() == LinearLayout.HORIZONTAL) {
            for (int i = 0; i < parent.getChildCount(); i++)
                if (batteryView(parent.getChildAt(i))) return new Placement(parent, parent.getChildAt(i));
        }
        return null;
    }

    private void observe(View battery, TextView reference) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> observe(battery, reference)); return; }
        if (!active) return;
        State state = state(battery);
        Placement candidate = placement(battery);
        if (state == null && candidate != null) for (State existing : currentStates()) {
            View owner = existing.owner.get();
            Placement other = owner == null ? null : placement(owner);
            if (other != null && other.anchor() == candidate.anchor()) { state = existing; break; }
        }
        if (state == null) {
            state = new State(battery);
            states.put(battery, new WeakReference<>(state));
            battery.addOnAttachStateChangeListener(state);
            if (battery.isAttachedToWindow()) state.onViewAttachedToWindow(battery);
            if (ChargingInfo.enabled(mode())) log("charging information host: " + battery.getClass().getName()
                    + ", id=" + resourceName(battery));
        }
        if (reference != null) state.reference = new WeakReference<>(reference);
        state.update(); receivers(); reconcile();
    }

    public static void refresh() {
        ChargingInfoHooks hooks = instance;
        if (hooks != null) hooks.main.post(() -> {
            if (!hooks.active) return;
            if (ChargingInfo.enabled(mode())) {
                restoreInRoots(StatusBarFontHooks.captureHotReloadHosts());
                restoreInRoots(StatusBarExtrasHooks.captureHotReloadHosts());
            }
            hooks.updateAll();
        });
    }

    private void discover(View root) {
        if (!active) return;
        List<View> views = new ArrayList<>();
        collect(root, views);
        for (View view : views) {
            if (view instanceof TextView text && (resourceName(view).contains("battery")
                    || view.getClass().getSimpleName().contains("Battery"))) observePercent(text);
            else if (batteryView(view)) observe(view, null);
        }
    }

    private static void collect(View view, List<View> views) {
        views.add(view);
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), views);
    }

    public static void restoreInRoots(Object saved) {
        if (instance == null) return;
        Set<View> roots = Collections.newSetFromMap(new IdentityHashMap<>());
        collectRoots(saved, roots);
        for (View root : roots) instance.discover(root);
    }

    private static void collectRoots(Object saved, Set<View> roots) {
        if (saved instanceof View view) roots.add(view.getRootView());
        else if (saved instanceof Object[] array) for (Object item : array) collectRoots(item, roots);
        else if (saved instanceof List<?> list) for (Object item : list) collectRoots(item, roots);
    }

    private List<State> currentStates() {
        List<State> result = new ArrayList<>();
        for (WeakReference<State> reference : new ArrayList<>(states.values())) {
            State state = reference.get(); if (state != null) result.add(state);
        }
        return result;
    }

    private void updateAll() {
        if (!active) return;
        receivers();
        for (State state : currentStates()) state.update();
        reconcile();
    }

    private void batteryChanged(Intent intent) {
        // NOT_CHARGING (including bypass charging) must not keep a stale input label alive.
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                && (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
    }

    private void receivers() {
        Context context = null;
        if (ChargingInfo.enabled(mode())) {
            for (State state : currentStates()) {
                View battery = state.owner.get();
                if (state.attached && battery != null && statusBar(battery)) {
                    context = battery.getContext().getApplicationContext(); break;
                }
            }
        }
        if (context != null && receiverContext == null) {
            PowerManager power = context.getSystemService(PowerManager.class);
            screenOn = power == null || power.isInteractive();
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON);
            receiverContext = context;
            Intent sticky = context.registerReceiver(events, filter, null, main, Context.RECEIVER_NOT_EXPORTED);
            if (sticky != null) batteryChanged(sticky);
        } else if (context == null && receiverContext != null) {
            receiverContext.unregisterReceiver(events); receiverContext = null; charging = false;
        }
    }

    private Context activeContext() {
        for (State state : currentStates()) {
            View battery = state.owner.get();
            if (battery != null && state.showing()) return battery.getContext().getApplicationContext();
        }
        return null;
    }

    private void reconcile() {
        boolean needed = active && activeContext() != null;
        if (needed == polling) return;
        polling = needed; generation++; sample = null; protocol = ""; sampledAt = 0;
        main.removeCallbacks(tick);
        if (polling && !inFlight) main.post(tick);
        for (State state : currentStates()) state.render();
    }

    private void read() {
        Context context = activeContext();
        if (!active || !polling || context == null || inFlight) { reconcile(); return; }
        int token = generation; inFlight = true;
        worker.execute(() -> {
            ChargingInfo.Sample result = null;
            String protocolResult = "";
            String status = "provider_unavailable";
            try {
                Bundle data = context.getContentResolver().call(SettingsProvider.NOTIFY_URI, ChargingInfo.METHOD, null, null);
                if (data != null) {
                    result = ChargingInfo.parseMicro(data.getString("sample_micro"));
                    protocolResult = data.getString("protocol", "");
                    status = data.getString("status", result == null ? "no_valid_input" : "valid_input");
                }
            } catch (RuntimeException error) { status = "provider_error_"+error.getClass().getSimpleName(); }
            ChargingInfo.Sample value = result;
            String protocolValue = protocolResult;
            String readStatus = status;
            main.post(() -> {
                inFlight = false;
                if (!active) return;
                if (token == generation && polling) {
                    if (!readStatus.equals(lastReadStatus)) {
                        lastReadStatus = readStatus; log("charging information input: " + readStatus);
                    }
                    sample = value; protocol = protocolValue; sampledAt = SystemClock.elapsedRealtime(); updateAll();
                }
                if (polling) { main.removeCallbacks(tick); main.postDelayed(tick, token == generation ? 5000 : 0); }
            });
        });
    }

    private static TextView percentView(View battery) {
        for (String name : new String[]{"battery_percentage_view", "battery_text", "battery_percentage"}) {
            int id = battery.getResources().getIdentifier(name, "id", "com.android.systemui");
            if (id != 0 && battery.findViewById(id) instanceof TextView text) return text;
        }
        if (battery instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView text) return text;
            TextView nested = percentView(child); if (nested != null) return nested;
        }
        return null;
    }

    private final class State implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        final WeakReference<View> owner;
        WeakReference<TextView> reference = new WeakReference<>(null);
        TextView label;
        ViewTreeObserver observer;
        boolean attached;
        String lastGate;

        State(View battery) { owner = new WeakReference<>(battery); }

        boolean showing() {
            int currentMode = mode();
            if (!ChargingInfo.enabled(currentMode)) return false;
            View battery = owner.get();
            return active && screenOn && reference.get() != null && battery != null
                    && placement(battery) != null && battery.getAlpha() > 0f
                    && battery.getWindowVisibility() == View.VISIBLE && statusBar(battery)
                    && ChargingInfo.active(currentMode, attached && battery.isAttachedToWindow(), battery.isShown(), charging, false);
        }

        @Override public void onViewAttachedToWindow(View battery) {
            if (!active || attached) return;
            attached = true; observer = battery.getViewTreeObserver(); observer.addOnPreDrawListener(this);
            updateAll();
        }
        @Override public void onViewDetachedFromWindow(View battery) {
            attached = false;
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null; removeLabel();
            if (active) updateAll();
        }
        @Override public boolean onPreDraw() { if (active) { update(); reconcile(); } return true; }

        void update() {
            View battery = owner.get(); if (battery == null) return;
            if (!ChargingInfo.enabled(mode())) { removeLabel(); return; }
            TextView nativeText = reference.get();
            if (nativeText == null) { nativeText = percentView(battery); reference = new WeakReference<>(nativeText); }
            if (!showing() || nativeText == null) {
                String gate = !statusBar(battery) ? "outside_status_bar" : !attached ? "detached"
                        : nativeText == null ? "waiting_for_percent_view" : placement(battery) == null ? "no_horizontal_parent"
                        : !screenOn ? "screen_off" : !charging ? "not_charging" : "native_battery_hidden";
                trace(gate); removeLabel(); return;
            }
            Placement placement = placement(battery);
            if (placement == null) return;
            LinearLayout parent = placement.parent();
            trace("active");
            if (label == null) {
                label = new TextView(battery.getContext());
                label.setTag("coloros_mod_charging_info"); label.setGravity(Gravity.CENTER);
                label.setSingleLine(true); label.setVisibility(View.GONE);
                label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                label.setPadding(0, 0, 0, 0);
            }
            // Insert on the battery's physical left in both LTR and RTL layouts.
            int index = ChargingInfo.labelIndex(parent.indexOfChild(placement.anchor()), parent.indexOfChild(label),
                    parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
            if (label.getParent() != parent || parent.indexOfChild(label) != index) {
                removeFromParent();
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
                params.gravity = Gravity.CENTER_VERTICAL;
                int gap = Math.round(2f*battery.getResources().getDisplayMetrics().density);
                params.setMarginStart(gap); params.setMarginEnd(gap);
                parent.addView(label, index, params);
            }
            if (label.getCurrentTextColor() != nativeText.getCurrentTextColor()) label.setTextColor(nativeText.getCurrentTextColor());
            if (label.getTextSize() != nativeText.getTextSize())
                label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, nativeText.getTextSize());
            if (label.getTypeface() != nativeText.getTypeface()) label.setTypeface(nativeText.getTypeface());
            label.setIncludeFontPadding(nativeText.getIncludeFontPadding());
            label.setAlpha(battery.getAlpha());
            render();
        }

        void render() {
            if (label == null) return;
            String text = showing() && SystemClock.elapsedRealtime()-sampledAt <= 7000
                    ? ChargingInfo.format(sample, protocol, mode()) : "";
            if (!text.contentEquals(label.getText())) label.setText(text);
            label.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }
        void removeFromParent() { if (label != null && label.getParent() instanceof ViewGroup parent) parent.removeView(label); }
        void removeLabel() { removeFromParent(); label = null; }
        void trace(String gate) {
            if (!gate.equals(lastGate)) {
                lastGate = gate;
                log("charging information display: " + gate + ", host=" + owner.get().getClass().getSimpleName());
            }
        }
        void dispose() {
            View battery = owner.get();
            if (battery != null) battery.removeOnAttachStateChangeListener(this);
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            attached = false; observer = null; removeLabel();
        }
    }

    public static Object captureHosts() {
        if (instance == null) return null;
        List<Object> hosts = new ArrayList<>();
        for (State state : instance.currentStates()) {
            View battery = state.owner.get();
            if (battery != null) hosts.add(new Object[]{battery, state.reference.get()});
        }
        return hosts;
    }

    public static void restoreHosts(Object saved) {
        if (instance == null || !(saved instanceof List<?> hosts)) return;
        for (Object item : hosts) if (item instanceof Object[] pair && pair.length == 2 && pair[0] instanceof View battery)
            instance.observe(battery, pair[1] instanceof TextView text ? text : null);
    }

    public static void cleanupForHotReload() {
        if (instance != null) { instance.close(); instance = null; }
    }

    private void close() {
        active = false; generation++; polling = false; main.removeCallbacksAndMessages(null);
        for (State state : currentStates()) state.dispose();
        states.clear();
        for (Map.Entry<TextView, View.OnAttachStateChangeListener> entry : new ArrayList<>(percentListeners.entrySet()))
            entry.getKey().removeOnAttachStateChangeListener(entry.getValue());
        percentListeners.clear();
        if (receiverContext != null) { receiverContext.unregisterReceiver(events); receiverContext = null; }
        worker.shutdownNow(); sample = null; protocol = "";
    }
}
