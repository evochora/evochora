# Dependency Update

**Status: TO BE REVIEWED — agreed procedure, decisions listed at the end.**

> **Note (2026-08-23):** Scheduled after PERSISTED_FORMAT_VERSIONING, because the treatment of
> own versus third-party formats decided there determines which safeguards an update needs. The
> version tables below are a snapshot of 2026-08-17 and must be re-collected from
> `maven-metadata.xml` before implementation; the procedure and the four rules do not age. All
> dependencies are to be kept current, major versions included.

## Problem

The dependency set in `build.gradle.kts` has drifted. Checked against `maven-metadata.xml` on
repo1.maven.org on 2026-08-17, 24 of the 32 declared artifacts are behind, six of them by a major
version. Alongside that the build carries one dependency that nothing uses and three declarations
that are internally inconsistent.

The Solr endpoint at `search.maven.org` is not usable for this check — it returns results in an order
that is neither version-sorted nor current, and reported several "latest" versions that were older
than what the build already declares. `maven-metadata.xml` is the authoritative source.

## Findings

### Behind, patch or minor

| Dependency | Current | Available |
|---|---|---|
| `com.google.protobuf:protobuf-java` / `-util` / `protoc` | 4.33.0 | 4.35.1 |
| `it.unimi.dsi:fastutil` | 8.5.12 | 8.5.19 |
| `org.slf4j:slf4j-api` | 2.0.17 | 2.0.18 |
| `com.google.code.gson:gson` | 2.13.2 | 2.14.0 |
| `com.typesafe:config` | 1.4.3 | 1.4.9 |
| `com.github.ben-manes.caffeine:caffeine` | 3.2.0 | 3.2.4 |
| `com.github.luben:zstd-jni` | 1.5.5-11 | 1.5.7-15 |
| `org.messaginghub:pooled-jms` | 3.2.2 | 3.2.4 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.20.1 | 2.22.2 |
| `org.assertj:assertj-core` | 3.26.3 | 3.27.7 |
| `org.mockito:mockito-core` / `-junit-jupiter` | 5.12.0 | 5.23.0 |
| `org.awaitility:awaitility` | 4.2.1 | 4.3.0 |
| `org.duckdb:duckdb_jdbc` | 1.4.3.0 | 1.5.5.1 |
| `org.apache.artemis:artemis-jakarta-server` / `-client` | 2.51.0 | 2.55.0 |

### Behind by a major version

| Dependency | Current | Available | Note |
|---|---|---|---|
| `org.junit:junit-bom` and the JUnit artifacts | 5.10.0 / 5.10.2 | 6.1.3 | |
| `io.javalin:javalin` / `javalin-testtools` | 6.7.0 | 7.2.3 | |
| `io.javalin.community.openapi:javalin-openapi-plugin` / `openapi-annotation-processor` | 6.7.0-1 | 7.2.3 | version scheme changed — the `-1` suffix is gone |
| `ch.qos.logback:logback-classic` | 1.5.26 | 1.6.3 | coupled to the encoder below |
| `net.logstash.logback:logstash-logback-encoder` | 8.1 | 9.0 | must move together with logback |
| `com.zaxxer:HikariCP` | 6.2.1 | 7.1.0 | |
| `io.rest-assured:rest-assured` | 5.4.0 | 6.0.1 | |
| `com.google.protobuf:protobuf-gradle-plugin` | 0.9.6 | 0.10.0 | |
| Gradle wrapper | 8.13 | 9.7.0 | the build already contains Gradle 9 compatibility workarounds |

### Already current

`com.h2database:h2` 2.4.240, `info.picocli:picocli` and `-codegen` 4.7.7,
`org.apache.commons:commons-math3` 3.6.1, `org.reflections:reflections` 0.10.2,
`me.champeau.jmh:jmh-gradle-plugin` 0.7.3.

### Unused dependency

`org.jline:jline` and the `runtimeOnly` `org.jline:jline-terminal-jansi` are referenced nowhere —
not in `src/main/java`, not in resources or configuration, not in tests. No picocli JLine
integration artifact is declared either, so nothing loads them indirectly. They are removed rather
than updated.

This also removes a trap: `org.jline:jline` exists as 4.3.1, but `jline-terminal-jansi` does not
exist in the 4.x line at all — the 3.x line is maintained in parallel up to 3.30.16. Updating the
pair naively would have forced an unnecessary terminal-provider migration.

### Build hygiene

- `ch.qos.logback:logback-classic:1.5.26` is declared twice, as `implementation` and again as
  `testImplementation`. The test declaration is redundant.
- The JUnit BOM is pinned at 5.10.0 while `junit-jupiter-engine` is pinned at 5.10.2 in three
  places. Versions must come from the BOM alone.
- `testFixturesImplementation` does not import the BOM at all, which is why it carries a hard-coded
  `5.10.2`.

## What can and cannot be verified

Confidence in a dependency update is not one number. It differs sharply by failure class:

| Failure class | Caught by | Situation here |
|---|---|---|
| Removed or changed signature | `compileJava` | complete and deterministic; no residual risk |
| Transitive version shift | diff of `gradlew dependencies` | only if the diff is actually taken — no test surfaces it |
| Behaviour change on a tested path | the test suite | strong: 233 test classes, 47 tagged `integration` |
| Reflection / `ServiceLoader` / JDBC driver loading | runtime only | partial: 16 production classes load dynamically, most are started by integration tests |
| Native library, platform | CI matrix | covered: CI runs `ubuntu-latest` and `windows-latest` |
| **Format break against already persisted data** | **nothing** | **not covered** |

