// Run with JDK 21 alongside FontFileInfo.java and RemoteZipFont.java; no Android runtime required.
import com.rikumi.colorosmod.RemoteZipFont;
import com.rikumi.colorosmod.FontOperation;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class RemoteZipFontTest {
    private static byte[] archive, payload;
    private static String etag = "\"v1\"";
    private static boolean ranges = true, corrupt = false;
    private static byte[] zip(byte[] content, int padding, boolean stored) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.setComment("comment with PK\005\006 signature");
            ZipEntry first = new ZipEntry("padding.bin");
            zip.putNextEntry(first); byte[] random = new byte[padding]; new Random(42).nextBytes(random);
            zip.write(random); zip.closeEntry();
            ZipEntry font = new ZipEntry("font.ttf");
            if (stored) {
                CRC32 crc = new CRC32(); crc.update(content);
                font.setMethod(ZipEntry.STORED); font.setSize(content.length); font.setCrc(crc.getValue());
            }
            zip.putNextEntry(font); zip.write(content); zip.closeEntry();
        }
        return output.toByteArray();
    }
    private static void equal(byte[] expected, File output) throws IOException {
        if (!Arrays.equals(expected, Files.readAllBytes(output.toPath()))) throw new AssertionError("Payload mismatch");
    }
    private interface Checked { void run() throws Exception; }
    private static void fails(Checked action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected an IOException");
    }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/font.zip", exchange -> {
            try {
                String guard = exchange.getRequestHeaders().getFirst("If-Match");
                if (guard != null && !guard.equals(etag)) { exchange.sendResponseHeaders(412, -1); return; }
                byte[] bytes = archive;
                String range = exchange.getRequestHeaders().getFirst("Range");
                int start = 0, end = bytes.length - 1;
                boolean partial = ranges && range != null;
                if (partial) {
                    String[] values = range.substring(6).split("-", -1);
                    if (values[0].isEmpty()) start = Math.max(0, bytes.length - Integer.parseInt(values[1]));
                    else { start = Integer.parseInt(values[0]); end = Integer.parseInt(values[1]); }
                    exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length);
                }
                exchange.getResponseHeaders().set("ETag", etag);
                exchange.sendResponseHeaders(partial ? 206 : 200, end - start + 1);
                byte[] body = Arrays.copyOfRange(bytes, start, end + 1);
                if (corrupt && body.length > 100) body[body.length / 2] ^= 1;
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        server.start();
        File output = File.createTempFile("remote-zip-test-", ".font");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/font.zip";
            payload = new byte[120000]; new Random(7).nextBytes(payload);
            archive = zip(payload, 90000, false);
            RemoteZipFont.Entry old = RemoteZipFont.locate(url, "font.ttf");
            long[] progress = {0, 0};
            RemoteZipFont.download(url, old, output, (done, total) -> { progress[0] = done; progress[1] = total; });
            equal(payload, output);
            if (progress[0] != payload.length || progress[1] != payload.length) throw new AssertionError("Progress mismatch");
            System.out.println("PASS: dynamic directory, local header, deflate, descriptor, progress");
            List<FontOperation.Step> steps = new ArrayList<>();
            List<String> details = new ArrayList<>();
            new FontOperation((step, detail) -> { steps.add(step); details.add(detail); }).run(() -> {
                try {
                    RemoteZipFont.Entry entry = RemoteZipFont.locate(url, "font.ttf");
                    RemoteZipFont.download(url, entry, output, null);
                } catch (IOException error) { throw new RuntimeException(error); }
            });
            if (!steps.containsAll(Arrays.asList(FontOperation.Step.REQUEST, FontOperation.Step.PARSE_ZIP_DIRECTORY,
                    FontOperation.Step.LOCATE_ZIP_ENTRY, FontOperation.Step.PARSE_ZIP_HEADER,
                    FontOperation.Step.DECOMPRESS, FontOperation.Step.VERIFY_FONT))
                    || details.stream().noneMatch(detail -> detail.contains(url) && detail.contains("Range: bytes=")))
                throw new AssertionError("Preparation details missing");
            int reported = steps.size(); FontOperation.report(FontOperation.Step.REQUEST, "outside task");
            if (steps.size() != reported) throw new AssertionError("Preparation observer leaked");
            System.out.println("PASS: preparation step events, request URL/range, observer cleanup");
            FontOperation cancelled = new FontOperation();
            try {
                cancelled.run(() -> {
                    try {
                        RemoteZipFont.download(url, old, output, (done, total) -> { if (done > 0) cancelled.cancel(); });
                        throw new AssertionError("Cancelled download completed");
                    } catch (IOException error) { throw new RuntimeException(error); }
                });
                throw new AssertionError("Expected download cancellation");
            } catch (FontOperation.Cancelled expected) {}
            FontOperation queued = new FontOperation(); queued.cancel();
            try { queued.run(() -> { throw new AssertionError("Cancelled queued task started"); }); }
            catch (FontOperation.Cancelled expected) {}
            FontOperation applying = new FontOperation();
            applying.run(() -> {
                applying.beginApplying();
                if (applying.cancel()) throw new AssertionError("Mount cancellation accepted");
                FontOperation.check();
                if (!applying.complete()) throw new AssertionError("Mount did not finish");
            });
            if (FontOperation.current() != null) throw new AssertionError("Cancellation leaked to next task");
            System.out.println("PASS: queued/active download cancellation, mount cancellation disabled, thread cleanup");

            payload = new byte[150001]; new Random(8).nextBytes(payload);
            archive = zip(payload, 130000, true); etag = "\"v2\"";
            fails(() -> RemoteZipFont.download(url, old, output, null));
            RemoteZipFont.Entry updated = RemoteZipFont.locate(url, "font.ttf");
            if (updated.offset == old.offset || updated.crc == old.crc || updated.size == old.size)
                throw new AssertionError("Updated metadata not detected");
            RemoteZipFont.download(url, updated, output, null); equal(payload, output);
            System.out.println("PASS: same URL updated size/offset/CRC, stored entry, stale ETag rejected");

            corrupt = true;
            fails(() -> RemoteZipFont.download(url, updated, output, null)); corrupt = false;
            fails(() -> RemoteZipFont.locate(url, "missing.ttf"));
            System.out.println("PASS: corrupt font and missing entry rejected");

            ranges = false;
            try { RemoteZipFont.locate(url, "font.ttf"); throw new AssertionError("Expected fallback"); }
            catch (RemoteZipFont.RangeUnsupported expected) {}
            long[] descriptor = RemoteZipFont.downloadWhole(url, "font.ttf", output, null);
            equal(payload, output);
            if (descriptor[0] != payload.length || descriptor[1] != updated.crc) throw new AssertionError("Fallback metadata mismatch");
            archive = zip(payload, 100, false);
            RemoteZipFont.downloadWhole(url, "font.ttf", output, null); equal(payload, output);
            System.out.println("PASS: no-Range fallback with stored and data-descriptor entries");

            ranges = true; archive = zip(payload, 10, false);
            RemoteZipFont.Entry small = RemoteZipFont.locate(url, "font.ttf");
            RemoteZipFont.download(url, small, output, null); equal(payload, output);
            archive = zip(new byte[100], 0, false);
            RemoteZipFont.Entry tiny = RemoteZipFont.locate(url, "font.ttf");
            RemoteZipFont.download(url, tiny, output, null); equal(new byte[100], output);
            System.out.println("PASS: suffix ranges for archives shorter than 65557 bytes");
        } finally { server.stop(0); output.delete(); }
    }
}
