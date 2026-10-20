package com.gathin.tvhome;

/** Text rules for voice input, kept apart so they can be tested without a device. */
final class VoiceText {

    private VoiceText() {
    }

    /** What the model heard, to be typed: a lone sentence loses its full stop, which a search box doesn't want. */
    static String tidy(String heard) {
        String s = heard.trim();
        return s.endsWith(".") && s.indexOf('.') == s.length() - 1 ? s.substring(0, s.length() - 1) : s;
    }

    /** What a text field holds after dictating {@code said} into it: after a space, or alone when it was empty. */
    static String merge(String field, String said) {
        String s = said.trim();
        if (s.isEmpty()) return field;
        if (field.isEmpty()) return s;
        return Character.isWhitespace(field.charAt(field.length() - 1)) ? field + s : field + " " + s;
    }
}
