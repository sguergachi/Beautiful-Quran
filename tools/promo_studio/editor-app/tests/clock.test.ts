import { test } from 'node:test';
import assert from 'node:assert/strict';

class Medium extends EventTarget {
  currentTime = 0; duration = 12; seeking = false; paused = true;
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
clock.bind(() => ({ spec: { duration: 10, fps: 60, edit: [{ in: 0, out: 10 }], audio } as any, hasClip: true, hasPlate: true, offset: 0, onset: 1 }));

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
