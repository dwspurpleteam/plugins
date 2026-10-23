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
  summary: SummaryState;
}

/** Публикации сводки через Result Gateway. */
export interface SummaryState {
  /** Последняя публикация; null — сводку ещё не публиковали. */
  publication: Publication | null;
  /** Последняя доставленная сводка: её видят участники звонка. */
  delivered: DeliveredSummary | null;
}

export type PublicationStatus = 'SENDING' | 'ACCEPTED' | 'DELIVERED' | 'REJECTED' | 'FAILED';

/** Ответ POST /api/calls/{callId}/summary. */
export interface Publication {
  messageId: string;
  revision: number;
  status: PublicationStatus;
  reason: string | null;
  requestedAt: string;
  updatedAt: string;
}

export interface DeliveredSummary {
  revision: number;
  deliveredAt: string;
  recipients: number;
  summary: AttendanceSummary;
}

export interface AttendanceSummary {
  composedAt: string;
  trackingSince: string | null;
  present: number;
  left: number;
  publishedBy: string | null;
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
