import com.google.protobuf.gradle.*
import java.util.Properties
import org.gradle.jvm.application.tasks.CreateStartScripts

// Show deprecation details for test sources to fix root causes
tasks.withType<JavaCompile>().configureEach {
    if (name == "compileTestJava") {
        options.compilerArgs.add("-Xlint:deprecation")
    }
}


plugins {
    java
    application
    jacoco
    `java-test-fixtures`
    id("com.google.protobuf") version "0.9.6"
    id("me.champeau.jmh") version "0.7.3"
    id("pmd")
    id("org.sonarqube") version "7.5.0.8588"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

group = "org.evochora"
version = project.findProperty("RELEASE_TAG")?.toString() ?: "latest"

// Remove -SNAPSHOT suffix if it exists for local builds
if (version.toString().endsWith("-SNAPSHOT")) {
    version = version.toString().removeSuffix("-SNAPSHOT")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:4.33.0")
    implementation("com.google.protobuf:protobuf-java-util:4.33.0") // For JSON conversion
    implementation("com.zaxxer:HikariCP:6.2.1") // High-performance JDBC connection pool
    implementation("it.unimi.dsi:fastutil:8.5.12") // High-performance primitive collections
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("ch.qos.logback:logback-classic:1.5.26")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.awaitility:awaitility:4.2.1")
    testImplementation("io.rest-assured:rest-assured:5.4.0") // For API integration testing
    testImplementation("io.javalin:javalin-testtools:6.7.0")
    testImplementation("com.tngtech.archunit:archunit:1.5.0") // Package dependency rules
    
    
    // Explicitly declare test framework implementation dependencies for Gradle 9 compatibility
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("ch.qos.logback:logback-classic:1.5.26")
    // REMOVED: sqlite-jdbc - not used, H2 is the primary database
    implementation("com.h2database:h2:2.4.240")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
    implementation("io.javalin:javalin:6.7.0")
    // Javalin OpenAPI Plugin for API documentation
    implementation("io.javalin.community.openapi:javalin-openapi-plugin:6.7.0-1")
    annotationProcessor("io.javalin.community.openapi:openapi-annotation-processor:6.7.0-1")
    implementation("com.typesafe:config:1.4.3")
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")
    implementation("org.jline:jline:3.30.4")
    runtimeOnly("org.jline:jline-terminal-jansi:3.30.4")

    // Apache Commons Math for scientifically-validated RNG with state serialization
    implementation("org.apache.commons:commons-math3:3.6.1")

    // Zstd compression library with bundled native binaries for cross-platform support
    implementation("com.github.luben:zstd-jni:1.5.5-11")

    // Caffeine high-performance cache for chunk caching in EnvironmentController
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.0")

    // Reflections library for dynamic subcommand registration (video renderers)
    implementation("org.reflections:reflections:0.10.2")

    // DuckDB JDBC driver for analytics
    implementation("org.duckdb:duckdb_jdbc:1.4.3.0")

    // ActiveMQ Artemis for high-performance messaging (Topics/Queues)
    implementation("org.apache.artemis:artemis-jakarta-server:2.51.0")
    implementation("org.apache.artemis:artemis-jakarta-client:2.51.0")

    // JMS session pooling for producer sends (eliminates TCP session creation overhead)
    implementation("org.messaginghub:pooled-jms:3.2.2")

    // Test fixtures: dependencies needed to compile the JUnit extension
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testFixturesImplementation("ch.qos.logback:logback-classic:1.5.26")
    testFixturesImplementation("com.google.protobuf:protobuf-java:4.33.0")
    testFixturesImplementation("com.typesafe:config:1.4.3")

}

application {
    mainClass.set("org.evochora.cli.CommandLineInterface")
    applicationDefaultJvmArgs = listOf("-Xmx8g")
}

// Fix for Windows "Input line is too long" error
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val windowsScriptFile = windowsScript
        if (windowsScriptFile.exists()) {
            val content = windowsScriptFile.readText()
            // Replace the long classpath with a wildcard classpath
            val newContent = content.replace(
                Regex("set CLASSPATH=.*"),
                "set CLASSPATH=%APP_HOME%\\\\lib\\\\*"
            )
            windowsScriptFile.writeText(newContent)
        }
    }
}

// Konfiguriere den run-Task für interaktive Eingabe
tasks.named<JavaExec>("run") {
    dependsOn(tasks.processResources) // Ensure resources are updated before running
    group = "application"
    description = "Run the Evochora server CLI with interactive input"
    standardInput = System.`in`
}

