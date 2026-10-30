package com.rikumi.colorosmod.hooks;

final class MergedLandscapeGeometry {
    static int contentWidth(int available, int displayWidth, int displayHeight) {
        return Math.max(1, Math.min(available, Math.min(displayWidth, displayHeight)));
    }
    static float verticalInsetScale(int width, int height) {
        if (width <= 0 || height <= 0) return 1f;
        return Math.min(width, height) / (float) Math.max(width, height);
    }

    static SplitPanels splitPanels(int available, float density, int outerMargin, int gap) {
        int width = Math.max(0, available);
        int margin = Math.min(Math.max(0, outerMargin), width / 2);
        int spacing = Math.min(Math.max(0, gap), width - 2 * margin);
        int maxQs = Math.max(1, Math.round(400f * density));
        int maxNotifications = Math.max(1, Math.round(540f * density));
        int content = Math.min(width - 2 * margin - spacing, maxQs + maxNotifications);
        int qsWidth = Math.round(content * (maxQs / (float) (maxQs + maxNotifications)));
        int notificationWidth = content - qsWidth;
        int left = (width - content - spacing) / 2;
        return new SplitPanels(left, notificationWidth, left + notificationWidth + spacing, qsWidth);
    }

    static SplitPanels splitPanels(int available, float density, int outerMargin, int gap,
                                   int safeLeft, int safeRight) {
        int left = Math.min(Math.max(0, safeLeft), Math.max(0, available));
        int right = Math.min(Math.max(0, safeRight), Math.max(0, available - left));
        SplitPanels panels = splitPanels(available - left - right, density, outerMargin, gap);
        return new SplitPanels(left + panels.notificationLeft(), panels.notificationWidth(),
                left + panels.qsLeft(), panels.qsWidth());
    }

    static float columnGapFraction(int value, boolean tablet, boolean split) {
        int clamped = Math.max(40, Math.min(60, value));
        int rounded = 40 + Math.round((clamped - 40) / 5f) * 5;
        return (rounded - (tablet && !split ? 20 : 0)) / 1000f;
    }

    static float alignedTopPadding(float cardTop, float stackTop, float scrimPadding) {
        return cardTop - stackTop - scrimPadding;
    }

    record SplitPanels(int notificationLeft, int notificationWidth, int qsLeft, int qsWidth) {
        int qsOffset() { return qsLeft - notificationLeft; }
        int groupWidth() { return qsOffset() + qsWidth; }
    }
}
