/**
 * Контекст запуска из адреса страницы: ?callId=…&roomId=…&participantId=…
 *
 * Временный контракт с Meet до появления подписанного контекста запуска: callId — SID комнаты
 * LiveKit (RM_…), roomId — имя комнаты, participantId — identity того, кто открыл плагин.
 */
export interface LaunchContext {
  callId: string | null;
  roomId: string | null;
  participantId: string | null;
}

export function readContext(search: string = window.location.search): LaunchContext {
  const params = new URLSearchParams(search);
  const value = (name: string) => params.get(name)?.trim() || null;
  return {
    callId: value('callId'),
    roomId: value('roomId'),
    participantId: value('participantId'),
  };
}
