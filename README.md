# Evochora Assembler Spezifikation

Dieses Dokument beschreibt die Syntax, Direktiven und den kompletten Befehlssatz zur Programmierung von Organismen in der Evochora-Simulation.

### 1. Grundlegende Syntax

- **Kommentare**: Jedes Zeichen nach einer Raute (`#`) wird als Kommentar behandelt und vom Assembler ignoriert.
- **Labels**: Ein Label wird durch einen Namen gefolgt von einem Doppelpunkt definiert (z.B. `MEIN_LABEL:`). Label-Namen sind nicht case-sensitive und dürfen nicht mit Befehlsnamen identisch sein.
- **Groß-/Kleinschreibung**: Befehle, Direktiven und Registernamen sind nicht case-sensitive. `%DR_A` ist also identisch mit `%dr_a`.

---

### 2. Direktiven

Direktiven sind spezielle Anweisungen an den Assembler, die den Assemblierungs-Prozess steuern, aber nicht zu ausführbarem Maschinencode übersetzt werden.

- **`.ORG X|Y`**: Setzt den Ursprung (Startkoordinate) für den folgenden Code, relativ zum Startpunkt des Programms.
- **`.REG %NAME ID`**: Weist einem Datenregister (`0-7`) einen benutzerdefinierten Namen (Alias) zu. Beispiel: `.REG %ACC 0`.
- **`.MACRO $NAME [param1 param2 ...]` ... `.ENDM`**: Definiert ein Makro zur Textersetzung während der Assemblierung.
- **`.ROUTINE NAME [param1 param2 ...]` ... `.ENDR`**: Definiert eine wiederverwendbare Routine-Schablone.
- **`.INCLUDE ROUTINEN-BIBLIOTHEK.ROUTINE AS INSTANZ WITH [arg1 arg2 ...]`**: Erzeugt aus einer Routine-Schablone eine aufrufbare Subroutine (Instanz).
- **`.PLACE TYP:WERT X|Y`**: Platziert ein bestimmtes `Symbol` an einer Koordinate, die relativ zum Programmstart ist. Beispiel: `.PLACE ENERGY:100 10|5`.

---

### 3. Argument-Typen

- **Register**: Es gibt 8 Datenregister, von `%DR0` bis `%DR7`, die auch über Aliase angesprochen werden können.
- **Literal**: Ein konstanter Wert, der direkt im Code steht und das Format `TYP:WERT` hat. Gültige Typen sind `CODE`, `DATA`, `ENERGY` und `STRUCTURE`. Beispiel: `DATA:123`.
- **Vektor**: Eine 2D-Koordinate im Format `X|Y`. Beispiel: `1|0` für einen Vektor nach rechts.
- **Label**: Der Name eines definierten Labels, der eine Speicheradresse repräsentiert.

---

### 4. Befehlssatz (Instruction Set)

#### **Daten & Speicher**

- **`SETI %REG_ZIEL LITERAL`**: Setzt den Wert eines Registers auf ein Literal.
- **`SETR %REG_ZIEL %REG_QUELLE`**: Kopiert den Wert aus einem Quell-Register in ein Ziel-Register.
- **`SETV %REG_ZIEL VEKTOR|LABEL`**: Setzt ein Register auf einen Vektor-Wert. Das Argument kann ein Vektor-Literal (`1|0`) oder ein Label sein, dessen relative Adresse dann als Vektor gespeichert wird.
- **`PUSH %REG_QUELLE`**: Legt eine Kopie des Werts aus dem Quell-Register auf den Stack.
- **`POP %REG_ZIEL`**: Holt den obersten Wert vom Stack und speichert ihn im Ziel-Register.

#### **Arithmetik & Logik**

- **`ADDR %REG_A %REG_B`**: Addiert die Werte von `%REG_A` und `%REG_B`. Das Ergebnis wird in `%REG_A` gespeichert. Funktioniert für Skalare und Vektoren.
- **`SUBR %REG_A %REG_B`**: Subtrahiert den Wert von `%REG_B` von `%REG_A`. Das Ergebnis wird in `%REG_A` gespeichert. Funktioniert für Skalare und Vektoren.
- **`ADDI %REG LITERAL`**: Addiert einen Literal-Wert zum Wert im Register.
- **`SUBI %REG LITERAL`**: Subtrahiert einen Literal-Wert vom Wert im Register.
- **`NADR %REG_A %REG_B`**: Führt eine bitweise NAND-Operation (`NOT (A AND B)`) mit den Werten in den Registern aus. Das Ergebnis wird in `%REG_A` gespeichert.
- **`NADI %REG LITERAL`**: Führt eine bitweise NAND-Operation mit dem Wert im Register und einem Literal aus.

#### **Kontrollfluss (Sprüngen)**

- **`JMPI LABEL`**: Springt zu einem Label. Der Assembler berechnet den relativen Offset.
- **`JMPR %REG_ADDR`**: Springt zu der programm-relativen Adresse, die als Vektor im Register `%REG_ADDR` gespeichert ist.
- **`CALL LABEL`**: Legt die programm-relative Rücksprungadresse auf den Stack und springt dann zum angegebenen Label.
- **`RET`**: Holt eine programm-relative Adresse vom Stack und springt dorthin.

#### **Bedingte Anweisungen**

Die folgenden Befehle überspringen die nächste Anweisung, falls die Bedingung **nicht** erfüllt ist.

