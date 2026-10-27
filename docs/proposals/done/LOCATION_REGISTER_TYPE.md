# LOCATION_REGISTER Implementation Plan

## Übersicht
Einführung von `LOCATION_REGISTER` als separater Argument-Typ neben `REGISTER`, um LR-Register explizit zu unterscheiden. Dies ermöglicht Compile-Time-Validierung und eliminiert die Notwendigkeit für ID-Range-Heuristiken.

## Implementierungsstrategie
Der Plan ist in inkrementelle Schritte aufgeteilt, die jeweils kompilierbar und testbar sind. Nach jedem Schritt sollte kompiliert und getestet werden, um Fehler früh zu erkennen.

## Schritt-für-Schritt Implementierung

### Schritt 1: Enum-Erweiterungen (Basis)
**Ziel:** Enums erweitern, ohne Funktionalität zu ändern
**Kompilierbar:** Ja (mit Warnungen für ungenutzte Enum-Werte)
**Testbar:** Ja (bestehende Tests sollten weiterhin funktionieren)

### Schritt 2: Runtime - resolveOperands() erweitern
**Ziel:** LOCATION_REGISTER Case in resolveOperands() hinzufügen
**Kompilierbar:** Ja
**Testbar:** Ja (bestehende Tests sollten weiterhin funktionieren)

### Schritt 3: Runtime - Signaturen anpassen (Breaking Change)
**Ziel:** LocationInstruction Signaturen von REGISTER auf LOCATION_REGISTER ändern
**Kompilierbar:** Ja
**Testbar:** Nein (Tests müssen angepasst werden, siehe Schritt 4)

### Schritt 4: Compiler - Validierung hinzufügen
**Ziel:** Compiler-Validierung für LOCATION_REGISTER Argumente
**Kompilierbar:** Ja
**Testbar:** Ja (Compiler-Tests müssen angepasst werden)

### Schritt 5: Datapipeline - resolveInstructionView() erweitern
**Ziel:** LOCATION_REGISTER Case in resolveInstructionView() hinzufügen
**Kompilierbar:** Ja
**Testbar:** Ja (Datapipeline-Tests müssen angepasst werden)

### Schritt 6: Tests anpassen und erweitern
**Ziel:** Alle Tests anpassen und neue Tests hinzufügen
**Kompilierbar:** Ja
**Testbar:** Ja (alle Tests sollten grün sein)

## Architektonische Prinzipien
- **Explizite Typen statt impliziter Ranges**: LR-Register werden durch Typ, nicht durch ID-Range identifiziert
- **Compile-Time-Validierung**: Compiler kann prüfen, dass nur `%LRx` für `LOCATION_REGISTER` Argumente erlaubt ist
- **Klare Semantik**: `REGISTER` = DR/PR/FPR, `LOCATION_REGISTER` = LR
- **Konsistenz**: Folgt dem bestehenden Typ-System (REGISTER, LITERAL, VECTOR, LABEL)

## Schritt 1: Enum-Erweiterungen (Basis)

**Ziel:** Enums erweitern, ohne Funktionalität zu ändern
**Kompilierbar:** Ja (mit Warnungen für ungenutzte Enum-Werte)
**Testbar:** Ja (bestehende Tests sollten weiterhin funktionieren)

### 1.1 InstructionArgumentType.java
**Datei:** `src/main/java/org/evochora/runtime/isa/InstructionArgumentType.java`

**Änderung:**
- Zeile 15: `LOCATION_REGISTER,` hinzufügen (nach `LABEL`)
- JavaDoc erweitern: `/** A location register (e.g., %LR0, %LR3). */`

**Prüfung nach Schritt 1:**
```bash
./gradlew compileJava
# Sollte kompilieren (möglicherweise Warnungen für ungenutzte Enum-Werte)
./gradlew test --tests "*LocationInstruction*"
# Bestehende Tests sollten weiterhin funktionieren
```

### 1.2 OperandSource.java (innerhalb Instruction.java)
**Datei:** `src/main/java/org/evochora/runtime/isa/Instruction.java`

