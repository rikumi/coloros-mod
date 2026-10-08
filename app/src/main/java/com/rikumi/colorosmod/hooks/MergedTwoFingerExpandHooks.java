package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Detect the complete shade gesture, then use native QS expansion and collapse states. */
final class MergedTwoFingerExpandHooks {
    private static final WeakHashMap<Object, Gesture> gestures = new WeakHashMap<>();

    static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        try {
            XposedHelpers.findAndHookDeclaredMethod(
                    "com.android.systemui.shade.NotificationPanelViewController$TouchHandler", pkg.classLoader,
                    "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            MotionEvent event = (MotionEvent) p.args[0];
                            Object panel = XposedHelpers.getObjectField(p.thisObject, "this$0");
                            Object qs = XposedHelpers.getObjectField(panel, "mQsController");
                            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                gestures.remove(p.thisObject);
                                if (!readBool(KEY_QS_MERGED_TWO_FINGER_EXPAND, false)
                                        || (Boolean) XposedHelpers.callMethod(qs, "isSeparateQSEnable")
                                        || XposedHelpers.getIntField(qs, "mBarState") != 0
                                        || !(Boolean) XposedHelpers.callMethod(panel, "isFullyCollapsed")
                                        || !(Boolean) XposedHelpers.callMethod(qs, "isExpansionEnabled")
                                        || event.getY() >= XposedHelpers.getIntField(qs, "mStatusBarMinHeight")) return;
                                View view = (View) XposedHelpers.getObjectField(panel, "mView");
                                gestures.put(p.thisObject, new Gesture(event.getY(),
                                        ViewConfiguration.get(view.getContext()).getScaledTouchSlop()));
                            }
                            Gesture gesture = gestures.get(p.thisObject);
                            if (gesture == null) return;
                            if (event.getPointerCount() == 2) gesture.twoFingers = true;
                            if (!gesture.expanding && gesture.twoFingers
                                    && event.getActionMasked() == MotionEvent.ACTION_MOVE
                                    && event.getY() - gesture.startY > gesture.slop) {
                                gesture.expanding = true;
                                XposedHelpers.callMethod(qs, "setExpandImmediate", true);
                                XposedHelpers.callMethod(qs, "setListening", true);
                            }
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            MotionEvent event = (MotionEvent) p.args[0];
                            int action = event.getActionMasked();
                            if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return;
                            Gesture gesture = gestures.remove(p.thisObject);
                            if (gesture == null || !gesture.expanding) return;
                            Object panel = XposedHelpers.getObjectField(p.thisObject, "this$0");
                            Object qs = XposedHelpers.getObjectField(panel, "mQsController");
                            XposedHelpers.callMethod(qs, "setExpandImmediate", false);
                            // Use the native full-QS target rather than a persistent override;
                            // the following upward gesture can still fling to the normal minimum.
                            if (action == MotionEvent.ACTION_UP
                                    && !(Boolean) XposedHelpers.callMethod(panel, "isFullyCollapsed")
                                    && ((Number) XposedHelpers.callMethod(qs, "computeExpansionFraction")).floatValue() < 1f) {
                                android.animation.Animator animator = (android.animation.Animator)
                                        XposedHelpers.getObjectField(qs, "mExpansionAnimator");
                                if (animator != null) {
                                    if (XposedHelpers.getBooleanField(qs, "mAnimatorExpand")) return;
                                    animator.cancel();
                                }
                                XposedHelpers.callMethod(qs, "flingQs", 0f, 0, null, false);
                            }
                        }
                    });
        } catch (Throwable t) { log("merged two finger expand hook failed: " + t); }
    }
    private static final class Gesture {
        final float startY;
        final int slop;
        boolean twoFingers, expanding;
        Gesture(float startY, int slop) { this.startY = startY; this.slop = slop; }
    }
}
