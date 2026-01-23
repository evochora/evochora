# ISA Extension and Rearrangement Proposal

## Overview

This document proposes a systematic rearrangement of the EvoASM instruction set to optimize for evolvability. The key goals are:

1. **Semantic locality**: Similar instructions should have numerically close opcode IDs
2. **Extensibility**: Reserve space for future instructions
3. **Mutation-friendly**: Enable mutation plugins to make semantically meaningful changes through simple arithmetic on opcode IDs

## Part A: Opcode ID Pattern

### Structure

```
Opcode = (Family × 1024) + (Operation × 32) + Variant

┌──────────────────────────────────────────────────────────────┐
│  Bits:  [FFFF][OOOOO][VVVVV]                                 │
│         │     │      │                                       │
│         │     │      └── Variant (5 bits = 32 slots)         │
│         │     │          Encodes operand source combination  │
│         │     │                                              │
│         │     └── Operation (5 bits = 32 per family)         │
│         │         The base operation within a family         │
│         │                                                    │
│         └── Family (4 bits = 16 families)                    │
│             Groups of related operations                     │
│                                                              │
│  Total: 14 bits = 0-16383                                    │
└──────────────────────────────────────────────────────────────┘
```

### Mutation Semantics

| Mutation | Effect |
|----------|--------|
| `+1` to `+31` | Change variant (operand sources) |
| `+32` to `+1023` | Change operation within family |
| `+1024` or more | Change family (major semantic change) |

### Family Assignments

| Family ID | Range | Name | Description |
|-----------|-------|------|-------------|
| 0 | 0-1023 | Special | NOP and reserved |
| 1 | 1024-2047 | Arithmetic | Mathematical operations |
| 2 | 2048-3071 | Bitwise | Bit manipulation |
| 3 | 3072-4095 | Data | Data movement and stack |
| 4 | 4096-5119 | Conditional | Branching conditions |
| 5 | 5120-6143 | Control | Control flow |
| 6 | 6144-7167 | Environment | World interaction |
| 7 | 7168-8191 | State | Organism state |
| 8 | 8192-9215 | Location | Location registers/stack |
| 9 | 9216-10239 | Vector | Vector manipulation |
| 10-15 | 10240-16383 | Reserved | Future expansion |

### Variant Encoding

Variants are grouped by argument count for natural mutation transitions:

| Variant ID | Pattern | Description |
|------------|---------|-------------|
| **0-Argument** | | |
| 0 | `-` | No operands |
| 1-7 | | Reserved |
| **1-Argument** | | |
| 8 | `R` | One register |
| 9 | `I` | One immediate |
| 10 | `S` | One stack value |
| 11 | `V` | One vector |
| 12 | `L` | One label/location register |
| 13-15 | | Reserved |
| **2-Argument** | | |
| 16 | `R,R` | Two registers |
| 17 | `R,I` | Register + immediate |
| 18 | `R,S` | Register + stack |
| 19 | `R,V` | Register + vector |
| 20 | `R,L` | Register + location register |
| 21 | `S,S` | Two stack values |
| 22 | `S,V` | Stack + vector |
| 23 | `L,L` | Two location registers |
| 24-27 | | Reserved |
| **3-Argument** | | |
| 28 | `R,R,R` | Three registers |
| 29 | `R,R,I` | Two registers + immediate |
| 30 | `R,I,I` | Register + two immediates |
| 31 | `S,S,S` | Three stack values |

---

## Part B: Instruction Catalog by Family

### Legend

- **Status**: `EXISTING` = currently implemented, `NEW` = proposed addition, `NEW-VAR` = new variant of existing instruction
- **Old ID**: Previous opcode ID (for existing instructions)
- **New ID**: Proposed opcode ID following the new pattern
- **Pattern**: `Family.Operation.Variant` breakdown

---

### Family 0: Special (0-1023)

Base: `0 × 1024 = 0`

#### Operation 0: NOP (No Operation)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| NOP | - | 0 | 0 | 0.0.0 | EXISTING | No operation; advances IP only |

---

### Family 1: Arithmetic (1024-2047)

Base: `1 × 1024 = 1024`

#### Operation 0: ADD (Addition)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| ADDR | R,R | 4 | 1040 | 1.0.16 | EXISTING | dest = dest + src (registers) |
| ADDI | R,I | 30 | 1041 | 1.0.17 | EXISTING | dest = dest + immediate |
| ADDS | S,S | 70 | 1045 | 1.0.21 | EXISTING | push(pop() + pop()) |

