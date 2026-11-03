package com.rikumi.colorosmod.hooks;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;

final class AppNavigationRecentsHooks {
    private static final String HANDLER = "com.android.wm.shell.recents.RecentsTransitionHandler";
    private static final String COMMANDS = "com.android.systemui.statusbar.CommandQueue";
    private final Field controllers;
    private final Class<?>[] startParameters;
    private volatile Object handler;

    AppNavigationRecentsHooks(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> owner = loader.loadClass(HANDLER);
        startParameters = NavigationPlatformCompat.recentsStart(loader).getParameterTypes();
        controllers = AppNavigationTaskSurface.field(owner, "mControllers");
        loader.loadClass(HANDLER + "$RecentsController").getDeclaredMethod("cleanUp");
        loader.loadClass(COMMANDS).getDeclaredMethod("onRecentsAnimationStateChanged", boolean.class);
    }

    void install(AppNavigationHooks.Installer installer, Consumer<Boolean> animation) {
        installer.hook(HANDLER, "startRecentsTransition", chain -> {
            handler = chain.getThisObject();
            animation.accept(true);
            Object result = chain.proceed();
            if (result == null && ((List<?>) controllers.get(handler)).isEmpty()) animation.accept(false);
            return result;
        }, startParameters);
        installer.hook(HANDLER + "$RecentsController", "cleanUp", chain -> {
            Object result = chain.proceed();
            Object current = handler;
            if (current != null && ((List<?>) controllers.get(current)).isEmpty()) animation.accept(false);
            return result;
        });
        // The legacy animation path reports through the status-bar command queue.
        installer.hook(COMMANDS, "onRecentsAnimationStateChanged", chain -> {
            animation.accept((Boolean) chain.getArg(0));
            return chain.proceed();
        }, boolean.class);
    }
}
