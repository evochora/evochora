import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

// Line coverage restricted to the lines a change actually touches.
//
// The project-wide gate in jacocoTestCoverageVerification looks at the whole code base, where a
// single change is too small to move the ratio: a change can add several hundred untested lines
// without pushing the overall number below its bound. This task asks the other question — of the
// lines this branch adds or rewrites, how many does the test suite reach?
//
// It measures nothing itself. The data is the JaCoCo report the test run already produces, and the
// set of touched lines comes from `git diff` against the merge base, so the task only intersects
// two results that exist anyway.

/** Lines added or rewritten per source file, keyed by path relative to the repository root. */
fun changedLines(baseRef: String): Map<String, Set<Int>> {
    fun git(vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(rootDir)
            .redirectErrorStream(false)
            .start()
        // Both streams are drained at once. Reading one to its end while the other fills up would
        // stall the process the moment its buffer is full: it waits to write, this side waits to
        // read, and neither moves again. The streams stay apart because the diff must not have
        // anything of git's own reporting mixed into it.
        var err = ""
        val errReader = Thread { err = process.errorStream.bufferedReader().readText() }
        errReader.start()
        val out = process.inputStream.bufferedReader().readText()
        errReader.join()
        val code = process.waitFor()
        // A missing merge base is the common failure on CI, where checkouts are shallow by default
        // and the base branch is simply absent. Reporting zero changed lines there would turn the
        // gate into a silent pass, so it fails loudly instead.
        check(code == 0) {
            "git ${args.joinToString(" ")} failed with exit code $code: ${err.trim()}\n" +
                "If this runs on CI, the checkout needs the base branch: set fetch-depth: 0."
        }
        return out
    }

    val mergeBase = git("merge-base", "HEAD", baseRef).trim()
    val diff = git("diff", "--unified=0", "--no-color", mergeBase, "--", "*.java")

    val byFile = mutableMapOf<String, MutableSet<Int>>()
    var current: MutableSet<Int>? = null
    // Hunk headers carry the line range of the post-image: "@@ -12,0 +13,4 @@". A missing count
    // means one line.
    val hunk = Regex("""^@@ -\S+ \+(\d+)(?:,(\d+))? @@""")
    for (line in diff.lineSequence()) {
        when {
            line.startsWith("+++ ") -> {
                val path = line.removePrefix("+++ ").trim()
                // Deleted files have no post-image and nothing to cover.
                current = if (path == "/dev/null") null
                else byFile.getOrPut(path.removePrefix("b/")) { mutableSetOf() }
            }
            line.startsWith("@@") -> {
                val m = hunk.find(line) ?: continue
                val start = m.groupValues[1].toInt()
                val count = m.groupValues[2].ifEmpty { "1" }.toInt()
                repeat(count) { current?.add(start + it) }
            }
        }
    }
    return byFile.filterValues { it.isNotEmpty() }
}

/** Executable lines per source file from the JaCoCo report, mapped to whether they were reached. */
fun coveredLines(report: File): Map<String, Map<Int, Boolean>> {
    val doc = DocumentBuilderFactory.newInstance().apply {
        // The report declares a DTD that is not resolvable offline.
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }.newDocumentBuilder().parse(report)

    val result = mutableMapOf<String, Map<Int, Boolean>>()
    val packages = doc.getElementsByTagName("package")
    for (p in 0 until packages.length) {
        val pkg = packages.item(p) as Element
        val files = pkg.getElementsByTagName("sourcefile")
        for (f in 0 until files.length) {
            val file = files.item(f) as Element
            val path = "${pkg.getAttribute("name")}/${file.getAttribute("name")}"
            val lines = file.getElementsByTagName("line")
            val byLine = mutableMapOf<Int, Boolean>()
            for (l in 0 until lines.length) {
                val line = lines.item(l) as Element
                // Covered instructions above zero means the line was reached at least once.
                byLine[line.getAttribute("nr").toInt()] = line.getAttribute("ci").toInt() > 0
            }
            result[path] = byLine
        }
    }
    return result
}

