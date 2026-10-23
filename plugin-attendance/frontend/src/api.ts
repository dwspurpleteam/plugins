import type { CallSnapshot, Publication } from './types';

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
  const response = await request(`api/calls/${encodeURIComponent(callId)}/participants`, { signal });
  return (await response.json()) as CallSnapshot;
}

/**
 * Публикует сводку через Result Gateway. POST без тела и заголовков — тоже простой запрос, без preflight.
 * Отказ Result Gateway приходит статусом публикации, а не ошибкой.
 */
export async function publishSummary(callId: string, participantId: string | null): Promise<Publication> {
  const query = participantId ? `?participantId=${encodeURIComponent(participantId)}` : '';
  const response = await request(`api/calls/${encodeURIComponent(callId)}/summary${query}`, { method: 'POST' });
  return (await response.json()) as Publication;
}

async function request(path: string, init: RequestInit): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (error) {
    if (init.signal?.aborted) {
      throw error;
    }
    throw new ApiError('Нет связи с сервером плагина', 0);
  }
  if (!response.ok) {
    throw new ApiError(await problemDetail(response), response.status);
  }
  return response;
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
