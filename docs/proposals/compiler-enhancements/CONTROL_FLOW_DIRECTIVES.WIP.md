# Compiler: Control Flow Directives

**Status: WORK IN PROGRESS — specification incomplete.**

Specified and ready for review: `.IF` / `.ELSEIF` / `.ELSE` / `.ENDIF`, including code generation
for both the skip-next and the branch instruction path, validation rules, and four implementation steps.

Still to be written: `.WHILE`, `.FOR`, `.BREAK`, `.CONTINUE`. Loop directives need decisions on
loop-label scoping, nesting of `.BREAK`/`.CONTINUE` across loop levels, and whether `.FOR` iterates
over a register range, a vector, or a state block.

## Problem

All control flow in Evochora assembly is manual: the programmer writes conditional instructions, labels, and jump instructions by hand. This is error-prone, hard to read, and produces repetitive patterns.

```
; Manual "if DR0 == 10, do body, else do other"
IFI %DR0 DATA:10
JMPI __skip
; else-body
JMPI __end
__skip:
; then-body
__end:
```

Higher-level control flow directives (`.IF`, `.WHILE`, `.FOR`) can generate these patterns automatically, reducing errors and improving readability. The generated machine code is identical to hand-written conditional + jump sequences.

## Prerequisites

This proposal requires the END-directive rename from the Conditional Compilation proposal (`.ENDP` → `.ENDPROC`, `.ENDM` → `.ENDMACRO`, etc.) to establish the `.END*` naming convention. `.ENDIF` follows this convention.

This proposal does **not** require the Conditional Branch ISA extension. It works with skip-next instructions (generating Skip + JMPI sequences). If the Branch ISA extension is implemented, the generated code can optionally use branch instructions for better code density (1 instruction instead of 2 per branch point). Both paths are documented below.

## Solution: .IF / .ELSEIF / .ELSE / .ENDIF

### Syntax

```
.IF <conditional-instruction> <operands>
  ; then-body
.ELSEIF <conditional-instruction> <operands>
  ; elseif-body
.ELSE
  ; else-body
.ENDIF
```

- `.ELSEIF` can appear zero or more times.
- `.ELSE` is optional and can appear at most once.
- `.ELSEIF` after `.ELSE` → compile error.
- Nesting is allowed to arbitrary depth.
- `<conditional-instruction>` is any registered conditional instruction (skip-next or branch).

### Semantics

The condition in `.IF` and `.ELSEIF` describes when the body is **executed** — same as how the conditional instruction itself works ("if equal, act"). The compiler generates the necessary negation and jumps to implement this.

### Code Generation

The compiler determines whether the instruction after `.IF`/`.ELSEIF` is a skip-next or branch conditional by querying the ISA registry (`Instruction.getInstructionClassById() == ConditionalInstruction.class`). It then checks the operand sources to determine the variant.

#### Without Branch ISA Extension (skip-next only)

Every conditional instruction is skip-next. The compiler generates the negated skip + JMPI to skip over the body when the condition is not met.

**Simple .IF:**

```
.IF IFI %DR0 DATA:10
  ; body
.ENDIF
```

Generated:
```
INI %DR0 DATA:10            ; negated: skip JMPI when equal (= execute body)
JMPI __endif_1               ; jump past body when NOT equal
; body
__endif_1:
```

The compiler uses the negated conditional (`ConditionalUtils.getNegatedOpcode()` or, if the Branch ISA metadata is implemented, `ConditionalInstruction.getNegated()`) to produce the skip-over.

**IF / ELSE:**

```
.IF IFI %DR0 DATA:10
  ; then-body
.ELSE
  ; else-body
.ENDIF
```

Generated:
```
INI %DR0 DATA:10            ; skip JMPI when equal
JMPI __else_1                ; jump to else when NOT equal
; then-body
JMPI __endif_1               ; skip else-body
__else_1:
; else-body
__endif_1:
```

**IF / ELSEIF / ELSE:**

