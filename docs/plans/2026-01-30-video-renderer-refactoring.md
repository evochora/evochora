# Video Renderer Refactoring Plan

**Datum:** 2026-01-30
**Status:** Geplant

## Übersicht

Refactoring des Video-Rendering-Systems für:
1. Saubere Trennung von Frame-Renderern und Overlay-Renderern
2. Plugin-artige Architektur mit Reflection-Loading
3. Neuer MinimapFrameRenderer für Minimap-Style Videos

---

## 1. Package-Struktur

```
org.evochora.cli.rendering/
│
├── IVideoFrameRenderer.java          # Interface für Frame-Renderer
├── IOverlayRenderer.java             # Interface für Overlays
│
├── frame/                            # Frame-Renderer Implementierungen
│   ├── ExactFrameRenderer.java       # (umbenennen von SimulationRenderer)
│   └── MinimapFrameRenderer.java     # (neu)
│
└── overlay/                          # Overlay-Renderer
    └── InfoOverlayRenderer.java      # (umbenennen von OverlayRenderer)
```

---

## 2. Interfaces

### IVideoFrameRenderer

```java
package org.evochora.cli.rendering;

import java.awt.image.BufferedImage;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;

public interface IVideoFrameRenderer {
    /**
     * Renders a snapshot tick (full environment data).
     */
    int[] renderSnapshot(TickData snapshot);

    /**
     * Renders a delta tick (incremental changes).
     */
    int[] renderDelta(TickDelta delta);

    /**
     * Returns the underlying BufferedImage for overlay rendering.
     */
    BufferedImage getFrame();

    /**
     * Returns the output image width in pixels.
     */
    int getImageWidth();

    /**
     * Returns the output image height in pixels.
     */
    int getImageHeight();
}
```

### IOverlayRenderer

```java
package org.evochora.cli.rendering;

import java.awt.image.BufferedImage;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDelta;

public interface IOverlayRenderer {
    /**
     * Renders overlay for a snapshot frame.
     * Full environment data available via tickData.getCellColumns().
     */
    void render(BufferedImage frame, TickData snapshot);

    /**
     * Renders overlay for a delta frame.
     * Only changed cells available via tickDelta.getChangedCells().
     */
    void render(BufferedImage frame, TickDelta delta);
}
```

---

## 3. CLI-Architektur (PicoCLI Subcommands)

Jeder Frame-Renderer wird als PicoCLI **Subcommand** implementiert. So definiert jeder Renderer seine eigenen CLI-Optionen, ohne dass `RenderVideoCommand` wissen muss, welche Renderer existieren.

### Warum Subcommands?

- **Keine Hardcodierung:** RenderVideoCommand kennt die Renderer nicht
- **Eigene Optionen:** Jeder Renderer definiert eigene Parameter (scale, etc.)
- **Eigene Validierung:** Jeder Renderer validiert seine Parameter selbst
- **Erweiterbar:** Neue Renderer = neue Klasse, keine Änderung in RenderVideoCommand

### Subcommand-Registrierung (Dynamisch via Reflection)

```java
// In RenderVideoCommand oder einer eigenen Registry-Klasse
private void registerRendererSubcommands(CommandLine cmdLine) {
    // Scanne das Package nach Klassen mit @Command Annotation
    Reflections reflections = new Reflections("org.evochora.cli.rendering.frame");
    Set<Class<?>> rendererClasses = reflections.getTypesAnnotatedWith(Command.class);

    for (Class<?> clazz : rendererClasses) {
        if (IVideoFrameRenderer.class.isAssignableFrom(clazz)) {
            Command cmdAnnotation = clazz.getAnnotation(Command.class);
            String name = cmdAnnotation.name();
            cmdLine.addSubcommand(name, clazz);
        }
    }
}
```

**Alternative (ohne Reflections-Library):** ServiceLoader oder manuelle Registrierung in einer Konfigurationsdatei.

### Frame-Renderer als Subcommand

