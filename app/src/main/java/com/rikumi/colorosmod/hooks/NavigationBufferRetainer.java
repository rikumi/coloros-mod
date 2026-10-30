package com.rikumi.colorosmod.hooks;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Keep screenshot destruction out of the HWC visible-layer validation path. */
final class NavigationBufferRetainer implements AutoCloseable {
    private static final ScheduledThreadPoolExecutor RELEASES = createExecutor();
    private AutoCloseable current;

    private static ScheduledThreadPoolExecutor createExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "ColorOSMod-NavBufferRelease");
            thread.setDaemon(true);
            return thread;
        });
        executor.setKeepAliveTime(1, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    synchronized void replace(AutoCloseable buffer) {
        AutoCloseable previous = current;
        current = buffer;
        // Leave time for the replacement transaction to reach SurfaceFlinger. At
        // the 50 ms capture interval this retains only a few previous screenshots.
        releaseLater(previous, 100);
    }

    @Override public synchronized void close() {
        AutoCloseable previous = current;
        current = null;
        // The HWC can drop an invisible layer during the return-home animation.
        // Keep a reference past that transition so its close() is not the last
        // dma-buf reference and does not synchronously unmap all screenshot pages.
        // This executor survives navigation-worker shutdown during hot reload.
        releaseLater(previous, 1000);
    }

    private static void releaseLater(AutoCloseable buffer, long delayMs) {
        if (buffer == null) return;
        RELEASES.schedule(() -> {
            try { buffer.close(); }
            catch (Exception error) {
                com.rikumi.colorosmod.XposedInit.log("navigation buffer release failed: " + error);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }
}
