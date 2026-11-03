package com.rikumi.colorosmod.hooks;

import java.util.Set;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntUnaryOperator;

/** A completion barrier, not an estimate based on the dismissal animation duration. */
final class RecentsKillCompletion {
    static void await(Set<Thread> workers, long timeoutMillis)
            throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            Thread[] snapshot;
            synchronized (workers) { snapshot = workers.toArray(new Thread[0]); }
            if (snapshot.length == 0) return;
            for (Thread worker : snapshot) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("Recents process termination timed out");
                // Never hold the registry lock while joining: workers remove themselves in finally.
                worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
        }
    }

    static void awaitProcesses(Map<Integer, Integer> processes, IntUnaryOperator uidForPid,
            long timeoutMillis) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!processes.isEmpty()) {
            processes.entrySet().removeIf(process -> uidForPid.applyAsInt(process.getKey()) != process.getValue());
            if (processes.isEmpty()) return;
            if (System.nanoTime() >= deadline)
                throw new TimeoutException("Recents target process still exists");
            Thread.sleep(25L);
        }
    }

    private RecentsKillCompletion() {}
}
