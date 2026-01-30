# Video Renderer Improvements

## Overview

Improve the video renderer to match the web visualizer's design language and add a new "glow" organism rendering style.

## Changes

### 1. New Organism Rendering Style: Glow

**CLI Option:** `--organism-style <exact|glow>` (default: `exact`)

**Glow Style Features:**
- Single unified color: `#4a9a6a` (muted green, same as minimap)
- Density-based glow radius (more organisms = larger glow)
- No directional triangles (aggregated view)
- Cell-size proportional sizing

**Density Thresholds (scaled by cellSize):**
```java
double[] glowMultipliers = {1.5, 2.5, 3.5, 4.5};
int[] densityThresholds = {3, 10, 30};
// At cellSize=4: glow sizes = [6, 10, 14, 18] px
```

**Implementation:**
- Add `OrganismStyle` enum to `SimulationRenderer`
- Pre-render glow sprites at construction time
- Calculate density grid per frame
- Use `TYPE_INT_ARGB` for alpha compositing

### 2. New Overlay Renderer

**CLI Option:** `--overlay` (replaces all previous overlay options)

**Design (Visualizer-consistent):**
```
┌─────────────────────────────────┐
│  Tick         1.234.567         │
│  Alive              1,204       │
│  Born               8,456       │
└─────────────────────────────────┘
```

**Styling:**
- Background: `rgba(25, 25, 35, 0.85)` (glass morphism)
- Border: `1px solid #333333`
- Border radius: 6px
- Font: Monospace (Roboto Mono with fallback)
- Text colors: `#e0e0e0` (primary), `#888888` (labels)
- Position: Bottom-right, 10px padding from edge

**Number Formatting:**
- Under 10,000: Thousands separator (locale-aware)
- 1M+: Short form (e.g., "1.2M")

**Data Sources:**
- Tick: `TickData.tick_number` / `TickDelta.tick_number`
- Alive: Count of organisms where `!is_dead`
- Born: `total_organisms_created` field

### 3. Removed Components

**Deleted:**
- `StatisticsBarRenderer.java` - redundant with new overlay

**Removed CLI Options:**
- `--overlay-tick`
- `--overlay-time`
- `--overlay-run-id`
- `--overlay-position`
- `--overlay-font-size`
- `--overlay-color`
- `--overlay-stats`

### 4. Color Updates (Visualizer Consistency)

Colors already match visualizer for cell types. No changes needed.

## File Changes

| File | Action |
|------|--------|
| `SimulationRenderer.java` | Add `OrganismStyle` enum, glow rendering |
| `OverlayRenderer.java` | **NEW** - Info panel renderer |
| `StatisticsBarRenderer.java` | **DELETE** |
| `RenderVideoCommand.java` | Update CLI options, integrate new renderers |

## CLI Examples

```bash
# Simple video with default settings
evochora video

# Video with overlay info panel
evochora video --overlay

# Video with glow organisms and overlay
evochora video --organism-style glow --overlay

# High quality render
evochora video --organism-style glow --overlay --cell-size 8 --fps 60
```
