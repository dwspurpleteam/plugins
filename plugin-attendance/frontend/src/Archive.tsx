import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { downloadReport, fetchArchive, type ReportFormat } from './api';
import { plural } from './time';
import type { ArchiveCall, ArchivePage, ArchiveQuery } from './types';

interface ArchiveProps {
  initialParticipantId?: string | null;
  onParticipantIdChange?: (participantId: string) => void;
}

interface Search {
  participantId: string;
  query: ArchiveQuery;
}

interface DownloadState {
  pending?: boolean;
  error?: string;
  prepared?: boolean;
}

export function Archive({ initialParticipantId, onParticipantIdChange }: ArchiveProps) {
  const formId = useId();
  const timezone = useMemo(browserTimezone, []);
  const [participantId, setParticipantId] = useState(initialParticipantId ?? '');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [format, setFormat] = useState<ReportFormat>('pdf');
  const [search, setSearch] = useState<Search | null>(null);
  const [page, setPage] = useState<ArchivePage | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [downloads, setDownloads] = useState<Record<string, DownloadState>>({});
  const searchRequest = useRef<AbortController | null>(null);
  const downloadRequests = useRef(new Map<string, AbortController>());

  useEffect(() => () => {
    searchRequest.current?.abort();
    for (const request of downloadRequests.current.values()) request.abort();
    downloadRequests.current.clear();
  }, []);

  function cancelRequests() {
    searchRequest.current?.abort();
    searchRequest.current = null;
    for (const request of downloadRequests.current.values()) request.abort();
    downloadRequests.current.clear();
    setDownloads({});
  }

  function clearResults() {
    cancelRequests();
    setSearch(null);
    setPage(null);
    setPending(false);
    setError(null);
  }

  async function load(nextSearch: Search) {
    cancelRequests();
    const controller = new AbortController();
    searchRequest.current = controller;
    setSearch(nextSearch);
    setPage(null);
    setError(null);
    setPending(true);
    try {
      const result = await fetchArchive(nextSearch.participantId, nextSearch.query, controller.signal);
      if (!controller.signal.aborted) setPage(result);
    } catch (failure) {
      if (!controller.signal.aborted) {
        setError(errorMessage(failure, 'Не удалось загрузить архив. Попробуйте ещё раз.'));
      }
    } finally {
      if (searchRequest.current === controller) {
        searchRequest.current = null;
        setPending(false);
      }
    }
  }

  function submit() {
    const subject = participantId.trim();
    clearResults();
    if (!subject) {
      setError('Укажите логин.');
      return;
    }
    if (from && to && from > to) {
      setError('Дата «С» должна быть не позднее даты «По».');
      return;
    }
    setParticipantId(subject);
    onParticipantIdChange?.(subject);
    void load({ participantId: subject, query: { page: 1, limit: 10, from: from || undefined, to: to || undefined, timezone } });
  }

  async function download(callId: string) {
    if (!search || downloadRequests.current.has(callId)) return;
    const controller = new AbortController();
    downloadRequests.current.set(callId, controller);
    setDownloads((current) => ({ ...current, [callId]: { pending: true } }));
    try {
      await downloadReport(callId, format, timezone, controller.signal, search.participantId);
      if (!controller.signal.aborted) {
        setDownloads((current) => ({ ...current, [callId]: { prepared: true } }));
      }
    } catch (failure) {
      if (!controller.signal.aborted) {
        setDownloads((current) => ({ ...current, [callId]: {
          error: errorMessage(failure, 'Не удалось скачать отчёт. Попробуйте ещё раз.'),
        } }));
      }
    } finally {
      if (downloadRequests.current.get(callId) === controller) downloadRequests.current.delete(callId);
    }
  }

  const downloading = Object.values(downloads).some((download) => download.pending);

  return (
    <section className="archive" aria-label="Архив посещаемости">
      <div className="archive-content">
        <h1>Архив посещаемости</h1>
        <p className="archive-intro">Укажите логин, с которым вы участвовали в звонке.</p>

        {/* Meet allows scripts but blocks native form submissions in the sandboxed iframe. */}
        <div className="archive-form" role="search" aria-label="Поиск в архиве"
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.nativeEvent.isComposing && event.target instanceof HTMLInputElement) {
              event.preventDefault();
              submit();
            }
          }}>
          <label htmlFor={`${formId}-login`}>Логин</label>
          <div className="archive-search">
            <input id={`${formId}-login`} value={participantId} required autoCapitalize="none"
              autoCorrect="off" spellCheck={false} autoComplete="username"
              onChange={(event) => { clearResults(); setParticipantId(event.target.value); }} />
            <button type="button" onClick={submit}>Показать</button>
          </div>
          <details className="archive-filters">
            <summary>Период звонков</summary>
            <div className="archive-dates">
              <label htmlFor={`${formId}-from`}>С
                <input id={`${formId}-from`} type="date" value={from} max={to || undefined}
                  onChange={(event) => { clearResults(); setFrom(event.target.value); }} />
              </label>
              <label htmlFor={`${formId}-to`}>По
                <input id={`${formId}-to`} type="date" value={to} min={from || undefined}
                  onChange={(event) => { clearResults(); setTo(event.target.value); }} />
              </label>
            </div>
            <p className="archive-note">По дате начала учёта. Часовой пояс: {timezone}.</p>
          </details>
        </div>

        <div aria-busy={pending}>
          {pending && <p className="archive-message" role="status">Загружаем архив…</p>}
          {error && <p className="archive-error" role="alert">{error}</p>}
          {page && search && <>
            <div className="archive-results-heading">
              <p role="status">{page.total} {plural(page.total, ['звонок', 'звонка', 'звонков'])}</p>
              {page.items.length > 0 && <label htmlFor={`${formId}-format`}>Формат
                <select id={`${formId}-format`} value={format} disabled={downloading}
                  onChange={(event) => { setFormat(event.target.value as ReportFormat); setDownloads({}); }}>
                  <option value="pdf">PDF</option>
                  <option value="csv">CSV (Excel)</option>
                </select>
              </label>}
            </div>
            {page.items.length === 0
              ? <p className="archive-message">Звонки не найдены. Попробуйте другой логин или период.</p>
              : <ul className="archive-calls">{page.items.map((call) => (
                <ArchiveCard key={call.callId} call={call} download={downloads[call.callId]}
                  onDownload={() => { void download(call.callId); }} />
              ))}</ul>}
            {page.totalPages > 1 && <nav className="archive-pagination" aria-label="Страницы архива">
              <button type="button" disabled={page.page <= 1}
                onClick={() => { void load({ ...search, query: { ...search.query, page: page.page - 1 } }); }}>Назад</button>
              <span>{page.page} / {page.totalPages}</span>
              <button type="button" disabled={page.page >= page.totalPages}
                onClick={() => { void load({ ...search, query: { ...search.query, page: page.page + 1 } }); }}>Далее</button>
            </nav>}
            <p className="archive-note archive-retention">{retentionText(page.retentionSeconds)}</p>
          </>}
        </div>
      </div>
    </section>
  );
}

