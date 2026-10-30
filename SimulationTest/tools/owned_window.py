"""Owned-window input guard; imports are inert, no legacy activation wrapper."""
import ctypes
from ctypes import wintypes as W
from pathlib import Path
import time

class GuardFailure(RuntimeError):
    pass

def validate_identity(expected, state, foreground=True, size=True):
    for snapshot in (expected, state):
        thread = snapshot.get('windowThreadId')
        if type(thread) is not int or not 0 < thread <= 0xffffffff:
            raise GuardFailure('owned-window-thread-unavailable')
    for key in ('pid', 'startTicks', 'path', 'hwnd', 'title', 'windowThreadId'):
        a, b = expected[key], state[key]
        if key == 'path':
            a, b = str(a).casefold(), str(b).casefold()
        if a != b:
            raise GuardFailure('owned-' + key + '-changed')
    if not state['alive'] or not state['visible']:
        raise GuardFailure('owned-window-inactive')
    if foreground and state['foreground'] != expected['hwnd']:
        raise GuardFailure('owned-window-lost-focus')
    if size and (state['width'], state['height']) != (expected['width'], expected['height']):
        raise GuardFailure('owned-client-size-changed')
    if state['width'] <= 0 or state['height'] <= 0:
        raise GuardFailure('invalid-client-bounds')
    if state.get('matchingWindows', 1) != 1:
        raise GuardFailure('owned-window-not-unique')
    return state

def validate(expected, state, foreground=True, size=True):
    state = validate_identity(expected, state, foreground, size)
    qualified = expected.get('qualifiedKeyboardLayout')
    actual = state.get('keyboardLayout')
    if type(qualified) is not int or qualified <= 0 or qualified & 0xffff != 0x409:
        raise GuardFailure('owned-full-keyboard-layout-not-qualified')
    if type(actual) is not int or actual != qualified or state.get('keyboardLanguage') != 0x409:
        raise GuardFailure('owned-keyboard-layout-changed')
    return state

