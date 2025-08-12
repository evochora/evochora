# Evochora Architecture Refactoring: Migrationsplan und Zielarchitektur
**Version**: 1.0
**Datum**: 12. August 2025
**Status**: Konzeptdokument
## Zusammenfassung
Dieses Dokument beschreibt einen umfassenden Refactoring-Plan für die Evochora-Architektur zur Behebung der starken Kopplung zwischen Instructions, Assembler und Organism-Komponenten. Das Refactoring führt eine klare Trennung der Verantwortlichkeiten durch Execution-Services und eine unveränderliche Assembler-Pipeline ein.
## 1. Problemanalyse der aktuellen Architektur
### 1.1 Identifizierte Probleme
- **Starke Kopplung**: Instructions greifen direkt auf statische AssemblyProgram-Methoden zu
- **Vermischte Verantwortlichkeiten**: PassManager verwaltet sowohl Assembly- als auch Laufzeit-Belange
- **Komplexes Zustandsmanagement**: Veränderlicher Zustand wird zwischen Assembler-Komponenten geteilt
- **Schlechte Testbarkeit**: Komplexe Abhängigkeiten erschweren Unit-Tests erheblich
- **Fehleranfällige Parameterbindung**: Mehrere Fallback-Strategien über die Codebasis verteilt

### 1.2 Auswirkungen auf die Wartbarkeit
- **Instruction-Klassen**: Über 250 Zeilen komplexe CALL/RET-Logik
- : Zustandsmanagement vermischt mit Code-Generierung **Assembler**
- : Indirekte Kopplungen zu Assembler-Metadaten **Organism**
- **Testaufwand**: Komplexe Setup-Logik für jeden Test erforderlich

## 2. Zielarchitektur
Die Zielarchitektur basiert auf einer strikten Trennung zwischen **Compile-Zeit** und **Laufzeit**. Alle Verantwortlichkeiten werden klar einem der beiden neuen Top-Level-Packages zugeordnet: `compiler` und `runtime`. Diese Aufteilung löst die Kernprobleme der Kopplung und vermischten Zuständigkeiten und schafft eine saubere, moderne Grundlage für zukünftige Entwicklungen.

### 2.1 Neue Package-Struktur
``` 
src/main/java/org/evochora/
├── app/
│   ├── ui/
│   │   ├── AppView.java
│   │   ├── FooterController.java
│   │   ├── HeaderController.java
│   │   └── WorldRenderer.java
│   ├── Main.java
│   ├── Messages.java
│   ├── Simulation.java
│   └── setup/
│       ├── Config.java
│       ├── Setup.java
│       └── SimulationFactory.java 
├── compiler/
│   ├── api/
│   │   ├── Compiler.java
│   │   └── ProgramArtifact.java
│   └── internal/
│       ├── ast/
│       │   ├── SymbolTable.java
│       │   ├── AstNode.java
│       │   ├── DirectiveNode.java
│       │   ├── InstructionNode.java
│       │   └── LabelNode.java
│       ├── pipeline/
│       │   ├── CompilerPipeline.java
│       │   ├── PassContext.java
│       │   └── passes/
│       │       ├── CallBindingPass.java
│       │       ├── CodeGenPass.java
│       │       ├── LayoutPass.java
│       │       ├── ParsePass.java
│       │       └── SymbolResolutionPass.java
│       └── legacy/
│           ├── Assembler.java
│           ├── ...
│           └── directives/
│               └── ...
├── decompiler/
│   ├── api/
│   │   └── Decompiler.java
│   └── internal/
└── runtime/
    ├── api/
    │    └── VirtualMachine.java
    ├── internal/
    │    └── services
    │       ├── CallBindingResolver.java
    │       ├── ExecutionContext.java
    │       └── ProcedureCallHandler.java
    ├── isa/
    │   ├── IWorldModifyingInstruction.java
    │   ├── Instruction.java
    │   └── instructions/
    │       ├── ArithmeticInstruction.java
    │       ├── BitwiseInstruction.java
    │       ├── ConditionalInstruction.java
    │       ├── ControlFlowInstruction.java
    │       ├── DataInstruction.java
    │       ├── NopInstruction.java
    │       ├── StackInstruction.java
    │       ├── StateInstruction.java
    │       └── WorldInteractionInstruction.java
    └── model/
        ├── Organism.java
        ├── Entity.java
        └── World.java

```
### 2.2 Kernkomponenten der neuen Architektur
Die neue Architektur führt mehrere Schlüsselkomponenten ein, die die Probleme der Kopplung und der vermischten Verantwortlichkeiten lösen, gruppiert nach ihrer Zuständigkeit.

