package com.rikumi.colorosmod.hooks;

import java.util.List;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.log;

/** Work around the firmware's remove-while-indexing loop during AOD listener replacement. */
final class AodReceiverCompatibilityHooks {
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> receiver = XposedHelpers.findClass("com.oplus.systemui.aod.receiver.AodUpdateReceiver", lp.classLoader);
            Class<?> listener = XposedHelpers.findClass("com.oplusos.systemui.common.receiver.IReceiverListener", lp.classLoader);
            Class<?> controller = XposedHelpers.findClass("com.oplus.systemui.aod.controller.BaseAodController$AodControllerUpdateReceiver", lp.classLoader);
            XposedHelpers.hookChainMethod(receiver.getDeclaredMethod("addListener", listener), chain -> {
                Object owner = chain.getThisObject();
                synchronized (owner) {
                    if (controller.isInstance(chain.getArg(0))) {
                        @SuppressWarnings("unchecked")
                        List<Object> previous = (List<Object>) XposedHelpers.getObjectField(owner, "mListenersList");
                        // Remove via native removeListener, updating both stores.
                        // Native add then sees no old controller to delete mid-loop.
                        ReceiverListenerReplacement.removePrevious(previous, controller,
                                old -> XposedHelpers.callMethod(owner, "removeListener", old));
                    }
                    return chain.proceed();
                }
            });
            XposedHelpers.hookChainMethod(receiver.getDeclaredMethod("removeListener", listener), chain -> {
                synchronized (chain.getThisObject()) { return chain.proceed(); }
            });
        } catch (Throwable error) { log("AOD receiver compatibility hook failed: " + error); }
    }
}
