package com.rikumi.colorosmod.hooks;

final class LegacyGroupGeometry {
    // notification_header_height from the supplied Android 15 template is 46dp.
    static final int COLLAPSED_HEADER_DP = 46;
    static final int COLLAPSED_BODY_DP = 40;
    static final int EXPANDED_BODY_DP = 56;
    static int headerHeight(float progress, float density) {
        float p = Math.max(0f, Math.min(1f, progress));
        return Math.round((COLLAPSED_HEADER_DP + (EXPANDED_BODY_DP - COLLAPSED_HEADER_DP) * p) * density);
    }
    static int childTop(int previousBottom, boolean first, boolean expanded, boolean dragging,
            float fraction, int gap, int divider, int extra) {
        int collapsedGap = first ? 0 : gap;
        int expandedGap = first ? extra + divider : divider;
        return previousBottom + (dragging ? (int) (collapsedGap + (expandedGap - collapsedGap) * fraction)
                : expanded ? expandedGap : collapsedGap);
    }
}
