package com.rikumi.colorosmod.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Remove through the receiver API so its registration set and index list stay aligned. */
final class ReceiverListenerReplacement {
    static <T> void removePrevious(List<T> listeners, Class<?> type, Consumer<T> remove) {
        for (T listener : new ArrayList<>(listeners)) {
            if (type.isInstance(listener)) remove.accept(listener);
        }
    }
}