```
.IF IFI %DR0 DATA:10
  ; body-10
.ELSEIF IFI %DR0 DATA:20
  ; body-20
.ELSE
  ; default-body
.ENDIF
```

Generated:
```
INI %DR0 DATA:10            ; skip JMPI when equal to 10
JMPI __elseif_1              ; jump to elseif when NOT equal to 10
; body-10
JMPI __endif_1               ; done
__elseif_1:
INI %DR0 DATA:20            ; skip JMPI when equal to 20
JMPI __else_1                ; jump to else when NOT equal to 20
; body-20
JMPI __endif_1               ; done
__else_1:
; default-body
__endif_1:
```

#### With Branch ISA Extension

If a branch variant exists for the conditional instruction, the compiler can generate a single branch instruction instead of the negated skip + JMPI pair. The branch variant is negated to skip over the body (same logic, fewer instructions).

**Simple .IF with skip-next input:**

```
.IF IFI %DR0 DATA:10
  ; body
.ENDIF
```

Generated (with branch optimization):
```
BNI %DR0 DATA:10 __endif_1   ; branch when NOT equal → skip body (1 instruction)
; body
__endif_1:
```

The compiler uses `ConditionalInstruction.getBranchVariant("IFI")` → `"BFI"`, then `ConditionalInstruction.getNegated("BFI")` → `"BNI"`. BNI branches when NOT equal, skipping the body.

**Simple .IF with branch input:**

```
.IF BFI %DR0 DATA:10
  ; body
.ENDIF
```

Generated:
```
BNI %DR0 DATA:10 __endif_1   ; getNegated("BFI") → "BNI"
; body
__endif_1:
```

The user writes BFI (branch if equal), the compiler emits BNI (branch if NOT equal) to skip the body. The condition name in the source describes when the body **executes**, the generated code negates it.

**IF / ELSE with branch optimization:**

```
.IF IFI %DR0 DATA:10
  ; then-body
.ELSE
  ; else-body
.ENDIF
```

Generated:
```
BNI %DR0 DATA:10 __else_1    ; branch when NOT equal → skip to else
; then-body
JMPI __endif_1                ; skip else-body
__else_1:
; else-body
__endif_1:
```

**IF / ELSEIF / ELSE with branch optimization:**

```
.IF IFI %DR0 DATA:10
  ; body-10
.ELSEIF IFI %DR0 DATA:20
  ; body-20
.ELSE
  ; default-body
.ENDIF
```

Generated:
```
BNI %DR0 DATA:10 __elseif_1  ; branch when NOT equal to 10
; body-10
JMPI __endif_1
__elseif_1:
BNI %DR0 DATA:20 __else_1    ; branch when NOT equal to 20
; body-20
JMPI __endif_1
__else_1:
; default-body
__endif_1:
```

#### Fallback logic

When the compiler encounters `.IF <conditional>`:

1. If the Branch ISA metadata is available (`ConditionalInstruction.getBranchVariant()` exists):
   - Get the branch variant of the conditional (or use it directly if already a branch).
   - Get the negated form of the branch variant.
   - Emit the negated branch with the generated label. (1 instruction)
   - If no branch variant exists: fall back to step 2.
   - If no negated branch variant exists: fall back to step 2.
2. Fall back to skip-next:
   - Get the negated form of the skip conditional.
   - Emit negated skip + JMPI with the generated label. (2 instructions)
   - If no negated form exists: compile error: "Conditional instruction '%s' cannot be negated for .IF block generation."

### Validation

- `.ELSE` without preceding `.IF` → compile error
- `.ENDIF` without preceding `.IF` → compile error
- `.ELSEIF` after `.ELSE` → compile error
- End of file inside `.IF` block → compile error
- Instruction after `.IF`/`.ELSEIF` is not a conditional → compile error: "Instruction '%s' after .IF is not a conditional instruction. Expected a conditional like IFI, INR, LTI, BFMI, etc."
- `.IF` without any instruction after it → compile error

### Label Generation

