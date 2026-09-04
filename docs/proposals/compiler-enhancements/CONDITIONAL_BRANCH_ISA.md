# ISA Extension: Conditional Branch Instructions

**Status: TO BE REVIEWED**

> **Review note (2026-08-23):** The proposed variant constants ignore that `Variant.L` and
> `Variant.RL` already exist, and that `L` is registered with both LABEL and LOCATION_REGISTER
> operands. Before implementation, reconcile the new slots with `Variant.java` and decide whether
> branch variants share those slots (and thus the mutation pool) or get their own. Also fix the
> Performance section: the label is resolved when the condition **is** met, not when it is not.

## Problem

All 18 conditional operations (IF, IN, LT, GT, LET, GET, IFT, INT, IFM, INM, IFP, INP, IFF, INF, IFV, INV, IER, INE) use skip-next semantics: they skip the next instruction if the condition is not met. To implement a conditional branch, the programmer must write a skip-next + JMP pair:

```
IFI %DR0 DATA:10     ; skip next if NOT equal
JMPI ELSE_LABEL      ; skipped when equal → body executes
; then-body
```

This works but has two disadvantages:
1. **Two instructions for one branch decision** — more molecules in the genome, more mutation surface per control flow decision.
2. **Future mutation robustness** — the skip-next and JMP are a coupled pair. While current mutation plugins (substitution, insertion, duplication, deletion) are all block-based or arity-preserving and don't break this pairing, future mutation mechanisms that are less conservative could fill or empty individual cells between the pair, breaking the logic.

Conditional branch instructions combine the condition test and the branch into a single atomic instruction, reducing code size and providing a self-contained control flow primitive.

## Solution

Add new **branch variants** to all 18 existing conditional operations. Each branch variant has the same comparison operands as its skip-next counterpart, plus an additional LABEL operand as branch target. The branch variant jumps to the label (via fuzzy matching) if the condition **is** met — same polarity as skip-next, matching universal convention (x86 JE, MIPS BEQ, ARM BEQ, RISC-V BEQ).

Both skip-next and branch use the same polarity: the condition name describes when the instruction acts. IFI executes the next instruction when equal. BFI branches to the label when equal.

Branch variants are registered as **new Variants of the existing Operations** (not new Operations). The different Variant number (due to the additional LABEL operand / different arity group) ensures that mutation cannot flip between skip-next and branch — they are isolated within the same Operation.

### Naming Convention

Replace the leading `I` with `B` for I-starting instructions. For non-I-starting instructions (LT, GT, LET, GET), drop the `T` and prepend `B`. The operand suffix (R, I, S, V) is preserved, indicating the comparison operand source. All names stay within 4 characters.

### Complete Instruction Table

#### Value Comparisons (Operations 0-7)

| Op | Semantics | Skip-Next (existing) | Branch (new) |
|---|---|---|---|
| 0 | Equal | IFR, IFI, IFS | BFR, BFI, BFS |
| 1 | Not Equal | INR, INI, INS | BNR, BNI, BNS |
| 2 | Less Than | LTR, LTI, LTS | BLR, BLI, BLS |
| 3 | Greater Than | GTR, GTI, GTS | BGR, BGI, BGS |
| 4 | Less or Equal | LETR, LETI, LETS | BLER, BLEI, BLES |
| 5 | Greater or Equal | GETR, GETI, GETS | BGER, BGEI, BGES |
| 6 | Type Equal | IFTR, IFTI, IFTS | BFTR, BFTI, BFTS |
| 7 | Type Not Equal | INTR, INTI, INTS | BNTR, BNTI, BNTS |

#### Environment Checks (Operations 8-15)

| Op | Semantics | Skip-Next (existing) | Branch (new) |
|---|---|---|---|
| 8 | Mine (accessible) | IFMR, IFMI, IFMS | BFMR, BFMI, BFMS |
| 9 | Not Mine | INMR, INMI, INMS | BNMR, BNMI, BNMS |
| 10 | Passable | IFPR, IFPI, IFPS | BFPR, BFPI, BFPS |
| 11 | Not Passable | INPR, INPI, INPS | BNPR, BNPI, BNPS |
| 12 | Foreign | IFFR, IFFI, IFFS | BFFR, BFFI, BFFS |
| 13 | Not Foreign | INFR, INFI, INFS | BNFR, BNFI, BNFS |
| 14 | Vacant | IFVR, IFVI, IFVS | BFVR, BFVI, BFVS |
| 15 | Not Vacant | INVR, INVI, INVS | BNVR, BNVI, BNVS |

