import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ApiError, downloadReport, fetchArchive, fetchSnapshot } from '../src/api.ts';

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

test('archive search encodes the exact subject, date filters and browser timezone', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const page = { participantId: 'Teacher+1', total: 0, items: [] };
  t.mock.method(globalThis, 'fetch', async (path, init) => {
    const url = new URL(path, 'https://plugin.example/apps/attendance/');
    assert.equal(url.pathname, '/apps/attendance/api/archive');
    assert.deepEqual(Object.fromEntries(url.searchParams), {
      participantId: 'Teacher+1', timezone: 'Europe/Moscow', page: '2', limit: '10',
      from: '2026-11-01', to: '2026-11-27',
    });
    assert.equal(init.cache, 'no-store');
    return Response.json(page);
  });
  assert.deepEqual(await fetchArchive('Teacher+1', {
    page: 2, limit: 10, from: '2026-11-01', to: '2026-11-27', timezone: 'Europe/Moscow',
  }, new AbortController().signal), page);
});

test('archive download sends the subject to its membership endpoint', async t => {
  const links = downloads(t);
  t.mock.method(globalThis, 'fetch', async path => {
    const url = new URL(path, 'https://plugin.example/');
    assert.equal(url.pathname, '/api/archive/calls/RM_one/report');
    assert.equal(url.searchParams.get('participantId'), 'teacher+1');
    assert.equal(url.searchParams.get('timezone'), 'Asia/Tokyo');
    return new Response('CSV', { headers: { 'Content-Type': 'text/csv' } });
  });
  await downloadReport('RM_one', 'csv', 'Asia/Tokyo', new AbortController().signal, 'teacher+1');
  assert.equal(links.length, 1);
});

test('archive download denies an absent participant without saving a file', async t => {
  const links = downloads(t);
  t.mock.method(globalThis, 'fetch', async () => new Response('{}', { status: 403 }));
  await assert.rejects(downloadReport('RM_one', 'pdf', 'UTC', new AbortController().signal, 'outsider'),
    error => error instanceof ApiError && error.status === 403 && error.message.includes('логин'));
  assert.equal(links.length, 0);
});
