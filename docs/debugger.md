# Erster Schritt zum robusten Debugger: Ein lokales, zuverlässiges Setup

## 1. Ziel

Das Hauptziel dieses ersten Schrittes ist es, die **Instabilität und die Daten-Inkonsistenzen** des aktuellen Webrenderers zu beseitigen. Wir wollen einen Debugger schaffen, auf den man sich zu 100% verlassen kann, indem wir die Datenaufbereitung von der Darstellung trennen. Dieses Setup soll auf einem einzelnen Entwickler-Rechner ohne zusätzliche Infrastruktur lauffähig sein und als Fundament für die spätere, skalierbare Architektur dienen.

## 2. Kernprinzip: Verlagerung der Intelligenz ins Backend

Die grundlegende Änderung ist, die gesamte Logik zur Datenaufbereitung aus dem JavaScript-Frontend (`main.js`) in das Java-Backend (`WorldStateAdapter`) zu verlagern. Das Frontend wird zu einem "dummen" Renderer.

![Lokale Architektur](https://i.imgur.com/L3b4r2k.png)

---

## 3. Konkrete Implementierungsschritte

### Schritt 1: Die Simulation Engine zum "Datenaufbereiter" machen

* **Anpassung in `WorldStateAdapter.java`:**
    1.  Die Methode `fromSimulation` wird so erweitert, dass sie **alle** für das Debugging notwendigen Informationen vollständig aufbereitet.
    2.  Sie führt die **Disassemblierung** des aktuellen Befehls durch (unter Nutzung des `RuntimeDisassembler`).
    3.  Sie **formatiert alle Register- und Stack-Werte** in menschenlesbare Strings (z.B. `1234` -> `"DATA:1234"`).
    4.  Sie **löst den Call-Stack** auf und erzeugt die finalen Anzeige-Strings (z.B. `"MY_PROC WITH %DR0=DATA:42"`).
    5.  **Wichtig:** Der Output ist nicht mehr ein roher `WorldStateMessage`, sondern ein reichhaltiges Java-Objekt (`PreparedTickState`), das die finale JSON-Struktur widerspiegelt.

### Schritt 2: Persistenz der aufbereiteten Daten

* **Anpassung in `PersistenceService.java`:**
    * Für diesen ersten Schritt vereinfachen wir die Persistenz radikal. Statt vieler Tabellen für Rohdaten gibt es nur noch eine:
        ```sql
        CREATE TABLE IF NOT EXISTS prepared_ticks (
            tick_number INTEGER PRIMARY KEY,
            tick_data_json TEXT
        );
        ```
    * Der `PersistenceService` nimmt das `PreparedTickState`-Objekt, serialisiert es zu einem JSON-String und speichert diesen in der Datenbank.
    * **Vorteil:** Die Daten in der DB sind bereits final und 100% konsistent. Was gespeichert wird, ist genau das, was später angezeigt wird.

### Schritt 3: Einführung eines minimalen Debugger-Backends

* **Neue Java-Klasse `DebuggerServer`:**
    * Diese Klasse enthält eine `main`-Methode, die einen leichtgewichtigen, eingebetteten Webserver startet (z.B. mit **Javalin**).
    * **Funktion 1: Statische Dateien bereitstellen:** Der Server liefert alle Dateien aus dem `webrenderer`-Verzeichnis (`index.html`, `renderer.js`, etc.).
    * **Funktion 2: Eine API bereitstellen:** Der Server implementiert einen einzigen Endpunkt:
        * `GET /api/tick/:tickNumber`: Diese Route öffnet die `sim_run.sqlite`-Datei, führt die Abfrage `SELECT tick_data_json FROM prepared_ticks WHERE tick_number = ?` aus und sendet den gefundenen JSON-String direkt an den Browser.

### Schritt 4: Das Frontend "verdummen"

* **Anpassung in `webrenderer/main.js`:**
    1.  **Entferne `sql.js`:** Die gesamte Logik zum Laden und Abfragen der SQLite-Datei im Browser wird restlos entfernt.
    2.  **Entferne Datenaufbereitung:** Alle Code-Teile, die Register formatieren, den Quellcode annotieren oder Befehle disassemblieren, werden gelöscht.
    3.  **Ersetze durch API-Aufrufe:** Die `navigateToTick`-Funktion wird umgeschrieben. Statt einer SQL-Abfrage macht sie jetzt einen simplen `fetch`-Aufruf:
        ```javascript
        async function navigateToTick(tick) {
            const response = await fetch(`/api/tick/${tick}`);
            const preparedData = await response.json();
            
            // Daten an die UI-Komponenten übergeben
            worldRenderer.draw(preparedData.worldState);
            sidebar.update(preparedData.organismDetails[selectedOrganismId]);
        }
        ```

## 4. Ergebnis dieses Schrittes

* **Zuverlässigkeit:** Die Daten im Debugger sind garantiert korrekt, da sie von einer einzigen "Source of Truth" (dem Java-Backend) aufbereitet werden.
* **Testbarkeit:** Die komplexe Logik der Datenaufbereitung liegt nun in `WorldStateAdapter` und kann mit JUnit-Tests validiert werden.
* **Zukunftssicherheit:** Diese Architektur ist eine perfekte Miniatur des finalen Systems. Der API-Vertrag (die Struktur des JSONs) ist definiert. Die spätere Optimierung (Trennung von Roh-Persistenz und Indexierung) kann im Backend stattfinden, **ohne dass das Frontend jemals wieder angefasst werden muss.**