**Änderung:**
- Zeile 30: `public enum OperandSource { REGISTER, IMMEDIATE, STACK, VECTOR, LABEL, LOCATION_REGISTER }`
- JavaDoc erweitern falls vorhanden

**Prüfung nach Schritt 1:**
```bash
./gradlew compileJava
# Sollte kompilieren
./gradlew test --tests "*Instruction*" --tests "*LocationInstruction*"
# Bestehende Tests sollten weiterhin funktionieren
```

## Schritt 2: Runtime - resolveOperands() erweitern

**Ziel:** LOCATION_REGISTER Case in resolveOperands() hinzufügen
**Kompilierbar:** Ja
**Testbar:** Ja (bestehende Tests sollten weiterhin funktionieren, da LOCATION_REGISTER noch nicht verwendet wird)

### 2.1 resolveOperands() Methode
**Datei:** `src/main/java/org/evochora/runtime/isa/Instruction.java`

**Änderung:**
- Zeile 98-139: Switch-Statement erweitern um `LOCATION_REGISTER` Case:
  ```java
  case LOCATION_REGISTER: {
      Organism.FetchResult arg = organism.fetchArgument(currentIp, environment);
      int regId = Molecule.fromInt(arg.value()).toScalarValue();
      // LOCATION_REGISTER operands use rawSourceId() directly (no readOperand)
      resolved.add(new Operand(null, regId)); // Value resolved in LocationInstruction
      currentIp = arg.nextIp();
      break;
  }
  ```

**Prüfung nach Schritt 2:**
```bash
./gradlew compileJava
# Sollte kompilieren
./gradlew test --tests "*Instruction*" --tests "*LocationInstruction*"
# Bestehende Tests sollten weiterhin funktionieren
```

## Schritt 3: Runtime - Signaturen anpassen (Breaking Change)

**Ziel:** LocationInstruction Signaturen von REGISTER auf LOCATION_REGISTER ändern
**Kompilierbar:** Ja
**Testbar:** Nein (Tests müssen angepasst werden, siehe Schritt 4)
**Breaking Change:** Ja - Compiler-Tests werden fehlschlagen, bis Schritt 4 implementiert ist

### 3.1 Instruction.init() - LocationInstruction Registrierungen
**Datei:** `src/main/java/org/evochora/runtime/isa/Instruction.java`

**Änderungen:**
- Zeile 284: `List.of(OperandSource.REGISTER)` → `List.of(OperandSource.LOCATION_REGISTER)` für:
  - DPLR, SKLR, PUSL, LRDS, POPL, CRLR
- Zeile 285: `List.of(OperandSource.REGISTER, OperandSource.REGISTER)` → `List.of(OperandSource.LOCATION_REGISTER, OperandSource.LOCATION_REGISTER)` für:
  - LRLR
- Zeile 285: `List.of(OperandSource.REGISTER, OperandSource.REGISTER)` → `List.of(OperandSource.REGISTER, OperandSource.LOCATION_REGISTER)` für:
  - LRDR (erstes Argument = DR, zweites = LR)
- Zeile 286: `List.of(OperandSource.REGISTER)` bleibt unverändert für:
  - LSDR (verwendet DR-Register)

### 3.2 Instruction.init() - Signaturen für LocationInstructions
**Datei:** `src/main/java/org/evochora/runtime/isa/Instruction.java`

**Änderungen:**
- Zeile ~360-370: Signaturen für LocationInstructions anpassen:
  - DPLR, SKLR, PUSL, LRDS, POPL, CRLR: `InstructionSignature.of(InstructionArgumentType.LOCATION_REGISTER)`
  - LRLR: `InstructionSignature.of(InstructionArgumentType.LOCATION_REGISTER, InstructionArgumentType.LOCATION_REGISTER)`
  - LRDR: `InstructionSignature.of(InstructionArgumentType.REGISTER, InstructionArgumentType.LOCATION_REGISTER)`
  - LSDR: `InstructionSignature.of(InstructionArgumentType.REGISTER)` (bleibt unverändert)

