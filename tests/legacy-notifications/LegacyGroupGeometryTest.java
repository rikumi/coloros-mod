package com.rikumi.colorosmod.hooks;

public final class LegacyGroupGeometryTest {
    public static void main(String[] args) {
        int checks = 0;
        for (boolean first : new boolean[]{true, false}) {
            int collapsed = LegacyGroupGeometry.childTop(40, first, false, false, 0, 4, 2, 16);
            int expanded = LegacyGroupGeometry.childTop(40, first, true, false, 0, 4, 2, 16);
            for (int step = 0; step <= 100; step++) {
                float fraction = step / 100f;
                int top = LegacyGroupGeometry.childTop(40, first, false, true, fraction, 4, 2, 16);
                if (top < Math.min(collapsed, expanded) || top > Math.max(collapsed, expanded))
                    throw new AssertionError("Drag must interpolate continuously between endpoints");
                if (step == 0 && top != collapsed || step == 100 && top != expanded)
                    throw new AssertionError("Endpoint mismatch");
                checks++;
            }
        }
        for (int step = 0; step <= 100; step++) {
            float fraction = step / 100f;
            int first = LegacyGroupGeometry.childTop(40, true, false, true, fraction, 4, 2, 16);
            int second = LegacyGroupGeometry.childTop(first + 60, false, false, true, fraction, 4, 2, 16);
            if (second < first + 60) throw new AssertionError("Children must not collapse into one origin");
            checks++;
        }
        if (LegacyGroupGeometry.childTop(40, true, true, false, 0, 4, 2, 16) != 58)
            throw new AssertionError("Expanded first child retains original 48+8+divider offset");
        System.out.println("PASS: " + (checks + 1) + " legacy group geometry checks");
    }
}
