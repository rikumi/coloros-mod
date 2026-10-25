package com.rikumi.colorosmod.hooks;

final class MergedLandscapeGeometry {
    static int contentWidth(int available, int displayWidth, int displayHeight) {
        return Math.max(1, Math.min(available, Math.min(displayWidth, displayHeight)));
    }
    static float verticalInsetScale(int width, int height) {
        if (width <= 0 || height <= 0) return 1f;
        return Math.min(width, height) / (float) Math.max(width, height);
    }
}

