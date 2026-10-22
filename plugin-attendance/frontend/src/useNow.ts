import { useEffect, useState } from 'react';

/** Текущее время, обновляется раз в intervalMs: для длительности пребывания в звонке. */
export function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs]);

  return now;
}
