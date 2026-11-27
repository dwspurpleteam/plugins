import { useEffect, useRef, useState } from 'react';
import { downloadReport, type ReportFormat } from './api';

export function ReportDownload({ callId }: { callId: string }) {
  const [format, setFormat] = useState<ReportFormat>('pdf');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [prepared, setPrepared] = useState(false);
  const request = useRef<AbortController | null>(null);
  const timezone = browserTimezone();

  useEffect(() => () => request.current?.abort(), []);

  async function download() {
    if (request.current) return;
    const controller = new AbortController();
    request.current = controller;
    setPending(true);
    setError(null);
    setPrepared(false);
    try {
      await downloadReport(callId, format, timezone, controller.signal);
      if (!controller.signal.aborted) setPrepared(true);
    } catch (failure) {
      if (!controller.signal.aborted) {
        setError(failure instanceof Error ? failure.message : 'Не удалось скачать отчёт. Попробуйте ещё раз.');
      }
    } finally {
      request.current = null;
      if (!controller.signal.aborted) setPending(false);
    }
  }

  function changeFormat(value: ReportFormat) {
    setFormat(value);
    setPrepared(false);
    setError(null);
  }

  return (
    <div className="report" aria-label="Выгрузка посещаемости">
      <div className="report-controls">
        <label htmlFor="report-format">Отчёт</label>
        <select id="report-format" value={format} disabled={pending}
          onChange={(event) => changeFormat(event.target.value as ReportFormat)}>
          <option value="pdf">PDF</option>
          <option value="csv">CSV (Excel)</option>
        </select>
        <button type="button" onClick={download} disabled={pending} aria-busy={pending}
          title={`Скачать историю посещений. Часовой пояс: ${timezone}`}>
          {pending ? 'Подготовка…' : 'Скачать'}
        </button>
      </div>
      {error && <p className="report-error" role="alert">{error}</p>}
      {/* A click hands the file to the browser; it cannot confirm that the browser saved it. */}
      <span className="report-status" role="status">{prepared ? 'Файл отчёта подготовлен' : ''}</span>
    </div>
  );
}

function browserTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  } catch {
    return 'UTC';
  }
}
