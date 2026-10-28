package com.rikumi.colorosmod.graphics;

/** Catmull-Rom bicubic interpolation, with pixel-center alignment and clamped edges. */
public final class BicubicPixels {
    public static int[] resize(int[] pixels, int width, int height, int outWidth, int outHeight) {
        if (width <= 0 || height <= 0 || outWidth <= 0 || outHeight <= 0
                || (long) width * height != pixels.length)
            throw new IllegalArgumentException("Invalid bitmap dimensions");
        Axis horizontal = new Axis(width, outWidth);
        Axis vertical = new Axis(height, outHeight);
        // Interpolate premultiplied channels: transparent RGB must not bleed into edges.
        float[] rows = new float[Math.multiplyExact(Math.multiplyExact(outWidth, height), 4)];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < outWidth; x++) {
                int offset = (y * outWidth + x) * 4;
                for (int tap = 0; tap < 4; tap++) {
                    int color = pixels[y * width + horizontal.indices[x * 4 + tap]];
                    float weight = horizontal.weights[x * 4 + tap];
                    float alpha = color >>> 24;
                    rows[offset] += weight * alpha;
                    float premultipliedWeight = weight * alpha / 255f;
                    rows[offset + 1] += premultipliedWeight * ((color >> 16) & 255);
                    rows[offset + 2] += premultipliedWeight * ((color >> 8) & 255);
                    rows[offset + 3] += premultipliedWeight * (color & 255);
                }
            }
        }
        int[] result = new int[Math.multiplyExact(outWidth, outHeight)];
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                float alpha = 0, red = 0, green = 0, blue = 0;
                for (int tap = 0; tap < 4; tap++) {
                    int offset = (vertical.indices[y * 4 + tap] * outWidth + x) * 4;
                    float weight = vertical.weights[y * 4 + tap];
                    alpha += weight * rows[offset];
                    red += weight * rows[offset + 1];
                    green += weight * rows[offset + 2];
                    blue += weight * rows[offset + 3];
                }
                alpha = Math.max(0, Math.min(255, alpha));
                int a = Math.round(alpha);
                result[y * outWidth + x] = a == 0 ? 0 : (a << 24)
                        | (channel(red, alpha) << 16) | (channel(green, alpha) << 8)
                        | channel(blue, alpha);
            }
        }
        return result;
    }

    private static int channel(float value, float alpha) {
        return Math.round(Math.max(0, Math.min(alpha, value)) * 255f / alpha);
    }

    private static final class Axis {
        final int[] indices;
        final float[] weights;
        Axis(int input, int output) {
            indices = new int[Math.multiplyExact(output, 4)];
            weights = new float[indices.length];
            for (int i = 0; i < output; i++) {
                double position = (i + 0.5) * input / output - 0.5;
                int base = (int) Math.floor(position);
                for (int tap = 0; tap < 4; tap++) {
                    int index = base + tap - 1;
                    indices[i * 4 + tap] = Math.max(0, Math.min(input - 1, index));
                    weights[i * 4 + tap] = (float) cubic(Math.abs(position - index));
                }
            }
        }
    }

    // Keys cubic convolution kernel, a = -0.5 (Catmull-Rom).
    private static double cubic(double x) {
        if (x <= 1) return ((1.5 * x - 2.5) * x) * x + 1;
        if (x < 2) return ((-0.5 * x + 2.5) * x - 4) * x + 2;
        return 0;
    }

    private BicubicPixels() {}
}
