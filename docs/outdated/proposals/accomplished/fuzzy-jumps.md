# Fuzzy Jumps Proposal

> **Status:** In Diskussion
> **Erstellt:** 2026-01-23
> **Konversations-Format:** Strukturierte 1:1-Diskussion, ein Aspekt nach dem anderen

## Kontext und Motivation

### Problem mit absoluten Sprüngen

Das aktuelle Sprungsystem in Evochora verwendet **absolute Koordinaten** relativ zur Geburtsposition des Organismus:
- `JMPI LABEL` wird zur Compile-Zeit zu `JMPI x|y` aufgelöst
- Sprünge sind fragil gegenüber Mutationen (eine Bit-Änderung → völlig falsches Ziel)
- Die Fitness-Landschaft ist "rau" — kleine Genom-Änderungen führen zu großen Verhaltensänderungen

### Vorbild: Tierra/Avida

In Tierra/Avida werden Sprungziele durch **NOP-Muster** markiert:
- Sprungbefehl sucht nach komplementärem NOP-Muster
- Robuster gegen Mutationen (Muster kann teilweise korrupt sein und trotzdem gefunden werden)
- 1-dimensionaler Adressraum macht Suche trivial

### Herausforderung in Evochora

- **N-dimensionaler Raum**: Suche ist viel aufwendiger als in 1D
- **Dynamische Umgebung**: Mutationen, Schreiboperationen von Organismen
- **Performance**: Naive Suche wäre zu teuer

## Ziel

Einführung von "Fuzzy Jumps" die:
1. Robuster gegen Mutationen sind
2. Die Fitness-Landschaft glätten
3. Effizient im n-dimensionalen Raum funktionieren
4. Mit der dynamischen Natur der Simulation kompatibel sind

## Kritische Anforderung: Artifact-Unabhängigkeit

**Die Runtime muss vollständig unabhängig vom ProgramArtifact sein.**

- ProgramArtifact darf nur für initiales Code-Placement und Debugging verwendet werden
- Laufzeitverhalten muss ausschließlich aus Environment und internem Organismus-State kommen
- Begründung: Self-modifying Code — Organismen können ihren Code verändern, das Artifact repräsentiert nur den initialen Zustand

---

## Diskussions-Format

**Rolle von Claude:** Wissenschaftlicher Berater und Software-Architekt

**Methodik:**
- Strukturierte Diskussion, **ein Aspekt nach dem anderen**
- Jeder Aspekt wird vollständig besprochen bevor zum nächsten übergegangen wird
- Entscheidungen werden hier dokumentiert
- Offene Punkte werden in der Liste geführt

---

## Diskussionspunkte

| # | Thema | Status | Entscheidung |
|---|-------|--------|--------------|
| 1 | Marker-Repräsentation | ✅ Entschieden | Neuer Molekültyp `LABEL` |
| 2 | Robustheit vs. Evolvierbarkeit | ✅ Entschieden | Evolvierbarkeit (konfigurierbar) |
| 2a | Operand- und Marker-Typen | ✅ Entschieden | DATA für Operanden, LABEL für Marker |
| 3 | Matching-Strategie | ✅ Entschieden | Hamming-Gruppe → Score (distance + foreignPenalty) → Ownership |
| 3a | Ähnlichkeitsmetrik | ✅ Entschieden | Hamming-Distanz ≤ 2 |
| 3b | Parent-Child-Problem | ✅ Entschieden | foreignPenalty (default=20) für fremde Labels |
| 4 | Index-Architektur | ✅ Entschieden | LabelIndex in Environment, vorexpandiert |
| 5 | Compiler-Integration | ✅ Entschieden | Bestehende Instructions umbauen, DATA für Operanden |
| 5a | Compiler-Implementierung | ✅ Analysiert | LayoutEngine, Emitter, LabelRefLinkingRule |
| 5b | Runtime-Implementierung | ✅ Analysiert | Environment (LabelIndex), ControlFlowInstruction, ProcedureCallHandler |
| 5c | Frontend-Implementierung | ✅ Analysiert | AnnotationUtils, LabelReferenceTokenHandler, Emitter |
| 6 | Molekülsystem-Erweiterung | ✅ Entschieden | TYPE_LABEL hinzufügen (trivial, gut gekapselt) |

---

## Entscheidungen

### 1. Marker-Repräsentation

**Datum:** 2026-01-23

