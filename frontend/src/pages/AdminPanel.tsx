import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { adminApi } from '@/api/adminApi';
import { toApiError } from '@/api/client';
import { CircuitBreakerCard } from '@/components/CircuitBreakerCard';
import { DlqTable } from '@/components/DlqTable';
import { StatusDistributionChart } from '@/components/StatusDistributionChart';
import type {
  CircuitBreakerStatus,
  DlqEvent,
  Transaction,
} from '@/api/types';

const POLL_INTERVAL_MS = 3000;

/**
 * Admin-only operations panel. Polls three endpoints in parallel every 3 s
 * — DLQ list, circuit breaker status, and admin-scoped transactions for the
 * status-distribution chart. Same in-flight guard pattern as the dashboard
 * so a slow request never overlaps the next tick.
 */
export default function AdminPanel() {
  const [dlqEvents, setDlqEvents] = useState<DlqEvent[]>([]);
  const [dlqLoading, setDlqLoading] = useState(true);
  const [dlqError, setDlqError] = useState<string | null>(null);

  const [cbStatus, setCbStatus] = useState<CircuitBreakerStatus | null>(null);
  const [cbLoading, setCbLoading] = useState(true);
  const [cbError, setCbError] = useState<string | null>(null);

  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [txError, setTxError] = useState<string | null>(null);

  const inFlightRef = useRef(false);

  async function refresh() {
    if (inFlightRef.current) return;
    inFlightRef.current = true;
    try {
      const [dlqResult, cbResult, txResult] = await Promise.allSettled([
        adminApi.getDlqEvents(),
        adminApi.getCircuitBreakerStatus(),
        adminApi.getAllTransactions(),
      ]);

      if (dlqResult.status === 'fulfilled') {
        setDlqEvents(dlqResult.value.data);
        setDlqError(null);
      } else {
        setDlqError(toApiError(dlqResult.reason).message);
      }

      if (cbResult.status === 'fulfilled') {
        setCbStatus(cbResult.value);
        setCbError(null);
      } else {
        setCbError(toApiError(cbResult.reason).message);
      }

      if (txResult.status === 'fulfilled') {
        setTransactions(txResult.value.data);
        setTxError(null);
      } else {
        setTxError(toApiError(txResult.reason).message);
      }
    } finally {
      setDlqLoading(false);
      setCbLoading(false);
      inFlightRef.current = false;
    }
  }

  useEffect(() => {
    let cancelled = false;
    const tick = () => {
      if (!cancelled) void refresh();
    };
    tick();
    const id = window.setInterval(tick, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, []);

  async function handleRetry(id: string) {
    try {
      await adminApi.retryDlqEvent(id);
      // Trigger an immediate refresh so the resolved row reflects the new state
      // before the next poll tick.
      await refresh();
    } catch (err) {
      setDlqError(toApiError(err).message);
    }
  }

  return (
    <main className="page page--wide">
      <header className="page__header">
        <h1>Admin panel</h1>
        <Link to="/dashboard">Back to dashboard</Link>
      </header>

      <section className="page__section">
        <h2>Payment-gateway circuit breaker</h2>
        {cbError && (
          <p className="banner-error" role="alert">Circuit breaker: {cbError}</p>
        )}
        <CircuitBreakerCard status={cbStatus} loading={cbLoading} />
      </section>

      <section className="page__section">
        <h2>Transactions by status</h2>
        {txError && (
          <p className="banner-error" role="alert">Transactions: {txError}</p>
        )}
        <StatusDistributionChart transactions={transactions} />
      </section>

      <section className="page__section">
        <div className="section__header">
          <h2>Dead-letter queue</h2>
          <button type="button" onClick={() => void refresh()} disabled={inFlightRef.current}>
            Refresh
          </button>
        </div>
        {dlqError && (
          <p className="banner-error" role="alert">DLQ: {dlqError}</p>
        )}
        <DlqTable events={dlqEvents} loading={dlqLoading} onRetry={handleRetry} />
      </section>
    </main>
  );
}
