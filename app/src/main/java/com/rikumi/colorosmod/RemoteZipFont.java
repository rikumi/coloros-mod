package com.rikumi.colorosmod;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads the current ZIP directory before requesting a font's compressed byte range. */
public final class RemoteZipFont {
    private static final int TAIL_BYTES = 65557;
    private static final int MAX_DIRECTORY = 8 * 1024 * 1024;
    public interface Progress { void update(long done, long total); }
    public static final class RangeUnsupported extends IOException {}
    public static final class Entry {
        public final String name;
        public final long size, compressedSize, crc, offset;
        private final int method, flags;
        private final Snapshot snapshot;
        private Entry(String name, long size, long compressedSize, long crc, long offset,
                      int method, int flags, Snapshot snapshot) {
            this.name = name; this.size = size; this.compressedSize = compressedSize;
            this.crc = crc; this.offset = offset; this.method = method; this.flags = flags;
            this.snapshot = snapshot;
        }
    }
    private static final class Snapshot {
        long length;
        String etag, modified;
    }
    private static HttpURLConnection connect(String url, String range, Snapshot snapshot) throws IOException {
        FontOperation.check();
        FontOperation.report(FontOperation.Step.REQUEST, url + (range == null ? "" : "\nRange: " + range));
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        FontOperation.track(connection);
        connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "ColorOS-Mod");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Cache-Control", "no-cache");
        if (range != null) connection.setRequestProperty("Range", range);
        if (snapshot != null) {
            if (snapshot.etag != null && !snapshot.etag.startsWith("W/"))
                connection.setRequestProperty("If-Match", snapshot.etag);
            else if (snapshot.modified != null)
                connection.setRequestProperty("If-Unmodified-Since", snapshot.modified);
        }
        return connection;
    }
    private static byte[] range(String url, String request, long start, int size, Snapshot snapshot) throws IOException {
        HttpURLConnection connection = connect(url, request, snapshot.length == 0 ? null : snapshot);
        try {
            int status = connection.getResponseCode();
            if (status == 200) throw new RangeUnsupported();
            if (status != 206) throw new IOException("ZIP range request failed: " + status);
            String header = connection.getHeaderField("Content-Range");
            if (header == null || !header.matches("bytes [0-9]+-[0-9]+/[0-9]+"))
                throw new IOException("Invalid ZIP Content-Range");
            String[] parts = header.substring(6).split("[-/]");
            long actualStart = Long.parseLong(parts[0]), end = Long.parseLong(parts[1]);
            long length = Long.parseLong(parts[2]);
            if (snapshot.length == 0) {
                snapshot.length = length;
                snapshot.etag = connection.getHeaderField("ETag");
                snapshot.modified = connection.getHeaderField("Last-Modified");
                start = Math.max(0, length - TAIL_BYTES);
                size = (int) (length - start);
            }
            if (length != snapshot.length || actualStart != start || end != start + size - 1)
                throw new IOException("ZIP changed or range mismatch");
            byte[] bytes = new byte[size];
            try (DataInputStream input = new DataInputStream(connection.getInputStream())) {
                input.readFully(bytes);
                if (input.read() != -1) throw new IOException("Oversized ZIP range");
            }
            return bytes;
        } finally { connection.disconnect(); }
    }
    private static byte[] range(String url, long start, int size, Snapshot snapshot) throws IOException {
        if (start < 0 || size <= 0 || start > snapshot.length - size) throw new IOException("Invalid ZIP range");
        return range(url, "bytes=" + start + "-" + (start + size - 1), start, size, snapshot);
    }
    private static int u16(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset > bytes.length - 2) throw new IOException("Truncated ZIP metadata");
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8);
    }
    private static long u32(byte[] bytes, int offset) throws IOException {
        return u16(bytes, offset) | ((long) u16(bytes, offset + 2) << 16);
    }
    public static Entry locate(String url, String name) throws IOException {
        Snapshot snapshot = new Snapshot();
        byte[] tail = range(url, "bytes=-" + TAIL_BYTES, 0, 0, snapshot);
        FontOperation.report(FontOperation.Step.PARSE_ZIP_DIRECTORY, name);
        int eocd = tail.length - 22;
        while (eocd >= 0 && (u32(tail, eocd) != 0x06054b50L
                || eocd + 22 + u16(tail, eocd + 20) != tail.length)) eocd--;
        if (eocd < 0) throw new IOException("ZIP directory missing");
        if (u16(tail, eocd + 4) != 0 || u16(tail, eocd + 6) != 0
                || u16(tail, eocd + 8) != u16(tail, eocd + 10)) throw new IOException("Split ZIP unsupported");
        long directorySize = u32(tail, eocd + 12), directoryOffset = u32(tail, eocd + 16);
        if (directorySize == 0xffffffffL || directoryOffset == 0xffffffffL
                || u16(tail, eocd + 10) == 65535) throw new IOException("ZIP64 unsupported");
        if (directorySize <= 0 || directorySize > MAX_DIRECTORY
                || directoryOffset + directorySize > snapshot.length - tail.length + eocd)
            throw new IOException("Invalid ZIP directory size");
        long tailOffset = snapshot.length - tail.length;
        byte[] directory = directoryOffset >= tailOffset
                ? Arrays.copyOfRange(tail, (int) (directoryOffset - tailOffset), (int) (directoryOffset - tailOffset + directorySize))
                : range(url, directoryOffset, (int) directorySize, snapshot);
        FontOperation.report(FontOperation.Step.LOCATE_ZIP_ENTRY, name);
        for (int offset = 0; offset < directory.length;) {
            if (u32(directory, offset) != 0x02014b50L) throw new IOException("Invalid ZIP directory entry");
            int nameSize = u16(directory, offset + 28), extraSize = u16(directory, offset + 30);
            int next = offset + 46 + nameSize + extraSize + u16(directory, offset + 32);
            if (next > directory.length) throw new IOException("Truncated ZIP entry");
            String entryName = new String(directory, offset + 46, nameSize, StandardCharsets.UTF_8);
            if (entryName.equals(name)) {
                long size = u32(directory, offset + 24), compressed = u32(directory, offset + 20);
                int flags = u16(directory, offset + 8), method = u16(directory, offset + 10);
                long localOffset = u32(directory, offset + 42);
                if ((flags & 1) != 0 || (method != 0 && method != 8) || size <= 0
                        || size > FontFileInfo.MAX_BYTES || compressed <= 0
                        || compressed > FontFileInfo.MAX_BYTES + 1048576 || localOffset == 0xffffffffL)
                    throw new IOException("Unsupported ZIP font entry");
                return new Entry(name, size, compressed, u32(directory, offset + 16), localOffset, method, flags, snapshot);
            }
            offset = next;
        }
        throw new IOException("Emoji font missing from archive: " + name);
    }
    public static void download(String url, Entry entry, File target, Progress progress) throws IOException {
        byte[] header = range(url, entry.offset, 30, entry.snapshot);
        FontOperation.report(FontOperation.Step.PARSE_ZIP_HEADER, entry.name);
        if (u32(header, 0) != 0x04034b50L || u16(header, 6) != entry.flags || u16(header, 8) != entry.method)
            throw new IOException("ZIP local header mismatch");
        int nameSize = u16(header, 26), extraSize = u16(header, 28);
        byte[] name = range(url, entry.offset + 30, nameSize, entry.snapshot);
        FontOperation.report(FontOperation.Step.PARSE_ZIP_HEADER, entry.name);
        if (!new String(name, StandardCharsets.UTF_8).equals(entry.name)) throw new IOException("ZIP font name mismatch");
        long start = entry.offset + 30 + nameSize + extraSize, end = start + entry.compressedSize - 1;
        if (start < 0 || end >= entry.snapshot.length) throw new IOException("Invalid compressed font range");
        HttpURLConnection connection = connect(url, "bytes=" + start + "-" + end, entry.snapshot);
        Inflater inflater = entry.method == 8 ? new Inflater(true) : null;
        try {
            int status = connection.getResponseCode();
            if (status == 200) throw new RangeUnsupported();
            if (status != 206 || !("bytes " + start + "-" + end + "/" + entry.snapshot.length)
                    .equals(connection.getHeaderField("Content-Range"))) throw new IOException("ZIP changed during download");
            FontOperation.report(FontOperation.Step.DECOMPRESS, entry.name);
            try (InputStream raw = connection.getInputStream();
                 InputStream source = inflater == null ? raw : new InflaterInputStream(raw, inflater, 65536)) {
                copy(source, target, entry.size, entry.crc, progress);
            }
        } finally { if (inflater != null) inflater.end(); connection.disconnect(); }
    }
    /** Servers without Range support still use the current ZIP entry and its CRC. */
    public static long[] downloadWhole(String url, String name, File target, Progress progress) throws IOException {
        HttpURLConnection connection = connect(url, null, null);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("ZIP download failed: " + connection.getResponseCode());
            FontOperation.report(FontOperation.Step.LOCATE_ZIP_ENTRY, name);
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(connection.getInputStream()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    FontOperation.check();
                    if (!entry.getName().equals(name)) continue;
                    FontOperation.report(FontOperation.Step.DECOMPRESS, name);
                    copy(zip, target, entry.getSize(), entry.getCrc(), progress);
                    // ZipInputStream validates data-descriptor CRC and fills sizes at end of entry.
                    if (entry.getSize() != target.length()) throw new IOException("ZIP font size mismatch");
                    return new long[]{entry.getSize(), entry.getCrc()};
                }
                throw new IOException("Emoji font missing from archive: " + name);
            }
        } finally { connection.disconnect(); }
    }
    private static void copy(InputStream input, File target, long expectedSize, long expectedCrc, Progress progress) throws IOException {
        CRC32 crc = new CRC32(); long total = 0, last = 0;
        if (progress != null) progress.update(0, Math.max(0, expectedSize));
        try (FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) {
                FontOperation.check();
                total += count;
                if (total > FontFileInfo.MAX_BYTES || (expectedSize >= 0 && total > expectedSize)) throw new IOException("Font too large");
                crc.update(buffer, 0, count); output.write(buffer, 0, count);
                long now = System.nanoTime();
                if (progress != null && (now - last >= 100000000L || total == expectedSize)) {
                    progress.update(total, Math.max(0, expectedSize)); last = now;
                }
            }
            output.getFD().sync();
        }
        FontOperation.report(FontOperation.Step.VERIFY_FONT, "CRC32");
        if ((expectedSize >= 0 && total != expectedSize) || (expectedCrc >= 0 && crc.getValue() != expectedCrc))
            throw new IOException("Emoji font integrity check failed");
        if (progress != null) progress.update(total, total);
    }
    private RemoteZipFont() {}
}