**Prüfung nach Schritt 3:**
```bash
./gradlew compileJava
# Sollte kompilieren
./gradlew test --tests "*LocationInstruction*"
# Tests werden fehlschlagen (erwartet) - Compiler erkennt %LRx nicht als LOCATION_REGISTER
# Runtime-Tests sollten weiterhin funktionieren
```

## Schritt 3.5: LocationInstruction.java - Keine Änderung nötig

**Datei:** `src/main/java/org/evochora/runtime/isa/instructions/LocationInstruction.java`

**Ergebnis:** Keine Änderung nötig!
- Alle `rawSourceId()` Aufrufe bleiben unverändert (kein `- LR_BASE` nötig!)
- Zeile 69, 79, 93, 102, 110, 120, 144, 145, 171: `rawSourceId()` direkt verwenden
- Validierungen bleiben unverändert (Zeile 148-149, 152-153, 174)

**Wichtig:** Da `LOCATION_REGISTER` Operanden direkt `rawSourceId()` verwenden (ohne `readOperand()`), bleiben die Implementierungen unverändert.

## Schritt 4: Compiler - Validierung hinzufügen

**Ziel:** Compiler-Validierung für LOCATION_REGISTER Argumente
**Kompilierbar:** Ja
**Testbar:** Ja (Compiler-Tests müssen angepasst werden)

### 4.1 RuntimeInstructionSetAdapter.java
**Datei:** `src/main/java/org/evochora/compiler/isa/RuntimeInstructionSetAdapter.java`

**Änderungen:**
- Zeile 30-35: Switch-Statement erweitern:
  ```java
  return sig.map(s -> (Signature) () -> s.argumentTypes().stream().map(a -> switch (a) {
      case REGISTER -> ArgKind.REGISTER;
      case LITERAL -> ArgKind.LITERAL;
      case VECTOR -> ArgKind.VECTOR;
      case LABEL -> ArgKind.LABEL;
      case LOCATION_REGISTER -> ArgKind.REGISTER; // Maps to REGISTER in compiler interface
  }).toList());
  ```

**Hinweis:** `IInstructionSet.ArgKind` hat kein `LOCATION_REGISTER`, daher Mapping zu `REGISTER`. Dies ist OK, da der Compiler die Unterscheidung über die Signatur macht.

**Prüfung nach Schritt 4.1:**
```bash
./gradlew compileJava
# Sollte kompilieren
```

### 4.2 IInstructionSet.java - Keine Änderung
**Datei:** `src/main/java/org/evochora/compiler/isa/IInstructionSet.java`

**Ergebnis:** `ArgKind` Enum hat nur `REGISTER`, `LITERAL`, `VECTOR`, `LABEL`. **Keine Änderung nötig** - Adapter mappt `LOCATION_REGISTER` zu `REGISTER`.

### 4.3 InstructionAnalysisHandler.java - getArgumentTypeFromNode()
**Datei:** `src/main/java/org/evochora/compiler/frontend/semantics/analysis/InstructionAnalysisHandler.java`

**Änderung:**
- Zeile 353-358: Methode erweitern um LR-Erkennung:
  ```java
  private InstructionArgumentType getArgumentTypeFromNode(AstNode node) {
      if (node instanceof RegisterNode regNode) {
          String tokenText = regNode.registerToken().text().toUpperCase();
          if (tokenText.startsWith("%LR")) {
              return InstructionArgumentType.LOCATION_REGISTER;
          }
          return InstructionArgumentType.REGISTER;
      }
      if (node instanceof NumberLiteralNode || node instanceof TypedLiteralNode) return InstructionArgumentType.LITERAL;
      if (node instanceof VectorLiteralNode) return InstructionArgumentType.VECTOR;
      if (node instanceof IdentifierNode) return InstructionArgumentType.LABEL;
      return null;
  }
  ```

**Prüfung nach Schritt 4.3:**
```bash
./gradlew compileJava
# Sollte kompilieren
./gradlew test --tests "*LocationInstructionCompilerTest*"
# Tests sollten jetzt funktionieren (Compiler erkennt %LRx als LOCATION_REGISTER)
```

