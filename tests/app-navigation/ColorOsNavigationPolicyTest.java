package com.rikumi.colorosmod.hooks;

public final class ColorOsNavigationPolicyTest {
    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError(Integer.toHexString(actual));
    }
    public static void main(String[] args) {
        equal(0xff123456, ColorOsNavigationPolicy.toArgb(0xff563412));
        equal(0x80102030, ColorOsNavigationPolicy.toArgb(0x80302010));
        equal(0, ColorOsNavigationPolicy.toArgb(0));
        equal(0xffffffff, ColorOsNavigationPolicy.toArgb(0xffffffff));
        equal(0xff000000, ColorOsNavigationPolicy.toArgb(0xff000000));
        equal(0xffff0000, ColorOsNavigationPolicy.toArgb(0xff0000ff));
        equal(0xff0000ff, ColorOsNavigationPolicy.toArgb(0xffff0000));
        for (int pixel : new int[]{0, 0x80102030, 0xffffffff, 0xff010203, 0x12345678}) {
            equal(pixel, ColorOsNavigationPolicy.toArgb(ColorOsNavigationPolicy.toArgb(pixel)));
        }
        if (ColorOsNavigationPolicy.hasAlpha(0xff000000, 1f)
                || !ColorOsNavigationPolicy.hasAlpha(0xff000000, .5f)
                || !ColorOsNavigationPolicy.hasAlpha(0x80000000, 1f)) throw new AssertionError("alpha semantics");
        ColorOsNavigationPolicy defaults = ColorOsNavigationPolicy.DEFAULT;
        equal(100, defaults.fullscreenVariance);
        equal(25, defaults.variance);
        equal(10, defaults.minFrameRate);
        equal(3000, (int) defaults.startReduceTime);
        equal(1500, (int) defaults.inputReduceTime);
        equal(100, (int) defaults.moveReduceTime);
        equal(101, ColorOsNavigationPolicy.FRAME_RATE);
        equal(102, ColorOsNavigationPolicy.FULLSCREEN_VARIANCE);
        equal(103, ColorOsNavigationPolicy.VARIANCE);
        System.out.println("PASS: ColorOS native pixel format, alpha, metadata and policy defaults");
    }
}
