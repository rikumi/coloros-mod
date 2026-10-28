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

public final class SystemFontHooks {
    public static final String PREFS = "system_fonts";
    public static final String ZH = "system_font_zh", LATIN = "system_font_latin", MONO = "system_font_mono";
    public static final String EMOJI = "system_font_emoji";
    public static final String DISABLE_REPLACEMENT = "system_font_disable_replacement";
    private static final String FONT_ROOT = "/data/fonts/files";
    private static final String CACHE_NAME = "coloros-mod";
    private static XposedInit module;
    private static SharedPreferences prefs;
    private static final Map<String, FontFamily> families = new HashMap<>();
    private static final Map<String, File> files = new HashMap<>();
    private static final Set<String> errors = new HashSet<>();
    private static final Set<FontFamily> emojiFamilies = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    public static void attach(XposedInit instance) { module = instance; }
    static boolean fontReplacementDisabled() {
        if (prefs == null) prefs = module.getRemotePreferences(PREFS);
        return prefs.getBoolean(DISABLE_REPLACEMENT, false);
    }
    static void deoptimize(java.lang.reflect.Executable method) { module.deoptimize(method); }

    public static void hook(XC_LoadPackage.LoadPackageParam pkg) {
        if (android.os.Build.VERSION.SDK_INT < 29) return;
        try {
            prefs = module.getRemotePreferences(PREFS);

            XposedHelpers.findAndHookMethod(File.class, "listFiles", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!FONT_ROOT.equals(((File) p.thisObject).getAbsolutePath())
                            || !(p.getResult() instanceof File[] children)) return;
                    p.setResult(Arrays.stream(children).filter(file -> !CACHE_NAME.equals(file.getName()) && !"coloros-mod-webview".equals(file.getName()))
                            .toArray(File[]::new));
                }
            });
            Class<?> systemFonts = XposedHelpers.findClass("android.graphics.fonts.SystemFonts", pkg.classLoader);

            XposedBridge.hookAllMethods(systemFonts, "createFontFamily", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.getResult() instanceof FontFamily original)) return;
                    String language = (String) p.args[1];
                    boolean emoji = language.contains("Zsye");
                    boolean chinese = Arrays.stream(language.split(",")).anyMatch(s -> s.startsWith("zh"));
                    boolean serif = false;
                    for (int i = 0; i < original.getSize(); i++) {
                        File file = original.getFont(i).getFile();
                        if (file == null) continue;
                        String name = file.getName().toLowerCase(Locale.ROOT);
                        chinese |= name.contains("hans") || name.contains("hant");
                        serif |= name.contains("serif");
                    }
                    if (!emoji && (!chinese || serif)) return;
                    FontFamily replacement = family(emoji ? EMOJI : ZH, language, (Integer) p.args[2], (Boolean) p.args[4]);
                    if (replacement != null) p.setResult(replacement);
                    if (emoji) emojiFamilies.add(replacement != null ? replacement : original);
                }
            });
            XposedBridge.hookAllMethods(systemFonts, "buildSystemFallback", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.getResult() instanceof Map<?, ?>)) return;
                    @SuppressWarnings("unchecked") Map<String, FontFamily[]> map = (Map<String, FontFamily[]>) p.getResult();
                    Map<String, FontFamily[]> result = new LinkedHashMap<>(map);
                    for (Map.Entry<String, FontFamily[]> entry : map.entrySet()) {
                        String name = entry.getKey();
                        boolean mono = com.rikumi.colorosmod.FontOverlayXml.MonospaceNames.matches(name);
                        boolean latin = name.equals("sans-serif") || name.startsWith("sans-serif-")
                                || name.equals("sys-sans-en") || name.equals("op-sans-en");
                        if ((!mono && !latin) || entry.getValue().length == 0) continue;
                        FontFamily replacement = family(mono ? MONO : LATIN, "", 0, true);
                        if (replacement == null) continue;
                        FontFamily[] chain = entry.getValue().clone();
                        chain[0] = replacement;
                        result.put(name, chain);
                    }
                    boolean active = prefs.getInt(ZH, 0) > 0 || prefs.getInt(LATIN, 0) > 0
                            || prefs.getInt(MONO, -1) >= 0 || prefs.getInt(EMOJI, 0) > 0;
                    if (active) for (Map.Entry<String, FontFamily[]> entry : result.entrySet()) {
                        FontFamily[] chain = entry.getValue();
                        if (chain.length < 2) continue;
                        List<FontFamily> ordered = new ArrayList<>(); ordered.add(chain[0]);
                        for (int i = 1; i < chain.length; i++) if (emojiFamilies.contains(chain[i]) && !ordered.contains(chain[i])) ordered.add(chain[i]);
                        for (int i = 1; i < chain.length; i++) if (!emojiFamilies.contains(chain[i])) ordered.add(chain[i]);
                        entry.setValue(ordered.toArray(new FontFamily[0]));
                    }
                    p.setResult(result);
                }
            });
            XposedInit.log("HOOK OK SystemFonts#createFontFamily/buildSystemFallback (system fonts)");
        } catch (Throwable t) { XposedInit.log("HOOK FAIL system fonts: " + t); }
    }

    private static synchronized FontFamily family(String slot, String language, int variant, boolean defaultFallback) {
        try {
            int choice = prefs.getInt(slot, slot.equals(MONO) ? -1 : 0);
            if ((slot.equals(MONO) && choice < 0) || (slot.equals(EMOJI) && choice == 0)) return null;
            String[] sources;
            int custom = slot.equals(ZH) ? 2 : slot.equals(LATIN) ? 3 : 4;
            if ((slot.equals(ZH) || slot.equals(LATIN)) && choice == 0) {
                sources = com.rikumi.colorosmod.SystemOppoFonts.find(slot.equals(ZH)).stream()
                        .map(file -> "system:" + file.getAbsolutePath()).toArray(String[]::new);
            } else if (slot.equals(EMOJI)) {
                String remote = prefs.getString(EMOJI + "_file", "");
                if (choice < 1 || choice > 4 || remote.isEmpty()) return null;
                sources = new String[]{"remote:" + remote};
            } else if (choice == custom) {
                String remote = prefs.getString(slot + "_file", "");
                if (remote.isEmpty()) return null;
                sources = new String[]{"remote:" + remote};
            } else if (slot.equals(ZH) && choice == 1) {
                sources = com.rikumi.colorosmod.SystemCjkFonts.find().stream()
                        .map(file -> "system:" + file.getAbsolutePath()).toArray(String[]::new);
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
                File file = materialize(source, !slot.equals(EMOJI));
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
                    int candidate = FontFileInfo.matchScore(face, weight, italic, language);
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

    private static File materialize(String source, boolean emojiPriority) throws Exception {
        if (source.startsWith("system:")) return new File(source.substring(7));
        String cacheKey = source + ":" + emojiPriority;
        File existing = files.get(cacheKey);
        if (existing != null && existing.isFile()) return existing;

        if (!new File(FONT_ROOT).isDirectory()) throw new IOException("System font directory unavailable");
        File directory = new File(FONT_ROOT, CACHE_NAME);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create font cache");
        Os.chmod(directory.getAbsolutePath(), 0755);
        File temporary = File.createTempFile("import-", ".font", directory);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream raw = open(source)) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[65536]; int size; long total = 0;
                while ((size = raw.read(chunk)) != -1) {
                    total += size;
                    if (total > FontFileInfo.MAX_BYTES) throw new IOException("Font too large");
                    buffer.write(chunk, 0, size);
                }
                byte[] contents = buffer.toByteArray();
                if (emojiPriority) contents = com.rikumi.colorosmod.EmojiFontPriority.apply(contents);
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(contents); digest.update(contents); output.getFD().sync();
                }
            }
            FontFileInfo.read(temporary);
            StringBuilder name = new StringBuilder();
            for (byte b : digest.digest()) name.append(String.format(Locale.ROOT, "%02x", b & 255));
            File target = new File(directory, name + ".font");
            Os.chmod(temporary.getAbsolutePath(), 0644);
            if (!target.isFile() && !temporary.renameTo(target)) throw new IOException("Cannot publish font cache");
            files.put(cacheKey, target);
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