#### Error Checks (Operations 16-17)

| Op | Semantics | Skip-Next (existing) | Branch (new) |
|---|---|---|---|
| 16 | Error | IFER | BFER |
| 17 | No Error | INER | BNER |

**Total: 52 new instructions** (matching the 52 existing skip-next instructions).

### New Variant Constants

New constants in `Variant.java` for operand combinations that include LABEL:

```java
// 2-Argument Variants (32-47) — new entries
public static final int RL_LABEL = 40;  // Register + Label
public static final int VL_LABEL = 41;  // Vector + Label
public static final int SL_LABEL = 42;  // Stack + Label

// 1-Argument Variants (16-31) — new entry
public static final int LABEL = 21;     // Label only (for BFER, BNER)

// 3-Argument Variants (48-63) — new entries
public static final int RRL = 53;       // Register + Register + Label
public static final int RIL = 54;       // Register + Immediate + Label
public static final int SSL = 55;       // Stack + Stack + Label
```

Exact slot numbers to be determined during implementation based on available slots. The key constraint: each new variant must be in the correct arity group (1-arg: 16-31, 2-arg: 32-47, 3-arg: 48-63) to ensure mutation stays within the same arity group.

### Execution Semantics

Identical to skip-next but with branch instead of skip:

- **Condition met** → resolve LABEL operand via `resolveLabelTarget()` (fuzzy matching), set IP to target, skip past the LABEL molecule
- **Condition not met** → fall through (execute next instruction normally)

This is the same label resolution used by JMPI/JMPR/JMPS. The LABEL operand is a LABELREF molecule in the code stream, resolved at runtime via the LabelIndex.

### Mutation Behavior

> **Note (2026-08-23):** The skip-next + `JMPI` pair is not only a code-density issue but a
> mutation surface: both selective sweeps observed so far (`RUN_20260402`, demo run 1) arose from
> an insertion into the padding between such a pair, which made the jump unconditional. Branch
> variants remove that surface in both directions. The decision to introduce them rests on the
> assumption that mutations at these pairs are predominantly lethal and only rarely beneficial —
> an assumption, not a measurement.

- **Operation Flip** (keeps family + variant fixed): BFI (Op 0, Var RIL) → BNI (Op 1, Var RIL), BLI (Op 2, Var RIL), etc. Switches between different branch comparisons. Skip-next variants have different Variant numbers (RI ≠ RIL) and are not in the same mutation pool.
- **Family Flip** (keeps operation + variant fixed): BFI (Family 4, Var RIL) → any instruction in another family with the same Variant RIL. Currently none exist, but future RIL instructions in other families would be valid flip targets.
- **Variant Flip** (keeps family + operation + arity group fixed): BFI (Op 0, Var RIL, 3-arg) → BFR (Op 0, Var RRL, 3-arg), BFS (Op 0, Var SSL, 3-arg). Switches operand sources within the same branch comparison. Skip-next variants are in a different arity group (2-arg) and are not flip targets.
- **Insertion**: If branch instructions are included in the GeneInsertionPlugin config (directly or via wildcard `*`), insertion generates a complete chain: CODE(branch opcode) + comparison operands + LABELREF(hash from existing label or random). The LABELREF generation already exists in `buildInstructionChain()` for the LABEL OperandSource.
- **Duplication**: Copies molecules 1:1 including the LABELREF operand. The duplicated branch points to the same label (or its duplicate, found via fuzzy matching).

### Performance

Branch variants call `resolveLabelTarget()` when the condition is not met — a label index lookup with fuzzy matching. This is more expensive than `skipNextInstruction()` (which is O(1) grid traversal). Skip-next variants remain available for cases where a simple single-instruction guard is sufficient (26% of conditionals in the primordial codebase use skip-next without JMP).

The compiler-generated control flow sugar (future proposal) will use branch variants, replacing skip-next + JMP pairs. Net effect: one label lookup instead of skip + label lookup — no regression, one fewer instruction executed.

### Conditional Instruction Metadata

> **Note (2026-09-04):** The skip half of this is done. `ConditionalUtils` is gone; every
> conditional is registered together with its negation in `ConditionalInstruction.regPair`,
> `negationOf` answers the negation by name, and the compiler asks through
> `IInstructionSet.negatedConditional`. What remains is the branch half: `regPair` grows into
> the `conditionalSet` described below, the instruction-set view gains the branch variant, and
> the marshalling rules ask the view rather than a table of their own.