- **`IFR %REG_A %REG_B`**: Wenn die Werte in `%REG_A` und `%REG_B` exakt gleich sind. Bei Vektoren werden alle Komponenten verglichen.
- **`GTR %REG_A %REG_B`**: Wenn der Skalar-Wert in `%REG_A` **größer als** der in `%REG_B` ist.
- **`LTR %REG_A %REG_B`**: Wenn der Skalar-Wert in `%REG_A` **kleiner als** der in `%REG_B` ist.
- **`IFI %REG LITERAL`**: Wenn der Wert im Register exakt gleich dem Literal ist.
- **`GTI %REG LITERAL`**: Wenn der Skalar-Wert im Register **größer als** der des Literals ist.
- **`LTI %REG LITERAL`**: Wenn der Skalar-Wert im Register **kleiner als** der des Literals ist.
- **`IFTR %REG_A %REG_B`**: Wenn der **Typ** des Symbols in `%REG_A` gleich dem Typ des Symbols in `%REG_B` ist.
- **`IFTI %REG LITERAL`**: Wenn der **Typ** des Symbols im Register gleich dem Typ des Literals ist.

#### **Welt-Interaktion & Organismus-Zustand**
Anmerkung: Alle `%REG_VEKTOR` in diesem Abschnitt müssen Einheitsvektoren sein. Der DP kann nur in seiner direkten umgebung arbeiten!
- **`PEEK %REG_ZIEL %REG_VEKTOR`**: Liest das Symbol aus der Zelle bei `DP + %REG_VEKTOR`, speichert es in `%REG_ZIEL` und **leert die Zelle** in der Welt.
- **`POKE %REG_QUELLE %REG_VEKTOR`**: Schreibt das Symbol aus `%REG_QUELLE` in die Zelle bei `DP + %REG_VEKTOR`, aber nur, wenn diese leer ist.
- **`SCAN %REG_ZIEL %REG_VEKTOR`**: Liest das Symbol aus der Zelle bei `DP + %REG_VEKTOR` und speichert es in `%REG_ZIEL`, **ohne die Zelle zu verändern**.
- **`SEEK %REG_VEKTOR`**: Bewegt den Daten-Pointer (`DP`) um den Einheitsvektor in `%REG_VEKTOR`, aber nur, wenn die Zielzelle leer ist.
- **`SYNC`**: Synchronisiert den Daten-Pointer (`DP`) auf die aktuelle Position des Instruktions-Pointers (`IP`).
- **`TURN %REG_VEKTOR`**: Ändert den Bewegungs-Vektor (`DV`) des Organismus auf den Wert in `%REG_VEKTOR`.
- **`POS %REG_ZIEL`**: Schreibt die aktuelle, programm-relative Koordinate des `IP` als Vektor in das Ziel-Register.
- **`DIFF %REG_ZIEL`**: Berechnet den Vektor vom `IP` zum `DP` und speichert ihn im Ziel-Register.
- **`NRG %REG_ZIEL`**: Schreibt die aktuelle Energie (`ER`) des Organismus als `DATA`-Symbol in das Ziel-Register.
- **`FORK %REG_DELTA %REG_ENERGY %REG_DV`**: Erzeugt einen neuen Organismus (ein Kind) mit der in den Registern spezifizierten Energie und Bewegungsrichtung, an einer Position relativ zum aktuellen `IP`.

### 5. Zustand des Organismus (CPU-Register)

Jeder Organismus besitzt einen internen Zustand, der sein Verhalten und seine Interaktion mit der Welt steuert. Dieser Zustand wird durch eine Reihe von speziellen Zeigern und Registern repräsentiert.

- **`IP` (Instruction Pointer / Befehlszähler)**
    - Der `IP` ist ein Vektor, der auf die **absolute Weltkoordinate** der nächsten auszuführenden Instruktion zeigt. Er bewegt sich nach jeder Anweisung automatisch in die Richtung des `DV`.
- **`DP` (Data Pointer / Datenzeiger)**
    - Der `DP` ist ein Vektor, der als Basis-Adresse für Speicheroperationen dient. Befehle wie `PEEK`, `POKE` und `SCAN` verwenden den `DP` als Ausgangspunkt für ihre Lese- und Schreibzugriffe (`DP + Vektor`). Er kann unabhängig vom `IP` bewegt werden, was es einem Organismus ermöglicht, an einer Stelle Code auszuführen und an einer anderen Daten zu bearbeiten.
- **`DV` (Direction Vector / Richtungsvektor)**
    - Der `DV` ist ein Einheitsvektor (z.B. `1|0` oder `0|-1`), der die "Blickrichtung" des Organismus bestimmt. Er steuert, in welche Richtung sich der `IP` nach jeder Instruktion standardmäßig bewegt. Er wird mit dem `TURN`-Befehl geändert.
- **`ER` (Energy Register / Energiereserve)**
    - Ein einfacher Skalar-Wert, der die Lebensenergie des Organismus speichert. Jede ausgeführte Instruktion kostet Energie. Sinkt `ER` auf 0 oder darunter, stirbt der Organismus. Energie kann mit `PEEK` aufgenommen werden.
- **`DRs` (Data Registers / Datenregister)**
    - Ein Satz von 8 Allzweck-Registern (`%DR0` bis `%DR7`), in denen Skalare (wie `DATA:123`) oder Vektoren (`10|20`) für Berechnungen und als Argumente für Befehle gespeichert werden können.
- **`DS` (Data Stack / Daten-Stack)**
    - Ein LIFO-Speicher ("Last-In, First-Out"), der für temporäre Daten, Parameterübergabe an Routinen und das Sichern von Rücksprungadressen (`CALL`/`RET`) verwendet wird. Er hat eine begrenzte Tiefe, die in der Simulation konfiguriert ist.