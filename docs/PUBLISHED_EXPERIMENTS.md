# Published Experiments

Simulation runs that produced a scientific finding are published as self-contained, citable
experiment packages on Zenodo. Each record's analysis notebook documents the finding, and its
package serves the run's data for independent inspection. All records are collected in the
[Evochora community on Zenodo](https://zenodo.org/communities/evochora).

## A completed selective sweep: the restrictive setting of a reproduction switch displaces the permissive one

*Run `20260226-03114337`, 2.017 billion ticks, published August 2026 —
[10.5281/zenodo.22081452](https://doi.org/10.5281/zenodo.22081452)*

Two mutually exclusive one-edit variants of the same regulatory switch arose spontaneously in the
organisms' main loop: variant A closes the energy-triggered route into reproduction permanently,
variant B holds it permanently open. Variant A displaced variant B — absent at tick 5 M, above
99.8 % from tick ≈620 M to the end of the run, a logistic rise consistent with strong selection
(s ≈ 0.11 per generation). The "reproduce more often" variant lost; the winner pays a recurring
error penalty on every loop pass and fixed anyway; and the sweep is invisible in every aggregate
curve — it exists only at the genome level.

## A single inserted instruction disarms the entropy brake on reproduction and shifts a population from an entropy-limited to an energy-limited regime

*Run `20260402-11564129`, 271 million ticks, published August 2026 —
[10.5281/zenodo.22155607](https://doi.org/10.5281/zenodo.22155607)*

For 166 million ticks the population oscillated between 80 and 200 organisms in a world full of
unharvested energy: it was dying on the entropy clock. A single `NOT` inserted into the main loop's
NOP padding made the jump into reproduction unconditional, turned row writing into a permanent
entropy sink, and swept to fixation in roughly 90 generations — after which the population grew
eightfold, filled the world and consumed the free energy. The same reproduction switch that the
record above saw closed under rich energy input was here forced open under scarce input: opposite
winners at one locus, in opposite configurations.