Replace `ConditionalUtils` (hardcoded Map in `backend/emit/`) with explicit metadata on `ConditionalInstruction`. Relationships are declared via `conditionalSet()` — one call per instruction group that registers all four related instructions and derives all relationships automatically.

```java
// (skipPositive, skipNegated, branchPositive, branchNegated)
// Any argument can be null if the counterpart does not exist.
conditionalSet("IFR", "INR", "BFR", "BNR");
conditionalSet("IFI", "INI", "BFI", "BNI");
conditionalSet("IFS", "INS", "BFS", "BNS");
conditionalSet("LTR", "GETR", "BLR", "BGER");
conditionalSet("LTI", "GETI", "BLI", "BGEI");
conditionalSet("LTS", "GETS", "BLS", "BGES");
conditionalSet("GTR", "LETR", "BGR", "BLER");
conditionalSet("GTI", "LETI", "BGI", "BLEI");
conditionalSet("GTS", "LETS", "BGS", "BLES");
conditionalSet("IFTR", "INTR", "BFTR", "BNTR");
conditionalSet("IFTI", "INTI", "BFTI", "BNTI");
conditionalSet("IFTS", "INTS", "BFTS", "BNTS");
conditionalSet("IFMR", "INMR", "BFMR", "BNMR");
conditionalSet("IFMI", "INMI", "BFMI", "BNMI");
conditionalSet("IFMS", "INMS", "BFMS", "BNMS");
conditionalSet("IFPR", "INPR", "BFPR", "BNPR");
conditionalSet("IFPI", "INPI", "BFPI", "BNPI");
conditionalSet("IFPS", "INPS", "BFPS", "BNPS");
conditionalSet("IFFR", "INFR", "BFFR", "BNFR");
conditionalSet("IFFI", "INFI", "BFFI", "BNFI");
conditionalSet("IFFS", "INFS", "BFFS", "BNFS");
conditionalSet("IFVR", "INVR", "BFVR", "BNVR");
conditionalSet("IFVI", "INVI", "BFVI", "BNVI");
conditionalSet("IFVS", "INVS", "BFVS", "BNVS");
conditionalSet("IFER", "INER", "BFER", "BNER");
```

From each `conditionalSet(a, b, c, d)` call, the method derives (for non-null arguments):
- `a ↔ b`: negation (skip pair)
- `c ↔ d`: negation (branch pair)
- `a ↔ c`: skip↔branch (positive)
- `b ↔ d`: skip↔branch (negated)
- `a`, `b` marked as skip; `c`, `d` marked as branch; all marked as conditional

**Static query methods on ConditionalInstruction:**

```java
public static boolean isConditional(String opcode);
public static boolean isSkip(String opcode);
public static boolean isBranch(String opcode);
public static Optional<String> getNegated(String opcode);
public static Optional<String> getBranchVariant(String opcode);
public static Optional<String> getSkipVariant(String opcode);
```

All methods return `Optional.empty()` when no counterpart exists. If a new instruction is added without declaring its relationships, the methods return empty rather than guessing.

`ConditionalUtils` is deleted. All callers migrate to `ConditionalInstruction` methods.

### Compiler Marshalling Rule Changes

`CallerMarshallingRule` and `ProcedureMarshallingRule` currently detect "skip-conditional before CALL/RET" and generate `negate + JMPI + marshalling` (2 instructions overhead). With branch variants, they can generate better machine code (1 instruction overhead).

The rules reach these methods through `IInstructionSet`, the compiler's view of the instruction set; no feature reads `ConditionalInstruction` directly.

**Updated logic (same for both rules):**

```
For each IR item:
  1. Is it a skip-conditional AND the next item is a CALL/RET?
     → getBranchVariant(opcode):
       → Present: getNegated(branchVariant):
         → Present: emit negated-branch-variant with generated label + marshalling
                    (1 instruction overhead instead of 2)
         → Empty: fall back to step 1b
       → Empty (step 1b): getNegated(opcode):
         → Present: emit negated-skip + JMPI + marshalling (old behavior, fallback)
         → Empty: compile error:
           "Conditional instruction '%s' cannot be used directly before
            a CALL/RET because no branch variant or negated form exists
            to skip over the parameter marshalling sequence."
  2. Is it a CALL/RET (no skip-conditional before it)?
     → Standard marshalling (PUSH + CALL/RET + POP)
  3. Everything else (including branch-conditionals)?
     → Pass through unchanged
```

