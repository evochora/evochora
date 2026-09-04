# Relative .ORG and .DIR + Layout Bounds Checking

**Status: TO BE REVIEWED**

## Problem

### 1. Fragile absolute positioning in .ORG

All `.ORG` directives use absolute coordinates. Inserting code between two `.ORG` directives requires manually updating all subsequent positions:

```
.ORG 0|4
  ; ... code ...
.ORG 0|6       ; ← insert code above → must change to 0|8
  ; ... code ...
.ORG 0|8       ; ← must change to 0|10
  ; ... etc, cascading changes through entire file
```

In `primordial/lib/reproduce.evo`, there are 25+ `.ORG` directives at consecutive even y-positions. Inserting one code block means updating all subsequent lines.

### 2. No rotation support in .DIR

`.DIR` only supports absolute direction vectors. Changing placement direction requires knowing the current direction and computing the new vector manually. A relative rotation ("turn 90 degrees") is more natural for sequential code layout.

### 3. Silent out-of-bounds placement in non-toroidal grids

When code is placed at coordinates that fall outside the grid (e.g., negative absolute coordinates near a grid edge), `Environment.setMolecule` silently ignores the placement (`getFlatIndex` returns -1, the molecule is not placed). This means code is lost without any error. In toroidal grids, coordinates wrap correctly via `Math.floorMod`. In non-toroidal grids, out-of-bounds coordinates should be a compile-time error.

### 4. Dead code: anchorPos in LayoutContext — done

`LayoutContext.anchorPos` was set by `OrgLayoutHandler` but never read by any code. The field, getter, setter and the call have been deleted; nothing of this remains to do.

## Solution

### Implementation Steps

Three vertical steps, each independently compilable and testable:

```text
Step 1 (Bounds Check) → Step 2 (Relative .ORG) → Step 3 (Relative .DIR)
```

Step 1 is an independent bug fix. Step 2 introduces the `TILDE` token and relative vector components. Step 3 depends on Step 2 (reuses the `TILDE` token).

### Relative marker: `~` prefix

The `~` (tilde) prefix marks a vector component or .DIR directive as relative. Without `~`, values are absolute (unchanged behavior). This is necessary because `-` is already used for negative absolute values (e.g., `.ORG -3|0` places code 3 cells left of the organism origin).

### .ORG: Relative position offsets

Each component of the vector is independently absolute or relative. The `~` prefix marks a component as relative to the current position. Without `~`, the component is absolute.

**Syntax:**

```
.ORG 0|6            ; absolute: position = (0, 6)
.ORG ~0|~2          ; relative: position += (0, 2) — x unchanged, y += 2
.ORG 0|~2           ; mixed: x = 0 (absolute), y relative += 2
.ORG ~0|6           ; mixed: x unchanged (relative +0), y = 6 (absolute)
.ORG ~2|~-3         ; relative: x += 2, y -= 3
.ORG -3|0           ; absolute: position = (-3, 0) — negative absolute, valid
```

Key distinction: `0` = absolute zero, `~0` = relative zero (unchanged).

**N-dimensional:** Each component follows the same rule. In 3D:

```
.ORG ~0|~2|~0       ; only y changes
.ORG 0|~2|0         ; x and z set to 0, y relative
```

The number of components must match the environment's dimensionality. Mismatch is a compile error.

### .DIR: Absolute direction (unchanged) + relative rotation (new)

**Absolute (unchanged):** A vector literal with N components sets the direction vector directly.

```
.DIR 1|0            ; 2D: place along x
.DIR 0|1            ; 2D: place along y
.DIR -1|0           ; 2D: place in negative x direction
.DIR 0|1|0          ; 3D: place along y
```

**Relative (new):** A `~` prefixed rotation. The `~+` / `~-` determines the rotation direction. The semantics depend on dimensionality:

**1D — direction flip:**

```
.DIR ~+              ; flip direction: (1) → (-1) → (1) → cycle
.DIR ~-              ; flip direction: same as ~+ (only two states)
```

In 1D, `~+` and `~-` are equivalent — both flip the single-axis direction.

**2D — single plane, no axis specification needed:**

```
.DIR ~+              ; 90° rotation: (1,0) → (0,1) → (-1,0) → (0,-1) → cycle
.DIR ~-              ; -90° rotation: (1,0) → (0,-1) → (-1,0) → (0,1) → cycle
```

**3D+ — plane specified by two axis indices:**

```
.DIR ~+0|1           ; 90° in (dim0, dim1) plane: rotates dim0 component toward dim1
.DIR ~-0|1           ; -90° in (dim0, dim1) plane: rotates dim1 component toward dim0
.DIR ~+0|2           ; 90° in (dim0, dim2) plane
.DIR ~+1|2           ; 90° in (dim1, dim2) plane
```

**Rotation semantics:** `~+i|j` rotates the current direction vector 90° in the (i, j) plane, moving the component in axis i toward axis j.

Equivalence: `~+i|j` ≡ `~-j|i` (same rotation, expressed with flipped axes and negated sign).

