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

## A self-damaging insertion changes the material of the child's shell, and the lineage sweeps the population

*Run `20260923-11351286`, 355 million ticks, published November 2026 —
[10.5281/zenodo.23843601](https://doi.org/10.5281/zenodo.23843601)*

Every organism in this run builds a closed shell of STRUCTURE cells around each child before
copying itself into it, and the shell is more than half of what a child costs. An inserted
instruction made one organism remove a cell of its own body during a harvest: the operand that
tells the shell-building loop which molecule to write. The runtime reads an empty operand as
register 0, which in this program holds the energy level, so the loop wrote the shell as DATA cells
at a twentieth of the price. Carriers had their first child earlier and more children per
reproducing organism, and the lineage replaced the population within 22 generations, where a
neutral lineage would have needed hundreds; consistent with selection on the cost of the shell, not
tested by intervention. The first published sweep that changed what an organism builds rather than
a setting of its main loop.

## A lineage that lost two corner labels builds its children far away, loosens the kin structure of the population, and spreads

*Run `20261001-02023062`, 1.065 billion ticks, published November 2026 —
[10.5281/zenodo.23897978](https://doi.org/10.5281/zenodo.23897978)*

Every organism in this run builds each child in the slot next to its own body, so families stand
in grids and harvest the energy around themselves. In one lineage three components came together,
none of which changes the placement by itself: a substitution that leaves the write pointer where
the last harvest ended, a duplicated corner block whose guard no longer gives up, and the loss of
the two labels that mark the southern corners of the body, which happened during a copy and was
inherited. Together they make the parent build its child where its harvest ended, hundreds of
cells away. Within 83 million ticks the lineage replaced the population, and its kin structure
loosened: where nearly every neighbour had been close kin, a third were. The lineage's newborns
are born where more free energy lies and starve less often, and a remotely placed child does
better than its adjacently placed sibling by about what the energy in its nearest surrounding
leads one to expect; consistent with selection on the energy the dispersal provides, not tested
by intervention. The first published sweep that changed where an organism places its offspring.
