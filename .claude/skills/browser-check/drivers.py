"""Browser drivers for scripted frontend checks: Chrome over the DevTools protocol, Firefox over
WebDriver BiDi, behind one interface.

Needs the `websockets` package (a temporary venv). Both drivers talk to an already running
browser started with a remote debugging port; see SKILL.md.

    drv = ChromeDriver(port)          # or FirefoxDriver(port): the browser's debugging port
    drv.open('http://localhost:8081/visualizer/')
    drv.sleep(5)
    app = drv.evaluate("(async () => (await import('/visualizer/js/main.js')).appController.state.runId)()")
    drv.wheel(700, 400, 0, -120)      # one notch of a mouse wheel, away from the user
    drv.close()
"""
import asyncio
import itertools
import json
import urllib.request

import websockets


class _Socket:
    """A JSON message socket with numbered requests; events arriving meanwhile are kept."""

    def __init__(self, url):
        self.loop = asyncio.new_event_loop()
        self.ws = self.loop.run_until_complete(websockets.connect(url, max_size=None))
        self.ids = itertools.count(1)
        self.events = []

    def request(self, message, is_event, is_reply):
        return self.loop.run_until_complete(self._request(message, is_event, is_reply))

    async def _request(self, message, is_event, is_reply):
        message['id'] = next(self.ids)
        await self.ws.send(json.dumps(message))
        while True:
            reply = json.loads(await self.ws.recv())
            if is_event(reply):
                self.events.append(reply)
            elif is_reply(reply, message['id']):
                return reply

    def sleep(self, seconds, is_event):
        """Waits, keeping the events that arrive meanwhile."""
        self.loop.run_until_complete(self._drain(seconds, is_event))

    async def _drain(self, seconds, is_event):
        end = self.loop.time() + seconds
        while (left := end - self.loop.time()) > 0:
            try:
                reply = json.loads(await asyncio.wait_for(self.ws.recv(), left))
                if is_event(reply):
                    self.events.append(reply)
            except asyncio.TimeoutError:
                return

    def close(self):
        self.loop.run_until_complete(self.ws.close())


class ChromeDriver:
    """One tab of a Chrome started with --remote-debugging-port."""
    name = 'chrome'

    def __init__(self, port):
        self.base = f'http://127.0.0.1:{port}'

    def open(self, url, touch=True):
        request = urllib.request.Request(self.base + '/json/new?about:blank', method='PUT')
        self.target = json.loads(urllib.request.urlopen(request).read())
        self.socket = _Socket(self.target['webSocketDebuggerUrl'])
        self.send('Runtime.enable')
        if touch:
            self.send('Emulation.setTouchEmulationEnabled', enabled=True, maxTouchPoints=5)
        self.send('Page.navigate', url=url)

    def send(self, method, **params):
        reply = self.socket.request({'method': method, 'params': params},
                                    lambda r: 'method' in r, lambda r, i: r.get('id') == i)
        if 'error' in reply:
            raise RuntimeError(f"{method}: {reply['error']}")
        return reply.get('result', {})

    def evaluate(self, expression):
        """Evaluates in the page, awaiting a promise; returns the value as JSON data."""
        result = self.send('Runtime.evaluate', expression=expression, awaitPromise=True, returnByValue=True)
        if 'exceptionDetails' in result:
            raise RuntimeError(json.dumps(result['exceptionDetails'])[:2000])
        return result['result'].get('value')

    def sleep(self, seconds):
        self.socket.sleep(seconds, lambda r: 'method' in r)

    def navigate(self, url):
        self.send('Page.navigate', url=url)

    def reload(self):
        self.send('Page.reload')

    def wheel(self, x, y, dx, dy, ctrl=False):
        self.send('Input.dispatchMouseEvent', type='mouseWheel', x=x, y=y, deltaX=dx, deltaY=dy,
                  modifiers=2 if ctrl else 0)

    def key(self, key, code, alt=False, repeat=False):
        params = dict(key=key, code=code, modifiers=1 if alt else 0, autoRepeat=repeat)
        self.send('Input.dispatchKeyEvent', type='keyDown', text=key, **params)
        self.send('Input.dispatchKeyEvent', type='keyUp', **params)

    def touch(self, kind, points):
        """kind: touchStart, touchMove or touchEnd; points: (x, y) per finger, none for touchEnd."""
        self.send('Input.dispatchTouchEvent', type=kind,
                  touchPoints=[{'x': x, 'y': y, 'id': i} for i, (x, y) in enumerate(points)])

    def click(self, x, y, count=1):
        self.send('Input.dispatchMouseEvent', type='mouseMoved', x=x, y=y)
        for n in range(1, count + 1):
            self.send('Input.dispatchMouseEvent', type='mousePressed', x=x, y=y, button='left', clickCount=n)
            self.send('Input.dispatchMouseEvent', type='mouseReleased', x=x, y=y, button='left', clickCount=n)

    def errors(self):
        out = []
        for event in self.socket.events:
            if event['method'] == 'Runtime.exceptionThrown':
                details = event['params']['exceptionDetails']
                out.append('EXC ' + (details.get('exception', {}).get('description') or details.get('text', ''))[:400])
            elif event['method'] == 'Runtime.consoleAPICalled' and event['params']['type'] in ('error', 'warning'):
                args = ' '.join(str(a.get('value', a.get('description', ''))) for a in event['params']['args'])
                out.append(event['params']['type'].upper() + ' ' + args[:400])
        return out

    def close(self):
        self.socket.close()
        urllib.request.urlopen(self.base + '/json/close/' + self.target['id'])


