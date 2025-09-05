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
- Tag all tests as integration, except they run in under 0.2s and so not use any i/o ressources.
- If no i/o ressources are used and test runs in under 0.2s then tag as unit
- Test must not leave any artifacts like file
- If tests use sqlite they should use in momenry sqlite
- Ths instruction set needs to be initiatlized with Instruction.init() before the compiler or runtime can use any instruction.   

# Architectureal pincipals
- Compiler phases are immutable
- Every compiler phase runs exactly only once
- Compiler phases never call other compiler phases
- For the Compile phases main classes:
  - PreProcessor.java Parser.java, SemanticAnalyzer.java, IrGenerator.java, LayoutEngine.java, Linker.java, Emitter.java
  - Their corresponding handlers/ plugins system must be used
  - All logic should go into such handlers / plugins.
  - Compiler phases main classes must stay clean and should basically be only distributors for their handlers/ plugins. 
    
## CI & PR expectations
- CI: GitHub Actions that run `./gradlew build` on Ubuntu & Windows.
- PR must include: summary, changelog, follow-up suggestions, green CI.
- Branch naming: `jules/<short-purpose>`.

## Conventions
- Code style: **GOOGLE_JAVA_FORMAT_OR_OTHER** (if any).
- Commit message style: **CONVENTION**.
- Everything documented with extensive JavaDoc in English

## Known constraints
- Don’t commit secrets; environment via **HOW_TO_SET_ENV**.
- If build fails due to UI: use `./gradlew build -x test` and open a PR that fixes tests separately.