class Guard:
    def __init__(self, api, expected, record, sleep=time.sleep):
        self.api, self.expected, self.record, self.sleep = api, dict(expected), record, sleep
        self.held = []

    def check(self, foreground=True, size=True):
        state = validate(self.expected, self.api.snapshot(), foreground, size)
        self.record({'kind': 'owned-guard-check', 'requireForeground': foreground, 'requireSize': size, 'window': state})
        return state

    def check_identity(self, foreground=False, size=True):
        state = validate_identity(self.expected, self.api.snapshot(), foreground, size)
        self.record({'kind': 'owned-identity-check', 'requireForeground': foreground, 'requireSize': size, 'window': state})
        return state

    def settle(self, seconds):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            self.check()
            self.sleep(min(.03, max(0, end - time.monotonic())))
        return self.check()

    def move(self, x, y):
        before = self.check()
        if not (0 <= x < before['width'] and 0 <= y < before['height']):
            raise GuardFailure('input-outside-owned-client')
        self.api.move(before['x'] + x, before['y'] + y)
        after = self.check()
        if (before['x'], before['y']) != (after['x'], after['y']):
            raise GuardFailure('owned-client-moved')
        if not self.api.point_owned(before['x'] + x, before['y'] + y):
            raise GuardFailure('owned-point-occluded')
        self.record({'kind': 'cursor', 'x': x, 'y': y, 'window': after})

    def down(self, kind, value):
        state = self.check()
        if kind == 'mouse': self.check_pointer(state)
        item = (kind, value)
        if item in self.held or self.api.pressed(kind, value):
            raise GuardFailure('input-already-held')
        self.api.send(kind, value, False)
        self.held.append(item)
        self.record({'kind': 'owned-input-down', 'inputKind': kind, 'value': value})
        self.check()

    def up(self, kind, value):
        state = self.check()
        if kind == 'mouse': self.check_pointer(state)
        item = (kind, value)
        if item not in self.held:
            raise GuardFailure('release-not-owned')
        self.api.send(kind, value, True)
        self.held.remove(item)
        self.record({'kind': 'owned-input-up', 'inputKind': kind, 'value': value})
        self.check()

    def check_pointer(self, state):
        x, y = self.api.cursor()
        if not (state['x'] <= x < state['x'] + state['width'] and state['y'] <= y < state['y'] + state['height']):
            raise GuardFailure('current-cursor-outside-owned-client')
        if not self.api.point_owned(x, y): raise GuardFailure('current-owned-point-occluded')

    def release_owned(self):
        # Failure cleanup emits only matching UPs for successful own DOWNs.
        errors = []
        while self.held:
            kind, value = self.held.pop()
            try:
                self.api.send(kind, value, True)
                self.record({'kind': 'release-own-held', 'inputKind': kind, 'value': value})
            except Exception as error:
                errors.append(str(error))
        if errors:
            self.record({'kind': 'release-own-held-errors', 'errors': errors})
        return errors

    def click(self, x, y, button='left'):
        if button not in ('left', 'right'):
            raise GuardFailure('unsupported-mouse-button')
        try:
            self.move(x, y); self.settle(.06)
            self.down('mouse', button); self.settle(.06)
            self.up('mouse', button); self.settle(.08)
        finally:
            self.release_owned()

    def key(self, vk, ctrl=False):
        if vk not in (27, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 80, 83):
            raise GuardFailure('unregistered-key')
        try:
            if ctrl: self.down('key', 17)
            self.down('key', vk); self.settle(.06); self.up('key', vk)
            if ctrl: self.up('key', 17)
            self.settle(.08)
        finally:
            self.release_owned()

    def gesture(self, x, y, end_x, end_y, held_keys=()):
        if any(k not in (49, 50) for k in held_keys):
            raise GuardFailure('unregistered-held-key')
        try:
            self.move(x, y); self.down('mouse', 'left'); self.settle(.08)
            for vk in held_keys:
                self.down('key', vk); self.settle(.06); self.up('key', vk); self.settle(.15)
            self.move(end_x, end_y); self.settle(.08); self.up('mouse', 'left'); self.settle(.08)
        finally:
            self.release_owned()

    def resize(self, width, height):
        if (width, height) not in ((960, 640), (1200, 800)):
            raise GuardFailure('unregistered-size')
        self.check()
        self.api.resize(width, height, flags=0x0016)  # NOMOVE | NOZORDER | NOACTIVATE
        end = time.monotonic() + 3
        while time.monotonic() < end:
            state = self.check(size=False)
            if (state['width'], state['height']) == (width, height):
                self.expected.update(width=width, height=height)
                self.record({'kind': 'resized-owned-no-activation', 'window': self.check()})
                return
            self.sleep(.03)
        raise GuardFailure('resize-not-observed')

    def capture(self, path):
        path = Path(path)
        if path.exists():
            raise FileExistsError('never-overwrite-capture')
        before = self.check()
        bitmap = self.api.capture((before['x'], before['y'], before['x'] + before['width'], before['y'] + before['height']))
        after = self.check()
        if before != after:
            raise GuardFailure('capture-window-changed')
        bitmap.save(path)
        self.record({'kind': 'captured-owned-no-activation', 'path': str(path), 'window': after})

    def close_owned(self):
        self.check_identity(foreground=False)
        self.api.close_message()
        self.record({'kind': 'normal-WM_CLOSE-sent', 'pid': self.expected['pid'], 'hwnd': self.expected['hwnd']})

