import type { CircuitBreakerStatus } from '@/api/types';

/**
 * Snapshot card for the {@code payment-gateway} circuit breaker. Renders the
 * current state with state-keyed colour styling, plus the call-window
 * metrics. Failure / slow-call rates of {@code -1} indicate the sliding
 * window doesn't yet have enough calls — rendered as "—" rather than "-100%".
 */
interface CircuitBreakerCardProps {
  status: CircuitBreakerStatus | null;
  loading: boolean;
}

export function CircuitBreakerCard({ status, loading }: CircuitBreakerCardProps) {
  if (loading && status === null) {
    return <p>Loading circuit breaker status…</p>;
  }

  if (status === null) {
    return <p className="tx-table__empty">No circuit breaker data.</p>;
  }

  return (
    <div className="cb-card" data-state={status.state}>
      <div className="cb-card__title">
        <span>{status.name}</span>
        <span className="cb-card__state">{status.state}</span>
      </div>
      <dl className="cb-card__metrics">
        <div>
          <dt>Failure rate</dt>
          <dd>{formatRate(status.failureRate)}</dd>
        </div>
        <div>
          <dt>Slow-call rate</dt>
          <dd>{formatRate(status.slowCallRate)}</dd>
        </div>
        <div>
          <dt>Buffered calls</dt>
          <dd>{status.bufferedCalls}</dd>
        </div>
        <div>
          <dt>Failed calls</dt>
          <dd>{status.failedCalls}</dd>
        </div>
      </dl>
    </div>
  );
}

function formatRate(rate: number): string {
  if (rate < 0) return '—';
  return `${rate.toFixed(1)}%`;
}
