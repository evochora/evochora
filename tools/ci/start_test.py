#!/usr/bin/env python3
"""Starts a platform distribution of Evochora the way a user does and checks that it works.

Unpacks the archive into a temporary directory and, from its top directory, checks three things:

1. ``bin/evochora --help`` succeeds on the Java the archive brings, whatever JAVA_HOME says.
2. With EVOCHORA_JAVA_HOME naming an older Java, the start is refused with the guard's message.
3. ``bin/evochora node run`` on the standard configuration with one plugin added runs until
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
import urllib.request
import zipfile
from pathlib import Path

WINDOWS = os.name == "nt"
STATUS_URL = "http://localhost:8081/pipeline/api/status"
ANSI = re.compile(r"\x1b\[[0-9;]*m")
LOG_PROBLEM = re.compile(r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d (WARN|ERROR)\b")
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
    it is then ended hard so that nothing stays behind.
    """
    if process.poll() is not None:
        return []
    if WINDOWS:
        sent = subprocess.run([sys.executable, "-I", "-c", WINDOWS_CTRL_C, str(process.pid)],
                              capture_output=True, text=True, timeout=60)
        try:
            process.wait(timeout=60)
            if sent.returncode != 0:
                return [f"sending Ctrl+C failed: {sent.stderr.strip()}"]
            return []
        except subprocess.TimeoutExpired:
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(process.pid)], capture_output=True)
            process.wait(timeout=60)
            return [f"the node did not stop within 60 s of Ctrl+C ({sent.stderr.strip() or 'sent'})"]
    os.killpg(process.pid, signal.SIGINT)
    try:
        process.wait(timeout=60)
        return []
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=60)
        return ["the node did not stop within 60 s of SIGINT"]


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
            in_error = []
            while time.monotonic() < deadline and process.poll() is None:
                time.sleep(5)
                status = pipeline_status() or status
                waiting = waiting_services(status)
                # A service leaves ERROR only through a new start, which nothing here asks for: the
                # outcome is settled, and waiting for ticks after it would only take the time out
                in_error = [s for s in (status or {}).get("services", []) if s["state"] == "ERROR"]
                if in_error:
                    print(f"node run: {[s['name'] for s in in_error]} in ERROR after "
                          f"{time.monotonic() - started:.0f} s", flush=True)
                    break
                if waiting == []:
                    counted = [s["name"] for s in status["services"] if "ticks_processed" in (s.get("metrics") or {})]
                    print(f"node run: {counted} processed ticks after {time.monotonic() - started:.0f} s", flush=True)
                    break
            if process.poll() is not None:
                problems.append(f"the node ended on its own with exit code {process.returncode}")
            if status is None:
                problems.append(f"the HTTP API never answered at {STATUS_URL}")
            elif in_error:
                problems += [f"service {s['name']} is in ERROR: {s.get('errors')}" for s in in_error]
            elif waiting is None:
                problems.append("no service reports processed ticks")
            elif waiting:
                problems.append(f"within {timeout:.0f} s these services processed no tick: {waiting}")
        finally:
            problems += stop(process)
    # Read once the node has ended, so that what it logs while it shuts down is checked as well
    text = log.read_text(encoding="utf-8", errors="replace")
    for line in ANSI.sub("", text).splitlines():
        if LOG_PROBLEM.match(line) or JVM_WARNING.match(line):
            problems.append(f"logged: {line}")
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
        for name, check in [("help", lambda: check_help(root)),
                            ("refusal", lambda: check_refusal(root, args.old_java)),
                            ("plugin", lambda: install_plugin(root, args.jdk, work / "plugin-classes")),
                            ("node run", lambda: check_node_run(root, args.timeout))]:
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