#### Operation 1: SUB (Subtraction)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SUBR | R,R | 6 | 1072 | 1.1.16 | EXISTING | dest = dest - src (registers) |
| SUBI | R,I | 31 | 1073 | 1.1.17 | EXISTING | dest = dest - immediate |
| SUBS | S,S | 71 | 1077 | 1.1.21 | EXISTING | push(pop() - pop()) |

#### Operation 2: MUL (Multiplication)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| MULR | R,R | 40 | 1104 | 1.2.16 | EXISTING | dest = dest × src (registers) |
| MULI | R,I | 41 | 1105 | 1.2.17 | EXISTING | dest = dest × immediate |
| MULS | S,S | 72 | 1109 | 1.2.21 | EXISTING | push(pop() × pop()) |

#### Operation 3: DIV (Division)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| DIVR | R,R | 42 | 1136 | 1.3.16 | EXISTING | dest = dest ÷ src (registers) |
| DIVI | R,I | 43 | 1137 | 1.3.17 | EXISTING | dest = dest ÷ immediate |
| DIVS | S,S | 73 | 1141 | 1.3.21 | EXISTING | push(pop() ÷ pop()) |

#### Operation 4: MOD (Modulo)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| MODR | R,R | 44 | 1168 | 1.4.16 | EXISTING | dest = dest % src (registers) |
| MODI | R,I | 45 | 1169 | 1.4.17 | EXISTING | dest = dest % immediate |
| MODS | S,S | 74 | 1173 | 1.4.21 | EXISTING | push(pop() % pop()) |

#### Operation 5: NEG (Negation) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| NEGR | R | - | 1208 | 1.5.8 | NEW | dest = -dest |
| NEGS | S | - | 1210 | 1.5.10 | NEW | push(-pop()) |

#### Operation 6: ABS (Absolute Value) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| ABSR | R | - | 1240 | 1.6.8 | NEW | dest = |dest| |
| ABSS | S | - | 1242 | 1.6.10 | NEW | push(|pop()|) |

#### Operation 7: INC (Increment) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| INCR | R | - | 1272 | 1.7.8 | NEW | dest = dest + 1 |
| INCS | S | - | 1274 | 1.7.10 | NEW | push(pop() + 1) |

#### Operation 8: DEC (Decrement) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| DECR | R | - | 1304 | 1.8.8 | NEW | dest = dest - 1 |
| DECS | S | - | 1306 | 1.8.10 | NEW | push(pop() - 1) |

#### Operation 9: MIN (Minimum) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| MINR | R,R | - | 1328 | 1.9.16 | NEW | dest = min(dest, src) |
| MINI | R,I | - | 1329 | 1.9.17 | NEW | dest = min(dest, immediate) |
| MINS | S,S | - | 1333 | 1.9.21 | NEW | push(min(pop(), pop())) |

#### Operation 10: MAX (Maximum) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| MAXR | R,R | - | 1360 | 1.10.16 | NEW | dest = max(dest, src) |
| MAXI | R,I | - | 1361 | 1.10.17 | NEW | dest = max(dest, immediate) |
| MAXS | S,S | - | 1365 | 1.10.21 | NEW | push(max(pop(), pop())) |

#### Operation 11: SGN (Sign) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SGNR | R | - | 1400 | 1.11.8 | NEW | dest = sign(dest) → -1, 0, or +1 |
| SGNS | S | - | 1402 | 1.11.10 | NEW | push(sign(pop())) |

#### Operation 12: DOT (Dot Product)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| DOTR | R,R,R | 108 | 1436 | 1.12.28 | EXISTING | dest = vec1 · vec2 (dot product) |
| DOTS | S,S | 110 | 1429 | 1.12.21 | EXISTING | push(pop() · pop()) |

#### Operation 13: CRS (Cross Product)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| CRSR | R,R,R | 109 | 1468 | 1.13.28 | EXISTING | dest = vec1 × vec2 (2D: scalar, 3D: vector) |
| CRSS | S,S | 111 | 1461 | 1.13.21 | EXISTING | push(pop() × pop()) |

---

### Family 2: Bitwise (2048-3071)

Base: `2 × 1024 = 2048`

#### Operation 0: AND (Bitwise AND)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| ANDR | R,R | 46 | 2064 | 2.0.16 | EXISTING | dest = dest & src |
| ANDI | R,I | 47 | 2065 | 2.0.17 | EXISTING | dest = dest & immediate |
| ANDS | S,S | 75 | 2069 | 2.0.21 | EXISTING | push(pop() & pop()) |

