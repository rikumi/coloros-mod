package com.rikumi.colorosmod;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class FontFileInfo {
    public static final long MAX_BYTES = 100L * 1024 * 1024;
    public static final class Face {
        public int index, weight = 400;
        public boolean italic;
        public boolean monospace;
        public boolean color;
        public float minWeight, maxWeight;
        public String name = "";
        public boolean variable() { return maxWeight > 0; }
        public int weightAt(int requested) {
            return variable() ? Math.round(Math.max(minWeight, Math.min(maxWeight, requested))) : weight;
        }
    }
    private static void range(RandomAccessFile f, long offset, long size) throws IOException {
        if (offset < 0 || size < 0 || offset > f.length() - size) throw new IOException("Invalid font offset");
    }
    private static long uint(RandomAccessFile f) throws IOException { return Integer.toUnsignedLong(f.readInt()); }
    public static List<Face> read(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            if (f.length() < 12 || f.length() > MAX_BYTES) throw new IOException("Invalid font size");
            int signature = f.readInt();
            long[] offsets;
            if (signature == 0x74746366) {
                f.readInt();
                long count = uint(f);
                if (count < 1 || count > 256) throw new IOException("Invalid TTC count");
                range(f, 12, count * 4);
                offsets = new long[(int) count];
                for (int i = 0; i < count; i++) offsets[i] = uint(f);
            } else offsets = new long[]{0};
            List<Face> result = new ArrayList<>();
            for (int i = 0; i < offsets.length; i++) {
                long offset = offsets[i];
                range(f, offset, 12);
                f.seek(offset);
                int magic = f.readInt();
                if (magic != 0x00010000 && magic != 0x4f54544f && magic != 0x74727565)
                    throw new IOException("Unsupported SFNT");
                int count = f.readUnsignedShort();
                if (count < 1 || count > 256) throw new IOException("Invalid table count");
                range(f, offset + 12, count * 16L);
                Face face = new Face(); face.index = i;
                long os2 = -1, head = -1, fvar = -1, name = -1, cmap = -1, hhea = -1, hmtx = -1;
                long os2Len = 0, headLen = 0, fvarLen = 0, nameLen = 0, cmapLen = 0, hheaLen = 0, hmtxLen = 0;
                boolean cbdt = false, cblc = false, colr = false, cpal = false;
                for (int t = 0; t < count; t++) {
                    f.seek(offset + 12 + t * 16L);
                    int tag = f.readInt(); f.readInt();
                    long start = uint(f), length = uint(f); range(f, start, length);
                    if (tag == 0x4f532f32) { os2 = start; os2Len = length; }
                    if (tag == 0x68656164) { head = start; headLen = length; }
                    if (tag == 0x66766172) { fvar = start; fvarLen = length; }
                    if (tag == 0x6e616d65) { name = start; nameLen = length; }
                    if (tag == 0x636d6170) { cmap = start; cmapLen = length; }
                    if (tag == 0x68686561) { hhea = start; hheaLen = length; }
                    if (tag == 0x686d7478) { hmtx = start; hmtxLen = length; }
                    cbdt |= tag == 0x43424454; cblc |= tag == 0x43424c43;
                    colr |= tag == 0x434f4c52; cpal |= tag == 0x4350414c;
                }
                face.color = (cbdt && cblc) || (colr && cpal);
                if (os2Len >= 6) {
                    f.seek(os2 + 4); face.weight = Math.max(1, Math.min(1000, f.readUnsignedShort()));
                    if (os2Len >= 64) { f.seek(os2 + 62); face.italic = (f.readUnsignedShort() & 1) != 0; }
                } else if (headLen >= 46) {
                    f.seek(head + 44); int style = f.readUnsignedShort();
                    face.weight = (style & 1) != 0 ? 700 : 400; face.italic = (style & 2) != 0;
                }
                if (fvarLen >= 16) {
                    f.seek(fvar + 4); int axesOffset = f.readUnsignedShort(); f.readUnsignedShort();
                    int axesCount = f.readUnsignedShort(), axisSize = f.readUnsignedShort();
                    if (axesCount > 64 || axisSize < 20 || axesOffset + axesCount * (long) axisSize > fvarLen)
                        throw new IOException("Invalid variable axes");
                    for (int a = 0; a < axesCount; a++) {
                        f.seek(fvar + axesOffset + a * (long) axisSize);
                        int tag = f.readInt(); float min = f.readInt() / 65536f;
                        f.readInt(); float max = f.readInt() / 65536f;
                        if (tag == 0x77676874 && min >= 1 && max <= 1000 && min <= max) {
                            face.minWeight = min; face.maxWeight = max;
                        }
                    }
                }
                if (nameLen >= 6) {
                    f.seek(name + 2); int names = f.readUnsignedShort(), strings = f.readUnsignedShort();
                    if (names > 4096 || 6L + names * 12L > nameLen) throw new IOException("Invalid names");
                    int best = -1;
                    for (int n = 0; n < names; n++) {
                        f.seek(name + 6 + n * 12L);
                        int platform = f.readUnsignedShort(); f.readUnsignedShort();
                        int language = f.readUnsignedShort(), id = f.readUnsignedShort();
                        int length = f.readUnsignedShort(), start = f.readUnsignedShort();
                        if ((id != 1 && id != 16) || (platform != 0 && platform != 3) || length > 1024) continue;
                        int score = (id == 16 ? 2 : 0) + (language == 0x409 ? 4 : 0);
                        if (score <= best || strings + start + (long) length > nameLen) continue;
                        byte[] bytes = new byte[length]; f.seek(name + strings + start); f.readFully(bytes);
                        face.name = new String(bytes, StandardCharsets.UTF_16BE); best = score;
                    }
                }
                face.monospace = asciiMonospace(f, cmap, cmapLen, hhea, hheaLen, hmtx, hmtxLen);
                result.add(face);
            }
            return result;
        }
    }
    private static boolean asciiMonospace(RandomAccessFile f, long cmap, long cmapLen,
                                           long hhea, long hheaLen, long hmtx, long hmtxLen) throws IOException {
        if (cmapLen < 4 || hheaLen < 36 || hmtxLen < 4) return false;
        f.seek(hhea + 34); int metrics = f.readUnsignedShort();
        if (metrics == 0 || metrics * 4L > hmtxLen) return false;
        f.seek(cmap + 2); int count = f.readUnsignedShort();
        if (count > 1024 || 4 + count * 8L > cmapLen) return false;
        long selected = -1, selectedLength = 0; int selectedFormat = 0;
        for (int i = 0; i < count; i++) {
            f.seek(cmap + 4 + i * 8L);
            int platform = f.readUnsignedShort(), encoding = f.readUnsignedShort();
            long offset = uint(f);
            if ((platform != 0 && !(platform == 3 && (encoding == 1 || encoding == 10))) || offset + 4 > cmapLen) continue;
            f.seek(cmap + offset); int format = f.readUnsignedShort();
            long length;
            if (format == 4) length = f.readUnsignedShort();
            else if (format == 12 && offset + 16 <= cmapLen) { f.readUnsignedShort(); length = uint(f); }
            else continue;
            if (length < (format == 4 ? 16 : 16) || offset + length > cmapLen) continue;
            if (selected < 0 || format == 12) { selected = cmap + offset; selectedLength = length; selectedFormat = format; }
        }
        if (selected < 0) return false;
        int advance = -1;
        for (int ch = 32; ch <= 126; ch++) {
            int glyph = glyphId(f, selected, selectedLength, selectedFormat, ch);
            if (glyph <= 0) return false;
            f.seek(hmtx + Math.min(glyph, metrics - 1) * 4L);
            int width = f.readUnsignedShort();
            if (width == 0 || (advance >= 0 && width != advance)) return false;
            advance = width;
        }
        return true;
    }

    private static int glyphId(RandomAccessFile f, long table, long length, int format, int ch) throws IOException {
        if (format == 12) {
            f.seek(table + 12); long count = uint(f);
            if (count > (length - 16) / 12) return 0;
            long low = 0, high = count - 1;
            while (low <= high) {
                long mid = (low + high) >>> 1; f.seek(table + 16 + mid * 12);
                long start = uint(f), end = uint(f), glyph = uint(f);
                if (ch < start) high = mid - 1;
                else if (ch > end) low = mid + 1;
                else return glyph + ch - start <= 65535 ? (int) (glyph + ch - start) : 0;
            }
            return 0;
        }
        f.seek(table + 6); int segments = f.readUnsignedShort() / 2;
        if (segments == 0 || 16L + segments * 8L > length) return 0;
        long ends = table + 14, starts = ends + segments * 2L + 2;
        long deltas = starts + segments * 2L, ranges = deltas + segments * 2L;
        for (int i = 0; i < segments; i++) {
            f.seek(ends + i * 2L); int end = f.readUnsignedShort();
            if (ch > end) continue;
            f.seek(starts + i * 2L); int start = f.readUnsignedShort();
            if (ch < start) return 0;
            f.seek(deltas + i * 2L); int delta = f.readShort();
            f.seek(ranges + i * 2L); int offset = f.readUnsignedShort();
            if (offset == 0) return (ch + delta) & 65535;
            long address = ranges + i * 2L + offset + (ch - start) * 2L;
            if (address < table || address + 2 > table + length) return 0;
            f.seek(address); int glyph = f.readUnsignedShort();
            return glyph == 0 ? 0 : (glyph + delta) & 65535;
        }
        return 0;
    }

    public static Face match(List<Face> faces, int weight, boolean italic, String language) {
        Face best = null; int bestScore = Integer.MAX_VALUE;
        for (Face face : faces) {
            int score = matchScore(face, weight, italic, language);
            if (score < bestScore) { bestScore = score; best = face; }
        }
        return best;
    }
    public static int matchScore(Face face, int weight, boolean italic, String language) {
        boolean hongKong = language.contains("HK");
        boolean traditional = language.contains("Hant") || language.contains("TW") || hongKong;
        String name = face.name.toLowerCase(java.util.Locale.ROOT);
        boolean hk = name.contains("hk") || name.contains(" hc") || name.endsWith("hc");
        boolean tw = name.contains("tc") || name.contains("tw");
        boolean hant = tw || hk || name.contains("hant");
        boolean hans = name.contains("sc") || name.contains("cn") || name.contains("hans");
        boolean foreign = name.endsWith(" jp") || name.endsWith(" kr") || name.endsWith("japanese") || name.endsWith("korean");
        int localePenalty = language.startsWith("zh")
                ? (traditional ? (hant ? 0 : hans ? 10000 : 1000) : (hans ? 0 : hant ? 10000 : 1000)) : 0;
        return Math.abs(face.weightAt(weight) - weight) + (face.italic == italic ? 0 : 2000)
                + localePenalty + (foreign && language.startsWith("zh") ? 20000 : 0)
                + (traditional && hant && (hongKong ? !hk : !tw) ? 5000 : 0);
    }
    private FontFileInfo() {}
}
