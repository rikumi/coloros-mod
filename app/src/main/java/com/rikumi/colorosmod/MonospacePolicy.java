package com.rikumi.colorosmod;

import android.graphics.Typeface;
import java.lang.reflect.Field;
import java.util.Locale;

public final class MonospacePolicy {
    private static final Field familyName;
    private static final Field derivedFrom;
    static {
        Field name = null, derived = null;
        try { name = Typeface.class.getDeclaredField("mSystemFontFamilyName"); name.setAccessible(true); }
        catch (ReflectiveOperationException ignored) {}
        try { derived = Typeface.class.getDeclaredField("mDerivedFrom"); derived.setAccessible(true); }
        catch (ReflectiveOperationException ignored) {}
        familyName = name; derivedFrom = derived;
    }
    public static boolean isMonospaceName(String name) {
        if (name == null) return false;
        switch (name.toLowerCase(Locale.ROOT)) {
            case "monospace": case "sans-serif-monospace": case "serif-monospace":
            case "monaco": case "courier": case "courier new": return true;
            default: return false;
        }
    }
    public static boolean isMonospace(Typeface typeface) {
        if (typeface == null) return false;
        if (typeface == Typeface.MONOSPACE) return true;
        try {
            for (int i = 0; i < 8 && typeface != null; i++) {
                if (familyName != null && isMonospaceName((String) familyName.get(typeface))) return true;
                typeface = derivedFrom == null ? null : (Typeface) derivedFrom.get(typeface);
            }
        } catch (IllegalAccessException ignored) {}
        return false;
    }
    private MonospacePolicy() {}
}