#### Operation 1: OR (Bitwise OR)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| ORR | R,R | 48 | 2096 | 2.1.16 | EXISTING | dest = dest \| src |
| ORI | R,I | 49 | 2097 | 2.1.17 | EXISTING | dest = dest \| immediate |
| ORS | S,S | 76 | 2101 | 2.1.21 | EXISTING | push(pop() \| pop()) |

#### Operation 2: XOR (Bitwise XOR)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| XORR | R,R | 50 | 2128 | 2.2.16 | EXISTING | dest = dest ^ src |
| XORI | R,I | 51 | 2129 | 2.2.17 | EXISTING | dest = dest ^ immediate |
| XORS | S,S | 77 | 2133 | 2.2.21 | EXISTING | push(pop() ^ pop()) |

#### Operation 3: NAD (Bitwise NAND)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| NADR | R,R | 5 | 2160 | 2.3.16 | EXISTING | dest = ~(dest & src) |
| NADI | R,I | 32 | 2161 | 2.3.17 | EXISTING | dest = ~(dest & immediate) |
| NADS | S,S | 78 | 2165 | 2.3.21 | EXISTING | push(~(pop() & pop())) |

#### Operation 4: NOR (Bitwise NOR) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| NORR | R,R | - | 2192 | 2.4.16 | NEW | dest = ~(dest \| src) |
| NORI | R,I | - | 2193 | 2.4.17 | NEW | dest = ~(dest \| immediate) |
| NORS | S,S | - | 2197 | 2.4.21 | NEW | push(~(pop() \| pop())) |

#### Operation 5: EQU (Bitwise XNOR / Equivalence) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| EQUR | R,R | - | 2224 | 2.5.16 | NEW | dest = ~(dest ^ src) |
| EQUI | R,I | - | 2225 | 2.5.17 | NEW | dest = ~(dest ^ immediate) |
| EQUS | S,S | - | 2229 | 2.5.21 | NEW | push(~(pop() ^ pop())) |

#### Operation 6: ADN (AND-NOT: A AND ~B) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| ADNR | R,R | - | 2256 | 2.6.16 | NEW | dest = dest & ~src |
| ADNI | R,I | - | 2257 | 2.6.17 | NEW | dest = dest & ~immediate |
| ADNS | S,S | - | 2261 | 2.6.21 | NEW | push(pop() & ~pop()) |

#### Operation 7: ORN (OR-NOT: A OR ~B) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| ORNR | R,R | - | 2288 | 2.7.16 | NEW | dest = dest \| ~src |
| ORNI | R,I | - | 2289 | 2.7.17 | NEW | dest = dest \| ~immediate |
| ORNS | S,S | - | 2293 | 2.7.21 | NEW | push(pop() \| ~pop()) |

#### Operation 8: NOT (Bitwise NOT)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| NOT | R | 52 | 2312 | 2.8.8 | EXISTING | dest = ~dest |
| NOTS | S | 79 | 2314 | 2.8.10 | EXISTING | push(~pop()) |

#### Operation 9: SHL (Shift Left)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SHLR | R,R | 103 | 2336 | 2.9.16 | EXISTING | dest = dest << src |
| SHLI | R,I | 53 | 2337 | 2.9.17 | EXISTING | dest = dest << immediate |
| SHLS | S,S | 80 | 2341 | 2.9.21 | EXISTING | push(pop() << pop()) |

#### Operation 10: SHR (Shift Right)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SHRR | R,R | 104 | 2368 | 2.10.16 | EXISTING | dest = dest >> src |
| SHRI | R,I | 54 | 2369 | 2.10.17 | EXISTING | dest = dest >> immediate |
| SHRS | S,S | 81 | 2373 | 2.10.21 | EXISTING | push(pop() >> pop()) |

#### Operation 11: ROT (Bitwise Rotate)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| ROTR | R,R | 135 | 2400 | 2.11.16 | EXISTING | dest = rotate(dest, src) |
| ROTI | R,I | 136 | 2401 | 2.11.17 | EXISTING | dest = rotate(dest, immediate) |
| ROTS | S,S | 137 | 2405 | 2.11.21 | EXISTING | push(rotate(pop(), pop())) |

#### Operation 12: PCN (Population Count)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| PCNR | R,R | 138 | 2432 | 2.12.16 | EXISTING | dest = popcount(src) |
| PCNS | S | 139 | 2442 | 2.12.10 | EXISTING | push(popcount(pop())) |