// Fix empty info section in generated OpenAPI files
tasks.named("compileJava") {
    // ÄNDERUNG: Werte in der Konfigurationsphase erfassen
    val apiVersion = project.version.toString()
    val buildDir = layout.buildDirectory.get().asFile

    doLast {
        // ÄNDERUNG: Keine Verwendung von file() oder project.* hier drin
        val openApiDir = buildDir.resolve("classes/java/main/openapi-plugin")
        val openApiFile = openApiDir.resolve("openapi-default.json")
        
        if (openApiFile.exists()) {
            val content = openApiFile.readText()
            // Verwendung der zuvor erfassten Variable
            val fixedContent = content
                .replace("\"title\": \"\"", "\"title\": \"Evochora API\"")
                .replace("\"version\": \"\"", "\"version\": \"$apiVersion\"")
            openApiFile.writeText(fixedContent)
        }
    }
}

// Copy generated OpenAPI files to resources directory
tasks.named("processResources") {
    doLast {
        val openApiSource = file("build/classes/java/main/openapi-plugin")
        val openApiTarget = file("src/main/resources/openapi-plugin")
        if (openApiSource.exists()) {
            openApiTarget.mkdirs()
            copy {
                from(openApiSource)
                into(openApiTarget)
            }
        }

        // Copy notebook to web root so it can be served for same-origin download
        val notebookSource = file("notebooks/data_analysis_guide.ipynb")
        val notebookTarget = layout.buildDirectory.get().asFile.resolve("resources/main/web/root/notebooks")
        if (notebookSource.exists()) {
            notebookTarget.mkdirs()
            copy {
                from(notebookSource)
                into(notebookTarget)
            }
        }
    }
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "org.evochora.cli.CommandLineInterface"
    }
    // Dependencies werden über lib/ und den Classpath im Start-Skript geladen (installDist)
}

tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Configure the distribution archives (distZip, distTar)
tasks.withType<AbstractArchiveTask> {
    // Use the project version for archives, which is dynamically set from RELEASE_TAG
    archiveVersion.set(project.version.toString())
}

distributions {
    main {
        contents {
            from("assembly") {
                into("assembly")
            }
            from("config") {
                into("config")
            }
            from("README.md")
        }
    }
}