Jeder Frame-Renderer ist gleichzeitig:
1. Eine Klasse die `IVideoFrameRenderer` implementiert
2. Ein PicoCLI Subcommand mit `@Command` Annotation

```java
@Command(name = "exact", description = "Exact pixel-per-cell rendering")
public class ExactFrameRenderer implements IVideoFrameRenderer {

    @Option(names = "--scale",
            description = "Pixels per cell (default: 4)",
            defaultValue = "4")
    private int scale;

    @Option(names = "--overlay",
            description = "Overlays to apply (comma-separated)",
            split = ",")
    private List<String> overlayNames;

    // Weitere exact-spezifische Optionen...

    // IVideoFrameRenderer Implementation
    @Override
    public int[] renderSnapshot(TickData snapshot) { ... }

    @Override
    public int[] renderDelta(TickDelta delta) { ... }

    @Override
    public BufferedImage getFrame() { ... }

    @Override
    public int getImageWidth() { ... }

    @Override
    public int getImageHeight() { ... }
}
```

```java
@Command(name = "minimap", description = "Minimap-style aggregated rendering with glow")
public class MinimapFrameRenderer implements IVideoFrameRenderer {

    @Option(names = "--scale",
            description = "Fraction of world size (0 < scale < 1, default: 0.3)",
            defaultValue = "0.3")
    private double scale;

    @Option(names = "--overlay",
            description = "Overlays to apply (comma-separated)",
            split = ",")
    private List<String> overlayNames;

    // Validierung im Konstruktor oder init() Methode
    public void init(EnvironmentProperties envProps) {
        if (scale <= 0 || scale >= 1) {
            throw new IllegalArgumentException("Minimap scale must be between 0 and 1 (exclusive)");
        }
        // ... Initialisierung
    }

    // IVideoFrameRenderer Implementation
    // ...
}
```

### RenderVideoCommand als Parent

```java
@Command(name = "video",
         description = "Render simulation video",
         subcommands = {})  // Subcommands werden dynamisch registriert
public class RenderVideoCommand implements Runnable {

    // Globale Optionen (gelten für alle Renderer)
    @Option(names = "--storage", description = "Storage directory")
    private Path storagePath;

    @Option(names = {"-o", "--out"}, description = "Output video file")
    private Path outputFile;

    @Option(names = "--fps", description = "Frames per second", defaultValue = "30")
    private int fps;

    @Option(names = "--start-tick", description = "Start tick")
    private Long startTick;

    @Option(names = "--end-tick", description = "End tick")
    private Long endTick;

    // KEIN --renderer, --scale, --cell-size hier!

    @Override
    public void run() {
        // Dieser Code wird nur aufgerufen wenn KEIN Subcommand angegeben wurde
        System.err.println("Please specify a renderer: video exact, video minimap, ...");
    }
}
```

### Beispiel-Aufrufe

```bash
# Exact Renderer mit Scale 4
./evochora video exact --scale 4 --out video.mkv

# Minimap Renderer mit Scale 0.3
./evochora video minimap --scale 0.3 --out video.mkv

# Exact mit Info-Overlay
./evochora video exact --scale 4 --overlay info --out video.mkv

# Minimap mit Overlays
./evochora video minimap --scale 0.3 --overlay info,stats --out video.mkv

# Globale Optionen vor Subcommand
./evochora video --storage /data/sim exact --scale 4 --out video.mkv
```

### Overlay-Loading

Overlays werden weiterhin per Reflection geladen:

```java
// In der Renderer-Klasse oder einer gemeinsamen Utility
private List<IOverlayRenderer> loadOverlays(List<String> names) throws Exception {
    List<IOverlayRenderer> overlays = new ArrayList<>();
    if (names == null) return overlays;

    for (String name : names) {
        String className = "org.evochora.cli.rendering.overlay."
            + capitalize(name) + "OverlayRenderer";
        overlays.add((IOverlayRenderer) Class.forName(className)
            .getConstructor()
            .newInstance());
    }
    return overlays;
}
```