**Examples in 3D, starting from (1, 0, 0):**

| Directive | Rotation | Result |
|---|---|---|
| `.DIR ~+0\|1` | dim0 → dim1 | (0, 1, 0) |
| `.DIR ~+0\|2` | dim0 → dim2 | (0, 0, 1) |
| `.DIR ~-0\|1` | dim1 → dim0 | (0, -1, 0) |
| `.DIR ~+1\|0` | dim1 → dim0 | (0, -1, 0) |

### Parser behavior

The `~` prefix unambiguously marks relative mode. The parser does NOT know the environment dimensionality (that is only available in Phase 9 via EnvironmentProperties). Therefore the parser accepts all structurally valid forms and the dimensionality validation happens in Phase 9 (Layout).

**For .ORG:**
- N components, each either bare number (absolute) or `~` prefixed (relative). The parser accepts any number of components; Phase 9 validates the count matches the environment dimensionality.

**For .DIR:**
1. Starts with `~` → relative rotation. The parser accepts two structural forms:
   - `~+` or `~-` alone (no axis specification)
   - `~+` or `~-` followed by exactly 2 axis indices separated by `|`
2. Starts with number → absolute direction vector (any number of `|`-separated components).

Phase 9 validates:
- 1D/2D rotation: axis specification must be absent. If present → compile error.
- 3D+ rotation: axis specification must be present. If absent → compile error.

### .DIR rotation validation (Phase 9)

- Axis indices must be valid dimension indices (0 to N-1) and distinct. `.DIR ~+0|0` is a compile error.
- Rotating a direction vector that has no component in the rotation plane leaves it unchanged. This is a no-op, not an error.

### Bounds checking for non-toroidal grids

In Phase 9 (Layout), after computing the final absolute coordinates (base position + .ORG vector), validate against the environment's world shape:

- **Toroidal grid:** coordinates wrap via `Math.floorMod` — no change needed, already works correctly.
- **Non-toroidal grid:** any coordinate component `c` where `c < 0 || c >= shape[dim]` is a **compile error**: "Coordinate %s is out of bounds for non-toroidal grid with shape %s."

This check applies to all placed items (opcodes, operands, labels), not just .ORG directives. The check is in `LayoutContext.placeAtCurrent()`, before the existing address conflict check.

---

## Step 1: Bounds Check + Dead Code Removal

Independent bug fix. No new syntax.

**LayoutContext.java** (`backend/layout/`):
- In `placeAtCurrent()`, add bounds check before the existing address conflict check:

```java
if (!envProps.isToroidal()) {
    for (int i = 0; i < currentPos.length; i++) {
        if (currentPos[i] < 0 || currentPos[i] >= envProps.getWorldShape()[i]) {
            throw new CompilationException(String.format(
                "Coordinate %s is out of bounds for non-toroidal grid with shape %s.",
                Arrays.toString(currentPos), Arrays.toString(envProps.getWorldShape())));
        }
    }
}
```

- `LayoutContext` needs access to `EnvironmentProperties` for the toroidal check. It already has the field (`envProps`, line 17).

**Tests:**
- Non-toroidal grid: placement at (-1, 0) with basePos (0, 0) → compile error
- Non-toroidal grid: placement at (0, 1000) with shape (1000, 1000) → compile error
- Toroidal grid: placement at (-1, 0) → wraps correctly, no error
- Non-toroidal grid: valid coordinates → no error
- Bounds check triggers on advancing past grid edge (not just .ORG)
- All 5 existing CLI smoke tests remain green

---

## Step 2: Relative .ORG

Depends on Step 1 (bounds check catches relative offsets that land out of bounds).

### Lexer changes

**Lexer** — recognize `~` as a new token type `TILDE`. Add alongside other single-character tokens.

### AST changes

**OrgNode** — extend to carry per-component relative flags:

Currently `OrgNode(AstNode originVector)`. Change to `OrgNode(AstNode originVector, boolean[] relativeMask)`. The `relativeMask` array has one entry per vector component: `true` if that component was `~` prefixed, `false` otherwise. For fully absolute `.ORG` (unchanged syntax), all entries are `false`.

`VectorLiteralNode` remains unchanged (`List<Integer>`). The relative information is carried solely in `OrgNode.relativeMask`, not in the vector literal. This avoids impacting other consumers of VectorLiteralNode (.PLACE, instruction operands, .DIR absolute mode).

### Parser changes

**OrgDirectiveHandler** — after consuming `.ORG`, parse each vector component: check for `TILDE` before each number. Build the `boolean[] relativeMask` alongside the vector values. For negative relative values, the token sequence is `TILDE`, `NUMBER(-3)`. Produce `OrgNode(vectorNode, relativeMask)`.

### IR changes

**OrgNodeConverter** — emit a `relativeMask` boolean list alongside the `position` vector in the IR directive args.

### Layout changes (Phase 9)

**OrgLayoutHandler** — compute new position per-component:

