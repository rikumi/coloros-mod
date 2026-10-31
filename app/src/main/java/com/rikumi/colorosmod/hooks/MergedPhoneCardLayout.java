package com.rikumi.colorosmod.hooks;

import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.WeakHashMap;
import com.rikumi.colorosmod.xposed.XposedHelpers;

/** 分栏面板采用手机端的两列半宽卡片，每列占两个网格单位。 */
final class MergedPhoneCardLayout {
    private static final WeakHashMap<ViewGroup, State> states = new WeakHashMap<>();

    static void update(ViewGroup root, int measuredWidth) {
        if (!MergedSplitShadeHooks.phoneLayout(root)) {
            restore(root);
            return;
        }
        View first = field(root, "mFirstTileContainer"), second = field(root, "mSecondTileContainer");
        View media = field(root, "mQsMediaPanelContainer"), sliders = field(root, "mQsSeekBarContainer");
        int guide = NotificationResourceIds.get(root.getResources(), "guide_line", "id", "com.android.systemui");
        if (guide == 0 || first == null || second == null || media == null || sliders == null
                || first.getParent() != root || second.getParent() != root || media.getParent() != root
                || sliders.getParent() != root || sliders.getVisibility() == View.GONE) {
            restore(root);
            return;
        }
        int width = measuredWidth - root.getPaddingLeft() - root.getPaddingRight();
        if (width <= 0) return;
        int gap = Math.round(width * MergedCardRatioHooks.gapFraction(root));
        boolean playing = Boolean.TRUE.equals(XposedHelpers.callMethod(root, "getMediaMode"));
        State state = states.get(root);
        if (state == null) {
            state = new State(root, media);
            states.put(root, state);
        }
        int visibility = playing ? View.VISIBLE : View.GONE;
        if (media.getVisibility() != visibility) media.setVisibility(visibility);
        if (state.width == width && state.gap == gap && state.playing == playing) return;
        Object set = XposedHelpers.newInstance(state.original.getClass());
        XposedHelpers.callMethod(set, "clone", root);
        XposedHelpers.callMethod(set, "setGuidelinePercent", guide, .5f);
        for (View card : new View[]{first, second, media, sliders}) {
            int target = card.getId();
            for (int side = 1; side <= 7; side++) XposedHelpers.callMethod(set, "clear", target, side);
            XposedHelpers.callMethod(set, "constrainWidth", target, 0);
            Object layout = XposedHelpers.getObjectField(XposedHelpers.callMethod(set, "getConstraint", target), "layout");
            XposedHelpers.setIntField(layout, "widthDefault", 0);
            XposedHelpers.setObjectField(layout, "dimensionRatio", null);
        }
        half(set, first, guide, false, gap);
        half(set, second, guide, playing, gap);
        half(set, media, guide, false, gap);
        half(set, sliders, guide, true, gap);
        connect(set, media, 3, null, 3, 0);
        connect(set, first, 3, playing ? media : null, playing ? 4 : 3, playing ? gap : 0);
        connect(set, second, 3, first, playing ? 3 : 4, playing ? 0 : gap);
        connect(set, sliders, 3, playing ? media : first, 3, 0);
        connect(set, sliders, 4, playing ? media : second, 4, 0);
        apply(set, root);
        state.width = width;
        state.gap = gap;
        state.playing = playing;
    }

    private static void half(Object set, View card, int guide, boolean end, int gap) {
        int target = card.getId();
        XposedHelpers.callMethod(set, "connect", target, 6, end ? guide : 0, end ? 7 : 6,
                end ? gap - gap / 2 : 0);
        XposedHelpers.callMethod(set, "connect", target, 7, end ? 0 : guide, end ? 7 : 6,
                end ? 0 : gap / 2);
    }

    private static void connect(Object set, View card, int side, View anchor, int anchorSide, int margin) {
        XposedHelpers.callMethod(set, "connect", card.getId(), side, anchor == null ? 0 : anchor.getId(), anchorSide, margin);
    }

    private static View field(Object host, String name) {
        return (View) XposedHelpers.getObjectField(host, name);
    }

    // 只更新布局，保持系统当前的 alpha、缩放、平移和通知展开动画。
    private static void apply(Object set, ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            Object constraint = XposedHelpers.callMethod(set, "getConstraint", child.getId());
            if (constraint == null) continue;
            ViewGroup.LayoutParams params = child.getLayoutParams();
            XposedHelpers.callMethod(constraint, "applyTo", params);
            child.setLayoutParams(params);
        }
    }

    static boolean manages(View view) {
        return view.getParent() instanceof ViewGroup root && states.containsKey(root)
                && MergedSplitShadeHooks.phoneLayout(root);
    }

    static void restore(ViewGroup root) {
        State state = states.remove(root);
        if (state == null) return;
        apply(state.original, root);
        View media = field(root, "mQsMediaPanelContainer");
        if (media != null) media.setVisibility(state.mediaVisibility);
    }

    static void cleanup() {
        for (ViewGroup root : new ArrayList<>(states.keySet())) restore(root);
    }

    private static final class State {
        final Object original;
        final int mediaVisibility;
        int width = -1, gap;
        boolean playing;
        State(ViewGroup root, View media) {
            Class<?> type = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintSet", root.getClass().getClassLoader());
            original = XposedHelpers.newInstance(type);
            XposedHelpers.callMethod(original, "clone", root);
            mediaVisibility = media.getVisibility();
        }
    }
}
