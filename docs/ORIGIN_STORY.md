# The Origin Story of Evochora

This is the personal account of how Evochora came to be. It is not a scientific paper
or a feature overview — for those, see the [Scientific Overview](SCIENTIFIC_OVERVIEW.md)
and the [README](../README.md). This is the story of a naive idea, three decades of
procrastination, and the humbling experience of independently rediscovering why building
an artificial life platform is so much harder than it looks.

## The Idea (1990s)

I studied computer science in the 1990s. Somewhere during that time, I stumbled
across Tom Ray's Tierra — a system where self-replicating programs competed for CPU
cycles and memory in a digital "soup." Parasites emerged. Hyper-parasites followed.
Evolution, happening right there on a computer. I was fascinated.

I was also, with the confidence only a student can muster, convinced I knew how to do
it better. Tierra's instruction set was too specific, I thought. Too hand-crafted. If
you really wanted open-ended evolution, you needed to strip everything down to the
absolute minimum: a Universal Turing Machine. No special instructions, no shortcuts —
just the bare computational substrate, and let evolution figure out the rest.

I never built it. Life happened — the tech industry in the late 90s didn't care
whether you had a degree, and neither did I. I left university, moved into the
software industry, spent three decades in tech leadership positions. But the idea
never went away. It just sat there, quietly, getting more romantic with every year I
didn't have to confront it with reality.

## The Beginning (2025)

Fast forward to the summer of 2025. AI coding assistants had arrived, and I was
curious — could someone who hadn't written a line of code in twenty years actually
build something with their help? My career had taken me from engineering into leading
both engineering teams and product organizations — always close to technology, but
firmly on the "what should we build and why" side of the fence. I needed a project to
test whether AI could bridge the gap back to the "how," and that old idea was still
sitting there, waiting.

So I started. Python, because that's what everyone seemed to use. A Universal Turing
Machine, because I still believed in the purity of the concept. I didn't even know
that an entire field had continued working on digital evolution while I was in
industry. I had never heard of Avida, Lenia, or any of the platforms that came after
Tierra. I was starting from scratch, blissfully unaware that the research community
had spent three decades discovering exactly why my "simple" idea wasn't simple at all.

## Hitting the Wall

The UTM idea lasted about a week. Writing a self-replicating program on a bare Turing
Machine turned out to be — how to put this diplomatically — absolutely brutal. The
number of states required to implement even basic copying logic was enormous, and the
idea of evolution ever optimizing something that complex felt less like science and
more like wishful thinking.

So I compromised. A little. Instead of a UTM, I designed a small custom instruction
set. Just the basics — move, copy, compare, jump. That was manageable enough to
actually write an ancestor. But "manageable" is relative when you're writing
machine code by hand. I needed an assembler. So I wrote one.

Then Python started to hurt. The simulation was slow, the assembler was getting
complex, and refactoring dynamically typed code without breaking everything felt like
defusing a bomb while blindfolded. I switched to Java — not because I loved Java, but
because I needed a type system that would catch my mistakes before the simulation did.

The compiler, meanwhile, had developed a life of its own. Every time I wanted to add
a new instruction or a new directive to the assembly language, I found myself
rewriting half the compiler. It was a monolith, and monoliths don't like change. So I
broke it apart into a multi-pass pipeline with plugin registries — preprocessor,
parser, semantic analyzer, IR generator, layout engine, linker, emitter — each phase
producing an immutable artifact for the next. It felt like over-engineering at the
time. It turned out to be one of the best decisions I made.

## The Data Wall

The early versions had a JavaFX frontend bolted directly onto the simulation loop.
Every tick, the UI would repaint. It worked — barely — for a handful of test organisms
running simple assembly snippets. There was no ancestor yet, just handwritten
test code to validate the VM. But even at that small scale, rendering and data
processing were stealing all the CPU cycles.