The integration tests are genuinely strong — `ArtemisTopicIntegrationTest`, `ArtemisQueueResourceTest`
and `EmbeddedBrokerProcessTest` start a real broker, `AnalyticsIndexerEndToEndTest` produces real
Parquet output, the Javalin controllers run against a real server, and protobuf and Zstd are
exercised throughout.

What they cannot do is detect a format break. `src/test/resources` holds only `.evo`, `.conf`, `.xml`
and `.properties` — no binary data fixture exists. Every test creates its data within the same run,
using the same library version that later reads it. A round-trip test is structurally blind to a
format change.

### Which formats a fixture may legitimately cover

The distinction that matters is ownership, not age:

- **Formats this codebase owns** — storage batches, the H2 schema, resume checkpoints. Backward
  compatibility is deliberately not maintained here, so a fixture test would fail on every intended
  change and be repaired by regenerating the fixture. It is the wrong instrument; the right one is a
  version stamp with fail-fast reads, specified in
  [PERSISTED_FORMAT_VERSIONING](PERSISTED_FORMAT_VERSIONING.md).
- **Formats a third party owns and guarantees** — Parquet, Zstd frames, the protobuf wire format.
  Nothing in this codebase changes them. A failure there is therefore always a real finding: either
  the library broke its guarantee, or the update crossed an announced break. Never noise.

Only the second class justifies a fixture, which reduces the fixture work to a single test.

### Two risk assessments corrected

**DuckDB carries no storage-format risk.** All four production call sites open
`DriverManager.getConnection("jdbc:duckdb:")` without a path — the database is purely in-memory and no
`.duckdb` file exists anywhere. DuckDB is a query engine over Parquet here, not a store. It moves to
the ordinary minor-update group.

**The Artemis journal is broker runtime state, not archived data.** Persistence is enabled and the
journal lives on disk, but it holds undelivered messages rather than run results. The appropriate
safeguard is operational — let the queues drain before upgrading — not a test.

## Solution

The remaining Parquet boundary is worth one fixture, because it is a genuine cross-tool boundary: the
notebooks read the same Parquet files through the Python DuckDB package, which is versioned entirely
independently of the JDBC driver.

Beyond that the procedure rests on four rules:

1. **`protoc` and `protobuf-java` move in lockstep.** Diverging versions produce generated code that
   does not match the runtime. All three protobuf coordinates carry the same version.
2. **One dependency per commit.** This is the difference between "one of these fifteen updates broke
   test X" and three steps of `git bisect`.
3. **Diff the dependency graph, not just the declarations.** `gradlew dependencies` before and after.
   The most common silent breakage in an update is not the named library but a transitive one it
   pulls along.
4. **A real smoke run in addition to the suite.** `installDist`, start the server, run a short
   simulation, the five CLI compile checks, one resume. Logback configuration, OpenAPI annotation
   processing and the Reflections-based video-renderer registration only surface there.

## Implementation

**Step 1 — Hygiene and dead weight.** Remove both `org.jline` declarations and the duplicate
`logback-classic` test declaration. Raise the JUnit BOM so all JUnit artifacts draw their version
from it, and add the BOM to `testFixturesImplementation` so the hard-coded version there disappears.
No version changes otherwise, so the suite must stay green unchanged.

**Step 2 — Parquet fixture.** Produce a Parquet file with the current DuckDB version, commit it, and
add a test that reads it. This must happen before step 4; afterwards the artifact cannot be
reproduced.

**Step 3 — Dependency graph baseline.** Record `gradlew dependencies` against the current state as the
reference for later diffs.

**Step 4 — Minor and patch updates**, one per commit, in the order of the first table, with the three
protobuf coordinates as one commit. Full suite per commit; graph diff whenever the output changes.

**Step 5 — Artemis 2.55.0** on its own, with a full integration run and a broker restart against a
non-empty journal.

**Step 6 — Major updates**, one per commit, in ascending order of expected effort: protobuf-gradle-plugin,
HikariCP, rest-assured, Gradle wrapper, JUnit 6, logback with the logstash encoder, Javalin 7 with the
OpenAPI plugin. Logback and Javalin need the smoke run rather than the suite alone — a broken appender
or an unregistered plugin leaves the tests green.

## Decisions required before implementation

1. **Whether the Gradle wrapper update pulls the Docker builder image with it.**

   `infrastructure/docker/evochora-node/Dockerfile` builds from `gradle:8.8.0-jdk21-jammy`, which is
   already older than the wrapper the repository declares.

   Arguments for updating it together: the image tag and the wrapper stating different versions is
   misleading to anyone reading the Dockerfile, and the gap will widen further with Gradle 9.

   Arguments for leaving it: the image only provides a JDK and a Gradle that the build never uses,
   because every build step invokes `./gradlew`. The tag is effectively documentation, and changing
   it touches the release path for no functional gain.

2. **Whether the Java toolchain stays at 21.**

   The toolchain, both CI jobs and the Docker image are on JDK 21. Several of the major updates
   raise their own minimum JDK requirement; the exact figures are not established here and must be
   confirmed per library as part of step 6.

   Arguments for staying: a toolchain change affects every artifact the project publishes, and it
   should only be undertaken if one of the updates actually requires it.

   Arguments for moving: doing it while the dependency set is already in motion means one round of
   verification rather than two, and each of the majors would otherwise be verified against a
   toolchain that is itself due to change.
