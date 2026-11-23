package com.rikumi.colorosmod.hooks;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.TimeInterpolator;
import com.rikumi.colorosmod.xposed.*;
import static com.rikumi.colorosmod.XposedInit.log;

/** Apply the configured duration to the visible part of ColorOS 15's folder spring. */
final class LegacyFolderAnimationHooks {
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> manager = XposedHelpers.findClass("com.android.launcher3.folder.OplusFolderAnimationManager", lp.classLoader);
            XposedBridge.hookAllMethods(manager, "getAnimator", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    int duration = LauncherHooks.folderAnimMs();
                    if (duration < 0 || p.hasThrowable() || !(p.getResult() instanceof Animator animator)) return;
                    try {
                        // The render-thread wrapper forwards setDuration, but does not expose
                        // its child interpolators. Update the underlying AnimatorSet as well.
                        if (animator.getClass().getName().equals("android.view.OplusRenderValueAnimator")) {
                            Object target = XposedHelpers.getObjectField(animator, "mTarget");
                            animator = (Animator) XposedHelpers.getObjectField(target, "mAnimator");
                        }
                        retime(animator, duration);
                    } catch (Throwable error) { log("legacy folder duration update failed: " + error); }
                }
            });
        } catch (Throwable error) { log("legacy folder duration hook unavailable: " + error); }
    }

    private static void retime(Animator animator, long duration) {
        if (animator instanceof AnimatorSet set) {
            for (Animator child : set.getChildAnimations()) retime(child, duration);
        } else {
            TimeInterpolator curve = animator.getInterpolator();
            if (curve != null && curve.getClass().getName().equals("com.coui.appcompat.animation.COUISpringInterpolator"))
                animator.setInterpolator(new VisibleSpringInterpolator(curve));
        }
        animator.setDuration(duration);
    }

    static final class VisibleSpringInterpolator implements TimeInterpolator {
        private final TimeInterpolator nativeCurve;
        private final float end, error;

        VisibleSpringInterpolator(TimeInterpolator curve) {
            nativeCurve = curve;
            // The native curve assumes a one-second timeline, including a long
            // settling tail. Compressing that whole timeline to 300 ms makes the
            // icons reach their destination in roughly 100 ms. Keep the visible
            // motion (including the first rebound), then remove only its <1% tail.
            final int samples = 128;
            int last = samples;
            while (last > 1 && Math.abs(curve.getInterpolation(last / (float) samples) - 1f) <= .01f) last--;
            end = Math.min(1f, (last + 1) / (float) samples);
            error = curve.getInterpolation(end) - 1f;
        }

        @Override public float getInterpolation(float input) {
            if (input <= 0f) return 0f;
            if (input >= 1f) return 1f;
            return nativeCurve.getInterpolation(input * end) - input * error;
        }
    }
    private LegacyFolderAnimationHooks() {}
}
