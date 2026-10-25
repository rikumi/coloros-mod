package com.rikumi.colorosmod.hooks;

final class NotificationIconMask {
    static boolean accepts(int[] pixels) {
        int occupied = 0, transparent = 0;
        int minR = 255, minG = 255, minB = 255, maxR = 0, maxG = 0, maxB = 0;
        boolean gray = true;
        for (int pixel : pixels) {
            int alpha = pixel >>> 24;
            if (alpha < 16) { transparent++; continue; }
            if (alpha < 128) continue;
            int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
            gray &= Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 12;
            minR = Math.min(minR, r); maxR = Math.max(maxR, r);
            minG = Math.min(minG, g); maxG = Math.max(maxG, g);
            minB = Math.min(minB, b); maxB = Math.max(maxB, b);
            occupied++;
        }
        return occupied >= 4 && transparent >= pixels.length / 10
                && (gray || (maxR - minR <= 12 && maxG - minG <= 12 && maxB - minB <= 12));
    }
}
