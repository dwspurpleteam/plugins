import { type CSSProperties, useState } from 'react';
import { ApiError, publishSummary } from './api';
import { type LaunchContext, readContext } from './context';
import { type ParticipantLabel, labelFor } from './participants';
import { formatDuration, formatGap, formatTime, formatTimeSeconds, plural } from './time';
import type {
  CallSnapshot,
  CallStatus,
  DeliveredSummary,
  Participant,
  Publication,
  PublicationStatus,
} from './types';
import { useNow } from './useNow';
import { useSnapshot } from './useSnapshot';

const context = readContext();

export function App() {
  if (!context.callId) {
    return <MissingCallId />;
  }
  return <Attendance callId={context.callId} context={context} />;
}

function MissingCallId() {
  return (
    <div className="app">
      <div className="center">
        <h1>Не передан звонок</h1>
        <p>
          Плагин открывается из звонка. Для проверки добавьте к адресу <code>?callId=…</code>, например{' '}
          <code>?callId=demo</code> в демо-режиме.
        </p>
      </div>
    </div>
  );
}

function Attendance({ callId, context }: { callId: string; context: LaunchContext }) {
  const { snapshot, error, clockOffsetMs } = useSnapshot(callId);
  const now = useNow(5000) + clockOffsetMs;
  const notices = noticesFor(snapshot, error);

  return (
    <div className="app">
      <Header snapshot={snapshot} />
      {/* Уведомления прокручиваются вместе со списком: в маленьком окне они не закрывают участников. */}
      <main className={error && snapshot ? 'list stale' : 'list'}>
        {notices.map((notice) => (
          <p key={notice.text} className={`notice ${notice.tone}`}>
            {notice.text}
          </p>
        ))}
        {snapshot?.summary.delivered && <DeliveredCard delivered={snapshot.summary.delivered} />}
        {snapshot ? (
          <Lists snapshot={snapshot} now={now} you={context.participantId} />
        ) : (
          !error && <p className="empty">Загрузка…</p>
        )}
      </main>
      {snapshot && (
        <PublishBar callId={callId} publishedBy={context.participantId} fromServer={snapshot.summary.publication} />
      )}
      <footer className="foot" title="Идентификатор звонка (SID комнаты) и имя комнаты">
        {callId}
        {context.roomId && ` · ${context.roomId}`}
      </footer>
    </div>
  );
}

/** Сводка, доставленная через Result Gateway: так её видят участники звонка. */
function DeliveredCard({ delivered }: { delivered: DeliveredSummary }) {
  const { summary } = delivered;
  return (
    <section className="summary" aria-label="Опубликованная сводка">
      <div className="summary-head">
        <span>Сводка на {formatTime(summary.composedAt)}</span>
        <span>ревизия {delivered.revision}</span>
      </div>
      <p className="summary-main">
        В звонке {summary.present}, покинули {summary.left}
      </p>
      <p className="meta">
        Доставлена в {formatTimeSeconds(delivered.deliveredAt)}, получателей: {delivered.recipients}
        {summary.publishedBy && ` · опубликовал(а) ${labelFor(summary.publishedBy).name}`}
      </p>
    </section>
  );
}

const PUBLICATION: Record<PublicationStatus, { text: string; tone: string }> = {
  SENDING: { text: 'отправляется в Result Gateway…', tone: 'muted' },
  ACCEPTED: { text: 'принята, ждём доставки…', tone: 'muted' },
  DELIVERED: { text: 'доставлена', tone: 'ok' },
  REJECTED: { text: 'отклонена', tone: 'err' },
  FAILED: { text: 'не отправлена', tone: 'err' },
};

