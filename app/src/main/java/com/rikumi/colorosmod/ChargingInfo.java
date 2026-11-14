package com.rikumi.colorosmod;

final class ChargingInfo {
    static final String METHOD = "charging_input_sample";
    static final int OFF = 0, POWER = 1, VOLTAGE_CURRENT = 2, POWER_PROTOCOL = 3;
    record Sample(long microvolts, long microamps) {
        boolean valid() {
            return microvolts >= 3000000 && microvolts <= 30000000 && microamps > 0 && microamps <= 15000000;
        }
        String format(int mode) {
            if (!valid()) return "";
            if (mode == POWER) return ((microvolts * microamps + 999999999999L) / 1000000000000L) + "W";
            if (mode != VOLTAGE_CURRENT) return "";
            long tenths = (microamps + 50000) / 100000;
            if (tenths == 0) return "";
            String amps = tenths % 10 == 0 ? Long.toString(tenths / 10)
                    : (tenths / 10) + "." + (tenths % 10);
            return ((microvolts + 999999) / 1000000) + "V " + amps + "A";
        }
    }
    static Sample parse(String raw) {
        return parse(raw, 1000);
    }
    static Sample parseMicro(String raw) {
        return parse(raw, 1);
    }
    private static Sample parse(String raw, long scale) {
        if (raw == null) return null;
        String[] fields = raw.trim().split("\\s+");
        if (fields.length != 6) return null;
        try {
            long usb = Long.parseLong(fields[0]), wireless = Long.parseLong(fields[1]);
            if ((usb != 1 || wireless != 0) && (usb != 0 || wireless != 1)) return null;
            if (usb != Long.parseLong(fields[4]) || wireless != Long.parseLong(fields[5])) return null;
            Sample sample = new Sample(Math.multiplyExact(Long.parseLong(fields[2]), scale),
                    Math.multiplyExact(Long.parseLong(fields[3]), scale));
            return sample.valid() ? sample : null;
        } catch (NumberFormatException | ArithmeticException ignored) { return null; }
    }
    static String legacySample(String raw) {
        Sample sample = parseMicro(raw);
        if (sample == null) return "";
        String[] fields = raw.trim().split("\\s+");
        // Keep the existing Provider wire contract for already-loaded SystemUI hooks.
        return fields[0] + " " + fields[1] + " " + sample.microvolts / 1000 + " "
                + sample.microamps / 1000 + " " + fields[4] + " " + fields[5];
    }
    static boolean active(int mode, boolean attached, boolean visible, boolean nativeCharging, boolean bypass) {
        return enabled(mode) && attached && visible && nativeCharging && !bypass;
    }
    static boolean enabled(int mode) { return mode == POWER || mode == VOLTAGE_CURRENT || mode == POWER_PROTOCOL; }
    static String format(Sample sample, String protocol, int mode) {
        String value = sample == null ? "" : sample.format(mode == POWER_PROTOCOL ? POWER : mode);
        if (mode != POWER_PROTOCOL || protocol == null || protocol.isEmpty()) return value;
        return value.isEmpty() ? protocol : protocol + " " + value;
    }
    static int labelIndex(int batteryIndex, int existingLabelIndex, boolean rtl) {
        if (existingLabelIndex >= 0 && existingLabelIndex < batteryIndex) batteryIndex--;
        return batteryIndex + (rtl ? 1 : 0);
    }
    private ChargingInfo() {}
}
