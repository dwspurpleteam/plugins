import { useEffect, useState } from 'react';
import { ApiError, fetchSnapshot } from './api';
import type { CallSnapshot } from './types';

const POLL_MS = 2000;
const ENDED_POLL_MS = 10_000;
const MAX_RETRY_MS = 15_000;

export interface SnapshotState {
  snapshot: CallSnapshot | null;
  error: ApiError | null;
  /** Разница часов сервера и браузера: длительности считаем по времени сервера. */
  clockOffsetMs: number;
}

/**
 * Опрашивает состав звонка. Пока вкладка скрыта, запросов нет; после ошибок интервал растёт.
 */
export function useSnapshot(callId: string): SnapshotState {
  const [state, setState] = useState<SnapshotState>({ snapshot: null, error: null, clockOffsetMs: 0 });

  useEffect(() => {
    let disposed = false;
    let inFlight = false;
    let timer: number | undefined;
    let controller: AbortController | undefined;
    let failures = 0;

    const schedule = (delayMs: number) => {
      if (!disposed && !document.hidden) {
        timer = window.setTimeout(poll, delayMs);
      }
    };

    const poll = async () => {
      timer = undefined;
      inFlight = true;
      const current = new AbortController();
      controller = current;
      try {
        const snapshot = await fetchSnapshot(callId, current.signal);
        failures = 0;
        setState({ snapshot, error: null, clockOffsetMs: Date.parse(snapshot.serverTime) - Date.now() });
        schedule(snapshot.status === 'ENDED' ? ENDED_POLL_MS : POLL_MS);
      } catch (error) {
        if (disposed || current.signal.aborted) {
          return;
        }
        const apiError = error instanceof ApiError ? error : new ApiError('Не удалось получить данные', 0);
        failures += 1;
        setState((previous) => ({ ...previous, error: apiError }));
        // Неверный callId повтором не исправится.
        if (apiError.status !== 400) {
          schedule(Math.min(POLL_MS * 2 ** failures, MAX_RETRY_MS));
        }
      } finally {
        inFlight = false;
      }
    };

    const onVisibilityChange = () => {
      if (document.hidden) {
        window.clearTimeout(timer);
        timer = undefined;
        controller?.abort();
      } else if (!inFlight && timer === undefined) {
        void poll();
      }
    };

    document.addEventListener('visibilitychange', onVisibilityChange);
    void poll();

    return () => {
      disposed = true;
      window.clearTimeout(timer);
      controller?.abort();
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [callId]);

  return state;
}