### Overlay-Anwendung

```java
// Im Renderer nach Frame-Rendering:

// Bei Snapshot:
renderSnapshot(snapshot);
for (IOverlayRenderer overlay : overlays) {
    overlay.render(getFrame(), snapshot);
}

// Bei Delta:
renderDelta(delta);
for (IOverlayRenderer overlay : overlays) {
    overlay.render(getFrame(), delta);
}
```

---

## 4. ExactFrameRenderer

**Datei:** `src/main/java/org/evochora/cli/rendering/frame/ExactFrameRenderer.java`

Umbenennung von `SimulationRenderer` mit folgenden Änderungen:

1. Implementiert `IVideoFrameRenderer`
2. Konstruktor: `ExactFrameRenderer(EnvironmentProperties envProps, double scale)`
   - `scale` wird zu `int cellSize` (muss >= 1 sein)
3. Bestehende Logik bleibt erhalten

```java
package org.evochora.cli.rendering.frame;

public class ExactFrameRenderer implements IVideoFrameRenderer {

    public ExactFrameRenderer(EnvironmentProperties envProps, double scale) {
        this.cellSize = (int) scale;  // scale >= 1
        // ... rest wie SimulationRenderer
    }

    // ... bestehende Methoden
}
```

---

## 5. MinimapFrameRenderer (NEU)

**Datei:** `src/main/java/org/evochora/cli/rendering/frame/MinimapFrameRenderer.java`

### Konstruktor

```java
public MinimapFrameRenderer(EnvironmentProperties envProps, double scale) {
    // scale ist Bruchteil der Weltgröße (0 < scale < 1)
    // z.B. scale=0.3 bei 1000x1000 Welt → 300x300 Output

    this.worldWidth = envProps.getWorldShape()[0];
    this.worldHeight = envProps.getWorldShape()[1];
    this.outputWidth = (int) (worldWidth * scale);
    this.outputHeight = (int) (worldHeight * scale);

    // Scale-Faktoren für Koordinaten-Mapping
    this.scaleX = worldWidth / outputWidth;   // Welt-Zellen pro Output-Pixel
    this.scaleY = worldHeight / outputHeight;

    // Frame-Buffer
    this.frame = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
    this.frameBuffer = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();

    // Glow-Sprites vorberechnen
    initGlowSprites();
}
```

### Cell-Aggregation (Majority Voting)

Exakt wie Server-seitige MinimapAggregator:

```java
private void aggregateCells(CellDataColumns cellColumns) {
    // counts[pixelIndex * NUM_TYPES + typeIndex] = Anzahl
    int[] counts = new int[outputWidth * outputHeight * NUM_TYPES];

    // Sparse Iteration über alle Zellen
    int cellCount = cellColumns.getFlatIndicesCount();
    for (int i = 0; i < cellCount; i++) {
        int flatIndex = cellColumns.getFlatIndices(i);
        int moleculeInt = cellColumns.getMoleculeData(i);

        // Welt-Koordinaten berechnen
        int[] coord = envProps.flatIndexToCoordinates(flatIndex);
        int wx = coord[0];
        int wy = coord[1];

        // Zu Output-Pixel mappen
        int mx = Math.min(wx / scaleX, outputWidth - 1);
        int my = Math.min(wy / scaleY, outputHeight - 1);
        int mIdx = my * outputWidth + mx;

        // Zelltyp bestimmen und zählen
        int typeIndex = getCellTypeIndex(moleculeInt);
        counts[mIdx * NUM_TYPES + typeIndex]++;
    }

    // Hintergrund-Gewichtung (2.5% wie Server)
    int totalPixels = outputWidth * outputHeight;
    int totalCells = worldWidth * worldHeight;
    int cellsPerPixel = scaleX * scaleY;

    for (int mIdx = 0; mIdx < totalPixels; mIdx++) {
        int baseIdx = mIdx * NUM_TYPES;
        int occupiedCells = 0;
        for (int t = 0; t < NUM_TYPES; t++) {
            occupiedCells += counts[baseIdx + t];
        }
        int backgroundCells = cellsPerPixel - occupiedCells;
        int weightedEmpty = backgroundCells / 40;  // 2.5% Gewicht
        counts[baseIdx + TYPE_EMPTY] += weightedEmpty;
    }

    // Majority Vote → Pixel-Farbe
    for (int mIdx = 0; mIdx < totalPixels; mIdx++) {
        int baseIdx = mIdx * NUM_TYPES;
        int maxCount = 0;
        int winningType = TYPE_EMPTY;

        for (int t = 0; t < NUM_TYPES; t++) {
            if (counts[baseIdx + t] > maxCount) {
                maxCount = counts[baseIdx + t];
                winningType = t;
            }
        }

        frameBuffer[mIdx] = CELL_COLORS[winningType];
    }
}
```

