package com.rikumi.colorosmod.hooks;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

final class LegacyFrameworkNotificationTemplates {
    record Resource(String type, String name, Long dimension) { }
    final Map<Integer, Resource> resources = new HashMap<>();
    final Map<String, byte[]> layouts = new HashMap<>();

    static LegacyFrameworkNotificationTemplates load(InputStream input) throws Exception {
        LegacyFrameworkNotificationTemplates bundle = new LegacyFrameworkNotificationTemplates();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int size;
                while ((size = zip.read(buffer)) != -1) bytes.write(buffer, 0, size);
                if (entry.getName().endsWith(".xml")) bundle.layouts.put(entry.getName(), bytes.toByteArray());
                else if (entry.getName().equals("resources.tsv")) {
                    for (String line : bytes.toString(StandardCharsets.UTF_8).split("\n")) {
                        String[] fields = line.split("\t");
                        if (fields.length != 4) throw new IllegalArgumentException("Invalid legacy framework resource index");
                        bundle.resources.put((int) Long.parseLong(fields[0], 16), new Resource(fields[1], fields[2],
                                fields[3].equals("-") ? null : Long.parseLong(fields[3], 16)));
                    }
                }
            }
        }
        if (bundle.layouts.isEmpty() || bundle.resources.isEmpty()) throw new IllegalArgumentException("Empty legacy framework templates");
        return bundle;
    }

    byte[] layout(String name, boolean landscape) {
        byte[] selected = landscape ? layouts.get("res/layout-land/" + name + ".xml") : null;
        return selected != null ? selected : layouts.get("res/layout/" + name + ".xml");
    }

    long dimension(int id) {
        Resource resource = resources.get(id);
        return resource != null && resource.dimension != null ? (5L << 32) | resource.dimension : -1L;
    }
}
