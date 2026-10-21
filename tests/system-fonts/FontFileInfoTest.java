import com.rikumi.colorosmod.FontFileInfo;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class FontFileInfoTest {
    static int checks;
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static byte[] face(int weight, boolean italic, boolean variable, String name) {
        byte[] text = name.getBytes(StandardCharsets.UTF_16BE);
        int tables = variable ? 3 : 2, first = 12 + 16 * tables;
        ByteBuffer b = ByteBuffer.allocate(first + 64 + 18 + text.length + (variable ? 36 : 0)).order(ByteOrder.BIG_ENDIAN);
        b.putInt(0x10000).putShort((short) tables).position(12);
        b.putInt(0x4f532f32).putInt(0).putInt(first).putInt(64);
        b.putInt(0x6e616d65).putInt(0).putInt(first + 64).putInt(18 + text.length);
        if (variable) b.putInt(0x66766172).putInt(0).putInt(first + 82 + text.length).putInt(36);
        b.position(first + 4); b.putShort((short) weight);
        b.position(first + 62); b.putShort((short) (italic ? 1 : 0));
        b.putShort((short) 0).putShort((short) 1).putShort((short) 18);
        b.putShort((short) 3).putShort((short) 1).putShort((short) 0x409)
            .putShort((short) 16).putShort((short) text.length).putShort((short) 0).put(text);
        if (variable) {
            b.putInt(0x10000).putShort((short) 16).putShort((short) 2)
                .putShort((short) 1).putShort((short) 20).putShort((short) 0).putShort((short) 8);
            b.putInt(0x77676874).putInt(200 << 16).putInt(400 << 16).putInt(800 << 16)
                .putShort((short) 0).putShort((short) 256);
        }
        return b.array();
    }
    static byte[] collection(byte[]... faces) {
        int size = 12 + faces.length * 4;
        for (byte[] face : faces) size += face.length;
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        b.putInt(0x74746366).putInt(0x10000).putInt(faces.length);
        int offset = 12 + faces.length * 4;
        for (byte[] face : faces) {
            b.putInt(offset);
            ByteBuffer f = ByteBuffer.wrap(face.clone()).order(ByteOrder.BIG_ENDIAN);
            int count = Short.toUnsignedInt(f.getShort(4));
            for (int i = 0; i < count; i++) f.putInt(20 + i * 16, f.getInt(20 + i * 16) + offset);
            System.arraycopy(f.array(), 0, b.array(), offset, face.length);
            offset += face.length;
        }
        return b.array();
    }
    static List<FontFileInfo.Face> read(byte[] bytes) throws Exception {
        Path file = Files.createTempFile("font-test-", ".ttc");
        try { Files.write(file, bytes); return FontFileInfo.read(file.toFile()); }
        finally { Files.delete(file); }
    }
    static void reject(byte[] bytes) throws Exception {
        try { read(bytes); throw new AssertionError("Malformed font accepted"); }
        catch (IOException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        List<FontFileInfo.Face> fonts = read(collection(face(400, false, false, "Example SC"),
            face(700, false, false, "Example SC"), face(400, true, false, "Example SC"),
            face(700, false, false, "Example TC"), face(700, false, false, "Example HC")));
        check(fonts.size() == 5, "TTC member count");
        check(FontFileInfo.match(fonts, 650, false, "zh-Hans").index == 1, "Bold TTC member");
        check(FontFileInfo.match(fonts, 400, true, "zh-Hans").index == 2, "Italic TTC member");
        check(FontFileInfo.match(fonts, 700, false, "zh-Hant").index == 3, "Traditional Chinese TTC member");
        check(FontFileInfo.match(fonts, 700, false, "zh-Hant-HK").index == 4, "Hong Kong TTC member");
        List<FontFileInfo.Face> variable = read(face(400, false, true, "Variable"));
        check(variable.get(0).variable(), "Weight axis found");
        check(variable.get(0).weightAt(100) == 200, "Minimum axis limit");
        check(variable.get(0).weightAt(550) == 550, "Continuous axis weight");
        check(variable.get(0).weightAt(900) == 800, "Maximum axis limit");
        reject(new byte[12]);
        byte[] bad = collection(face(400, false, false, "Bad"));
        ByteBuffer.wrap(bad).putInt(12, Integer.MAX_VALUE); reject(bad);
        bad = face(400, false, true, "Bad");
        ByteBuffer.wrap(bad).putInt(20, Integer.MAX_VALUE); reject(bad);
        reject(ByteBuffer.allocate(12).putInt(0x74746366).putInt(0x10000).putInt(257).array());
        for (String path : args) {
            List<FontFileInfo.Face> real = FontFileInfo.read(new File(path));
            check(!real.isEmpty(), "Bundled font metadata: " + path);
            if (path.contains("MapleMono") || path.contains("JetBrainsMono") || path.contains("FiraCode") || path.contains("CascadiaCode"))
                check(real.stream().allMatch(f -> f.monospace), "Actual ASCII advances are equal: " + path);
            if (path.contains("Inter.ttf") || path.contains("Manrope.ttf"))
                check(real.stream().noneMatch(f -> f.monospace), "Proportional fonts are not misidentified: " + path);
            check(real.stream().anyMatch(f -> f.variable() || f.weight >= 100), "Bundled weight: " + path);
        }
        System.out.println("PASS: " + checks + " font metadata and TTC checks");
    }
}