### Zelltyp-Konstanten

```java
private static final int TYPE_CODE = 0;
private static final int TYPE_DATA = 1;
private static final int TYPE_ENERGY = 2;
private static final int TYPE_STRUCTURE = 3;
private static final int TYPE_LABEL = 4;
private static final int TYPE_EMPTY = 5;
private static final int NUM_TYPES = 6;

private static final int[] CELL_COLORS = {
    0x3c5078,  // CODE - blue-gray
    0x32323c,  // DATA - dark gray
    0xffe664,  // ENERGY - yellow
    0xff7878,  // STRUCTURE - red/pink
    0xa0a0a8,  // LABEL - light gray
    0x1e1e28   // EMPTY - dark background
};
```

### Organism-Glow-Rendering

```java
// Glow-Konfiguration (wie Web-Minimap)
private static final int[] GLOW_SIZES = {6, 10, 14, 18};
private static final int[] DENSITY_THRESHOLDS = {3, 10, 30};
private static final int GLOW_COLOR = 0x4a9a6a;  // Muted green
private static final int CORE_SIZE = 3;

private int[][] glowSprites;  // [sizeIndex][pixelIndex] = ARGB

private void initGlowSprites() {
    glowSprites = new int[GLOW_SIZES.length][];

    for (int i = 0; i < GLOW_SIZES.length; i++) {
        int size = GLOW_SIZES[i];
        glowSprites[i] = createGlowSprite(size);
    }
}

private int[] createGlowSprite(int size) {
    int[] pixels = new int[size * size];
    float center = size / 2.0f;
    float radius = center;

    int r = (GLOW_COLOR >> 16) & 0xFF;
    int g = (GLOW_COLOR >> 8) & 0xFF;
    int b = GLOW_COLOR & 0xFF;

    for (int y = 0; y < size; y++) {
        for (int x = 0; x < size; x++) {
            float dx = x - center + 0.5f;
            float dy = y - center + 0.5f;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);

            int alpha = 0;

            // Core (solid)
            if (Math.abs(dx) <= CORE_SIZE/2.0f && Math.abs(dy) <= CORE_SIZE/2.0f) {
                alpha = 255;
            }
            // Glow (gradient)
            else if (dist <= radius) {
                float t = dist / radius;
                // 3-stop gradient: 60% -> 30% -> 0%
                if (t < 0.5f) {
                    alpha = (int) (153 - t * 2 * 76);  // 60% -> 30%
                } else {
                    alpha = (int) (76 * (1 - (t - 0.5f) * 2));  // 30% -> 0%
                }
            }

            pixels[y * size + x] = (alpha << 24) | (r << 16) | (g << 8) | b;
        }
    }

    return pixels;
}

private void renderOrganismGlows(List<OrganismState> organisms) {
    // Density Grid
    int[] density = new int[outputWidth * outputHeight];

    for (OrganismState org : organisms) {
        if (org.getIsDead()) continue;

        // IP Position
        int wx = org.getIp().getComponents(0);
        int wy = org.getIp().getComponents(1);
        int mx = Math.min(wx * outputWidth / worldWidth, outputWidth - 1);
        int my = Math.min(wy * outputHeight / worldHeight, outputHeight - 1);
        density[my * outputWidth + mx]++;

        // DP Positions
        for (Vector dp : org.getDataPointersList()) {
            wx = dp.getComponents(0);
            wy = dp.getComponents(1);
            mx = Math.min(wx * outputWidth / worldWidth, outputWidth - 1);
            my = Math.min(wy * outputHeight / worldHeight, outputHeight - 1);
            density[my * outputWidth + mx]++;
        }
    }

    // Render Glows
    for (int my = 0; my < outputHeight; my++) {
        for (int mx = 0; mx < outputWidth; mx++) {
            int count = density[my * outputWidth + mx];
            if (count == 0) continue;

            int spriteIndex = selectSpriteIndex(count);
            blitGlowSprite(mx, my, spriteIndex);
        }
    }
}

private int selectSpriteIndex(int count) {
    for (int i = 0; i < DENSITY_THRESHOLDS.length; i++) {
        if (count <= DENSITY_THRESHOLDS[i]) return i;
    }
    return GLOW_SIZES.length - 1;
}

private void blitGlowSprite(int centerX, int centerY, int spriteIndex) {
    int[] sprite = glowSprites[spriteIndex];
    int size = GLOW_SIZES[spriteIndex];
    int half = size / 2;

    int startX = centerX - half;
    int startY = centerY - half;

    for (int sy = 0; sy < size; sy++) {
        int fy = startY + sy;
        if (fy < 0 || fy >= outputHeight) continue;

        for (int sx = 0; sx < size; sx++) {
            int fx = startX + sx;
            if (fx < 0 || fx >= outputWidth) continue;

            int src = sprite[sy * size + sx];
            int alpha = (src >>> 24) & 0xFF;
            if (alpha == 0) continue;

            int idx = fy * outputWidth + fx;
            int dst = frameBuffer[idx];

            // Alpha blend
            int invA = 255 - alpha;
            int outR = (((src >> 16) & 0xFF) * alpha + ((dst >> 16) & 0xFF) * invA) / 255;
            int outG = (((src >> 8) & 0xFF) * alpha + ((dst >> 8) & 0xFF) * invA) / 255;
            int outB = ((src & 0xFF) * alpha + (dst & 0xFF) * invA) / 255;

            frameBuffer[idx] = (outR << 16) | (outG << 8) | outB;
        }
    }
}
```