function ArchiveCard({ call, download, onDownload }: {
  call: ArchiveCall;
  download?: DownloadState;
  onDownload: () => void;
}) {
  const started = call.trackingSince ?? call.activatedAt;
  const finished = call.endedAt ?? call.stoppedAt;
  return (
    <li className="archive-call">
      <h2>Звонок от {dateText(started)}</h2>
      <p className="archive-call-time">{timeText(started)}{finished ? ` — ${endText(started, finished)}` : ''}</p>
      <p className="archive-call-id" title={call.callId}>{call.callId}</p>
      <div className="archive-call-meta">
        <span>{call.participantCount} {plural(call.participantCount, ['участник', 'участника', 'участников'])}</span>
        <span>{statusText(call)}</span>
      </div>
      {call.hasGaps && <p className="archive-call-warning">В отслеживании были перерывы.</p>}
      <button type="button" className="archive-download" onClick={onDownload}
        disabled={!call.reportAvailable || download?.pending} aria-busy={download?.pending}
        aria-label={`Скачать отчёт за ${dateText(started)}, ${timeText(started)}`}>
        {download?.pending ? 'Подготовка…' : call.reportAvailable ? 'Скачать отчёт' : 'Отчёт пока недоступен'}
      </button>
      {download?.error && <p className="archive-error" role="alert">{download.error}</p>}
      {download?.prepared && <p className="archive-note" role="status">Файл отчёта подготовлен.</p>}
    </li>
  );
}

function statusText(call: ArchiveCall): string {
  if (call.endedAt || call.status === 'ENDED') return 'Завершён';
  if (call.stoppedAt || call.status === 'FAILED' || call.status === 'STOPPED') return 'Отслеживание остановлено';
  if (call.status === 'CONNECTING' || call.status === 'RECONNECTING') return 'Ожидание соединения';
  return 'Идёт звонок';
}

const dateFormat = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });
const timeFormat = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' });

function dateText(iso: string): string {
  return dateFormat.format(new Date(iso));
}

function timeText(iso: string): string {
  return timeFormat.format(new Date(iso));
}

function endText(start: string, end: string): string {
  return dateText(start) === dateText(end) ? timeText(end) : `${dateText(end)}, ${timeText(end)}`;
}

function retentionText(seconds: number): string {
  if (seconds === 0) return 'Отчёты хранятся без ограничения срока.';
  const days = seconds / 86_400;
  if (Number.isInteger(days)) return `Отчёты хранятся ${days} ${plural(days, ['день', 'дня', 'дней'])} после завершения отслеживания.`;
  const hours = seconds / 3_600;
  if (Number.isInteger(hours)) return `Отчёты хранятся ${hours} ${plural(hours, ['час', 'часа', 'часов'])} после завершения отслеживания.`;
  return `Срок хранения отчётов после завершения отслеживания: ${seconds.toLocaleString('ru-RU')} с.`;
}

function browserTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}

function errorMessage(failure: unknown, fallback: string): string {
  return failure instanceof Error ? failure.message : fallback;
}