/** Кнопка публикации и статус последней публикации: из ответа на нажатие или из опроса, что новее. */
function PublishBar({
  callId,
  publishedBy,
  fromServer,
}: {
  callId: string;
  publishedBy: string | null;
  fromServer: Publication | null;
}) {
  const [pending, setPending] = useState(false);
  const [local, setLocal] = useState<Publication | null>(null);
  const [error, setError] = useState<string | null>(null);
  const shown = latest(fromServer, local);

  const publish = async () => {
    setPending(true);
    setError(null);
    try {
      setLocal(await publishSummary(callId, publishedBy));
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : 'Не удалось опубликовать сводку');
    } finally {
      setPending(false);
    }
  };

  const status = shown ? PUBLICATION[shown.status] : null;
  return (
    <div className="actions">
      <button type="button" className="button" onClick={() => void publish()} disabled={pending}>
        {pending ? 'Публикуем…' : 'Опубликовать сводку'}
      </button>
      {error ? (
        <span className="publication err">{error}</span>
      ) : (
        shown &&
        status && (
          <span className={`publication ${status.tone}`} title={shown.messageId}>
            Ревизия {shown.revision} {status.text}
            {shown.status === 'DELIVERED' && ` в ${formatTimeSeconds(shown.updatedAt)}`}
            {shown.reason && `: ${shown.reason}`}
          </span>
        )
      )}
    </div>
  );
}

function latest(a: Publication | null, b: Publication | null): Publication | null {
  if (!a || !b) {
    return a ?? b;
  }
  if (a.revision !== b.revision) {
    return a.revision > b.revision ? a : b;
  }
  return Date.parse(a.updatedAt) >= Date.parse(b.updatedAt) ? a : b;
}

const STATUS: Record<CallStatus, { text: string; tone: string }> = {
  CONNECTING: { text: 'Подключение', tone: 'muted' },
  TRACKING: { text: 'Онлайн', tone: 'ok' },
  RECONNECTING: { text: 'Нет связи', tone: 'warn' },
  ENDED: { text: 'Завершён', tone: 'muted' },
  FAILED: { text: 'Ошибка', tone: 'err' },
};

function Header({ snapshot }: { snapshot: CallSnapshot | null }) {
  const status = snapshot ? STATUS[snapshot.status] : null;
  return (
    <header className="top">
      <div className="headline">
        <span className="count" aria-label="Участников в звонке">
          {snapshot ? snapshot.present.length : '–'}
        </span>
        <div>
          <h1>Посещаемость</h1>
          <p>{snapshot?.status === 'ENDED' ? 'звонок завершён' : 'в звонке сейчас'}</p>
        </div>
      </div>
      <div className="badges">
        {snapshot?.source === 'fake' && (
          <span className="badge demo" title="Плагин запущен с attendance.source=fake: события генерируются">
            Демо
          </span>
        )}
        {status && <span className={`badge dot ${status.tone}`}>{status.text}</span>}
      </div>
    </header>
  );
}

interface Notice {
  tone: 'info' | 'warn' | 'err' | 'muted';
  text: string;
}

function noticesFor(snapshot: CallSnapshot | null, error: ApiError | null): Notice[] {
  const notices: Notice[] = [];
  if (error) {
    notices.push({
      tone: 'err',
      text: snapshot ? `${error.message}. Показаны данные на ${formatTime(snapshot.serverTime)}` : error.message,
    });
  }
  if (!snapshot) {
    return notices;
  }
  const reason = snapshot.statusMessage ? ` (${snapshot.statusMessage})` : '';
  switch (snapshot.status) {
    case 'CONNECTING':
      notices.push(
        snapshot.statusMessage
          ? { tone: 'warn', text: `Не удаётся подключиться к событиям звонка${reason}. Повторяем…` }
          : { tone: 'muted', text: 'Подключаемся к событиям звонка…' },
      );
      break;
    case 'RECONNECTING':
      notices.push({
        tone: 'warn',
        text: `Связь с платформой звонков потеряна${reason}, переподключаемся. Входы и выходы за это время могут не попасть в список`,
      });
      break;
    case 'FAILED':
      notices.push({ tone: 'err', text: `Платформа звонков отказала в подписке на события${reason}` });
      break;
    case 'ENDED':
      notices.push({
        tone: 'info',
        text: snapshot.endedAt ? `Звонок завершён в ${formatTime(snapshot.endedAt)}` : 'Звонок завершён',
      });
      break;
    case 'TRACKING': {
      const closedGaps = snapshot.gaps.filter((gap) => gap.to !== null);
      const last = closedGaps.at(-1);
      if (last?.to) {
        const count = closedGaps.length;
        const times = count > 1 ? ` ${count} ${plural(count, ['раз', 'раза', 'раз'])}, последний —` : '';
        notices.push({
          tone: 'warn',
          text: `Связь с платформой прерывалась${times} ${formatGap(last.from, last.to)}: список может быть неточным`,
        });
      }
      break;
    }
  }
  return notices;
}

