# Evochora Assembler Spezifikation

Dieses Dokument beschreibt die Syntax, Direktiven und den kompletten Befehlssatz zur Programmierung von Organismen in der Evochora-Simulation.

### 1. Grundlegende Syntax

- **Kommentare**: Jedes Zeichen nach einer Raute (`#`) wird als Kommentar behandelt und vom Assembler ignoriert.
- **Labels**: Ein Label wird durch einen Namen gefolgt von einem Doppelpunkt definiert (z.B. `MEIN_LABEL:`). Label-Namen sind nicht case-sensitive und dürfen nicht mit Befehlsnamen identisch sein.
- **Groß-/Kleinschreibung**: Befehle, Direktiven und Registernamen sind nicht case-sensitive.

---

### 2. Direktiven

- **`.ORG X|Y`**: Setzt den Ursprung für den folgenden Code, relativ zum Programmstart.
- **`.REG %NAME ID`**: Weist einem Datenregister (`0-7`) einen Alias zu.
- **`.MACRO $NAME [params...]` ... `.ENDM`**: Definiert ein Makro.
- **`.ROUTINE NAME [params...]` ... `.ENDR`**: Definiert eine Routine-Schablone.
- **`.INCLUDE LIB.ROUTINE AS INSTANZ WITH [args...]`**: Erzeugt eine Subroutine aus einer Schablone.
- **`.PLACE TYP:WERT X|Y`**: Platziert ein `Symbol` an einer relativen Koordinate.

---

### 3. Argument-Typen

- **Register**: `%DR0` bis `%DR7`.
- **Literal**: `TYP:WERT` (z.B. `DATA:123`).
- **Vektor**: `X|Y` (z.B. `1|0`).
- **Label**: Ein definierter Label-Name.

---

### 4. Befehlssatz (Instruction Set)

#### **Daten & Speicher**

- **`SETI %REG_ZIEL LITERAL`**: Setzt Register auf Literal.
- **`SETR %REG_ZIEL %REG_QUELLE`**: Kopiert Registerwert.
- **`SETV %REG_ZIEL VEKTOR|LABEL`**: Setzt Register auf Vektor-Wert.
- **`PUSH %REG_QUELLE`**: Legt Registerwert auf den Stack.
- **`POP %REG_ZIEL`**: Holt Wert vom Stack in ein Register.
- **`PUSI LITERAL`**: Legt Literal direkt auf den Stack.

#### **Arithmetik & Logik**

- **`ADDR %REG_A %REG_B`**: `A = A + B` (Skalar & Vektor).
- **`SUBR %REG_A %REG_B`**: `A = A - B` (Skalar & Vektor).
- **`ADDI %REG LITERAL`**: `REG = REG + LITERAL`.
- **`SUBI %REG LITERAL`**: `REG = REG - LITERAL`.
- **`MULR %REG_A %REG_B`**: `A = A * B` (Skalar).
- **`MULI %REG LITERAL`**: `REG = REG * LITERAL`.
- **`DIVR %REG_A %REG_B`**: `A = A / B` (Skalar, Integer-Division).
- **`DIVI %REG LITERAL`**: `REG = REG / LITERAL`.
- **`MODR %REG_A %REG_B`**: `A = A % B` (Skalar, Modulo).
- **`MODI %REG LITERAL`**: `REG = REG % LITERAL`.

#### **Bitweise Operationen**

- **`NADR %REG_A %REG_B`**: `A = NOT (A AND B)`.
- **`NADI %REG LITERAL`**: `REG = NOT (REG AND LITERAL)`.
- **`ANDR %REG_A %REG_B`**: `A = A AND B`.
- **`ANDI %REG LITERAL`**: `REG = REG AND LITERAL`.
- **`ORR %REG_A %REG_B`**: `A = A OR B`.
- **`ORI %REG LITERAL`**: `REG = REG OR LITERAL`.
- **`XORR %REG_A %REG_B`**: `A = A XOR B`.
- **`XORI %REG LITERAL`**: `REG = REG XOR LITERAL`.
- **`NOT %REG`**: `REG = NOT REG`.
- **`SHLI %REG DATA:N`**: Bit-Shift nach links um N Stellen.
- **`SHRI %REG DATA:N`**: Bit-Shift nach rechts um N Stellen (arithmetisch).

#### **Kontrollfluss (Sprüngen)**

- **`JMPI LABEL`**: Springt zu einem Label (relativer Sprung).
- **`JMPR %REG_ADDR`**: Springt zur programm-relativen Adresse im Register.
- **`CALL LABEL`**: Ruft eine Subroutine auf.
- **`RET`**: Kehrt von einer Subroutine zurück.

#### **Bedingte Anweisungen**

- **`IFR %REG_A %REG_B`**: Wenn `A == B`.
- **`GTR %REG_A %REG_B`**: Wenn `A > B` (Skalar).
- **`LTR %REG_A %REG_B`**: Wenn `A < B` (Skalar).
- **`IFI %REG LITERAL`**: Wenn `REG == LITERAL`.
- **`GTI %REG LITERAL`**: Wenn `REG > LITERAL` (Skalar).
- **`LTI %REG LITERAL`**: Wenn `REG < LITERAL` (Skalar).
- **`IFTR %REG_A %REG_B`**: Wenn `typ(A) == typ(B)`.
- **`IFTI %REG LITERAL`**: Wenn `typ(REG) == typ(LITERAL)`.

#### **Welt-Interaktion & Organismus-Zustand**

- **`PEEK %REG_ZIEL %REG_VEKTOR`**: Liest Symbol bei `DP + Vektor` und leert die Zelle.
- **`PEKI %REG_ZIEL VEKTOR`**: Wie PEEK, aber mit Vektor-Literal.
- **`POKE %REG_QUELLE %REG_VEKTOR`**: Schreibt Symbol bei `DP + Vektor`.
- **`POKI %REG_QUELLE VEKTOR`**: Wie POKE, aber mit Vektor-Literal.
- **`SCAN %REG_ZIEL %REG_VEKTOR`**: Liest Symbol bei `DP + Vektor` ohne zu leeren.
- **`SEEK %REG_VEKTOR`**: Bewegt `DP` um den Vektor.
- **`SEKI VEKTOR`**: Bewegt `DP` um den Vektor-Literal. **(NEU)**
- **`SYNC`**: `DP = IP`.
- **`TURN %REG_VEKTOR`**: `DV = Vektor`.
- **`POS %REG_ZIEL`**: Schreibt die programm-relative Koordinate des `IP` ins Register.
- **`DIFF %REG_ZIEL`**: `ZIEL = DP - IP`.
- **`NRG %REG_ZIEL`**: `ZIEL = ER`.
- **`RAND %REG`**: `REG = random(0, REG - 1)`.
- **`FORK %REG_DELTA %REG_ENERGY %REG_DV`**: Erzeugt einen Klon.

---

### 5. Zustand des Organismus (CPU-Register)

- **`IP` (Instruction Pointer)**: Absolute Weltkoordinate der nächsten Instruktion.
- **`DP` (Data Pointer)**: Absolute Weltkoordinate für Speicherzugriffe (`PEEK`, `POKE`, `SCAN`).
- **`DV` (Direction Vector)**: Bewegungsrichtung des `IP`.
- **`ER` (Energy Register)**: Lebensenergie.
- **`DRs` (Data Registers)**: 8 Allzweck-Register (`%DR0`-`%DR7`).
- **`DS` (Data Stack)**: LIFO-Speicher für temporäre Daten und Rücksprungadressen.