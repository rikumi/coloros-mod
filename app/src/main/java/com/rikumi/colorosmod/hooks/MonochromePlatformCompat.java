package com.rikumi.colorosmod.hooks;

import com.rikumi.colorosmod.xposed.XposedHelpers;

/** Resolve optional chip and descriptor factories without rejecting legacy notification icons. */
final class MonochromePlatformCompat {
    static Object chipIcon(Object pack) {
        try { return XposedHelpers.callMethod(pack, "getStatusBarChipIcon"); }
        catch (NoSuchMethodError absent) { return null; }
    }

    static Object descriptor(Object manager, Object entry, Object icon, Object original) {
        Class<?> type = XposedHelpers.findClass("com.android.internal.statusbar.StatusBarIcon$Type",
                manager.getClass().getClassLoader());
        Object small = XposedHelpers.getStaticObjectField(type, "NotifSmallIcon");
        try { return XposedHelpers.callMethod(manager, "toStatusBarIcon", icon, entry, small); }
        catch (NoSuchMethodError legacy) {
            if (original == null) return null;
            Object copy = XposedHelpers.callMethod(original, "clone");
            XposedHelpers.setObjectField(copy, "icon", icon);
            XposedHelpers.setObjectField(copy, "type", small);
            return copy;
        }
    }
}
