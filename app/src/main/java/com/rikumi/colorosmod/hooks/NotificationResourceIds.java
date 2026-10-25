package com.rikumi.colorosmod.hooks;

import android.content.res.Resources;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

final class NotificationResourceIds {
    private static final WeakHashMap<Resources, Map<String, Integer>> caches = new WeakHashMap<>();
    static synchronized int get(Resources resources, String name, String type, String owner) {
        Map<String, Integer> cache = caches.computeIfAbsent(resources, ignored -> new HashMap<>());
        String key = owner + ':' + type + '/' + name;
        Integer value = cache.get(key);
        if (value == null) {
            value = resources.getIdentifier(name, type, owner);
            cache.put(key, value);
        }
        return value;
    }
}