I ripped out the frontend and replaced it with a web-based visualizer, decoupled from
the simulation via an asynchronous data pipeline. This was data pipeline version one.
It didn't survive long. The sheer volume of data a tick-by-tick simulation produces
caught me completely off guard — tens of gigabytes per hour, and that was a small
world. Version two introduced batching and better storage, but still couldn't keep up.
Version three — the current architecture — finally got it right: abstract resource
interfaces, competing consumers, and a clean separation between the hot path
(simulation) and the cold path (persistence, indexing, analysis). I also had to
introduce delta compression, because storing the complete state of every cell at every
tick was simply not feasible.

Three rewrites of the data pipeline. Each one felt like starting over. Each one taught
me something I couldn't have learned from a textbook — or more honestly, something I
would have learned if I'd read the right textbook, but where's the fun in that?

## The Biological Wall

Somewhere between data pipeline version one and version three — I honestly don't
remember the exact sequence anymore — I finally had enough infrastructure to attempt
what this whole project was supposed to be about: a self-replicating organism.

The first ancestor was a mess. It could copy itself, barely, but its offspring
immediately started corrupting each other. Organisms would blindly write over their
neighbors' code during replication, turning functioning programs into random noise.
The neighbors, being copies of the same blind replicator, did the same thing back.
Within a few dozen replications, the entire population had devolved into a churning
soup of broken code — a phenomenon I later learned the field calls "error catastrophe."
I had independently rediscovered a problem Manfred Eigen described in 1971.

The brute-force fix would have been to penalize invalid instructions — make it
expensive to execute garbage code, so corrupted organisms die quickly. I tried it. It
stabilized the population, but something felt wrong. If every non-functional
instruction is punished, there's no room for "junk code" — and junk code is where
neutral mutations accumulate, the raw material for evolutionary innovation. I was
imposing a selection pressure that might prevent exactly the thing I wanted to observe.

So instead, I introduced thermodynamics. Every action could now cost energy, produce
entropy, or both — all fully configurable. Organisms that wasted energy on destructive
behavior ran out of it faster. Organisms that generated too much entropy died from
that. The physics of the world, not an artificial penalty, became the stabilizing
force. For the first time, I had a population that sustained itself.

Then came mutation. I knew from the thermodynamics experience that I couldn't just
flip random bits and hope for the best. The fitness landscape was full of cliffs —
a single misplaced instruction could turn a functional replicator into a destroyer.
I needed two things: mutations that were structurally meaningful (gene insertion,
deletion, substitution, duplication — not just random noise), and a genome architecture
that could tolerate them.

That second part led me to fuzzy jumps. In traditional architectures, jump
instructions target exact addresses. Move one instruction, and every jump that pointed
past it breaks. I found the solution in SignalGP's tag-based referencing and later
discovered that even Tierra had used template patterns for the same reason, and Avida
as well. Evochora's implementation resolves jump targets through label matching based
on Hamming distance — the genome becomes inherently resilient to insertions and
deletions. I also redesigned the ancestor from scratch: more redundancy,
more spacing between functional regions, multiple safety fallbacks. It was no longer
elegant. It was robust.

## The Analysis Wall

By early 2026 it worked. The simulation ran stably for hundreds of millions of ticks.
Hundreds of thousands of organisms were born, replicated, mutated, and died. The
population sustained itself — and it wasn't static. I could see dynamics: sometimes the
population crashed to a few hundred individuals, then recovered. I spotted organisms
producing strangely shaped copies of themselves — triangular replication patterns, for
instance — though none of these variants survived long enough to establish themselves.

And that was exactly the problem. The tools I had were already quite capable — I could
step through individual organisms tick by tick, watch population metrics over time, see
genome diversity shift. But finding the *interesting* events in hundreds of millions of
ticks of simulation data was searching for a needle in a haystack. I saw hints of
dynamics, but I couldn't rigorously answer the most basic evolutionary question: is
anything actually evolving, or is it just churning?

