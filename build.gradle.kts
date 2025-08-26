import java.io.FileOutputStream;
import java.time.Duration;

plugins {
    java
    application
    jacoco
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
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
    implementation("org.slf4j:slf4j-api:2.0.13")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("info.picocli:picocli:4.7.6")
    implementation("io.javalin:javalin:6.1.3")

}

// Definiert die Hauptklasse für den 'run'-Task
application {
    mainClass.set("org.evochora.server.CommandLineInterface")
}

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
        attributes["Main-Class"] = "org.evochora.server.CommandLineInterface"
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

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Duser.language=en", "-Duser.country=US")
    finalizedBy(tasks.jacocoTestReport)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    include("org/evochora/**")
    // Läuft alle Tests (wie vorher) - für CI/CD und vollständige Test-Suite
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
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
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