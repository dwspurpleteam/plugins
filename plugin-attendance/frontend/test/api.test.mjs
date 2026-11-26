import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ApiError, downloadReport, fetchSnapshot } from '../src/api.ts';

function rejectOnAbort(signal) {
  return new Promise((_, reject) => {
    if (signal.aborted) reject(signal.reason);
    else signal.addEventListener('abort', () => reject(signal.reason), { once: true });
  });
}

function downloads(t) {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const links = [];
  const previous = Object.getOwnPropertyDescriptor(globalThis, 'document');
  Object.defineProperty(globalThis, 'document', { configurable: true, value: {
    body: { append() {} },
    createElement() {
      return {
        href: '', download: '',
        click() { links.push({ href: this.href, filename: this.download }); },
        remove() {},
      };
    },
  } });
  t.after(() => {
    if (previous) Object.defineProperty(globalThis, 'document', previous);
    else delete globalThis.document;
  });
  t.mock.method(URL, 'createObjectURL', () => 'blob:report');
  t.mock.method(URL, 'revokeObjectURL');
  return links;
}

test('download keeps server filename and releases its object URL', async t => {
  const links = downloads(t);
  t.mock.method(globalThis, 'fetch', async () => new Response('%PDF-1.4', { headers: {
    'Content-Type': 'application/pdf',
    'Content-Disposition': 'attachment; filename="attendance-RM_1-2026-11-27.pdf"',
  } }));
  await downloadReport('RM_1', 'pdf', 'Europe/Moscow', new AbortController().signal);
  assert.deepEqual(links, [{ href: 'blob:report', filename: 'attendance-RM_1-2026-11-27.pdf' }]);
  t.mock.timers.tick(30_000);
  assert.equal(URL.revokeObjectURL.mock.calls[0].arguments[0], 'blob:report');
});

test('download rejects an HTML login page instead of saving a corrupt report', async t => {
  const links = downloads(t);
  t.mock.method(globalThis, 'fetch', async () => new Response('<html>Login</html>', {
    headers: { 'Content-Type': 'text/html' },
  }));
  await assert.rejects(downloadReport('RM_1', 'pdf', 'UTC', new AbortController().signal),
    error => error instanceof ApiError && error.status === 502);
  assert.equal(links.length, 0);
});

test('download rejects empty reports', async t => {
  const links = downloads(t);
  t.mock.method(globalThis, 'fetch', async () => new Response('', { headers: { 'Content-Type': 'text/csv' } }));
  await assert.rejects(downloadReport('RM_1', 'csv', 'UTC', new AbortController().signal),
    error => error instanceof ApiError && error.message.includes('пустой'));
  assert.equal(links.length, 0);
});

test('untrusted path in Content-Disposition falls back to a safe filename', async t => {
  const links = downloads(t);
  t.mock.method(globalThis, 'fetch', async () => new Response('CSV', { headers: {
    'Content-Type': 'text/csv;charset=UTF-8',
    'Content-Disposition': 'attachment; filename="../../unexpected.csv"',
  } }));
  await downloadReport('RM/one', 'csv', 'UTC', new AbortController().signal);
  assert.equal(links[0].filename, 'attendance-RM_one.csv');
});

test('unknown archive returns a helpful error without starting a download', async t => {
  const links = downloads(t);
  t.mock.method(globalThis, 'fetch', async () => new Response('{}', { status: 404 }));
  await assert.rejects(downloadReport('RM_1', 'pdf', 'UTC', new AbortController().signal),
    error => error instanceof ApiError && error.status === 404 && error.message.includes('сохранённого отчёта'));
  assert.equal(links.length, 0);
});

test('snapshot timeout releases a hung request', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  t.mock.method(globalThis, 'fetch', (_, init) => rejectOnAbort(init.signal));
  const result = assert.rejects(fetchSnapshot('RM_1', new AbortController().signal),
    error => error instanceof ApiError && error.message.includes('вовремя'));
  t.mock.timers.tick(10_000);
  await result;
});

test('report timeout also covers reading its response body', async t => {
  const links = downloads(t);
  t.mock.method(globalThis, 'fetch', async (_, init) => ({
    ok: true,
    headers: new Headers({ 'Content-Type': 'application/pdf' }),
    blob: () => rejectOnAbort(init.signal),
  }));
  const result = assert.rejects(downloadReport('RM_1', 'pdf', 'UTC', new AbortController().signal),
    error => error instanceof ApiError && error.message.includes('вовремя'));
  await Promise.resolve();
  t.mock.timers.tick(30_000);
  await result;
  assert.equal(links.length, 0);
});

test('caller cancellation is not reported as a connection error', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  t.mock.method(globalThis, 'fetch', (_, init) => rejectOnAbort(init.signal));
  const controller = new AbortController();
  const result = assert.rejects(fetchSnapshot('RM_1', controller.signal), { name: 'AbortError' });
  controller.abort();
  await result;
});