#### Compiler-Komponenten

*   **CompilerPipeline:**
    *   Ersetzt den monolithischen `PassManager` durch eine Kette von reinen, funktionalen Pässen (z.B. `ParsePass`, `SymbolResolutionPass`, `CodeGenPass`).
    *   Jeder Pass nimmt einen unveränderlichen Kontext entgegen und erzeugt einen neuen, was den Prozess zustandslos, testbar und parallelisierbar macht.
    *   Orchestriert den gesamten Übersetzungsprozess von Quellcode zum `ProgramArtifact`.

*   **SymbolTable:**
    *   Eine unveränderliche Datenstruktur zur Verwaltung von Symbolen (Labels, Variablen) mit hierarchischen Geltungsbereichen (Scopes).
    *   Wird schrittweise über einen Builder aufgebaut und zwischen den Pässem weitergereicht, was das komplexe, veränderliche Zustandsmanagement des alten Systems eliminiert.

*   **ProgramArtifact:**
    *   Das standardisierte, unveränderliche Austauschformat, das vom Compiler erzeugt wird.
    *   Enthält alles, was die Runtime zur Ausführung benötigt: den kompilierten Code, Metadaten (z.B. ISA-Version) und optionale Debug-Informationen.
    *   Dient als klar definierter Vertrag zwischen `compiler` und `runtime`.

#### Architektur-übergreifende Konzepte

*   **SourceMap (im ProgramArtifact):**
    *   Löst das "Decompiler"-Problem, ohne die Architektur zu verletzen.
    *   Wird vom `compiler` erzeugt und bildet jede ausführbare Instruktion auf ihre ursprüngliche Quellcode-Zeile ab.
    *   Wird von der `app`-Schicht zur Laufzeit gelesen, um die aktuell von der `VirtualMachine` ausgeführte Zeile im UI hervorzuheben.

#### Runtime-Komponenten

*   **VirtualMachine (VM):**
    *   Die öffentliche API der `runtime`-Bibliothek.
    *   Nimmt ein `ProgramArtifact` entgegen und führt es auf einem Zieldatenmodell (z.B. einem `Organism`) aus.
    *   Kapselt die Komplexität der Ausführung und verwaltet den Lebenszyklus interner Services.

*   **Interne Runtime-Services (Beispiele):**
    *   **ExecutionContext:** Kapselt alle zur Laufzeit benötigten Informationen (z.B. Zugriff auf `World` und `Organism`) und wird per Dependency Injection an die ausführenden Einheiten übergeben.
    *   **ProcedureCallHandler:** Isoliert die komplexe Logik für Prozeduraufrufe (CALL/RET) nach dem "Copy-In/Copy-Out"-Muster.
    *   **CallBindingResolver:** Entkoppelt die Strategien zur Auflösung von Parameterbindungen.

### 2.3 Ergänzungen aus dem Architektur-Review

1) Module und API-Grenzen schärfen
- Multi-Module (Gradle) mit ausschließlich zwei Top-Level-Packages in der Codebasis:
  - compiler.frontend, compiler.core, compiler.passes, compiler.artifact
  - runtime.api (inkl. ISA-Deskriptoren), runtime.exec, runtime.vm, runtime.syscall
  - optionale Apps/CLIs als Module unter compiler.cli und runtime.cli
- Keine Abhängigkeiten von runtime.* nach compiler.*; Austausch ausschließlich über neutrale Verträge:
  - ProgramArtifact (compiler.artifact) als Übergabeformat von Compile- nach Laufzeit.
  - ISA-Deskriptoren (runtime.api.isa) als stabile, versionierte Beschreibung der Instruktionssemantik.

