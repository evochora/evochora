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
### 2.1 Neue Package-Struktur
``` 
src/main/java/org/evochora/
├── execution/                      # Laufzeit-Execution-Services
│   ├── ExecutionContext.java          # Kapselung von Laufzeitinformationen
│   ├── ProcedureCallHandler.java      # CALL/RET Copy-In/Copy-Out-Logik
│   └── CallBindingResolver.java       # Parameterbindungs-Strategien
├── assembler/
│   ├── ast/                           # Abstract Syntax Tree
│   │   ├── AssemblerSymbolTable.java     # Unveränderliche Symbol-Verwaltung
│   │   ├── AstNode.java
│   │   ├── InstructionNode.java
│   │   ├── LabelNode.java
│   │   └── DirectiveNode.java
│   ├── pipeline/                      # Unveränderliche Pass-Pipeline
│   │   ├── AssemblyPipeline.java         # Pipeline-Orchestrator
│   │   ├── PassContext.java              # Unveränderlicher Kontext
│   │   └── passes/
│   │       ├── ParsePass.java            # Source → AST
│   │       ├── SymbolResolutionPass.java # Symbol-Auflösung
│   │       ├── CallBindingPass.java      # CALL .WITH Analyse
│   │       ├── LayoutPass.java           # Adresszuweisung
│   │       └── CodeGenPass.java          # AST → Maschinencode
│   └── // Bestehende Klassen (vereinfacht)
├── organism/
│   ├── Organism.java                  # Reines Zustandsmanagement
│   ├── Instruction.java               # Vereinfachte Basisklasse
│   └── instructions/
│       └── ControlFlowInstruction.java   # Drastisch vereinfacht
└── world/                             # Unverändert
```
### 2.2 Kernkomponenten der neuen Architektur
#### ExecutionContext
- Kapselt alle Laufzeitinformationen und Metadaten-Zugriff
- Macht alle Abhängigkeiten explizit und testbar
- Trennt Laufzeit- von Compile-Zeit-Belangen

#### CallBindingResolver
- Isoliert die Parameterbindungslogik mit verschiedenen Auflösungsstrategien
- Strategy Pattern für erweiterbare Bindungslogik
- Testbar ohne komplexe Metadaten-Setup

#### ProcedureCallHandler
- Implementiert das Copy-In/Copy-Out-Pattern für Prozeduraufrufe
- Kapselt die gesamte CALL/RET-Komplexität
- Wiederverwendbar und isoliert testbar

#### AssemblerSymbolTable (AST)
- Unveränderliche Symboltabelle mit hierarchischen Scopes
- Ersetzt das mutable State-Management im PassManager
- Builder-Pattern für schrittweise Konstruktion

#### AssemblyPipeline
- Funktionale Pipeline mit klaren Phasen
- Jeder Pass ist eine reine Funktion
- Unveränderlicher PassContext zwischen den Pässen

## 3. Migrationsplan
### 3.1 Phase 1: Grundlagen (Wochen 1-2)
**Ziel**: Neue Architektur parallel zum bestehenden System etablieren
**Lieferungen**:
- Erstelle `execution` Package mit Kern-Services
- Implementiere `ExecutionContext` als Metadaten-Zugriffsschicht
- Erstelle `CallBindingResolver` mit Strategy Pattern
- Implementiere `ProcedureCallHandler` für CALL/RET-Logik
- Erhalte 100% Rückwärtskompatibilität

**Erfolgskriterien**:
- Alle bestehenden Tests bestehen weiterhin
- Neue Klassen haben >90% Testabdeckung
- Performance-Impact < 5%

### 3.2 Phase 2: Instruction-Refactoring (Wochen 3-4)
**Ziel**: Migriere Instruction-Klassen zu neuen Services
**Lieferungen**:
- Refaktoriere zur Nutzung der Execution-Services `ControlFlowInstruction`
- Erstelle vereinfachtes Instruction-Ausführungsmuster
- Migriere andere Instruction-Familien schrittweise
- Aktualisiere Instruction-Tests zur Nutzung gemockter Services

**Erfolgskriterien**:
- reduziert von ~250 auf ~80 Zeilen `ControlFlowInstruction`
- Alle Instruction-Tests nutzen Dependency Injection
- Keine statischen Aufrufe zu aus Instructions `AssemblyProgram`

### 3.3 Phase 3: AST-Grundlagen (Wochen 5-6)
**Ziel**: Implementiere unveränderlichen AST und Symboltabelle
**Lieferungen**:
- Erstelle `AssemblerSymbolTable` mit unveränderlichem Design
- Implementiere AST-Node-Hierarchie
- Erstelle AST-Builder-Utilities
- Entwickle AST → bestehendes Format Konverter für Kompatibilität

**Erfolgskriterien**:
- AST kann alle aktuellen Assembly-Konstrukte repräsentieren
- Symboltabelle unterstützt hierarchische Scopes
- Konvertierung zwischen AST und aktuellem Format ist verlustfrei

### 3.4 Phase 4: Pipeline-Implementierung (Wochen 7-8)
**Ziel**: Implementiere unveränderliche Pass-Pipeline
**Lieferungen**:
- Erstelle `AssemblyPipeline` mit funktionalen Pässen
- Implementiere `PassContext` für unveränderliches State-Threading
- Erstelle initiale Pass-Implementierungen (Parse, SymbolResolution)
- Implementiere `CallBindingPass` für Parameter-Analyse

**Erfolgskriterien**:
- Pipeline kann einfache Programme assemblieren
- Jeder Pass ist eine reine Funktion
- Pipeline-Zustand ist zwischen Pässen inspizierbar

### 3.5 Phase 5: Schrittweise Migration (Wochen 9-10)
**Ziel**: Migriere Assembler-Funktionalität zur neuen Pipeline
**Lieferungen**:
- Implementiere verbleibende Pässe (Layout, CodeGen)
- Erstelle Kompatibilitätsschicht für bestehende -Aufrufe `PassManager`
- Migriere komplexe Assembly-Features (Makros, Includes, etc.)
- Aktualisiere Assembler-Tests zur Nutzung der neuen Pipeline

**Erfolgskriterien**:
- Neue Pipeline assembliert alle bestehenden Test-Programme
- Performance entspricht aktueller Implementierung oder übertrifft sie
- Fehlerberichterstattung ist mit besseren Source-Locations verbessert

### 3.6 Phase 6: Legacy-Bereinigung (Wochen 11-12)
**Ziel**: Entferne veraltete Komponenten und optimiere
**Lieferungen**:
- Entferne alten und `PassManager``CodeExpander`
- Bereinige statische Registries in Instruction-Klassen
- Optimiere Pipeline-Performance
- Aktualisiere Dokumentation und Beispiele

**Erfolgskriterien**:
- Codebasis ist 30-40% kleiner
- Build-Zeit um 20% verbessert
- Alle Deprecation-Warnungen aufgelöst

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
### 4.2 Performance-Vorteile
- **Assembly-Zeit**: 15-25% schneller durch unveränderliche Optimierungen
- **Speicherverbrauch**: 20% Reduktion durch besseren Object-Lifecycle
- **Debugging**: Pipeline-Zustandsinspektionsfähigkeiten
- **Fehlerberichterstattung**: Bessere Source-Location-Verfolgung

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
