package com.rikumi.colorosmod.hooks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public final class LegacyFrameworkNotificationTemplatesTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        LegacyFrameworkNotificationTemplates bundle;
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            bundle = LegacyFrameworkNotificationTemplates.load(input);
        }
        Map<String, Integer> current = new HashMap<>();
        var matcher = Pattern.compile("(?m)^\\s+resource (0x[0-9a-f]+) ([^/\\s]+/[^\\s]+)")
                .matcher(Files.readString(Path.of(args[2])));
        while (matcher.find()) current.put(matcher.group(2), Integer.decode(matcher.group(1)));
        Path output = Path.of(args[3]);
        Files.createDirectories(output);
        try (ZipFile source = new ZipFile(args[1])) {
            for (var entry : bundle.layouts.entrySet()) {
                byte[] original;
                try (var input = source.getInputStream(source.getEntry(entry.getKey()))) {
                    original = input.readAllBytes();
                }
                check(java.util.Arrays.equals(original, entry.getValue()), "Original XML preserved: " + entry.getKey());
                byte[] mapped = LegacyNotificationXml.remap(entry.getValue(), old -> {
                    if (old == 0) return 0;
                    var resource = bundle.resources.get(old);
                    if (resource == null) throw new AssertionError("Unindexed old ID: " + old);
                    Integer id = current.get(resource.type() + "/" + resource.name());
                    if (id == null) throw new AssertionError("Missing current resource: " + resource);
                    return id;
                }, bundle::dimension);
                check(mapped.length == original.length, "Mapped XML shape retained");
                Path file = output.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, mapped);
            }
        }
        for (var resource : bundle.resources.values()) {
            check(current.containsKey(resource.type() + "/" + resource.name()), "Resource name survives version change");
            if (resource.type().equals("layout"))
                check(bundle.layout(resource.name(), false) != null, "Nested include/stub template bundled: " + resource.name());
        }
        check(bundle.layout("notification_template_material_base", false) != null, "Ordinary notification included");
        check(bundle.layout("notification_template_material_big_text", false) != null, "Expanded text included");
        check(bundle.layout("notification_template_header", false) != null, "Group header included");
        check(bundle.layout("not_a_notification", false) == null, "Unrelated layouts untouched");
        long dimensions = bundle.resources.values().stream().filter(r -> r.dimension() != null).count();
        check(dimensions == 47, "Original 47 dimension values preserved");
        if (args.length > 4) try (ZipFile hybrids = new ZipFile(args[4])) {
            for (String name : new String[]{"hybrid_notification", "hybrid_conversation_notification"}) {
                byte[] xml;
                try (var input = hybrids.getInputStream(hybrids.getEntry("res/layout/" + name + ".xml"))) {
                    xml = input.readAllBytes();
                }
                LegacyNotificationXml.remap(xml, id -> {
                    if ((id >>> 24) != 1 || id == 0) return id;
                    // Public attributes have stable framework IDs; private styles,
                    // IDs and layouts must resolve through the real old index.
                    if (((id >>> 16) & 255) == 1) return id;
                    var resource = bundle.resources.get(id);
                    check(resource != null, "Hybrid private framework ID indexed: " + Integer.toHexString(id));
                    Integer mapped = current.get(resource.type() + "/" + resource.name());
                    check(mapped != null, "Hybrid private resource exists on current framework: " + resource);
                    return mapped;
                }, bundle::dimension);
            }
            check(bundle.resources.get(0x01030392).name().equals("TextAppearance.DeviceDefault.Notification.Title"),
                    "Hybrid title uses notification typography rather than an unrelated Holo style");
            check(bundle.resources.get(0x01030483).name().equals("Widget.DeviceDefault.Notification.Text"),
                    "Hybrid text uses the notification widget style");
        }
        System.out.println("PASS: " + checks + " original framework template and current-ID checks");
    }
}