So the next wall wasn't more physics or more instructions. It was the ability to
*understand* what the simulation was producing — the tools evolutionary biologists have
used for decades to make sense of their data.

## Where Things Stand

Those tools exist now. Every genome is recorded with the genome it arose from, every
mutation with the cells it changed, and a notebook draws phylogenetic trees and Muller
plots from any run. With them, the needle turned up — twice.

In one run of two billion ticks, two variants of the same switch in the organisms' main
loop arose on their own: one closed the energy-triggered route into reproduction for
good, the other held it open for good. The one that reproduces more often lost. The
winner pays a small error penalty on every pass through its loop and took over the
population anyway — and none of it shows in any aggregate curve. It exists only at the
level of the genomes.

In another run, a population spent 166 million ticks dying on the entropy clock in a
world full of energy it never harvested. Then a single `NOT`, inserted into the padding of
the main loop, made the jump into reproduction unconditional. It swept through the
population in about ninety generations, the population grew eightfold and finally ate
the energy it had been sitting next to. The same switch, forced open this time — opposite
winners at one place in the genome, in two differently configured worlds.

Both findings are published as citable experiment records, with their data, on Zenodo
(see [Published Experiments](PUBLISHED_EXPERIMENTS.md)). They are small steps: selection
at work, measured, nothing more. But they answer the question I couldn't answer a few
months earlier. Something is evolving.

And the architecture still opens doors I find hard to stop thinking about. What if
organisms could run multiple VMs in parallel — dedicated threads for different
routines — and the coordination overhead became the evolutionary pressure toward
something like multicellularity? What if instead of a single energy molecule, there
were reaction chains — A + B → C + Energy + Entropy — and food webs emerged from digital
chemistry? What if the environment itself changed over time, with shifting resource
regions and occasional catastrophes forcing adaptation? These are not plans. They are
questions I'd love to explore — and I hope others will too. For a more rigorous
treatment of these research directions, see the
[Scientific Overview](SCIENTIFIC_OVERVIEW.md).

## What I Learned

Looking back, I realize that I spent months independently stumbling into problems the
Artificial Life community had been working on for three decades. Error catastrophe,
the need for neutral spaces in fitness landscapes, the data scaling challenges of
high-fidelity simulations, the tension between stability and evolvability. Every time
I hit a wall, I'd eventually find a paper from the 90s or 2000s describing exactly
the problem I was facing — often with a solution I could have just looked up.

But there's something valuable in not having looked it up first. Every architectural
decision in Evochora was born from a concrete, experienced failure, not from reading
a best-practices guide. The multi-pass compiler exists because the monolithic one
broke. The data pipeline is distributed because the monolithic one couldn't keep up.
Thermodynamics replaced penalties because penalties felt wrong. Fuzzy jumps exist
because exact addressing made genomes brittle. None of this was planned. All of it
was necessary.

The result is a platform that is, I think, genuinely solid — not because it was
designed by an expert, but because every piece of it was stress-tested by someone who
didn't know what they were doing until the thing broke and they had to figure it out.

A note on AI: I know how skeptically many people view AI-generated code today, and
rightfully so — it has flooded the world with superficially impressive but ultimately
hollow projects. But Evochora would not exist without AI coding assistants. Someone
twenty years out of practice does not rebuild a multi-pass compiler, a distributed
data pipeline, and a spatial VM from scratch in a few months on their own — let alone
rewrite major components multiple times when the design turned out to be wrong. AI
didn't make the architectural decisions or define the physics. But it gave me back the
ability to code, and with it, the joy of building something I'd been thinking about
for thirty years.

Evochora is open source, and I hope it becomes useful to others — researchers who
actually know the field, students who want to experiment, hobbyists who share the
same fascination with digital evolution that grabbed me in the 1990s. It's not a
finished product. It's a foundation, built the hard way, and an invitation to build
on it.
