import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.JsonReportRenderer
import com.github.jk1.license.render.ReportRenderer
import com.github.jk1.license.render.TextReportRenderer
import com.google.protobuf.gradle.*
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import javax.inject.Inject
import org.gradle.jvm.application.scripts.TemplateBasedScriptGenerator
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.process.ExecOperations

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
    id("com.google.protobuf") version "0.10.0"
    id("me.champeau.jmh") version "0.7.3"
    id("pmd")
    id("com.github.node-gradle.node") version "7.1.0"
    id("com.github.jk1.dependency-license-report") version "3.1.5"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// JavaVersionGuard, which the start scripts run on a Java other than the runtime a distribution
// brings before they start Evochora on it, so that an older Java stops with a message naming the
// Java Evochora needs. An older Java can load the guard only when it is compiled for that older
// Java: the guard has a source set of its own, compiled for the oldest Java the compiler targets.
// That the compiler calls this target obsolete is known and not reported.
val launcher: SourceSet = sourceSets.create("launcher")
tasks.named<JavaCompile>(launcher.compileJavaTaskName) {
    options.release = 8
    options.compilerArgs.add("-Xlint:-options")
}

group = "org.evochora"
version = project.findProperty("RELEASE_TAG")?.toString() ?: "latest"

// Remove -SNAPSHOT suffix if it exists for local builds
if (version.toString().endsWith("-SNAPSHOT")) {
    version = version.toString().removeSuffix("-SNAPSHOT")
}

// The Java runtime the platform distributions bring, as Adoptium names the Temurin release
val temurinVersion = "25.0.5+8"
val temurinFeature = temurinVersion.substringBefore('.')

// The libraries with native code that publish, beside a main jar carrying the native library of
// every platform they support, a jar per platform carrying that platform's alone. The platform
// distributions take the latter in place of the main jar.
data class NativeLibrary(val group: String, val module: String, val version: String) {
    val mainJar: String = "$module-$version.jar"
}
val duckdbJdbc = NativeLibrary("org.duckdb", "duckdb_jdbc", "2.0.0.0")
val zstdJni = NativeLibrary("com.github.luben", "zstd-jni", "1.5.7-24")

repositories {
    mavenCentral()
    // DuckDB's own extension repository, for the extensions the analyzer's DuckDB-WASM loads; see
    // duckdbExtensions below
    ivy {
        url = uri("https://extensions.duckdb.org")
        patternLayout { artifact("[revision]/[classifier]/[module].duckdb_extension.[ext]") }
        metadataSources { artifact() }
        content { includeGroup("org.duckdb.extensions") }
    }
    // Adoptium's GitHub releases of Eclipse Temurin, for the Java runtime each platform's
    // distribution brings; see temurinVersion below. A release is named by the version with "+",
    // its files by the version with "_": the module carries the second, the revision the first.
    ivy {
        url = uri("https://github.com/adoptium/temurin${temurinFeature}-binaries/releases/download")
        patternLayout { artifact("jdk-[revision]/OpenJDK${temurinFeature}U-jre_[classifier]_hotspot_[module].[ext]") }
        metadataSources { artifact() }
        content { includeGroup("net.adoptium") }
    }
}

// Every dependency names an exact version, the newest stable one when it is updated. What a library
// brings along stays at the version that library chose; it is raised over that choice only for a
// stated reason, such as a security fix, which the comment at the pin names. After a version
// changes, gradle/verification-metadata.xml takes the checksums of what the build now downloads:
// ./gradlew --write-verification-metadata sha256 --refresh-dependencies check jmhJar installDist
dependencies {
    implementation("com.google.protobuf:protobuf-java:4.36.2")
    implementation("com.google.protobuf:protobuf-java-util:4.36.2") // For JSON conversion
    implementation("com.zaxxer:HikariCP:7.1.0") // High-performance JDBC connection pool
    implementation("it.unimi.dsi:fastutil:8.5.19") // High-performance primitive collections
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.24.0")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("io.rest-assured:rest-assured:6.3.0") // For API integration testing
    testImplementation("io.javalin:javalin-testtools:7.2.3")
    testImplementation("com.tngtech.archunit:archunit:1.5.1") // Package dependency rules
    testImplementation("com.tngtech.archunit:archunit-junit6:1.5.1") // Runs the rule classes, sharing one import
    testImplementation(launcher.output)
    
    
    // Explicitly declare test framework implementation dependencies for Gradle 9 compatibility
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.slf4j:slf4j-api:2.0.20")
    implementation("ch.qos.logback:logback-classic:1.6.5")
    // REMOVED: sqlite-jdbc - not used, H2 is the primary database
    implementation("com.h2database:h2:2.5.252")
    implementation("tools.jackson.core:jackson-databind:3.2.4")
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
    implementation("io.javalin:javalin:7.2.3")
    // The OpenAPI annotations of the controllers; the annotation processor generates the specification from them
    implementation("io.javalin.community.openapi:openapi-specification:7.2.3")
    annotationProcessor("io.javalin.community.openapi:openapi-annotation-processor:7.2.3")
    implementation("com.typesafe:config:1.4.9")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    // Apache Commons RNG for scientifically-validated RNG with state serialization
    implementation("org.apache.commons:commons-rng-simple:1.7")

    // Zstd compression library with bundled native binaries for cross-platform support
    implementation("${zstdJni.group}:${zstdJni.module}:${zstdJni.version}")

    // Caffeine high-performance cache for chunk caching in EnvironmentController
    implementation("com.github.ben-manes.caffeine:caffeine:3.3.1")

    // DuckDB JDBC driver for analytics
    implementation("${duckdbJdbc.group}:${duckdbJdbc.module}:${duckdbJdbc.version}")

    // ActiveMQ Artemis for high-performance messaging (Topics/Queues)
    implementation("org.apache.artemis:artemis-jakarta-server:2.57.0")
    implementation("org.apache.artemis:artemis-jakarta-client:2.57.0")

    // JMS session pooling for producer sends (eliminates TCP session creation overhead)
    implementation("org.messaginghub:pooled-jms:3.2.5")

    // Test fixtures: dependencies needed to compile the JUnit extension
    testFixturesImplementation(platform("org.junit:junit-bom:6.1.3"))
    testFixturesImplementation("org.junit.jupiter:junit-jupiter-api")
    testFixturesImplementation("ch.qos.logback:logback-classic:1.6.5")
    testFixturesImplementation("com.google.protobuf:protobuf-java:4.36.2")
    testFixturesImplementation("com.typesafe:config:1.4.9")

}

