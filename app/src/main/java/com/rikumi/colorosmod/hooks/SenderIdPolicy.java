package com.rikumi.colorosmod.hooks;

/** Text senders must not be interpreted as vanity phone numbers or country codes. */
final class SenderIdPolicy {
    static boolean isTextSender(String value) {
        if (value == null || value.isEmpty() || value.length() > 25) return false;
        boolean letter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c > 126 || c == '@') return false;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) letter = true;
        }
        return letter;
    }
}
