package com.rikumi.colorosmod.hooks;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

/** Validate the real packaged notification templates and malformed chunk handling. */
public final class LegacyNotificationXmlTest {
    private static int checks;
    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    private static void rejects(byte[] bytes, String name) {
        try { LegacyNotificationXml.remap(bytes, id -> id); throw new AssertionError(name); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        try (ZipFile apk = new ZipFile(args[0])) {
            for (String name : new String[]{"hybrid_notification", "hybrid_conversation_notification"}) {
                byte[] xml;
                try (var input = apk.getInputStream(apk.getEntry("res/layout/" + name + ".xml"))) {
                    xml = input.readAllBytes();
                }
                byte[] original = xml.clone();
                AtomicInteger references = new AtomicInteger();
                byte[] mapped = LegacyNotificationXml.remap(xml, id -> { references.incrementAndGet(); return id + 1; });
                check(Arrays.equals(xml, original), "Original template remains intact");
                check(references.get() > 10, "Actual template attribute names and references visited");
                check(!Arrays.equals(mapped, xml), "Resource references remapped");
                check(Arrays.equals(xml, LegacyNotificationXml.remap(mapped, id -> id - 1)),
                        "Remap preserves all non-resource bytes including string pool and literal dimensions");
                check(Arrays.equals(xml, LegacyNotificationXml.remap(xml, id -> id)), "Identity mapping is lossless");
                byte[] bad = xml.clone();
                ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(4, xml.length - 1);
                rejects(bad, "Truncated document rejected");
                bad = xml.clone();
                ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 0);
                rejects(bad, "Zero-sized chunk cannot loop forever");
                bad = xml.clone();
                ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(12, Integer.MAX_VALUE);
                rejects(bad, "Oversized chunk rejected");
            }
        }
        rejects(new byte[0], "Empty input rejected");
        System.out.println("PASS: " + checks + " real legacy notification XML checks");
    }
}
