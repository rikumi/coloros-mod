package com.rikumi.colorosmod.hooks;

import java.util.Arrays;
import java.io.*;

public final class WhiteNotificationIconMaskTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError(checks); }
    public static void main(String[] args) throws Exception {
        int[] pixels = new int[100]; Arrays.fill(pixels, 0xff0080ff);
        check(WhiteNotificationIconMask.extract(pixels) == null);
        Arrays.fill(pixels, 0xffffffff);
        check(WhiteNotificationIconMask.extract(pixels) == null);
        Arrays.fill(pixels, 0x00ffffff);
        check(WhiteNotificationIconMask.extract(pixels) == null);
        Arrays.fill(pixels, 0xff00b868);
        for (int i = 20; i < 40; i++) pixels[i] = 0xffffffff;
        pixels[40] = 0x80ffffff;
        int[] mask = WhiteNotificationIconMask.extract(pixels);
        check(mask != null && mask[0] == 0 && mask[20] == 0xffffffff);
        check((mask[40] >>> 24) == 128);
        pixels[41] = 0xffffff00;
        pixels[42] = 0xffeeeeee;
        pixels[43] = 0xff80dcb4;
        mask = WhiteNotificationIconMask.extract(pixels);
        check(mask[41] == 0);
        check(Math.abs((mask[43] >>> 24) - 128) <= 1);
        check((mask[42] >>> 24) > 0 && (mask[42] >>> 24) < 255);
        check(pixels[0] == 0xff00b868);
        check(WhiteNotificationIconMask.extract(new int[0]) == null);
        if (args.length == 2) {
            try (DataInputStream in = new DataInputStream(new FileInputStream(args[0]));
                    DataOutputStream out = new DataOutputStream(new FileOutputStream(args[1]))) {
                int count = in.readInt(); int[] image = new int[count];
                for (int i = 0; i < count; i++) image[i] = in.readInt();
                int[] result = WhiteNotificationIconMask.extract(image);
                check(result != null);
                for (int p : result) out.writeInt(p);
            }
        }
        System.out.println("PASS: " + checks + " white foreground mask checks");
    }
}
