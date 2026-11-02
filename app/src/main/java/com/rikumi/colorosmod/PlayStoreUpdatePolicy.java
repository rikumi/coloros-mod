package com.rikumi.colorosmod;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Function;

/** Keep the Play Store update out of ColorOS's temporary factory-package recovery. */
public final class PlayStoreUpdatePolicy {
    public static final String KEY = "preserve_play_store_update";
    public static final String PROPERTY = "persist.sys.colorosmod.keep_play_update";
    public static final String PACKAGE = "com.android.vending";

    public static Set<String> filter(Set<String> packages, boolean enabled) {
        if (!enabled || packages == null || !packages.contains(PACKAGE)) return packages;
        Set<String> result = new LinkedHashSet<>(packages);
        result.remove(PACKAGE);
        return result;
    }

    /** Preserve native recovery shadows for other apps, but serialize Play's live setting. */
    public static <T> Collection<T> preferLivePlayStore(Collection<T> live, Collection<T> serialized,
                                                       Function<T, String> name) {
        T current = null, shadow = null;
        for (T item : live) if (PACKAGE.equals(name.apply(item))) { current = item; break; }
        for (T item : serialized) if (PACKAGE.equals(name.apply(item))) { shadow = item; break; }
        if (current == shadow) return serialized;
        Collection<T> result = new ArrayList<>();
        boolean replaced = false;
        for (T item : serialized) {
            if (PACKAGE.equals(name.apply(item))) {
                if (current != null && !replaced) result.add(current);
                replaced = true;
            } else result.add(item);
        }
        if (!replaced && current != null) result.add(current);
        return result;
    }

    private PlayStoreUpdatePolicy() {}
}