tasks.withType<Tar> {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

// Two test JVMs for every test task. A second fork cuts the wall-clock time by a third and, as
// Gradle never splits a class across forks, makes every hidden dependency between test classes
// fail visibly. More forks do not pay off: each JVM initialises Mockito, H2, Javalin and the
// brokers on its own, and the JVMs then compete for cores and JIT time. On eight cores, four
// forks doubled the summed test time and finished later than two.
val testForks = 2

tasks.test {
    useJUnitPlatform()
    maxParallelForks = testForks
    // The assembly programs are compiled by a test, so a change to one has to invalidate the
    // task. Gradle decides that per task, not per test class: touching a program reruns the
    // suite, which is the price for the examples and the primordial staying compilable.
    inputs.dir("assembly").withPropertyName("assemblyPrograms")
    maxHeapSize = "2g" // Increase heap size for tests
    jvmArgs("-Duser.language=en", "-Duser.country=US")
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    jvmArgs("-Xshare:off")
    finalizedBy(tasks.jacocoTestReport)
    testLogging {
        events("passed", "skipped", "failed")
        // Only show output for failed tests (silent on success)
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    include("org/evochora/**")
    // Läuft alle Tests außer Benchmarks - für CI/CD und vollständige Test-Suite
}

// Unit Tests - Fast, isolated tests without external dependencies
tasks.register<Test>("unit") {
    group = "verification"
    description = "Run fast unit tests"
    useJUnitPlatform {
        includeTags("unit")
    }
    maxParallelForks = testForks
    jvmArgs("-Duser.language=en", "-Duser.country=US")
    jvmArgs("-Xshare:off")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // Explicitly configure classpath and test classes for Gradle 9 compatibility
    classpath = sourceSets.test.get().runtimeClasspath
    testClassesDirs = sourceSets.test.get().output.classesDirs
}

// Integration Tests - Medium speed, test service interactions
tasks.register<Test>("integration") {
    group = "verification"
    description = "Run integration tests"
    useJUnitPlatform {
        includeTags("integration")
    }
    maxHeapSize = "2g" // Match test task heap size to avoid OOM
    maxParallelForks = testForks
    jvmArgs("-Xshare:off")
    testLogging {
        events("passed", "skipped", "failed")
        // Only show output for failed tests (silent on success)
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    // Explicitly configure classpath and test classes for Gradle 9 compatibility
    classpath = sourceSets.test.get().runtimeClasspath
    testClassesDirs = sourceSets.test.get().output.classesDirs
}

// The classes the coverage report and the coverage gate look at. Taken from the source set
// output so that the collection carries the tasks producing it: both JaCoCo tasks then depend on
// compilation directly, not only through the test task's execution data, and a build that skips
// the tests (-x test) still resolves its task graph.
val coveredClasses = sourceSets.main.get().output.classesDirs.asFileTree

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(coveredClasses)
}

// Coverage may not erode unnoticed: the build fails below this share of covered lines. The bound
// sits under the current value so that an ordinary refactoring cannot trip it; raising it is a
// deliberate change once the suite has grown past it.
tasks.jacocoTestCoverageVerification {
    classDirectories.setFrom(coveredClasses)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// Coverage of the lines a change touches, as a counterpart to the project-wide gate above.
apply(from = "gradle/new-code-coverage.gradle.kts")

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.33.0"
    }
}

// The protobuf plugin adds a generated-sources directory to every source set, but proto files
// exist only in src/main/proto. For the other source sets nothing is ever generated, so their
// directories stay absent and IDEs report them as missing source folders. Removing them keeps the
// build model in line with where proto files actually live.
afterEvaluate {
    val generatedProtoRoot = layout.buildDirectory.dir("generated/sources/proto").get().asFile
    sourceSets.configureEach {
        if (name != SourceSet.MAIN_SOURCE_SET_NAME) {
            java.setSrcDirs(java.srcDirs.filterNot { it.startsWith(generatedProtoRoot) })
        }
    }
}

jmh {
    jvmArgs.set(listOf("-Xmx8g"))
}

// Notebooks are committed without execution state (outputs, execution counts). This is done by a
// git clean filter, which Git deliberately does not let a repository define for itself — a
// repository could otherwise run arbitrary commands on every clone. Registering it is therefore a
// per-clone step, and this task performs it during the first build so nobody has to remember it.
// CI enforces the result independently, see .github/workflows/build.yml.
val registerNotebookFilter = tasks.register("registerNotebookFilter") {
    group = "build setup"
    description = "Registers the git clean filter that strips notebook execution state"

    val gitDir = rootProject.file(".git")
    val repoRoot = rootProject.projectDir
    val runningInCi = providers.environmentVariable("CI").isPresent
    val configKey = "filter.nbstrip.clean"

    // Absent in source archives and container builds; irrelevant on CI runners, which never commit.
    onlyIf { gitDir.exists() && !runningInCi }

    doLast {
        fun run(vararg command: String): Pair<Int, String> {
            val process = ProcessBuilder(*command)
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            return process.waitFor() to output
        }

        val (existingStatus, existingValue) = run("git", "config", "--get", configKey)
        if (existingStatus == 0 && existingValue.isNotEmpty()) {
            logger.info("Notebook filter already registered as: $existingValue")
            return@doLast
        }

        val interpreter = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val (interpreterStatus, _) = try {
            run(interpreter, "--version")
        } catch (e: Exception) {
            -1 to ""
        }
        if (interpreterStatus != 0) {
            // A filter whose command cannot run makes every `git add` of a notebook fail, which is
            // worse than having no filter at all.
            logger.warn("Notebook filter not registered: '$interpreter' is not available. " +
                    "Notebooks would be committed with their outputs — see notebooks/README.md.")
            return@doLast
        }

        val (writeStatus, writeOutput) = run("git", "config", configKey, "$interpreter tools/nbstrip.py")
        if (writeStatus == 0) {
            logger.lifecycle("Registered git filter '$configKey' — notebooks are committed without outputs.")
        } else {
            logger.warn("Could not register the notebook filter: $writeOutput")
        }
    }
}

tasks.named("build") {
    dependsOn(registerNotebookFilter)
}


// Code that is written but never reached: the compiler accepts it and the tests pass over it, so
// nothing else in this build would ever report it.
pmd {
    toolVersion = "7.26.0"
    threads = 4
    // On by default, stated here because the CI workflow restores and saves the resulting
    // build/tmp/pmd*/incremental.cache between runs; without it that step would cache nothing.
    incrementalAnalysis = true
    isConsoleOutput = true
    ruleSetFiles = files("gradle/pmd/ruleset.xml")
    ruleSets = listOf()
    isIgnoreFailures = false
}

tasks.withType<Pmd>().configureEach {
    // Generated protobuf sources: nobody edits them, and the generator will not follow these rules.
    exclude("**/org/evochora/datapipeline/api/contracts/**")
}

// Analysis by SonarQube Cloud. The `sonar` task is deliberately not wired into `build` or `check`:
// it uploads to an external service and needs a token, so it runs only where it is asked for —
// the CI workflow, or an explicit local `./gradlew sonar`.
//
// Coverage is not reported here. The build already gates line coverage through
// jacocoTestCoverageVerification, and Sonar does not measure coverage itself; it would only import
// the same JaCoCo report. Leaving it out keeps the analysis independent of a test run.
sonar {
    properties {
        property("sonar.projectKey", "evochora_evochora")
        property("sonar.organization", "evochora")
        property("sonar.host.url", "https://sonarcloud.io")
        // Generated protobuf sources: nobody edits them, and the generator will not follow these rules.
        property("sonar.exclusions", "**/org/evochora/datapipeline/api/contracts/**")
    }
}

// Javadoc that no longer resolves — a link to a method that was renamed, markup that does not
// parse — compiles without complaint and is only noticed by whoever reads the generated
// documentation. The javadocLint task below turns it into a build failure instead.
//
// The generating task itself runs without doclint. Not because the checks are unwanted, but
// because it prints every warning it finds, and the 140 unavoidable ones would bury the rest of
// the build output under every invocation.
tasks.withType<Javadoc>().configureEach {
    // Generated protobuf sources: nobody edits them, and the generator will not follow these rules.
    exclude("**/org/evochora/datapipeline/api/contracts/**")
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

// Runs javadoc a second time, with doclint on and its output captured rather than printed.
//
// Everything about the invocation — classpath, sources, destination — is taken from the options
// file the javadoc task writes, so this cannot drift away from what is actually documented.
//
// Only one warning is allowed through: the implicit constructor of a public class has no place in
// the source to carry a comment. doclint cannot name a single warning — "-missing" would allow
// back every absent comment, tag and description — so it is recognised by its text. A future JDK
// could word it differently; the failure would be loud rather than silent, because the build would
// start reporting warnings it used to pass.
val javadocLint by tasks.registering {
    dependsOn(tasks.named("javadoc"))
    val optionsFile = layout.buildDirectory.file("tmp/javadoc/javadoc.options")
    val lintDir = layout.buildDirectory.dir("tmp/javadocLint")
    val javadocTool = javaToolchains.javadocToolFor(java.toolchain).map { it.executablePath }
    inputs.file(optionsFile)
    outputs.dir(lintDir)
    doLast {
        val source = optionsFile.get().asFile.readLines()
        val target = lintDir.get().asFile.also { it.mkdirs() }
        // The destination is replaced so that the check cannot touch the documentation that was
        // just generated; Xmaxwarns is raised so the constructor warnings cannot push a real one
        // past javadoc's default cap of 100.
        val rewritten = mutableListOf<String>()
        var skipNext = false
        for (line in source) {
            if (skipNext) { skipNext = false; continue }
            if (line.trim() == "-d") { skipNext = true; continue }
            if (line.trimStart().startsWith("-d ")) continue
            if (line.contains("-Xdoclint")) continue
            rewritten.add(line)
        }
        rewritten.add(0, "-d '${File(target, "docs").absolutePath}'")
        rewritten.add(1, "-Xdoclint:all")
        rewritten.add(2, "-Xmaxwarns '10000'")
        val lintOptions = File(target, "javadocLint.options")
        lintOptions.writeText(rewritten.joinToString("\n"))

        val result = providers.exec {
            commandLine(javadocTool.get().toString(), "@${lintOptions.absolutePath}")
            isIgnoreExitValue = true
        }
        val text = result.standardOutput.asText.get() + result.standardError.asText.get()

        val unexpected = text.lineSequence()
            .filter { it.contains(": warning:") || it.contains(": error:") }
            .filterNot { it.contains("use of default constructor") }
            .map { it.trim() }
            .toCollection(linkedSetOf())

        if (unexpected.isNotEmpty()) {
            throw GradleException(
                "javadoc reported ${unexpected.size} problem(s) other than the implicit constructor:\n"
                    + unexpected.joinToString("\n")
            )
        }
    }
}

tasks.named("check") {
    dependsOn(javadocLint)
}