2) ISA/VM-Grenze und Versionierung
- Versionierte ISA-Deskriptoren (SemVer) mit Opcode, Operanden, Seiteneffekten, Feature-Flags und Capability-Set (heap, io, fp).
- Kompatibilitätsmatrix: vom Compiler erzeugte ISA-Version → von der VM unterstützte Versionen.
- Laufzeit-Fähigkeitsprüfung: VM validiert ProgramArtifact gegen aktivierte ISA/Capabilities (deterministischer Fail early).
- Instruktions-Plugin-Mechanismus: Registrierungen aus Deskriptoren (keine statischen Singletons).

3) IR und ProgramArtifact
- Stabiles IR nach Symbol-/Binding-Auflösung (Instruktions-Stream, Konstanten, Layouts).
- ProgramArtifact:
  - **Header:** ISA-Version, Compiler-Version, Build-Metadaten.
  - **Code-Sektion:** Der kompilierte, ausführbare Code (IR).
  - **Source-Map-Sektion:** Eine detaillierte Zuordnung von jeder kompilierten Instruktion zurück zu ihrem Ursprung im Quellcode (Datei und Zeilennummer). **Dies ist der Schlüssel zum Debugging und zur Anzeige der aktuellen Codezeile.**
  - **Diagnostics-Sektion:** Eine Zusammenfassung aller Warnungen und Fehler, die während der Kompilierung aufgetreten sind.
- Binäres Artefaktformat plus optionaler textueller Dump; Compiler produziert, Runtime lädt/validiert/führt aus.

4) DiagnosticsEngine mit Fehlercodes
- Strukturierte Diagnostics mit stabilen Codes, Source-Ranges, Schweregraden und Hints.
- Konsistente Emission über alle Passes; keine Logger in Passes, nur Diagnostics.
- First-class Source-Maps: präzise Lokationen in jeder Phase.

5) Deterministische, sichere Runtime
- Explizite Syscall-/Host-Interfaces (Sandbox), injizier- und mockbar.
- Ressourcenbudgets (Schritte, Speicher, Call-Tiefe) mit Trap-Codes statt unkontrollierter Exceptions.
- Reproduzierbarkeit: deterministische Seeds/Clocks; keine versteckten globalen Uhren/Zufälle.

6) Konfiguration und Dependency Injection
- Keine Singletons/Global State; Konfiguration (Limits, Features, ISA) als explizite Objekte.
- Konstruktorinjektion mit klaren Composition-Roots in CLI/Runtime-Entrypoints.
- Service-Locator maximal als Adapter an der Peripherie.

7) Observability by design (Logging, Metriken, Tracing)
- **Logging**: Es wird eine Logging-Fassade (z.B. SLF4J) verwendet.
  - Die `compiler`- und `runtime`-Module hängen **ausschließlich** von der `slf4j-api` ab.
  - Das `app`-Modul liefert die konkrete Implementierung (z.B. Logback) und deren Konfiguration (z.B. in `src/main/resources/logback.xml`). Dies stellt eine vollständige Entkopplung sicher.
- **Strukturierte Logs**: Alle Log-Ausgaben erfolgen in einem strukturierten Format (z.B. JSON), um die maschinelle Auswertung zu erleichtern.
- **Metriken & Tracing**: Klare Hooks für Metriken (Zähler, Latenzen) und verteiltes Tracing (z.B. OpenTelemetry) werden in der `CompilerPipeline` und der `VirtualMachine` vorgesehen.
- **Debug-Dumps**: Die Möglichkeit, Zustände (AST, IR, Artefakt) auf Wunsch auszugeben, wird über Feature-Flags gesteuert.

8) Teststrategie
- Compiler:
  - Golden-File-Tests (Source → IR/Artifact), Source-Maps verifizieren.
  - Property-based Tests (z. B. Operanden-Bindungen, Symbol-Scopes).
  - Grammar-Fuzzing (Parser).
- Runtime:
  - Differentialtests (Interpreter-VM vs. Referenz).
  - Contract-Tests für ISA-Deskriptoren (jede Instruktion vs. definierte Semantik).
  - Ressourcengrenztests (Budget-/Trap-Pfade).
- End-to-End:
  - Fixtures mit bekannten Side-Effects.
  - Strict determinism checks (gleicher Seed ⇒ gleicher Output).

