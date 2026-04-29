import { useState } from 'react';
import type { DlqEvent } from '@/api/types';

/**
 * Pure presentational DLQ list. Each row gets a "Retry" button when the
 * event is unresolved; clicking it calls {@code onRetry} and disables the
 * button until the parent's mutation settles (the parent re-fetches and
 * the row either disappears or shows a `resolvedAt` timestamp).
 */
interface DlqTableProps {
  events: DlqEvent[];
  loading: boolean;
  onRetry: (id: string) => Promise<void>;
}

export function DlqTable({ events, loading, onRetry }: DlqTableProps) {
  const [pendingId, setPendingId] = useState<string | null>(null);

  if (loading && events.length === 0) {
    return <p>Loading dead-letter events…</p>;
  }

  if (events.length === 0) {
    return <p className="tx-table__empty">No dead-letter events. 🎉</p>;
  }

  async function handleRetry(id: string) {
    setPendingId(id);
    try {
      await onRetry(id);
    } finally {
      setPendingId(null);
    }
  }

  return (
    <table className="tx-table" aria-label="dead-letter events">
      <thead>
        <tr>
          <th>Created</th>
          <th>Event type</th>
          <th>Transaction</th>
          <th>Error</th>
          <th>Resolved</th>
          <th aria-label="actions" />
        </tr>
      </thead>
      <tbody>
        {events.map((e) => {
          const resolved = e.resolvedAt !== null;
          const isPending = pendingId === e.id;
          return (
            <tr key={e.id} data-resolved={resolved}>
              <td title={e.createdAt}>{formatTimestamp(e.createdAt)}</td>
              <td>{e.eventType ?? <span className="tx-table__missing">unparseable</span>}</td>
              <td>
                {e.transactionId ? (
                  <code title={e.transactionId}>{shortId(e.transactionId)}</code>
                ) : (
                  <span className="tx-table__missing">—</span>
                )}
              </td>
              <td title={e.errorMessage}>{truncate(e.errorMessage, 80)}</td>
              <td>
                {resolved ? (
                  <span title={`${e.resolvedAt} by ${e.resolvedBy}`}>
                    {formatTimestamp(e.resolvedAt!)}
                  </span>
                ) : (
                  <span className="tx-table__missing">—</span>
                )}
              </td>
              <td>
                {!resolved && (
                  <button
                    type="button"
                    onClick={() => void handleRetry(e.id)}
                    disabled={isPending}
                  >
                    {isPending ? 'Retrying…' : 'Retry'}
                  </button>
                )}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

function formatTimestamp(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

function shortId(id: string): string {
  return id.length <= 12 ? id : `${id.slice(0, 8)}…${id.slice(-4)}`;
}

function truncate(s: string, max: number): string {
  return s.length <= max ? s : `${s.slice(0, max - 1)}…`;
}
