package com.rikumi.colorosmod.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * 旧版 XposedHelpers 的等价实现, 底层换成新版 libxposed API:
 * 反射查找仍是 java.lang.reflect, 而方法/构造的 hook 走 XposedInterface 的拦截器链。
 * 抛出的异常类型与旧版保持一致(ClassNotFoundError / NoSuchFieldError / NoSuchMethodError,
 * 均为 Error), 业务代码里清一色 catch Throwable, 语义不变。
 */
public final class XposedHelpers {

    private static final Object QUIESCE_LOCK = new Object();
    private static volatile boolean QUIESCING;
    private static int IN_FLIGHT;

    private XposedHelpers() {
    }

    // ------------------------------------------------------------------ 类查找

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        if (classLoader == null) classLoader = ClassLoader.getSystemClassLoader();
        try {
            return classLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return findClass(className, classLoader);
        } catch (ClassNotFoundError e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ Hook

    /** 只能 hook 方法或构造方法; 字段等 Member 会直接抛异常(与旧版一致)。 */
    static void hookMethod(Member hookMethod, XC_MethodHook callback) {
        if (!(hookMethod instanceof Executable)) {
            throw new IllegalArgumentException("Only methods and constructors can be hooked: " + hookMethod);
        }
        hookExecutable((Executable) hookMethod, callback);
    }

    static XC_MethodHook.Unhook hookExecutable(Executable executable, XC_MethodHook callback) {
        XposedInterface framework = XposedBridge.framework();
        executable.setAccessible(true);
        try {
            // 被内联的方法 hook 不上(system_server 常见), 先去优化再挂钩。
            framework.deoptimize(executable);
        } catch (Throwable ignored) {
        }
        final XC_MethodHook hook = callback;
        XposedInterface.HookHandle handle = framework.hook(executable)
                .intercept(new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (!enterHook()) return chain.proceed();
                        try {
                            return hook.handleHookedMethod(chain);
                        } finally {
                            leaveHook();
                        }
                    }
                });
        return new XC_MethodHook.Unhook(handle);
    }

    public static void findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback defined");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Object[] types = Arrays.copyOf(parameterTypesAndCallback, parameterTypesAndCallback.length - 1);
        hookExecutable(findMethodExact(clazz, methodName,
                getParameterClasses(clazz.getClassLoader(), types)), callback);
    }

    public static void findAndHookMethod(String className, ClassLoader classLoader, String methodName,
                                         Object... parameterTypesAndCallback) {
        findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    /**
     * 只在目标类自己声明的方法里找, 不上溯父类、不查接口; 找不到直接抛 NoSuchMethodError。
     *
     * 用于 hook view / 框架生命周期回调(onAttachedToWindow、onDraw、onTouchEvent 等):
     * 这些方法在 android.view.View 里都有实现, 一旦某个 ROM 版本的目标类没有覆写它,
     * findMethodExact 会沿父类链一路找到 View, 于是 hook 变成对该进程内所有 View 生效。
     * 更严重的是回调里若再 addView, 会触发 dispatchAttachedToWindow 重新进入同一个 hook,
     * 直接栈溢出崩溃(实测 SystemUI 每 90s 崩一次、循环不停)。
     * 因此"本意就是 hook 该类自己的覆写"的场景必须用这个版本, 缺失即放弃 hook。
     * 需要靠上溯覆盖子类的场景(如 final 方法) 仍用 findAndHookMethod。
     */
    public static Method findMethodDeclared(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        Class<?>[] types = (parameterTypes == null) ? new Class<?>[0] : parameterTypes;
        try {
            Method method = clazz.getDeclaredMethod(methodName, types);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName + parametersString(types) + "#declared");
        }
    }

    public static void findAndHookDeclaredMethod(Class<?> clazz, String methodName,
                                                 Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback defined");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Object[] types = Arrays.copyOf(parameterTypesAndCallback, parameterTypesAndCallback.length - 1);
        hookExecutable(findMethodDeclared(clazz, methodName,
                getParameterClasses(clazz.getClassLoader(), types)), callback);
    }

    public static void findAndHookDeclaredMethod(String className, ClassLoader classLoader, String methodName,
                                                 Object... parameterTypesAndCallback) {
        findAndHookDeclaredMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static void findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback defined");
        }
        XC_MethodHook callback = (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Object[] types = Arrays.copyOf(parameterTypesAndCallback, parameterTypesAndCallback.length - 1);
        hookExecutable(findConstructorExact(clazz, getParameterClasses(clazz.getClassLoader(), types)), callback);
    }

    public static void findAndHookConstructor(String className, ClassLoader classLoader,
                                              Object... parameterTypesAndCallback) {
        findAndHookConstructor(findClass(className, classLoader), parameterTypesAndCallback);
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new LinkedHashSet<XC_MethodHook.Unhook>();
        for (Constructor<?> ctor : hookClass.getDeclaredConstructors()) {
            unhooks.add(hookExecutable(ctor, callback));
        }
        return unhooks;
    }

    /** Hook 目标类自己声明的全部同名方法, 用于 OEM 重载签名不稳定的系统服务入口。 */
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
                                                            XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> unhooks = new LinkedHashSet<XC_MethodHook.Unhook>();
        for (Method method : hookClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                unhooks.add(hookExecutable(method, callback));
            }
        }
        return unhooks;
    }

    // ------------------------------------------------------------------ 方法查找

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        Class<?>[] types = (parameterTypes == null) ? new Class<?>[0] : parameterTypes;
        Class<?> clz = clazz;
        while (clz != null) {
            try {
                Method method = clz.getDeclaredMethod(methodName, types);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                clz = clz.getSuperclass();
            }
        }
        for (Class<?> iface : allInterfaces(clazz)) {
            try {
                Method method = iface.getDeclaredMethod(methodName, types);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName + parametersString(types) + "#exact");
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Class<?>... parameterTypes) {
        Class<?>[] types = (parameterTypes == null) ? new Class<?>[0] : parameterTypes;
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + "#<init>" + parametersString(types) + "#exact");
        }
    }

    /**
     * 先按精确签名找, 找不到再按实参类型做宽松匹配(含装箱/拆箱与父类兼容)。
     * 实参里出现 null 时无法精确匹配, 直接落到宽松匹配 —— 与旧版 findMethodBestMatch 同口径。
     */
    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        Class<?>[] types = (parameterTypes == null) ? new Class<?>[0] : parameterTypes;
        try {
            return findMethodExact(clazz, methodName, types);
        } catch (Throwable ignored) {
        }

        Method bestMatch = null;
        Class<?> clz = clazz;
        while (clz != null) {
            for (Method method : clz.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) continue;
                if (!parametersMatch(method.getParameterTypes(), types, true)) continue;
                if (bestMatch != null && !Arrays.equals(bestMatch.getParameterTypes(), method.getParameterTypes())) {
                    throw new NoSuchMethodError(clazz.getName() + "#" + methodName
                            + parametersString(types) + "#bestmatch ambiguous");
                }
                bestMatch = method;
            }
            clz = clz.getSuperclass();
        }
        if (bestMatch == null) {
            for (Class<?> iface : allInterfaces(clazz)) {
                for (Method method : iface.getDeclaredMethods()) {
                    if (!method.getName().equals(methodName)) continue;
                    if (!parametersMatch(method.getParameterTypes(), types, true)) continue;
                    if (bestMatch != null
                            && !Arrays.equals(bestMatch.getParameterTypes(), method.getParameterTypes())) {
                        throw new NoSuchMethodError(clazz.getName() + "#" + methodName
                                + parametersString(types) + "#bestmatch ambiguous");
                    }
                    bestMatch = method;
                }
            }
        }
        if (bestMatch == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName + parametersString(types) + "#bestmatch");
        }
        bestMatch.setAccessible(true);
        return bestMatch;
    }

    public static Constructor<?> findConstructorBestMatch(Class<?> clazz, Class<?>... parameterTypes) {
        Class<?>[] types = (parameterTypes == null) ? new Class<?>[0] : parameterTypes;
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException ignored) {
        }

        Constructor<?> bestMatch = null;
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (!parametersMatch(ctor.getParameterTypes(), types, true)) continue;
            if (bestMatch != null && !Arrays.equals(bestMatch.getParameterTypes(), ctor.getParameterTypes())) {
                throw new NoSuchMethodError(clazz.getName() + "#<init>" + parametersString(types) + " ambiguous");
            }
            bestMatch = ctor;
        }
        if (bestMatch == null) {
            throw new NoSuchMethodError(clazz.getName() + "#<init>" + parametersString(types) + " not found");
        }
        bestMatch.setAccessible(true);
        return bestMatch;
    }

    // ------------------------------------------------------------------ 调用

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return invoke(findMethodBestMatch(obj.getClass(), methodName, parameterTypes(args)), obj, args);
    }

    public static Object callMethod(Object obj, String methodName, Class<?>[] parameterTypes, Object... args) {
        return invoke(findMethodBestMatch(obj.getClass(), methodName, parameterTypes), obj, args);
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return invoke(findMethodBestMatch(clazz, methodName, parameterTypes(args)), null, args);
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Class<?>[] parameterTypes,
                                          Object... args) {
        return invoke(findMethodBestMatch(clazz, methodName, parameterTypes), null, args);
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        try {
            return findConstructorBestMatch(clazz, parameterTypes(args)).newInstance(args);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InstantiationException e) {
            throw new InstantiationError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw new InvocationTargetError("Construction of " + clazz + " failed", e.getCause());
        }
    }

    private static Object invoke(Method method, Object receiver, Object[] args) {
        try {
            return method.invoke(receiver, args);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        } catch (InvocationTargetException e) {
            throw new InvocationTargetError("Invocation of " + method + " failed", e.getCause());
        }
    }

    // ------------------------------------------------------------------ 字段

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static int getIntField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getInt(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        try {
            findField(obj.getClass(), fieldName).setInt(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getBoolean(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
        try {
            findField(obj.getClass(), fieldName).setBoolean(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static long getLongField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getLong(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setLongField(Object obj, String fieldName, long value) {
        try {
            findField(obj.getClass(), fieldName).setLong(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static float getFloatField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getFloat(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setFloatField(Object obj, String fieldName, float value) {
        try {
            findField(obj.getClass(), fieldName).setFloat(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<String, Field>();

    /** 沿类继承链查找字段(含父类), 并打开访问开关。 */
    private static Field findField(Class<?> clazz, String fieldName) {
        String key = clazz.getName() + '#' + fieldName;
        Field cached = FIELD_CACHE.get(key);
        if (cached != null) return cached;

        Class<?> clz = clazz;
        while (clz != null) {
            try {
                Field field = clz.getDeclaredField(fieldName);
                field.setAccessible(true);
                FIELD_CACHE.put(key, field);
                return field;
            } catch (NoSuchFieldException ignored) {
                clz = clz.getSuperclass();
            }
        }
        throw new NoSuchFieldError(clazz.getName() + '.' + fieldName);
    }

    // ------------------------------------------------------------------ 附加实例字段
    //
    // 旧版是往目标类里注入一个真实字段; 这里改用弱引用表, 效果等价
    // (值的生命周期与对象一致, 且被 hook 的类不会被改写)。
    // key 走 identity 语义, 避免目标对象重写 equals/hashCode 时串数据。

    // 以目标对象本身为 WeakHashMap 的 key(弱引用指向真实对象): 对象存活期间(含跨多次
    // hook 调用的场景, 如 mBack 的 DOWN->UP)条目保留; 对象被 GC 时条目自动回收, 不泄漏。
    // 注意: 此前误用 IdentityWeakKey 包装对象做 key —— 包装对象本身也被 WeakHashMap 弱引用,
    // 方法返回后即无强引用, 很快被 GC, 导致条目在两次调用之间丢失, 表现为依赖附加实例字段
    // 跨调用存状态的功能(如 mBack)整体失效; 单次 hook 调用内读写(如浮窗挂机的 LAST_SHOWING)
    // 因包装对象仍在栈上而幸存, 故其他功能看似正常。这是 libxposed 迁移后兼容层引入的回归。
    private static final Map<Object, Map<String, Object>> ADDITIONAL_FIELDS =
            java.util.Collections.synchronizedMap(new WeakHashMap<Object, Map<String, Object>>());
    private static final Map<String, List<TrackedPreDrawListener>> PREPARED_PRE_DRAW =
            new HashMap<String, List<TrackedPreDrawListener>>();
    private static final Map<String, Runnable> PRE_DRAW_ROLLBACK_RETRIES =
            new HashMap<String, Runnable>();

    private static final class TrackedPreDrawListener {
        final android.view.View owner;
        final android.view.ViewTreeObserver.OnPreDrawListener listener;

        TrackedPreDrawListener(android.view.View owner,
                android.view.ViewTreeObserver.OnPreDrawListener listener) {
            this.owner = owner;
            this.listener = listener;
        }
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        if (obj == null) throw new NullPointerException("object must not be null");
        if (key == null) throw new NullPointerException("key must not be null");
        Map<String, Object> fields = ADDITIONAL_FIELDS.get(obj);
        if (fields == null) {
            synchronized (ADDITIONAL_FIELDS) {
                fields = ADDITIONAL_FIELDS.get(obj);
                if (fields == null) {
                    fields = new ConcurrentHashMap<String, Object>();
                    ADDITIONAL_FIELDS.put(obj, fields);
                }
            }
        }
        return fields.put(key, value);
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        if (obj == null) throw new NullPointerException("object must not be null");
        if (key == null) throw new NullPointerException("key must not be null");
        Map<String, Object> fields = ADDITIONAL_FIELDS.get(obj);
        if (fields == null) return null;
        return fields.get(key);
    }

    public static Object removeAdditionalInstanceField(Object obj, String key) {
        if (obj == null) throw new NullPointerException("object must not be null");
        if (key == null) throw new NullPointerException("key must not be null");
        Map<String, Object> fields = ADDITIONAL_FIELDS.get(obj);
        if (fields == null) return null;
        return fields.remove(key);
    }


    /** Stop admitting module callbacks and wait for callbacks already inside old code. */
    public static boolean beginQuiescing(long timeoutMs) {
        long deadline = android.os.SystemClock.uptimeMillis() + timeoutMs;
        synchronized (QUIESCE_LOCK) {
            QUIESCING = true;
            while (IN_FLIGHT != 0) {
                long left = deadline - android.os.SystemClock.uptimeMillis();
                if (left <= 0) {
                    QUIESCING = false;
                    QUIESCE_LOCK.notifyAll();
                    return false;
                }
                try {
                    QUIESCE_LOCK.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    QUIESCING = false;
                    QUIESCE_LOCK.notifyAll();
                    return false;
                }
            }
            return true;
        }
    }

    public static void cancelQuiescing() {
        synchronized (QUIESCE_LOCK) {
            QUIESCING = false;
            QUIESCE_LOCK.notifyAll();
        }
    }

    private static boolean enterHook() {
        synchronized (QUIESCE_LOCK) {
            if (QUIESCING) return false;
            IN_FLIGHT++;
            return true;
        }
    }

    private static void leaveHook() {
        synchronized (QUIESCE_LOCK) {
            if (--IN_FLIGHT == 0) QUIESCE_LOCK.notifyAll();
        }
    }


    public interface TrackedOwnerConsumer { void accept(Object owner); }

    public static void forEachTrackedOwner(String key, TrackedOwnerConsumer consumer) {
        synchronized (ADDITIONAL_FIELDS) {
            for (Map.Entry<Object, Map<String, Object>> entry :
                    new ArrayList<Map.Entry<Object, Map<String, Object>>>(ADDITIONAL_FIELDS.entrySet())) {
                if (entry.getValue().containsKey(key)) consumer.accept(entry.getKey());
            }
        }
    }

    /** Detach tracked listeners without dropping bookkeeping so a rejected reload can restore them. */
    public static boolean prepareTrackedPreDrawListeners(String key) {
        synchronized (ADDITIONAL_FIELDS) {
            if (PREPARED_PRE_DRAW.containsKey(key)) return false;
            List<TrackedPreDrawListener> detached = new ArrayList<TrackedPreDrawListener>();
            PREPARED_PRE_DRAW.put(key, detached);
            for (Map.Entry<Object, Map<String, Object>> entry :
                    new ArrayList<Map.Entry<Object, Map<String, Object>>>(ADDITIONAL_FIELDS.entrySet())) {
                Object owner = entry.getKey();
                Object listener = entry.getValue().get(key);
                if (!(owner instanceof android.view.View)
                        || !(listener instanceof android.view.ViewTreeObserver.OnPreDrawListener)) continue;
                try {
                    android.view.View view = (android.view.View) owner;
                    android.view.ViewTreeObserver observer = view.getViewTreeObserver();
                    if (!observer.isAlive()) continue;
                    observer.removeOnPreDrawListener(
                            (android.view.ViewTreeObserver.OnPreDrawListener) listener);
                    detached.add(new TrackedPreDrawListener(view,
                            (android.view.ViewTreeObserver.OnPreDrawListener) listener));
                } catch (Throwable t) {
                    boolean restored = cancelTrackedPreDrawListenerPreflightLocked(key);
                    if (!restored) scheduleTrackedPreDrawRollbackLocked(key);
                    return false;
                }
            }
            return true;
        }
    }

    /** Restore listeners detached by a rejected hot-reload preflight. */
    public static boolean cancelTrackedPreDrawListenerPreflight(String key) {
        synchronized (ADDITIONAL_FIELDS) {
            boolean restored = cancelTrackedPreDrawListenerPreflightLocked(key);
            if (!restored) scheduleTrackedPreDrawRollbackLocked(key);
            return restored;
        }
    }

    private static boolean cancelTrackedPreDrawListenerPreflightLocked(String key) {
        List<TrackedPreDrawListener> detached = PREPARED_PRE_DRAW.get(key);
        if (detached == null) return true;
        for (Iterator<TrackedPreDrawListener> iterator = detached.iterator(); iterator.hasNext(); ) {
            TrackedPreDrawListener tracked = iterator.next();
            Map<String, Object> fields = ADDITIONAL_FIELDS.get(tracked.owner);
            if (fields == null || fields.get(key) != tracked.listener) {
                iterator.remove();
                continue;
            }
            try {
                android.view.ViewTreeObserver observer = tracked.owner.getViewTreeObserver();
                if (!observer.isAlive()) {
                    // A dead observer cannot retain or invoke the old-generation listener.
                    iterator.remove();
                    continue;
                }
                observer.addOnPreDrawListener(tracked.listener);
                iterator.remove();
            } catch (Throwable ignored) { }
        }
        if (!detached.isEmpty()) return false;
        PREPARED_PRE_DRAW.remove(key);
        Runnable retry = PRE_DRAW_ROLLBACK_RETRIES.remove(key);
        if (retry != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(retry);
        }
        return true;
    }

    private static void scheduleTrackedPreDrawRollbackLocked(final String key) {
        if (PRE_DRAW_ROLLBACK_RETRIES.containsKey(key)) return;
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable retry = new Runnable() {
            @Override public void run() {
                synchronized (ADDITIONAL_FIELDS) {
                    if (PRE_DRAW_ROLLBACK_RETRIES.get(key) != this) return;
                    PRE_DRAW_ROLLBACK_RETRIES.remove(key);
                    if (!cancelTrackedPreDrawListenerPreflightLocked(key)) {
                        scheduleTrackedPreDrawRollbackLocked(key);
                    }
                }
            }
        };
        PRE_DRAW_ROLLBACK_RETRIES.put(key, retry);
        main.postDelayed(retry, 1000L);
    }

    /** Finalize a successful preflight without attempting to detach the same listeners twice. */
    public static boolean commitTrackedPreDrawListenerPreflight(String key) {
        synchronized (ADDITIONAL_FIELDS) {
            if (!PREPARED_PRE_DRAW.containsKey(key)) return false;
            Runnable retry = PRE_DRAW_ROLLBACK_RETRIES.remove(key);
            if (retry != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(retry);
            }
            for (Map.Entry<Object, Map<String, Object>> entry :
                    new ArrayList<Map.Entry<Object, Map<String, Object>>>(ADDITIONAL_FIELDS.entrySet())) {
                entry.getValue().remove(key);
            }
            PREPARED_PRE_DRAW.remove(key);
            return true;
        }
    }


    public static void removeTrackedViewChildren(String... keys) {
        synchronized (ADDITIONAL_FIELDS) {
            for (Map.Entry<Object, Map<String, Object>> entry :
                    new ArrayList<Map.Entry<Object, Map<String, Object>>>(ADDITIONAL_FIELDS.entrySet())) {
                for (String key : keys) {
                    Object value = entry.getValue().remove(key);
                    if (!(value instanceof android.view.View)) continue;
                    android.view.View view = (android.view.View) value;
                    try { view.animate().cancel(); } catch (Throwable ignored) { }
                    try {
                        if (view.getParent() instanceof android.view.ViewGroup) {
                            ((android.view.ViewGroup) view.getParent()).removeView(view);
                        }
                    } catch (Throwable ignored) { }
                }
            }
        }
    }

    public static void cancelTrackedCallbacksAndAnimators() {
        synchronized (ADDITIONAL_FIELDS) {
            for (Map.Entry<Object, Map<String, Object>> entry :
                    new ArrayList<Map.Entry<Object, Map<String, Object>>>(ADDITIONAL_FIELDS.entrySet())) {
                Object owner = entry.getKey();
                for (Object value : new ArrayList<Object>(entry.getValue().values())) {
                    if (owner instanceof android.view.View && value instanceof Runnable) {
                        try { ((android.view.View) owner).removeCallbacks((Runnable) value); }
                        catch (Throwable ignored) { }
                    }
                    if (value instanceof android.animation.Animator) {
                        try { ((android.animation.Animator) value).cancel(); }
                        catch (Throwable ignored) { }
                    }
                    if (value == null) continue;
                    Field[] fields;
                    try { fields = value.getClass().getDeclaredFields(); }
                    catch (Throwable ignored) { continue; }
                    for (Field field : fields) {
                        if (!android.animation.Animator.class.isAssignableFrom(field.getType())) continue;
                        try {
                            field.setAccessible(true);
                            Object animator = field.get(value);
                            if (animator instanceof android.animation.Animator) {
                                ((android.animation.Animator) animator).cancel();
                            }
                        } catch (Throwable ignored) { }
                    }
                }
            }
        }
    }

    /** Drop Java-only generation state. UI/Animator cleanup is committed on main beforehand. */
    public static void clearGenerationState() {
        FIELD_CACHE.clear();
        synchronized (ADDITIONAL_FIELDS) {
            ADDITIONAL_FIELDS.clear();
            PREPARED_PRE_DRAW.clear();
            PRE_DRAW_ROLLBACK_RETRIES.clear();
        }
    }

    // ------------------------------------------------------------------ 内部工具

    private static Class<?>[] getParameterClasses(ClassLoader classLoader, Object[] parameterTypes) {
        Class<?>[] classes = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object type = parameterTypes[i];
            if (type == null) throw new ClassNotFoundError("parameter type must not be null");
            if (type instanceof Class) classes[i] = (Class<?>) type;
            else if (type instanceof String) classes[i] = findClass((String) type, classLoader);
            else throw new ClassNotFoundError("parameter type must either be specified as Class or String: " + type);
        }
        return classes;
    }

    /** 由实参推导形参类型; null 实参对应 null(宽松匹配时视为匹配任意非基本类型)。 */
    private static Class<?>[] parameterTypes(Object[] args) {
        if (args == null || args.length == 0) return new Class<?>[0];
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = (args[i] != null) ? args[i].getClass() : null;
        }
        return types;
    }

    private static boolean parametersMatch(Class<?>[] declared, Class<?>[] actual, boolean relaxed) {
        if (declared.length != actual.length) return false;
        for (int i = 0; i < declared.length; i++) {
            if (actual[i] == null) {
                if (declared[i].isPrimitive()) return false;
                continue;
            }
            Class<?> actualWrapped = wrap(actual[i]);
            Class<?> declaredWrapped = wrap(declared[i]);
            if (actualWrapped == declaredWrapped) continue;
            if (!relaxed) return false;
            if (!declaredWrapped.isAssignableFrom(actualWrapped)) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        return type;
    }

    private static String parametersString(Class<?>[] types) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(types[i] == null ? "<null>" : types[i].getCanonicalName());
        }
        return sb.append(')').toString();
    }

    private static List<Class<?>> allInterfaces(Class<?> clazz) {
        List<Class<?>> result = new ArrayList<Class<?>>();
        Set<Class<?>> seen = new LinkedHashSet<Class<?>>();
        for (Class<?> clz = clazz; clz != null; clz = clz.getSuperclass()) {
            collectInterfaces(clz, result, seen);
        }
        return result;
    }

    private static void collectInterfaces(Class<?> clazz, List<Class<?>> out, Set<Class<?>> seen) {
        for (Class<?> iface : clazz.getInterfaces()) {
            if (!seen.add(iface)) continue;
            out.add(iface);
            collectInterfaces(iface, out, seen);
        }
    }

    // ------------------------------------------------------------------ 异常类型(与旧版同构, 均为 Error)

    public static final class ClassNotFoundError extends Error {

        private static final long serialVersionUID = 1L;

        public ClassNotFoundError(Throwable cause) {
            super(cause);
        }

        public ClassNotFoundError(String msg) {
            super(msg);
        }
    }

    public static final class InvocationTargetError extends Error {

        private static final long serialVersionUID = 1L;

        public InvocationTargetError(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
