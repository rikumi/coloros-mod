package com.rikumi.colorosmod.hooks;

/** Ignore sparse controls and use the dominant opaque color along the app's bottom edge. */
final class NavigationEdgeColor {
    static Integer pick(int[] pixels) {
        int[] counts = new int[4096];
        long[] red = new long[4096], green = new long[4096], blue = new long[4096];
        int winner = -1;
        for (int pixel : pixels) {
            if ((pixel >>> 24) != 255) continue;
            int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
            int bin = ((r >>> 4) << 8) | ((g >>> 4) << 4) | (b >>> 4);
            counts[bin]++;
            red[bin] += r; green[bin] += g; blue[bin] += b;
            if (winner < 0 || counts[bin] > counts[winner]) winner = bin;
        }
        if (winner < 0) return null;
        return 0xff000000 | ((int) (red[winner] / counts[winner]) << 16)
                | ((int) (green[winner] / counts[winner]) << 8) | (int) (blue[winner] / counts[winner]);
    }
}