### Render-Methoden

```java
@Override
public int[] renderSnapshot(TickData snapshot) {
    // 1. Cell-Aggregation (Majority Voting)
    aggregateCells(snapshot.getCellColumns());

    // 2. Organism Glows
    renderOrganismGlows(snapshot.getOrganismsList());

    return frameBuffer;
}

@Override
public int[] renderDelta(TickDelta delta) {
    // Für Minimap: Full Redraw (kein Incremental)
    // Delta hat nur changedCells, wir brauchen aber alle für Aggregation

    // Option A: Full redraw wenn wir cellColumns haben
    // Option B: Nur Organisms updaten

    // Einfachste Lösung: Organisms neu rendern über bestehendem Hintergrund
    // (Hintergrund ändert sich kaum, Organisms sind das Wichtige)

    // Hintergrund behalten, nur Organisms neu
    // TODO: Optimieren wenn nötig

    renderOrganismGlows(delta.getOrganismsList());

    return frameBuffer;
}
```

**HINWEIS zu renderDelta:** Die Minimap im Web rendert auch bei jedem Frame komplett neu (ist schnell genug bei 300x300). Alternativ könnten wir den Hintergrund cachen und nur Organisms neu rendern.

---

## 6. InfoOverlayRenderer

**Datei:** `src/main/java/org/evochora/cli/rendering/overlay/InfoOverlayRenderer.java`

