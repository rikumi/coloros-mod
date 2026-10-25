package com.rikumi.colorosmod.hooks;

import java.util.function.ObjIntConsumer;

final class NavigationCaptureCallback implements ObjIntConsumer<Object> {
    interface Delivery {
        boolean deliver(NavigationCaptureCallback request, Object buffer, int status);
    }

    private Delivery delivery;
    private final java.util.function.Consumer<Object> discard;

    NavigationCaptureCallback(Delivery delivery, java.util.function.Consumer<Object> discard) {
        this.delivery = delivery;
        this.discard = discard;
    }

    @Override public synchronized void accept(Object buffer, int status) {
        Delivery current = delivery;
        delivery = null;

        boolean accepted = false;
        try {
            accepted = current != null && current.deliver(this, buffer, status);
        } finally {
            if (!accepted) discard.accept(buffer);
        }
    }

    synchronized void cancel() {
        // The native listener may live longer, but must retain no View, effect or worker.
        delivery = null;
    }
}
