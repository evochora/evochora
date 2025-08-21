# AGENTS.md

## Project overview
Simulate evolution of organisms in n-D worlds, written in Assemnbly.

## Build & run (Java/Gradle)
- Build (all): `./gradlew clean build`
- Assemble (no tests): `./gradlew clean assemble`
- Unit tests: `./gradlew test`
- Java toolchain: **21** (configured in `build.gradle(.kts)`)
- Packaging/outputs: `jar` (+ `distZip`/`distTar` if application plugin)

## Repository layout
- `src/main/java` – application code
- `src/test/java` – unit tests
- `gradlew`, `gradlew.bat`, `gradle/wrapper/**` – Gradle wrapper

## What agents should do
- Allowed: refactors, bug fixes, unit tests, safe dependency bumps (patch/minor).
- Avoid: changing Config.java and any UI/FX tests.
- Prefer minimal diffs; explain reasoning in PR.

## Testing guidelines
- Framework: JUnit 5 (`useJUnitPlatform()`).
- Write tests for org.evochora.assembler, org.evochora.organism, org.evochora.runtime.instructions
- Skip/mark slow/integration tests: .
- (Optional) Coverage goal: 60%+ lines (JaCoCo).

## CI & PR expectations
- CI: GitHub Actions that run `./gradlew build` on Ubuntu & Windows.
- PR must include: summary, changelog, follow-up suggestions, green CI.
- Branch naming: `jules/<short-purpose>`.

## Conventions
- Code style: **GOOGLE_JAVA_FORMAT_OR_OTHER** (if any).
- Commit message style: **CONVENTION**.

## Known constraints
- Don’t commit secrets; environment via **HOW_TO_SET_ENV**.
- If build fails due to UI: use `./gradlew build -x test` and open a PR that fixes tests separately.