```java
int[] newPos = new int[N];
for (int i = 0; i < N; i++) {
    if (relativeMask[i]) {
        newPos[i] = currentPos[i] + vec[i];
    } else {
        newPos[i] = basePos[i] + vec[i];
    }
}
context.setCurrentPos(newPos);
```

**Tests:**
- `.ORG 0|6` → absolute, position = (0, 6) — unchanged behavior
- `.ORG -3|0` → absolute negative, position = (-3, 0) — unchanged behavior
- `.ORG ~0|~2` from (0, 4) → position = (0, 6)
- `.ORG 0|~2` from (3, 4) → position = (0, 6) — x reset to 0, y relative
- `.ORG ~0|~0` → no-op
- `.ORG ~2|~-3` from (5, 10) → position = (7, 7)
- Mixed absolute/relative in 3D
- Wrong number of components → compile error
- All 5 existing CLI smoke tests remain green
- Assembly file using relative `.ORG` compiles and produces correct layout

---

## Step 3: Relative .DIR

Depends on Step 2 (reuses `TILDE` token).

### AST changes

**DirMode** — new sealed interface in `features/dir/`:

```java
public sealed interface DirMode {
    record Absolute(AstNode vector, SourceInfo sourceInfo) implements DirMode {}
    record Rotation(boolean positive, int axisA, int axisB,
                    boolean axesExplicit, SourceInfo sourceInfo) implements DirMode {}
}
```

`axesExplicit` distinguishes between the parser-inferred default plane (1D/2D: `axisA=0, axisB=1`, `axesExplicit=false`) and explicitly specified axes (3D+: `axesExplicit=true`). Phase 9 validates the combination against the actual dimensionality.

In 1D, `axisA=0, axisB=0` — the rotation degenerates to a direction flip (special-cased in DirLayoutHandler).

**DirNode** — change from `DirNode(AstNode directionVector)` to `DirNode(DirMode mode)`. `getChildren()` returns the vector's children for `Absolute`, empty list for `Rotation`.

### Parser changes

**DirDirectiveHandler** — if the first token after `.DIR` is `TILDE`:
- Consume `TILDE`.
- Next token determines sign: positive number or `+` prefix → `positive=true`, negative number or `-` prefix → `positive=false`.
- If followed by `NUMBER PIPE NUMBER` → explicit axes: `Rotation(positive, axisA, axisB, true, src)`.
- If no further tokens → implicit axes: `Rotation(positive, 0, 1, false, src)`.
- In 1D implicit case, DirLayoutHandler handles the special semantics.

Otherwise: absolute mode, parse vector as before, wrap in `Absolute`.

### IR changes

**DirNodeConverter** — for `Absolute`: emit direction vector (unchanged). For `Rotation`: emit `positive` (boolean), `axisA` (int), `axisB` (int), `axesExplicit` (boolean) into the IR directive args.

### Layout changes (Phase 9)

**DirLayoutHandler** — for absolute mode: set direction vector (unchanged). For rotation mode:

First validate dimensionality:
- `axesExplicit == false && N > 2` → compile error: "Rotation plane must be specified for %dD environments."
- `axesExplicit == true && N <= 2` → compile error: "Rotation plane must not be specified for %dD environments."
- `axisA == axisB` → compile error: "Rotation axes must be distinct."
- `axisA >= N || axisB >= N` → compile error: "Axis index %d is out of range for %dD environment."

Then apply rotation:

**1D** — direction flip:
```
v[0] = -v[0]
```

**2D+** — 90° rotation in plane (i, j):

Positive rotation (`~+i|j`):
```
v[i], v[j] = -v[j], v[i]
```

Negative rotation (`~-i|j`):
```
v[i], v[j] = v[j], -v[i]
```

All other components remain unchanged.

**Tests:**
- `.DIR 0|1` → absolute direction (0, 1) — unchanged behavior
- `.DIR -1|0` → absolute direction (-1, 0) — unchanged behavior
- 1D: `.DIR ~+` from (1) → direction (-1)
- 1D: `.DIR ~-` from (1) → direction (-1) (equivalent to ~+)
- 1D: `.DIR ~+` twice from (1) → direction (1) (back to start)
- 2D: `.DIR ~+` from (1, 0) → direction (0, 1)
- 2D: `.DIR ~-` from (1, 0) → direction (0, -1)
- 2D: `.DIR ~+` four times cycles back to original direction
- 3D: `.DIR ~+0|1` from (1, 0, 0) → direction (0, 1, 0)
- 3D: `.DIR ~-0|1` from (1, 0, 0) → direction (0, -1, 0)
- 3D: `.DIR ~+0|1` ≡ `.DIR ~-1|0` equivalence test
- Rotation of zero-component → no-op (not an error)
- `.DIR ~+0|0` → compile error (same axis)
- Invalid axis index → compile error
- 2D rotation with explicit axis specification → compile error
- 3D rotation without axis specification → compile error
- 1D rotation with axis specification → compile error
- All 5 existing CLI smoke tests remain green
