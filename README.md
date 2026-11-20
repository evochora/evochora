<div align="center">

  <img width="465" height="74" alt="Evochora logo" src="https://github.com/user-attachments/assets/4724aed1-a596-4e03-9c3c-41f791b0babd" />
  <br><code>Simulation Platform for Digital Evolution Research</code>

  <br><br>

  <a href="https://opensource.org/licenses/MIT">
    <img src="https://img.shields.io/badge/License-MIT-2ea44f?style=flat&logo=opensourceinitiative&logoColor=white" height="28">
  </a>
  &nbsp;&nbsp;
  <a href="https://discord.gg/t9yEJc4MKX">
    <img src="https://img.shields.io/badge/Discord-Join%20Community-5865F2?style=flat&logo=discord&logoColor=white" height="28">
  </a>
  &nbsp;&nbsp;
  <a href="https://github.com/evochora/evochora/actions">
    <img src="https://img.shields.io/github/actions/workflow/status/evochora/evochora/build.yml?branch=main&style=flat&logo=github&logoColor=white&label=Build" height="28">
  </a>
  &nbsp;&nbsp;
  <a href="https://colab.research.google.com/github/evochora/evochora/blob/main/notebooks/data_analysis_guide.ipynb?flush_cache=true">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" height="28">
  </a>
  <!-- After the first archived release, with the concept DOI in both places:
  &nbsp;&nbsp;
  <a href="https://doi.org/10.5281/zenodo.0000000">
    <img src="https://img.shields.io/badge/DOI-10.5281%2Fzenodo.0000000-1682D4?style=flat&logo=zenodo&logoColor=white" height="28">
  </a>
  -->

</div>

<br>

Evochora is an open-source platform for digital evolution research. Its organisms are self-replicating programs laid out in a shared spatial environment, where they live under thermodynamic constraints.

<br>

<div align="center">
  <a href="https://evochora.org">
    <img alt="Live demo" src="https://img.shields.io/badge/Live_demo-evochora.org-1f6feb?style=for-the-badge" height="40">
  </a>
  <br>
  <sub>Runs in your browser. No installation required.</sub>
</div>

<br>

<!--<video src="https://github.com/user-attachments/assets/2dd2163a-6abe-4121-936d-eb46cc314859" loop></video>-->
<!--<video src="https://github.com/user-attachments/assets/28c329bc-9554-4b10-8d65-049f00eeda86" loop></video>-->

https://github.com/user-attachments/assets/69b33e5b-074b-46d6-917c-9b9cf06e10ef

<br>

<table>
  <tr>
    <td align="center" width="50%">
      <a href="https://github.com/user-attachments/assets/3bf0aa8e-f2bd-4b98-a03f-dd33d449ed93">
        <img alt="Web Visualizer" src="https://github.com/user-attachments/assets/3bf0aa8e-f2bd-4b98-a03f-dd33d449ed93" width="100%">
      </a>
      <br><strong>Web Visualizer</strong>
      <br><sub>Inspect any recorded tick: the environment and every organism with its internal state and the code it executes</sub>
    </td>
    <td align="center" width="50%">
      <a href="https://github.com/user-attachments/assets/eb83082c-0a04-4572-aaa3-2e601a1322c3">
        <img alt="Web Analyzer" src="https://github.com/user-attachments/assets/eb83082c-0a04-4572-aaa3-2e601a1322c3" width="100%">
      </a>
      <br><strong>Web Analyzer</strong>
      <br><sub>Follow the whole run: charts on its population, evolution and ecology</sub>
    </td>
  </tr>
</table>

<br>

## What is Evochora?

Evochora's organisms are virtual machines that execute code laid out in a shared, n-dimensional grid. The cells an organism writes become part of its body or of its offspring. Every instruction costs energy, which the organism has to harvest, and adds entropy, which it sheds by writing into the grid. What it builds and how it harvests and reproduces are written in its code, and so they are exposed to selection. Selection needs no reward from outside: it arises from the competition for space and energy.<br>→ [n-dimensional grid](docs/SCIENTIFIC_OVERVIEW.md#21-the-environment-and-its-molecules) · [virtual machine](docs/SCIENTIFIC_OVERVIEW.md#22-the-virtual-machine) · [ownership and thermodynamics](docs/SCIENTIFIC_OVERVIEW.md#23-ownership-and-thermodynamics)

Out of the box, a run starts with a default ancestor in a default environment. The ancestor is assembled from a library of behaviors, and you can set which of them it uses and under which conditions. The environment's physics is configurable too: its energy sources, the cost of every instruction, how offspring mutate and what happens to the dead.<br>→ [default ancestor](docs/USER_GUIDE.md#2-choose-or-write-an-ancestor) · [library of behaviors](assembly/lib/behavior/README.md) · [configurable physics](docs/USER_GUIDE.md#3-configure-an-experiment)

