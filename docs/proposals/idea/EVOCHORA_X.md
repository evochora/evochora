# Evochora-X — Continuous Substrate as a Separate Research Track

**Status: IDEA — not decided. Unlike the other documents in `ideas/`, this one proposes no change
to Evochora. It records a deliberate demarcation and the literature basis behind it, so the
question "why not simply make the language continuous?" is answered once instead of resurfacing
in every design discussion.**

## Background

The motivating observation: assembler-class genomes produce rugged fitness landscapes. A
literature review (2026-08) examined whether the organism logic could be made continuous instead
of discrete. The findings below are the condensed result; the practical consequences for Evochora
itself live in the other idea documents (graded chemistry, continuous regulation, dumb-variation
package).

## Finding 1 — The naive path is refuted

Relaxing programs into continuous form and optimizing by gradient descent has been studied
systematically. TerpreT (Gaunt et al., 2016) compared gradient-based program search against
discrete search: constraint solvers dominate gradient descent and LP relaxations decisively. The
differentiable Forth interpreter (Bošnjak et al., 2017) works only for small program sketches.
The relaxed landscape is formally smooth but full of poor local minima, and the discretization
gap remains unsolved. This path should not be re-entered.

## Finding 2 — Continuous AND universal is theoretically possible

Shannon's General Purpose Analog Computer — polynomial ODE systems — is equivalent to computable
analysis (Graça & Costa; Bournez et al.): Turing universality with fully continuous dynamics.
The practical counterpart in A-life is CTRNNs (continuous-time recurrent neural networks),
universal approximators of dynamical systems with decades of successful neuroevolution practice
(Beer's minimally cognitive agents). The decisive property: behavior depends *continuously* on
the coefficients — locality as a mathematical guarantee rather than a design goal, which is
exactly what assembler cannot provide (a changed jump reroutes control flow entirely).

## Finding 3 — The honest costs (why this is not Evochora 2.0)

1. **Universality requires unbounded precision.** In finite floating-point arithmetic a CTRNN is
   not Turing-complete; a discrete VM with growable storage is. A real, non-negotiable trade-off.
2. **Programmability is gone.** A primordial would be a weight vector — no `main.evo`, no
   compiler, no source-level debugging; the entire compiler/visualizer toolchain becomes moot.
3. **Runtime cost.** The Evochora runtime is consistently integer-hotpath (flat arrays, permille
   integer arithmetic; no float/double anywhere in the ISA). Real-valued fields would be orders
   of magnitude more expensive.
4. **Empirical state.** Flow-Lenia (Plantec et al.) shows open-ended dynamics but no demonstrated
   complexity growth; neural-CA replicators are fragile and hyperparameter-sensitive. No one has
   shown a continuous substrate producing anything program-like.

## Finding 4 — Strand displacement as the substrate candidate

If a continuous-leaning substrate were ever built, DNA-strand-displacement chemistry is the
strongest candidate: programmable logic from pure domain complementarity (Qian & Winfree —
digital circuits and neural networks built from seesaw gates; compilers exist, e.g. Microsoft's
DSD language), computationally cheap (no folding problem — domain matching, closely related to
Evochora's existing Hamming fuzzy-matching machinery), with graded binding affinities and
mass-action kinetics providing intrinsic continuity. What is validated is the *engineering* side;
*evolvability* and leakage resistance under mutation are open — that would be the research
question of this track.

## The demarcation

- **Evochora 2.0** = layering on the discrete substrate: graded chemistry, continuous regulation,
  encoding robustness (see the other idea documents). The discrete, programmable core remains.
- **Evochora-X** = a separate project with its own runtime, if anyone ever walks the continuous
  path: CTRNN/ODE organisms or a strand-displacement chemistry in a Flow-Lenia-class world.
  Not a successor, no shared codebase.

## Key literature

- Gaunt et al. (2016). *TerpreT: A Probabilistic Programming Language for Program Induction.*
  https://arxiv.org/abs/1608.04428
- Bošnjak et al. (2017). *Programming with a Differentiable Forth Interpreter.*
  https://arxiv.org/abs/1605.06640
- Bournez, Graça, Pouly (2017). *Polynomial ODEs and computable analysis* (GPAC universality).
- Beer, R. D. (1996+). Minimally cognitive agents / CTRNN evolution.
- Plantec et al. (2023). *Flow-Lenia: Towards open-ended evolution in cellular automata.*
  https://arxiv.org/abs/2212.07906
- Qian & Winfree (2011). *Scaling Up Digital Circuit Computation with DNA Strand Displacement
  Cascades.* Science 332. / Qian, Winfree, Bruck (2011). *Neural network computation with DNA
  strand displacement cascades.* Nature 475.
- Lakin et al. (2011). *A programming language for composable DNA circuits* (DSD).
- Mordvintsev et al. (2020). *Growing Neural Cellular Automata.* https://distill.pub/2020/growing-ca/