**Optionen betrachtet:**
- A: DATA-Moleküle wiederverwenden
- B: Neuer LABEL-Molekültyp
- C: CODE mit speziellem Wert

**Entscheidung:** **Option B — Neuer `LABEL`-Molekültyp**

**Begründung:**
- Klare semantische Trennung
- Keine Konflikte mit existierenden Daten
- Ermöglicht spezielle Behandlung (z.B. Energie-Kosten, Sichtbarkeit)
- Gelegenheit, das Molekülsystem besser zu kapseln

**Aufwandsanalyse:**

| Komponente | Datei | Aufwand |
|------------|-------|---------|
| Konstante | `Config.java` | Trivial (1 Zeile) |
| Registry | `MoleculeTypeRegistry.java` | Trivial (1 Zeile) |
| Rendering | `SimulationRenderer.java` | Niedrig (1 if-else + Farbe) |
| Statistiken | `EnvironmentCompositionPlugin.java` | Niedrig (1 if-else + Spalte) |
| Frontend | Web-Visualisierung | Niedrig (Farbe) |

**Fazit:** Architektur ist besser gekapselt als erwartet. `MoleculeTypeRegistry` ist Single-Point-of-Truth.

---

### 2. Robustheit vs. Evolvierbarkeit

**Datum:** 2026-01-24

**Optionen betrachtet:**
- A: Absolute Robustheit (Labels von Mutation ausgenommen)
- B: Evolvierbarkeit (Labels können mutieren, Matching ist tolerant)

**Entscheidung:** **Option B — Evolvierbarkeit**

**Begründung:**
- Tierra/Avida zeigt: Evolvierbare Templates ermöglichen emergente Kontrollfluss-Strukturen
- Neue Subroutinen können durch Duplikation + leichte Mutation entstehen
- Evochoras Ziel ist Emergenz, nicht Design
- Falls problematisch: Mutations-Regeln können später angepasst/konfiguriert werden

**Referenz:** Ray, T.S. (1991) — "The use of templates provides an addressing system which is tolerant of some errors."

**Fallback-Strategie:**
```java
mutation.label.rate = 0.1  // Konfigurierbar
mutation.label.protected = false  // Kann auf true gesetzt werden
```

---

### 2a. Operand- und Marker-Typen

**Datum:** 2026-01-24

**Entscheidung:** **DATA für Operanden, LABEL für Marker**

**Begründung:**
- Konsistent mit anderen Instruction-Argumenten (alle sind DATA)
- Index enthält nur LABEL-Moleküle — einfacher Typ-Check zur Unterscheidung
- Kein Marker-Bit nötig
- Voller 20-Bit Werteraum für beide

**Matching:**
- Jump-Operand: DATA:X
- Ziel-Marker: LABEL:X
- Vergleich erfolgt auf Wert-Ebene (Hamming-Distanz), nicht Typ

---

### 3. Matching-Strategie

**Datum:** 2026-01-24

**Teilentscheidungen:**
- **Keine harte Reichweiten-Begrenzung** — würde Organismen-Größe limitieren
- **Globale Suche** — alle Labels im Environment sind potentielle Ziele
- **Fremde Labels erreichbar** — ermöglicht Parasitismus und Symbiose

**Priorisierung (entschieden):**
1. Ähnlichkeit (Hamming-Distanz) — strikt gruppiert, niedrigste Hamming-Gruppe gewinnt
2. Score innerhalb Hamming-Gruppe: `distance + (fremd ? foreignPenalty : 0)`
3. Ownership als ultimativer Tie-Breaker für Determinismus

**Definition "eigen" vs "fremd":**
```java
// Owner kommt aus Environment.ownerGrid, nicht aus Molecule
int codeOwner = environment.getOwnerId(ip);

// Für jedes Label-Kandidat:
int labelOwner = environment.getOwnerId(labelCoord);
Molecule labelMol = environment.getMolecule(labelCoord);
boolean hasTransferMarker = labelMol.marker() != 0;  // marker != 0 → für FORK markiert

boolean eigen = (labelOwner == codeOwner) && !hasTransferMarker;
boolean fremd = (labelOwner != codeOwner) || hasTransferMarker;
```

**Warum CODE-Owner statt ausführender Organismus:**
- Einheitliche Semantik für JMPI, JMPR, JMPS und CALL
- Code ist "selbsttragend" — funktioniert unabhängig davon wer ihn ausführt

