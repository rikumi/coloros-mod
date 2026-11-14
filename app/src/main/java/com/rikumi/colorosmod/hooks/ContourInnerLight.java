package com.rikumi.colorosmod.hooks;

import android.graphics.RuntimeShader;

final class ContourInnerLight {
    static void configure(RuntimeShader shader, float density, boolean night) {
        float unit = Math.max(1f, density) * .34f;
        shader.setFloatUniform("uGlowDepth", 86f * unit);
        // a5.e: reference size 600x600, scale 1.1x1.07, offset (0,10).
        shader.setFloatUniform("uGlowGeometry", 600f * .1f * unit / 2f,
                600f * .07f * unit / 2f, 10f * unit);
        // Requested overlay peaks: 15% in light mode, 6% in night mode.
        shader.setFloatUniform("uGlowAlpha", night ? .06f : .15f);
    }
}
