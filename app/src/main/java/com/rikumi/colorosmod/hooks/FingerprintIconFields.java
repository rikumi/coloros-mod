package com.rikumi.colorosmod.hooks;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** The dark artwork cache exists only on some SystemUI versions. */
final class FingerprintIconFields {
    private final List<Field> fields = new ArrayList<>();

    FingerprintIconFields(Class<?> type) {
        for (String name : new String[]{"imMobileDrawable", "imMobileDrawableDark"}) {
            for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
                try {
                    Field field = owner.getDeclaredField(name);
                    field.setAccessible(true);
                    fields.add(field);
                    break;
                } catch (NoSuchFieldException absent) { /* Optional on this ROM. */ }
            }
        }
    }

    void fillMissing(Object owner, Supplier<?> placeholder) throws IllegalAccessException {
        for (Field field : fields) if (field.get(owner) == null) field.set(owner, placeholder.get());
    }

    void clearMatching(Object owner, Predicate<Object> isPlaceholder) throws IllegalAccessException {
        for (Field field : fields) if (isPlaceholder.test(field.get(owner))) field.set(owner, null);
    }

    Object first(Object owner) throws IllegalAccessException {
        for (Field field : fields) {
            Object value = field.get(owner);
            if (value != null) return value;
        }
        return null;
    }
}
