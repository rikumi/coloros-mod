package com.rikumi.colorosmod;

import java.net.HttpURLConnection;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;

/** Cooperative cancellation before commit; active mount transactions cannot be cancelled. */
public final class FontOperation {
    public enum Step {
        CHECK_CACHE, REQUEST, PARSE_ZIP_DIRECTORY, LOCATE_ZIP_ENTRY, PARSE_ZIP_HEADER,
        DECOMPRESS, VERIFY_FONT, PARSE_FONT, SAVE_FONT, PUBLISH_FONT, BUILD_CONFIG, READ_IMPORT, LOAD_FONT
    }
    public interface Observer { void update(Step step, String detail); }
    public static final class Cancelled extends RuntimeException {}
    private static final ThreadLocal<FontOperation> CURRENT = new ThreadLocal<>();
    private final List<HttpURLConnection> connections = new ArrayList<>();
    private final List<Closeable> streams = new ArrayList<>();
    private boolean cancelled, applying, finished;
    private final Observer observer;

    public FontOperation() { this(null); }
    public FontOperation(Observer observer) { this.observer = observer; }
    public static void report(Step step, String detail) {
        check();
        FontOperation operation = CURRENT.get();
        if (operation != null && operation.observer != null) operation.observer.update(step, detail);
    }

    public void run(Runnable action) {
        CURRENT.set(this);
        try {
            check(); action.run();
        } catch (Throwable error) {
            synchronized (this) { if (cancelled && !applying) throw new Cancelled(); }
            throw error;
        } finally {
            synchronized (this) { finished = true; connections.clear(); streams.clear(); }
            CURRENT.remove();
        }
    }
    public synchronized boolean cancel() {
        if (finished || cancelled || applying) return false;
        cancelled = true;
        if (!applying) {
            List<HttpURLConnection> active = new ArrayList<>(connections);
            List<Closeable> inputs = new ArrayList<>(streams);
            Thread closer = new Thread(() -> {
                active.forEach(HttpURLConnection::disconnect);
                inputs.forEach(input -> { try { input.close(); } catch (IOException ignored) {} });
            }, "font-download-cancel");
            closer.setDaemon(true); closer.start();
        }
        return true;
    }
    public synchronized void beginApplying() {
        if (cancelled) throw new Cancelled();
        applying = true;
    }
    /** Resolves the last cancel/finish race before showing success. */
    public synchronized boolean complete() {
        if (cancelled) return false;
        finished = true;
        return true;
    }
    public static FontOperation current() { return CURRENT.get(); }
    public static void check() {
        FontOperation operation = CURRENT.get();
        if (operation != null) synchronized (operation) {
            if (operation.cancelled && !operation.applying) throw new Cancelled();
        }
    }
    public static void track(HttpURLConnection connection) {
        FontOperation operation = CURRENT.get();
        if (operation != null) synchronized (operation) {
            check(); operation.connections.add(connection);
        }
    }
    public static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[65536]; int count;
        while ((count = input.read(buffer)) != -1) {
            check(); output.write(buffer, 0, count);
        }
    }
    public static void track(Closeable input) {
        FontOperation operation = CURRENT.get();
        if (operation != null) synchronized (operation) {
            check(); operation.streams.add(input);
        }
    }
}