Beyond that, you can write new behaviors in EvoASM, an assembly language with a compiler, modules and procedures. And you can write plugins to replace any part of the physics or to act on the environment and all its organisms at every tick.<br>→ [assembly language](docs/EVOASM_SPEC.md) · [plugin interfaces](docs/USER_GUIDE.md#10-extend-evochora-with-plugins)

The web frontend lets you inspect a run even while it is being recorded. Its visualizer shows the environment at any recorded tick and any organism in it, down to the single instruction it is executing and the mutations its lineage carries. Its analyzer plots metrics over the whole run and can be extended with additional ones. All metrics are stored as Parquet files for analysis in Python or R.<br>→ [visualizer and analyzer](docs/USER_GUIDE.md#5-observe-a-run) · [parquet data](docs/USER_GUIDE.md#7-analyze-the-data)

Runs are deterministic: the same version and configuration give the same run on any machine (Linux, macOS and Windows). A run of billions of ticks can therefore be recorded sparsely, and any stretch of it re-recorded tick by tick. In this way, a change that spread through a population can be traced back to the mutation behind it.<br>→ [deterministic runs](docs/USER_GUIDE.md#8-reproduce-a-run) · [re-recording a stretch](docs/USER_GUIDE.md#6-record-a-stretch-in-full)

Evochora's development is driven by the desire to understand what an environment must provide for complexity to keep arising. The [Scientific Overview](docs/SCIENTIFIC_OVERVIEW.md) describes the physics and the research directions in depth, the [Published Experiments](docs/PUBLISHED_EXPERIMENTS.md) document what has evolved so far, and the [Origin Story](docs/ORIGIN_STORY.md) tells how the project came about.

<br>

## Quick Start

Download the archive for your system from the [GitHub Releases page](https://github.com/evochora/evochora/releases) and unpack it.<br>Start a simulation from the unpacked directory:

```bash
cd evochora-<version>-<platform>
bin/evochora node run          # Windows: bin\evochora.bat node run
```

Open the visualizer in your browser: http://localhost:8081/visualizer/

Next, set up, run and analyze your own experiment.<br>→ [user guide](docs/USER_GUIDE.md)

> A simulation records to disk until you press Ctrl+C, so allow sufficient disk space.<br>It keeps the processor busy while it runs.<br>By default it records only every n-th tick, which you can adjust with the [tuning profile](docs/USER_GUIDE.md#3-configure-an-experiment).

<br>

## Published Experiments

The project publishes its findings as citable records on Zenodo. Each record holds the data of its run, the build of Evochora that recorded it and an executed notebook with the evidence, so that anyone can check the finding and run the simulation again.

In the runs published so far, evolution changed where offspring are built, what they are made of and when a parent reproduces. None of it was designed or rewarded.<br>→ [published experiments](docs/PUBLISHED_EXPERIMENTS.md) · [records on zenodo](https://zenodo.org/communities/evochora)

<br>

## Documentation

Run experiments

- [User Guide](docs/USER_GUIDE.md): how to run an experiment, from the first start to analyzing and sharing its results.
- [CLI Usage Guide](docs/CLI_USAGE.md): every command of `bin/evochora` with its options.

Analyze the data

- [Analysis Notebook](notebooks/README.md): how to build an analysis of a run, shown by example, with a reference of the data a notebook can fetch.

Write organisms

- [Assembly Specification](docs/EVOASM_SPEC.md): the EvoASM language, the virtual machine and every instruction.
- [EvoASM Guidelines](docs/EVOASM_GUIDELINES.md): practices for writing programs that leave room for evolution and act reliably among other organisms.

Understand the project

- [Scientific Overview](docs/SCIENTIFIC_OVERVIEW.md): the physics of the environment, the first results, the research directions and the limitations.
- [Published Experiments](docs/PUBLISHED_EXPERIMENTS.md): the experiment records on Zenodo, each with its finding.
- [Origin Story](docs/ORIGIN_STORY.md): how the project came about.

Work on the code

- [Development Guide](docs/dev/DEVELOPMENT.md): how to build, run and test Evochora from its sources.
- [Architecture](docs/dev/ARCHITECTURE.md): how Evochora is built, its packages and how they depend on each other.
- [Architecture & Design Decisions](docs/dev/ARCHITECTURE_DECISIONS.md): why the main parts were built the way they are.

<br>

## Contributing & Community

Contributions of any kind are welcome, and none is too small: an experiment, an ancestor, a problem you found, or simply what you think. If you want to try Evochora, we are glad to help you get started.

Get in touch

- [Discord](https://discord.gg/t9yEJc4MKX): chat with us.
- [Discussions](https://github.com/evochora/evochora/discussions): ask a question, share an idea or show what you are working on.
- [Issues](https://github.com/evochora/evochora/issues): report a problem or request a feature.

Contribute

- [Contributing Guide](CONTRIBUTING.md): how to get started, whether you do research with Evochora or work on its code.
- [AI Policy](AI_POLICY.md): the rules for using AI assistants in the project.
- [Code of Conduct](CODE_OF_CONDUCT.md): what everyone in the project follows.

<br>

## License & Citation

Evochora is open source under the [MIT License](LICENSE).

If you use Evochora in your research, please cite the version that produced your runs. The reference is in [`CITATION.cff`](CITATION.cff), and GitHub offers it under "Cite this repository". [Cite and Share](docs/USER_GUIDE.md#9-cite-and-share) in the User Guide says how to find the version and its DOI.

<br>

## Use of AI

AI assistants have been used in Evochora since its inception. Their use is governed by the project's [AI Policy](AI_POLICY.md).