// zstd-jni and DuckDB load native libraries, which the JVM warns about and a future release blocks
// unless native access is enabled. Everything runs from the classpath, so it is enabled for the
// unnamed module as a whole. Every JVM that runs Evochora's code carries the flag: the start
// scripts of the distribution, the test tasks and the benchmarks.
val enableNativeAccess = "--enable-native-access=ALL-UNNAMED"

// Netty 4.1, which ActiveMQ Artemis brings along, allocates memory through sun.misc.Unsafe, which
// the JVM warns about and a future release denies. The permission is owed to that Netty alone:
// NettyUnsafeRulesTest fails once Artemis brings another line, and then this flag is checked.
// A Java that does not know the option rejects it before loading any class; the start scripts
// therefore check the Java with JavaVersionGuard, without this option, before they pass it. The
// option exists from Java 23 on, so the benchmark scripts, which may run an older side, leave it out.
val allowUnsafeMemoryAccess = "--sun-misc-unsafe-memory-access=allow"

application {
    mainClass.set("org.evochora.cli.CommandLineInterface")
    applicationDefaultJvmArgs = listOf("-Xmx8g", enableNativeAccess, allowUnsafeMemoryAccess)
}

// The start scripts are made from templates of our own in gradle/start-scripts: copies of Gradle's,
// with the choice of the Java to run on added - EVOCHORA_JAVA_HOME, else the runtime an archive
// brings in jre/, else JAVA_HOME - and, on Windows, every jar in lib as the classpath, whose list
// would make the command line too long. What a copy adds or replaces stands between the comments
// "Evochora: begin" and "Evochora: end"; the rest is Gradle's. Each copy is held to the checksum of the
// template of Gradle's it was made from: a Gradle whose template differs stops the build until the
// copy has been made anew from that template, with the marked parts set in again, and the checksum
// renewed.
val startScriptTemplates = layout.projectDirectory.dir("gradle/start-scripts")
val startScriptTemplateOrigins = mapOf(
    "unixStartScript.txt" to "81a2c1ec4cad61c1991e5a86329f928e406bc0f98bc98852be1b72ba9e81c6c0",
    "windowsStartScript.txt" to "c91840406db3b7efa91b66121f6e883c62533212a849f19de1ca794766168d9d",
)

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