tasks.register("newCodeCoverage") {
    group = "verification"
    description = "Line coverage of the lines this branch changes, measured against the merge base."

    val report = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
    // The same exclusions the project-wide gate uses, so both gates judge the same code.
    val excludedPrefixes = listOf("org/evochora/ui/", "org/evochora/Main")
    val sourceRoot = "src/main/java/"
    // The prefix carries a trailing slash so that removePrefix cuts cleanly; in a sentence the
    // path reads better without it.
    val sourceRootName = sourceRoot.trimEnd('/')
    val baseRef = (findProperty("newCodeCoverage.baseRef") ?: "origin/main").toString()
    val minimum = (findProperty("newCodeCoverage.minimum") as String?)?.toDouble()
    // A rendered summary for whoever reads the pull request rather than the build log.
    val summary = layout.buildDirectory.file("reports/new-code-coverage/summary.md")

    outputs.upToDateWhen { false }

    doLast {
        val reportFile = report.get().asFile
        check(reportFile.exists()) {
            "No JaCoCo report at ${reportFile.path}. Run `gradlew test jacocoTestReport` first."
        }

        val changed = changedLines(baseRef)
        val covered = coveredLines(reportFile)

        var total = 0
        var hit = 0
        val misses = sortedMapOf<String, List<Int>>()

        for ((path, lines) in changed) {
            if (!path.startsWith(sourceRoot)) continue
            val classPath = path.removePrefix(sourceRoot)
            if (excludedPrefixes.any { classPath.startsWith(it) }) continue
            // Files absent from the report contain no lines the coverage analysis knows about.
            val reported = covered[classPath] ?: continue

            // Only lines JaCoCo can observe count; blank lines, comments and declarations are
            // changed text but nothing a test could reach.
            val coverable = lines.filter { reported.containsKey(it) }
            if (coverable.isEmpty()) continue

            total += coverable.size
            hit += coverable.count { reported.getValue(it) }
            coverable.filterNot { reported.getValue(it) }
                .takeIf { it.isNotEmpty() }
                ?.let { misses[path] = it }
        }

        val summaryFile = summary.get().asFile
        summaryFile.parentFile.mkdirs()

        if (total == 0) {
            logger.lifecycle("New-code coverage: no coverable lines changed in $sourceRootName against $baseRef.")
            summaryFile.writeText(
                "## New-code coverage\n\nNo coverable lines changed in `$sourceRootName` against `$baseRef`.\n"
            )
            return@doLast
        }

        val ratio = hit.toDouble() / total
        // Locale.US throughout: the figure is read by a machine as often as by a person —
        // the pull request comment, the log line, the failure message — and a decimal comma
        // from a differently configured JVM would change what those texts say.
        logger.lifecycle(
            "New-code coverage: %.1f%% (%d of %d changed coverable lines) against %s"
                .format(Locale.US, ratio * 100, hit, total, baseRef)
        )
        if (misses.isNotEmpty()) {
            logger.lifecycle("Uncovered changed lines:")
            misses.forEach { (path, lines) -> logger.lifecycle("  $path: ${lines.joinToString(", ")}") }
        }

        summaryFile.writeText(buildString {
            append("## New-code coverage\n\n")
            append("**%.1f%%** of the lines this branch changes are covered ".format(Locale.US, ratio * 100))
            append("($hit of $total coverable lines, against `$baseRef`).\n")
            if (minimum != null) append("\nRequired: %.1f%%.\n".format(Locale.US, minimum * 100))
            if (misses.isNotEmpty()) {
                append("\n<details><summary>Uncovered changed lines")
                append(" (${misses.values.sumOf { it.size }})</summary>\n\n")
                misses.forEach { (path, lines) ->
                    append("- `$path`: ${lines.joinToString(", ")}\n")
                }
                append("\n</details>\n")
            }
        })

        // Without an explicit bound the task reports and does not judge, so it can be read for a
        // while before it is allowed to fail a build.
        if (minimum != null) {
            check(ratio >= minimum) {
                "New-code coverage %.1f%% is below the required %.1f%%."
                    .format(Locale.US, ratio * 100, minimum * 100)
            }
        }
    }
}
