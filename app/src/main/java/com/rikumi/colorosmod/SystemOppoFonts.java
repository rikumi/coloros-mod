package com.rikumi.colorosmod;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Immutable firmware fonts: never follow SysFont links owned by the theme/font manager. */
public final class SystemOppoFonts {
    public static List<File> find(boolean chinese) throws IOException {
        return findIn(new File("/system/fonts"), chinese);
    }
    static List<File> findIn(File directory, boolean chinese) throws IOException {
        List<File> fonts = chinese
                ? List.of(new File(directory, "SysSans-Hans-Regular.ttf"), new File(directory, "SysSans-Hant-Regular.ttf"))
                : List.of(new File(directory, "SysSans-En-Regular.ttf"));
        for (File font : fonts) if (!font.isFile() || !font.canRead())
            throw new IOException("Firmware OPPO Sans unavailable: " + font);
        return fonts;
    }
    private SystemOppoFonts() {}
}