tasks.withType<CreateStartScripts>().configureEach {
    val generators = mapOf(
        "unixStartScript.txt" to unixStartScriptGenerator as TemplateBasedScriptGenerator,
        "windowsStartScript.txt" to windowsStartScriptGenerator as TemplateBasedScriptGenerator,
    )
    val gradleTemplates = generators.mapValues { (_, generator) -> generator.template }
    generators.forEach { (name, generator) ->
        generator.template = resources.text.fromFile(startScriptTemplates.file(name))
    }
    inputs.dir(startScriptTemplates)
    doFirst {
        gradleTemplates.forEach { (name, template) ->
            if (sha256(template.asString()) != startScriptTemplateOrigins.getValue(name)) {
                throw GradleException(
                    "Gradle's start script template $name differs from the one $startScriptTemplates/$name " +
                        "was copied from: compare the copy with it and renew its checksum in build.gradle.kts"
                )
            }
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

// The web frontend is a source tree of its own, src/main/web, and goes into the jar under web/:
// the node serves it from the classpath there, apart from the other resources.
tasks.named<ProcessResources>("processResources") {
    from("src/main/web") { into("web") }
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

// The revision of the sources a build was made from, put on its classpath as
// evochora-build.properties and read there by org.evochora.BuildInfo. The engine writes that
// revision into the metadata of every run, because a run is reproduced exactly only by the build
// that wrote it; whoever reads a run compares the two and warns when they differ. Beside it the
// file holds the version of the build - the release tag, or "latest" - which the API documentation
// names.
//
// The value comes from the first of these that answers:
//   1. the git checkout — the commit hash, with "-dirty" appended when a tracked file carries
//      uncommitted changes;
//   2. a GIT_REVISION file in the project root — an experiment bundle ships one next to its source
//      archive, so a build made from the archive records the revision the archive came from;
//   3. "unknown" — no checkout and no such file, and the sources cannot be named.
//
// The value is resolved while the build is configured and registered as an input of the task, so
// the file is rewritten when the revision changes and left alone when it does not.
// Gradle runs the git commands of a value source itself and tracks them, which it does not allow
// for processes a build script starts on its own while it is configured.
abstract class BuildRevisionSource : ValueSource<String, BuildRevisionSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val projectDir: DirectoryProperty
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val projectDir = parameters.projectDir.get().asFile
        // Absent in source archives and container builds; in a worktree it is a file, not a directory.
        val head = if (projectDir.resolve(".git").exists()) gitOutput(projectDir, "rev-parse", "HEAD") else null
        if (!head.isNullOrBlank()) {
            // Only tracked files count: an untracked file is not part of the sources a checkout yields.
            // A status that cannot be taken leaves the tree's state unknown, not clean.
            val status = gitOutput(projectDir, "status", "--porcelain", "--untracked-files=no")
            return when {
                status == null -> "unknown"
                status.isBlank() -> head
                else -> head + "-dirty"
            }
        }
        val shipped = projectDir.resolve("GIT_REVISION")
        val recorded = if (shipped.isFile) shipped.readText().trim() else ""
        return recorded.ifBlank { "unknown" }
    }

    private fun gitOutput(projectDir: File, vararg arguments: String): String? = try {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            workingDir = projectDir
            commandLine("git", *arguments)
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        if (result.exitValue == 0) output.toString().trim() else null
    } catch (e: Exception) {
        null
    }
}

val buildRevision: String = providers.of(BuildRevisionSource::class) {
    parameters.projectDir.set(rootProject.layout.projectDirectory)
}.get()

// Written as a properties file by Gradle's own task, which escapes every value as the format
// requires and writes no timestamp: a release tag may hold any character a tag allows, and what
// is read back is exactly what was written
val writeBuildInfo = tasks.register<WriteProperties>("writeBuildInfo") {
    group = "build"
    description = "Records the revision of the sources and the version of the build on the classpath"
    destinationFile = layout.buildDirectory.file("generated/build-info/evochora-build.properties")
    property("revision", buildRevision)
    property("version", project.version.toString())
    // The Java the code is compiled for, which JavaVersionGuard requires of the Java it runs on
    property("requiredJava", java.toolchain.languageVersion.get().asInt())
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/build-info"))
}

// The libraries and fonts of the web frontend are served by the node itself rather than fetched
// from a CDN, so that the frontend works without internet access and no third party sees its use.
// package.json names every package at an exact version, the newest stable one
// when it is updated; what a package brings along stays at the version it chose, and
// package-lock.json holds all of them with their checksums, which npm ci verifies. Gradle fetches
// Node itself, so building needs nothing but Java; from Node 25 on, its Linux builds need the
// system library libatomic, so the major stays at 24. After a version in package.json changes:
// ./gradlew npmInstall -PnpmInstallCommand=install, then the checksums as described above.
node {
    download = true
    version = "24.22.1"
    npmInstallCommand = providers.gradleProperty("npmInstallCommand").orElse("ci")
}

val frontendModules = layout.projectDirectory.dir("node_modules")

// DuckDB-WASM imports apache-arrow, whose browser module in turn imports packages published only
// as CommonJS; esbuild joins them into the one module the analyzer loads. The metafile beside it
// names every source file that went into the bundle, and with it every package.
val duckDbBundleMeta = layout.buildDirectory.file("generated/duckdb-bundle-meta/meta.json")
val bundleDuckDb = tasks.register<com.github.gradle.node.npm.task.NpxTask>("bundleDuckDb") {
    dependsOn(tasks.named("npmInstall"))
    val out = layout.buildDirectory.file("generated/duckdb-bundle/duckdb-browser.mjs")
    command = "esbuild"
    args = listOf("@duckdb/duckdb-wasm", "--bundle", "--format=esm", "--minify",
        "--outfile=" + out.get().asFile.absolutePath,
        "--metafile=" + duckDbBundleMeta.get().asFile.absolutePath)
    inputs.file("package-lock.json")
    outputs.file(out)
    outputs.file(duckDbBundleMeta)
}

// The DuckDB extensions the analyzer loads, served from the node like the rest of the frontend.
// An extension is built for one DuckDB core: the version is the core inside the DuckDB-WASM of
// package.json and changes with it, else DuckDB fails to load the extension.
val duckdbCore = "v1.4.3"
val duckdbPlatform = "wasm_mvp"
val duckdbExtensions = configurations.create("duckdbExtensions") {
    isTransitive = false
    isCanBeConsumed = false
}
dependencies {
    duckdbExtensions("org.duckdb.extensions:parquet:$duckdbCore:$duckdbPlatform@wasm")
}

// The weights the pages use of each font
val servedFonts = mapOf(
    "doto" to listOf("900"),
    "ibm-plex-sans" to listOf("400", "500", "600"),
    "roboto-mono" to listOf("400", "500"),
)

// The npm packages the frontend serves, each with the directory under web/shared/vendor that
// serves it. webVendor copies a package's files into that directory and frontendLicenses its
// license files; the duckdb directory serves in addition every package the DuckDB bundle holds.
val servedPackages: Map<String, String> = linkedMapOf(
    "chart.js" to "chart.js",
    "pixi.js" to "pixi.js",
    "protobufjs" to "protobufjs",
    "redoc" to "redoc",
    "@duckdb/duckdb-wasm" to "duckdb",
    "@fontsource-variable/source-serif-4" to "fonts/source-serif-4",
) + servedFonts.keys.associate { "@fontsource/$it" to "fonts/$it" }

fun servedDirectory(npmPackage: String): String = servedPackages.getValue(npmPackage)

// The license and notice files of the packages the frontend serves, laid out as under
// web/shared/vendor: next to a library in its directory, or under licenses/<package> where one
// directory serves several packages. The node serves a package's files to every browser, so its
// license goes along with them. A package whose own directory holds no such file takes it from
// src/licenses/frontend/<package>, and one with neither stops the build. Beside them the task
// writes the overview of those packages that the distribution carries.
val frontendLicenseFileName = Regex("(?i)(licen[cs]e|notice|copying|copyrightnotice)(\\..+)?")
val frontendLicenses = tasks.register("frontendLicenses") {
    group = "build"
    description = "Collects the license files of the frontend's npm packages and lists the packages"
    dependsOn(bundleDuckDb)
    val modules = frontendModules.asFile
    val kept = layout.projectDirectory.dir("src/licenses/frontend").asFile
    val bundleMeta = duckDbBundleMeta
    val packages = servedPackages
    val extensionCore = duckdbCore
    val out = layout.buildDirectory.dir("generated/frontend-licenses")
    inputs.file("package-lock.json")
    inputs.file(bundleMeta)
    inputs.dir(kept)
    inputs.property("servedPackages", packages)
    outputs.dir(out)
    doLast {
        val root = out.get().asFile
        root.deleteRecursively()
        @Suppress("UNCHECKED_CAST")
        val bundleInputs = (JsonSlurper().parse(bundleMeta.get().asFile) as Map<String, Map<String, Any>>)
            .getValue("inputs").keys
        val bundled = bundleInputs.filter { "node_modules/" in it }.map { input ->
            val segments = input.substringAfterLast("node_modules/").split("/")
            if (segments[0].startsWith("@")) "${segments[0]}/${segments[1]}" else segments[0]
        }.toSet()
        val byDirectory = packages.entries.groupBy({ it.value }, { it.key }).mapValues { (directory, names) ->
            if (directory == packages.getValue("@duckdb/duckdb-wasm")) (names + bundled).distinct() else names
        }
        val overview = StringBuilder("Libraries the web frontend serves under web/shared/vendor/\n\n")
        byDirectory.forEach { (directory, names) ->
            names.forEach { name ->
                val own = modules.resolve(name).listFiles { file -> file.isFile && frontendLicenseFileName.matches(file.name) }
                val files = own.orEmpty().ifEmpty { kept.resolve(name).listFiles().orEmpty() }
                if (files.isEmpty()) {
                    throw GradleException("The npm package $name carries no license file, and $kept/$name holds none")
                }
                val target = if (names.size == 1) root.resolve("frontend/$directory") else root.resolve("frontend/$directory/licenses/$name")
                files.forEach { it.copyTo(target.resolve(it.name)) }
                val manifest = JsonSlurper().parse(modules.resolve("$name/package.json")) as Map<*, *>
                val repository = manifest["repository"].let { if (it is Map<*, *>) it["url"] else it }
                overview.append("${manifest["name"]} ${manifest["version"]}  ${manifest["license"]}  ")
                    .append("${manifest["homepage"] ?: repository}  -> $directory/\n")
            }
        }
        val extensionLicenses = kept.resolve("duckdb-extensions").listFiles().orEmpty()
            .filter { frontendLicenseFileName.matches(it.name) }
        if (extensionLicenses.isEmpty()) {
            throw GradleException("The DuckDB parquet extension carries no license file, and $kept/duckdb-extensions holds none")
        }
        extensionLicenses.forEach { it.copyTo(root.resolve("frontend/duckdb/extensions/${it.name}")) }
        overview.append("DuckDB parquet extension $extensionCore  MIT  https://duckdb.org  -> duckdb/extensions/\n")
        root.resolve("frontend-libraries.txt").writeText(overview.toString())
    }
}

val webVendor = tasks.register<Sync>("webVendor") {
    dependsOn(tasks.named("npmInstall"))
    into(layout.buildDirectory.dir("generated/web-vendor"))
    into("web/shared/vendor") {
        from(frontendLicenses) {
            include("frontend/**")
            eachFile { path = path.replaceFirst("vendor/frontend/", "vendor/") }
            includeEmptyDirs = false
        }
        from(bundleDuckDb) { include("*.mjs"); into(servedDirectory("@duckdb/duckdb-wasm")) }
        from(frontendModules.dir("chart.js/dist")) { include("chart.umd.min.js"); into(servedDirectory("chart.js")) }
        from(frontendModules.dir("pixi.js/dist")) { include("pixi.min.js"); into(servedDirectory("pixi.js")) }
        from(frontendModules.dir("protobufjs/dist")) { include("protobuf.min.js"); into(servedDirectory("protobufjs")) }
        from(frontendModules.dir("redoc/bundles")) { include("redoc.standalone.js"); into(servedDirectory("redoc")) }
        from(frontendModules.dir("@duckdb/duckdb-wasm/dist")) {
            include("duckdb-mvp.wasm", "duckdb-browser-mvp.worker.js")
            into(servedDirectory("@duckdb/duckdb-wasm"))
        }
        // DuckDB asks its extension repository for <version>/<platform>/<name>.duckdb_extension.wasm
        from(duckdbExtensions) {
            rename { "parquet.duckdb_extension.wasm" }
            into("duckdb/extensions/$duckdbCore/$duckdbPlatform")
        }
        // Each font's stylesheet for the weights the pages use and the woff2 files it names
        servedFonts.forEach { (font, weights) ->
            from(frontendModules.dir("@fontsource/$font")) {
                weights.forEach { include("$it.css", "files/*-$it-normal.woff2") }
                into(servedDirectory("@fontsource/$font"))
            }
        }
        from(frontendModules.dir("@fontsource-variable/source-serif-4")) {
            include("opsz.css", "opsz-italic.css", "files/*-opsz-normal.woff2", "files/*-opsz-italic.woff2")
            into(servedDirectory("@fontsource-variable/source-serif-4"))
        }
    }
}

sourceSets.main {
    resources.srcDir(webVendor)
}

// The web frontend's tests, src/test/web, run with vitest against the files webVendor provides.
val webTest = tasks.register<com.github.gradle.node.npm.task.NpmTask>("webTest") {
    description = "Runs the web frontend's tests with vitest."
    group = "verification"
    dependsOn(tasks.named("npmInstall"))
    val vendor = webVendor.map { it.destinationDir.resolve("web/shared/vendor") }
    val report = layout.buildDirectory.file("test-results/webTest/TEST-webTest.xml")
    args = report.map { listOf("test", "--", "--reporter=default", "--reporter=junit", "--outputFile.junit=" + it.asFile.absolutePath) }
    environment = vendor.map { mapOf("WEB_VENDOR_DIR" to it.absolutePath) }
    inputs.files(webVendor).withPropertyName("vendor")
    inputs.dir("src/main/web").withPropertyName("web")
    inputs.dir("src/test/web").withPropertyName("tests")
    inputs.files("package-lock.json", "vitest.config.mjs").withPropertyName("setup")
    outputs.file(report)
    // Starting DuckDB-WASM takes most of the tests' time and all the processor it gets. PMD and
    // JaCoCo run on in worker processes while the next task starts; beside them the tests wait for
    // the processor and slow those tasks in turn, so they run once both are done.
    mustRunAfter(tasks.withType<Pmd>(), tasks.withType<JacocoReport>())
}

tasks.named("check") {
    dependsOn(webTest)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(writeBuildInfo)
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "org.evochora.cli.CommandLineInterface"
    }
    from(launcher.output)
    // Dependencies werden über lib/ und den Classpath im Start-Skript geladen (installDist)
}

tasks.withType<Copy> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// The platform distribution archives carry the project version, which comes from RELEASE_TAG.
// The jars inside them do not: an archive names its version once, and the jars are never published
// on their own, so they keep the fixed names the documentation and the benchmark tooling refer to.
tasks.withType<Jar> {
    archiveVersion.set("")
}

// The main distribution, without a Java runtime, is installed for development by installDist and
// not published, so its archives are not built
tasks.named<Zip>("distZip") { enabled = false }
tasks.named<Tar>("distTar") { enabled = false }

// The licenses of the Java libraries the distribution ships. The plugin reports every library on
// the runtime classpath with all the licenses it declares, and extracts the license and notice
// files its jar carries. checkLicense stops the build at a library none of whose licenses is in
// gradle/licenses/allowed-licenses.json. A license taken into that list names its text in
// src/licenses/texts/ as "text", unless its text names the copyright holder, as BSD and MIT do:
// such a library ships the file its jar carries, or the one in src/licenses/java/<group>/.
// checkLicenseFiles requires one or the other for every library, and reports a text or a
// directory no library needs.
licenseReport {
    excludeBoms = true
    // A parent POM speaks for the organisation that publishes it, not for each library under it:
    // OW2's names Apache-2.0 for ASM, which is BSD-3. Only a library's own POM is taken.
    unionParentPomLicenses = false
    renderers = arrayOf<ReportRenderer>(
        TextReportRenderer("java-libraries.txt"),
        JsonReportRenderer("index.json", false),
    )
    filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer())
    allowedLicensesFile = layout.projectDirectory.file("gradle/licenses/allowed-licenses.json").asFile
}