9) Performance und Speicher
- Pipeline: rein funktionale Passes, String/Identifier-Interning, Immutable-Collections.
- Caching: inkrementelles Parsen/Codegen, Artifact-Cache mit Content-Hash.
- Arena-/Pool-Allocator für kurzlebige Compiler-Objekte.
- Microbenchmarks (z. B. JMH) für Passes und VM-Hotpaths.
- Performance-Budgets je Phase; Regression-Guardrails in CI.

10) Migrationsleitplanken
- **Compiler-Fassade und Adapter**: Die neue `Compiler`-API-Fassade kapselt den gesamten Prozess. Zunächst delegiert sie an einen Adapter, der den alten `PassManager` umhüllt, um eine schrittweise Ablösung zu ermöglichen (Strangler-Fig-Pattern).
- **Feature-Toggles**: Pro Pass/Instruktionsfamilie, schrittweises Aktivieren der neuen Logik.
- **Legacy-Code-Freeze**: Nur Bugfixes im alten Code, keine neuen Features. Alle Änderungen werden in der neuen Architektur umgesetzt.
- **Automatisierte Codemods**: Helfen bei der schrittweisen Ablösung von API-Brüchen und statischen Registries.
- **Kontinuierliches Benchmarking**: Performance- und Coverage-Gates (>90% für neue Komponenten) stellen sicher, dass keine Regressionen eingeführt werden.

11) Offene Architekturentscheidungen (ADR-Kandidaten)
- IR-Level (flach vs. blockbasiert), Source-Map-Format, Syscall-Modell (sync vs. Yield), Trap-Politik, ISA-Kompatibilität.

12) Konkrete Checkliste (Start)
- [ ] ProgramArtifact-Spezifikation (Header, IR, Debug, Versionierung).
- [ ] ISA-Deskriptoren mit Validator und Capability-Matrix.
- [ ] DiagnosticsEngine: Codes, Ranges, Renderer.
- [ ] PassContext/CompilerPipeline finalisieren, „pure function“-Kontrakt dokumentieren.
- [ ] Runtime-API minimal definieren; Syscall-Interfaces mockbar.
- [ ] Observability: Log-/Metrics-/Trace-Hooks; Debug-Dumps mit Feature-Flag.
- [ ] CI: Fuzzing-Job, Performance-Gates, Golden-File-Diffs.

## 3. Migrationsplan (Inkrementell & Sicher)
Dieser Plan ist darauf ausgelegt, das System nach jedem kleinen Schritt in einem lauffähigen und testbaren Zustand zu halten.

### Phase 0: Grundgerüst und Struktur
**Ziel**: Die neue Paketstruktur anlegen, ohne die Logik zu verändern.
**Lieferungen**:
- Erstelle die vollständige Ziel-Paketstruktur (`app`, `compiler/api`, `compiler/internal`, `runtime/api`, etc.).
- Verschiebe alle existierenden Klassen grob in ihre zukünftigen Pakete (z.B. alter Compiler-Code nach `compiler/internal/legacy`).
- **Ändere keine Logik.**

**Erfolgskriterien**:
- Das Projekt ist nach der Umstrukturierung kompilier- und lauffähig.
- **Alle bestehenden Tests sind erfolgreich.**

### Phase 1: Runtime-Services entkoppeln (Strangler-Fig-Muster)
**Ziel**: Die komplexe Logik aus den `Instruction`-Klassen in neue, testbare Services auslagern, ohne die Aufrufer zu beeinflussen.
**Lieferungen**:
- Implementiere die neuen Runtime-Services (`ProcedureCallHandler`, `CallBindingResolver`, `ExecutionContext`) in `runtime/internal/services`.
- **Ändere die `execute()`-Methoden in den `Instruction`-Klassen:** Statt die Logik selbst zu enthalten, instanziieren und delegieren sie die Arbeit an die neuen Services. Die Signatur und das Verhalten der `execute()`-Methode bleiben für die Aufrufer identisch.

**Erfolgskriterien**:
- Die neuen Services haben eine hohe Testabdeckung (>95%).
- **Alle bestehenden Tests sind erfolgreich.**
- Die Komplexität der `Instruction`-Klassen ist signifikant reduziert.

