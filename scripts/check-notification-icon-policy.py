#!/usr/bin/env python3
"""Run asset-free regression checks for notification mask and count policy."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HOOKS = ROOT / "app/src/main/java/com/rikumi/colorosmod/hooks"
JDK = Path("/opt/homebrew/opt/openjdk@21/bin")
JAVAC = JDK / "javac"
JAVA = JDK / "java"

MASK_CHECK = r'''package com.rikumi.colorosmod.hooks;
public class MaskCheck {
    private static void check(int[] pixels, boolean expected, String name) {
        if (NotificationIconMask.accepts(pixels) != expected) throw new AssertionError(name);
    }
    public static void main(String[] args) {
        int[] gray = new int[48 * 48], flat = new int[48 * 48], colored = new int[48 * 48];
        for (int i = 0; i < gray.length / 2; i++) {
            int value = 219 + i % 37;
            gray[i] = 0xff000000 | (value << 16) | (value << 8) | value;
            flat[i] = 0xff44aa66;
            colored[i] = i % 2 == 0 ? 0xff00bb00 : 0xffffffff;
        }
        check(gray, true, "native gray shadow");
        check(flat, true, "flat native tint");
        check(colored, false, "multicolor foreground");
        int[] opaqueGray = new int[48 * 48];
        java.util.Arrays.fill(opaqueGray, 0xffffffff);
        check(opaqueGray, false, "opaque background");
        check(new int[48 * 48], false, "empty mask");
        System.out.println("PASS: 5 notification mask checks");
    }
}
'''
PARTITION_CHECK = r'''package com.rikumi.colorosmod.hooks;
import java.util.*;
public class PartitionCheck {
    static int checks;
    static NotificationIconPartition.Item<String> item(String key, String owner, boolean good) {
        return new NotificationIconPartition.Item<>(key, owner, good, key);
    }
    static void check(boolean condition) {
        if (!condition) throw new AssertionError("check " + (checks + 1));
        checks++;
    }
    public static void main(String[] args) {
        var empty = NotificationIconPartition.partition(List.<NotificationIconPartition.Item<String>>of());
        check(empty.missing == 0 && empty.icons.isEmpty());
        var missing = NotificationIconPartition.partition(List.of(item("a", "0:p", false), item("b", "0:p", false)));
        check(missing.missing == 2 && missing.icons.isEmpty());
        var duplicate = NotificationIconPartition.partition(List.of(item("a", "0:p", false), item("a", "0:p", false)));
        check(duplicate.missing == 1);
        var mixed = NotificationIconPartition.partition(List.of(item("a", "0:p", false), item("b", "0:p", true), item("c", "0:p", true)));
        check(mixed.missing == 1 && mixed.icons.equals(List.of("b")));
        var users = NotificationIconPartition.partition(List.of(item("personal", "0:p", true), item("work", "10:p", true)));
        check(users.icons.size() == 2 && users.missing == 0);
        check(NotificationIconPartition.leaves("summary", List.of("child1", "child2")).equals(List.of("child1", "child2")));
        check(NotificationIconPartition.leaves("standalone", List.<String>of()).equals(List.of("standalone")));
        var group = new ArrayList<NotificationIconPartition.Item<String>>();
        for (String key : NotificationIconPartition.leaves("summary", List.of("child1", "child2"))) group.add(item(key, "0:p", false));
        check(NotificationIconPartition.partition(group).missing == 2);
        var overflow = new ArrayList<NotificationIconPartition.Item<String>>();
        for (int i = 0; i < 8; i++) overflow.add(item("good" + i, "0:pkg" + i, true));
        overflow.add(item("bad1", "0:p", false)); overflow.add(item("bad2", "0:p", false));
        var limited = NotificationIconPartition.partition(overflow);
        check(limited.icons.size() == 8 && limited.missing == 2);
        var capped = new ArrayList<NotificationIconPartition.Item<String>>();
        for (int i = 0; i < 120; i++) capped.add(item("bad" + i, "0:p", false));
        check(NotificationIconPartition.partition(capped).missing == 120);
        System.out.println("PASS: " + checks + " notification partition checks");
    }
}
'''
with tempfile.TemporaryDirectory(prefix="notification-policy-", dir="/private/tmp") as tmp:
    tmp = Path(tmp)
    mask = tmp / "MaskCheck.java"
    partition = tmp / "PartitionCheck.java"
    mask.write_text(MASK_CHECK)
    partition.write_text(PARTITION_CHECK)
    subprocess.run(
        [str(JAVAC), "-d", str(tmp), str(HOOKS / "NotificationIconMask.java"),
         str(HOOKS / "NotificationIconPartition.java"), str(mask), str(partition)],
        check=True,
    )
    subprocess.run([str(JAVA), "-cp", str(tmp), "com.rikumi.colorosmod.hooks.MaskCheck"], check=True)
    subprocess.run([str(JAVA), "-cp", str(tmp), "com.rikumi.colorosmod.hooks.PartitionCheck"], check=True)
