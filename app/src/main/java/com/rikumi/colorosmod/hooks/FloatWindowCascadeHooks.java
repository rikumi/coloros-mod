package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.*;

import android.content.Context;
import android.graphics.Rect;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.List;

/** 补充旧版多小窗布局的纵向错位；保留系统的横向避让及全景布局。 */
final class FloatWindowCascadeHooks {
    static void hook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod("com.android.server.wm.OplusFlexibleTaskLayoutPolicy", lp.classLoader,
                    "avoidOverlappingAreas", Rect.class, ArrayList.class, int.class, float.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.hasThrowable() || !readBoolCached(KEY_FLOAT_WINDOW_COUNT_ENABLED, false)) return;
                            try {
                                Rect bounds = (Rect) p.args[0];
                                List<?> existing = (List<?>) p.args[1];
                                float scale = (Float) p.args[3];
                                if (bounds == null || bounds.isEmpty() || existing == null || existing.isEmpty()
                                        || !Float.isFinite(scale) || scale <= 0) return;
                                Rect screen = (Rect) XposedHelpers.getObjectField(p.thisObject, "mWindowBounds");
                                Rect status = (Rect) XposedHelpers.getObjectField(p.thisObject, "mStatusBarFrame");
                                Rect navigation = (Rect) XposedHelpers.getObjectField(p.thisObject, "mNaviBarFrame");
                                Context context = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                                int gap = Math.max(1, Math.round(32 * context.getResources().getDisplayMetrics().density));
                                int minTop = Math.max(screen.top, status.bottom);
                                int bottom = screen.bottom;
                                // 横屏导航栏可能位于侧边，不能将其 top 当作底部边界。
                                if (!navigation.isEmpty() && navigation.width() >= screen.width() / 2
                                        && navigation.bottom >= screen.bottom) bottom = Math.min(bottom, navigation.top);
                                int maxTop = bottom - Math.round(bounds.height() * scale);
                                List<Integer> tops = new ArrayList<>();
                                int right = bounds.left + Math.round(bounds.width() * scale);
                                for (Object item : existing) {
                                    if (item instanceof Rect other && !other.isEmpty()
                                            && bounds.left < other.right && right > other.left) tops.add(other.top);
                                }
                                int top = findTop(bounds.top, minTop, maxTop, gap, tops);
                                bounds.offsetTo(bounds.left, top);
                            } catch (Throwable t) { log("float window cascade layout failed: " + t); }
                        }
                    });
        } catch (Throwable t) { log("float window cascade hook unavailable: " + t); }
    }

    // 无可用位置时保留原生位置；已有纵向错位的窗口不再移动。
    static int findTop(int original, int min, int max, int gap, List<Integer> tops) {
        if (gap <= 0 || min > max || original < min || original > max) return original;
        int candidate = nextFreeTop(original, max, gap, tops);
        if (candidate <= max) return candidate;
        candidate = nextFreeTop(min, max, gap, tops);
        return candidate <= max ? candidate : original;
    }

    private static int nextFreeTop(int start, int max, int gap, List<Integer> tops) {
        int top = start;
        for (int attempt = 0; attempt <= tops.size() && top <= max; attempt++) {
            int next = top;
            for (int occupied : tops) if (Math.abs((long) occupied - top) < gap)
                next = Math.max(next, occupied + gap);
            if (next == top) return top;
            top = next;
        }
        return top;
    }
}
