import com.google.protobuf.gradle.*

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
    id("com.google.protobuf") version "0.9.4"
}

group = "org.evochora"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:3.25.3")
    implementation("com.google.protobuf:protobuf-java-util:3.25.3") // For JSON conversion
    implementation("com.zaxxer:HikariCP:5.1.0") // High-performance JDBC connection pool
    implementation("it.unimi.dsi:fastutil:8.5.12") // High-performance primitive collections
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("ch.qos.logback:logback-classic:1.5.6")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.awaitility:awaitility:4.2.1")
    testImplementation("io.rest-assured:rest-assured:5.4.0") // For API integration testing
    
    
    // Explicitly declare test framework implementation dependencies for Gradle 9 compatibility
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")
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

    // Apache Commons Math for scientifically-validated RNG with state serialization
    implementation("org.apache.commons:commons-math3:3.6.1")

    // Zstd compression library with bundled native binaries for cross-platform support
    implementation("com.github.luben:zstd-jni:1.5.5-11")

    // Test fixtures: dependencies needed to compile the JUnit extension
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testFixturesImplementation("ch.qos.logback:logback-classic:1.5.6")

}

// Definiert die Hauptklasse für den 'run'-Task
application {
    mainClass.set("org.evochora.cli.CommandLineInterface")
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
        attributes["Main-Class"] = "org.evochora.cli.CommandLineInterface"
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

tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
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
    maxParallelForks = 1 // Integration tests often can't run in parallel
    jvmArgs("-Xshare:off")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    // Explicitly configure classpath and test classes for Gradle 9 compatibility
    classpath = sourceSets.test.get().runtimeClasspath
    testClassesDirs = sourceSets.test.get().output.classesDirs
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
    // Explicitly configure classpath and test classes for Gradle 9 compatibility
    classpath = sourceSets.test.get().runtimeClasspath
    testClassesDirs = sourceSets.test.get().output.classesDirs
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

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    sourceSets {
        main {
            proto {
                srcDir("src/main/proto")
            }
            java {
                srcDirs("build/generated/source/proto/main/java")
            }
        }
    }
}