---
name: browser-check
description: Check a change to the web frontend (visualizer, analyzer) in real browsers — scripted checks in headless Chrome over the DevTools protocol and headless Firefox over WebDriver BiDi, against a node that serves a run, and the result shown to the user in a browser. Use when asked to test, check, measure input for, or demonstrate a frontend change.
---

# Checking a frontend change in the browser

The web frontend has no automated tests. A change to it is checked by scripts that drive real
browsers — Chrome and Firefox both, since both must work — and then by the user, who tries it.
The scripts answer what a value decides (did the view load once, is the line on the minimap, does
a key do nothing in a text field); the user answers what can only be felt.

The project's working rules apply throughout (`AGENTS.md`, `CLAUDE.md`). Every process, profile
and file started for a check is removed afterwards. `drivers.py` beside this file is the browser
client the procedure below refers to.

## 0 · Serving the frontend

- The node serves the web files **from its classpath**: a running node shows the build it was
  started from. After a change, restart it — `./gradlew run --args="node show"` rebuilds the
  resources. A node started from `build/install` is stopped before `./gradlew installDist`; the
  JVM reads classes from the jar lazily and fails when it is replaced under it.
- `node show` serves the indexed runs without simulating. It reads `${user.dir}/data` unless
  `pipeline.dataBaseDir` says otherwise; `--config=<file>` before `node show` selects another
  configuration.
- One node at a time: it takes the ports 8081 (web), 8082 and 9092 (H2). `ss -ltnp` shows who
  holds them.
- Some behaviour shows only on large worlds (a minimap rectangle too small to see, the cost of a
  load), some only on small ones (the camera held at the world's edge). Pick the run for the
  question.
- To check new web files against a node that is already running and should stay untouched, a
  small local server can serve the working tree's `src/main/resources/web` and pass every other
  request (`/…/api/…`) on to the node.

## 1 · Browsers

- **A browser the user works in**, when it is reachable over a debugging port, is for showing a
  result, not for running checks: open a tab of your own, close only tabs you opened. A window
  that is covered, minimised or on another workspace may render no frames:
  `requestAnimationFrame` stops firing, the visualizer's start waits on it and hangs, and
  synthesized input stalls — a script that runs while the window is visible stops when the user
  looks elsewhere.
- **Scripted checks** run in headless instances of your own, which always render, each with a
  fresh profile and a free port:
  - Chrome: `<chrome> --headless=new --remote-debugging-port=<port> --user-data-dir=<dir> --window-size=1536,900 --no-first-run about:blank`
  - Firefox: `firefox --headless --no-remote --profile <dir> --remote-debugging-port <port> --window-size=1536,900`;
    WebDriver BiDi then listens on `ws://127.0.0.1:<port>/session`. It takes one session at a
    time; a session not ended blocks the next with "Maximum number of active sessions".
  - A browser in a sandbox (as Flatpak and Snap packages are) may not read temporary or hidden
    directories: put its profile where the sandbox can write, and serve pages over
    `http://127.0.0.1:<port>` rather than as `file://`.
- The client is Python in a temporary venv with `websockets` (`python3 -m venv <dir>`,
  `<dir>/bin/pip install websockets`). `drivers.py` gives both browsers one interface: `open`,
  `evaluate`, `sleep`, `wheel`, `key`, `touch`, `click`, `navigate`, `reload`, `errors`, `close`.
- Stop processes by the port they hold or by their PID. `pkill -f <pattern>` also matches the
  shell that runs it.

## 2 · What to check, and how

- **The live application.** `(await import('/visualizer/js/main.js')).appController` returns the
  running instance, since a module loads once per page; from it the renderer, the minimap and the
  state are reachable. Wait for readiness by polling a state (the minimap's data, the world shape)
  rather than sleeping a fixed time.
- **Work done.** Wrap the method whose calls matter inside the page (`renderer.loadViewport`,
  `appController.navigateToTick`) with a counter, then assert "nothing loaded during the gesture,
  one load after it".
- **Geometry.** Derive every expected value from the state — world shape, viewport, cell size —
  instead of fixed pixels: a check written against one run breaks on the next. Where the camera is
  held at the world's edge, or the world is smaller than the viewport, a point cannot stay under
  the pointer; test anchors where the world is larger than the view.
- **Drawings on a canvas.** Read pixels with `getImageData` (the minimap is a 2D canvas) and
  assert what is drawn where.
- **Input.** Chrome: `Input.dispatchMouseEvent` (`mouseWheel`; control held is modifier 2, the
  way a touchpad pinch arrives), `Input.dispatchKeyEvent` (`autoRepeat` for a held key),
  `Input.dispatchTouchEvent` after `Emulation.setTouchEmulationEnabled`. Firefox:
  `input.performActions` with wheel, key and pointer sources (`pointerType: touch` for fingers).
- **Console.** Collect `Runtime.exceptionThrown` and console errors (Chrome), `log.entryAdded`
  (Firefox); an unhandled rejection is a finding even when the view looks right.
- **Real devices.** Synthesized events are not what a real mouse, touchpad or pen sends. When a
  change depends on the values devices report (a heuristic, a threshold), ask the user to perform
  the gestures on a page that only logs the events — in both browsers — and base the change on
  those values. Check the logic against the measured values in isolation, not only against
  synthesized ones.
- **Design.** Render variants in the real page, not in a mock-up, and show them live in the
  user's browser; a small switch injected into the page lets them compare without a change to the
  repository. A screenshot (`Page.captureScreenshot`, device scale 2) is for your own critique
  before showing, not a replacement for showing.

## 3 · Known limits

- Headless Firefox has no touch device (`navigator.maxTouchPoints` is 0) and passes only the
  first of several fingers; two-finger gestures in Firefox need a real touch screen.
- WebDriver takes whole pixels for wheel deltas; fractional touchpad values cannot be sent to
  Firefox. `drivers.py` rounds and keeps the sign of small values.
- A synthesized click in Chrome needs a `mouseMoved` to its position first, and lands on whatever
  covers the point: panels lie over the canvas, and one that opens (the organism details after a
  selection) takes clicks meant for the world.
- Keyboard shortcuts with control can reach the browser itself (control-plus zooms the page);
  check a "modifier does nothing" rule with alt instead.
