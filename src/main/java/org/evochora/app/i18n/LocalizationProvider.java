package org.evochora.app.i18n;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Stellt zentralisierte, internationalisierte Texte (i18n) für die Anwendung bereit.
 * Diese Klasse lädt die passenden Textressourcen (z.B. messages_de.properties)
 * aus dem Classpath.
 * <p>
 * Verwendung in der UI: {@code String title = LocalizationProvider.getString("app.title");}
 */
public final class LocalizationProvider {

    private static final String BUNDLE_BASE_NAME = "messages";
    private static ResourceBundle bundle;

    // Statischer Initializer, um das Standard-Bundle beim ersten Zugriff zu laden.
    static {
        // Startet mit der Default-Locale der JVM. Kann später geändert werden.
        setLocale(Locale.getDefault());
    }

    /**
     * Privater Konstruktor, um Instanziierung zu verhindern. Dies ist eine Utility-Klasse.
     */
    private LocalizationProvider() {
    }

    /**
     * Ändert die aktuell verwendete Sprache für die gesamte Anwendung.
     * Dies könnte z.B. in den App-Einstellungen aufgerufen werden.
     * @param locale Die neue Locale, die verwendet werden soll (z.B. Locale.GERMAN).
     */
    public static void setLocale(Locale locale) {
        bundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, locale);
    }

    /**
     * Holt einen übersetzten Text anhand seines Schlüssels in der aktuell eingestellten Sprache.
     *
     * @param key Der Schlüssel des Textes (z.B. "app.title").
     * @return Der übersetzte Text oder der Schlüssel selbst in einer auffälligen
     *         Formatierung, falls er nicht gefunden wurde.
     */
    public static String getString(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            // Dieser Fallback ist nützlich für Entwickler, um fehlende
            // Schlüssel während des Tests sofort zu bemerken.
            return "!" + key + "!";
        }
    }
}
