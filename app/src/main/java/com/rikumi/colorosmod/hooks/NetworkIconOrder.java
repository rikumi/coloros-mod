package com.rikumi.colorosmod.hooks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reorders network entries in physical left-to-right order, preserving widths and gaps. */
final class NetworkIconOrder {
    static final int OTHER = 0, WIFI = 1, MOBILE = 2;

    static float[] positions(float[] x, float[] widths, int[] kinds, int mode) {
        float[] result = x.clone();
        if (mode != WIFI && mode != MOBILE) return result;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < x.length; i++) order.add(i);
        order.sort(Comparator.comparingDouble(i -> x[i]));
        List<Integer> networks = new ArrayList<>();
        boolean wifi = false, mobile = false;
        for (int index : order) {
            if (kinds[index] == OTHER) continue;
            networks.add(index);
            wifi |= kinds[index] == WIFI;
            mobile |= kinds[index] == MOBILE;
        }
        if (!wifi || !mobile) return result;
        // Stable sorting retains the native order of the two SIMs, including RTL layouts.
        networks.sort(Comparator.comparingInt(i -> kinds[i] == mode ? 0 : 1));
        float next = x[order.get(0)];
        int networkIndex = 0;
        for (int i = 0; i < order.size(); i++) {
            int original = order.get(i);
            int target = kinds[original] == OTHER ? original : networks.get(networkIndex++);
            result[target] = next;
            next += widths[target];
            if (i + 1 < order.size()) {
                next += x[order.get(i + 1)] - x[original] - widths[original];
            }
        }
        return result;
    }
}
