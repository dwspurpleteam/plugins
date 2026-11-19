/** Ответ GET /api/calls/{callId}/participants. Время — строки ISO-8601 в UTC. */
export interface CallSnapshot {
  callId: string;
  source: string;
  status: CallStatus;
  statusMessage: string | null;
  trackingSince: string | null;
  endedAt: string | null;
  gaps: Gap[];
  serverTime: string;
  present: Participant[];
  left: Participant[];
}

export type CallStatus = 'CONNECTING' | 'TRACKING' | 'RECONNECTING' | 'ENDED' | 'FAILED';

export interface Participant {
  participantId: string;
  /** null: вошёл до начала отслеживания. */
  joinedAt: string | null;
  /** null: сейчас в звонке. */
  leftAt: string | null;
}

export interface Gap {
  from: string;
  /** null: связь ещё не восстановлена. */
  to: string | null;
}