#### Operation 13: BSN (Bit Scan N-th)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| BSNR | R,R,R | 140 | 2492 | 2.13.28 | EXISTING | dest = mask of n-th set bit in src |
| BSNI | R,R,I | 141 | 2493 | 2.13.29 | EXISTING | dest = mask of n-th set bit (n=immediate) |
| BSNS | S,S | 142 | 2485 | 2.13.21 | EXISTING | push(mask of n-th set bit) |

---

### Family 3: Data & Stack (3072-4095)

Base: `3 × 1024 = 3072`

#### Operation 0: SET (Set Register)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SETR | R,R | 2 | 3088 | 3.0.16 | EXISTING | dest = src (copy register) |
| SETI | R,I | 1 | 3089 | 3.0.17 | EXISTING | dest = immediate |
| SETS | R,S | - | 3090 | 3.0.18 | NEW-VAR | dest = pop() |
| SETV | R,V | 3 | 3091 | 3.0.19 | EXISTING | dest = vector literal |

#### Operation 1: PUSH (Push to Stack)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| PUSH | R | 22 | 3128 | 3.1.8 | EXISTING | push(register) |
| PUSI | I | 58 | 3129 | 3.1.9 | EXISTING | push(immediate) |
| PUSV | V | 178 | 3131 | 3.1.11 | EXISTING | push(vector) |

#### Operation 2: POP (Pop from Stack)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| POP | R | 23 | 3160 | 3.2.8 | EXISTING | register = pop() |

#### Operation 3: DUP (Duplicate Stack Top)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| DUP | - | 60 | 3192 | 3.3.0 | EXISTING | push(peek()) — duplicate top |

#### Operation 4: SWAP (Swap Stack Top Two)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SWAP | - | 61 | 3224 | 3.4.0 | EXISTING | swap top two stack elements |

#### Operation 5: DROP (Drop Stack Top)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| DROP | - | 62 | 3256 | 3.5.0 | EXISTING | pop() and discard |

#### Operation 6: ROT (Rotate Stack Top Three)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SROT | - | 63 | 3288 | 3.6.0 | EXISTING | rotate top 3: [a,b,c] → [b,c,a] |

#### Operation 7: XCHG (Exchange Registers) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| XCHG | R,R | - | 3344 | 3.7.16 | NEW | swap contents of two registers |

---

### Family 4: Conditional (4096-5119)

Base: `4 × 1024 = 4096`

#### Operation 0: IF (Equal)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| IFR | R,R | 7 | 4112 | 4.0.16 | EXISTING | if (a == b) execute next |
| IFI | R,I | 24 | 4113 | 4.0.17 | EXISTING | if (reg == immediate) execute next |
| IFS | S,S | 85 | 4117 | 4.0.21 | EXISTING | if (pop() == pop()) execute next |

#### Operation 1: NE (Not Equal)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| INR | R,R | 163 | 4144 | 4.1.16 | EXISTING | if (a != b) execute next |
| INI | R,I | 170 | 4145 | 4.1.17 | EXISTING | if (reg != immediate) execute next |
| INS | S,S | 171 | 4149 | 4.1.21 | EXISTING | if (pop() != pop()) execute next |

#### Operation 2: LT (Less Than)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| LTR | R,R | 8 | 4176 | 4.2.16 | EXISTING | if (a < b) execute next |
| LTI | R,I | 25 | 4177 | 4.2.17 | EXISTING | if (reg < immediate) execute next |
| LTS | S,S | 87 | 4181 | 4.2.21 | EXISTING | if (pop() < pop()) execute next |

#### Operation 3: GT (Greater Than)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| GTR | R,R | 9 | 4208 | 4.3.16 | EXISTING | if (a > b) execute next |
| GTI | R,I | 26 | 4209 | 4.3.17 | EXISTING | if (reg > immediate) execute next |
| GTS | S,S | 86 | 4213 | 4.3.21 | EXISTING | if (pop() > pop()) execute next |

#### Operation 4: LE (Less or Equal)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| LETR | R,R | 165 | 4240 | 4.4.16 | EXISTING | if (a <= b) execute next |
| LETI | R,I | 168 | 4241 | 4.4.17 | EXISTING | if (reg <= immediate) execute next |
| LETS | S,S | 173 | 4245 | 4.4.21 | EXISTING | if (pop() <= pop()) execute next |