### 4.4 InstructionAnalysisHandler.java - Register-Validierung für LOCATION_REGISTER
**Datei:** `src/main/java/org/evochora/compiler/frontend/semantics/analysis/InstructionAnalysisHandler.java`

**Änderung:**
- Zeile 252-337: Register-Validierung erweitern:
  ```java
  if (expectedType == InstructionArgumentType.REGISTER && argumentNode instanceof RegisterNode regNode) {
      // ... bestehende DR/PR/FPR Validierung ...
  } else if (expectedType == InstructionArgumentType.LOCATION_REGISTER && argumentNode instanceof RegisterNode regNode) {
      String tokenText = regNode.registerToken().text();
      String u = tokenText.toUpperCase();
      
      if (u.startsWith("%LR")) {
          try {
              int regNum = Integer.parseInt(u.substring(3));
              if (regNum < 0 || regNum >= Config.NUM_LOCATION_REGISTERS) {
                  diagnostics.reportError(
                      String.format("Location register '%s' is out of bounds. Valid range: %%LR0-%%LR%d.", 
                          tokenText, Config.NUM_LOCATION_REGISTERS - 1),
                      regNode.registerToken().fileName(),
                      regNode.registerToken().line()
                  );
                  return;
              }
          } catch (NumberFormatException e) {
              diagnostics.reportError(
                  String.format("Invalid location register format '%s'.", tokenText),
                  regNode.registerToken().fileName(),
                  regNode.registerToken().line()
              );
              return;
          }
      } else {
          diagnostics.reportError(
              String.format("Argument %d for instruction '%s' expects a location register (%%LRx), but got '%s'.",
                  i + 1, instructionName, tokenText),
              regNode.registerToken().fileName(),
              regNode.registerToken().line()
          );
          return;
      }
      
      // Resolve register token
      Optional<Integer> regId = Instruction.resolveRegToken(tokenText);
      if (regId.isEmpty()) {
          diagnostics.reportError(
              String.format("Unknown location register '%s'.", tokenText),
              regNode.registerToken().fileName(),
              regNode.registerToken().line()
          );
      }
  }
  ```

### 4.5 InstructionAnalysisHandler.java - Symbol-Validierung für LOCATION_REGISTER
**Datei:** `src/main/java/org/evochora/compiler/frontend/semantics/analysis/InstructionAnalysisHandler.java`

**Änderung:**
- Zeile 209-217: ALIAS-Validierung erweitern:
  ```java
  } else if (symbol.type() == Symbol.Type.ALIAS) {
      // Register aliases are valid for REGISTER and LOCATION_REGISTER arguments
      if (expectedType != InstructionArgumentType.REGISTER && expectedType != InstructionArgumentType.LOCATION_REGISTER) {
          diagnostics.reportError(...);
      }
  }
  ```

**Prüfung nach Schritt 4:**
```bash
./gradlew compileJava
# Sollte kompilieren
./gradlew test --tests "*LocationInstructionCompilerTest*"
# Tests sollten funktionieren
./gradlew test --tests "*InstructionAnalysisHandler*"
# Validierungs-Tests sollten funktionieren
```

## Schritt 5: Datapipeline - resolveInstructionView() erweitern

**Ziel:** LOCATION_REGISTER Case in resolveInstructionView() hinzufügen
**Kompilierbar:** Ja
**Testbar:** Ja (Datapipeline-Tests müssen angepasst werden)

### 5.1 resolveInstructionView() Methode
**Datei:** `src/main/java/org/evochora/datapipeline/resources/database/OrganismStateConverter.java`

