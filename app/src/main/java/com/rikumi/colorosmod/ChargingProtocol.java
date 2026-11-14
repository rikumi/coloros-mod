package com.rikumi.colorosmod;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Oplus protocol_type uses OPLUS_CHARGER_SUBTYPE, not adapter IDs from fast_chg_type.
 * Source: OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_sm8750,
 * vendor/oplus/kernel/charger/v2/{oplus_configfs.c,include/oplus_chg.h}.
 */
final class ChargingProtocol {
    static final String COMMAND = "root=/sys/class/power_supply; for p in \"$root/usb\" \"$root/wireless\" \"$root/ac\" \"$root/mains\" \"$root/charger\" \"$root\"/*; do name=${p##*/}; case \"$name\" in battery*|bms*|*ucsi*|*source*) continue;; esac; t=$(cat \"$p/type\" 2>/dev/null) || continue; case \"$t\" in USB*|Mains) u=1; w=0;; Wireless) u=0; w=1;; *) continue;; esac; a=$(cat \"$p/online\" 2>/dev/null) || continue; [ \"$a\" = 1 ] || continue; vendor=$(cat /sys/class/oplus_chg/common/protocol_type 2>/dev/null); has_vendor=$?; real=$(cat \"$p/real_type\" 2>/dev/null); usb=$(cat \"$p/usb_type\" 2>/dev/null); b=$(cat \"$p/online\" 2>/dev/null) || continue; [ \"$b\" = 1 ] || continue; printf 'source %s %s %s %s\\n' \"$u\" \"$w\" \"$u\" \"$w\"; [ \"$has_vendor\" = 0 ] && printf 'oplus %s\\n' \"$vendor\"; printf 'real %s\\nusb %s\\n' \"$real\" \"$usb\"; exit 0; done";
    private static final Pattern SELECTED = Pattern.compile("\\[([^\\[\\]]+)\\]");

    static String parse(String raw, String sample) {
        if (raw == null || raw.length() > 1600) return "";
        String[] lines = raw.split("\\R", -1);
        if (lines.length < 2) return "";
        String[] source = lines[0].trim().split("\\s+");
        if (source.length != 5 || !"source".equals(source[0])) return "";
        boolean wired = "1".equals(source[1]) && "0".equals(source[2]);
        boolean wireless = "0".equals(source[1]) && "1".equals(source[2]);
        if ((!wired && !wireless) || !source[1].equals(source[3]) || !source[2].equals(source[4])) return "";
        if (ChargingInfo.parseMicro(sample) != null) {
            String[] input = sample.trim().split("\\s+");
            if (!source[1].equals(input[0]) || !source[2].equals(input[1])) return "";
        }
        String real = "", usb = "";
        for (int i = 1; i < lines.length; i++) {
            // An available vendor node is authoritative, including its default/unknown value.
            if (lines[i].startsWith("oplus ")) return oplus(lines[i].substring(6).trim());
            if (lines[i].startsWith("real ")) real = lines[i].substring(5).trim();
            if (lines[i].startsWith("usb ")) usb = lines[i].substring(4).trim();
        }
        String protocol = text(real);
        return protocol.isEmpty() ? text(usb) : protocol;
    }

    static String oplus(String raw) {
        return switch (raw) {
            case "1" -> "VC";
            case "2" -> "SV";
            case "3" -> "PD";
            case "4" -> "QC";
            case "5" -> "PPS";
            case "6" -> "UFCS";
            case "7" -> "PE 2.0";
            default -> "";
        };
    }

    static String text(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 512) return "";
        Matcher selected = SELECTED.matcher(raw);
        if (selected.find()) {
            String value = selected.group(1);
            if (selected.find()) return "";
            raw = value;
        } else if (raw.indexOf('[') >= 0 || raw.indexOf(']') >= 0) return "";
        // usb_type can list every supported protocol; only its bracketed selection is active.
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "VOOC", "USB_VOOC" -> "VC";
            case "SVOOC", "SUPERVOOC", "USB_SVOOC" -> "SV";
            case "PD", "USB_PD", "USB_PD_DRP", "PD_DRP" -> "PD";
            case "PPS", "PD_PPS", "USB_PD_PPS" -> "PPS";
            case "UFCS", "USB_UFCS" -> "UFCS";
            case "QC", "HVDCP", "USB_HVDCP" -> "QC";
            case "QC2", "QC2.0", "QC_2", "USB_QC2" -> "QC 2.0";
            case "QC3", "QC3.0", "QC_3", "USB_QC3", "USB_HVDCP_3" -> "QC 3.0";
            default -> "";
        };
    }

    private ChargingProtocol() {}
}
