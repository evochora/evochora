# Load Tests for the Web Frontends

This directory holds a [k6](https://k6.io/) script that puts the load of visitors on a node:
`web-frontend-load.js` sends the requests the visualizer and the analyzer send, and reports how
long the node takes to answer each kind of them. It serves to see what a demo host stands, and to
compare two versions of it.

## Prerequisites

- [k6](https://k6.io/docs/get-started/installation/) installed on your machine.

## Usage

Against a node on this machine, at `http://localhost:8081`:

```bash
k6 run web-frontend-load.js
```

Against another host, for one of its runs:

```bash
k6 run -e TARGET_URL=https://<your-host> -e RUN_ID=<run-id> web-frontend-load.js
```

| Variable | Meaning | Without it |
|---|---|---|
| `TARGET_URL` | The node to test | `http://localhost:8081` |
| `RUN_ID` | The run the requests ask for | the node answers for its newest run |
| `SCENARIO` | Whom the virtual users act as: `visualizer` or `analyzer` | both mixed, 95% visualizer |

The script ramps up to 50 virtual users and holds them for a minute. k6's own options change
that, for example five users for three minutes:

```bash
k6 run --stage 10s:5 --stage 3m:5 --stage 10s:0 -e SCENARIO=visualizer web-frontend-load.js
```

Results depend on the run: on the size of its environment, on how many organisms it holds and on
how many ticks it recorded. To compare two hosts, give each a run of the same kind with `RUN_ID`.

## What the virtual users do

A visualizer user loads the metadata of the run and the range of its ticks, then looks at ten
recorded ticks in a row. For each tick it asks for the environment twice, zoomed out and zoomed
in, for the list of the organisms and for the details of one of them.

An analyzer user loads the list of the runs and the manifest of the metrics, then asks for every
metric as the analyzer's page does: the range of its ticks and its data as Parquet.

## Reading the result

The summary lists the response times of each kind of request on a line of its own. Every request
that is not answered with 200 counts as an error, a request that runs into k6's time limit among
them.

A load a host cannot stand may leave its node unable to answer afterwards. Check the host after a
test, and restart the node where it no longer answers.
