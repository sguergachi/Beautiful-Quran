import { test } from 'node:test';
import assert from 'node:assert/strict';

class Medium extends EventTarget {
  currentTime = 0; duration = 12; seeking = false; paused = true; playbackRate = 1;
  async play() { this.paused = false; }
  pause() { this.paused = true; }
}
const frames = new Map<number, FrameRequestCallback>();
let next = 0;
Object.assign(globalThis, {
  document: { createElement: () => new Medium() }, Audio: Medium, Image: class {},
  requestAnimationFrame: (fn: FrameRequestCallback) => { frames.set(++next, fn); return next; },
  cancelAnimationFrame: (id: number) => frames.delete(id),
});
const { clock, media } = await import('../src/state/clock.ts');
const audio = { gain_db: 0, fade_in: 1, fade_out: 2, loudness: -16 };
const spec = { duration: 10, fps: 60, edit: [{ in: 0, out: 10 }], audio };
clock.bind(() => ({ spec: spec as any, hasClip: true, hasPlate: true, offset: 0, onset: 1 }));

test('rapid play/pause/play leaves one animation loop', () => {
  clock.play(); clock.pause(); clock.play();
  assert.equal(frames.size, 1);
  clock.pause();
  assert.equal(frames.size, 0);
});

test('replacement media resumes only while the timeline is playing', () => {
  clock.seek(2); clock.play();
  for (const m of [media.screen, media.audio, media.plate]) {
    m.pause(); // replacing src pauses a real media element
    m.dispatchEvent(new Event('loadeddata'));
    assert.equal(m.paused, false);
    assert.equal(m.currentTime, 2);
  }
  clock.pause();
  media.plate.dispatchEvent(new Event('loadeddata'));
  assert.equal(media.plate.paused, true);
});

test('without Web Audio the preview still hears the fades', () => {
  clock.seek(1.5); clock.play();
  assert.equal(media.audio.volume, 0.5);
  clock.pause();
  clock.seek(9); clock.play();
  assert.equal(media.audio.volume, 0.5);
  clock.pause();
});

test('small playback drift never replays or skips the recording', () => {
  clock.seek(5); clock.play();
  try {
    media.screen.currentTime = 5.1;
    clock.sync(false);
    assert.equal(media.screen.currentTime, 5.1, 'a leading recording must not seek backward');
    assert.ok(media.screen.playbackRate < 1 && media.screen.playbackRate >= .96);
    media.screen.currentTime = 4.9;
    clock.sync(false);
    assert.equal(media.screen.currentTime, 4.9, 'a lagging recording must not skip frames');
    assert.ok(media.screen.playbackRate > 1 && media.screen.playbackRate <= 1.04);
    media.screen.currentTime = 4.995;
    clock.sync(false);
    assert.equal(media.screen.playbackRate, 1);
  } finally { clock.pause(); }
});

test('large delays and explicit scrubs still land exactly and reset the rate', () => {
  clock.seek(5); clock.play();
  try {
    media.screen.currentTime = 4;
    clock.sync(false);
    assert.equal(media.screen.currentTime, 5);
    media.screen.currentTime = 4.9;
    clock.sync(false);
    clock.seek(2);
    assert.equal(media.screen.currentTime, 2);
    assert.equal(media.screen.playbackRate, 1);
  } finally { clock.pause(); }
});

test('a cut seeks to its new source even when the difference is small', () => {
  const original = spec.edit;
  spec.edit = [{ in: 0, out: 5 }, { in: 5.1, out: 10.1 }];
  clock.seek(4.9); clock.play();
  try {
    media.screen.currentTime = 5;
    clock.t = 5;
    clock.sync(false);
    assert.equal(media.screen.currentTime, 5.1);
    assert.equal(media.screen.playbackRate, 1);
  } finally { clock.pause(); spec.edit = original; }
});

test('playback does not replace the latest explicit scrub while a seek is pending', () => {
  const screen = media.screen as unknown as Medium;
  clock.seek(5); clock.play();
  try {
    screen.seeking = true;
    clock.seek(2); clock.seek(3);
    clock.t = 3.2;
    clock.sync(false);
    screen.seeking = false;
    media.screen.dispatchEvent(new Event('seeked'));
    assert.equal(media.screen.currentTime, 3);
    media.screen.currentTime = 3.1;
    clock.sync(false);
    assert.ok(media.screen.playbackRate > 1);
  } finally { screen.seeking = false; clock.pause(); }
  assert.equal(media.screen.playbackRate, 1);
});