### Phase 2: Compiler-Fassade mit Legacy-Adapter
**Ziel**: Den gesamten alten Compiler-Prozess hinter einer neuen, sauberen Fassade verstecken.
**Lieferungen**:
- Erstelle die öffentliche Compiler-API: `compiler.api.Compiler` und `compiler.api.ProgramArtifact`.
- Erstelle einen **`LegacyCompilerAdapter`**. Dieser Adapter ruft intern den alten `PassManager` auf und wandelt dessen Ergebnis in das neue `ProgramArtifact`-Format um.
- Die `Compiler.compile()`-Methode delegiert ihre Aufrufe 1:1 an diesen Adapter.
- Ändere die `app`-Schicht (`SimulationFactory`) so, dass sie **nur noch** die neue `Compiler.compile()`-Fassade aufruft.

**Erfolgskriterien**:
- Die gesamte Anwendung ist vom alten `PassManager` entkoppelt. Alle Aufrufe laufen über die neue Fassade.
- **Alle bestehenden Tests sind erfolgreich.**

### Phase 3: Schrittweise Pipeline-Implementierung (Pass für Pass)
**Ziel**: Den Legacy-Compiler von innen nach außen durch die neue Pipeline ersetzen, wobei das System jederzeit lauffähig bleibt.
**Lieferungen**:
- **3a: ParsePass:**
    - Implementiere die `CompilerPipeline` und den `ParsePass`. Die Pipeline kann nun `Source -> AST`.
    - Erstelle einen **`AstToLegacyFormatConverter`**, der einen AST in die alten Datenstrukturen des `PassManager` zurückverwandelt.
    - Ändere den `LegacyCompilerAdapter`: Er nutzt jetzt den `ParsePass` und füttert das Ergebnis via Konverter an den *restlichen* alten `PassManager`.
- **3b: Weitere Pässe:**
    - Implementiere den `SymbolResolutionPass`. Der Adapter nutzt jetzt `ParsePass` -> `SymbolResolutionPass` und konvertiert das Ergebnis zurück.
    - Wiederhole diesen Prozess für jeden weiteren Pass (`LayoutPass`, `CodeGenPass` etc.).

**Erfolgskriterien**:
- Nach der Implementierung jedes Passes ist das System lauffähig.
- **Alle bestehenden Tests sind nach jedem Unterschritt erfolgreich.**
- Die Logik im `LegacyCompilerAdapter` wird schrittweise durch die neue Pipeline ersetzt.

### Phase 4: Finale Umstellung und Bereinigung
**Ziel**: Den Umbau abschließen und allen alten Code entfernen.
**Lieferungen**:
- Sobald die `CompilerPipeline` vollständig ist und ein `ProgramArtifact` erzeugen kann, wird die `Compiler`-Fassade umkonfiguriert: Sie ruft jetzt direkt die `CompilerPipeline` auf.
- Der `LegacyCompilerAdapter` und der `AstToLegacyFormatConverter` werden nicht mehr benötigt und können gelöscht werden.
- Lösche das gesamte `legacy`-Paket im Compiler.
- Entferne alle weiteren veralteten Klassen und räume die Codebasis final auf.

**Erfolgskriterien**:
- Die Kompilierung läuft vollständig über die neue Architektur.
- **Alle bestehenden Tests sind erfolgreich.**
- Die Codebasis ist signifikant kleiner und frei von Legacy-Code.

