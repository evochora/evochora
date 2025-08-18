package org.evochora.compiler.internal.i18n;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Interne i18n-Fassade für Compiler- und Legacy-Komponenten.
 * Verwendet ResourceBundles mit dem Basisnamen "compiler_messages".
 */
public final class Messages {

    private static final String BUNDLE_BASE_NAME = "compiler_messages";
    private static volatile ResourceBundle bundle = loadBundle(Locale.getDefault());

    private Messages() {}

    public static void setLocale(Locale locale) {
        bundle = loadBundle(locale);
    }

    public static String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
    }

    public static String get(String key, Object... args) {
        String pattern = get(key);
        return MessageFormat.format(pattern, args);
    }

    private static ResourceBundle loadBundle(Locale locale) {
        try {
            return ResourceBundle.getBundle(BUNDLE_BASE_NAME, locale);
        } catch (MissingResourceException e) {
            // Fallback auf Englisch, wenn die Locale nicht gefunden wird.
            return ResourceBundle.getBundle(BUNDLE_BASE_NAME, Locale.ENGLISH);
        }
    }
}
