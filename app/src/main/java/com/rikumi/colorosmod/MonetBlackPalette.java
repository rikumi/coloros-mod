package com.rikumi.colorosmod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Precomputed Tuned OLED Black resource values; the theme and upstream revisions ship in assets. */
final class MonetBlackPalette {
    record Entry(String target, String type, String name, String configuration, int value) {}
    static List<Entry> read(Reader source) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        try (BufferedReader input = new BufferedReader(source)) {
            for (String line; (line = input.readLine()) != null;) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 5 || !(fields[0].equals("android") || fields[0].equals("com.android.systemui"))
                        || !(fields[1].equals("color") || fields[1].equals("bool"))
                        || !fields[2].matches("[A-Za-z0-9_]+")
                        || !(fields[3].equals("-") || fields[3].equals("night") || fields[3].equals("notnight"))
                        || !fields[4].matches("[0-9A-F]{8}")) throw new IOException("Invalid built-in Monet palette");
                String key = String.join("\t", fields[0], fields[1], fields[2], fields[3]);
                if (!keys.add(key)) throw new IOException("Duplicate Monet resource: " + key);
                entries.add(new Entry(fields[0], fields[1], fields[2], fields[3].equals("-") ? null : fields[3],
                        (int) Long.parseLong(fields[4], 16)));
            }
        }
        if (entries.isEmpty()) throw new IOException("Empty Monet palette");
        return entries;
    }
    private MonetBlackPalette() {}
}