val javaLicenseReport = tasks.named("generateLicenseReport")

val checkLicenseFiles = tasks.register("checkLicenseFiles") {
    group = "verification"
    description = "Checks that every library ships the text of a license it is allowed under, and that none is left over"
    dependsOn(javaLicenseReport)
    val report = layout.buildDirectory.dir("reports/dependency-license")
    val allowedLicenses = layout.projectDirectory.file("gradle/licenses/allowed-licenses.json").asFile
    val kept = layout.projectDirectory.dir("src/licenses/java").asFile
    val texts = layout.projectDirectory.dir("src/licenses/texts").asFile
    inputs.file(allowedLicenses)
    inputs.dir(kept)
    inputs.dir(texts)
    doLast {
        val reportDir = report.get().asFile
        @Suppress("UNCHECKED_CAST")
        val libraries = (JsonSlurper().parse(reportDir.resolve("index.json")) as Map<String, Any>)
            .getValue("dependencies") as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val textOf = ((JsonSlurper().parse(allowedLicenses) as Map<String, Any>)
            .getValue("allowedLicenses") as List<Map<String, Any?>>)
            .associate { it.getValue("moduleLicense") as String to it["text"] as String? }
        val keptGroups = kept.listFiles().orEmpty().map { it.name }.toSet()
        val licenseFileName = Regex("(?i)(LICENSE|COPYING|NOTICE).*")
        val withoutLicenseFile = kept.listFiles().orEmpty()
            .filter { group -> group.listFiles().orEmpty().none { it.isFile && licenseFileName.matches(it.name) } }
            .map { it.name }
        val namingTheHolder = Regex(".*\\b(BSD|MIT)\\b.*")
        val neededTexts = mutableSetOf<String>()
        // A library may name several licenses; it ships under one it is allowed under, so one of
        // those needs its text: the library's own file for a license naming the holder, else the
        // text the allowed list names. A library allowed under none is checkLicense's to report.
        val withoutText = libraries.filter { library ->
            @Suppress("UNCHECKED_CAST")
            val allowedHere = (library["moduleLicenses"] as List<Map<String, Any?>>)
                .mapNotNull { it["moduleLicense"] as String? }.filter { it in textOf }
            val (group, name) = (library["moduleName"] as String).split(":")
            val embedded = reportDir.listFiles().orEmpty()
                .any { it.isDirectory && it.name.startsWith("$name-${library["moduleVersion"]}") }
            val holderFile = allowedHere.any { namingTheHolder.matches(it) } && (embedded || group in keptGroups)
            val withText = allowedHere.mapNotNull { textOf[it] }.filter { texts.resolve(it).isFile }
            neededTexts += withText
            allowedHere.isNotEmpty() && !holderFile && withText.isEmpty()
        }.map { it["moduleName"] }
        val missingTexts = textOf.values.filterNotNull().filter { !texts.resolve(it).isFile }
        val unusedTexts = texts.listFiles().orEmpty().map { it.name } - neededTexts
        val leftOver = keptGroups - libraries.map { (it["moduleName"] as String).substringBefore(":") }.toSet()
        if (withoutText.isNotEmpty() || missingTexts.isNotEmpty() || unusedTexts.isNotEmpty() ||
            leftOver.isNotEmpty() || withoutLicenseFile.isNotEmpty()) {
            throw GradleException(
                "Libraries without the text of a license they are allowed under, neither a license file in " +
                    "their jar or in $kept nor a text in $texts: $withoutText. Texts the allowed licenses name " +
                    "that are missing from $texts: $missingTexts. Texts in $texts that no library needs: " +
                    "$unusedTexts. Directories in $kept that no library on the runtime classpath belongs to: " +
                    "$leftOver. Directories in $kept without a file named LICENSE, COPYING or NOTICE: " +
                    "$withoutLicenseFile"
            )
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("checkLicense"), checkLicenseFiles)
}

// The sources of the commit a distribution is built from, which every distribution carries under
// src/: the installation directory holds the runs made with it, and with the sources beside them
// runs, application and the code that made them are published as one. GIT_REVISION among them
// names the revision, so that a build made from them records it, as BuildRevisionSource reads it.
// They are the sources of the last commit; a build with changes not yet committed leaves those
// out, and its revision says "-dirty". Archiving needs a git checkout, and without one git stops
// the task.
val sourceArchive = tasks.register<Exec>("sourceArchive") {
    group = "distribution"
    description = "Packs the sources of the commit HEAD for the src/ directory of the distributions"
    val archive = layout.buildDirectory.file("sources/evochora-sources.tar").get().asFile
    inputs.property("revision", buildRevision)
    outputs.file(archive)
    workingDir = rootProject.projectDir
    commandLine("git", "archive", "--format=tar", "--add-virtual-file=GIT_REVISION:$buildRevision",
        "--output=$archive", "HEAD")
    doFirst { archive.parentFile.mkdirs() }
}

// Unpacked once, as the JRE is, so that the archives take files that keep the permissions git
// gives them
val unpackSources = tasks.register<Sync>("unpackSources") {
    from(tarTree(sourceArchive.map { it.outputs.files.singleFile }))
    into(layout.buildDirectory.dir("sources/src"))
}

// What every distribution carries beside its start scripts and its libraries
val distributionContents = copySpec {
    into("src") {
        from(unpackSources)
        // Archives get fixed permissions; a file git keeps executable, gradlew among them, stays
        // executable in the archive
        eachFile { if (file.canExecute()) permissions { unix("rwxr-xr-x") } }
    }
    from("assembly") {
        into("assembly")
    }
    from("config") {
        // config/ is where a machine keeps its own overrides; only the configuration
        // meant for everyone belongs in the distribution
        include("evochora.conf")
        into("config")
    }
    from("README.md")
    from("LICENSE")
    into("licenses") {
        from("src/licenses") { exclude("frontend/**") }
        from(javaLicenseReport) { include("java-libraries.txt") }
        from(javaLicenseReport) { include("*.jar/**"); into("java") }
        from(frontendLicenses)
    }
}

distributions {
    main {
        contents {
            with(distributionContents)
        }
    }
}

// The distributions that are published: one per supported platform, each bringing that
// platform's Java runtime in jre/ and, for each native library, the jar for that platform in place
// of the main jar. The main distribution, which brings neither, is what installDist builds for
// development.
data class TargetPlatform(
    /** The platform as the archive's name carries it */
    val classifier: String,
    /** The platform as Temurin's file names carry it */
    val temurin: String,
    /** The archive type Temurin publishes for the platform */
    val temurinExtension: String,
    /** For each native library, the classifier of its jar for the platform */
    val nativeJars: Map<NativeLibrary, String>,
    /** The JVM's os.name and os.arch on the platform, to find the host's distribution */
    val osName: String,
    val osArch: String,
) {
    val taskSuffix: String = classifier.split("-").joinToString("") { it.replaceFirstChar(Char::uppercase) }
    val distributionName: String = taskSuffix.replaceFirstChar(Char::lowercase)
    val zip: Boolean = osName == "windows"
}

val targetPlatforms = listOf(
    TargetPlatform("windows-x64", "x64_windows", "zip",
        mapOf(duckdbJdbc to "windows_amd64", zstdJni to "win_amd64"), "windows", "amd64"),
    TargetPlatform("macos-aarch64", "aarch64_mac", "tar.gz",
        mapOf(duckdbJdbc to "macos_arm64", zstdJni to "darwin_aarch64"), "mac", "aarch64"),
    TargetPlatform("linux-x64", "x64_linux", "tar.gz",
        mapOf(duckdbJdbc to "linux_amd64", zstdJni to "linux_amd64"), "linux", "amd64"),
    TargetPlatform("linux-aarch64", "aarch64_linux", "tar.gz",
        mapOf(duckdbJdbc to "linux_arm64", zstdJni to "linux_aarch64"), "linux", "aarch64"),
)

targetPlatforms.forEach { platform ->
    val jreArchive = configurations.create("jre${platform.taskSuffix}") {
        isTransitive = false
        isCanBeConsumed = false
    }
    val nativeJars = configurations.create("nativeJars${platform.taskSuffix}") {
        isTransitive = false
        isCanBeConsumed = false
    }
    dependencies {
        jreArchive("net.adoptium:${temurinVersion.replace('+', '_')}:$temurinVersion:${platform.temurin}@${platform.temurinExtension}")
        platform.nativeJars.forEach { (library, classifier) ->
            nativeJars("${library.group}:${library.module}:${library.version}:$classifier")
        }
    }

    // The runtime classpath with each native library's jar for the platform in place of its main
    // jar. A classpath without a main jar would mean the dependency changed its name, and the
    // platform's jar would land beside whatever replaced it.
    val runtimeClasspath = configurations.runtimeClasspath.get()
    val mainJars = platform.nativeJars.keys.map { it.mainJar }.toSet()
    val platformLibraries = files(runtimeClasspath.filter { it.name !in mainJars }, nativeJars)
    val checkMainJars = {
        val missing = mainJars - runtimeClasspath.map { it.name }.toSet()
        if (missing.isNotEmpty()) {
            throw GradleException("The runtime classpath holds no $missing for ${platform.classifier}'s jars to replace")
        }
    }

    // The JRE unpacked without its top directory: what the distribution carries as jre/
    val unpackJre = tasks.register<Sync>("unpackJre${platform.taskSuffix}") {
        from(jreArchive.elements.map { elements ->
            val archive = elements.single().asFile
            if (platform.zip) zipTree(archive) else tarTree(archive)
        })
        // The JRE carries read-only files, which a later run could not overwrite: every file keeps
        // the permissions the archive gives it, with write for its owner added
        eachFile {
            relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
            val fromArchive = permissions.toUnixNumeric()
            permissions { unix(fromArchive or "200".toInt(8)) }
        }
        includeEmptyDirs = false
        into(layout.buildDirectory.dir("jre/${platform.classifier}"))
    }

    // Where the sources and licenses of the JRE are, read from the release file it carries
    val javaRuntimeNotice = tasks.register("javaRuntimeNotice${platform.taskSuffix}") {
        dependsOn(unpackJre)
        val jreDir = layout.buildDirectory.dir("jre/${platform.classifier}")
        val home = if (platform.osName == "mac") "Contents/Home/" else ""
        val out = layout.buildDirectory.file("java-runtime-notice/${platform.classifier}/java-runtime.txt")
        // The release file is what the notice is written from, so a new JRE writes it anew
        inputs.file(jreDir.map { it.file("${home}release") })
        outputs.file(out)
        doLast {
            val release = Properties()
            jreDir.get().file("${home}release").asFile.inputStream().use { release.load(it) }
            fun field(key: String) = release.getProperty(key)?.trim('"')
                ?: throw GradleException("The JRE's release file has no $key")
            out.get().asFile.apply { parentFile.mkdirs() }.writeText(
                "Java runtime: ${field("IMPLEMENTOR")} ${field("IMPLEMENTOR_VERSION")}, in jre/\n" +
                    "  licenses: jre/${home}legal/\n" +
                    "  sources:  ${field("SOURCE_REPO").removeSuffix(".git")}/tree/${field("SOURCE").substringAfterLast("git:")}\n"
            )
        }
    }

    val startScripts = tasks.register<CreateStartScripts>("startScripts${platform.taskSuffix}") {
        doFirst { checkMainJars() }
        applicationName = "evochora"
        mainClass.set(application.mainClass)
        defaultJvmOpts = application.applicationDefaultJvmArgs
        classpath = files(tasks.jar) + platformLibraries
        // Apart from build/scripts, which the main distribution takes into its bin/ as a whole
        outputDir = layout.buildDirectory.dir("platform-scripts/${platform.classifier}").get().asFile
    }

    distributions.create(platform.distributionName) {
        distributionBaseName = "evochora"
        distributionClassifier = platform.classifier
        contents {
            with(distributionContents)
            // What Gradle takes into the main distribution by convention, plugins/ among it
            from("src/main/dist")
            into("bin") {
                from(startScripts)
                filePermissions { unix("rwxr-xr-x") }
            }
            into("lib") {
                from(tasks.jar)
                from(platformLibraries)
            }
            into("jre") {
                from(unpackJre)
                // Archives get fixed permissions, which would leave the JRE's programs unable to
                // run; a file the unpacked JRE can execute is executable in the archive as well
                eachFile { if (file.canExecute()) permissions { unix("rwxr-xr-x") } }
            }
            into("licenses") { from(javaRuntimeNotice) }
        }
    }
    tasks.named<Zip>("${platform.distributionName}DistZip") { enabled = platform.zip }
    tasks.named<Tar>("${platform.distributionName}DistTar") { enabled = !platform.zip }
}

// Builds the archive of every platform distribution, the ones the CI starts, the image is built
// from and a release publishes
tasks.register("platformDistributions") {
    group = "distribution"
    description = "Builds the archive of every platform distribution under build/distributions"
    targetPlatforms.forEach { platform ->
        dependsOn(if (platform.zip) "${platform.distributionName}DistZip" else "${platform.distributionName}DistTar")
    }
}

// Builds the platform distribution of the machine this build runs on and unpacks it under
// build/install, to try out what a user of that machine downloads. A machine no platform
// distribution covers fails only when the task runs, not while the build is configured, so that
// the project still loads there, in an IDE or for a task list.
tasks.register("installLocalPlatformDist") {
    group = "distribution"
    description = "Installs the platform distribution of this machine, with its Java runtime, under build/install"
    val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
    val osArch = System.getProperty("os.arch").lowercase(Locale.ROOT).let { if (it == "x86_64") "amd64" else it }
    val host = targetPlatforms.firstOrNull { osName.startsWith(it.osName) && osArch == it.osArch }
    if (host != null) {
        dependsOn("install${host.taskSuffix}Dist")
    }
    doFirst {
        if (host == null) {
            throw GradleException("No platform distribution for $osName/$osArch")
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
    // The editor extension's grammar is checked against the instruction set by a test. It lives
    // outside the source sets, so without this the suite stays up to date when the grammar
    // changes and the rule that guards it never runs.
    inputs.file("extensions/vscode/src/extension/syntaxes/evochora.tmLanguage.json")
        .withPropertyName("editorGrammar")
    maxHeapSize = "2g" // Increase heap size for tests
    jvmArgs("-Duser.language=en", "-Duser.country=US")
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    jvmArgs("-Xshare:off")
    jvmArgs(enableNativeAccess, allowUnsafeMemoryAccess)
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
    maxHeapSize = "2g" // Match test task heap size to avoid OOM
    jvmArgs("-Duser.language=en", "-Duser.country=US")
    jvmArgs("-Xshare:off")
    jvmArgs(enableNativeAccess, allowUnsafeMemoryAccess)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    // Explicitly configure classpath and test classes for Gradle 9 compatibility
    classpath = sourceSets.test.get().runtimeClasspath
    testClassesDirs = sourceSets.test.get().output.classesDirs
}

// Platform comparison - runs the scenario under src/test/resources/org/evochora/determinism/ and
// writes the fingerprint of its course to build/platform-fingerprint/. The build runs it on every
// platform it tests and compares the fingerprints; it is not part of check, because one platform
// alone has nothing to compare with.
tasks.register<JavaExec>("platformFingerprint") {
    group = "verification"
    description = "Run the platform comparison scenario and write its fingerprint"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("org.evochora.determinism.PlatformFingerprint")
    maxHeapSize = "2g"
    jvmArgs(enableNativeAccess, allowUnsafeMemoryAccess)
    args(
        providers.gradleProperty("platformFingerprint.lastTick").getOrElse("2000000"),
        layout.buildDirectory.file("platform-fingerprint/fingerprint.txt").get().asFile.path
    )
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
    jvmArgs(enableNativeAccess, allowUnsafeMemoryAccess)
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

// The source sets whose code coverage is measured, here alone: the coverage report and the
// coverage gate take their classes, the report also their sources, which newCodeCoverage reads
// from it. The classes are taken from the source set outputs so that the collection carries the
// tasks producing it: both JaCoCo tasks then depend on compilation directly, not only through the
// test task's execution data, and a build that skips the tests (-x test) still resolves its task
// graph.
val coveredSourceSets = listOf(sourceSets.main.get(), launcher)
val coveredClasses = files(coveredSourceSets.map { it.output.classesDirs }).asFileTree

jacoco {
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(coveredClasses)
    sourceDirectories.setFrom(coveredSourceSets.map { it.java.srcDirs })
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
        artifact = "com.google.protobuf:protoc:4.36.2"
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
    jmhVersion = "1.37"
    jvmArgs.set(listOf("-Xmx8g", enableNativeAccess, allowUnsafeMemoryAccess))
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
    toolVersion = "7.28.0"
    threads = 4
    // On by default, stated here because the CI workflow restores and saves the resulting
    // build/tmp/pmd*/incremental.cache between runs; without it that step would cache nothing.
    incrementalAnalysis = true
    isConsoleOutput = true
    ruleSetFiles = files("gradle/pmd/ruleset.xml")
    ruleSets = listOf()
    isIgnoreFailures = false
}

// PMD resolves the types of the JDK through the jrt-fs.jar on its auxiliary classpath. Given the
// toolchain's, it checks against the JDK the code is compiled with, whatever JVM PMD runs on.
val pmdJdkTypes = javaToolchains.launcherFor(java.toolchain)
    .map { it.metadata.installationPath.file("lib/jrt-fs.jar") }

tasks.withType<Pmd>().configureEach {
    // Generated protobuf sources: nobody edits them, and the generator will not follow these rules.
    exclude("**/org/evochora/datapipeline/api/contracts/**")
    classpath = (classpath ?: files()) + files(pmdJdkTypes)
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
val javadocLint = tasks.register("javadocLint") {
    dependsOn(tasks.named("javadoc"))
    val optionsFile = layout.buildDirectory.file("tmp/javadoc/javadoc.options")
    val lintDir = layout.buildDirectory.dir("tmp/javadocLint")
    val javadocTool = javaToolchains.javadocToolFor(java.toolchain).map { it.executablePath }
    inputs.file(optionsFile)
    // The options file names the sources by path only, so the sources and the classpath are
    // inputs of their own: the lint runs again when the content of a source changes.
    val javadocTask = tasks.named<Javadoc>("javadoc")
    inputs.files(javadocTask.map { it.source }).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("sources")
    inputs.files(javadocTask.map { it.classpath }).withPropertyName("classpath")
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
