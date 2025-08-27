package org.evochora.compiler.internal.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Internal i18n facade for compiler and legacy components.
 * Uses ResourceBundles with the base name "compiler_messages".
 */
public final class Messages {

    private static final String BUNDLE_BASE_NAME = "compiler_messages";
    private static volatile ResourceBundle bundle = loadBundle(Locale.getDefault());

    private Messages() {}

    /**
     * Sets the locale for the message bundle.
     * @param locale The new locale.
     */
    public static void setLocale(Locale locale) {
        bundle = loadBundle(locale);
    }

    /**
     * Gets a message for the given key.
     * @param key The key of the message.
     * @return The message, or "!key!" if not found.
     */
    public static String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
    }

    /**
     * Gets a formatted message for the given key.
     * @param key The key of the message.
     * @param args The arguments for the message format.
     * @return The formatted message.
     */
    public static String get(String key, Object... args) {
        String pattern = get(key);
        return MessageFormat.format(pattern, args);
    }

    private static ResourceBundle loadBundle(Locale locale) {
        try {
            return ResourceBundle.getBundle(BUNDLE_BASE_NAME, locale);
        } catch (MissingResourceException e) {
            // Fallback to English if the locale is not found.
            return ResourceBundle.getBundle(BUNDLE_BASE_NAME, Locale.ENGLISH);
        }
    }
}