#### Operation 5: GE (Greater or Equal)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| GETR | R,R | 164 | 4272 | 4.5.16 | EXISTING | if (a >= b) execute next |
| GETI | R,I | 167 | 4273 | 4.5.17 | EXISTING | if (reg >= immediate) execute next |
| GETS | S,S | 172 | 4277 | 4.5.21 | EXISTING | if (pop() >= pop()) execute next |

#### Operation 6: IFT (If True / Non-Zero)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| IFTR | R,R | 33 | 4304 | 4.6.16 | EXISTING | if (a != 0) execute next (ignores b) |
| IFTI | R,I | 29 | 4305 | 4.6.17 | EXISTING | if (reg != 0) execute next |
| IFTS | S,S | 88 | 4309 | 4.6.21 | EXISTING | if (pop() != 0) execute next |

#### Operation 7: INT (If Not True / Zero)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| INTR | R,R | 166 | 4336 | 4.7.16 | EXISTING | if (a == 0) execute next |
| INTI | R,I | 169 | 4337 | 4.7.17 | EXISTING | if (reg == 0) execute next |
| INTS | S,S | 174 | 4341 | 4.7.21 | EXISTING | if (pop() == 0) execute next |

#### Operation 8: IFM (If Molecule Match)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| IFMR | R | 93 | 4360 | 4.8.8 | EXISTING | if molecule at DP+direction matches register |
| IFMI | V | 94 | 4363 | 4.8.11 | EXISTING | if molecule at DP+vector matches |
| IFMS | S | 95 | 4362 | 4.8.10 | EXISTING | if molecule at DP+pop() matches |

#### Operation 9: INM (If Not Molecule Match)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| INMR | R | 175 | 4392 | 4.9.8 | EXISTING | if molecule does NOT match |
| INMI | V | 176 | 4395 | 4.9.11 | EXISTING | if molecule at vector does NOT match |
| INMS | S | 177 | 4394 | 4.9.10 | EXISTING | if molecule from stack does NOT match |

#### Operation 10: IFP (If Passable)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| IFPR | R | 182 | 4424 | 4.10.8 | EXISTING | if cell at direction is passable |
| IFPI | V | 183 | 4427 | 4.10.11 | EXISTING | if cell at vector is passable |
| IFPS | S | 184 | 4426 | 4.10.10 | EXISTING | if cell from stack is passable |

#### Operation 11: INP (If Not Passable)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| INPR | R | 185 | 4456 | 4.11.8 | EXISTING | if cell is NOT passable |
| INPI | V | 186 | 4459 | 4.11.11 | EXISTING | if cell at vector is NOT passable |
| INPS | S | 187 | 4458 | 4.11.10 | EXISTING | if cell from stack is NOT passable |

#### Operation 12: IFF (If Foreign Owned)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| IFFR | R | 200 | 4488 | 4.12.8 | EXISTING | if cell is owned by another organism |
| IFFI | V | 201 | 4491 | 4.12.11 | EXISTING | if cell at vector is foreign |
| IFFS | S | 202 | 4490 | 4.12.10 | EXISTING | if cell from stack is foreign |

#### Operation 13: INF (If Not Foreign)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| INFR | R | 203 | 4520 | 4.13.8 | EXISTING | if cell is NOT foreign (own or unowned) |
| INFI | V | 204 | 4523 | 4.13.11 | EXISTING | if cell at vector is not foreign |
| INFS | S | 205 | 4522 | 4.13.10 | EXISTING | if cell from stack is not foreign |

#### Operation 14: IFV (If Vacant / Unowned)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| IFVR | R | 206 | 4552 | 4.14.8 | EXISTING | if cell is unowned (vacant) |
| IFVI | V | 207 | 4555 | 4.14.11 | EXISTING | if cell at vector is vacant |
| IFVS | S | 208 | 4554 | 4.14.10 | EXISTING | if cell from stack is vacant |

#### Operation 15: INV (If Not Vacant / Owned)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| INVR | R | 209 | 4584 | 4.15.8 | EXISTING | if cell is owned (not vacant) |
| INVI | V | 210 | 4587 | 4.15.11 | EXISTING | if cell at vector is owned |
| INVS | S | 211 | 4586 | 4.15.10 | EXISTING | if cell from stack is owned |

---

### Family 5: Control Flow (5120-6143)

Base: `5 × 1024 = 5120`

