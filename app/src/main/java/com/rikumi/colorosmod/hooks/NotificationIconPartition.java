package com.rikumi.colorosmod.hooks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

final class NotificationIconPartition {
    static <T> List<? extends T> leaves(T summary, List<? extends T> children) {
        return children.isEmpty() ? java.util.Collections.singletonList(summary) : children;
    }
    static final class Item<T> {
        final String key, owner;
        final boolean usable;
        final T value;
        Item(String key, String owner, boolean usable, T value) {
            this.key = key; this.owner = owner; this.usable = usable; this.value = value;
        }
    }
    static final class Result<T> {
        final List<T> icons = new ArrayList<>();
        int missing;
    }
    static <T> Result<T> partition(List<Item<T>> items) {
        Result<T> result = new Result<>();
        HashSet<String> keys = new HashSet<>(), owners = new HashSet<>();
        for (Item<T> item : items) {
            if (!keys.add(item.key)) continue;
            if (!item.usable) result.missing++;
            else if (owners.add(item.owner)) result.icons.add(item.value);
        }
        return result;
    }
}