**Warum Transfer-Marker als "fremd":**
- Löst Replikations-Timing-Problem: Während Parent Code kopiert (vor FORK), ist Kopie markiert
- Markierte Labels werden als fremd behandelt → Parent springt nicht in unfertigen Kind-Code
- Nach FORK: Marker entfernt, Kind hat eigene Ownership → normale Semantik

**foreignPenalty (konfigurierbar):**
- Distanz-Malus für fremde Labels
- Ermöglicht Feintuning zwischen Parasitismus und Stabilität

| foreignPenalty | Verhalten |
|----------------|-----------|
| 0 | Pure Distance (Probleme während Replikation!) |
| 20 (default) | Balanced (Parasitismus bei deutlich näherem Label) |
| 1000+ | Praktisch Ownership-first |

**Begründung für diesen Ansatz:**
- Löst Parent-Child-Problem: Nach Replikation springt Parent nicht ins Kind
- Löst Replikations-Timing: Während Kopieren springt Parent nicht in markierten Code
- Parasitismus bleibt möglich: Fremdes Label kann gewinnen wenn viel näher
- Konfigurierbar: Experimentieren mit verschiedenen ökologischen Dynamiken

---

### 3a. Ähnlichkeitsmetrik

**Datum:** 2026-01-24

**Entscheidung:** **Hamming-Distanz ≤ 2**

**Begründung:**
- Typische Punktmutation kippt 1-2 Bits
- Bei 20-Bit-Werten: 211 Nachbarn pro Wert (1 + 20 + 190)
- Vorberechenbar für O(1) Lookup
- Bei >2 Bits Unterschied: kein Match → `instructionFailed`

**Lookup-Algorithmus:**
```
1. Alle Kandidaten mit Hamming ≤ tolerance aus Index holen
2. Gruppieren nach Hamming-Distanz (0, 1, 2)
3. Niedrigste nicht-leere Gruppe wählen
4. Score pro Kandidat: distance + (fremd ? foreignPenalty : 0)
5. Kandidat mit niedrigstem Score gewinnt
6. Bei gleichem Score: Ownership als Tie-Breaker (Determinismus)
7. Keine Kandidaten → instructionFailed
```

---

### 4. Index-Architektur

**Datum:** 2026-01-24

**Entscheidung:** **LabelIndex als Komponente innerhalb von Environment**

**Struktur:**
```
Environment
├── grid[]
├── ownerGrid[]
├── changedSinceLastReset
└── labelIndex           ← NEU
    └── valueToLabels    ← vorexpandierter Reverse-Index
```

**Index-Strategie: Vorexpandierung**

Für jedes LABEL mit Wert V an Position P:
- Trage (V, P, Owner) ein unter V UND allen 210 Nachbar-Werten
- Speicher: ~211 Einträge pro Label → bei 10.000 Labels ~50 MB

**Update-Strategie:**
- Direkt in `setMolecule()` bei LABEL-Änderungen
- Kein Batch-Update via BitSet (wäre langsamer)
- Nur LABEL-Änderungen triggern Index-Update

**Performance:**
- Lookup: O(1) HashMap-Zugriff + O(k) Kandidaten-Auswahl
- Update: O(211) pro LABEL-Änderung (Einträge hinzufügen/entfernen)

**Persistence:**
- Index wird NICHT persistiert (abgeleitete Daten)
- Bei Replay/Restore: Neuaufbau aus Environment-Zustand

**Austauschbarkeit:**
- Matching-Algorithmus und Index als Strategy Pattern
- Ermöglicht Experimente mit verschiedenen Ansätzen
- Performance-Overhead durch Indirektion: <5% (vernachlässigbar)

**Konfigurierbare Toleranz:**
- Default: Hamming ≤ 2 (~50 MB Index)
- Optional: Hamming ≤ 3 (~300 MB Index)
- Toleranz als Konfigurations-Parameter, nicht hardcoded

**Konfiguration (evochora.conf Pattern):**
```hocon
runtime {
  label-matching {
    className = "org.evochora.runtime.label.PreExpandedHammingStrategy"
    options {
      tolerance = 2        // Hamming-Distanz Toleranz
      foreignPenalty = 20  // Distanz-Malus für fremde Labels
    }
  }
}
```
- Strategy via Reflection instanziiert
- Options strategy-spezifisch
- Code-Default falls nicht konfiguriert (tolerance=2, foreignPenalty=20)

---

### 5. Compiler-Integration

**Datum:** 2026-01-24

**Entscheidungen:**