#### Operation 0: JMP (Jump)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| JMPR | R | 10 | 5128 | 5.0.8 | EXISTING | jump to address in register |
| JMPI | L | 20 | 5132 | 5.0.12 | EXISTING | jump to label |
| JMPS | S | 89 | 5130 | 5.0.10 | EXISTING | jump to address from stack |
| JMPV | V | - | 5131 | 5.0.11 | NEW-VAR | jump to vector coordinate |

#### Operation 1: CALL (Call Procedure)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| CALL | L | 34 | 5164 | 5.1.12 | EXISTING | call procedure at label |
| CALR | R | - | 5160 | 5.1.8 | NEW-VAR | call procedure at register address |
| CALS | S | - | 5162 | 5.1.10 | NEW-VAR | call procedure at stack address |

#### Operation 2: RET (Return)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| RET | - | 35 | 5184 | 5.2.0 | EXISTING | return from procedure |

#### Operation 3: SKIP (Skip Instructions) — NEW

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SKPR | R | - | 5224 | 5.3.8 | NEW | skip N instructions (N from register) |
| SKPI | I | - | 5225 | 5.3.9 | NEW | skip N instructions (N = immediate) |
| SKPS | S | - | 5226 | 5.3.10 | NEW | skip N instructions (N from stack) |

---

### Family 6: Environment (6144-7167)

Base: `6 × 1024 = 6144`

#### Operation 0: PEEK (Read from Environment)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| PEEK | R,R | 14 | 6160 | 6.0.16 | EXISTING | dest = molecule at DP+direction |
| PEKI | R,V | 56 | 6163 | 6.0.19 | EXISTING | dest = molecule at DP+vector |
| PEKS | S | 90 | 6154 | 6.0.10 | EXISTING | push(molecule at DP+pop()) |

#### Operation 1: POKE (Write to Environment)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| POKE | R,R | 15 | 6192 | 6.1.16 | EXISTING | write molecule to DP+direction |
| POKI | R,V | 57 | 6195 | 6.1.19 | EXISTING | write molecule to DP+vector |
| POKS | S,S | 91 | 6197 | 6.1.21 | EXISTING | write pop() to DP+pop() |

#### Operation 2: PPK (Peek+Poke Atomic Swap)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| PPKR | R,R | 179 | 6224 | 6.2.16 | EXISTING | atomic read+write at DP+direction |
| PPKI | R,V | 180 | 6227 | 6.2.19 | EXISTING | atomic read+write at DP+vector |
| PPKS | S,S | 181 | 6229 | 6.2.21 | EXISTING | atomic read+write from stack |

---

### Family 7: State (7168-8191)

Base: `7 × 1024 = 7168`

#### Operation 0: SCAN (Scan Environment)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SCAN | R,R | 16 | 7184 | 7.0.16 | EXISTING | dest = molecule type at DP+direction |
| SCNI | R,V | 82 | 7187 | 7.0.19 | EXISTING | dest = molecule type at DP+vector |
| SCNS | S | 83 | 7178 | 7.0.10 | EXISTING | push(molecule type at DP+pop()) |

#### Operation 1: SEEK (Move Data Pointer)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SEEK | R | 12 | 7208 | 7.1.8 | EXISTING | DP += direction from register |
| SEKI | V | 59 | 7211 | 7.1.11 | EXISTING | DP += vector |
| SEKS | S | 84 | 7210 | 7.1.10 | EXISTING | DP += pop() |

#### Operation 2: TURN (Change Direction)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| TURN | R | 11 | 7240 | 7.2.8 | EXISTING | DV = direction from register |
| TRNI | V | 96 | 7243 | 7.2.11 | EXISTING | DV = vector |
| TRNS | S | 97 | 7242 | 7.2.10 | EXISTING | DV = pop() |

#### Operation 3: SYNC (Synchronize)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SYNC | - | 13 | 7264 | 7.3.0 | EXISTING | synchronize DP with IP |

#### Operation 4: NRG (Get Energy)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| NRG | R | 17 | 7304 | 7.4.8 | EXISTING | dest = current energy |
| NRGS | - | 92 | 7296 | 7.4.0 | EXISTING | push(current energy) |

#### Operation 5: NTR (Get Entropy)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| NTR | R | 212 | 7336 | 7.5.8 | EXISTING | dest = current entropy |
| NTRS | - | 213 | 7328 | 7.5.0 | EXISTING | push(current entropy) |

