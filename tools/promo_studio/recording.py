"""On-camera navigation for an ordered recording sequence (no app changes)."""
import re
import shlex
import sqlite3
import time
from pathlib import Path

import spec


class Sequence:
    """Each step leaves its screen open; the following step chooses the route."""
    def __init__(self, s, driver=None, log=print):
        if driver is None:
            import capture as driver
        self.c, self.s, self.a, self.log = driver, s, s['app'], log
        self.screen, self.ayah = 'reader', self.a['ayah']

    def find(self, label, exact=True):
        return self.c.at.find(label, exact)

    def tap(self, label, exact=True):
        # Ink-bleed transitions can still expose the outgoing sheet's nodes.
        # Wait for the destination rather than tapping through the animation.
        xy = None
        for _ in range(8):
            xy = self.find(label, exact)
            if xy: break
            time.sleep(0.5)
        if not xy:
            raise RuntimeError(f'Recording step: {label!r} is not visible')
        self.c.at.tap(xy)
        time.sleep(0.8)

    def home(self):
        for _ in range(5):
            if self.find('Open settings'):
                clear = self.find('Clear search')
                if clear:
                    self.c.at.tap(clear)
                    self.c.back()   # dismiss the keyboard opened by Clear
                self.screen = 'home'
                return
            self.c.back()
        raise RuntimeError('Could not reach the chapter list')

    def reader(self, ayah=None):
        ayah = self.a['ayah'] if ayah is None else ayah
        if self.screen == 'reader' and self.ayah == ayah:
            return
        if self.screen in ('settings', 'roots', 'repeat'):
            self.c.back(); self.screen = 'reader'
            if self.ayah == ayah:
                return
        if self.screen == 'reader':
            # Leave the current reader before replacing its deep-link target;
            # this also releases that sheet's pinned transport callbacks.
            self.home()
        self.c.at.open_reader(self.a['surah'], ayah)
        time.sleep(2.5)
        self.screen, self.ayah = 'reader', ayah

    def chapter(self, via):
        if via == 'direct':
            self.reader()
            return
        self.home()
        with sqlite3.connect(Path(spec.REPO) / 'data/quran.db') as db:
            name = db.execute('select name_transliteration from surahs where id=?', (self.a['surah'],)).fetchone()[0]
        if via == 'search':
            self.tap('Search')
            # Search by chapter name: reference queries intentionally filter
            # chapter results in the app rather than selecting an ayah row.
            self.c.adb('shell', 'input', 'text', shlex.quote(name.replace(' ', '%s')))
            time.sleep(1)
            self.c.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        else:
            self.c.scroll_top('Al-Fatihah', max_swipes=20)
        xy = self.c.scroll_to(name, max_swipes=30)
        if not xy:
            raise RuntimeError(f'Chapter {name} was not found in the list')
        self.c.at.tap(xy); time.sleep(2)
        self.screen, self.ayah = 'reader', 1

    def cover(self):
        self.c.adb('shell', 'am', 'force-stop', 'com.beautifulquran')
        self.c.adb('shell', 'am', 'start', '-f', '0x10008000', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-n', 'com.beautifulquran/.MainActivity')
        # The app opens automatically after its title and du'a washes. Launch
        # only once recording is rolling, and let the complete ceremony play.
        if not self.c.wait_for('Open settings', tries=20):
            raise RuntimeError('The opening ceremony did not reach Home')
        self.screen = 'home'

    def repeat(self, hold=0, mode=None):
        self.reader()
        self.tap('Repeat')
        self.screen = 'repeat'
        mode = self.a['repeat'] if mode is None else mode
        count = (self.a.get('repeat_to') or self.a['ayah']) - self.a['ayah'] + 1
        self.tap('Off' if mode == 'off' else 'This ayah' if mode == 'ayah' or count == 1 else 'From this ayah')
        if mode == 'range' and count > 1:
            count = self.a['repeat_to'] - self.a['ayah'] + 1
            for _ in range(100):
                ns = self.c.at.nodes()
                header = next((b for t, d, b in ns if t == 'ayahs'), None)
                join = next((b for t, d, b in ns if t.strip() == 'to'), None)
                if not header or not join: raise RuntimeError('Repeat count dial is not visible')
                x, y = (header[0] + header[2]) // 2, (join[1] + join[3]) // 2
                digits = [(int(t), b) for t, d, b in ns if t.isdigit() and b[0] > header[0] - 30]
                selected = min(digits, key=lambda v: abs((v[1][1] + v[1][3]) / 2 - y))[0]
                if selected == count:
                    break
                spacing = sorted((b[1] + b[3]) / 2 for n, b in digits)
                row = spacing[1] - spacing[0] if len(spacing) > 1 else 110
                dy = max(-row * 4, min(row * 4, (selected - count) * row))
                self.c.adb('shell', 'input', 'swipe', str(x), str(y), str(x), str(round(y + dy)), '650')
                time.sleep(0.7)
            else: raise RuntimeError('Could not set the requested repeat count')
        time.sleep(hold)
        xy = self.find('Done')
        if not xy: raise RuntimeError('Repeat picker Done action was not found')
        self.c.at.tap(xy)
        self.screen = 'reader'
        self.c.adb('shell', 'input', 'keyevent', 'KEYCODE_MEDIA_PAUSE', check=False)
        time.sleep(0.5)

    def prepare(self):
        steps = spec.opening_steps(self.s)
        # Clear previous app-session repeat even for an ordinary take.
        # Bind repeat to the requested verse, rather than the previous take's
        # now-playing ayah (the app's "This ayah" refers to that playlist).
        # A fresh activity also clears callbacks retained by an earlier reader
        # session, and prevents a previous take's repeat range leaking in.
        self.c.adb('shell', 'am', 'force-stop', 'com.beautifulquran')
        warm = self.a['repeat'] != 'off'
        self.c.at.open_reader(self.a['surah'], self.a['ayah'], play=warm)
        if warm:
            for _ in range(30):
                if self.c.playing(): break
                time.sleep(0.5)
            else: raise RuntimeError('Could not prepare playback for this verse')
            self.c.adb('shell', 'input', 'keyevent', 'KEYCODE_MEDIA_PAUSE', check=False)
            time.sleep(1)
            self.repeat()
        elif not self.c.wait_for('Settings', tries=15):
            raise RuntimeError('The verse did not become ready')
        if steps and steps[0]['kind'] == 'cover':
            self.c.adb('shell', 'am', 'force-stop', 'com.beautifulquran')
            self.screen = 'stopped'
        elif steps and steps[0]['kind'] == 'home':
            self.home()
        # A cover restart clears session repeat. Reapply it immediately before
        # playback if the sequence has not shown the picker itself.
        self.repeat_applied = bool(not steps or steps[0]['kind'] != 'cover')

    def run(self, mark, stage):
        steps = spec.opening_steps(self.s)
        if steps:
            mark('intro')
        events = []
        for i, step in enumerate(steps):
            kind, seconds = step['kind'], step['seconds']
            stage('opening', f'Step {i + 1}/{len(steps)}: {kind}', 0.24)
            mark(f'step:{i}:{kind}')
            events.append({'kind': kind, 'seconds': seconds})
            if kind == 'cover':
                self.cover()
                self.repeat_applied = False
                time.sleep(seconds)
            elif kind == 'home': self.home(); time.sleep(seconds)
            elif kind == 'open': self.chapter(step.get('via', 'direct')); time.sleep(seconds)
            elif kind == 'header':
                self.ayah = None
                self.reader(1)
                if self.a['layout'] == 'Scroll' and not self.c.scroll_top(f"Chapter {self.a['surah']} ·", max_swipes=12, exact=False):
                    raise RuntimeError('Chapter heading was not found')
                time.sleep(seconds)
            elif kind == 'settings':
                self.reader(); self.tap('Settings'); self.screen = 'settings'; time.sleep(seconds)
            elif kind == 'roots':
                self.reader()
                xy = self.c.word_xy(self.a['ayah'], step.get('word', 1))
                if not xy: raise RuntimeError('Requested root word is not visible in this verse')
                self.c.adb('shell', 'input', 'swipe', str(xy[0]), str(xy[1]), str(xy[0]), str(xy[1]), '650')
                if not self.c.wait_for('Close'): raise RuntimeError('Root word viewer did not open')
                self.screen = 'roots'; time.sleep(seconds)
            elif kind == 'repeat': self.repeat(seconds); self.repeat_applied = True
            else: time.sleep(seconds)
        if self.a['intro']['play']:
            self.reader()
            if not self.repeat_applied and self.a['repeat'] != 'off':
                self.repeat()
        time.sleep(self.a['intro']['dwell'])
        return events
