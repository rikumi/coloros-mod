package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import com.rikumi.colorosmod.xposed.*;
import java.util.function.BooleanSupplier;

final class PasswordDialSwipeHooks {
    private static final String STATE = "colorosmod.passwordDialSwipe";
    private PasswordDialSwipeHooks() {}

    static final class Gesture {
        float x, y;
        boolean allowed, fired;
    }

    static void hook(ClassLoader loader, BooleanSupplier dismiss) {
        Class<?> root = XposedHelpers.findClassIfExists(
                "com.coui.appcompat.passworddial.PasswordUnlockGestureLayout", loader);
        if (root == null) return;
        XposedHelpers.findAndHookDeclaredMethod(root, "dispatchTouchEvent", MotionEvent.class,
                new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                View host = (View) p.thisObject;
                MotionEvent event = (MotionEvent) p.args[0];
                int action = event.getActionMasked();
                Gesture state = (Gesture) XposedHelpers.getAdditionalInstanceField(host, STATE);
                if (action == MotionEvent.ACTION_DOWN) {
                    state = new Gesture();
                    state.x = event.getRawX(); state.y = event.getRawY();
                    state.allowed = readBool(KEY_KEYGUARD_BOUNCER_SWIPE_BACK_ENABLED, false)
                            && startAllowed(host, state.x, state.y);
                    XposedHelpers.setAdditionalInstanceField(host, STATE, state);
                }
                if (state == null || (!state.allowed && !state.fired)) return;
                if (state.fired) {
                    p.setResult(true);
                    return;
                }
                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    state.allowed = false;
                    // Leave the complete multi-touch stream to the native dial.
                    return;
                }
                if (action != MotionEvent.ACTION_MOVE) return;
                float dx = event.getRawX() - state.x, dy = event.getRawY() - state.y;
                float slop = ViewConfiguration.get(host.getContext()).getScaledTouchSlop();
                if (Math.abs(dx) > slop && Math.abs(dx) >= Math.abs(dy)) {
                    state.allowed = false;
                    return;
                }
                float remaining = host.getResources().getDisplayMetrics().heightPixels - state.y;
                float threshold = Math.max(slop, Math.min(
                        48 * host.getResources().getDisplayMetrics().density, remaining * .6f));
                if (dy < threshold || dy <= Math.abs(dx)) return;
                // Let native dispatch cancel pressed keys and any deferred panel gesture
                // before starting its normal back-to-lockscreen animation.
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                p.args[0] = cancel;
                p.setObjectExtra("dialCancel", cancel);
                p.setObjectExtra("dialDismiss", Boolean.TRUE);
                state.fired = true;
            }

            @Override protected void afterHookedMethod(MethodHookParam p) {
                View host = (View) p.thisObject;
                Gesture state = (Gesture) XposedHelpers.getAdditionalInstanceField(host, STATE);
                MotionEvent cancel = (MotionEvent) p.getObjectExtra("dialCancel");
                if (cancel != null) {
                    cancel.recycle();
                    if (!p.hasThrowable() && Boolean.TRUE.equals(p.getObjectExtra("dialDismiss")))
                        dismiss.getAsBoolean();
                    p.setResult(true);
                    return;
                }
                int action = ((MotionEvent) p.args[0]).getActionMasked();
                if (action == MotionEvent.ACTION_DOWN && state != null && state.allowed && !p.hasThrowable())
                    p.setResult(true); // Keep blank side cells as a touch target until the gesture ends.
                boolean finished = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
                if (host.getParent() != null && state != null)
                    host.getParent().requestDisallowInterceptTouchEvent(!finished && (state.allowed || state.fired));
                if (finished) XposedHelpers.removeAdditionalInstanceField(host, STATE);
            }
        });
        log("HOOK OK password dial dispatch (bouncer_swipe_back, ColorOS 17)");
    }

    static boolean startAllowed(View host, float x, float y) {
        int zeroId = host.getResources().getIdentifier("password_dial_key_31", "id", "com.android.systemui");
        int matrixId = host.getResources().getIdentifier("password_dial_matrix", "id", "com.android.systemui");
        if (zeroId == 0 || matrixId == 0) return false;
        View zero = host.findViewById(zeroId), matrix = host.findViewById(matrixId);
        if (zero == null || matrix == null || !zero.isEnabled() || !matrix.isEnabled()
                || !(matrix.getParent() instanceof View dial) || !dial.isEnabled()) return false;
        Rect zeroBounds = new Rect(), matrixBounds = new Rect();
        return zero.getGlobalVisibleRect(zeroBounds) && matrix.getGlobalVisibleRect(matrixBounds)
                && sideOfZero(zeroBounds, matrixBounds, x, y);
    }

    static boolean sideOfZero(Rect zero, Rect matrix, float x, float y) {
        return y >= zero.top && y < zero.bottom && x >= matrix.left && x < matrix.right
                && (x < zero.left || x >= zero.right);
    }
}
