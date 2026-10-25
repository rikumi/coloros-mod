package com.rikumi.colorosmod.hooks;

import android.graphics.Canvas;
import android.text.TextPaint;
import android.view.View;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import static com.rikumi.colorosmod.XposedInit.log;

final class NotificationBadgeGeometryHooks {
    private static final String BADGE = "com.oplus.systemui.statusbar.widget.NotificationBadgeView";
    private static final String[] FIELDS = {"mCircleRadius", "mRoundRectHeight", "mRoundRectWith",
            "mTextOffsetX", "mTextOffsetY"};
    private static final WeakHashMap<View, State> states = new WeakHashMap<>();

    private static final class State {
        final float[] values = new float[FIELDS.length];
        final int twoDigits;
        final float textSize;
        final LinearLayout.LayoutParams params;
        State(View view, LinearLayout.LayoutParams original) {
            for (int i = 0; i < FIELDS.length; i++) values[i] = XposedHelpers.getFloatField(view, FIELDS[i]);
            twoDigits = XposedHelpers.getIntField(view, "mTwoDigitsWidth");
            textSize = paint(view).getTextSize();
            params = new LinearLayout.LayoutParams(original);
        }
        void restore(View view) {
            for (int i = 0; i < FIELDS.length; i++) XposedHelpers.setFloatField(view, FIELDS[i], values[i]);
            XposedHelpers.setIntField(view, "mTwoDigitsWidth", twoDigits);
            paint(view).setTextSize(textSize);
            view.setLayoutParams(new LinearLayout.LayoutParams(params));
            view.invalidate();
        }
    }

    private static TextPaint paint(View view) {
        return (TextPaint) XposedHelpers.getObjectField(view, "mTextPaint");
    }

    static void update(View view, boolean enabled) {
        State state = states.get(view);
        if (!enabled) {
            if (state != null) { states.remove(view); state.restore(view); }
            return;
        }
        if (!(view.getLayoutParams() instanceof LinearLayout.LayoutParams params)) return;
        boolean fresh = state == null;
        if (fresh) { state = new State(view, params); states.put(view, state); }
        float scale = MonochromeIconGeometryHooks.drawingSize(view) / Math.max(1f, state.values[0] * 2f);
        for (int i = 0; i < FIELDS.length; i++) XposedHelpers.setFloatField(view, FIELDS[i], state.values[i] * scale);
        XposedHelpers.setIntField(view, "mTwoDigitsWidth", Math.round(state.twoDigits * scale));
        paint(view).setTextSize(state.textSize * scale);
        if (fresh || params.leftMargin != 0 || params.rightMargin != 0
                || params.getMarginStart() != 0 || params.getMarginEnd() != 0) {
            LinearLayout.LayoutParams own = new LinearLayout.LayoutParams(params);
            own.setMargins(0, 0, 0, 0); own.setMarginStart(0); own.setMarginEnd(0);
            view.setLayoutParams(own);
            view.invalidate();
        }
    }

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(BADGE, lp.classLoader, "onMeasure", int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            View view = (View) p.thisObject;
                            update(view, MissingNotificationCountHooks.hybridBadge(view));
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            View view = (View) p.thisObject;
                            if (!states.containsKey(view)) return;
                            int shapeWidth = XposedHelpers.getIntField(view, "mWidth");
                            int diameter = (int) (XposedHelpers.getFloatField(view, "mCircleRadius") * 2f);
                            XposedHelpers.callMethod(view, "setMeasuredDimension",
                                    MonochromeIconGeometryHooks.cellWidth(view) + Math.max(0, shapeWidth - diameter),
                                    view.getMeasuredHeight());
                        }
                    });
            XposedHelpers.findAndHookMethod(BADGE, lp.classLoader, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    if (!states.containsKey(view)) return;
                    Canvas canvas = (Canvas) p.args[0];
                    p.setObjectExtra("badgeGeometrySave", canvas.save());
                    canvas.translate((view.getWidth() - XposedHelpers.getIntField(view, "mWidth")) / 2f, 0f);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getObjectExtra("badgeGeometrySave") instanceof Integer save)
                        ((Canvas) p.args[0]).restoreToCount(save);
                }
            });
            XposedHelpers.findAndHookMethod(BADGE, lp.classLoader, "reloadDimens$4", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View view = (View) p.thisObject;
                    State old = states.get(view);
                    if (old == null) return;
                    states.put(view, new State(view, old.params));
                    update(view, MissingNotificationCountHooks.hybridBadge(view));
                    view.requestLayout(); view.invalidate();
                }
            });
        } catch (Throwable error) { log("notification badge geometry hook failed: " + error); }
    }

    static void cleanup() {
        for (View view : new ArrayList<>(states.keySet())) {
            State state = states.remove(view);
            if (state != null) state.restore(view);
        }
    }
}
