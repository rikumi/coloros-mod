package com.rikumi.colorosmod.hooks;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import com.rikumi.colorosmod.AppNavigationSettings;
import com.rikumi.colorosmod.XposedInit;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import io.github.libxposed.api.XposedInterface;
import java.util.ArrayList;
import java.util.Set;
import java.util.Collections;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.view.SurfaceControl;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.ObjIntConsumer;

public final class AppNavigationHooks {
    interface Installer {
        void hook(String name, String method, XposedInterface.Hooker hooker, Class<?>... parameters);
    }
    private static volatile AppNavigationHooks instance;
    private final java.util.List<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private volatile boolean paused, closed;
    private final Set<NavigationCaptureCallback> captures = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private HandlerThread workerThread, captureThread;
    private Set<String> packages = Collections.emptySet();
    private boolean lastEnabled;

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        if (instance != null) return;
        AppNavigationHooks owner = new AppNavigationHooks();
        try {
            owner.install(lp.classLoader, (name, method, hooker, parameters) -> {
                Class<?> type = XposedHelpers.findClass(name, lp.classLoader);
                owner.hooks.add(XposedHelpers.hookChainMethod(
                        XposedHelpers.findMethodDeclared(type, method, parameters), hooker));
            });
            instance = owner;
            owner.settingsChanged();
        } catch (Throwable error) {
            for (XposedInterface.HookHandle hook : owner.hooks) hook.unhook();
            owner.cleanup();
            XposedInit.log("application navigation hooks failed: " + error);
        }
    }

    public static void onSettingsChanged() {
        AppNavigationHooks owner = instance;
        if (owner != null && !owner.closed && !owner.paused) owner.main.post(owner::settingsChanged);
    }

    private Set<String> selectedApplications() {
        return XposedInit.navigationApplications();
    }

    private void settingsChanged() {
        if (closed || paused) return;
        Set<String> selected = selectedApplications();
        boolean enabled = enabled();
        if (lastEnabled == enabled && packages.equals(selected)) return;
        lastEnabled = enabled;
        packages = selected;
        for (State state : states.values()) { state.reset(); state.tick(); }
    }

    public static Object captureHosts() {
        AppNavigationHooks owner = instance;
        return owner == null ? null : new Object[]{owner.taskSurfaces.host(), owner.states.keySet().toArray(new View[0])};
    }

    public static void restoreHosts(Object saved) {
        AppNavigationHooks owner = instance;
        if (owner == null || !(saved instanceof Object[])) return;
        Object[] hosts = (Object[]) saved;
        owner.taskSurfaces.restoreHost(hosts[0]);
        owner.main.post(() -> {
            if (owner.closed) return;
            for (View view : (View[]) hosts[1]) if (view.isAttachedToWindow() && !owner.states.containsKey(view)) {
                State state = owner.new State(view);
                owner.states.put(view, state);
                state.tick();
            }
        });
    }

    public static boolean prepareHotReload() {
        AppNavigationHooks owner = instance;
        if (owner == null) return true;
        synchronized (owner.captures) {
            owner.paused = true;
            for (NavigationCaptureCallback capture : owner.captures) capture.cancel();
            owner.captures.clear();
        }
        // Drain queued work before committing any destructive cleanup.
        java.util.concurrent.CountDownLatch idle = new java.util.concurrent.CountDownLatch(2);
        owner.worker.post(idle::countDown); owner.captureWorker.post(idle::countDown);
        try {
            if (!idle.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) return false;
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
        return true;
    }

    public static void cancelHotReload() {
        AppNavigationHooks owner = instance;
        if (owner == null) return;
        owner.paused = false;
        owner.main.post(() -> { for (State state : owner.states.values()) { state.reset(); state.tick(); } });
    }

    public static void cleanupForHotReload() {
        AppNavigationHooks owner = instance;
        if (owner != null) owner.cleanup();
        instance = null;
    }

    private void cleanup() {
        closed = true;
        synchronized (captures) {
            for (NavigationCaptureCallback capture : captures) capture.cancel();
            captures.clear();
        }
        main.removeCallbacksAndMessages(null);
        for (State state : states.values()) state.close();
        states.clear();
        if (taskSurfaces != null) taskSurfaces.releaseTargets();
        if (workerThread != null) workerThread.quitSafely();
        if (captureThread != null) captureThread.quitSafely();
        try {
            if (workerThread != null && Thread.currentThread() != workerThread) workerThread.join(1000);
            if (captureThread != null && Thread.currentThread() != captureThread) captureThread.join(1000);
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }

    private void startWorkers() {
        workerThread = new HandlerThread("ColorOSMod-NavColor", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start(); worker = new Handler(workerThread.getLooper());
        captureThread = new HandlerThread("ColorOSMod-NavCapture", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        captureThread.start(); captureWorker = new Handler(captureThread.getLooper());
    }

    private static java.util.function.Consumer<Object> discardCaptureBuffer(Method hardwareBuffer) {
        // Capture only the framework Method, never an AppNavigationHooks/State instance.
        return buffer -> {
            if (buffer == null) return;
            try {
                HardwareBuffer allocation = (HardwareBuffer) hardwareBuffer.invoke(buffer);
                if (allocation != null) allocation.close();
            } catch (ReflectiveOperationException | RuntimeException error) {
                XposedInit.log("navigation stale capture disposal failed: " + error);
            }
        };
    }

    private void reapply(Object transitions) throws ReflectiveOperationException {
        Object light = transitions.getClass().getMethod("getLightTransitionsController").invoke(transitions);
        float intensity = (Float) light.getClass().getMethod("getCurrentDarkIntensity").invoke(light);
        applyIntensity.invoke(transitions, intensity);
    }

    private static Rect navigationFrame(View view) throws ReflectiveOperationException {
        View root = view.getRootView();
        Object params = root.getLayoutParams();
        if (!(params instanceof android.view.WindowManager.LayoutParams)) return new Rect();
        Object[] providers = (Object[]) params.getClass().getField("providedInsets").get(params);
        if (providers == null) return new Rect();
        for (Object provider : providers) {
            int type = (Integer) provider.getClass().getMethod("getType").invoke(provider);
            if (type != android.view.WindowInsets.Type.navigationBars()) continue;
            android.graphics.Insets size = (android.graphics.Insets) provider.getClass().getMethod("getInsetsSize").invoke(provider);
            if (size == null || size.bottom <= 0 || size.bottom > root.getHeight()) return new Rect();
            int[] position = new int[2]; root.getLocationOnScreen(position);
            return new Rect(position[0], position[1] + root.getHeight() - size.bottom,
                    position[0] + root.getWidth(), position[1] + root.getHeight());
        }
        return new Rect();
    }

    private static final String NAV = "com.android.systemui.navigationbar.views.NavigationBarView";
    private static final String TRANSITIONS = "com.android.systemui.navigationbar.views.NavigationBarTransitions";
    private static final String SHADE_WINDOW = "com.android.systemui.shade.NotificationShadeWindowControllerImpl";
    private static final long SAMPLE_INTERVAL_MS = 50;
    private static final long RECENTS_FOCUS_SETTLE_MS = 500;
    private final BiConsumer<String, Throwable> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler worker, captureWorker;
    private final Map<View, State> states = new java.util.concurrent.ConcurrentHashMap<>();
    private Constructor<?> argsBuilder, listenerConstructor;
    private Method setCrop, setExcludeLayers, build, captureLayers, getHardwareBuffer, getColorSpace, applyIntensity;
    private AppNavigationTaskSurface taskSurfaces;
    private ClassLoader classLoader;
    private final ColorOsNavigationConfig policies = new ColorOsNavigationConfig();
    private boolean shadeVisible;
    private volatile boolean recentsRunning, recentsCached;
    private AppNavigationHooks() {
        log = (message, error) -> XposedInit.log(message + (error == null ? "" : ": " + error));
    }

    void install(ClassLoader loader, AppNavigationHooks.Installer installer) throws ReflectiveOperationException {
        classLoader = loader;
        Class<?> nav = loader.loadClass(NAV);
        nav.getDeclaredMethod("onAttachedToWindow");
        nav.getDeclaredMethod("onDetachedFromWindow");
        nav.getField("mBarTransitions");
        nav.getField("mIsVertical");
        Class<?> transitions = loader.loadClass(TRANSITIONS);
        transitions.getField("mView");
        applyIntensity = transitions.getMethod("applyDarkIntensity", float.class);
        Class<?> shadeState = loader.loadClass("com.android.systemui.shade.NotificationShadeWindowState");
        loader.loadClass(SHADE_WINDOW).getDeclaredMethod("apply", shadeState);
        Field panelVisible = shadeState.getField("panelVisible");
        Field centerVisible = shadeState.getField("qsPanelVisible");
        Field expanded = shadeState.getField("shadeOrQsExpanded");
        Field blurRadius = shadeState.getField("backgroundBlurRadius");
        Field scrimsVisibility = shadeState.getField("scrimsVisibility");
        Field keyguardShowing = shadeState.getField("keyguardShowing");
        Field dozing = shadeState.getField("dozing");
        Class<?> builder = loader.loadClass("android.window.ScreenCapture$LayerCaptureArgs$Builder");
        Class<?> args = loader.loadClass("android.window.ScreenCapture$LayerCaptureArgs");
        Class<?> listener = loader.loadClass("android.window.ScreenCapture$ScreenCaptureListener");
        Class<?> buffer = loader.loadClass("android.window.ScreenCapture$ScreenshotHardwareBuffer");
        argsBuilder = builder.getConstructor(SurfaceControl.class);
        setCrop = builder.getMethod("setSourceCrop", Rect.class);
        setExcludeLayers = builder.getMethod("setExcludeLayers", SurfaceControl[].class);
        build = builder.getMethod("build");
        listenerConstructor = listener.getConstructor(ObjIntConsumer.class);
        getHardwareBuffer = buffer.getMethod("getHardwareBuffer");
        getColorSpace = buffer.getMethod("getColorSpace");
        captureLayers = loader.loadClass("android.window.ScreenCapture").getMethod("captureLayers", args, listener);
        taskSurfaces = new AppNavigationTaskSurface(loader);
        taskSurfaces.install(installer, id -> main.post(() -> {
            for (State state : states.values()) if (state.task != null && state.task.id == id) state.reset();
        }), info -> main.post(() -> focusedTaskChanged(info)));
        new AppNavigationRecentsHooks(loader).install(installer, this::recentsAnimationChanged);
        startWorkers();
        installer.hook(NAV, "onAttachedToWindow", chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            State state = new State(view);
            State previous = states.put(view, state);
            if (previous != null) previous.close();
            state.tick();
            return result;
        });
        installer.hook(NAV, "onDetachedFromWindow", chain -> {
            State state = states.remove(chain.getThisObject());
            if (state != null) state.close();
            return chain.proceed();
        });
        installer.hook(SHADE_WINDOW, "apply", chain -> {
            Object state = chain.getArg(0);
            if (recentsCached && (keyguardShowing.getBoolean(state) || dozing.getBoolean(state))) {
                recentsCached = false; recentsRunning = false;
                for (State navigation : states.values()) {
                    navigation.reset(); navigation.scheduleSample();
                }
            }
            boolean visible = panelVisible.getBoolean(state) || centerVisible.getBoolean(state)
                    || expanded.getBoolean(state) || blurRadius.getInt(state) > 0
                    || scrimsVisibility.getInt(state) != 0;
            if (shadeVisible != visible) {
                shadeVisible = visible;
                for (State navigation : states.values()) {

                    try { reapply(navigation.transitions()); }
                    catch (ReflectiveOperationException error) { navigation.report(error); }
                }
            }
            return chain.proceed();
        }, shadeState);
        installer.hook(TRANSITIONS, "applyDarkIntensity", chain -> {
            View view = (View) chain.getThisObject().getClass().getField("mView").get(chain.getThisObject());
            State state = states.get(view);
            if (state != null && state.color != null && enabled() && !shadeVisible && !recentsCached) {
                return chain.proceed(new Object[]{Color.luminance(state.color) > .5f ? 1f : 0f});
            }
            return chain.proceed();
        }, float.class);
        installer.hook("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler", "onInputEvent$1", chain -> {
            InputEvent event = (InputEvent) chain.getArg(0);
            if (enabled() && !recentsCached && event instanceof MotionEvent) {
                MotionEvent motion = (MotionEvent) event;
                int action = motion.getActionMasked();
                float x = motion.getX(), y = motion.getY();
                int display = (Integer) XposedHelpers.callMethod(event, "getDisplayId");
                main.post(() -> {
                    if (!enabled() || recentsCached) return;
                    for (State state : states.values()) if (state.effect != null && state.view.getDisplay() != null
                            && state.view.getDisplay().getDisplayId() == display) {
                        try { state.effect.input(action, x, y); }
                        catch (ReflectiveOperationException error) { state.report(error); }
                    }
                });
            }
            return chain.proceed();
        }, InputEvent.class);
        log.accept("SystemUI ColorOS native navigation color-pick hooks registered", null);
    }

    private boolean enabled() {
        return !closed && !paused && XposedInit.readBoolCached(AppNavigationSettings.ENABLED, false);
    }

    private void recentsAnimationChanged(boolean running) {
        recentsRunning = running;
        if (running) {

            recentsCached = true;
            main.post(this::cacheForRecents);
        } else main.postDelayed(() -> {
            try {
                ActivityManager.RunningTaskInfo info = taskSurfaces.focusedTask();
                if (info != null) focusedTaskChanged(info);
            } catch (ReflectiveOperationException | RuntimeException error) {
                log.accept("Cannot resolve focused task after recents animation", error);
            }
        }, RECENTS_FOCUS_SETTLE_MS);
    }

    private void cacheForRecents() {
        if (closed || paused || !recentsCached) return;
        for (State state : states.values()) {
            main.removeCallbacks(state.tick);
            state.cancelRequests();
            state.restoreNavigationBackground();
            try { reapply(state.transitions()); }
            catch (ReflectiveOperationException error) { state.report(error); }
        }
    }

    private void focusedTaskChanged(ActivityManager.RunningTaskInfo info) {
        if (closed || paused || recentsRunning) return;
        try {
            if (taskSurfaces.isOverviewHost(info)) {
                if (!recentsCached) { recentsCached = true; cacheForRecents(); }
                return;
            }
            if (!recentsCached) return;
            recentsCached = false;
            for (State state : states.values()) {
                if (state.task != null && state.task.id == info.taskId
                        && state.task.surface.isValid() && enabled()
                        && info.topActivity != null && selectedApplications()
                                .contains(info.topActivity.getPackageName())) {
                    if (state.effect != null && state.color != null) state.clearNavigationBackground();
                    reapply(state.transitions());
                } else state.reset();
                state.scheduleSample();
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            log.accept("Cannot restore application navigation after recents", error);
        }
    }

    private final class State {
        final View view;
        final Runnable tick = this::tick;
        volatile boolean attached = true;
        boolean pending, checkingTask, errorLogged;
        Integer color;
        volatile int generation;
        volatile long activeCapture;
        volatile long activeTask;
        Runnable captureTimeout, taskTimeout;
        // Retain the native listener until its asynchronous capture completes.
        Object captureListener;
        NavigationCaptureCallback captureRequest;
        ColorOsNavigationEffect effect;
        long effectVersion;
        Rect navigationArea;
        AppNavigationTaskSurface.Target task;
        Drawable originalBackground;
        boolean backgroundCleared;
        State(View view) { this.view = view; }

        void tick() {
            if (!attached || paused || closed) return;
            main.removeCallbacks(tick);
            if (!enabled()) { reset(); return; }
            if (recentsCached) return;
            try {
                KeyguardManager keyguard = view.getContext().getSystemService(KeyguardManager.class);
                boolean vertical = view.getClass().getField("mIsVertical").getBoolean(view);
                int mode = android.provider.Settings.Secure.getInt(view.getContext().getContentResolver(), "hide_navigationbar_enable", 0);
                android.view.WindowInsets insets = view.getRootWindowInsets();
                boolean ime = (insets != null && insets.isVisible(android.view.WindowInsets.Type.ime()))
                        || (view.getClass().getField("mNavbarFlags").getInt(view) & 1) != 0;
                if (!enabled() || !view.isShown() || vertical || (mode != 2 && mode != 3) || ime || view.getDisplay() == null
                        || (keyguard != null && keyguard.isKeyguardLocked())) {
                    reset();
                } else if (!checkingTask) {
                    Rect area = navigationFrame(view);
                    if (area.top > 1 && !area.isEmpty()) {
                        Rect navigation = new Rect(area);
                        int display = view.getDisplay().getDisplayId(), token = generation;
                        checkingTask = true;
                        long taskRequest = ++activeTask;
                        taskTimeout = () -> { if (!paused && !closed && checkingTask && taskRequest == activeTask && !recentsCached) reset(); };
                        main.postDelayed(taskTimeout, 750);
                        worker.post(() -> checkTask(navigation, display, token, taskRequest));
                    } else reset();
                }
            } catch (ReflectiveOperationException | RuntimeException error) { report(error); reset(); }
            scheduleSample();
        }

        void scheduleSample() {
            main.removeCallbacks(tick);
            if (attached && enabled() && !recentsCached) main.postDelayed(tick, SAMPLE_INTERVAL_MS);
        }

        void checkTask(Rect navigation, int display, int token, long taskRequest) {
            if (paused || closed || !attached || token != generation || taskRequest != activeTask || recentsCached) return;
            try {
                ActivityManager manager = view.getContext().getSystemService(ActivityManager.class);
                List<ActivityManager.RunningTaskInfo> tasks = manager == null ? null : manager.getRunningTasks(1);
                ActivityManager.RunningTaskInfo task = tasks == null || tasks.isEmpty() ? null : tasks.get(0);
                boolean selected = task != null && task.topActivity != null
                        && task.getClass().getField("displayId").getInt(task) == display
                        && selectedApplications().contains(task.topActivity.getPackageName());
                ColorOsNavigationPolicy policy = selected ? policies.resolve(view.getContext(),
                        task.topActivity.getPackageName(), task.topActivity.getClassName()) : ColorOsNavigationPolicy.DEFAULT;
                AppNavigationTaskSurface.Target target = selected ? taskSurfaces.find(task, display, navigation, policy) : null;
                main.post(() -> {
                    if (taskRequest != activeTask) { if (target != null) target.close(); return; }
                    if (taskTimeout != null) main.removeCallbacks(taskTimeout);
                    taskTimeout = null;
                    checkingTask = false;
                    if (paused || closed || !attached || token != generation || !enabled() || recentsCached) { if (target != null) target.close(); return; }
                    if (target == null) { reset(); return; }
                    boolean adopted = false;
                    try {
                        boolean same = taskSurfaces.same(this.task, target);
                        if (pending) {
                            if (!same) reset();
                            return;
                        }
                        if (!same) hideBacking();
                        if (this.task != null) this.task.close();
                        this.task = target; adopted = true;
                        startCapture(navigation, token);
                    } catch (ReflectiveOperationException | RuntimeException error) { report(error); reset(); }
                    finally { if (!adopted) target.close(); }
                });
            } catch (ReflectiveOperationException | RuntimeException error) {
                report(error); main.post(() -> { if (!paused && !closed && taskRequest == activeTask && !recentsCached) reset(); });
            }
        }

        void startCapture(Rect navigation, int token) throws ReflectiveOperationException {
            if (effect == null) {
                long effectToken = ++effectVersion;
                effect = new ColorOsNavigationEffect(classLoader, main, task, navigation, (picked, alpha) -> {
                    if (paused || closed || !attached || token != generation || effectToken != effectVersion
                            || !enabled() || recentsCached) return;

                    color = picked;
                    if (Color.alpha(picked) > 0 && alpha > 0) clearNavigationBackground();
                    else restoreNavigationBackground();
                    try { applyIntensity.invoke(transitions(), Color.luminance(picked) > .5f ? 1f : 0f); }
                    catch (ReflectiveOperationException error) { report(error); }
                });
            }
            navigationArea = new Rect(navigation);
            SurfaceControl root = taskSurfaces.copy(task.surface);
            SurfaceControl exclude;
            try { exclude = effect.sourceCopy(taskSurfaces); }
            catch (ReflectiveOperationException | RuntimeException error) { root.release(); throw error; }
            Rect bounds = new Rect(task.bounds);
            ColorOsNavigationEffect currentEffect = effect;
            pending = true;
            long request = ++activeCapture;
            captureTimeout = () -> { if (!paused && !closed && pending && activeCapture == request && !recentsCached) reset(); };
            main.postDelayed(captureTimeout, 750);
            captureWorker.post(() -> sample(navigation, bounds, root, exclude, currentEffect, token, request));
        }

        void sample(Rect navigation, Rect bounds, SurfaceControl root, SurfaceControl exclude,
                ColorOsNavigationEffect currentEffect, int token, long request) {
            NavigationCaptureCallback callback = null;
            try {
                if (paused || closed || !attached || token != generation || request != activeCapture || recentsCached) return;
                Object builder = argsBuilder.newInstance(root);
                setCrop.invoke(builder, new Rect(0, 0, bounds.width(), bounds.height()));
                setExcludeLayers.invoke(builder, (Object) new SurfaceControl[]{exclude});
                callback = new NavigationCaptureCallback((capture, buffer, status) -> worker.post(() -> {
                    HardwareBuffer allocation = null;
                    boolean submitted = false;
                    try {
                        if (buffer != null) {
                            allocation = (HardwareBuffer) getHardwareBuffer.invoke(buffer);
                            if (status == 0 && allocation != null && allocation.getWidth() == bounds.width()
                                    && allocation.getHeight() == bounds.height() && !paused && !closed && attached
                                    && token == generation && request == activeCapture && !recentsCached) {

                                submitted = currentEffect.submit(allocation,
                                        (ColorSpace) getColorSpace.invoke(buffer), navigation, bounds);
                                if (submitted) allocation = null; // Ownership moved to the effect.
                            }
                        }
                    } catch (ReflectiveOperationException | RuntimeException error) { report(error); }
                    finally {
                        if (allocation != null) allocation.close();
                        captures.remove(capture);
                    }
                    boolean valid = submitted;
                    main.post(() -> {
                        if (request != activeCapture) return;
                        if (captureTimeout != null) main.removeCallbacks(captureTimeout);
                        captureTimeout = null; captureListener = null; captureRequest = null; pending = false;
                        if (paused || closed || !attached || token != generation || !enabled() || recentsCached) return;
                        Rect current;
                        try { current = navigationFrame(view); }
                        catch (ReflectiveOperationException error) { report(error); reset(); return; }
                        if (!valid || !current.equals(navigation)) reset();
                    });
                }), discardCaptureBuffer(getHardwareBuffer));
                synchronized (captures) {
                    if (paused || closed || token != generation || request != activeCapture) { callback.cancel(); return; }
                    captures.add(callback);
                    captureRequest = callback;
                }
                captureListener = listenerConstructor.newInstance(callback);
                int status = (Integer) captureLayers.invoke(null, build.invoke(builder), captureListener);
                if (status != 0) { callback.cancel(); captures.remove(callback); }
                if (status != 0) main.post(() -> { if (!paused && !closed && request == activeCapture && !recentsCached) reset(); });
            } catch (ReflectiveOperationException | RuntimeException error) {
                if (callback != null) { callback.cancel(); captures.remove(callback); }
                report(error); main.post(() -> { if (!paused && !closed && request == activeCapture && !recentsCached) reset(); });
            } finally {
                root.release(); exclude.release();
            }
        }

        Object transitions() throws ReflectiveOperationException { return view.getClass().getField("mBarTransitions").get(view); }
        void clearNavigationBackground() {
            if (!backgroundCleared) {
                originalBackground = view.getBackground();
                backgroundCleared = true;
                view.setBackground(null);
            }
        }
        void restoreBacking() {
            effectVersion++;
            if (effect != null) { effect.close(); effect = null; }
            navigationArea = null;
            restoreNavigationBackground();
        }
        void restoreNavigationBackground() {
            if (backgroundCleared) {
                if (view.getBackground() == null) view.setBackground(originalBackground);
                originalBackground = null;
                backgroundCleared = false;
            }
        }
        void cancelRequests() {
            synchronized (captures) {
                generation++;
                activeCapture++;
                activeTask++;
                if (captureRequest != null) {
                    captureRequest.cancel();
                    captures.remove(captureRequest);
                    captureRequest = null;
                }
            }
            if (taskTimeout != null) main.removeCallbacks(taskTimeout);
            taskTimeout = null; checkingTask = false;
            if (captureTimeout != null) main.removeCallbacks(captureTimeout);
            captureTimeout = null; captureListener = null; pending = false;
        }
        void reset() {
            cancelRequests();
            hideBacking();
            if (task != null) { task.close(); task = null; }
        }
        void hideBacking() {
            restoreBacking();
            if (color == null) return;
            color = null;
            try { reapply(transitions()); }
            catch (ReflectiveOperationException error) { report(error); }
        }
        void close() { attached = false; main.removeCallbacks(tick); reset(); }
        void report(Throwable error) {
            if (!errorLogged) { errorLogged = true; log.accept("Cannot sample application navigation background in SystemUI", error); }
        }
    }
}
