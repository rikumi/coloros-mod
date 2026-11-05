package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keep native touch replay consistent with mBack and the independent gesture-band blocker. */
final class GestureTouchReplayHooks {
    private static volatile boolean active;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadLocal<Boolean> REPLAYING = new ThreadLocal<>();
    private static final Map<String, Claim> CONSUMED = new LinkedHashMap<>();
    private static final Map<String, Boolean> WINDOW_MBACK = new LinkedHashMap<>();
    private static final Map<String, Boolean> MONITOR_MBACK = new LinkedHashMap<>();
    private static final Map<String, Boolean> CAPTURED_INPUT = new LinkedHashMap<>();
    private static final Map<String, Long> MBACK_PHYSICAL_UP = new ConcurrentHashMap<>();
    private static final Set<String> MBACK_PHYSICAL_CANCEL = ConcurrentHashMap.newKeySet();
    private static final Map<String, List<DeferredMotion>> PENDING = new LinkedHashMap<>();
    private static final class DeferredMotion {
        final Object injector;
        final MotionEvent event;
        DeferredMotion(Object injector, MotionEvent event) { this.injector = injector; this.event = event; }
    }

    private static final class Claim {
        final boolean block;
        final boolean navigation;
        Claim(boolean block, boolean navigation) { this.block = block; this.navigation = navigation; }
    }

