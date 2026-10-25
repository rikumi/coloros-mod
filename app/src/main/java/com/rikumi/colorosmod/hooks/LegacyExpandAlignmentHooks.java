package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Gravity;
import android.widget.LinearLayout;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;

final class LegacyExpandAlignmentHooks {
    private static final WeakHashMap<View, WeakReference<ViewTreeObserver.OnPreDrawListener>> listeners = new WeakHashMap<>();

    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.android.internal.widget.NotificationExpandButton",
                    lp.classLoader, "onFinishInflate", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) == 1) {
                                View button = (View) p.thisObject;
                                centerArrow(button);
                                attach(button);
                            }
                        }
                    });
        } catch (Throwable error) { log("legacy notification expand alignment hook failed: " + error); }
    }

    static void centerArrow(View button) {
        int arrowId = NotificationResourceIds.get(button.getResources(), "expand_button_icon", "id", "android");
        int pillId = NotificationResourceIds.get(button.getResources(), "expand_button_pill", "id", "android");
        View arrow = arrowId == 0 ? null : button.findViewById(arrowId);
        View pill = pillId == 0 ? null : button.findViewById(pillId);
        if (pill instanceof LinearLayout line)
            line.setGravity((line.getGravity() & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.CENTER_VERTICAL);
        if (pill != null && pill.getParent() instanceof LinearLayout line)
            line.setGravity((line.getGravity() & ~Gravity.VERTICAL_GRAVITY_MASK) | Gravity.CENTER_VERTICAL);
        if (arrow != null && arrow.getLayoutParams() instanceof LinearLayout.LayoutParams params
                && (params.topMargin != 0 || params.bottomMargin != 0 || params.gravity != Gravity.CENTER_VERTICAL)) {
            params.topMargin = 0;
            params.bottomMargin = 0;
            params.gravity = Gravity.CENTER_VERTICAL;
            arrow.setLayoutParams(params);
        }
    }

    static void attach(View button) {
        synchronized (listeners) {
            WeakReference<ViewTreeObserver.OnPreDrawListener> previous = listeners.get(button);
            if (previous != null && previous.get() != null) return;
            ViewTreeObserver.OnPreDrawListener listener = () -> {

                align(button);
                return true;
            };
            listeners.put(button, new WeakReference<>(listener));
            button.getViewTreeObserver().addOnPreDrawListener(listener);
        }
    }

    private static void align(View button) {

        if (readIntCached(KEY_NOTIFICATION_LAYOUT_STYLE, NOTIFICATION_LAYOUT_STYLE_DEFAULT) != 1) return;
        if (button.getParent() instanceof View header) hideTimeDivider(header);
        if (button.getWidth() == 0 || !(button.getParent() instanceof ViewGroup parent)) return;
        int id = NotificationResourceIds.get(parent.getResources(), "notification_top_line", "id", "android");
        View candidate = id == 0 ? null : parent.findViewById(id);
        if (!(candidate instanceof ViewGroup line) || line.getVisibility() != View.VISIBLE) return;
        int lineX = 0;
        View ancestor = line;
        while (ancestor != parent) {
            lineX += ancestor.getLeft();
            if (!(ancestor.getParent() instanceof View next)) return;
            ancestor = next;
        }
        int start = Integer.MAX_VALUE, end = Integer.MIN_VALUE;
        for (int i = 0; i < line.getChildCount(); i++) {
            View child = line.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0) continue;
            start = Math.min(start, lineX + child.getLeft());
            end = Math.max(end, lineX + child.getRight());
        }
        if (start == Integer.MAX_VALUE) return;
        int gap = Math.round(4 * button.getResources().getDisplayMetrics().density);
        int desired = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? Math.max(parent.getPaddingLeft(), start - gap - button.getWidth())
                : Math.min(parent.getWidth() - parent.getPaddingRight() - button.getWidth(), end + gap);
        float translation = desired - button.getLeft();

        if (button.getTranslationX() != translation) button.setTranslationX(translation);
    }

    static void hideTimeDivider(View root) {
        int id = NotificationResourceIds.get(root.getResources(), "time_divider", "id", "android");
        View divider = id == 0 ? null : root.findViewById(id);
        if (divider != null && divider.getVisibility() != View.GONE) divider.setVisibility(View.GONE);
    }

    static void cleanup() {
        synchronized (listeners) {
            for (var entry : listeners.entrySet()) {
                View button = entry.getKey();
                var listener = entry.getValue().get();
                if (listener != null && button.getViewTreeObserver().isAlive())
                    button.getViewTreeObserver().removeOnPreDrawListener(listener);
            }
            listeners.clear();
        }
    }
}
