import assert from 'node:assert/strict';
import { test } from 'node:test';
import { labelFor, labelsFor } from '../src/participants.ts';

function displayed(label) {
  return label.name + (label.showTag ? ` #${label.tag}` : '');
}

test('unique guest names hide the identity suffix', () => {
  const id = 'ivan_petrov-123456789012345';
  const label = labelsFor([id]).get(id);
  assert.equal(displayed(label), 'Ivan Petrov');
  assert.equal(label.initials, 'IP');
  assert.equal(label.hue, labelFor(id).hue);
});

test('guests with the same first four suffix characters remain distinct', () => {
  const first = 'anna-0123aaaaaaaaaaa';
  const second = 'anna-0123bbbbbbbbbbb';
  const labels = labelsFor([first, second]);
  assert.equal(displayed(labels.get(first)), 'Anna #0123a');
  assert.equal(displayed(labels.get(second)), 'Anna #0123b');
});

test('generic and empty guest names always include a distinguishing suffix', () => {
  const normal = 'participant-123456789012345';
  const empty = '___-abcdef012345678';
  const labels = labelsFor([normal, empty]);
  assert.equal(displayed(labels.get(normal)), 'Участник #1234');
  assert.equal(displayed(labels.get(empty)), 'Участник #abcd');
  assert.equal(labelFor(empty).generic, true);
});

test('a raw identity cannot impersonate another participant formatted label', () => {
  const first = 'anna-0123456789abcde';
  const second = 'anna-abcdef012345678';
  const raw = 'Anna #0123';
  const labels = labelsFor([first, second, raw]);
  assert.equal(displayed(labels.get(first)), first);
  assert.equal(displayed(labels.get(second)), 'Anna #abcd');
  assert.equal(displayed(labels.get(raw)), raw);
  assert.equal(new Set([...labels.values()].map(displayed)).size, 3);
});

test('normalized names with identical full suffixes fall back to their different identities', () => {
  const first = 'anna_bob-0123456789abcde';
  const second = 'anna__bob-0123456789abcde';
  const labels = labelsFor([first, second]);
  assert.equal(displayed(labels.get(first)), first);
  assert.equal(displayed(labels.get(second)), second);
});

test('duplicate identities and input order do not change their labels', () => {
  const first = 'anna-0123aaaaaaaaaaa';
  const second = 'anna-0123bbbbbbbbbbb';
  const forward = labelsFor([first, second, first]);
  const reverse = labelsFor([second, first]);
  assert.equal(forward.size, 2);
  for (const id of [first, second]) assert.deepEqual(forward.get(id), reverse.get(id));
});