**Bestehende Instructions umbauen (nicht ergänzen):**
- JMPI, JMPR, JMPS, CALL → alle auf LABEL-basiertes Matching umstellen
- Keine neuen Instructions (kein "JMPF")
- Verhindert, dass Mutation zwischen alten/neuen Instructions wechselt

**Operanden-Typ:**
- Jump-Operanden: DATA-Moleküle (konsistent mit anderen Instruction-Argumenten)
- Ziel-Marker: LABEL-Moleküle
- Index enthält nur LABEL-Moleküle (Typ-Check reicht zur Unterscheidung)

**Wert-Generierung:**
- Compiler generiert Label-Werte automatisch
- Programmierer verwendet symbolische Namen (wie heute)
- Assembly-Syntax bleibt weitgehend unverändert

**RET:**
- Bleibt koordinaten-basiert
- Return-Adresse wird bei CALL aus aktueller Position berechnet (nicht initialPosition-relativ)
- Funktioniert auch bei Code-Sharing zwischen Organismen

---

### 5a. Compiler-Implementierungsdetails

**Datum:** 2026-01-24

**Analyse der bestehenden Struktur:**
- `IrLabelDef` — Label-Definition, implementiert `IrItem` (bereits vorhanden)
- `IrLabelRef` — Label-Referenz in Jumps, implementiert `IrOperand` (bereits vorhanden)

**Aktuelles Verhalten:**
- LayoutEngine (Z. 44-47): Registriert Label-Adresse, belegt aber keinen Platz (`continue` ohne `place*`)
- Emitter: Ignoriert `IrLabelDef` komplett (nur `IrInstruction` behandelt)

**Änderungen:**

| Datei | Zeile | Änderung |
|-------|-------|----------|
| `LayoutEngine.java` | 44-47 | `ctx.placeLabel(src)` aufrufen |
| `LayoutContext.java` | — | `placeLabel()` Methode hinzufügen |
| `Emitter.java` | nach 69 | `IrLabelDef` → LABEL-Molekül emittieren |
| `LabelRefLinkingRule.java` | 52-60 | Hash statt Koordinaten-Vektor erzeugen |

**LayoutEngine.java (Z. 44-47):**
```java
if (item instanceof IrLabelDef lbl) {
    labelToAddress.put(lbl.name(), ctx.linearAddress());
    ctx.placeLabel(src);  // NEU: Label belegt 1 Zelle
    continue;
}
```

**Emitter.java (nach Z. 69):**
```java
if (item instanceof IrLabelDef lbl) {
    int[] labelCoord = linearToCoord.get(address);
    int labelHash = lbl.name().hashCode() & Config.VALUE_MASK;
    machineCodeLayout.put(labelCoord, new Molecule(Config.TYPE_LABEL, labelHash).toInt());
    address++;
    continue;
}
```

**LabelRefLinkingRule.java (Z. 52-60):**
```java
// ALT: Koordinaten-Vektor
int[] dstCoord = layout.linearAddressToCoord().get(targetAddr);
rewritten.set(i, new IrVec(absoluteVector));

// NEU: Hash-Wert als einzelner DATA-Operand
int labelHash = labelNameToFind.hashCode() & Config.VALUE_MASK;
rewritten.set(i, new IrImm(labelHash));
```

**Konsequenz für Layout:**
Labels belegen jetzt 1 Zelle. Alle nachfolgenden Adressen verschieben sich entsprechend.
Dies ist gewollt und konsistent mit dem Tierra/Avida-Modell (Templates belegen Platz).

---

### 5b. Runtime-Implementierungsdetails

**Datum:** 2026-01-24

#### Environment.java — LabelIndex Integration

**Neues Feld (nach Zeile 35):**
```java
private final Int2ObjectOpenHashMap<IntOpenHashSet> cellsByLabel;
```

**Initialisierung (Constructor, nach Zeile 90):**
```java
this.cellsByLabel = new Int2ObjectOpenHashMap<>();
```

**Hooks für Index-Updates:**

| Methode | Zeile | Änderung |
|---------|-------|----------|
| `setMolecule(mol, coords)` | 160 | Hook nach grid-Update |
| `setMolecule(mol, owner, coords)` | 185 | Hook nach ownership-Update |
| `transferOwnership()` | 433 | Hook nach marker-Reset |
| `clearOwnershipFor()` | 467 | Hook nach marker-Reset |

