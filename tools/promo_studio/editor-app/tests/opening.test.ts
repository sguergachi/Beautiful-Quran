import { test } from 'node:test';
import assert from 'node:assert/strict';
import { openingSteps, openingEstimate } from '../src/lib/opening.ts';

const intro = { steps: null, play: true, cover: 2, home: 3, open_by: 'search', header: true, settings: 4, roots: 0, repeat: true, dwell: 2 };
test('legacy recording choices become an ordered sequence', () => {
  const s = { app: { intro } } as any;
  assert.deepEqual(openingSteps(s).map(x => x.kind), ['cover', 'home', 'open', 'header', 'settings', 'repeat']);
  assert.equal(openingSteps(s)[2].via, 'search');
  assert.equal(openingEstimate(s), 29.5);
});
test('explicit order, repeated screens and waits survive legacy fields', () => {
  const steps = [{ kind: 'settings', seconds: 1 }, { kind: 'home', seconds: 2 }, { kind: 'wait', seconds: 3 }, { kind: 'home', seconds: 1 }];
  const s = { app: { intro: { ...intro, steps } } } as any;
  assert.deepEqual(openingSteps(s), steps);
  assert.equal(openingEstimate(s), 16.5);
  s.app.intro.steps = [];
  assert.deepEqual(openingSteps(s), []);
});
