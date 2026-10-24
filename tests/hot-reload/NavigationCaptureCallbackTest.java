package com.rikumi.colorosmod.hooks;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class NavigationCaptureCallbackTest {
    private static int checks;
    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger delivered = new AtomicInteger(), disposed = new AtomicInteger();
        NavigationCaptureCallback cancelled = new NavigationCaptureCallback((r, b, s) -> {
            delivered.incrementAndGet(); return true;
        }, b -> disposed.incrementAndGet());
        cancelled.cancel();
        cancelled.accept(new Object(), 0);
        check(delivered.get() == 0, "Late native result cannot reach retired state");
        check(disposed.get() == 1, "Late native buffer is released");
        var field = NavigationCaptureCallback.class.getDeclaredField("delivery");
        field.setAccessible(true);
        check(field.get(cancelled) == null, "Cancellation drops owner closure");

        NavigationCaptureCallback rejected = new NavigationCaptureCallback((r, b, s) -> false,
                b -> disposed.incrementAndGet());
        rejected.accept(new Object(), 0);
        check(disposed.get() == 2, "Rejected worker enqueue releases buffer");
        NavigationCaptureCallback throwing = new NavigationCaptureCallback((r, b, s) -> {
            throw new IllegalStateException("Worker unavailable");
        }, b -> disposed.incrementAndGet());
        try { throwing.accept(new Object(), 0); throw new AssertionError("Exception lost"); }
        catch (IllegalStateException expected) { }
        check(disposed.get() == 3, "Enqueue exception releases buffer");

        var worker = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < 100; i++) {
                CountDownLatch enqueued = new CountDownLatch(1), release = new CountDownLatch(1);
                AtomicInteger work = new AtomicInteger(), discard = new AtomicInteger();
                NavigationCaptureCallback request = new NavigationCaptureCallback((r, b, s) -> {
                    enqueued.countDown();
                    try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("Release timeout"); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    worker.execute(work::incrementAndGet);
                    return true;
                }, b -> discard.incrementAndGet());
                Thread nativeCallback = new Thread(() -> request.accept(new Object(), 0));
                nativeCallback.start();
                check(enqueued.await(2, TimeUnit.SECONDS), "Native callback entered");
                Thread cancel = new Thread(request::cancel);
                cancel.start();
                release.countDown();
                cancel.join(2000); nativeCallback.join(2000);
                check(!cancel.isAlive() && !nativeCallback.isAlive(), "Cancellation and enqueue complete");
                worker.submit(() -> {}).get(2, TimeUnit.SECONDS);
                check(work.get() == 1 && discard.get() == 0, "Barrier covers pre-cancel delivery");
                request.accept(new Object(), 0);
                check(work.get() == 1 && discard.get() == 1, "Post-cancel delivery only discards");
                check(field.get(request) == null, "Completed request releases owner closure");
            }
        } finally { worker.shutdownNow(); }
        System.out.println("PASS: " + checks + " navigation capture reload checks");
    }
}
