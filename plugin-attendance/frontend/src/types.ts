/** Ответ GET /api/calls/{callId}/participants. Время — строки ISO-8601 в UTC. */
export interface CallSnapshot {
  callId: string;
  source: string;
  status: CallStatus;
  statusMessage: string | null;
  persistencePending: boolean;
  trackingSince: string | null;
  endedAt: string | null;
  stoppedAt: string | null;
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

export interface ArchiveQuery {
  page?: number;
  limit?: number;
  from?: string;
  to?: string;
  timezone: string;
}

export interface ArchiveCall {
  callId: string;
  trackingSince: string | null;
  activatedAt: string;
  endedAt: string | null;
  stoppedAt: string | null;
  status: CallStatus;
  participantCount: number;
  hasGaps: boolean;
  expiresAt: string | null;
  reportAvailable: boolean;
}

export interface ArchivePage {
  participantId: string;
  serverTime: string;
  retentionSeconds: number;
  page: number;
  limit: number;
  total: number;
  totalPages: number;
  items: ArchiveCall[];
}