**Neue Helper-Methoden:**
```java
private void updateLabelIndex(int flatIndex, int oldType, int newType, int oldValue, int newValue) {
    // Nur bei LABEL-Molekülen
    if (oldType == Config.TYPE_LABEL && oldValue != 0) {
        IntOpenHashSet oldSet = cellsByLabel.get(oldValue);
        if (oldSet != null) {
            oldSet.remove(flatIndex);
            if (oldSet.isEmpty()) cellsByLabel.remove(oldValue);
        }
    }
    if (newType == Config.TYPE_LABEL && newValue != 0) {
        cellsByLabel.computeIfAbsent(newValue, k -> new IntOpenHashSet()).add(flatIndex);
    }
}

public IntOpenHashSet getCellsByLabelValue(int labelValue) {
    return cellsByLabel.getOrDefault(labelValue, new IntOpenHashSet());
}
```

#### ControlFlowInstruction.java — Fuzzy Lookup

**resolveOperands() (Z. 57-68) — ALT:**
```java
int dims = environment.getShape().length;
int[] delta = new int[dims];
for (int i = 0; i < dims; i++) {
    Organism.FetchResult res = organism.fetchSignedArgument(currentIp, environment);
    delta[i] = res.value();
    currentIp = res.nextIp();
}
resolved.add(new Operand(delta, -1));
```

**resolveOperands() — NEU:**
```java
// Fetch single DATA molecule (20-bit label hash)
Organism.FetchResult res = organism.fetchUnsignedArgument(currentIp, environment);
int labelValue = res.value() & Config.VALUE_MASK;
resolved.add(new Operand(labelValue, -1));
```

**execute() (Z. 95-105) — ALT:**
```java
int[] delta = (int[]) operands.get(0).value();
int[] targetIp = organism.getTargetCoordinate(
    organism.getInitialPosition(), delta, environment);  // ← BUG
organism.setIp(targetIp);
```

**execute() — NEU:**
```java
int labelValue = (Integer) operands.get(0).value();
int codeOwner = environment.getOwnerId(organism.getIp());

int[] targetIp = environment.getLabelIndex().findTarget(
    labelValue,
    codeOwner,
    organism.getIp(),  // für Distanz-Berechnung
    environment
);

if (targetIp == null) {
    organism.instructionFailed("No matching label found");
    return;
}
organism.setIp(targetIp);
```

#### ProcedureCallHandler.java — CALL Anpassung

**Signatur (Z. 34) — ALT:**
```java
public void executeCall(int[] targetDelta, ProgramArtifact artifact)
```

**Signatur — NEU:**
```java
public void executeCall(int labelValue, ProgramArtifact artifact)
```

**Zielberechnung (Z. 67) — ALT:**
```java
int[] targetIp = organism.getTargetCoordinate(
    organism.getInitialPosition(), targetDelta, environment);  // ← BUG
```

**Zielberechnung — NEU:**
```java
int codeOwner = environment.getOwnerId(ipBeforeFetch);
int[] targetIp = environment.getLabelIndex().findTarget(
    labelValue, codeOwner, ipBeforeFetch, environment);
```

**executeReturn() — KEINE ÄNDERUNG:**
- Zeilen 116-128 bleiben unverändert
- Verwendet bereits `absoluteReturnIp` (koordinaten-basiert)
- Funktioniert korrekt bei Code-Sharing

---

### 5c. Frontend/Visualizer-Implementierungsdetails

**Datum:** 2026-01-24

#### Annotation-System Übersicht

Annotationen erscheinen in eckigen Klammern direkt nach dem Token:
```
%COUNTER[%DR0=42]     ← Register-Alias mit Wert
my_label[15|23]       ← Label mit Koordinaten (AKTUELL)
```

#### Änderungen für Fuzzy Jumps

**Source View (LabelReferenceTokenHandler.js):**

| Aktuell | Neu |
|---------|-----|
| `my_label[15\|23]` (Koordinaten) | `my_label[#12345]` (Hash-Wert) |