**Änderung:**
- Zeile 304-334: `LOCATION_REGISTER` Case hinzufügen:
  ```java
  for (org.evochora.runtime.isa.InstructionArgumentType argType : argTypes) {
      if (argType == org.evochora.runtime.isa.InstructionArgumentType.REGISTER) {
          // ... bestehende REGISTER-Logik (DR/PR/FPR) ...
      } else if (argType == org.evochora.runtime.isa.InstructionArgumentType.LOCATION_REGISTER) {
          // LOCATION_REGISTER: Extract register ID from raw argument
          argumentTypesList.add("REGISTER"); // Frontend zeigt als REGISTER mit registerType="LR"
          if (argIndex < rawArguments.size()) {
              int rawArg = rawArguments.get(argIndex);
              Molecule molecule = Molecule.fromInt(rawArg);
              int registerId = molecule.toScalarValue();
              
              // LOCATION_REGISTER arguments are always LR registers
              String registerType = "LR";
              
              // Resolve register value from locationRegisters
              int index = registerId; // LR registers use direct index (0-3)
              if (index >= 0 && index < locationRegisters.size()) {
                  int[] vector = locationRegisters.get(index);
                  RegisterValueView registerValue = RegisterValueView.vector(vector);
                  resolvedArgs.add(InstructionArgumentView.register(registerId, registerValue, registerType));
              } else {
                  throw new IllegalStateException(
                      String.format("LR register ID %d is out of bounds. " +
                          "Valid LR range: 0-%d, but only %d LR registers available.",
                          registerId, locationRegisters.size() - 1, locationRegisters.size()));
              }
              argIndex++;
          }
      } else if (argType == org.evochora.runtime.isa.InstructionArgumentType.LITERAL) {
          // ... bestehende LITERAL-Logik ...
      }
  }
  ```

**Hinweis:** `registerId` für LR-Register ist direkt der Index (0-3), kein Base-Offset nötig.

**Prüfung nach Schritt 5:**
```bash
./gradlew compileJava
# Sollte kompilieren
./gradlew test --tests "*H2DatabaseReader*"
# Tests werden fehlschlagen (erwartet) - siehe Schritt 6
```

## Schritt 5.5: Frontend - Keine Änderung nötig

**Datei:** `src/main/resources/web/visualizer/js/SidebarInstructionView.js`

**Ergebnis:** Frontend verwendet bereits `registerType` vom Backend. **Keine Änderung nötig** - Backend setzt `registerType="LR"` für `LOCATION_REGISTER` Argumente.

## Schritt 6: Tests anpassen und erweitern

**Ziel:** Alle Tests anpassen und neue Tests hinzufügen
**Kompilierbar:** Ja
**Testbar:** Ja (alle Tests sollten grün sein)

### 6.1 Compiler-Tests
**Datei:** `src/test/java/org/evochora/compiler/instructions/LocationInstructionCompilerTest.java`

**Ergebnis:** Tests prüfen nur Kompilierung. **Keine Änderung nötig** - Tests sollten nach Schritt 4 bereits funktionieren.

**Prüfung:**
```bash
./gradlew test --tests "*LocationInstructionCompilerTest*"
# Sollte grün sein
```

### 6.2 Runtime-Tests
**Datei:** `src/test/java/org/evochora/runtime/instructions/VMLocationInstructionTest.java`

**Ergebnis:** Tests verwenden `placeInstruction()` mit Molecule-Werten. Da `LOCATION_REGISTER` direkt `rawSourceId()` verwendet (kein Base-Offset), bleiben Tests unverändert. **Keine Änderung nötig.**

**Prüfung:**
```bash
./gradlew test --tests "*VMLocationInstructionTest*"
# Sollte grün sein
```

### 6.3 Datapipeline-Tests
**Datei:** `src/test/java/org/evochora/datapipeline/resources/database/H2DatabaseReaderInstructionResolutionTest.java`