**Why branch-conditionals before CALL need no special handling (point 3):** When the programmer writes a branch-conditional before a CALL, they manage the control flow themselves. The branch has its own label target. The CALL is handled as a standalone CALL (point 2) and gets normal marshalling. The label defined by the programmer naturally ends up after the marshalling expansion (PUSH/CALL/POP), so the branch correctly skips the entire sequence.

**Example — skip-conditional before CALL (branch variant available):**

```
; Input IR:
IFI %DR0 DATA:10
CALL PROC REF %DR1

; Output (1 instruction overhead):
; IFI = "if equal, execute next" → to skip marshalling when NOT equal, use BNI (negated branch)
BNI %DR0 DATA:10 _safe_call_0    ; branch when NOT equal → skip marshalling
PUSH %DR1
CALL PROC
POP %DR1
_safe_call_0:
```

The marshalling rule uses `getNegated()` on the branch variant of the input conditional. For IFI: `getBranchVariant("IFI")` → `"BFI"`, then `getNegated("BFI")` → `"BNI"`. BNI branches when NOT equal, skipping the marshalling when the condition is not met.

## Implementation Steps

```text
Step 1 → Step 2 → Step 3 → Step 4 → Step 5 (strictly linear)
```

### Step 1: Metadata Infrastructure (replaces ConditionalUtils)

No new instructions. Refactors conditional metadata from hardcoded map to explicit declarations.

**ConditionalInstruction.java** (`runtime/isa/instructions/`):
- Add `conditionalSet(String skipPos, String skipNeg, String branchPos, String branchNeg)` static method (see "Conditional Instruction Metadata" section).
- Add static query methods: `isConditional()`, `isSkip()`, `isBranch()`, `getNegated()`, `getBranchVariant()`, `getSkipVariant()`.
- Register all existing skip-next conditionals via `conditionalSet()` with `null` for branch arguments:

```java
// After all existing reg() calls, declare metadata:
conditionalSet("IFR", "INR", null, null);
conditionalSet("IFI", "INI", null, null);
conditionalSet("IFS", "INS", null, null);
conditionalSet("LTR", "GETR", null, null);
// ... all 25 groups
conditionalSet("IFER", "INER", null, null);
```

**ConditionalUtils.java** (`backend/emit/`) — DELETE.

**CallerMarshallingRule.java** (`features/proc/`):
- Replace `ConditionalUtils.isConditional()` with `ConditionalInstruction.isConditional(opcode) && ConditionalInstruction.isSkip(opcode)`.
- Replace `ConditionalUtils.getNegatedOpcode()` with `ConditionalInstruction.getNegated()`.
- No behavior change — same negate + JMPI logic, different API.

**ProcedureMarshallingRule.java** (`features/proc/`):
- Same migration as CallerMarshallingRule.

**Tests:**
- Verify metadata: `ConditionalInstruction.getNegated("IFI")` → `Optional.of("INI")`
- Verify `isSkip("IFI")` → true, `isBranch("IFI")` → false, `isConditional("IFI")` → true
- Verify `getBranchVariant("IFI")` → `Optional.empty()` (no branch variants yet)
- Verify marshalling behavior unchanged (existing tests stay green)
- Build green, all existing tests green, all 5 CLI smoke tests green

---

### Step 2: Variant Constants + Branch Registration

52 new instructions registered. Not yet executable (execution logic in Step 3).

**Variant.java** (`runtime/isa/`):
- Add new variant constants: LABEL, RL_LABEL, VL_LABEL, SL_LABEL, RRL, RIL, SSL (exact slot numbers within arity groups).

**ConditionalInstruction.java** (`runtime/isa/instructions/`):
- In `register()`, add branch variant registrations alongside existing skip registrations. Example for Operation 0 (Equal):

```java
// Operation 0: Equal — skip-next (existing)
reg(0, Variant.RR, "IFR", REGISTER, REGISTER);
reg(0, Variant.RI, "IFI", REGISTER, IMMEDIATE);
reg(0, Variant.SS, "IFS", STACK, STACK);

// Operation 0: Equal — branch (new)
reg(0, Variant.RRL, "BFR", REGISTER, REGISTER, LABEL);
reg(0, Variant.RIL, "BFI", REGISTER, IMMEDIATE, LABEL);
reg(0, Variant.SSL, "BFS", STACK, STACK, LABEL);
```

