package com.rikumi.colorosmod.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Resolves the old and current launcher index APIs once, before installing callbacks. */
final class DrawerIndexAccess {
    private final Method getter, disable;
    private final Field scroller, canStart;

    DrawerIndexAccess(Class<?> type) {
        Method getter = method(type, "getFastScroller");
        this.getter = getter != null ? getter : method(type, "getMFastScroller");
        scroller = field(type, "mFastScroller");
        Method setter = method(type, "setCanStartAnim", boolean.class);
        disable = setter != null ? setter : method(type, "setCanStartAnim");
        canStart = field(type, "mCanStartAnim");
        if (this.getter == null && scroller == null)
            throw new IllegalArgumentException("No drawer fast-scroller accessor in " + type.getName());
    }

    Object fastScroller(Object helper) throws ReflectiveOperationException {
        return getter != null ? getter.invoke(helper) : scroller.get(helper);
    }

    void disableAnimation(Object helper) throws ReflectiveOperationException {
        if (disable != null) {
            if (disable.getParameterCount() == 0) disable.invoke(helper);
            else disable.invoke(helper, false);
        } else if (canStart != null) canStart.setBoolean(helper, false);
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Method method = owner.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException absent) { /* Check the other native layout. */ }
        }
        return null;
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) { /* Check the native base class. */ }
        }
        return null;
    }
}
