package org.evochora;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

public final class Messages {

    private static final String BUNDLE_NAME = "messages";
    private static ResourceBundle resourceBundle;

    static {
        // Default to English locale
        setLocale(Locale.ENGLISH);
    }

    private Messages() {
        // Private constructor to prevent instantiation
    }

    public static void setLocale(final Locale locale) {
        try {
            resourceBundle = ResourceBundle.getBundle(BUNDLE_NAME, locale);
        } catch (final Exception e) {
            // Fallback to root bundle if locale-specific bundle is not found
            resourceBundle = ResourceBundle.getBundle(BUNDLE_NAME, Locale.ROOT);
        }
    }

    public static String get(final String key, final Object... args) {
        if (resourceBundle.containsKey(key)) {
            final String pattern = resourceBundle.getString(key);
            return MessageFormat.format(pattern, args);
        }
        // Return key as a fallback
        return key;
    }
}
