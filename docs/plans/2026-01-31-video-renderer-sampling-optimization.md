# Video Renderer Sampling Optimization

## Status: PLANUNG

## Problem

Bei `--sampling-interval > 1` ist die Rendering-Geschwindigkeit extrem langsam:
- Ohne Sampling: ~2000 fps
- Mit Sampling (z.B. 6000): ~2 fps

### Ursache

Der aktuelle Ansatz nutzt `DeltaCodec.Decoder.decompressTick()`, welches für jeden Frame:
1. Snapshot laden
2. Accumulated/Incremental Deltas anwenden
3. **`state.toCellDataColumns()`** aufrufen → iteriert über ALLE Zellen (O(n))
4. Vollständiges TickData Protobuf-Objekt bauen

Bei einer 1000x1000 Welt = 1 Million Zellen pro Frame, auch wenn nur wenige Zellen geändert wurden.

### Warum inkrementelles Rendering schnell ist

Bei `samplingInterval=1` nutzt der Renderer `renderDelta()`:
- Nur geänderte Zellen werden aktualisiert → O(changed_cells)
- Kein TickData-Objekt wird gebaut
- Renderer behält internen Zustand (z.B. `cellColors[]` in ExactFrameRenderer)

---

## Lösungsansatz

### Kernidee

Der Renderer verwaltet seinen eigenen persistenten Zustand und wendet Deltas direkt an, ohne den Umweg über DeltaCodec/TickData.

**Trennung von Zustand und Zeichnen:**
- `applySnapshot(TickData)` - Zustand initialisieren, NICHT zeichnen
- `applyDelta(TickDelta)` - Zustand updaten, NICHT zeichnen
- `render()` - aktuellen Zustand zeichnen + Overlays anwenden

### Rendering-Logik bei sampling > 1

```java
for (chunk : chunksWithSampleTicks) {  // Nur relevante Chunks laden!

    // Snapshot anwenden
    renderer.applySnapshot(chunk.getSnapshot());
    if (snapshotTick % samplingInterval == 0) {
        pixels = renderer.render();
        writeFrame(pixels);
    }

    for (delta : chunk.getDeltas()) {
        renderer.applyDelta(delta);  // Nur Zustand updaten (schnell!)

        if (delta.getTickNumber() % samplingInterval == 0) {
            pixels = renderer.render();  // Nur bei Sample-Ticks zeichnen
            writeFrame(pixels);
        }
    }
}
```

### Chunk-Filterung (Optimierung)

Chunks die keine Sample-Ticks enthalten können komplett übersprungen werden:
```java
// Chunk enthält Ticks von firstTick bis lastTick
// Sample-Ticks sind: 0, N, 2N, 3N, ...
boolean chunkHasSampleTick = (lastTick / samplingInterval) > ((firstTick - 1) / samplingInterval);
```

### Multithreading

Funktioniert wie bisher:
- Jeder Thread hat seinen eigenen Renderer (via `createThreadInstance()`)
- Jeder Renderer hat seinen eigenen Zustand
- Chunks werden auf Threads verteilt

---

## Betroffene Dateien

### Interface: IVideoFrameRenderer.java

Neue Methoden hinzufügen:
```java
/**
 * Applies snapshot state without rendering.
 * Use for sampling mode where not every tick is rendered.
 */
void applySnapshot(TickData snapshot);

/**
 * Applies delta state without rendering.
 * Use for sampling mode where not every tick is rendered.
 */
void applyDelta(TickDelta delta);

/**
 * Renders the current internal state to pixels.
 * Call after applySnapshot/applyDelta to actually draw.
 */
int[] render();
```

Entfernen:
```java
// renderTick() wird nicht mehr benötigt
int[] renderTick(TickDataChunk chunk, long tickNumber);
```

### AbstractFrameRenderer.java

1. **Entfernen:**
   - `DeltaCodec.Decoder decoder` Feld
   - `renderTick()` Methode
   - Import von DeltaCodec

2. **Hinzufügen:**
   ```java
   @Override
   public void applySnapshot(TickData snapshot) {
       doApplySnapshot(snapshot);
   }

   @Override
   public void applyDelta(TickDelta delta) {
       doApplyDelta(delta);
   }

   @Override
   public int[] render() {
       int[] pixels = doRender();
       applyOverlays();  // Overlays auf aktuellem Zustand
       return pixels;
   }

   // Neue abstrakte Methoden für Subklassen:
   protected abstract void doApplySnapshot(TickData snapshot);
   protected abstract void doApplyDelta(TickDelta delta);
   protected abstract int[] doRender();
   ```

3. **Refactoring bestehender Methoden:**
   ```java
   @Override
   public final int[] renderSnapshot(TickData snapshot) {
       applySnapshot(snapshot);
       return render();
   }

   @Override
   public final int[] renderDelta(TickDelta delta) {
       applyDelta(delta);
       return render();
   }
   ```

### ExactFrameRenderer.java

Hat bereits `cellColors[]` als persistenten Zustand!