```javascript
// LabelReferenceTokenHandler.js - analyze() Methode
analyze(tokenText, tokenInfo, organismState, artifact) {
    const hashValue = AnnotationUtils.resolveLabelNameToHash(tokenText, artifact);
    return {
        annotationText: `[#${hashValue}]`,
        kind: 'label-ref'
    };
}
```

**Instruction View (Emitter.formatOperandAsString):**

| Aktuell | Neu |
|---------|-----|
| `JMPI 5\|3` (Delta-Vektor) | `JMPI DATA:12345 [my_label]` (Hash + Annotation) |

```java
// Emitter.java - formatOperandAsString() für Label-Operanden
if (op instanceof IrImm imm) {
    String labelName = labelValueToName.get(imm.value());
    if (labelName != null) {
        // Label-Wert mit Name-Annotation
        return String.format("DATA:%d [%s]", imm.value(), labelName);
    }
    return String.valueOf(imm.value());
}
```

#### Neue Mappings in ProgramArtifact

```java
// ProgramArtifact.java - neue Felder
Map<Integer, String> labelValueToName;  // Hash → Name (für Reverse-Lookup)
Map<String, Integer> labelNameToValue;  // Name → Hash (für Annotation)
```

#### AnnotationUtils.js — neue Methoden

```javascript
// Hash → Name Lookup
static resolveLabelHashToName(hashValue, artifact) {
    if (artifact.labelValueToName && artifact.labelValueToName[hashValue]) {
        return artifact.labelValueToName[hashValue];
    }
    return null;  // Unbekannt (evtl. mutiert)
}

