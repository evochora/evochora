Hi all, I'm Rainer. As a student in the 90s I read about Tierra and was sure I'd build something like it one day. I finally did, and I'd love to show you (story below): Evochora, an open-source digital evolution research platform, MIT, on GitHub. Live demo, no install: https://evochora.org

An organism's code is spread over the cells of a shared n-dimensional grid; in that same grid it harvests energy, builds its offspring via pointers it moves around, and can be taken apart by its neighbours. There's no predefined fitness function. Actions cost energy and add entropy, and only writing into the world sheds it, so an organism that stops writing dies. Thermodynamics, energy sources and mutation operators are plugins. Organisms are hand-written in a spatial assembly language with a real compiler, runs are deterministic, and the data opens in the usual tools.

Two findings are on Zenodo: one switch in the reproduction logic fixed in opposite directions in two differently configured worlds, in one invisible in any population curve. Two more aren't fully analysed yet:
- In one run, organisms stopped building offspring next to themselves, as the ancestor does, and began building farther away; that variant took over.
- In another, organisms began writing into the empty cells around them, which appears to keep neighbours from building there; that spread too.

I'm not aware of Avida or Tierra showing this, but may be missing something. Data and config to rerun both: see links.

I don't come from academia and am not after a career in it; I've done nothing else for over a year, because I want to understand evolution. A paper up to this community's standards is still beyond me; for now there's a JOSS software submission. If this reminds you of anything, I'd be grateful for a pointer; if you'd like to run your own experiments, I'd be glad to help you get started; and if something in the physics looks wrong, please tell me.