**Refactoring:**
```java
// Bestehend: doRenderSnapshot()
// Aufteilen in:

@Override
protected void doApplySnapshot(TickData snapshot) {
    // Nur cellColors[] updaten (bestehende Logik)
    Arrays.fill(cellColors, COLOR_EMPTY_BG);
    for (cell : snapshot.getCellColumns()) {
        cellColors[flatIndex] = getCellColor(moleculeInt);
    }
    // Organismen-Positionen tracken für späteres Clearing
    trackOrganismPositions(snapshot.getOrganismsList());
}

@Override
protected void doApplyDelta(TickDelta delta) {
    // Nur cellColors[] updaten (bestehende Logik aus doRenderDelta)
    clearPreviousOrganismAreas();
    for (cell : delta.getChangedCells()) {
        cellColors[flatIndex] = getCellColor(moleculeInt);
    }
    trackOrganismPositions(delta.getOrganismsList());
}

@Override
protected int[] doRender() {
    // cellColors[] zu frameBuffer[] konvertieren
    // Organismen zeichnen
    return frameBuffer;
}
```

### MinimapFrameRenderer.java

**Problem:** Hat keinen persistenten Zell-Zustand!

**Lösung:** Persistenten Zustand einführen:
```java
// NEU: Persistenter Zell-Zustand
private int[] cellTypes;  // TYPE_CODE, TYPE_DATA, etc. für jede Welt-Zelle

@Override
protected void doApplySnapshot(TickData snapshot) {
    Arrays.fill(cellTypes, TYPE_EMPTY);
    for (cell : snapshot.getCellColumns()) {
        cellTypes[flatIndex] = getCellTypeIndex(moleculeInt);
    }
    // Organismen-Positionen speichern
    this.currentOrganisms = snapshot.getOrganismsList();
}

@Override
protected void doApplyDelta(TickDelta delta) {
    for (cell : delta.getChangedCells()) {
        cellTypes[flatIndex] = getCellTypeIndex(moleculeInt);
    }
    this.currentOrganisms = delta.getOrganismsList();
}

@Override
protected int[] doRender() {
    // Aggregation von cellTypes[] zu outputPixels[]
    aggregateCellsFromState();
    renderOrganismGlows(currentOrganisms);
    return frameBuffer;
}
```

### VideoRenderEngine.java

**Änderungen in `renderAndWriteChunkDirect()` und `renderChunk()`:**

```java
if (samplingInterval == 1) {
    // INCREMENTAL - wie bisher
    renderSnapshot() + renderDelta()
} else {
    // SAMPLING - neuer Ansatz
    renderer.applySnapshot(chunk.getSnapshot());
    if (snapshotTick % samplingInterval == 0) {
        writeFrame(renderer.render());
    }

    for (delta : chunk.getDeltas()) {
        renderer.applyDelta(delta);
        if (delta.getTickNumber() % samplingInterval == 0) {
            writeFrame(renderer.render());
        }
    }
}
```

---

## Implementierungsreihenfolge

### Phase 1: Interface & AbstractFrameRenderer
1. Neue Methoden zu IVideoFrameRenderer hinzufügen
2. AbstractFrameRenderer: Template-Implementierung
3. DeltaCodec/renderTick entfernen

### Phase 2: ExactFrameRenderer
1. doRenderSnapshot() → doApplySnapshot() + doRender()
2. doRenderDelta() → doApplyDelta() + doRender()
3. Testen mit sampling

### Phase 3: MinimapFrameRenderer
1. Persistenten cellTypes[] Zustand einführen
2. doApplySnapshot(), doApplyDelta(), doRender() implementieren
3. Testen mit sampling

### Phase 4: VideoRenderEngine
1. Sampling-Logik umstellen auf applySnapshot/applyDelta/render
2. Chunk-Filterung implementieren (optional)
3. Testen single-threaded und multi-threaded

### Phase 5: Cleanup & Tests
1. Unit Tests für neuen Rendering-Pfad
2. Performance-Vergleich dokumentieren
3. Alte renderTick()-bezogene Code-Pfade entfernen

---

## Offene Fragen

1. **Overlay-Handling bei render():** Overlays brauchen TickData/TickDelta für Tick-Nummer etc. Wie übergeben wir das?
   - Option A: render(TickData/TickDelta) - aktuellen Tick mitgeben
   - Option B: Overlays bekommen Tick-Info aus letztem apply*() Aufruf

2. **Chunk-Filterung:** Lohnt sich die Optimierung oder reicht der apply-without-render Ansatz?

3. **Accumulated Deltas:** Der Renderer wendet jetzt alle Deltas an. Nutzen wir noch accumulated deltas als Shortcut, oder ist apply so schnell dass es egal ist?

---

## Erwartete Performance

Nach Implementierung bei `--sampling-interval=6000`:
- **Vorher:** ~2 fps (wegen toCellDataColumns für jeden Frame)
- **Nachher:** Ähnlich wie ohne Sampling, da:
  - apply*() ist O(changed_cells)
  - render() ist O(total_pixels), aber nur bei Sample-Ticks
  - Kein TickData/Protobuf-Overhead
