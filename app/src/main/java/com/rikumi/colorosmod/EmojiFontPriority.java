package com.rikumi.colorosmod;

import java.io.*;
import java.nio.*;
import java.util.*;

/** Removes ambiguous emoji from selected text fonts while keeping explicit VS15 text glyphs. */
public final class EmojiFontPriority {
    // Unicode 17 emoji-data.txt, Emoji property; keycap bases # * 0-9 stay ordinary text.
    private static final int[] RANGES = { 0x23, 0x23, 0x2a, 0x2a, 0x30, 0x39, 0xa9, 0xa9, 0xae, 0xae, 0x203c, 0x203c, 0x2049, 0x2049, 0x2122, 0x2122, 0x2139, 0x2139, 0x2194, 0x2199, 0x21a9, 0x21aa, 0x231a, 0x231b, 0x2328, 0x2328, 0x23cf, 0x23cf, 0x23e9, 0x23ec, 0x23ed, 0x23ee, 0x23ef, 0x23ef, 0x23f0, 0x23f0, 0x23f1, 0x23f2, 0x23f3, 0x23f3, 0x23f8, 0x23fa, 0x24c2, 0x24c2, 0x25aa, 0x25ab, 0x25b6, 0x25b6, 0x25c0, 0x25c0, 0x25fb, 0x25fe, 0x2600, 0x2601, 0x2602, 0x2603, 0x2604, 0x2604, 0x260e, 0x260e, 0x2611, 0x2611, 0x2614, 0x2615, 0x2618, 0x2618, 0x261d, 0x261d, 0x2620, 0x2620, 0x2622, 0x2623, 0x2626, 0x2626, 0x262a, 0x262a, 0x262e, 0x262e, 0x262f, 0x262f, 0x2638, 0x2639, 0x263a, 0x263a, 0x2640, 0x2640, 0x2642, 0x2642, 0x2648, 0x2653, 0x265f, 0x265f, 0x2660, 0x2660, 0x2663, 0x2663, 0x2665, 0x2666, 0x2668, 0x2668, 0x267b, 0x267b, 0x267e, 0x267e, 0x267f, 0x267f, 0x2692, 0x2692, 0x2693, 0x2693, 0x2694, 0x2694, 0x2695, 0x2695, 0x2696, 0x2697, 0x2699, 0x2699, 0x269b, 0x269c, 0x26a0, 0x26a1, 0x26a7, 0x26a7, 0x26aa, 0x26ab, 0x26b0, 0x26b1, 0x26bd, 0x26be, 0x26c4, 0x26c5, 0x26c8, 0x26c8, 0x26ce, 0x26ce, 0x26cf, 0x26cf, 0x26d1, 0x26d1, 0x26d3, 0x26d3, 0x26d4, 0x26d4, 0x26e9, 0x26e9, 0x26ea, 0x26ea, 0x26f0, 0x26f1, 0x26f2, 0x26f3, 0x26f4, 0x26f4, 0x26f5, 0x26f5, 0x26f7, 0x26f9, 0x26fa, 0x26fa, 0x26fd, 0x26fd, 0x2702, 0x2702, 0x2705, 0x2705, 0x2708, 0x270c, 0x270d, 0x270d, 0x270f, 0x270f, 0x2712, 0x2712, 0x2714, 0x2714, 0x2716, 0x2716, 0x271d, 0x271d, 0x2721, 0x2721, 0x2728, 0x2728, 0x2733, 0x2734, 0x2744, 0x2744, 0x2747, 0x2747, 0x274c, 0x274c, 0x274e, 0x274e, 0x2753, 0x2755, 0x2757, 0x2757, 0x2763, 0x2763, 0x2764, 0x2764, 0x2795, 0x2797, 0x27a1, 0x27a1, 0x27b0, 0x27b0, 0x27bf, 0x27bf, 0x2934, 0x2935, 0x2b05, 0x2b07, 0x2b1b, 0x2b1c, 0x2b50, 0x2b50, 0x2b55, 0x2b55, 0x3030, 0x3030, 0x303d, 0x303d, 0x3297, 0x3297, 0x3299, 0x3299, 0x1f004, 0x1f004, 0x1f0cf, 0x1f0cf, 0x1f170, 0x1f171, 0x1f17e, 0x1f17f, 0x1f18e, 0x1f18e, 0x1f191, 0x1f19a, 0x1f1e6, 0x1f1ff, 0x1f201, 0x1f202, 0x1f21a, 0x1f21a, 0x1f22f, 0x1f22f, 0x1f232, 0x1f23a, 0x1f250, 0x1f251, 0x1f300, 0x1f30c, 0x1f30d, 0x1f30e, 0x1f30f, 0x1f30f, 0x1f310, 0x1f310, 0x1f311, 0x1f311, 0x1f312, 0x1f312, 0x1f313, 0x1f315, 0x1f316, 0x1f318, 0x1f319, 0x1f319, 0x1f31a, 0x1f31a, 0x1f31b, 0x1f31b, 0x1f31c, 0x1f31c, 0x1f31d, 0x1f31e, 0x1f31f, 0x1f320, 0x1f321, 0x1f321, 0x1f324, 0x1f32c, 0x1f32d, 0x1f32f, 0x1f330, 0x1f331, 0x1f332, 0x1f333, 0x1f334, 0x1f335, 0x1f336, 0x1f336, 0x1f337, 0x1f34a, 0x1f34b, 0x1f34b, 0x1f34c, 0x1f34f, 0x1f350, 0x1f350, 0x1f351, 0x1f37b, 0x1f37c, 0x1f37c, 0x1f37d, 0x1f37d, 0x1f37e, 0x1f37f, 0x1f380, 0x1f393, 0x1f396, 0x1f397, 0x1f399, 0x1f39b, 0x1f39e, 0x1f39f, 0x1f3a0, 0x1f3c4, 0x1f3c5, 0x1f3c5, 0x1f3c6, 0x1f3c6, 0x1f3c7, 0x1f3c7, 0x1f3c8, 0x1f3c8, 0x1f3c9, 0x1f3c9, 0x1f3ca, 0x1f3ca, 0x1f3cb, 0x1f3ce, 0x1f3cf, 0x1f3d3, 0x1f3d4, 0x1f3df, 0x1f3e0, 0x1f3e3, 0x1f3e4, 0x1f3e4, 0x1f3e5, 0x1f3f0, 0x1f3f3, 0x1f3f3, 0x1f3f4, 0x1f3f4, 0x1f3f5, 0x1f3f5, 0x1f3f7, 0x1f3f7, 0x1f3f8, 0x1f407, 0x1f408, 0x1f408, 0x1f409, 0x1f40b, 0x1f40c, 0x1f40e, 0x1f40f, 0x1f410, 0x1f411, 0x1f412, 0x1f413, 0x1f413, 0x1f414, 0x1f414, 0x1f415, 0x1f415, 0x1f416, 0x1f416, 0x1f417, 0x1f429, 0x1f42a, 0x1f42a, 0x1f42b, 0x1f43e, 0x1f43f, 0x1f43f, 0x1f440, 0x1f440, 0x1f441, 0x1f441, 0x1f442, 0x1f464, 0x1f465, 0x1f465, 0x1f466, 0x1f46b, 0x1f46c, 0x1f46d, 0x1f46e, 0x1f4ac, 0x1f4ad, 0x1f4ad, 0x1f4ae, 0x1f4b5, 0x1f4b6, 0x1f4b7, 0x1f4b8, 0x1f4eb, 0x1f4ec, 0x1f4ed, 0x1f4ee, 0x1f4ee, 0x1f4ef, 0x1f4ef, 0x1f4f0, 0x1f4f4, 0x1f4f5, 0x1f4f5, 0x1f4f6, 0x1f4f7, 0x1f4f8, 0x1f4f8, 0x1f4f9, 0x1f4fc, 0x1f4fd, 0x1f4fd, 0x1f4ff, 0x1f502, 0x1f503, 0x1f503, 0x1f504, 0x1f507, 0x1f508, 0x1f508, 0x1f509, 0x1f509, 0x1f50a, 0x1f514, 0x1f515, 0x1f515, 0x1f516, 0x1f52b, 0x1f52c, 0x1f52d, 0x1f52e, 0x1f53d, 0x1f549, 0x1f54a, 0x1f54b, 0x1f54e, 0x1f550, 0x1f55b, 0x1f55c, 0x1f567, 0x1f56f, 0x1f570, 0x1f573, 0x1f579, 0x1f57a, 0x1f57a, 0x1f587, 0x1f587, 0x1f58a, 0x1f58d, 0x1f590, 0x1f590, 0x1f595, 0x1f596, 0x1f5a4, 0x1f5a4, 0x1f5a5, 0x1f5a5, 0x1f5a8, 0x1f5a8, 0x1f5b1, 0x1f5b2, 0x1f5bc, 0x1f5bc, 0x1f5c2, 0x1f5c4, 0x1f5d1, 0x1f5d3, 0x1f5dc, 0x1f5de, 0x1f5e1, 0x1f5e1, 0x1f5e3, 0x1f5e3, 0x1f5e8, 0x1f5e8, 0x1f5ef, 0x1f5ef, 0x1f5f3, 0x1f5f3, 0x1f5fa, 0x1f5fa, 0x1f5fb, 0x1f5ff, 0x1f600, 0x1f600, 0x1f601, 0x1f606, 0x1f607, 0x1f608, 0x1f609, 0x1f60d, 0x1f60e, 0x1f60e, 0x1f60f, 0x1f60f, 0x1f610, 0x1f610, 0x1f611, 0x1f611, 0x1f612, 0x1f614, 0x1f615, 0x1f615, 0x1f616, 0x1f616, 0x1f617, 0x1f617, 0x1f618, 0x1f618, 0x1f619, 0x1f619, 0x1f61a, 0x1f61a, 0x1f61b, 0x1f61b, 0x1f61c, 0x1f61e, 0x1f61f, 0x1f61f, 0x1f620, 0x1f625, 0x1f626, 0x1f627, 0x1f628, 0x1f62b, 0x1f62c, 0x1f62c, 0x1f62d, 0x1f62d, 0x1f62e, 0x1f62f, 0x1f630, 0x1f633, 0x1f634, 0x1f634, 0x1f635, 0x1f635, 0x1f636, 0x1f636, 0x1f637, 0x1f640, 0x1f641, 0x1f644, 0x1f645, 0x1f64f, 0x1f680, 0x1f680, 0x1f681, 0x1f682, 0x1f683, 0x1f685, 0x1f686, 0x1f686, 0x1f687, 0x1f687, 0x1f688, 0x1f688, 0x1f689, 0x1f689, 0x1f68a, 0x1f68b, 0x1f68c, 0x1f68c, 0x1f68d, 0x1f68d, 0x1f68e, 0x1f68e, 0x1f68f, 0x1f68f, 0x1f690, 0x1f690, 0x1f691, 0x1f693, 0x1f694, 0x1f694, 0x1f695, 0x1f695, 0x1f696, 0x1f696, 0x1f697, 0x1f697, 0x1f698, 0x1f698, 0x1f699, 0x1f69a, 0x1f69b, 0x1f6a1, 0x1f6a2, 0x1f6a2, 0x1f6a3, 0x1f6a3, 0x1f6a4, 0x1f6a5, 0x1f6a6, 0x1f6a6, 0x1f6a7, 0x1f6ad, 0x1f6ae, 0x1f6b1, 0x1f6b2, 0x1f6b2, 0x1f6b3, 0x1f6b5, 0x1f6b6, 0x1f6b6, 0x1f6b7, 0x1f6b8, 0x1f6b9, 0x1f6be, 0x1f6bf, 0x1f6bf, 0x1f6c0, 0x1f6c0, 0x1f6c1, 0x1f6c5, 0x1f6cb, 0x1f6cb, 0x1f6cc, 0x1f6cc, 0x1f6cd, 0x1f6cf, 0x1f6d0, 0x1f6d0, 0x1f6d1, 0x1f6d2, 0x1f6d5, 0x1f6d5, 0x1f6d6, 0x1f6d7, 0x1f6d8, 0x1f6d8, 0x1f6dc, 0x1f6dc, 0x1f6dd, 0x1f6df, 0x1f6e0, 0x1f6e5, 0x1f6e9, 0x1f6e9, 0x1f6eb, 0x1f6ec, 0x1f6f0, 0x1f6f0, 0x1f6f3, 0x1f6f3, 0x1f6f4, 0x1f6f6, 0x1f6f7, 0x1f6f8, 0x1f6f9, 0x1f6f9, 0x1f6fa, 0x1f6fa, 0x1f6fb, 0x1f6fc, 0x1f7e0, 0x1f7eb, 0x1f7f0, 0x1f7f0, 0x1f90c, 0x1f90c, 0x1f90d, 0x1f90f, 0x1f910, 0x1f918, 0x1f919, 0x1f91e, 0x1f91f, 0x1f91f, 0x1f920, 0x1f927, 0x1f928, 0x1f92f, 0x1f930, 0x1f930, 0x1f931, 0x1f932, 0x1f933, 0x1f93a, 0x1f93c, 0x1f93e, 0x1f93f, 0x1f93f, 0x1f940, 0x1f945, 0x1f947, 0x1f94b, 0x1f94c, 0x1f94c, 0x1f94d, 0x1f94f, 0x1f950, 0x1f95e, 0x1f95f, 0x1f96b, 0x1f96c, 0x1f970, 0x1f971, 0x1f971, 0x1f972, 0x1f972, 0x1f973, 0x1f976, 0x1f977, 0x1f978, 0x1f979, 0x1f979, 0x1f97a, 0x1f97a, 0x1f97b, 0x1f97b, 0x1f97c, 0x1f97f, 0x1f980, 0x1f984, 0x1f985, 0x1f991, 0x1f992, 0x1f997, 0x1f998, 0x1f9a2, 0x1f9a3, 0x1f9a4, 0x1f9a5, 0x1f9aa, 0x1f9ab, 0x1f9ad, 0x1f9ae, 0x1f9af, 0x1f9b0, 0x1f9b9, 0x1f9ba, 0x1f9bf, 0x1f9c0, 0x1f9c0, 0x1f9c1, 0x1f9c2, 0x1f9c3, 0x1f9ca, 0x1f9cb, 0x1f9cb, 0x1f9cc, 0x1f9cc, 0x1f9cd, 0x1f9cf, 0x1f9d0, 0x1f9e6, 0x1f9e7, 0x1f9ff, 0x1fa70, 0x1fa73, 0x1fa74, 0x1fa74, 0x1fa75, 0x1fa77, 0x1fa78, 0x1fa7a, 0x1fa7b, 0x1fa7c, 0x1fa80, 0x1fa82, 0x1fa83, 0x1fa86, 0x1fa87, 0x1fa88, 0x1fa89, 0x1fa89, 0x1fa8a, 0x1fa8a, 0x1fa8e, 0x1fa8e, 0x1fa8f, 0x1fa8f, 0x1fa90, 0x1fa95, 0x1fa96, 0x1faa8, 0x1faa9, 0x1faac, 0x1faad, 0x1faaf, 0x1fab0, 0x1fab6, 0x1fab7, 0x1faba, 0x1fabb, 0x1fabd, 0x1fabe, 0x1fabe, 0x1fabf, 0x1fabf, 0x1fac0, 0x1fac2, 0x1fac3, 0x1fac5, 0x1fac6, 0x1fac6, 0x1fac8, 0x1fac8, 0x1facd, 0x1facd, 0x1face, 0x1facf, 0x1fad0, 0x1fad6, 0x1fad7, 0x1fad9, 0x1fada, 0x1fadb, 0x1fadc, 0x1fadc, 0x1fadf, 0x1fadf, 0x1fae0, 0x1fae7, 0x1fae8, 0x1fae8, 0x1fae9, 0x1fae9, 0x1faea, 0x1faea, 0x1faef, 0x1faef, 0x1faf0, 0x1faf6, 0x1faf7, 0x1faf8 };
    public static boolean prefersEmoji(int cp) {
        if (cp == '#' || cp == '*' || cp >= '0' && cp <= '9') return false;
        for (int i = 0; i < RANGES.length; i += 2) {
            if (cp < RANGES[i]) return false;
            if (cp <= RANGES[i + 1]) return true;
        }
        return false;
    }
    private static int u16(byte[] b, int p) { return (b[p] & 255) << 8 | b[p + 1] & 255; }
    private static int u24(byte[] b, int p) { return (b[p] & 255) << 16 | u16(b, p + 1); }
    private static int i32(byte[] b, int p) { return ByteBuffer.wrap(b, p, 4).getInt(); }
    private static void put32(byte[] b, int p, int n) { ByteBuffer.wrap(b, p, 4).putInt(n); }
    private static long checksum(byte[] b) {
        long sum = 0;
        for (int i = 0; i < b.length; i += 4) {
            long word = 0;
            for (int j = 0; j < 4; j++) word = word << 8 | (i + j < b.length ? b[i + j] & 255 : 0);
            sum += word;
        }
        return sum & 0xffffffffL;
    }
    private static TreeMap<Integer,Integer> mapping(byte[] data, int sub) throws IOException {
        TreeMap<Integer,Integer> map = new TreeMap<>();
        int format = u16(data, sub);
        if (format == 12) {
            int groups = i32(data, sub + 12);
            if (groups < 0 || groups > 0x110000) throw new IOException("Invalid cmap groups");
            for (int i = 0; i < groups; i++) {
                int p = sub + 16 + i * 12, start = i32(data, p), end = i32(data, p + 4), glyph = i32(data, p + 8);
                if (start < 0 || end > 0x10ffff || end < start) throw new IOException("Invalid cmap range");
                for (int cp = start; cp <= end; cp++) if (glyph + cp - start != 0) map.put(cp, glyph + cp - start);
            }
        } else if (format == 4) {
            int segments = u16(data, sub + 6) / 2;
            for (int i = 0; i < segments; i++) {
                int end = u16(data, sub + 14 + i * 2), start = u16(data, sub + 16 + segments * 2 + i * 2);
                int delta = u16(data, sub + 16 + segments * 4 + i * 2);
                int cell = sub + 16 + segments * 6 + i * 2, range = u16(data, cell);
                for (int cp = start; cp <= end && cp < 0xffff; cp++) {
                    int glyph = range == 0 ? (cp + delta) & 65535 : u16(data, cell + range + (cp - start) * 2);
                    if (range != 0 && glyph != 0) glyph = (glyph + delta) & 65535;
                    if (glyph != 0) map.put(cp, glyph);
                }
            }
        } else throw new IOException("Unsupported Unicode cmap");
        return map;
    }
    private static class Variation {
        final TreeSet<Integer> defaults = new TreeSet<>();
        final TreeMap<Integer,Integer> glyphs = new TreeMap<>();
    }
    private static TreeMap<Integer,Variation> variations(byte[] b, int p) throws IOException {
        TreeMap<Integer,Variation> result = new TreeMap<>();
        if (p == 0) return result;
        int count = i32(b, p + 6);
        if (count < 0 || count > 256) throw new IOException("Invalid variation selectors");
        for (int i = 0; i < count; i++) {
            int rec = p + 10 + i * 11, selector = u24(b, rec);
            int defaults = i32(b, rec + 3), nondefaults = i32(b, rec + 7);
            Variation v = new Variation();
            if (defaults != 0) {
                int at = p + defaults, n = i32(b, at);
                if (n < 0 || n > 0x110000) throw new IOException("Invalid default variations");
                for (int j = 0; j < n; j++) {
                    int start = u24(b, at + 4 + j * 4), extra = b[at + 7 + j * 4] & 255;
                    for (int cp = start; cp <= start + extra; cp++) v.defaults.add(cp);
                }
            }
            if (nondefaults != 0) {
                int at = p + nondefaults, n = i32(b, at);
                if (n < 0 || n > 0x110000) throw new IOException("Invalid nondefault variations");
                for (int j = 0; j < n; j++) v.glyphs.put(u24(b, at + 4 + j * 5), u16(b, at + 7 + j * 5));
            }
            result.put(selector, v);
        }
        return result;
    }
    private static byte[] format12(TreeMap<Integer,Integer> map) throws IOException {
        List<int[]> groups = new ArrayList<>();
        for (var e : map.entrySet()) {
            int cp = e.getKey(), glyph = e.getValue();
            int[] prev = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (prev != null && cp == prev[1] + 1 && glyph == prev[2] + cp - prev[0]) prev[1] = cp;
            else groups.add(new int[]{cp,cp,glyph});
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(buffer);
        out.writeShort(12); out.writeShort(0); out.writeInt(16 + groups.size() * 12); out.writeInt(0); out.writeInt(groups.size());
        for (int[] g : groups) for (int n : g) out.writeInt(n);
        return buffer.toByteArray();
    }
    private static void write24(DataOutputStream out, int cp) throws IOException { out.writeByte(cp >> 16); out.writeShort(cp); }
    private static byte[] format14(TreeMap<Integer,Variation> map) throws IOException {
        int header = 10 + map.size() * 11;
        ByteArrayOutputStream body = new ByteArrayOutputStream(), records = new ByteArrayOutputStream();
        DataOutputStream rec = new DataOutputStream(records), out = new DataOutputStream(body);
        for (var e : map.entrySet()) {
            Variation v = e.getValue(); write24(rec, e.getKey());
            if (v.defaults.isEmpty()) rec.writeInt(0);
            else {
                rec.writeInt(header + body.size());
                List<int[]> ranges = new ArrayList<>();
                for (int cp : v.defaults) {
                    int[] last = ranges.isEmpty() ? null : ranges.get(ranges.size() - 1);
                    if (last != null && cp == last[0] + last[1] + 1 && last[1] < 255) last[1]++;
                    else ranges.add(new int[]{cp,0});
                }
                out.writeInt(ranges.size());
                for (int[] r : ranges) { write24(out, r[0]); out.writeByte(r[1]); }
            }
            if (v.glyphs.isEmpty()) rec.writeInt(0);
            else {
                rec.writeInt(header + body.size()); out.writeInt(v.glyphs.size());
                for (var g : v.glyphs.entrySet()) { write24(out, g.getKey()); out.writeShort(g.getValue()); }
            }
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream(); DataOutputStream all = new DataOutputStream(result);
        all.writeShort(14); all.writeInt(header + body.size()); all.writeInt(map.size());
        all.write(records.toByteArray()); all.write(body.toByteArray()); return result.toByteArray();
    }
    private static byte[] filteredCmap(byte[] data, int cmap) throws IOException {
        int count = u16(data, cmap + 2), best = 0, score = -1, variation = 0;
        for (int i = 0; i < count; i++) {
            int rec = cmap + 4 + i * 8, platform = u16(data, rec), encoding = u16(data, rec + 2);
            int sub = cmap + i32(data, rec + 4), format = u16(data, sub);
            if (platform == 0 && format == 14) variation = sub;
            if (platform != 0 && !(platform == 3 && (encoding == 1 || encoding == 10))) continue;
            int rank = format == 12 ? 2 : format == 4 ? 1 : -1;
            if (rank > score) { score = rank; best = sub; }
        }
        if (score < 0) return null;
        TreeMap<Integer,Integer> glyphs = mapping(data, best), removed = new TreeMap<>();
        for (var e : glyphs.entrySet()) if (prefersEmoji(e.getKey())) removed.put(e.getKey(), e.getValue());
        if (removed.isEmpty()) return null;
        TreeMap<Integer,Variation> vars = variations(data, variation);
        Variation text = vars.computeIfAbsent(0xfe0e, unused -> new Variation());
        Variation emoji = vars.get(0xfe0f);
        for (var e : removed.entrySet()) {
            glyphs.remove(e.getKey()); text.defaults.remove(e.getKey()); text.glyphs.putIfAbsent(e.getKey(), e.getValue());
            if (emoji != null) { emoji.defaults.remove(e.getKey()); emoji.glyphs.remove(e.getKey()); }
        }
        byte[] nominal = format12(glyphs), selectors = format14(vars);
        ByteArrayOutputStream result = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(result);
        out.writeShort(0); out.writeShort(3);
        out.writeShort(0); out.writeShort(4); out.writeInt(28);
        out.writeShort(3); out.writeShort(10); out.writeInt(28);
        out.writeShort(0); out.writeShort(5); out.writeInt(28 + nominal.length);
        out.write(nominal); out.write(selectors); return result.toByteArray();
    }
    public static byte[] apply(byte[] original) throws IOException {
        try {
            boolean ttc = i32(original, 0) == 0x74746366;
            int count = ttc ? i32(original, 8) : 1;
            if (count < 1 || count > 256) throw new IOException("Invalid font collection");
            byte[] result = original;
            for (int face = 0; face < count; face++) {
                int base = ttc ? i32(original, 12 + face * 4) : 0;
                int tables = u16(original, base + 4);
                for (int i = 0; i < tables; i++) {
                    int rec = base + 12 + i * 16;
                    if (i32(original, rec) != 0x636d6170) continue;
                    byte[] cmap = filteredCmap(original, i32(original, rec + 8));
                    if (cmap == null) break;
                    int start = (result.length + 3) & ~3;
                    result = Arrays.copyOf(result, start + ((cmap.length + 3) & ~3));
                    System.arraycopy(cmap, 0, result, start, cmap.length);
                    put32(result, rec + 4, (int) checksum(cmap)); put32(result, rec + 8, start); put32(result, rec + 12, cmap.length);
                    break;
                }
            }
            if (result == original) return original;
            for (int face = 0; face < count; face++) {
                int base = ttc ? i32(original, 12 + face * 4) : 0;
                int tables = u16(original, base + 4);
                for (int i = 0; i < tables; i++) {
                    int rec = base + 12 + i * 16;
                    if (i32(original, rec) == 0x68656164) {
                        int head = i32(original, rec + 8); put32(result, head + 8, 0);
                        if (!ttc) put32(result, head + 8, (int) (0xb1b0afbaL - checksum(result)));
                        break;
                    }
                }
            }
            return result;
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) { throw new IOException("Invalid emoji cmap", e); }
    }
    private EmojiFontPriority() {}
}
