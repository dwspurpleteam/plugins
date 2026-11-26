import type { CallSnapshot } from './types';

export class ApiError extends Error {
  /** HTTP-статус; 0, если сервер плагина недоступен. */
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/**
 * Состав звонка. Адрес относительный: страница работает под любым префиксом пути.
 * Заголовков не добавляем, чтобы запрос из sandbox-iframe оставался простым, без preflight.
 */
export async function fetchSnapshot(callId: string, signal: AbortSignal): Promise<CallSnapshot> {
  return request(`api/calls/${encodeURIComponent(callId)}/participants`, signal,
    (response) => response.json() as Promise<CallSnapshot>);
}

export type ReportFormat = 'pdf' | 'csv';

/** Отчёт читается отдельно от текущего состава: архив доступен и после завершения трекера. */
export async function downloadReport(callId: string, format: ReportFormat, timezone: string, signal: AbortSignal): Promise<void> {
  const query = new URLSearchParams({ format, timezone });
  let report: { file: Blob; filename: string };
  try {
    report = await request(`api/calls/${encodeURIComponent(callId)}/report?${query}`, signal, async (response) => {
      const expectedType = format === 'pdf' ? 'application/pdf' : 'text/csv';
      if (response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase() !== expectedType) {
        throw new ApiError('Сервер вернул неподдерживаемый формат отчёта', 502);
      }
      const file = await response.blob();
      if (file.size === 0) throw new ApiError('Сервер вернул пустой отчёт', 502);
      const filename = response.headers.get('content-disposition')?.match(/filename="([a-zA-Z0-9_.-]{1,180})"/)?.[1]
        ?? `attendance-${callId.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 100)}.${format}`;
      return { file, filename };
    }, 30_000);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      throw new ApiError('Для этого звонка нет сохранённого отчёта. Возможно, срок хранения истёк.', 404);
    }
    throw error;
  }
  signal.throwIfAborted();
  const url = URL.createObjectURL(report.file);
  const link = document.createElement('a');
  link.href = url;
  link.download = report.filename;
  document.body.append(link);
  try {
    link.click();
  } finally {
    link.remove();
    // Даём браузеру начать чтение Blob перед освобождением URL.
    globalThis.setTimeout(() => URL.revokeObjectURL(url), 30_000);
  }
}

/** The timeout covers both response headers and reading the body. */
async function request<T>(path: string, signal: AbortSignal, read: (response: Response) => Promise<T>,
  timeoutMs = 10_000): Promise<T> {
  const timeout = new AbortController();
  const timer = globalThis.setTimeout(() => timeout.abort(), timeoutMs);
  try {
    const response = await fetch(path, { signal: AbortSignal.any([signal, timeout.signal]), cache: 'no-store' });
    if (!response.ok) throw new ApiError(await problemDetail(response), response.status);
    const result = await read(response);
    signal.throwIfAborted();
    timeout.signal.throwIfAborted();
    return result;
  } catch (error) {
    if (signal.aborted) throw error;
    if (timeout.signal.aborted) throw new ApiError('Сервер не ответил вовремя. Повторите запрос', 0);
    if (error instanceof ApiError) throw error;
    throw new ApiError('Нет связи с сервером плагина', 0);
  } finally {
    globalThis.clearTimeout(timer);
  }
}

async function problemDetail(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: unknown };
    if (typeof body.detail === 'string' && body.detail) {
      return body.detail;
    }
  } catch {
    // Ответ не JSON: показываем код.
  }
  return `Сервер плагина ответил ${response.status}`;
}