#### Operation 6: DIFF (Get Difference Vector)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| DIFF | R | 19 | 7368 | 7.6.8 | EXISTING | dest = IP - DP (difference vector) |
| DIFS | - | 99 | 7360 | 7.6.0 | EXISTING | push(IP - DP) |

#### Operation 7: POS (Get Initial Position)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| POS | R | 21 | 7400 | 7.7.8 | EXISTING | dest = initial spawn position |
| POSS | - | 98 | 7392 | 7.7.0 | EXISTING | push(initial position) |

#### Operation 8: RAND (Random Number)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| RAND | R | 55 | 7432 | 7.8.8 | EXISTING | dest = random(0, dest) |
| RNDS | S | 105 | 7434 | 7.8.10 | EXISTING | push(random(0, pop())) |

#### Operation 9: FORK (Create Child Organism)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| FORK | R,R,R | 18 | 7492 | 7.9.28 | EXISTING | fork(delta, energy, childDV) |
| FRKI | V,I,V | 106 | 7486 | 7.9.special | EXISTING | fork with immediates |
| FRKS | S,S,S | 107 | 7495 | 7.9.31 | EXISTING | fork with stack values |

#### Operation 10: ADP (Set Active Data Pointer)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| ADPR | R | 100 | 7528 | 7.10.8 | EXISTING | set active DP index from register |
| ADPI | I | 101 | 7529 | 7.10.9 | EXISTING | set active DP index to immediate |
| ADPS | S | 102 | 7530 | 7.10.10 | EXISTING | set active DP index from stack |

#### Operation 11: SPN (Scan Passable Neighbors)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SPNR | R | 152 | 7560 | 7.11.8 | EXISTING | dest = bitmask of passable neighbors |
| SPNS | - | 153 | 7552 | 7.11.0 | EXISTING | push(bitmask of passable neighbors) |

#### Operation 12: SNT (Scan Neighbors by Type)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SNTR | R,R | 154 | 7600 | 7.12.16 | EXISTING | dest = bitmask of neighbors matching type |
| SNTI | R,I | 155 | 7601 | 7.12.17 | EXISTING | dest = bitmask matching immediate type |
| SNTS | S | 156 | 7594 | 7.12.10 | EXISTING | push(bitmask matching pop() type) |

#### Operation 13: RBI (Random Bit from Mask)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| RBIR | R,R | 149 | 7632 | 7.13.16 | EXISTING | dest = random set bit from mask |
| RBII | R,I | 150 | 7633 | 7.13.17 | EXISTING | dest = random set bit from immediate mask |
| RBIS | S | 151 | 7626 | 7.13.10 | EXISTING | push(random set bit from pop()) |

#### Operation 14: GDV (Get Direction Vector)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| GDVR | R | 188 | 7664 | 7.14.8 | EXISTING | dest = current DV |
| GDVS | - | 189 | 7656 | 7.14.0 | EXISTING | push(current DV) |

#### Operation 15: SMR (Set Molecule Marker)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SMR | R | 192 | 7696 | 7.15.8 | EXISTING | set marker register from register |
| SMRI | I | 193 | 7697 | 7.15.9 | EXISTING | set marker register to immediate |
| SMRS | S | 194 | 7698 | 7.15.10 | EXISTING | set marker register from stack |

---

### Family 8: Location (8192-9215)

Base: `8 × 1024 = 8192`

#### Operation 0: DPL (Data Pointer to Location Stack)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| DPLS | - | 116 | 8192 | 8.0.0 | EXISTING | push DP to location stack |
| DPLR | L | 118 | 8204 | 8.0.12 | EXISTING | LR = DP (copy to location register) |

#### Operation 1: SKL (Location Stack to Data Pointer)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SKLS | - | 117 | 8224 | 8.1.0 | EXISTING | DP = pop from location stack |
| SKLR | L | 120 | 8236 | 8.1.12 | EXISTING | DP = LR (copy from location register) |

#### Operation 2: LRD (Location Register to Data Register)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| LRDS | L | 123 | 8268 | 8.2.12 | EXISTING | push LR to data stack |
| LRDR | R,L | 124 | 8276 | 8.2.20 | EXISTING | DR = LR (copy to data register) |

#### Operation 3: LSD (Data Stack/Register to Location)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| LSDS | - | 122 | 8288 | 8.3.0 | EXISTING | push data stack top to location stack |
| LSDR | R | 126 | 8296 | 8.3.8 | EXISTING | push data register to location stack |

#### Operation 4: PUSL (Push Location Register)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| PUSL | L | 121 | 8332 | 8.4.12 | EXISTING | push LR to location stack |

