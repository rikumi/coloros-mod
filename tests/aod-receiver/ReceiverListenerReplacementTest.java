package com.rikumi.colorosmod.hooks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class ReceiverListenerReplacementTest {
    static class Controller {}
    static final class Panoramic extends Controller {}
    private static final class Receiver {
        final List<Object> index = new ArrayList<>();
        final LinkedHashSet<Object> registered = new LinkedHashSet<>();
        void add(Object listener) { index.add(listener); registered.add(listener); }
        void remove(Object listener) { index.remove(listener); registered.remove(listener); }
        void replace(Object listener) {
            if (listener instanceof Controller)
                ReceiverListenerReplacement.removePrevious(index, Controller.class, this::remove);
            add(listener);
        }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Object time = new Object(), battery = new Object();
        Controller old = new Controller(), current = new Panoramic();
        // Firmware failure: remove index 0 while retaining the previous size of 2.
        List<Object> broken = new ArrayList<>(List.of(old, time));
        boolean reproduced = false;
        try {
            int size = broken.size();
            for (int i = 0; i < size; i++) {
                Object item = broken.get(i);
                if (item instanceof Controller) broken.remove(item);
            }
        } catch (IndexOutOfBoundsException expected) { reproduced = true; }
        require(reproduced, "firmware crash must reproduce");
        for (List<Object> initial : List.of(List.of(), List.of(old, time), List.of(time, old),
                List.of(old, time, new Panoramic(), battery), List.of(time, battery))) {
            Receiver receiver = new Receiver();
            initial.forEach(receiver::add);
            receiver.replace(current);
            require(receiver.index.stream().filter(Controller.class::isInstance).count() == 1, "one controller");
            require(receiver.registered.equals(new LinkedHashSet<>(receiver.index)), "native stores agree");
            List<Object> regular = initial.stream().filter(item -> !(item instanceof Controller)).toList();
            require(receiver.index.subList(0, receiver.index.size() - 1).equals(regular), "preserve other listeners and order");
            for (int repeat = 0; repeat < 100; repeat++) receiver.replace(current);
            require(receiver.index.size() == regular.size() + 1, "repeated starts do not accumulate");
            receiver.remove(current);
            require(receiver.index.equals(regular) && !receiver.registered.contains(current), "native stop unregisters");
        }
        System.out.println("PASS: firmware crash reproduced; 5 replacement scenarios, repeated starts and native stop");
    }
}