Umbenennung von `OverlayRenderer` mit folgenden Änderungen:

1. Implementiert `IOverlayRenderer`
2. Zwei render-Methoden für TickData und TickDelta

```java
package org.evochora.cli.rendering.overlay;

public class InfoOverlayRenderer implements IOverlayRenderer {

    @Override
    public void render(BufferedImage frame, TickData snapshot) {
        long tick = snapshot.getTickNumber();
        int aliveCount = countAlive(snapshot.getOrganismsList());
        long totalBorn = snapshot.getTotalOrganismsCreated();

        renderOverlay(frame, tick, aliveCount, totalBorn);
    }

    @Override
    public void render(BufferedImage frame, TickDelta delta) {
        long tick = delta.getTickNumber();
        int aliveCount = countAlive(delta.getOrganismsList());
        long totalBorn = delta.getTotalOrganismsCreated();

        renderOverlay(frame, tick, aliveCount, totalBorn);
    }

    private int countAlive(List<OrganismState> organisms) {
        int count = 0;
        for (OrganismState org : organisms) {
            if (!org.getIsDead()) count++;
        }
        return count;
    }

    private void renderOverlay(BufferedImage frame, long tick, int alive, long born) {
        // ... bestehende Logik aus OverlayRenderer.render()
    }
}
```

---

## 7. Beispiel-Aufrufe (Subcommand-Syntax)

```bash
# Exact Renderer mit Scale 4 (wie bisheriges --cell-size 4)
./evochora video exact --scale 4 --out exact.mkv

# Exact mit Info-Overlay
./evochora video exact --scale 4 --overlay info --out exact.mkv

# Minimap mit Scale 0.3 (30% der Weltgröße)
./evochora video minimap --scale 0.3 --out minimap.mkv

# Minimap mit Info-Overlay
./evochora video minimap --scale 0.3 --overlay info --out minimap.mkv

# Mehrere Overlays
./evochora video exact --scale 4 --overlay info,stats --out video.mkv

# Globale Optionen (Storage) vor Subcommand
./evochora video --storage /data/sim minimap --scale 0.3 --out video.mkv
```

---

## 8. Migrations-Schritte

1. **IVideoFrameRenderer erstellen** in `org.evochora.cli.rendering`
2. **IOverlayRenderer erstellen** in `org.evochora.cli.rendering`
3. **Package `frame/` erstellen**
4. **SimulationRenderer → ExactFrameRenderer** umbenennen und verschieben
   - `@Command(name = "exact", ...)` Annotation hinzufügen
   - `--scale` Option hinzufügen (ersetzt internen cellSize-Parameter)
   - `--overlay` Option hinzufügen
5. **Package `overlay/` erstellen**
6. **OverlayRenderer → InfoOverlayRenderer** umbenennen und verschieben
7. **MinimapFrameRenderer** neu erstellen
   - `@Command(name = "minimap", ...)` Annotation
   - `--scale` Option (0 < scale < 1)
   - `--overlay` Option
8. **RenderVideoCommand** anpassen:
   - `--cell-size` entfernen
   - `--renderer` NICHT hinzufügen (Subcommands stattdessen)
   - Dynamische Subcommand-Registrierung via Reflection
   - Globale Optionen (--storage, --out, --fps, etc.) behalten
9. **Tests** anpassen/erweitern
10. **Kompilieren und testen**

---

## 9. Performance-Erwartung MinimapFrameRenderer

| Welt-Größe | Scale | Output | Pixel | Erwartete Zeit |
|------------|-------|--------|-------|----------------|
| 1000×1000 | 0.3 | 300×300 | 90k | <10ms |
| 1000×1000 | 0.1 | 100×100 | 10k | <2ms |
| 3000×3000 | 0.1 | 300×300 | 90k | <10ms |

Zum Vergleich: Web-Minimap rendert 300×300 in <5ms.
