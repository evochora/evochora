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
            from("evochora.conf") {
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

tasks.test {
    useJUnitPlatform()
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
    maxHeapSize = "2g" // Match test task heap size to avoid OOM
    maxParallelForks = 1 // Integration tests often can't run in parallel
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
        artifact = "com.google.protobuf:protoc:4.33.0"
    }
}

jmh {
    jvmArgs.set(listOf("-Xmx4g"))
}