    static void hook(ClassLoader loader) {
        if (Build.VERSION.SDK_INT < 37) return;
        active = true;
        // Tablet/taskbar input has its own working admission and navigation path.
        if (!com.rikumi.colorosmod.TabletDevice.isTablet()) hookGestureInput(loader);
        try {
            Class<?> injector = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.OplusInputInjector", loader);
            XposedHelpers.findAndHookMethod(injector, "injectAllEvent",
                    MotionEvent.class, MotionEvent.class, List.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!active || Boolean.TRUE.equals(REPLAYING.get())) return;
                    MotionEvent down = (MotionEvent) param.args[0];
                    MotionEvent up = (MotionEvent) param.args[1];
                    MotionEvent reference = down != null ? down : up;
                    if (reference == null) return;
                    if (!readBool(KEY_MBACK_ENABLED, false)
                            && !readBool(KEY_GESTURE_TOUCH_THROUGH_ENABLED, false)
                            && !wasConsumed(displayId(reference), reference.getDownTime())
                            && !isPending(displayId(reference), reference.getDownTime())) return;
                    Object target = param.thisObject;
                    // SideGestureDetector queues handleValidTouchEvent on this same main
                    // looper before asking the injector to replay the unclaimed touch.
                    // Copy first: EventInterceptInjectHelper resets/recycles its cache
                    // immediately after injectAllEvent returns on the gesture thread.
                    MotionEvent downCopy = down == null ? null : MotionEvent.obtain(down);
                    MotionEvent upCopy = up == null ? null : MotionEvent.obtain(up);
                    MotionEvent referenceCopy = downCopy != null ? downCopy : upCopy;
                    List<MotionEvent> pointers = new ArrayList<>();
                    for (Object pointer : (List<?>) param.args[2]) {
                        pointers.add(MotionEvent.obtain((MotionEvent) pointer));
                    }
                    List<MotionEvent> ownedPointers = new ArrayList<>(pointers);
                    Runnable release = () -> {
                        if (downCopy != null) downCopy.recycle();
                        if (upCopy != null) upCopy.recycle();
                        for (MotionEvent pointer : ownedPointers) pointer.recycle();
                    };
                    if (MAIN.post(() -> {
                        boolean deferred = false;
                        try {
                            if (!active) return;
                            if (wasConsumed(displayId(referenceCopy), referenceCopy.getDownTime())) return;
                            deferred = deferBatch(target, downCopy, upCopy, pointers);
                            if (deferred) return;
                            REPLAYING.set(true);
                            XposedHelpers.callMethod(target, "injectAllEvent", downCopy, upCopy, pointers);
                        } catch (Throwable error) {
                            log("mback native touch replay failed: " + error);
                        } finally {
                            REPLAYING.remove();
                            if (!deferred) release.run();
                        }
                    })) {
                        param.setResult(null);
                    } else {
                        release.run();
                    }
                }
            });
            // The native VALID path can replay individual events without a batch.
            // Its DOWN must be filtered too, before an application can gain focus.
            XposedHelpers.findAndHookMethod(injector, "injectMotionEvent", MotionEvent.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!active || Boolean.TRUE.equals(REPLAYING.get())) return;
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (event == null || (!readBool(KEY_MBACK_ENABLED, false)
                            && !readBool(KEY_GESTURE_TOUCH_THROUGH_ENABLED, false)
                            && !wasConsumed(displayId(event), event.getDownTime())
                            && !isPending(displayId(event), event.getDownTime()))) return;
                    MotionEvent copy = MotionEvent.obtain(event);
                    Object target = param.thisObject;
                    if (MAIN.post(() -> {
                        boolean deferred = false;
                        try {
                            if (!active) return;
                            if (wasConsumed(displayId(copy), copy.getDownTime())) return;
                            deferred = defer(target, copy);
                            if (!deferred) replaySingle(target, copy);
                        } finally {
                            if (!deferred) copy.recycle();
                        }
                    })) param.setResult(null);
                    else copy.recycle();
                }
            });
        } catch (Throwable error) { log("gesture native touch replay hook unavailable: " + error); }
    }

    private static void hookGestureInput(ClassLoader loader) {
        try {
            Class<?> detector = XposedHelpers.findClass(
                    "com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector", loader);
            // ROM variants expose (MotionEvent) or (MotionEvent, Map). Missing
            // the raw DOWN leaves only native replay delivered on release.
            Set<XC_MethodHook.Unhook> inputHooks = XposedBridge.hookAllMethods(detector, "onMotionEventImpl",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof MotionEvent)) return;
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (event == null || (event.getEdgeFlags() & 0x40000000) != 0) return;
                    int action = event.getActionMasked();
                    int eventDisplay = displayId(event);
                    String key = eventDisplay + ":" + event.getDownTime();
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        if (!readBool(KEY_MBACK_ENABLED, false)
                                && !readBool(KEY_GESTURE_TOUCH_THROUGH_ENABLED, false)) return;
                        android.graphics.Point size = (android.graphics.Point) XposedHelpers.getObjectField(
                                param.thisObject, "mDisplaySize");
                        Object config = XposedHelpers.getObjectField(param.thisObject, "mSideGestureConfiguration");
                        int bottom = ((Number) XposedHelpers.callMethod(config, "getBottomGestureAreaHeight")).intValue();
                        if (event.getRawY() < size.y - bottom) return;
                        synchronized (CAPTURED_INPUT) {
                            CAPTURED_INPUT.put(key, true);
                            trim(CAPTURED_INPUT);
                        }
                    } else {
                        synchronized (CAPTURED_INPUT) {
                            if (!CAPTURED_INPUT.containsKey(key)) return;
                        }
                    }
                    Object animation = XposedHelpers.getObjectField(param.thisObject,
                            "mSideGestureHomeHandleAnimation");
                    Object home = animation == null ? null : XposedHelpers.getObjectField(animation, "mHome");
                    if (!(home instanceof View)) return;
                    View handle = (View) home;
                    if (readBool(KEY_MBACK_ENABLED, false)) noteMBackPhysicalTerminal(event);
                    MotionEvent copy = MotionEvent.obtain(event);
                    // Own a copy before the detector queues its animation callback or
                    // mutates/recycles the event. Raw input is authoritative even when
                    // the protected window receives CANCEL or animation is disabled.
                    if (!MAIN.post(() -> {
                        try {
                            if (!active) return;
                            int display = displayId(copy);
                            if (handle.getDisplay() != null
                                    && handle.getDisplay().getDisplayId() == display) {
                                if (copy.getActionMasked() == MotionEvent.ACTION_DOWN
                                        && GestureHooks.shouldBlockGestureReplay(handle, copy)) {
                                    remember(display, copy.getDownTime(), true);
                                }
                                GestureHooks.routeMBackMonitorTouch(handle, copy);
                            }
                        } catch (Throwable error) {
                            log("gesture replay admission failed: " + error);
                        } finally { copy.recycle(); }
                    })) copy.recycle();
                }
            });
            if (inputHooks.isEmpty()) log("gesture native replay admission unavailable: no onMotionEventImpl overload");
        } catch (Throwable error) { log("gesture native replay admission hook unavailable: " + error); }
    }

    static void consume(View handle, long downTime) {
        int display = handle.getDisplay() != null ? handle.getDisplay().getDisplayId() : 0;
        remember(display, downTime, false);
        resolvePending(display + ":" + downTime, true);
    }

    static void begin(View handle, long downTime) {
        synchronized (PENDING) {
            PENDING.putIfAbsent(streamKey(handle, downTime), new ArrayList<>());
            while (PENDING.size() > 32) {
                List<DeferredMotion> stale = PENDING.remove(PENDING.keySet().iterator().next());
                for (DeferredMotion motion : stale) motion.event.recycle();
            }
        }
    }

    static void release(View handle, long downTime) { resolvePending(streamKey(handle, downTime), false); }

    private static boolean isPending(int display, long downTime) {
        synchronized (PENDING) { return PENDING.containsKey(display + ":" + downTime); }
    }

    private static boolean deferBatch(Object injector, MotionEvent down, MotionEvent up, List<MotionEvent> pointers) {
        MotionEvent reference = down != null ? down : up;
        synchronized (PENDING) {
            if (!isPending(displayId(reference), reference.getDownTime())) return false;
            if (down != null) defer(injector, down);
            for (MotionEvent pointer : pointers) defer(injector, pointer);
            if (up != null) defer(injector, up);
            return true;
        }
    }

    private static boolean defer(Object injector, MotionEvent event) {
        synchronized (PENDING) {
            List<DeferredMotion> pending = PENDING.get(displayId(event) + ":" + event.getDownTime());
            if (pending == null) return false;
            // Preserve DOWN and recent positions without retaining an unbounded MOVE history.
            if (pending.size() >= 128) pending.remove(1).event.recycle();
            pending.add(new DeferredMotion(injector, event));
            return true;
        }
    }

    private static void resolvePending(String key, boolean consumed) {
        List<DeferredMotion> pending;
        synchronized (PENDING) { pending = PENDING.remove(key); }
        if (pending == null) return;
        for (DeferredMotion motion : pending) {
            try {
                if (!consumed && !wasConsumed(displayId(motion.event), motion.event.getDownTime()))
                    replaySingle(motion.injector, motion.event);
            } finally { motion.event.recycle(); }
        }
    }

    private static void replaySingle(Object injector, MotionEvent event) {
        boolean previous = Boolean.TRUE.equals(REPLAYING.get());
        REPLAYING.set(true);
        try { XposedHelpers.callMethod(injector, "injectMotionEvent", event); }
        catch (Throwable error) { log("gesture native event replay failed: " + error); }
        finally { if (previous) REPLAYING.set(true); else REPLAYING.remove(); }
    }

    static void cleanup() {
        active = false;
        synchronized (PENDING) {
            for (List<DeferredMotion> pending : PENDING.values())
                for (DeferredMotion motion : pending) motion.event.recycle();
            PENDING.clear();
        }
        synchronized (CONSUMED) { CONSUMED.clear(); }
        synchronized (WINDOW_MBACK) { WINDOW_MBACK.clear(); }
        synchronized (MONITOR_MBACK) { MONITOR_MBACK.clear(); }
        synchronized (CAPTURED_INPUT) { CAPTURED_INPUT.clear(); }
        MBACK_PHYSICAL_UP.clear();
        MBACK_PHYSICAL_CANCEL.clear();
    }

    private static void remember(int display, long downTime, boolean block) {
        synchronized (CONSUMED) {
            String key = display + ":" + downTime;
            Claim previous = CONSUMED.get(key);
            // downTime identifies a stream for this process lifetime. Bound the
            // cache by count, not duration: a stationary long hold must remain blocked.
            CONSUMED.put(key, new Claim(block || (previous != null && previous.block),
                    !block || (previous != null && previous.navigation)));
            while (CONSUMED.size() > 32) CONSUMED.remove(CONSUMED.keySet().iterator().next());
        }
    }

    private static String streamKey(View handle, long downTime) {
        int display = handle.getDisplay() != null ? handle.getDisplay().getDisplayId() : 0;
        return display + ":" + downTime;
    }

    private static void noteMBackPhysicalTerminal(MotionEvent event) {
        int action = event.getActionMasked();
        String key = displayId(event) + ":" + event.getDownTime();
        if (action == MotionEvent.ACTION_UP) {
            MBACK_PHYSICAL_UP.put(key, event.getEventTime());
        } else if (action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            MBACK_PHYSICAL_CANCEL.add(key);
        } else {
            return;
        }
        while (MBACK_PHYSICAL_UP.size() + MBACK_PHYSICAL_CANCEL.size() > 32) {
            if (!MBACK_PHYSICAL_UP.isEmpty()) {
                MBACK_PHYSICAL_UP.remove(MBACK_PHYSICAL_UP.keySet().iterator().next());
            } else {
                MBACK_PHYSICAL_CANCEL.remove(MBACK_PHYSICAL_CANCEL.iterator().next());
            }
        }
    }

    static boolean isMBackLongPressDue(View handle, long downTime, long now, long timeout) {
        String key = streamKey(handle, downTime);
        if (MBACK_PHYSICAL_CANCEL.contains(key)) return false;
        Long upTime = MBACK_PHYSICAL_UP.get(key);
        return upTime != null ? upTime - downTime >= timeout : now - downTime >= timeout;
    }

    static void clearMBackPhysicalTerminal(View handle, long downTime) {
        String key = streamKey(handle, downTime);
        MBACK_PHYSICAL_UP.remove(key);
        MBACK_PHYSICAL_CANCEL.remove(key);
    }

    static void abandonMBack(View handle, long downTime) {
        String key = streamKey(handle, downTime);
        synchronized (WINDOW_MBACK) { WINDOW_MBACK.remove(key); }
        synchronized (MONITOR_MBACK) { MONITOR_MBACK.remove(key); }
        clearMBackPhysicalTerminal(handle, downTime);
        release(handle, downTime);
    }

    static void claimWindowMBack(View handle, long downTime) {
        synchronized (WINDOW_MBACK) {
            WINDOW_MBACK.put(streamKey(handle, downTime), true);
            while (WINDOW_MBACK.size() > 32) WINDOW_MBACK.remove(WINDOW_MBACK.keySet().iterator().next());
        }
    }

    static boolean ownsWindowMBack(View handle, long downTime) {
        synchronized (WINDOW_MBACK) {
            return WINDOW_MBACK.containsKey(streamKey(handle, downTime));
        }
    }

    private static void trim(Map<String, Boolean> streams) {
        while (streams.size() > 32) streams.remove(streams.keySet().iterator().next());
    }

    static void claimMonitorMBack(View handle, long downTime) {
        synchronized (MONITOR_MBACK) {
            MONITOR_MBACK.put(streamKey(handle, downTime), true);
            trim(MONITOR_MBACK);
        }
    }

    static boolean ownsMonitorMBack(View handle, long downTime) {
        synchronized (MONITOR_MBACK) {
            return MONITOR_MBACK.containsKey(streamKey(handle, downTime));
        }
    }

    static boolean wasNavigationConsumed(View handle, long downTime) {
        synchronized (CONSUMED) {
            Claim claim = CONSUMED.get(streamKey(handle, downTime));
            return claim != null && claim.navigation;
        }
    }

    static boolean wasConsumed(int display, long downTime) {
        synchronized (CONSUMED) {
            Claim claim = CONSUMED.get(display + ":" + downTime);
            return claim != null;
        }
    }

    private static boolean wasBlocked(int display, long downTime) {
        synchronized (CONSUMED) {
            Claim claim = CONSUMED.get(display + ":" + downTime);
            return claim != null && claim.block;
        }
    }

    private static int displayId(MotionEvent event) {
        return ((Number) XposedHelpers.callMethod(event, "getDisplayId")).intValue();
    }
}
