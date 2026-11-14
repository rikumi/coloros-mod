package com.rikumi.colorosmod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Measured USB input from Oplus's aggregate charge pump, when power_supply has no ADC. */
final class ChargingChipReader {
    private ChargingChipReader() {}

    // func_id selects a diagnostic query; data is only read, never written. The aggregate
    // driver adds parallel pump currents, avoiding double counting physical/virtual ICs.
    static final String COMMAND = """
        (
        usb=/sys/class/power_supply/usb
        [ "$(cat "$usb/online" 2>/dev/null)" = 1 ] || exit 0
        [ "$(cat /sys/class/power_supply/wireless/online 2>/dev/null)" != 1 ] || exit 0
        before=$(cat "$usb/voltage_now" 2>/dev/null) || exit 0
        for d in /sys/class/virtual_ic/*; do
          [ "$(cat "$d/name" 2>/dev/null)" = oplus,virtual_cp ] || continue
          [ "$(cat "$d/online" 2>/dev/null)" = 1 ] || exit 0
          old=$(cat "$d/func_id" 2>/dev/null) || exit 0
          case "$old" in ''|*[!0-9]*) exit 0;; esac
          selected=$old
          restore() {
            current=$(cat "$d/func_id" 2>/dev/null)
            [ "$current" != "$selected" ] || printf '%s' "$old" > "$d/func_id"
          }
          query() {
            [ "$(cat "$d/func_id" 2>/dev/null)" = "$selected" ] || return 1
            selected=$1
            printf '%s' "$selected" > "$d/func_id" || return 1
            value=$(od -An -v -tu1 "$d/data" 2>/dev/null) || return 1
            [ "$(cat "$d/func_id" 2>/dev/null)" = "$selected" ] || return 1
            printf '%s %s\n' "$2" "$value"
          }
          values=$(
            trap 'restore' EXIT
            trap 'exit 1' HUP INT TERM
            query 312 work && query 306 vin && query 307 iin && query 312 after
          ) || exit 0
          [ "$(cat "$usb/online" 2>/dev/null)" = 1 ] || exit 0
          [ "$(cat /sys/class/power_supply/wireless/online 2>/dev/null)" != 1 ] || exit 0
          after=$(cat "$usb/voltage_now" 2>/dev/null) || exit 0
          printf 'cp %s %s\n%s\n' "$before" "$after" "$values"
          exit 0
        done
        )
        """;

    static String parse(String raw) {
        try {
            String[] tokens = raw.trim().split("\\s+");
            if (tokens.length != 135 || !tokens[0].equals("cp")) return "";
            long before = Long.parseLong(tokens[1]), after = Long.parseLong(tokens[2]);
            long work = value(tokens, 3, "work"), vin = value(tokens, 36, "vin");
            long iin = value(tokens, 69, "iin"), workAfter = value(tokens, 102, "after");
            if (work != 1 || workAfter != 1 || vin < 3000 || vin > 30000
                    || iin <= 0 || iin > 15000 || before < 3000000 || before > 30000000
                    || after < 3000000 || after > 30000000
                    || Math.abs(before - vin * 1000) > 1000000
                    || Math.abs(after - vin * 1000) > 1000000) return "";
            return "1 0 " + (vin * 1000) + " " + (iin * 1000) + " 1 0";
        } catch (RuntimeException ignored) { return ""; }
    }

    private static long value(String[] tokens, int offset, String label) {
        if (!tokens[offset].equals(label)) throw new IllegalArgumentException();
        byte[] bytes = new byte[32];
        for (int n = 0; n < bytes.length; n++) {
            int v = Integer.parseInt(tokens[offset + n + 1]);
            if (v < 0 || v > 255) throw new IllegalArgumentException();
            bytes[n] = (byte) v;
        }
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (data.getInt() != 0x20302009 || data.getInt() != 20 || data.getInt() != 1
                || data.getInt() != 0 || data.getInt() != 8 || data.getInt() != 0)
            throw new IllegalArgumentException();
        return data.getLong();
    }
}