For environment checks (1-operand comparisons):
```java
reg(8, Variant.RL_LABEL, "BFMR", REGISTER, LABEL);
reg(8, Variant.VL_LABEL, "BFMI", VECTOR, LABEL);
reg(8, Variant.SL_LABEL, "BFMS", STACK, LABEL);
```

For error checks (0-operand comparisons):
```java
reg(16, Variant.LABEL, "BFER", LABEL);
reg(17, Variant.LABEL, "BNER", LABEL);
```

- Update `conditionalSet()` calls to include branch arguments:

```java
conditionalSet("IFR", "INR", "BFR", "BNR");
conditionalSet("IFI", "INI", "BFI", "BNI");
conditionalSet("IFS", "INS", "BFS", "BNS");
// ... all 25 groups with full four arguments
```

**Tests:**
- Verify all 52 new instructions are registered: `Instruction.getInstructionIdByName("BFI")` returns valid ID
- Verify variant numbers are in correct arity groups
- Verify metadata: `getBranchVariant("IFI")` → `Optional.of("BFI")`, `getSkipVariant("BFI")` → `Optional.of("IFI")`
- Verify `isBranch("BFI")` → true, `isSkip("BFI")` → false
- Verify operation flip pools: BFI pool contains BNI, BLI, BGI, BLEI, BGEI, BFTI, BNTI but NOT IFI
- Verify variant flip pools: BFI pool contains BFR, BFS but NOT IFI, IFR, IFS
- Build green, all existing tests green

---

### Step 3: Execution Logic

Branch instructions become functional at runtime.

**ConditionalInstruction.execute()** (`runtime/isa/instructions/`):
- After the existing `if (!conditionMet) { organism.skipNextInstruction(environment); }` blocks, use `ConditionalInstruction.isBranch(getName())` to distinguish branch from skip variants.
- For branch variants when condition met: extract the label hash from the last operand (the LABEL operand), resolve via `resolveLabelTarget()`, and set IP to target.
- For branch variants when condition met: fall through (no action).
- For skip variants: existing behavior unchanged (`skipNextInstruction`).

**Tests (VMConditionalInstructionTest.java):**
- For each of the 52 new instructions, two tests:
  1. Condition met → falls through (next instruction executes)
  2. Condition met → branches to label (IP moves to label target)
- BFI with non-existent label → instructionFailed
- BFI with fuzzy-matched label → correct target
- Total: ~106 new test methods

---

### Step 4: Marshalling Optimization

Compiler generates better machine code for conditional CALL/RET.

**CallerMarshallingRule.java** (`features/proc/`):
- In `handleConditionalCall()`: get the negated branch variant to skip over marshalling. Steps: `getBranchVariant(opcode)` → `getNegated(branchVariant)` → emit negated branch with generated label (1 instruction overhead). If no branch variant exists, fall back to `getNegated(opcode)` + JMPI (2 instructions overhead, old behavior). If neither branch variant nor negated skip variant exists, compile error: "Conditional instruction '%s' cannot be used directly before a CALL because no branch variant or negated form exists to skip over the parameter marshalling sequence."
- Pattern match guard: `isConditional(opcode) && isSkip(opcode)` (unchanged from Step 1). Branch-conditionals before CALL pass through unchanged.

**ProcedureMarshallingRule.java** (`features/proc/`):
- Same changes as CallerMarshallingRule for conditional RET.

**Tests:**
- Verify marshalling: IFI before CALL → emits BNI with label (1 instruction overhead, negated branch)
- Verify marshalling: BFI before CALL → BFI passed through unchanged, CALL marshalled normally
- Verify marshalling fallback: skip-conditional without branch variant → negate + JMPI
- Verify compile error: skip-conditional without branch variant AND without negated → error
- Build green, all existing tests green

---

### Step 5: Assembly Spec

**ASSEMBLY_SPEC.md** (`docs/`):
- Add a new section "Conditional Branch Instructions" after the existing "Conditional Instructions" and "Negated Conditional Instructions" sections.
- Document syntax, semantics, and all 52 instructions with examples.
- Document the relationship between skip-next and branch variants.

**Tests:**
- All 5 CLI smoke tests green
