#!/usr/bin/env python3
"""Starts a platform distribution of Evochora the way a user does and checks that it works.

Unpacks the archive into a temporary directory and, from its top directory, checks these things:

1. The top directory holds the files of the commit the archive was built from, as the repository
   holds them, and the GIT_REVISION that names the commit, and none of the files that only
   building the project needs.
2. Every relative link in a document of the archive leads to a file the archive holds, and a
   link that names a place in a Markdown document leads to a heading or an anchor it has.
3. On macOS, the system takes the Java the archive brings as notarized by Apple, so that an
   archive a browser downloaded starts without a warning.
4. ``bin/evochora --help`` succeeds on the Java the archive brings, whatever JAVA_HOME says.
5. With EVOCHORA_JAVA_HOME naming an older Java, the start is refused with the guard's message.
6. ``bin/evochora node run`` on the standard configuration with one plugin added runs until
   every service that counts processed ticks has processed some, so that data has passed through
   the whole pipeline; the HTTP API reports no service in ERROR; nothing is logged at WARN or
   ERROR, and the JVM prints no warning of its own, up to and including the node's shutdown; and
   the plugin has run. The plugin, start-test-plugin/StartTestPlugin.java, is built the way a user
   builds one, against the archive's lib/, and put into its plugins/.

The node is stopped the way a user stops it, with Ctrl+C, and the directory is removed afterwards. Uses the standard library only, so that
it runs on every runner as it is. Exits with 0 when every check passes, 1 otherwise.
"""

import argparse
import ctypes
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

WINDOWS = os.name == "nt"
MACOS = sys.platform == "darwin"
STATUS_URL = "http://localhost:8081/pipeline/api/status"
ANSI = re.compile(r"\x1b\[[0-9;]*m")
LOG_PROBLEM = re.compile(r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d (WARN|ERROR)\b")
# A link as Markdown writes it, and the scheme that marks one that leaves the archive
MARKDOWN_LINK = re.compile(r"\]\(([^)\s]+)\)")
URL_SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")
# What a fragment can name in a Markdown document: a heading, an anchor the text sets itself, and
# the fence that opens and closes a code block, inside which a line beginning with # is no heading
HEADING = re.compile(r"^#{1,6}\s+(.*?)\s*#*\s*$")
ANCHOR = re.compile(r'<a\s[^>]*\b(?:id|name)="([^"]+)"')
FENCE = re.compile(r"^\s*(```|~~~)")
# The JVM's own warnings, in the three forms it writes them: the JDK's at run time, the virtual
# machine's ("OpenJDK 64-Bit Server VM warning: ..."), and those of its unified logging, which
# begin with the uptime ("[0.012s][warning][gc] ..."); a line of the node's own log, which begins
# with its date, is none of them
JVM_WARNING = re.compile(r"^(?!\d{4}-\d\d-\d\d )(WARNING:|\S.* VM warning:|\[\d+[.,]\d+s\]\[warning\])")


def unpack(archive: Path, into: Path) -> Path:
    """Unpacks the archive and returns its single top directory."""
    if archive.name.endswith(".zip"):
        with zipfile.ZipFile(archive) as z:
            z.extractall(into)
    else:
        with tarfile.open(archive) as t:
            t.extractall(into, filter="tar")
    tops = [p for p in into.iterdir() if p.is_dir()]
    if len(tops) != 1:
        raise RuntimeError(f"expected one top directory in {archive.name}, found {[p.name for p in tops]}")
    return tops[0]


def start_script(root: Path) -> list:
    return [str(root / "bin" / "evochora.bat")] if WINDOWS else [str(root / "bin" / "evochora")]


def environment(**overrides) -> dict:
    env = {k: v for k, v in os.environ.items() if k != "EVOCHORA_JAVA_HOME"}
    env.update(overrides)
    return env


def check_sources(root: Path) -> list:
    problems = [f"the archive lacks {name}" for name in ("GIT_REVISION", "README.md", "docs", "src/main")
                if not (root / name).exists()]
    problems += [f"the archive holds {name}, which only building the project needs"
                 for name in ("build.gradle.kts", "gradlew") if (root / name).exists()]
    return problems


def anchors(document: Path) -> set:
    """Returns the fragments a link can name in a Markdown document.

    A heading gets the fragment GitHub forms from it: the text as it is rendered, in lower case,
    without anything but letters, digits, hyphens, underscores and spaces, and with a hyphen for
    every space. A heading whose fragment is taken gets a number appended, counted from 1. An
    anchor the text sets with ``<a id="...">`` is named as it is written.
    """
    found = set()
    taken = {}
    in_code = False
    for line in document.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            in_code = not in_code
            continue
        if in_code:
            continue
        found.update(ANCHOR.findall(line))
        heading = HEADING.match(line)
        if heading:
            # What is rendered of a link is its text, and of inline HTML nothing
            text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading.group(1))
            text = re.sub(r"<[^>]+>", "", text)
            fragment = re.sub(r"[^\w\- ]", "", text.strip().lower()).replace(" ", "-")
            count = taken.get(fragment, 0)
            taken[fragment] = count + 1
            found.add(fragment if count == 0 else f"{fragment}-{count}")
    return found


