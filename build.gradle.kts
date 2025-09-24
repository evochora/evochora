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
}

group = "org.evochora"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("ch.qos.logback:logback-classic:1.5.6")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("com.h2database:h2:2.2.224")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
    implementation("io.javalin:javalin:6.1.3")
    implementation("com.typesafe:config:1.4.3")
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")
    implementation("org.jline:jline:3.30.3")
    runtimeOnly("org.jline:jline-terminal-jansi:3.30.3")

    // Test fixtures: dependencies needed to compile the JUnit extension
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testFixturesImplementation("ch.qos.logback:logback-classic:1.5.6")

}

// Definiert die Hauptklasse für den 'run'-Task
application {
    mainClass.set("org.evochora.cli.TemporaryCommandLineInterface")
}

// Application Plugin erstellt bereits Fat JARs mit allen Dependencies
// Das distZip/distTar Task erstellt ein Verzeichnis mit JAR + libs

// Konfiguriere den run-Task für interaktive Eingabe
tasks.named<JavaExec>("run") {
    group = "application"
    description = "Run the Evochora server CLI with interactive input"
    standardInput = System.`in`
}

// Der runServer Task wurde entfernt - verwende stattdessen: ./gradlew run

tasks.register<Jar>("cliJar") {
    archiveClassifier.set("cli")
    manifest {
        attributes["Main-Class"] = "org.evochora.cli.TemporaryCommandLineInterface"
    }
    
    // Add JVM arguments to suppress SLF4J warnings
    doFirst {
        // This will be used when running the jar
        println("To run without SLF4J warnings, use:")
        println("java -Dslf4j.replay.warn=false -jar build/libs/evochora-1.0-SNAPSHOT-cli.jar")
    }
    
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { config -> 
        config.map { if (it.isDirectory) it else zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(tasks.jar)
}

// Compile Task - Temporarily disabled after archiving datapipeline
tasks.register<JavaExec>("compile") {
    group = "application"
    description = "Compile assembly file to ProgramArtifact JSON (temporarily disabled)"
    // TODO: Re-implement compile functionality in new datapipeline
    mainClass.set("org.evochora.cli.TemporaryCommandLineInterface")
    classpath = sourceSets.main.get().runtimeClasspath
    
    args("compile")
    
    doFirst {
        val file = project.findProperty("file")?.toString()
        if (file != null) {
            args(file)
            
            // Add environment properties if specified
            val env = project.findProperty("env")?.toString()
            if (env != null) {
                args("--env=$env")
            }
        } else {
            throw GradleException("File parameter required. Use: ./gradlew compile --file=<path> [--env=<dimensions>[:<toroidal>]]")
        }
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("benchmark") // Exclude benchmark tests from regular test runs
    }
    jvmArgs("-Duser.language=en", "-Duser.country=US")
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    jvmArgs("-Xshare:off")
    finalizedBy(tasks.jacocoTestReport)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
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
    maxParallelForks = 1
    jvmArgs("-Duser.language=en", "-Duser.country=US")
    jvmArgs("-Xshare:off")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// Integration Tests - Medium speed, test service interactions
tasks.register<Test>("integration") {
    group = "verification"
    description = "Run integration tests"
    useJUnitPlatform {
        includeTags("integration")
    }
    maxParallelForks = 1 // Integration tests often can't run in parallel
    jvmArgs("-Xshare:off")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

// Benchmark Tests - Performance tests, run only when explicitly requested
tasks.register<Test>("benchmark") {
    group = "verification"
    description = "Run benchmark tests for performance measurement"
    useJUnitPlatform {
        includeTags("benchmark")
    }
    maxParallelForks = 1 // Benchmarks should run sequentially for accurate measurements
    jvmArgs("-Duser.language=en", "-Duser.country=US", "-Xmx2g") // Increased heap size for benchmarks
    jvmArgs("-Xshare:off")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    // Disable test caching to ensure benchmarks always run
    outputs.upToDateWhen { false }
    // Benchmarks are excluded from regular test runs and CI/CD
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude("org/evochora/ui/**", "org/evochora/Main*")
            }
        })
    )
}