class FirefoxDriver:
    """One tab of a Firefox started with --remote-debugging-port, over WebDriver BiDi."""
    name = 'firefox'
    CONTROL = '\ue009'  # WebDriver's code for the control key
    ALT = '\ue00a'  # WebDriver's code for the alt key

    def __init__(self, port):
        self.socket = _Socket(f'ws://127.0.0.1:{port}/session')
        self.send('session.new', capabilities={})
        self.send('session.subscribe', events=['log.entryAdded'])
        self.fingers = 0

    def send(self, method, **params):
        reply = self.socket.request({'method': method, 'params': params},
                                    lambda r: r.get('type') == 'event', lambda r, i: r.get('id') == i)
        if reply.get('type') == 'error':
            raise RuntimeError(f"{method}: {reply.get('error')} {reply.get('message')}")
        return reply.get('result', {})

    def open(self, url, width=1536, height=813):
        self.context = self.send('browsingContext.create', type='tab')['context']
        self.send('browsingContext.setViewport', context=self.context, viewport={'width': width, 'height': height})
        self.navigate(url)

    def evaluate(self, expression):
        """Evaluates in the page, awaiting a promise; returns the value as plain Python data."""
        result = self.send('script.evaluate', expression=expression, target={'context': self.context},
                           awaitPromise=True, resultOwnership='none', serializationOptions={'maxObjectDepth': 10})
        if result.get('type') == 'exception':
            raise RuntimeError(json.dumps(result['exceptionDetails'])[:2000])
        return self._plain(result['result'])

    def _plain(self, value):
        kind = value.get('type')
        if kind in ('undefined', 'null'):
            return None
        if kind == 'array':
            return [self._plain(v) for v in value.get('value', [])]
        if kind == 'object':
            return {k if isinstance(k, str) else self._plain(k): self._plain(v) for k, v in value.get('value', [])}
        return value.get('value')

    def sleep(self, seconds):
        self.socket.sleep(seconds, lambda r: r.get('type') == 'event')

    def navigate(self, url):
        self.send('browsingContext.navigate', context=self.context, url=url, wait='complete')

    def reload(self):
        self.send('browsingContext.reload', context=self.context, wait='complete')

    def _actions(self, sources):
        self.send('input.performActions', context=self.context, actions=sources)

    @staticmethod
    def _whole(delta):
        # WebDriver takes whole pixels only; a small delta keeps its sign rather than vanishing
        return 0 if delta == 0 else int((1 if delta > 0 else -1) * max(1, round(abs(delta))))

    def wheel(self, x, y, dx, dy, ctrl=False):
        wheel = {'type': 'wheel', 'id': 'wheel', 'actions': [
            {'type': 'pause'},
            {'type': 'scroll', 'x': int(x), 'y': int(y), 'deltaX': self._whole(dx), 'deltaY': self._whole(dy)},
            {'type': 'pause'}]}
        sources = [wheel]
        if ctrl:
            sources.insert(0, {'type': 'key', 'id': 'keys', 'actions': [
                {'type': 'keyDown', 'value': self.CONTROL}, {'type': 'pause'}, {'type': 'keyUp', 'value': self.CONTROL}]})
        self._actions(sources)

    def key(self, key, code, alt=False, repeat=False):
        """code is implied by the key in WebDriver; a held key's repeat cannot be sent."""
        actions = [{'type': 'keyDown', 'value': key}, {'type': 'keyUp', 'value': key}]
        if alt:
            actions = [{'type': 'keyDown', 'value': self.ALT}] + actions + [{'type': 'keyUp', 'value': self.ALT}]
        self._actions([{'type': 'key', 'id': 'keys', 'actions': actions}])

    def touch(self, kind, points):
        """kind: touchStart, touchMove or touchEnd; points: (x, y) per finger, none for touchEnd."""
        finger = lambda i, actions: {'type': 'pointer', 'id': f'finger{i}',
                                     'parameters': {'pointerType': 'touch'}, 'actions': actions}
        if kind == 'touchStart':
            self.fingers = len(points)
            sources = [finger(i, [{'type': 'pointerMove', 'x': int(x), 'y': int(y)}, {'type': 'pointerDown', 'button': 0}])
                       for i, (x, y) in enumerate(points)]
        elif kind == 'touchMove':
            sources = [finger(i, [{'type': 'pointerMove', 'x': int(x), 'y': int(y)}]) for i, (x, y) in enumerate(points)]
        else:
            sources = [finger(i, [{'type': 'pointerUp', 'button': 0}]) for i in range(self.fingers)]
            self.fingers = 0
        self._actions(sources)
        if kind == 'touchEnd':
            self.send('input.releaseActions', context=self.context)

    def click(self, x, y, count=1):
        actions = [{'type': 'pointerMove', 'x': int(x), 'y': int(y)}]
        for _ in range(count):
            actions += [{'type': 'pointerDown', 'button': 0}, {'type': 'pointerUp', 'button': 0}]
        self._actions([{'type': 'pointer', 'id': 'mouse', 'parameters': {'pointerType': 'mouse'}, 'actions': actions}])

    def errors(self):
        return [event['params']['level'].upper() + ' ' + str(event['params'].get('text'))[:400]
                for event in self.socket.events
                if event.get('method') == 'log.entryAdded' and event['params'].get('level') in ('error', 'warn')]

    def close(self):
        """Closes the tab and ends the session, which Firefox otherwise keeps blocking the next."""
        self.send('browsingContext.close', context=self.context)
        self.send('session.end')
        self.socket.close()