def check_links(root: Path) -> list:
    """Follows every relative link of every Markdown document outside the Java runtime and the licenses.

    A link leads to a file the archive holds, and its fragment, where it has one and the file is a
    Markdown document, to a heading or an anchor of that document. A link that is a fragment alone
    leads into the document it stands in.
    """
    problems = []
    known = {}
    for document in sorted(root.rglob("*.md")):
        if document.relative_to(root).parts[0] in ("jre", "lib", "licenses"):
            continue
        for number, line in enumerate(document.read_text(encoding="utf-8").splitlines(), 1):
            for target in MARKDOWN_LINK.findall(line):
                # A scheme marks a link that leaves the archive
                if URL_SCHEME.match(target):
                    continue
                link, _, fragment = target.partition("#")
                path = urllib.parse.unquote(link.split("?")[0])
                where = f"{document.relative_to(root).as_posix()}:{number}"
                if path.startswith("/"):
                    problems.append(f"{where} links to {target}, a path from the root of a web site")
                    continue
                destination = document.parent / path if path else document
                if not destination.exists():
                    problems.append(f"{where} links to {target}, which the archive does not hold")
                    continue
                if fragment and destination.is_file() and destination.suffix == ".md":
                    if destination not in known:
                        known[destination] = anchors(destination)
                    if urllib.parse.unquote(fragment) not in known[destination]:
                        problems.append(f"{where} links to {target}, but "
                                        f"{destination.relative_to(root).as_posix()} has no such heading or anchor")
    return problems


def check_notarization(root: Path) -> list:
    """Asks macOS whether Apple has notarized the Java the archive brings.

    A browser marks what it downloads as quarantined, and the Finder passes the mark on to what it
    unpacks. macOS starts a quarantined program without a warning only when its developer signed it
    and Apple notarized it. The Java runtime is what the start script executes, so it is the
    program this is asked for; the question goes to Apple over the network.
    """
    java = root / "jre" / "Contents" / "Home" / "bin" / "java"
    if not java.is_file():
        return [f"the archive holds no {java.relative_to(root).as_posix()}"]
    result = subprocess.run(["codesign", "--verify", "--check-notarization", "--test-requirement", "=notarized",
                             "--verbose=2", str(java)], capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        return [f"macOS does not take {java.relative_to(root).as_posix()} as notarized by Apple, so a downloaded "
                f"archive would not start without a warning: {result.stdout}{result.stderr}"]
    return []


def check_help(root: Path) -> list:
    result = subprocess.run(start_script(root) + ["--help"], cwd=root, env=environment(),
                            capture_output=True, text=True, timeout=120)
    # picocli colours the help wherever it takes the output for a terminal, which on Windows
    # includes a pipe in Git Bash or Cygwin; the colours are presentation, the text is the check
    if result.returncode != 0 or "Usage: evochora" not in ANSI.sub("", result.stdout):
        return [f"--help exited {result.returncode}: {result.stdout}{result.stderr}"]
    return []


def check_refusal(root: Path, old_java: str) -> list:
    result = subprocess.run(start_script(root) + ["--help"], cwd=root,
                            env=environment(EVOCHORA_JAVA_HOME=old_java),
                            capture_output=True, text=True, timeout=120)
    output = result.stdout + result.stderr
    if result.returncode != 1 or "Evochora needs Java" not in output:
        return [f"with EVOCHORA_JAVA_HOME={old_java}: exited {result.returncode}, expected 1 and the guard's message: {output}"]
    # The guard names the Java it runs on: the one EVOCHORA_JAVA_HOME names, not the archive's
    if old_java not in output and os.path.realpath(old_java) not in output:
        return [f"the refusal does not name the Java EVOCHORA_JAVA_HOME names ({old_java}): {output}"]
    return []


def pipeline_status():
    try:
        with urllib.request.urlopen(STATUS_URL, timeout=5) as response:
            return json.load(response)
    except (urllib.error.URLError, ConnectionError, TimeoutError, json.JSONDecodeError):
        return None


# Sends Ctrl+C to the console of the process whose id it is given, and to that console only: it
# leaves its own, attaches to that one and ignores Ctrl+C itself while it sends it to every process
# there. Run as a process of its own, so that this script keeps its console.
WINDOWS_CTRL_C = r"""
import ctypes, sys
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
kernel32.FreeConsole()
if not kernel32.AttachConsole(int(sys.argv[1])):
    sys.exit(f"AttachConsole failed: {ctypes.get_last_error()}")
kernel32.SetConsoleCtrlHandler(None, True)
if not kernel32.GenerateConsoleCtrlEvent(0, 0):
    sys.exit(f"GenerateConsoleCtrlEvent failed: {ctypes.get_last_error()}")
"""


def stop(process: subprocess.Popen) -> list:
    """Stops the node with Ctrl+C, as a user does, and returns what went wrong doing so.

    On Linux and macOS the node runs in a process group of its own, which gets SIGINT. Windows has
    no such signal: Ctrl+C reaches the processes of a console, so the node runs in a console of its
    own, and Ctrl+C is sent there alone. A node that does not end on Ctrl+C is a failure of the test;
    it is then ended hard so that nothing stays behind. A node that has already ended on its own
    is a failure as well, whenever it ended.
    """
    if process.poll() is not None:
        return [f"the node ended on its own with exit code {process.returncode}"]
    if WINDOWS:
        try:
            sent = subprocess.run([sys.executable, "-I", "-c", WINDOWS_CTRL_C, str(process.pid)],
                                  capture_output=True, text=True, timeout=60)
            failure = sent.stderr.strip() if sent.returncode != 0 else None
        except subprocess.TimeoutExpired:
            failure = "the sender did not finish within 60 s"
        try:
            process.wait(timeout=60)
            return [f"sending Ctrl+C failed: {failure}"] if failure else []
        except subprocess.TimeoutExpired:
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(process.pid)], capture_output=True)
            process.wait(timeout=60)
            return [f"the node did not stop within 60 s of Ctrl+C ({failure or 'sent'})"]
    os.killpg(process.pid, signal.SIGINT)
    try:
        process.wait(timeout=60)
        return []
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=60)
        return ["the node did not stop within 60 s of SIGINT"]