// Name → Hash Lookup
static resolveLabelNameToHash(name, artifact) {
    if (artifact.labelNameToValue) {
        const hash = artifact.labelNameToValue[name.toUpperCase()];
        if (hash !== undefined) return hash;
    }
    throw new Error(`Unknown label: ${name}`);
}
```

#### Betroffene Frontend-Dateien

| Datei | Änderung |
|-------|----------|
| `AnnotationUtils.js` | Neue Methoden für Hash-Lookup |
| `LabelReferenceTokenHandler.js` | Hash statt Koordinaten annotieren |
| `ProcedureTokenHandler.js` | Analog zu LabelReferenceTokenHandler |

---

## Offene Punkte

Keine — alle Design-Entscheidungen sind getroffen.

---

## Technischer Kontext

### Aktuelles Molekül-System

```java
// Molecule.java
public record Molecule(int type, int value, int marker)
// type: 2 bits (0-3)
// value: 20 bits
// marker: 4 bits (ownership)
```

**Existierende Typen:**
| Typ | Wert | Zweck |
|-----|------|-------|
| CODE | 0 | Maschinencode |
| DATA | 1 | Beschreibbare Daten |
| ENERGY | 2 | Energie-Währung |
| STRUCTURE | 3 | Umgebungsstrukturen |
| LABEL | 4 | **NEU: Sprungziel-Marker** |

### Betroffene Komponenten (vorläufig)

- `runtime/model/Molecule.java` — Typ-Konstante
- `runtime/model/MoleculeTypeRegistry.java` — Registrierung
- `runtime/Config.java` — Typ-Konstante
- `compiler/` — LABEL-Direktive, Emission
- `datapipeline/` — Serialisierung, Statistiken
- `frontend/` — Visualisierung (Farbe für LABEL)

---

## Referenzen

- Tierra: Ray, T.S. (1991) "An Approach to the Synthesis of Life"
- Avida: Ofria, C. & Wilke, C.O. (2004) "Avida: A Software Platform for Research in Computational Evolutionary Biology"
- Evochora Assembly Spec: `docs/ASSEMBLY_SPEC.md`

---

## Weitere Entscheidungen (2026-01-24)

### RET-Verhalten

**Entscheidung:** RET bleibt koordinaten-basiert

- Return-Adresse wird bei CALL aus aktueller Position berechnet (Zeilen 57-60 in ProcedureCallHandler)
- NICHT initialPosition-relativ → funktioniert bei Code-Sharing
- Kein Fuzzy-Lookup nötig

### Konfiguration

**evochora.conf Pattern (wie andere Runtime-Komponenten):**
```hocon
runtime {
  label-matching {
    className = "org.evochora.runtime.label.PreExpandedHammingStrategy"
    options {
      tolerance = 2        // Hamming-Distanz Toleranz
      foreignPenalty = 20  // Distanz-Malus für fremde Labels
    }
  }
}
```
- Strategy via Reflection instanziiert
- Code-Default falls nicht konfiguriert (tolerance=2, foreignPenalty=20)

### Label-Wert-Generierung

**Status:** ✅ Entschieden

**Entscheidung:** Hash des Label-Namens, maskiert auf 20 Bit

**Begründung:**
- Deterministisch (gleicher Name → gleicher Wert)
- Einfach zu implementieren
- Keine Kollisionskontrolle nötig (bei 20 Bit und typischen Programmgrößen <100 Labels ist die Kollisionswahrscheinlichkeit vernachlässigbar, und selbst bei Kollision entscheidet räumliche Nähe)

### ProgramArtifact Erweiterung

**Neues Mapping für Debugging:**
```java
Map<Integer, String> labelValueToName  // NEU: Wert → Name
```

- Ermöglicht Visualizer: "DATA:42 (my_label)" statt nur "DATA:42"
- `labelAddressToName` bleibt für Positions-Debugging

### Visualizer Source/Instruction View

**Muss weiter funktionieren:**
- `operandsAsString` in MachineInstructionInfo zeigt Label-Namen
- Emitter.formatOperandsAsString() muss angepasst werden
- labelValueToName für Reverse-Lookup nutzen

---

## Betroffene Dateien (vollständige Liste mit Zeilennummern)

### Runtime
| Datei | Zeilen | Änderung |
|-------|--------|----------|
| `Config.java` | — | TYPE_LABEL = 4 Konstante |
| `MoleculeTypeRegistry.java` | — | Register TYPE_LABEL |
| `Environment.java` | 35, 90, 160, 185, 433, 467 | LabelIndex Feld, Init, Hooks |
| `ControlFlowInstruction.java` | 57-68, 102 | resolveOperands, execute |
| `ProcedureCallHandler.java` | 34, 67 | Signatur, Zielberechnung |

### Compiler
| Datei | Zeilen | Änderung |
|-------|--------|----------|
| `LayoutEngine.java` | 44-47 | ctx.placeLabel() aufrufen |
| `LayoutContext.java` | — | placeLabel() Methode hinzufügen |
| `Emitter.java` | 69, 220-226, 234-254 | IrLabelDef emittieren, formatOperandAsString |
| `LabelRefLinkingRule.java` | 52-60 | Hash-Wert statt Koordinaten |
| `ProgramArtifact.java` | — | labelValueToName, labelNameToValue |

### Data Pipeline
| Datei | Änderung |
|-------|----------|
| `EnvironmentCompositionPlugin.java` | LABEL-Statistiken |
| `metadata_contracts.proto` | labelValueToName Feld |

### Frontend
| Datei | Änderung |
|-------|----------|
| `SimulationRenderer.java` | LABEL-Farbe |
| `AnnotationUtils.js` | resolveLabelHashToName, resolveLabelNameToHash |
| `LabelReferenceTokenHandler.js` | Hash-Annotation statt Koordinaten |
| `ProcedureTokenHandler.js` | Analog |

---

## Performance-Analyse (aus Code)

### Aktuell JMPI (2D)
```
Operanden fetchen:        2 Array-Zugriffe (je 1 pro Dimension)
getTargetCoordinate():    2 Additionen, 1 Array-Allokation (int[2])
setIp():                  1 Array-Copy
─────────────────────────────────────────────────
Gesamt:                   ~4 Array-Zugriffe, 2 Arithmetik, 1 Allokation
```

### Neu Fuzzy Jump (2D, optimiert)
```
Operand fetchen:          1 Array-Zugriff (DATA-Molekül)
codeOwner holen:          1 Array-Zugriff (ownerGrid[ip]) — einmal pro Instruction
HashMap.get(value):       ~3-5 Array-Zugriffe (Hash + Bucket)
Pro Kandidat (typ. 1-2):
  - owner/marker:         0 Array-Zugriffe (im Index gecacht)
  - eigen/fremd:          2-3 Ops
  - Score berechnen:      2 Additionen, 1 Vergleich
setIp():                  1 Array-Copy
─────────────────────────────────────────────────
Gesamt (1 Kandidat):      ~6-7 Array-Zugriffe, ~8 Arithmetik, 0 Allokationen
Gesamt (2 Kandidaten):    ~7-8 Array-Zugriffe, ~12 Arithmetik, 0 Allokationen

