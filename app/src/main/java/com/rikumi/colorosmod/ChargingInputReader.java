package com.rikumi.colorosmod;

import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class ChargingInputReader {

    static final String COMMAND = "root=/sys/class/power_supply; for p in \"$root/usb\" \"$root/wireless\" \"$root/ac\" \"$root/mains\" \"$root/charger\" \"$root\"/*; do name=${p##*/}; case \"$name\" in battery*|bms*|*ucsi*|*source*) continue;; esac; t=$(cat \"$p/type\" 2>/dev/null) || continue; case \"$t\" in USB*|Mains) u=1; w=0;; Wireless) u=0; w=1;; *) continue;; esac; a=$(cat \"$p/online\" 2>/dev/null) || continue; [ \"$a\" = 1 ] || continue; v=$(cat \"$p/voltage_now\" 2>/dev/null) || continue; i=$(cat \"$p/current_now\" 2>/dev/null) || continue; case \"$v:$i\" in *[!0-9:]*|:*|*:) continue;; esac; [ ${#v} -le 8 ] && [ ${#i} -le 8 ] || continue; if [ \"$w\" = 1 ] && [ \"$v\" -ge 3000 ] && [ \"$v\" -le 30000 ] && [ \"$i\" -gt 0 ] && [ \"$i\" -le 15000 ]; then v=$((v * 1000)); i=$((i * 1000)); fi; [ \"$v\" -ge 3000000 ] && [ \"$v\" -le 30000000 ] && [ \"$i\" -gt 0 ] && [ \"$i\" -le 15000000 ] || continue; b=$(cat \"$p/online\" 2>/dev/null) || continue; [ \"$b\" = 1 ] || continue; printf '%s %s %s %s %s %s' \"$u\" \"$w\" \"$v\" \"$i\" \"$u\" \"$w\"; exit 0; done";
    private long lastRead = -10000;
    private String cached = "";
    private String protocol = "";
    private boolean includesProtocol;
    private String status = "not_read";
    record Reading(String sample, String protocol) {}
    static String command(boolean includeProtocol) {
        String input = "read_supply() { " + COMMAND + "; }; input=$(read_supply); if [ -n \"$input\" ]; then printf '%s' \"$input\"; else "
                + ChargingChipReader.COMMAND.stripTrailing() + " | tr '\\n' ' '; fi";
        return includeProtocol ? "(" + input + "); printf '\\n'; " + ChargingProtocol.COMMAND : input;
    }
    static Reading decode(String raw, boolean includeProtocol) {
        if (raw == null) return new Reading("", "");
        int split = raw.indexOf('\n');
        String input = includeProtocol && split >= 0 ? raw.substring(0, split) : raw;
        if (input.startsWith("cp ")) input = ChargingChipReader.parse(input);
        String protocol = includeProtocol && split >= 0 ? ChargingProtocol.parse(raw.substring(split+1), input) : "";
        return new Reading(ChargingInfo.parseMicro(input) == null ? "" : input, protocol);
    }
    synchronized String status() { return status; }
    synchronized String protocol() { return protocol; }
    synchronized String read() { return read(false); }
    synchronized String read(boolean includeProtocol) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRead < 3000 && (!includeProtocol || includesProtocol)) return cached;
        lastRead = now; cached = ""; protocol = ""; includesProtocol = includeProtocol;
        java.lang.Process process = null;
        try {
            String bounded = "timeout 2 sh -c '" + command(includeProtocol).replace("'", "'\\''") + "'";
            process = new ProcessBuilder("su", "-c", bounded).redirectErrorStream(true).start();
            if (!process.waitFor(2500, TimeUnit.MILLISECONDS)) { status = "timeout"; return ""; }
            if (process.exitValue() != 0) { status = "reader_exit_" + process.exitValue(); return ""; }
            String raw = new String(process.getInputStream().readNBytes(4096), StandardCharsets.US_ASCII);
            Reading reading = decode(raw, includeProtocol);
            cached = reading.sample(); protocol = reading.protocol();
            if (!cached.isEmpty()) status = "valid_input";
            else if (!protocol.isEmpty()) status = "valid_protocol";
            else status = raw.isBlank() ? "offline" : "invalid_input";
        } catch (Exception ignored) {
            status = "reader_error_" + ignored.getClass().getSimpleName();
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { if (process != null) process.destroy(); }
        return cached;
    }
}