class Win32:
    """Construct only after explicit grant and resource acquisition."""
    def __init__(self, process, java_path, title):
        self.process, self.java_path, self.title = process, str(java_path), title
        self.u, self.k = ctypes.WinDLL('user32', use_last_error=True), ctypes.WinDLL('kernel32', use_last_error=True)
        self.u.SetProcessDPIAware()
        self.u.GetForegroundWindow.restype = W.HWND
        self.u.IsWindow.argtypes = [W.HWND]
        self.u.IsWindowVisible.argtypes = [W.HWND]
        self.u.GetWindowThreadProcessId.argtypes = [W.HWND, ctypes.POINTER(W.DWORD)]
        self.u.GetWindowThreadProcessId.restype = W.DWORD
        self.u.GetWindowTextW.argtypes = [W.HWND, W.LPWSTR, ctypes.c_int]
        self.u.GetClientRect.argtypes = [W.HWND, ctypes.POINTER(W.RECT)]
        self.u.GetWindowRect.argtypes = [W.HWND, ctypes.POINTER(W.RECT)]
        self.u.ClientToScreen.argtypes = [W.HWND, ctypes.POINTER(W.POINT)]
        self.u.GetCursorPos.argtypes = [ctypes.POINTER(W.POINT)]
        self.u.SetWindowPos.argtypes = [W.HWND, W.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, W.UINT]
        self.u.SetForegroundWindow.argtypes = [W.HWND]
        self.u.PostMessageW.argtypes = [W.HWND, W.UINT, W.WPARAM, W.LPARAM]
        self.u.GetAncestor.argtypes = [W.HWND, W.UINT]; self.u.GetAncestor.restype = W.HWND
        self.u.WindowFromPoint.argtypes = [W.POINT]; self.u.WindowFromPoint.restype = W.HWND
        self.u.GetKeyboardLayout.argtypes = [W.DWORD]; self.u.GetKeyboardLayout.restype = W.HANDLE
        self.u.GetKeyboardLayoutList.argtypes = [ctypes.c_int, ctypes.POINTER(W.HANDLE)]
        self.u.GetKeyboardLayoutList.restype = ctypes.c_int
        self.k.OpenProcess.argtypes = [W.DWORD, W.BOOL, W.DWORD]; self.k.OpenProcess.restype = W.HANDLE
        self.k.GetProcessTimes.argtypes = [W.HANDLE, ctypes.POINTER(W.FILETIME), ctypes.POINTER(W.FILETIME), ctypes.POINTER(W.FILETIME), ctypes.POINTER(W.FILETIME)]
        self.k.QueryFullProcessImageNameW.argtypes = [W.HANDLE, W.DWORD, W.LPWSTR, ctypes.POINTER(W.DWORD)]
        self.k.CloseHandle.argtypes = [W.HANDLE]
        self.handle = self.k.OpenProcess(0x1000 | 0x100000, False, process.pid)
        if not self.handle: raise GuardFailure('cannot-bind-own-process')
        self.hwnd = None; self.bound_state = None; self.input_state = None
        self.initial_focus_used = False; self.language_request_used = False
        class Mouse(ctypes.Structure):
            _fields_ = [('dx', W.LONG), ('dy', W.LONG), ('mouseData', W.DWORD), ('dwFlags', W.DWORD), ('time', W.DWORD), ('dwExtraInfo', ctypes.c_size_t)]
        class Keyboard(ctypes.Structure):
            _fields_ = [('wVk', W.WORD), ('wScan', W.WORD), ('dwFlags', W.DWORD), ('time', W.DWORD), ('dwExtraInfo', ctypes.c_size_t)]
        class Data(ctypes.Union):
            _fields_ = [('mi', Mouse), ('ki', Keyboard)]
        class Input(ctypes.Structure):
            _anonymous_ = ('data',)
            _fields_ = [('type', W.DWORD), ('data', Data)]
        self.Input = Input
        if ctypes.sizeof(Input) != 40:
            raise GuardFailure('requires-windows-x64-input-layout')
        self.u.SendInput.argtypes = [W.UINT, ctypes.POINTER(Input), ctypes.c_int]
        self.u.SendInput.restype = W.UINT

    def candidates(self):
        result = []
        callback_type = ctypes.WINFUNCTYPE(W.BOOL, W.HWND, W.LPARAM)
        @callback_type
        def callback(hwnd, unused):
            pid = W.DWORD(); self.u.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            text = ctypes.create_unicode_buffer(1024); self.u.GetWindowTextW(hwnd, text, 1024)
            if self.u.IsWindowVisible(hwnd) and pid.value == self.process.pid and text.value == self.title:
                result.append(int(hwnd))
            return True
        self.u.EnumWindows.argtypes = [callback_type, W.LPARAM]
        self.u.EnumWindows(callback, 0)
        return result

    def bind(self):
        windows = self.candidates()
        if len(windows) != 1: raise GuardFailure('expected-one-owned-window')
        self.hwnd = windows[0]
        bound = self.snapshot()
        if bound['pid'] != self.process.pid or bound['path'].casefold() != self.java_path.casefold() or bound['title'] != self.title:
            raise GuardFailure('initial-binding-does-not-match-owned-process')
        validate_identity(bound, bound, foreground=False)
        self.bound_state = dict(bound)
        return dict(bound)

    def initial_focus_once(self, allowed):
        if not allowed or self.initial_focus_used: raise GuardFailure('no-initial-focus-grant')
        self.initial_focus_used = True
        if not self.bound_state: raise GuardFailure('initial-focus-without-owned-binding')
        expected = getattr(self, 'input_state', None)
        if not expected: raise GuardFailure('initial-focus-without-qualified-input-state')
        validate(expected, self.snapshot(), foreground=False)
        # The sole activation call in this helper; no thread attachment or fallback.
        self.u.SetForegroundWindow(self.hwnd)
        time.sleep(.15)
        validate(expected, self.snapshot(), foreground=True)

    def loaded_english_layout(self):
        # Read-only enumeration: never load/install/activate a global layout.
        count = self.u.GetKeyboardLayoutList(0, None)
        if count <= 0: raise GuardFailure('keyboard-layout-list-unavailable')
        layouts = (W.HANDLE * count)()
        if self.u.GetKeyboardLayoutList(count, layouts) != count:
            raise GuardFailure('keyboard-layout-list-changed')
        # HKL's low word is the language ID; its high word is an opaque device
        # handle, not a constant we may invent or assume for this machine.
        matches = [int(value or 0) for value in layouts if int(value or 0) & 0xffff == 0x409]
        if len(matches) != 1:
            raise GuardFailure('English-US-layout-not-uniquely-loaded-no-global-change')
        return matches[0]

    def post_input_language(self, hkl):
        if hkl & 0xffff != 0x409: raise GuardFailure('only-English-US-request-is-allowed')
        if self.loaded_english_layout() != hkl: raise GuardFailure('requested-English-layout-changed')
        if not self.bound_state: raise GuardFailure('language-request-without-owned-binding')
        current = validate_identity(self.bound_state, self.snapshot(), foreground=False)
        return bool(self.u.PostMessageW(current['hwnd'], 0x0050, 0, hkl))

    def prepare_english_once(self, allowed, record):
        if not allowed or self.language_request_used: raise GuardFailure('no-owned-English-request-grant-or-already-attempted')
        self.language_request_used = True
        if not self.bound_state: raise GuardFailure('language-request-without-owned-binding')
        def complete(result):
            # Native identity remains language-independent for safe close. Only
            # this separately retained snapshot qualifies activation and input.
            observed = result['observed']
            qualified = dict(self.bound_state, qualifiedKeyboardLayout=observed.get('keyboardLayout'))
            validate(qualified, observed, foreground=False)
            self.input_state = qualified
            record({'kind': 'owned-English-preparation', **result})
            return result
        before = validate_identity(self.bound_state, self.snapshot(), foreground=False)
        if before['keyboardLanguage'] == 0x409:
            result = {'requested': False, 'reason': 'actual-owned-thread-already-English-US', 'before': before, 'observed': before}
            return complete(result)
        hkl = self.loaded_english_layout()
        immediate = validate_identity(self.bound_state, self.snapshot(), foreground=False)
        if immediate['keyboardLanguage'] == 0x409:
            result = {'requested': False, 'reason': 'actual-owned-thread-became-English-before-request', 'before': before, 'observed': immediate}
            return complete(result)
        posted = self.post_input_language(hkl)
        record({'kind': 'owned-WM_INPUTLANGCHANGEREQUEST', 'requestedHKL': hkl,
                'message': 0x0050, 'wParam': 0, 'window': immediate, 'return': posted,
                'activation': False, 'keyboardInput': False, 'globalOrBroadcast': False})
        if not posted: raise GuardFailure('owned-English-request-not-posted')
        end = time.monotonic() + 3
        while True:
            observed = validate_identity(self.bound_state, self.snapshot(), foreground=False)
            if observed['keyboardLanguage'] == 0x409 and observed['keyboardLayout'] == hkl:
                result = {'requested': True, 'requestedHKL': hkl, 'before': before, 'observed': observed}
                return complete(result)
            if time.monotonic() >= end:
                record({'kind': 'owned-English-request-not-observed', 'before': before, 'observed': observed, 'requestedHKL': hkl})
                raise GuardFailure('owned-English-request-not-observed')
            time.sleep(.05)

    def snapshot(self):
        if not self.hwnd: raise GuardFailure('unbound-window')
        pid = W.DWORD(); thread = self.u.GetWindowThreadProcessId(self.hwnd, ctypes.byref(pid))
        if not thread: raise GuardFailure('owned-window-thread-unavailable')
        start, end, kernel, user = W.FILETIME(), W.FILETIME(), W.FILETIME(), W.FILETIME()
        if not self.k.GetProcessTimes(self.handle, ctypes.byref(start), ctypes.byref(end), ctypes.byref(kernel), ctypes.byref(user)):
            raise GuardFailure('process-start-unavailable')
        name, count = ctypes.create_unicode_buffer(32768), W.DWORD(32768)
        if not self.k.QueryFullProcessImageNameW(self.handle, 0, name, ctypes.byref(count)):
            raise GuardFailure('process-path-unavailable')
        title = ctypes.create_unicode_buffer(1024); self.u.GetWindowTextW(self.hwnd, title, 1024)
        rect, point = W.RECT(), W.POINT()
        if not self.u.GetClientRect(self.hwnd, ctypes.byref(rect)) or not self.u.ClientToScreen(self.hwnd, ctypes.byref(point)):
            raise GuardFailure('client-geometry-unavailable')
        keyboard_layout = int(self.u.GetKeyboardLayout(thread) or 0)
        return dict(pid=pid.value, startTicks=(start.dwHighDateTime << 32) | start.dwLowDateTime,
                    path=name.value, hwnd=int(self.hwnd), title=title.value,
                    alive=self.process.poll() is None and bool(self.u.IsWindow(self.hwnd)), visible=bool(self.u.IsWindowVisible(self.hwnd)),
                    foreground=int(self.u.GetForegroundWindow() or 0), x=point.x, y=point.y,
                    width=rect.right - rect.left, height=rect.bottom - rect.top,
                    matchingWindows=len(self.candidates()), windowThreadId=int(thread),
                    keyboardLayout=keyboard_layout, keyboardLanguage=keyboard_layout & 0xffff)

    def move(self, x, y):
        if not self.u.SetCursorPos(round(x), round(y)): raise GuardFailure('cursor-move-failed')

    def point_owned(self, x, y):
        return self.u.GetAncestor(self.u.WindowFromPoint(W.POINT(round(x), round(y))), 2) == self.hwnd

    def cursor(self):
        point = W.POINT()
        if not self.u.GetCursorPos(ctypes.byref(point)): raise GuardFailure('cursor-position-unavailable')
        return point.x, point.y

    def pressed(self, kind, value):
        vk = value if kind == 'key' else {'left': 1, 'right': 2}[value]
        return bool(self.u.GetAsyncKeyState(vk) & 0x8000)

    def send(self, kind, value, up):
        event = self.Input()
        if kind == 'key':
            event.type = 1; event.ki.wVk = value; event.ki.dwFlags = 2 if up else 0
        else:
            event.type = 0; event.mi.dwFlags = {'left': (2, 4), 'right': (8, 16)}[value][up]
        if self.u.SendInput(1, ctypes.byref(event), ctypes.sizeof(event)) != 1:
            raise GuardFailure('owned-input-send-failed')

    def resize(self, width, height, flags):
        outer, client = W.RECT(), W.RECT()
        if not self.u.GetWindowRect(self.hwnd, ctypes.byref(outer)) or not self.u.GetClientRect(self.hwnd, ctypes.byref(client)):
            raise GuardFailure('resize-geometry-unavailable')
        if flags != 0x0016: raise GuardFailure('resize-must-not-activate')
        if not self.u.SetWindowPos(self.hwnd, None, 0, 0, width + outer.right - outer.left - client.right + client.left,
                                   height + outer.bottom - outer.top - client.bottom + client.top, flags):
            raise GuardFailure('owned-resize-failed')

    def capture(self, box):
        from PIL import ImageGrab
        return ImageGrab.grab(bbox=box)

    def close_message(self):
        if not self.u.PostMessageW(self.hwnd, 0x0010, 0, 0): raise GuardFailure('owned-WM_CLOSE-failed')

    def release_handle(self):
        if self.handle:
            self.k.CloseHandle(self.handle); self.handle = None
