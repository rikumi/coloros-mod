package com.rikumi.colorosmod.hooks;

final class ColorOsNavigationPolicy {
    static final String LAYER_NAME = "DecorNavBarBg-OverlayLayer";
    static final int FRAME_RATE = 101, FULLSCREEN_VARIANCE = 102, VARIANCE = 103;
    static final ColorOsNavigationPolicy DEFAULT = new ColorOsNavigationPolicy(100, 25, 10, 3000, 1500, 100);
    final int fullscreenVariance, variance, minFrameRate;
    final long startReduceTime, inputReduceTime, moveReduceTime;

    ColorOsNavigationPolicy(int fullscreenVariance, int variance, int minFrameRate,
            long startReduceTime, long inputReduceTime, long moveReduceTime) {
        this.fullscreenVariance = fullscreenVariance;
        this.variance = variance;
        this.minFrameRate = minFrameRate;
        this.startReduceTime = startReduceTime;
        this.inputReduceTime = inputReduceTime;
        this.moveReduceTime = moveReduceTime;
    }

    static int toArgb(int pixel) {
        // Native onColorPicked delivers ABGR; the framework swaps red and blue.
        return (pixel & 0xff00ff00) | ((pixel & 255) << 16) | ((pixel >>> 16) & 255);
    }

    static boolean hasAlpha(int pixel, float parentAlpha) {
        return (pixel >>> 24) != 255 || parentAlpha != 1f;
    }
}
