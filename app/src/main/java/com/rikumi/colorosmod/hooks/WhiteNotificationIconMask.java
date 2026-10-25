package com.rikumi.colorosmod.hooks;

final class WhiteNotificationIconMask {
    static int[] extract(int[] source) {
        int[] result = new int[source.length];
        int visible = 0;
        int[] background = background(source);
        for (int i = 0; i < source.length; i++) {
            int p = source[i], a = p >>> 24;
            int r = (p >>> 16) & 255, g = (p >>> 8) & 255, b = p & 255;
            int min = Math.min(r, Math.min(g, b)), max = Math.max(r, Math.max(g, b));
            if (a == 0) continue;
            int alpha;
            if (background != null) {

                int channel = background[0] <= background[1] && background[0] <= background[2] ? 0
                        : background[1] <= background[2] ? 1 : 2;
                int value = channel == 0 ? r : channel == 1 ? g : b;
                float coverage = Math.max(0f, Math.min(1f, (value - background[channel]) / (255f - background[channel])));
                if (coverage <= .02f) continue;
                if (Math.abs(r - (background[0] + (255 - background[0]) * coverage)) > 24
                        || Math.abs(g - (background[1] + (255 - background[1]) * coverage)) > 24
                        || Math.abs(b - (background[2] + (255 - background[2]) * coverage)) > 24) continue;
                alpha = Math.round(a * coverage);
            } else {
                if (min < 200 || max - min >= 32) continue;
                alpha = Math.round(a * ((min - 192) / 63f) * (1f - (max - min) / 32f));
            }
            alpha = Math.max(0, Math.min(255, alpha));
            result[i] = (alpha << 24) | 0xffffff;
            if (alpha >= 24) visible++;
        }
        return visible < Math.max(8, source.length / 200) || visible > source.length * .65f ? null : result;
    }
    private static int[] background(int[] source) {
        int[] bins = new int[32768];
        int best = -1, count = 0;
        for (int p : source) {
            if ((p >>> 24) < 240) continue;
            int r = (p >>> 16) & 255, g = (p >>> 8) & 255, b = p & 255;
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 40) continue;
            int bin = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
            if (++bins[bin] > count) { best = bin; count = bins[bin]; }
        }
        if (count < Math.max(8, source.length / 10)) return null;
        long r = 0, g = 0, b = 0; int n = 0;
        for (int p : source) {
            if ((p >>> 24) < 240) continue;
            int red = (p >>> 16) & 255, green = (p >>> 8) & 255, blue = p & 255;
            if ((((red >> 3) << 10) | ((green >> 3) << 5) | (blue >> 3)) != best) continue;
            r += red; g += green; b += blue; n++;
        }
        return new int[]{(int) (r / n), (int) (g / n), (int) (b / n)};
    }

}