## 4. Erwartete Vorteile
### 4.1 Code-Qualitätsverbesserungen
#### Instruction-Klassen: 60-70% Komplexitätsreduktion
``` java
// Vorher: Komplex, stark gekoppelt
public void execute() {
    // 100+ Zeilen Metadaten-Zugriff, Bindungsauflösung,
    // Koordinatentransformation, etc.
}

// Nachher: Sauber, fokussiert  
public void execute() {
    ExecutionContext context = new ExecutionContext(organism, world);
    ProcedureCallHandler handler = new ProcedureCallHandler(context);
    handler.executeCall(callSite, target, simulation);
}
```
#### Assembler: Klare Trennung zwischen Compile- und Laufzeit
``` java
// Vorher: Zustandsbehaftet, komplex
PassManager manager = new PassManager();
manager.processSomething(); // Versteckte Komplexität
manager.processSomethingElse(); // Mehr versteckte Komplexität

// Nachher: Funktionale Pipeline
AssemblyPipeline pipeline = new AssemblyPipeline();  
ProgramMetadata result = pipeline.assemble(sourceLines, programName);
```
### 4.2 Qualitäts- und Performance-Vorteile
- **Assembly-Zeit**: 15-25% schneller durch unveränderliche Optimierungen
- **Speicherverbrauch**: 20% Reduktion durch besseren Object-Lifecycle
- **Debugging**: Pipeline-Zustandsinspektionsfähigkeiten
- **Fehlerberichterstattung**: Hochpräzise und kontextbezogene Fehlermeldungen durch die zentrale `DiagnosticsEngine`, die exakte Datei- und Zeileninformationen über alle Assembler-Phasen hinweg verfolgt.

### 4.3 Entwicklererfahrung
- **Onboarding**: Klarere Architektur für neue Entwickler
- **Feature-Hinzufügung**: Wohldefinierte Erweiterungspunkte
- **Testen**: Isolierte Komponententest-Fähigkeiten
- **Debugging**: Klarer Ausführungsfluss und Zustandsinspektation

## 5. Risikomanagement
### 5.1 Technische Risiken
- **Kompatibilität**: Strikte Rückwärtskompatibilität während Migration beibehalten
- **Performance**: Kontinuierliches Benchmarking während der gesamten Migration
- **Komplexität**: Phasenweise Einführung verhindert überwältigende Änderungen

### 5.2 Migrationsrisiken
- **Regression**: Umfassende Testsuite mit >95% Abdeckung
- **Zeitplan**: Pufferzeit in jeder Phase eingebaut
- **Team-Wissen**: Pair Programming und Code Reviews

## 6. Erfolgsmetriken
### 6.1 Quantitative Ziele
- **Code-Reduktion**: 30% Gesamt-Codebasis-Reduktion
- **Testabdeckung**: >90% Abdeckung während der gesamten Migration beibehalten
- **Performance**: Keine Verschlechterung, 15% Verbesserungsziel
- **Build-Zeit**: 20% schnellere Builds

### 6.2 Qualitative Ziele
- **Entwicklergeschwindigkeit**: Schnellere Feature-Entwicklung
- **Bug-Reduktion**: Weniger kopplungsbedingte Bugs
- **Wartbarkeit**: Einfacher zu verstehen und zu modifizieren
- **Erweiterbarkeit**: Klare Muster für das Hinzufügen von Features

## 7. Klare Grenzen nach der Migration
### 7.1 Vereinfachte Komponenten
- **Runtime Services**: Behandeln Ausführungszeit-Belange
- **Assembly Services**: Behandeln Compile-Zeit-Belange
- **Domain Objects**: Fokussieren sich rein auf Zustandsmanagement
- **Keine Cross-Cutting Concerns**: Klarer Dependency-Fluss

### 7.2 Testbarkeit
Jede Komponente kann isoliert getestet werden:
``` java
@Test
void testCallBinding() {
    // Mock einfach zu erstellen
    ExecutionContext mockContext = mock(ExecutionContext.class);
    CallBindingResolver resolver = new CallBindingResolver(mockContext);
    
    // Testbar ohne komplexe Setup-Logik
}
```
## 8. Fazit
Diese Migration transformiert Evochora von einem stark gekoppelten, zustandsbehafteten System zu einer sauberen, funktionalen Architektur mit klarer Trennung der Verantwortlichkeiten. Der phasenweise Ansatz minimiert Risiken und liefert gleichzeitig sofortige Vorteile, was letztendlich zu einem wartbareren, testbareren und performanteren System führt.
Die Investition in dieses Refactoring wird sich durch reduzierte Wartungskosten, schnellere Feature-Entwicklung und verbesserte Systemzuverlässigkeit langfristig auszahlen.
**Nächste Schritte**:
1. Review und Genehmigung dieses Dokuments
2. Detailplanung für Phase 1
3. Einrichtung von Metriken und Monitoring
4. Beginn der Implementierung
