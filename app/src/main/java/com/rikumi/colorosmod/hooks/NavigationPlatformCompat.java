package com.rikumi.colorosmod.hooks;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.util.Map;

/** Resolve the platform APIs by their actual signatures, rather than Android version labels. */
final class NavigationPlatformCompat {
    static Class<?> captureApi(ClassLoader loader) throws ClassNotFoundException {
        try {
            Class<?> api = loader.loadClass("android.window.ScreenCaptureInternal");
            loader.loadClass(api.getName() + "$LayerCaptureArgs$Builder");
            return api;
        } catch (ClassNotFoundException legacy) {
            return loader.loadClass("android.window.ScreenCapture");
        }
    }

    static Method recentsStart(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> owner = loader.loadClass("com.android.wm.shell.recents.RecentsTransitionHandler");
        Class<?> app = loader.loadClass("android.app.IApplicationThread");
        Class<?> runner = loader.loadClass("com.android.wm.shell.recents.IRecentsAnimationRunner");
        try {
            return owner.getDeclaredMethod("startRecentsTransition", PendingIntent.class, Intent.class,
                    Bundle.class, loader.loadClass("android.window.WindowContainerTransaction"), app, runner);
        } catch (ClassNotFoundException | NoSuchMethodException legacy) {
            return owner.getDeclaredMethod("startRecentsTransition", PendingIntent.class, Intent.class,
                    Bundle.class, app, runner);
        }
    }

    static Method gestureInput(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> legacy = loader.loadClass("com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler");
        try {
            return legacy.getDeclaredMethod("onInputEvent$1", InputEvent.class);
        } catch (NoSuchMethodException current) {
            return loader.loadClass("com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector")
                    .getDeclaredMethod("onMotionEventImpl", MotionEvent.class, Map.class);
        }
    }
}