def problem_lines(text: str) -> list:
    """The lines of the node's log at WARN or ERROR and the JVM's own warnings."""
    return [line for line in ANSI.sub("", text).splitlines() if LOG_PROBLEM.match(line) or JVM_WARNING.match(line)]


def first_problem(text: str):
    """The first problem line of the log, or None while there is none."""
    lines = problem_lines(text)
    return lines[0] if lines else None


def waiting_services(status) -> list:
    """The services that count processed ticks and have processed none yet, or None while none counts."""
    counting = [s for s in (status or {}).get("services", []) if "ticks_processed" in (s.get("metrics") or {})]
    if not counting:
        return None
    return [s["name"] for s in counting if s["metrics"]["ticks_processed"] <= 0]


def take_ctrl_c() -> None:
    """Lets this process and the processes it starts take Ctrl+C again, as they do in cmd.

    Windows passes the ignoring of Ctrl+C on from a process to every process it starts, and Git
    Bash starts the programs it runs ignoring it; a node started from there would never see the
    Ctrl+C the test sends it.
    """
    if not ctypes.WinDLL("kernel32", use_last_error=True).SetConsoleCtrlHandler(None, False):
        raise OSError(f"SetConsoleCtrlHandler failed: {ctypes.get_last_error()}")


PLUGIN_SOURCE = Path(__file__).resolve().parent / "start-test-plugin" / "StartTestPlugin.java"
PLUGIN_CLASS = "starttest.StartTestPlugin"
PLUGIN_RAN = re.compile(r"Start test plugin ran at tick \d+")


def install_plugin(root: Path, jdk: Path, classes: Path) -> list:
    """Builds the start test's plugin against the archive's jars and puts it into its plugins/,
    writes a configuration that is the standard one with the plugin added, and returns what went
    wrong doing so."""
    exe = ".exe" if WINDOWS else ""
    (root / "plugins").mkdir(exist_ok=True)
    for command in ([str(jdk / "bin" / f"javac{exe}"), "-cp", str(root / "lib" / "*"), "-d", str(classes),
                     str(PLUGIN_SOURCE)],
                    [str(jdk / "bin" / f"jar{exe}"), "--create", "--file",
                     str(root / "plugins" / "start-test-plugin.jar"), "-C", str(classes), "."]):
        result = subprocess.run(command, capture_output=True, text=True, timeout=120)
        if result.returncode != 0:
            return [f"{Path(command[0]).name} exited {result.returncode}: {result.stdout}{result.stderr}"]
    (root / "config" / "start-test.conf").write_text(
        'include "evochora.conf"\n'
        "pipeline.services.simulation-engine.options.plugins += "
        f'{{ className = "{PLUGIN_CLASS}", options {{}} }}\n', encoding="utf-8")
    return []


