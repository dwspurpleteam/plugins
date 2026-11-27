import assert from 'node:assert/strict';
import { test } from 'node:test';
import { initialView, readContext } from '../src/context.ts';

test('standalone page opens archive and an in-call launch keeps attendance', () => {
  assert.equal(initialView(''), 'archive');
  assert.equal(initialView('?callId=%20'), 'archive');
  assert.equal(initialView('?callId=RM_1'), 'call');
  assert.equal(initialView('?callId=RM_1&view=archive'), 'archive');
});

test('archive deep link preserves exact subject case and optional call context', () => {
  assert.deepEqual(readContext('?view=archive&participantId=%20Teacher%2B1%20&callId=RM_1'), {
    participantId: 'Teacher+1', callId: 'RM_1', roomId: null,
  });
});