function Lists({ snapshot, now, you }: { snapshot: CallSnapshot; now: number; you: string | null }) {
  const active = snapshot.status === 'TRACKING' || snapshot.status === 'RECONNECTING';
  const labels = labelsOf([...snapshot.present, ...snapshot.left]);
  const row = (participant: Participant) => (
    <PersonRow
      key={participant.participantId}
      participant={participant}
      label={labels.get(participant.participantId)!}
      now={now}
      you={you}
    />
  );

  return (
    <>
      {snapshot.trackingSince && (
        <p className="hint">
          {snapshot.status === 'ENDED' ? 'Учитывались' : 'Учитываются'} входы с {formatTime(snapshot.trackingSince)}:
          кто был в звонке раньше, {snapshot.status === 'ENDED' ? 'не попал в список' : 'появится после повторного входа'}.
        </p>
      )}
      {snapshot.present.length > 0 ? (
        <ul className="people">{snapshot.present.map(row)}</ul>
      ) : (
        active && <EmptyCall snapshot={snapshot} />
      )}
      {snapshot.left.length > 0 && (
        <details className="left" open={snapshot.status === 'ENDED'}>
          <summary>Покинули звонок · {snapshot.left.length}</summary>
          <ul className="people">{snapshot.left.map(row)}</ul>
        </details>
      )}
    </>
  );
}

/** Подписи участников; метку из identity добавляем, только если без неё имена не различить. */
function labelsOf(participants: Participant[]): Map<string, ParticipantLabel & { showTag: boolean }> {
  const labels = participants.map((participant) => ({ id: participant.participantId, label: labelFor(participant.participantId) }));
  const sameName = new Map<string, number>();
  for (const { label } of labels) {
    sameName.set(label.name, (sameName.get(label.name) ?? 0) + 1);
  }
  return new Map(
    labels.map(({ id, label }) => [
      id,
      { ...label, showTag: label.tag !== null && (label.generic || (sameName.get(label.name) ?? 0) > 1) },
    ]),
  );
}

function EmptyCall({ snapshot }: { snapshot: CallSnapshot }) {
  const suspiciousId = snapshot.source === 'streamapi' && !snapshot.callId.startsWith('RM_');
  return (
    <div className="empty">
      <p>Пока никто не вошёл</p>
      {suspiciousId && (
        <p className="tip">
          callId обычно выглядит как <code>RM_…</code>: это SID комнаты LiveKit, а не её имя
        </p>
      )}
    </div>
  );
}

function PersonRow({
  participant,
  label,
  now,
  you,
}: {
  participant: Participant;
  label: ParticipantLabel & { showTag: boolean };
  now: number;
  you: string | null;
}) {
  const { joinedAt, leftAt } = participant;
  let meta: string;
  if (leftAt === null) {
    meta = joinedAt ? `с ${formatTime(joinedAt)} · ${formatDuration(now - Date.parse(joinedAt))}` : 'в звонке';
  } else if (joinedAt) {
    meta = `${formatTime(joinedAt)}–${formatTime(leftAt)} · ${formatDuration(Date.parse(leftAt) - Date.parse(joinedAt))}`;
  } else {
    meta = `выход в ${formatTime(leftAt)} · вход до начала отслеживания`;
  }

  return (
    <li className={leftAt === null ? 'person' : 'person gone'} title={participant.participantId}>
      <span className="avatar" style={{ '--hue': label.hue } as CSSProperties} aria-hidden="true">
        {label.initials}
      </span>
      <div className="who">
        <div className="name">
          <span className="text">{label.name}</span>
          {label.showTag && <span className="tag">#{label.tag}</span>}
          {participant.participantId === you && <span className="you">вы</span>}
        </div>
        <div className="meta">{meta}</div>
      </div>
    </li>
  );
}
