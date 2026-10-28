package com.rikumi.colorosmod;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Source Han Sans / Noto Sans CJK supplied by the firmware, without bundling or copying it. */
public final class SystemCjkFonts {
    public static List<File> find() throws IOException {
        return findIn(new File[]{new File("/system/fonts"), new File("/product/fonts"),
                new File("/system_ext/fonts"), new File("/vendor/fonts")});
    }
    static List<File> findIn(File[] directories) throws IOException {
        List<File> result = new ArrayList<>();
        for (File directory : directories) {
            File[] files = directory.listFiles();
            if (files == null) continue;
            for (File file : files) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (file.isFile() && file.canRead()
                        && (name.startsWith("sourcehansans") || name.startsWith("notosanscjk")
                            || name.startsWith("notosanssc-") || name.startsWith("notosanstc-") || name.startsWith("notosanshk-"))
                        && name.matches(".*\\.(ttf|otf|ttc|otc)")) result.add(file);
            }
        }
        if (result.isEmpty()) throw new IOException("No system Source Han Sans / Noto Sans CJK font found");
        result.sort(Comparator.comparingInt(SystemCjkFonts::priority).thenComparing(File::getAbsolutePath));
        return result;
    }
    private static int priority(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.contains("vf") || name.contains("variable")) return 0;
        return name.equals("notosanscjk-regular.ttc") ? 1 : 2;
    }
    private SystemCjkFonts() {}
}
