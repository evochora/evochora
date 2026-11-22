# The Demo System

The demo at evochora.org is one server that runs two containers: a node that shows recorded runs,
and Caddy, which delivers the homepage and passes everything else on to the node. This directory
holds all of it, laid out as on the server: [`docker-compose.yml`](docker-compose.yml) describes both
containers, [`Caddyfile`](Caddyfile) configures Caddy, [`config`](config) holds the configurations
the demo's runs are recorded with, [`site`](site) is the homepage, and [`cloud-init.sh`](cloud-init.sh)
sets up a new server. The image of the node is built from [`../image`](../image).

## What a deploy does

The workflow `deploy.yml` copies `docker-compose.yml`, the `Caddyfile`, the two configurations in
`config`, the homepage and the repository's own `config/evochora.conf` to the server, writes the
`.env` file with the image tag and the memory for Java, pulls the image and starts the containers.

The node container runs `bin/evochora node show`. It serves the runs in its data directory to the
visualizer and the analyzer and simulates nothing, so neither a deploy nor a restart of the server
starts a run or changes one.

## Recording the runs

A run is recorded by hand on the server, with a container that is started for this alone. Only one
node can have the database open, so the node that shows the runs is stopped for the time. The
homepage lists the runs with the newest first: record the run of `population.conf` first and the
run of `replication.conf` after it.

```bash
cd ~/app
docker compose stop evochora

# The long run. It goes on until it is stopped.
docker compose run -d --use-aliases --name record evochora bin/evochora -c config/population.conf node run
docker logs -f record                # the log of the node
docker stop -t 120 record            # ends the run; the node needs a moment to shut down
docker rm record

# The run of one replication. It pauses by itself at its pause tick and is stopped then.
docker compose run -d --use-aliases --name record evochora bin/evochora -c config/replication.conf node run
docker logs -f record
docker stop -t 120 record
docker rm record

docker compose up -d evochora
```

`--use-aliases` gives the recording container the name Caddy looks for, so the homepage, the
visualizer and the analyzer show a run while it is being recorded.

A run that was stopped can be continued with `node resume --run <id>` in place of `node run`.
`bin/evochora cleanup`, started the same way, deletes runs; the
[CLI Usage Guide](../../docs/CLI_USAGE.md) describes both.

## Settings of the server

- The memory for Java comes from `JAVA_MAX_MEM` in `~/app/.env`, which the deploy writes.
- The node listens on every interface of its container, because Caddy reaches it from the other
  container. `docker-compose.yml` sets this, and publishes none of the node's ports.
- Everything else is `config/evochora.conf` as the repository holds it.
