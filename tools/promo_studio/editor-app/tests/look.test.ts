import { test } from 'node:test';
import assert from 'node:assert/strict';
import { plateLook, takeKey } from '../src/lib/model.ts';

test('changing capture resolution reloads the take while legacy defaults stay identical', () => {
  const s = { app: {} } as any;
  const legacy = takeKey(s);
  s.app.capture = '1080p';
  assert.equal(takeKey(s), legacy);
  s.app.capture = '2k';
  assert.notEqual(takeKey(s), legacy);
});

test('a sharp look gets its own source plate, including when blur is animated', () => {
  const s = { background: { id: 'canopy' }, aspect: '1:1', props: { bg_blur: 2 }, tracks: {} } as any;
  const soft = plateLook(s);
  s.props.bg_blur = 0;
  const sharp = plateLook(s);
  assert.notEqual(sharp, soft);
  s.props.bg_blur = 36;
  s.tracks.bg_blur = [{ t: 0, v: 36 }, { t: 2, v: 0 }];
  assert.equal(plateLook(s), sharp, 'a sharp keyframe needs an unblurred base plate');
});
