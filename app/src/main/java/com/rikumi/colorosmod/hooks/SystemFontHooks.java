package com.rikumi.colorosmod.hooks;

import android.content.SharedPreferences;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import com.rikumi.colorosmod.FontFileInfo;
import com.rikumi.colorosmod.XposedInit;
import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipFile;

/** Replace system fallback families before FontManagerService serializes the shared font map. */
public final class SystemFontHooks {
    public static final String PREFS = "system_fonts";
    public static final String ZH = "system_font_zh", LATIN = "system_font_latin", MONO = "system_font_mono";
    private static final String FONT_ROOT = "/data/fonts/files";
    private static final String CACHE_NAME = "coloros-mod";
    private static XposedInit module;
    private static SharedPreferences prefs;
    private static final Map<String, FontFamily> families = new HashMap<>();
    private static final Map<String, File> files = new HashMap<>();
    private static final Set<String> errors = new HashSet<>();

    public static void attach(XposedInit instance) { module = instance; }
    static boolean customMonospaceEnabled() {
        if (prefs == null) prefs = module.getRemotePreferences(PREFS);
        return prefs.getInt(MONO, 0) == 4;
    }
    static void deoptimize(java.lang.reflect.Method method) { module.deoptimize(method); }

    public static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        try {
            prefs = module.getRemotePreferences(PREFS);
            // The font_data_file SELinux label allows apps to reopen serialized fonts.
            // UpdatableFontDir rejects every unregistered child, and deleteAllFiles also uses
            // File.listFiles through FileUtils. Hide only our directory from these enumerations;
            // native update files, their signatures and the update config remain untouched.
            XposedHelpers.findAndHookMethod(File.class, "listFiles", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!FONT_ROOT.equals(((File) p.thisObject).getAbsolutePath())
                            || !(p.getResult() instanceof File[] children)) return;
                    p.setResult(Arrays.stream(children).filter(file -> !CACHE_NAME.equals(file.getName()))
                            .toArray(File[]::new));
                }
            });
            Class<?> systemFonts = XposedHelpers.findClass("android.graphics.fonts.SystemFonts", pkg.classLoader);
            // Signature and ColorOS named families verified against the target framework.jar.
            XposedBridge.hookAllMethods(systemFonts, "createFontFamily", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.getResult() instanceof FontFamily original)) return;
                    String language = (String) p.args[1];
                    boolean chinese = Arrays.stream(language.split(",")).anyMatch(s -> s.startsWith("zh"));
                    boolean serif = false;
                    for (int i = 0; i < original.getSize(); i++) {
                        File file = original.getFont(i).getFile();
                        if (file == null) continue;
                        String name = file.getName().toLowerCase(Locale.ROOT);
                        chinese |= name.contains("hans") || name.contains("hant");
                        serif |= name.contains("serif");
                    }
                    if (!chinese || serif) return;
                    FontFamily replacement = family(ZH, language, (Integer) p.args[2], (Boolean) p.args[4]);
                    if (replacement != null) p.setResult(replacement);
                }
            });
            XposedBridge.hookAllMethods(systemFonts, "buildSystemFallback", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.getResult() instanceof Map<?, ?>)) return;
                    @SuppressWarnings("unchecked") Map<String, FontFamily[]> map = (Map<String, FontFamily[]>) p.getResult();
                    Map<String, FontFamily[]> result = new LinkedHashMap<>(map);
                    for (Map.Entry<String, FontFamily[]> entry : map.entrySet()) {
                        String name = entry.getKey();
                        boolean mono = com.rikumi.colorosmod.MonospacePolicy.isMonospaceName(name);
                        boolean latin = name.equals("sans-serif") || name.startsWith("sans-serif-")
                                || name.equals("sys-sans-en") || name.equals("op-sans-en");
                        if ((!mono && !latin) || entry.getValue().length == 0) continue;
                        FontFamily replacement = family(mono ? MONO : LATIN, "", 0, true);
                        if (replacement == null) continue;
                        FontFamily[] chain = entry.getValue().clone();
                        chain[0] = replacement;
                        result.put(name, chain);
                    }
                    p.setResult(result);
                }
            });
            XposedInit.log("HOOK OK SystemFonts#createFontFamily/buildSystemFallback (system fonts)");
        } catch (Throwable t) { XposedInit.log("HOOK FAIL system fonts: " + t); }
    }

    private static synchronized FontFamily family(String slot, String language, int variant, boolean defaultFallback) {
        try {
            int choice = prefs.getInt(slot, 0);
            if (!slot.equals(MONO) && choice == 0) return null;
            String[] sources;
            int custom = slot.equals(ZH) ? 2 : slot.equals(LATIN) ? 3 : 4;
            if (choice == custom) {
                String remote = prefs.getString(slot + "_file", "");
                if (remote.isEmpty()) return null;
                sources = new String[]{"remote:" + remote};
            } else if (slot.equals(ZH) && choice == 1) {
                sources = new String[]{"system_fonts/SourceHanSansSC.ttf"};
            } else if (slot.equals(LATIN) && (choice == 1 || choice == 2)) {
                sources = new String[]{"clock_fonts/" + (choice == 1 ? "Inter" : "Manrope") + ".ttf"};
            } else if (slot.equals(MONO) && choice >= 0 && choice <= 3) {
                String font = new String[]{"MapleMono", "JetBrainsMono", "FiraCode", "CascadiaCode"}[choice];
                sources = choice == 2 ? new String[]{"system_fonts/" + font + ".ttf"}
                        : new String[]{"system_fonts/" + font + ".ttf", "system_fonts/" + font + "-Italic.ttf"};
            } else return null;
            String key = Arrays.toString(sources) + language + variant + defaultFallback;
            FontFamily cached = families.get(key);
            if (cached != null) return cached;
            List<File> fontFiles = new ArrayList<>();
            List<List<FontFileInfo.Face>> metadata = new ArrayList<>();
            for (String source : sources) {
                File file = materialize(source);
                List<FontFileInfo.Face> faces = FontFileInfo.read(file);
                if (slot.equals(MONO)) faces.removeIf(face -> !face.monospace);
                if (faces.isEmpty()) throw new IOException("No monospace face in selected font");
                fontFiles.add(file); metadata.add(faces);
            }
            FontFamily.Builder builder = null;
            Set<String> styles = new HashSet<>();
            SortedSet<Integer> weights = new TreeSet<>();
            for (int weight = 100; weight <= 900; weight += 100) weights.add(weight);
            for (List<FontFileInfo.Face> faces : metadata) for (FontFileInfo.Face face : faces) {
                weights.add(face.weight);
                if (face.variable()) { weights.add(Math.round(face.minWeight)); weights.add(Math.round(face.maxWeight)); }
            }
            for (boolean italic : new boolean[]{false, true}) for (int weight : weights) {
                FontFileInfo.Face best = null; File file = null; int score = Integer.MAX_VALUE;
                for (int i = 0; i < metadata.size(); i++) {
                    FontFileInfo.Face face = FontFileInfo.match(metadata.get(i), weight, italic, language);
                    int candidate = Math.abs(face.weightAt(weight) - weight) + (face.italic == italic ? 0 : 2000);
                    if (candidate < score) { best = face; file = fontFiles.get(i); score = candidate; }
                }
                int actualWeight = best.weightAt(weight), slant = best.italic ? 1 : 0;
                if (!styles.add(actualWeight + ":" + slant)) continue;
                Font.Builder font = new Font.Builder(file).setTtcIndex(best.index).setWeight(actualWeight).setSlant(slant);
                if (best.variable()) font.setFontVariationSettings("'wght' " + actualWeight);
                Font built = font.build();
                if (builder == null) builder = new FontFamily.Builder(built); else builder.addFont(built);
            }
            // Preserve language/variant metadata, otherwise CJK fallback selection changes.
            FontFamily family = (FontFamily) XposedHelpers.callMethod(builder, "build", language, variant,
                    false, defaultFallback, 0);
            families.put(key, family);
            return family;
        } catch (Throwable t) {
            if (errors.add(slot)) XposedInit.log("system font load failed (" + slot + "): " + t);
            return null;
        }
    }

    private static File materialize(String source) throws Exception {
        File existing = files.get(source);
        if (existing != null && existing.isFile()) return existing;
        // Typeface serialization stores file paths. Apps must be able to reopen them after fork.
        // The parent must already have init's font_data_file label; do not create it ourselves.
        if (!new File(FONT_ROOT).isDirectory()) throw new IOException("System font directory unavailable");
        File directory = new File(FONT_ROOT, CACHE_NAME);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create font cache");
        Os.chmod(directory.getAbsolutePath(), 0755);
        File temporary = File.createTempFile("import-", ".font", directory);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = open(source); FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] bytes = new byte[65536]; int count; long total = 0;
                while ((count = input.read(bytes)) != -1) {
                    total += count;
                    if (total > FontFileInfo.MAX_BYTES) throw new IOException("Font too large");
                    output.write(bytes, 0, count); digest.update(bytes, 0, count);
                }
                output.getFD().sync();
            }
            FontFileInfo.read(temporary);
            StringBuilder name = new StringBuilder();
            for (byte b : digest.digest()) name.append(String.format(Locale.ROOT, "%02x", b & 255));
            File target = new File(directory, name + ".font");
            Os.chmod(temporary.getAbsolutePath(), 0644);
            if (!target.isFile() && !temporary.renameTo(target)) throw new IOException("Cannot publish font cache");
            files.put(source, target);
            return target;
        } finally { temporary.delete(); }
    }

    private static InputStream open(String source) throws IOException {
        if (source.startsWith("remote:")) {
            return new ParcelFileDescriptor.AutoCloseInputStream(module.openRemoteFile(source.substring(7)));
        }
        ZipFile zip = new ZipFile(module.getModuleApplicationInfo().sourceDir);
        try {
            java.util.zip.ZipEntry entry = zip.getEntry("assets/" + source);
            if (entry == null) throw new FileNotFoundException(source);
            return new FilterInputStream(zip.getInputStream(entry)) {
                @Override public void close() throws IOException { try { super.close(); } finally { zip.close(); } }
            };
        } catch (Throwable t) { zip.close(); throw t; }
    }
    private SystemFontHooks() {}
}