#### Operation 5: POPL (Pop to Location Register)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| POPL | L | 125 | 8364 | 8.5.12 | EXISTING | LR = pop from location stack |

#### Operation 6: DUPL (Duplicate Location Stack Top)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| DUPL | - | 112 | 8384 | 8.6.0 | EXISTING | duplicate top of location stack |

#### Operation 7: SWPL (Swap Location Stack Top)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| SWPL | - | 113 | 8416 | 8.7.0 | EXISTING | swap top two location stack entries |

#### Operation 8: DRPL (Drop Location Stack Top)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| DRPL | - | 114 | 8448 | 8.8.0 | EXISTING | pop and discard from location stack |

#### Operation 9: ROTL (Rotate Location Stack)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| ROTL | - | 115 | 8480 | 8.9.0 | EXISTING | rotate top 3 location stack entries |

#### Operation 10: CRL (Clear Location Register)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| CRLR | L | 191 | 8524 | 8.10.12 | EXISTING | clear location register |

#### Operation 11: LRL (Copy Location Register)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| LRLR | L,L | 190 | 8567 | 8.11.23 | EXISTING | copy one LR to another |

---

### Family 9: Vector (9216-10239)

Base: `9 × 1024 = 9216`

#### Operation 0: VGT (Vector Get Element)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| VGTR | R,R,R | 127 | 9244 | 9.0.28 | EXISTING | dest = vector[index] |
| VGTI | R,R,I | 128 | 9245 | 9.0.29 | EXISTING | dest = vector[immediate] |
| VGTS | S,S | 129 | 9237 | 9.0.21 | EXISTING | push(pop()[pop()]) |

#### Operation 1: VST (Vector Set Element)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| VSTR | R,R,R | 130 | 9276 | 9.1.28 | EXISTING | vector[index] = value |
| VSTI | R,I,I | 131 | 9278 | 9.1.30 | EXISTING | vector[immediate] = immediate |
| VSTS | S,S,S | 132 | 9279 | 9.1.31 | EXISTING | pop()[pop()] = pop() |

#### Operation 2: VBL (Vector Build)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| VBLD | R | 133 | 9304 | 9.2.8 | EXISTING | build vector from N stack values into register |
| VBLS | - | 134 | 9296 | 9.2.0 | EXISTING | build vector from N stack values, push result |

#### Operation 3: B2V (Bit Mask to Vector)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| B2VR | R,R | 146 | 9328 | 9.3.16 | EXISTING | dest = unit vector from bitmask |
| B2VI | R,I | 147 | 9329 | 9.3.17 | EXISTING | dest = unit vector from immediate mask |
| B2VS | S | 148 | 9322 | 9.3.10 | EXISTING | push(unit vector from pop()) |

#### Operation 4: V2B (Vector to Bit Mask)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| V2BR | R,R | 157 | 9360 | 9.4.16 | EXISTING | dest = bitmask from vector |
| V2BI | R,V | 158 | 9363 | 9.4.19 | EXISTING | dest = bitmask from vector literal |
| V2BS | S | 159 | 9354 | 9.4.10 | EXISTING | push(bitmask from pop()) |

#### Operation 5: RTR (Rotate Vector)

| Name | Variant | Old ID | New ID | Pattern | Status | Description |
|------|---------|--------|--------|---------|--------|-------------|
| RTRR | R,R,R | 160 | 9404 | 9.5.28 | EXISTING | rotate vector 90° in plane of axes |
| RTRI | R,I,I | 161 | 9406 | 9.5.30 | EXISTING | rotate with immediate axes |
| RTRS | S,S,S | 162 | 9407 | 9.5.31 | EXISTING | rotate with stack axes |

---

## Summary Statistics

| Category | Count |
|----------|-------|
| **Total Existing Instructions** | 193 |
| **New Instructions** | 28 |
| **New Variants of Existing** | 6 |
| **Total After Extension** | ~227 |
| **Families Used** | 10 of 16 |
| **Operations per Family (avg)** | ~16 |
| **Max Opcode ID** | ~9407 |
| **Available Space** | 16383 - 9407 = 6976 slots |

## Migration Notes

1. All existing instruction names are preserved
2. Only the numeric IDs change
3. The compiler uses instruction names, not IDs, so source code is unaffected
4. A mapping table (old ID → new ID) should be generated for any tooling that uses raw IDs
5. The `Instruction.java` `init()` method needs to be updated with new IDs