def check_node_run(root: Path, timeout: float) -> list:
    log = root / "node.log"
    problems = []
    with open(log, "w", encoding="utf-8", errors="replace") as out:
        # On Windows the node gets a console of its own, so that Ctrl+C can be sent to it alone, and
        # no input, so that cmd answers its "Terminate batch job?" question with nothing to wait for
        process = subprocess.Popen(
            start_script(root) + ["--config", "config/start-test.conf", "node", "run"], cwd=root, env=environment(),
            stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT,
            start_new_session=not WINDOWS,
            creationflags=subprocess.CREATE_NEW_CONSOLE if WINDOWS else 0)
        try:
            started = time.monotonic()
            deadline = started + timeout
            status = None
            waiting = None
            logged_problem = None
            while time.monotonic() < deadline and process.poll() is None:
                time.sleep(5)
                status = pipeline_status() or status
                waiting = waiting_services(status)
                # A line at WARN or ERROR, or a warning of the JVM, fails the run whatever follows:
                # the outcome is settled, and waiting for ticks after it would only take the time out
                logged_problem = first_problem(log.read_text(encoding="utf-8", errors="replace"))
                if logged_problem:
                    print(f"node run: logged a problem after {time.monotonic() - started:.0f} s", flush=True)
                    break
                if waiting == []:
                    counted = [s["name"] for s in status["services"] if "ticks_processed" in (s.get("metrics") or {})]
                    print(f"node run: {counted} processed ticks after {time.monotonic() - started:.0f} s", flush=True)
                    break
            # Ticks that did not come after a logged problem are its consequence; the problem itself
            # is reported with the log below
            if status is None:
                problems.append(f"the HTTP API never answered at {STATUS_URL}")
            elif not logged_problem and waiting is None:
                problems.append("no service reports processed ticks")
            elif not logged_problem and waiting:
                problems.append(f"within {timeout:.0f} s these services processed no tick: {waiting}")
            for service in (status or {}).get("services", []):
                if service["state"] == "ERROR":
                    problems.append(f"service {service['name']} is in ERROR: {service.get('errors')}")
        finally:
            problems += stop(process)
    # Read once the node has ended, so that what it logs while it shuts down is checked as well
    text = log.read_text(encoding="utf-8", errors="replace")
    problems += [f"logged: {line}" for line in problem_lines(text)]
    if not PLUGIN_RAN.search(text):
        problems.append("the plugin in plugins/ did not run")
    if problems:
        problems.append("log of the node run:\n" + text)
    return problems


def remove(directory: Path) -> list:
    """Removes the directory and returns what went wrong doing so.

    On Windows a file cannot be removed while any process holds it open, and a virus scanner or the
    index service may still be reading the files just unpacked. A lock there is waited out for a
    while, with a growing pause, as Python's own test support does; a lock that holds on longer, or
    any other failure, is reported. Elsewhere an open file can be removed, and one attempt is made.
    """
    deadline = time.monotonic() + 60
    pause = 0.1
    while True:
        try:
            shutil.rmtree(directory)
            return []
        except PermissionError as e:
            if not WINDOWS or time.monotonic() + pause > deadline:
                return [f"could not remove {directory}: {e}"]
            time.sleep(pause)
            pause = min(pause * 2, 5)
        except OSError as e:
            return [f"could not remove {directory}: {e}"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("archive", type=Path, help="the platform distribution to start")
    parser.add_argument("--old-java", required=True, help="installation directory of a Java older than Evochora needs")
    parser.add_argument("--jdk", required=True, type=Path, help="installation directory of the JDK the plugin is built with")
    parser.add_argument("--timeout", type=float, default=600, help="seconds the node run may take")
    args = parser.parse_args()
    if WINDOWS:
        take_ctrl_c()

    work = Path(tempfile.mkdtemp(prefix="evochora-start-test-"))
    problems = []
    try:
        root = unpack(args.archive.resolve(), work)
        checks = [("sources", lambda: check_sources(root)),
                  ("links", lambda: check_links(root))]
        if MACOS:
            checks.append(("notarization", lambda: check_notarization(root)))
        checks += [("help", lambda: check_help(root)),
                   ("refusal", lambda: check_refusal(root, args.old_java)),
                   ("plugin", lambda: install_plugin(root, args.jdk, work / "plugin-classes")),
                   ("node run", lambda: check_node_run(root, args.timeout))]
        for name, check in checks:
            found = check()
            print(f"{name}: {'ok' if not found else 'FAILED'}", flush=True)
            problems += [f"[{name}] {p}" for p in found]
    finally:
        problems += [f"[cleanup] {p}" for p in remove(work)]
    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