The compiler generates unique label names for each `.IF`/`.ELSEIF`/`.ELSE`/`.ENDIF` block using a monotonic counter: `__if_N`, `__elseif_N`, `__else_N`, `__endif_N`. Labels are prefixed with `__` to avoid collision with user-defined labels. The counter is global across the compilation unit to ensure uniqueness, and it belongs to that compilation: it starts at zero for every run of the compiler, never in a static field, so that the same source compiles to the same artifact. The bridge labels of the marshalling rules (`_safe_call_N`) are numbered the same way.

### Phase and Architecture

`.IF`/`.ELSEIF`/`.ELSE`/`.ENDIF` are **Phase 3 (Parser) directives**. The parser recognizes them and generates the corresponding AST nodes. The AST nodes are then converted to IR in Phase 7 (IrGen), which emits the conditional + jump + label sequences.

This follows the Feature-Slicing architecture: a new `features/controlflow/` package with:
- `IfDirectiveHandler` — parser handler for `.IF`
- `ElseIfDirectiveHandler` — parser handler for `.ELSEIF`
- `ElseDirectiveHandler` — parser handler for `.ELSE`
- `EndIfDirectiveHandler` — parser handler for `.ENDIF`
- `IfNode` — AST node containing condition, then-body, elseif-chains, else-body
- `IfNodeConverter` — IR converter that generates the conditional + jump + label sequences
- `ControlFlowFeature` — registration in StandardFeatures

The parser tracks `.IF` block nesting via a stack to match `.ELSEIF`/`.ELSE`/`.ENDIF` to the correct `.IF`.

## Implementation Steps

### Step 1: .IF / .ENDIF (no ELSE, no ELSEIF)

Minimal vertical slice: simple conditional block.

**New files** (`features/controlflow/`):
- `IfNode` — AST node: condition instruction (opcode + operands), body (List<AstNode>)
- `IfDirectiveHandler` — parses `.IF <conditional> <operands>`, collects body until `.ENDIF`
- `EndIfDirectiveHandler` — consumed by IfDirectiveHandler (not standalone)
- `IfNodeConverter` — emits negated conditional (+ JMPI if skip-next) + body + label
- `ControlFlowFeature` — registers handlers

**Tests:**
- `.IF IFI %DR0 DATA:10` + body + `.ENDIF` → correct IR with negated skip + JMPI + label
- `.IF` with non-conditional instruction → compile error
- `.IF` without `.ENDIF` → compile error
- `.ENDIF` without `.IF` → compile error
- Nested `.IF` blocks
- All 5 CLI smoke tests green

### Step 2: .ELSE

**Changes:**
- `IfNode` — add else-body (List<AstNode>, nullable)
- `IfDirectiveHandler` — parse `.ELSE` within `.IF` block
- `IfNodeConverter` — emit JMPI to skip else-body + else label + else-body

**Tests:**
- `.IF` / `.ELSE` / `.ENDIF` → correct IR
- `.ELSE` without `.IF` → compile error
- Multiple `.ELSE` → compile error
- All 5 CLI smoke tests green

### Step 3: .ELSEIF

**Changes:**
- `IfNode` — add elseif-chains (List of condition + body pairs)
- `IfDirectiveHandler` — parse `.ELSEIF` within `.IF` block
- `IfNodeConverter` — emit chained conditionals with labels

**Tests:**
- `.IF` / `.ELSEIF` / `.ELSE` / `.ENDIF` → correct IR
- Multiple `.ELSEIF` → correct chaining
- `.ELSEIF` after `.ELSE` → compile error
- All 5 CLI smoke tests green

### Step 4: Branch ISA optimization (optional, requires Branch ISA Extension)

**Changes:**
- `IfNodeConverter` — check `ConditionalInstruction.getBranchVariant()`, use negated branch variant if available, fall back to skip + JMPI

**Tests:**
- With branch ISA: `.IF IFI` → emits BNI with label (1 instruction)
- With branch ISA: `.IF BFI` → emits BNI with label
- Without branch ISA: `.IF IFI` → emits INI + JMPI (2 instructions, unchanged)
- All 5 CLI smoke tests green
