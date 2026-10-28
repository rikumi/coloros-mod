package com.rikumi.colorosmod.hooks;

import com.rikumi.colorosmod.R;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolve the Mod background independently of locale and marketing OS labels. */
public final class AboutCardStyleResolver {
    private static final Pattern VERSION = Pattern.compile("(?<!\\d)(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    public static int resolve(String area, String brand, String... versions) {
        // Settings' OOSUtils.isOHExportVersion uses precisely these two image properties.
        boolean oxygen = "gdpr".equalsIgnoreCase(area) && "oneplus".equalsIgnoreCase(brand);
        for (String version : versions) {
            if (version == null) continue;
            Matcher match = VERSION.matcher(version);
            if (!match.find()) continue;
            try {
                int major = Integer.parseInt(match.group(1));
                int minor = Integer.parseInt(match.group(2));
                int patch = match.group(3) == null ? 0 : Integer.parseInt(match.group(3));
                if (major < 15) continue;
                if (major == 15) return oxygen ? 5 : 2;
                // User-defined 16.1 boundary, including versions reported as 16.0.7.
                if (major > 16 || minor > 0 || patch >= 7) return 6;
                return oxygen ? 3 : 1;
            } catch (NumberFormatException ignored) { }
        }
        return oxygen ? 3 : 1;
    }

    public static int backgroundResource(int style, boolean tabletLandscape) {
        return switch (style) {
            case 9 -> tabletLandscape ? R.drawable.about_card_coloros17_land : R.drawable.about_card_coloros17;
            case 2 -> R.drawable.about_card_coloros15;
            case 3 -> tabletLandscape ? R.drawable.about_card_oxygenos_land : R.drawable.about_card_oxygenos;
            case 5 -> R.drawable.about_card_oxygenos15;
            case 7 -> R.drawable.about_card_realme15;
            case 8 -> tabletLandscape ? R.drawable.about_card_realme16_land : R.drawable.about_card_realme16;
            case 6 -> tabletLandscape ? R.drawable.about_card_coloros161_land : R.drawable.about_card_coloros161;
            default -> tabletLandscape ? R.drawable.about_card_coloros16_land : R.drawable.about_card_coloros16;
        };
    }

    public static int phoneBrandResource(String brand) {
        if ("oneplus".equalsIgnoreCase(brand)) return R.drawable.about_card_phone_oneplus;
        if ("realme".equalsIgnoreCase(brand)) return R.drawable.about_card_phone_realme;
        return R.drawable.about_card_phone_oppo;
    }

    public static String realmeVersion(String... versions) {
        for (String version : versions) {
            if (version == null) continue;
            Matcher match = VERSION.matcher(version);
            if (!match.find()) continue;
            try {
                int major = Integer.parseInt(match.group(1));
                if (major >= 15) return (major - 9) + ".0";
            } catch (NumberFormatException ignored) { }
        }
        return versions.length == 0 ? "" : versions[versions.length - 1];
    }

    private AboutCardStyleResolver() {}
}
