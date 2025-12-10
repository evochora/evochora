### **Ziel: Ein modernes, intuitives Modulsystem**

Dieses Dokument beschreibt eine neue, überarbeitete Syntax und Semantik für das Modulsystem des Compilers. Das Ziel ist es, ein System zu schaffen, das einfach, konsistent und leistungsfähig ist und sich an den etablierten Konventionen moderner Programmiersprachen orientiert. Dieses neue System bricht bewusst mit der alten Syntax, um architektonische Klarheit zu schaffen.



---

### **1. Modul-Deklaration: Eine Datei = Ein Modul**

Jede Assembly-Datei (`.evo`) ist implizit ein eigenständiges Modul. Ein Modul dient als Namespace für alle in ihm definierten Symbole (Prozeduren, Labels, Konstanten etc.).

* **Kanonischer Name:** Ein Modul erhält seinen kanonischen (eindeutigen) Namen durch eine **`.MODULE`-Direktive**, die in der Regel am Anfang der Datei steht.

    ```assembly
    ; Datei: std/math.evo
    .MODULE std.math  ; Definiert den kanonischen Namen des Moduls

    ; ... Definitionen ...
    ```
* **Vorteile:**
    * Der kanonische Name ist unabhängig vom Dateipfad, was das Projekt-Layout flexibler macht.
    * Verschachtelte Namespaces (z.B. `std.math`) sind explizit und klar definiert.
    * Wenn keine `.MODULE`-Direktive angegeben ist, kann der Dateiname als Fallback dienen.

---

### **2. Sichtbarkeit: Public (`.EXPORT`) vs. Private**

Standardmässig sind alle in einem Modul definierten Symbole **privat**, d.h. sie sind nur innerhalb dieses Moduls sichtbar. Um ein Symbol für andere Module verfügbar zu machen, muss es explizit exportiert werden.

* **Syntax:** Die `.EXPORT`-Direktive macht ein oder mehrere Symbole öffentlich.

    ```assembly
    ; Datei: std/math.evo
    .MODULE std.math

    ; Öffentliche Prozedur, da sie exportiert wird
    .EXPORT add_func
    add_func:
        .PROC a, b
        ADD a, b
        RET

    ; Private Hilfsprozedur, da sie nicht exportiert wird
    internal_helper:
        .PROC
        ; ...
        RET
    ```
* **Vorteile:**
    * **Kapselung:** Modul-Autoren können Implementierungsdetails verbergen und eine saubere, stabile öffentliche API anbieten.
    * **Keine Namenskollisionen:** Private Symbole können in verschiedenen Modulen denselben Namen haben, ohne sich gegenseitig zu stören.

---

### **3. Modul-Nutzung: `.IMPORT`**

Um auf die **exportierten** Symbole eines anderen Moduls zugreifen zu können, muss dieses Modul importiert werden. Die `.IMPORT`-Direktive ersetzt das alte `.REQUIRE` und `.INCLUDE`.

* **Syntax:**
    * **Import mit Alias (empfohlen):** Einem importierten Modul wird ein lokaler, kurzer Alias zugewiesen.
    * **Direkter Import:** Importiert die Symbole direkt (weniger empfohlen, da es zu Namenskollisionen führen kann).

* **Beispiel:**

    ```assembly
    ; Datei: main.evo
    .MODULE main

    ; Importiert das Modul "std.math" und gibt ihm den lokalen Alias "Math"
    .IMPORT std.math AS Math

    ; Importiert das Modul "utils.string" direkt.
    .IMPORT utils.string

    START:
        ; Zugriff auf die 'add_func' über den Alias "Math"
        CALL Math.add_func

        ; Direkter Zugriff, da "utils.string" direkt importiert wurde
        CALL string_reverse 
    ```

* **Vorteile:**
    * **Klarheit:** Es ist sofort ersichtlich, woher ein Symbol stammt (`Math.add_func`).
    * **Keine Namenskollisionen:** Durch die Verwendung von Aliasen können zwei Module, die beide eine Funktion namens `init` exportieren, problemlos im selben Programm verwendet werden (`ModuleA.init` und `ModuleB.init`).
    * **Flexibilität:** Ihre Idee, verschachtelte Scopes zu importieren (`.IMPORT OUTER.INNER AS ALIAS`), wird durch die kanonischen Modulnamen ebenfalls unterstützt.

### **Zusammenfassung des neuen Systems**

| Konzept | Alte Syntax | Neue, verbesserte Syntax | Zweck |
| :--- | :--- | :--- | :--- |
| **Modul-Definition** | `.SCOPE "name"` | `.MODULE my.module.name` | Definiert den eindeutigen Namen eines Moduls. |
| **Modul-Nutzung** | `.REQUIRE "file" AS ALIAS` | `.IMPORT my.module.name AS Alias` | Macht die Symbole eines anderen Moduls verfügbar. |
| **Sichtbarkeit** | Alle Symbole sind global/öffentlich. | Standardmässig privat, explizit mit `.EXPORT` veröffentlicht. | Ermöglicht Kapselung und stabile APIs. |

Ihr Vorschlag, die Dinge grundlegend zu ändern, ist der absolut richtige Weg. Dieses neue System ist nicht nur einfacher und konsistenter, sondern es ist auch deutlich leistungsfähiger und legt eine solide Grundlage für die zukünftige Entwicklung des Compilers.