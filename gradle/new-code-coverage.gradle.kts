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
    // Content is never mistaken for a header, because position decides rather than appearance.
    // Every file block opens with "diff --git", a line no content can imitate: content always
    // carries a leading '+', '-' or space. The headers sit between that line and the block's first
    // hunk, so once a hunk has been seen no "+++ " counts as a header again — which matters
    // because an added line beginning with "++ " reaches the diff looking exactly like one, and
    // reading it as a header would open a file that does not exist and swallow the hunks that
    // belong to the real one.
    var inHeaderSection = false
    for (line in diff.lineSequence()) {
        if (line.startsWith("diff --git ")) {
            inHeaderSection = true
            current = null
        }
        val isFileHeader = inHeaderSection && line.startsWith("+++ ")
        when {
            isFileHeader -> {
                val path = line.removePrefix("+++ ").trim()
                // Deleted files have no post-image and nothing to cover.
                current = if (path == "/dev/null") null
                else byFile.getOrPut(path.removePrefix("b/")) { mutableSetOf() }
            }
            line.startsWith("@@") -> {
                // The first hunk closes the header section; every later one is just a hunk.
                inHeaderSection = false
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
        // Every JaCoCo report opens with a doctype, so the declaration itself has to be allowed
        // and what it could pull in is shut off instead: the DTD it names is never fetched, no
        // entity may reach a file or a URL, and none is expanded, which also rules out an entity
        // that expands into itself.
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
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

    // The report is only as good as the run that produced it: measuring against execution data
    // from before the last edit gives line numbers that no longer match the source, and a figure
    // that is wrong without looking wrong. Gradle decides from file contents whether the tests
    // have to run again, so an unchanged tree costs nothing here. The task is named rather than
    // referenced: a script applied with apply(from = ...) has no type-safe task accessors.
    dependsOn("test")

    val report = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
    // Enough for the whole list in an ordinary change, far enough from GitHub's 65536-character
    // limit for a comment that even long paths cannot reach it.
    val maxListedLines = 50
    val sourceRoot = "src/main/java/"
    // The prefix carries a trailing slash so that removePrefix cuts cleanly; in a sentence the
    // path reads better without it.
    val sourceRootName = sourceRoot.trimEnd('/')
    val baseRef = (findProperty("newCodeCoverage.baseRef") ?: "origin/main").toString()
    // A share, not a percentage. Written as 80 it would ask for 8000% and fail every branch,
    // with a figure in the comment that says nothing about what went wrong.
    // Read as text rather than cast: a property set anywhere but the command line — gradle.properties,
    // an init script, the environment — need not arrive as a String, and a failed cast would land
    // before the check below ever states what a usable value looks like.
    val minimum = findProperty("newCodeCoverage.minimum")?.toString()?.let { given ->
        val value = given.toDoubleOrNull()
        require(value != null && value in 0.0..1.0) {
            "newCodeCoverage.minimum is the share of changed lines that has to be covered, " +
                "a value between 0 and 1 — 0.80 for 80 percent — but was \"$given\"."
        }
        value
    }
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
            // Files absent from the report contain no lines the coverage analysis knows about.
            // This is also what keeps the two gates over the same code: the report is built from
            // the class directories jacocoTestReport is given, so whatever that excludes is
            // already missing here and needs no second list to repeat it.
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
                val missed = misses.values.sumOf { it.size }
                append("\n<details><summary>Uncovered changed lines ($missed)</summary>\n\n")
                // A comment GitHub refuses for its length is worse than a shortened one, and the
                // branch that would reach the limit is the one whose figure is worth reading. The
                // count above stays complete, and the job log carries every line.
                var left = maxListedLines
                for ((path, lines) in misses) {
                    if (left == 0) break
                    val shown = lines.take(left)
                    left -= shown.size
                    append("- `$path`: ${shown.joinToString(", ")}")
                    append(if (shown.size < lines.size) ", …\n" else "\n")
                }
                if (missed > maxListedLines) {
                    append("\n… and ${missed - maxListedLines} more, in full in the job log.\n")
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