Early Exit (exakter eigener Match): ~5-6 Array-Zugriffe, ~4 Arithmetik
```

### Vergleich

| Metrik | Aktuell | Fuzzy (naiv) | Fuzzy (optimiert) |
|--------|---------|--------------|-------------------|
| Array-Zugriffe | ~4 | ~8-10 | ~6-7 |
| Arithmetik | ~2 | ~8 | ~8 |
| Allokationen | 1 | 0 | 0 |

**Fazit:**
- Fuzzy (optimiert) ist ~30-40% langsamer pro Jump
- Vorteil: Keine Allokationen (weniger GC-Druck bei vielen Jumps)
- Early Exit bei exaktem eigenem Match reduziert häufigsten Fall weiter

### Pflicht-Optimierungen (Requirements)

1. **Early Exit bei exaktem eigenem Match:**
   ```java
   if (candidates.size() == 1 && hamming == 0 && eigen) {
       return candidate.position;  // Skip scoring
   }
   ```
   Begründung: Der häufigste Fall (Sprung zum eigenen exakten Label) braucht kein Scoring.

2. **codeOwner einmal pro Instruction holen:**
   ```java
   int codeOwner = environment.getOwnerId(ip);  // Einmal am Anfang
   // Dann für alle Kandidaten wiederverwenden
   ```

3. **Owner/Marker im Index cachen:**
   ```java
   // Index-Eintrag enthält alle nötigen Daten
   record LabelEntry(int[] position, int owner, int marker) {}
   Map<Integer, List<LabelEntry>> valueToLabels;
   ```
   - Spart ~2 Array-Zugriffe pro Kandidat (kein ownerGrid/grid Lookup)
   - Update-Hooks: `setMolecule()` (bereits nötig) + `transferOwnership()` (trivial)

---

## Kritisches Problem im aktuellen Code

**ControlFlowInstruction.java Zeile 102:**
```java
int[] targetIp = organism.getTargetCoordinate(organism.getInitialPosition(), delta, environment);
```

Sprungziel ist relativ zu `initialPosition` — anderer Organismus mit anderem `initialPosition` springt falsch!

**Lösung durch Fuzzy Jumps:** Operand enthält Label-WERT, Lookup findet LABEL-Marker im Environment unabhängig von initialPosition.

---

## Zusammenfassung aller Entscheidungen

### Kern-Konzept
- **Neuer Molekültyp `LABEL`** (TYPE_LABEL = 4) für Sprungziel-Marker
- **Label-Werte via Hash** des Label-Namens (20 Bit, deterministisch)
- **Fuzzy Matching** mit Hamming-Distanz ≤ 2 (konfigurierbar)
- **Labels belegen Platz** im Grid (wie Tierra/Avida Templates)

### Matching-Algorithmus
1. Kandidaten mit Hamming ≤ tolerance aus Index
2. Gruppieren nach Hamming-Distanz (niedrigste Gruppe gewinnt)
3. Score = distance + (fremd ? foreignPenalty : 0)
4. Niedrigster Score gewinnt, Ownership als Tie-Breaker

### Definition "eigen" vs "fremd"
```java
int codeOwner = environment.getOwnerId(ip);  // Owner des CODE-Moleküls
int labelOwner = environment.getOwnerId(labelCoord);
boolean hasTransferMarker = environment.getMolecule(labelCoord).marker() != 0;

boolean eigen = (labelOwner == codeOwner) && !hasTransferMarker;
boolean fremd = (labelOwner != codeOwner) || hasTransferMarker;
```

### Konfiguration (evochora.conf)
```hocon
runtime.label-matching {
  className = "org.evochora.runtime.label.PreExpandedHammingStrategy"
  options { tolerance = 2, foreignPenalty = 20 }
}
```

### Betroffene Instructions
- **JMPI, JMPR, JMPS, CALL** → Fuzzy Label Lookup
- **RET** → Bleibt koordinaten-basiert (korrekt implementiert)

### Compiler-Änderungen
- `LayoutEngine.java` Z.44-47: Labels belegen 1 Zelle
- `Emitter.java`: LABEL-Moleküle emittieren, operandsAsString anpassen
- `LabelRefLinkingRule.java`: Hash-Werte statt Koordinaten

### Runtime-Änderungen
- `Environment.java`: LabelIndex mit Pre-Expansion (211 Einträge/Label)
- `ControlFlowInstruction.java` Z.102: Fuzzy-Lookup statt initialPosition
- `ProcedureCallHandler.java` Z.67: Fuzzy-Lookup statt initialPosition

### Frontend-Änderungen
- **Source View:** `my_label[#12345]` (Hash-Annotation)
- **Instruction View:** `JMPI DATA:12345 [my_label]` (Hash + Name)
- `ProgramArtifact`: labelValueToName, labelNameToValue

### Performance-Garantien
1. Early Exit bei exaktem eigenem Match
2. codeOwner einmal pro Instruction cachen
3. Owner/Marker im Index cachen (LabelEntry record)