**Änderung:** Test für `LOCATION_REGISTER` Argument hinzufügen:
```java
@Test
void resolveInstruction_LOCATION_REGISTER_argument() throws Exception {
    setupDatabase();
    
    // DPLR %LR0 - LOCATION_REGISTER argument
    int dplrOpcode = Instruction.getInstructionIdByName("DPLR") | org.evochora.runtime.Config.TYPE_CODE;
    int lrArg = new Molecule(org.evochora.runtime.Config.TYPE_DATA, 0).toInt(); // %LR0
    
    writeOrganismWithInstruction(1L, 1, dplrOpcode, java.util.List.of(lrArg), 5);
    
    try (IDatabaseReader reader = provider.createReader(runId)) {
        OrganismTickDetails details = reader.readOrganismDetails(1L, 1);
        
        assertThat(details.state.instructions.last).isNotNull();
        assertThat(details.state.instructions.last.opcodeName).isEqualTo("DPLR");
        assertThat(details.state.instructions.last.arguments).hasSize(1);
        assertThat(details.state.instructions.last.arguments.get(0).type).isEqualTo("REGISTER");
        assertThat(details.state.instructions.last.arguments.get(0).registerId).isEqualTo(0);
        assertThat(details.state.instructions.last.arguments.get(0).registerType).isEqualTo("LR");
        assertThat(details.state.instructions.last.arguments.get(0).registerValue).isNotNull();
        assertThat(details.state.instructions.last.arguments.get(0).registerValue.kind).isEqualTo("VECTOR");
    }
}
```

**Prüfung nach Schritt 6:**
```bash
./gradlew test
# Alle Tests sollten grün sein
./gradlew build
# Vollständiger Build sollte erfolgreich sein
```

## Zusammenfassung der Änderungen

### Dateien mit Änderungen:
1. `InstructionArgumentType.java` - Enum erweitern
2. `Instruction.java` - OperandSource Enum, resolveOperands(), init() Signaturen
3. `LocationInstruction.java` - **Keine Änderung** (rawSourceId() bleibt unverändert)
4. `RuntimeInstructionSetAdapter.java` - Mapping erweitern
5. `InstructionAnalysisHandler.java` - Validierung für LOCATION_REGISTER
6. `OrganismStateConverter.java` - resolveInstructionView() erweitern
7. `H2DatabaseReaderInstructionResolutionTest.java` - Test hinzufügen

### Dateien ohne Änderungen:
- `IInstructionSet.java` - ArgKind bleibt unverändert
- `LocationInstructionCompilerTest.java` - Tests bleiben unverändert
- `VMLocationInstructionTest.java` - Tests bleiben unverändert
- `SidebarInstructionView.js` - Frontend bleibt unverändert

## Implementierungsreihenfolge

**Wichtig:** Die Schritte müssen in dieser Reihenfolge ausgeführt werden:

1. **Schritt 1:** Enum-Erweiterungen (Basis) - Kompilierbar, Tests funktionieren
2. **Schritt 2:** Runtime resolveOperands() erweitern - Kompilierbar, Tests funktionieren
3. **Schritt 3:** Runtime Signaturen anpassen - Kompilierbar, aber Compiler-Tests schlagen fehl (erwartet)
4. **Schritt 4:** Compiler-Validierung hinzufügen - Kompilierbar, Compiler-Tests sollten funktionieren
5. **Schritt 5:** Datapipeline erweitern - Kompilierbar, Datapipeline-Tests schlagen fehl (erwartet)
6. **Schritt 6:** Tests anpassen - Alle Tests sollten grün sein

**Nach jedem Schritt:**
- `./gradlew compileJava` ausführen
- Relevante Tests ausführen
- Bei Fehlern: Schritt korrigieren, bevor weiter gemacht wird

## Breaking Changes

- **ISA-Enums**: `InstructionArgumentType` und `OperandSource` erweitert (Breaking Change für externe Code, der diese Enums verwendet)
- **Maschinencode**: Unverändert (gleiche Register-IDs, nur Typ-Information erweitert)
- **Compiler**: Neue Validierung für `LOCATION_REGISTER` Argumente

## 12. Vorteile dieser Lösung

1. **Explizite Typen**: LR-Register werden durch Typ identifiziert, nicht durch ID-Range
2. **Compile-Time-Validierung**: Compiler kann prüfen, dass nur `%LRx` für `LOCATION_REGISTER` erlaubt ist
3. **Klare Semantik**: `REGISTER` = DR/PR/FPR, `LOCATION_REGISTER` = LR
4. **Keine Heuristik**: `resolveInstructionView()` muss nicht raten, ob Register LR oder DR ist
5. **Erweiterbar**: Weitere Register-Kategorien können als separate Typen hinzugefügt werden

