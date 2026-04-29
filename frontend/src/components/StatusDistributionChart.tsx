import { useMemo } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { Transaction, TransactionStatus } from '@/api/types';

/**
 * Bar chart of transaction count by status. Aggregation runs over whatever
 * page of admin transactions the parent passed in — the chart faithfully
 * reflects the current viewport, not the global system state.
 */
interface StatusDistributionChartProps {
  transactions: Transaction[];
}

const STATUS_ORDER: TransactionStatus[] = [
  'PENDING',
  'PROCESSING',
  'SUCCESS',
  'FAILED',
  'TIMEOUT',
  'REFUNDED',
];

const STATUS_COLOR: Record<TransactionStatus, string> = {
  PENDING:    '#f59e0b',
  PROCESSING: '#3b82f6',
  SUCCESS:    '#10b981',
  FAILED:     '#ef4444',
  TIMEOUT:    '#dc2626',
  REFUNDED:   '#6b7280',
};

export function StatusDistributionChart({ transactions }: StatusDistributionChartProps) {
  const data = useMemo(() => {
    const counts: Record<TransactionStatus, number> = {
      PENDING: 0, PROCESSING: 0, SUCCESS: 0, FAILED: 0, TIMEOUT: 0, REFUNDED: 0,
    };
    for (const tx of transactions) {
      counts[tx.status] = (counts[tx.status] ?? 0) + 1;
    }
    return STATUS_ORDER.map((status) => ({ status, count: counts[status] }));
  }, [transactions]);

  if (transactions.length === 0) {
    return <p className="tx-table__empty">No transactions to chart yet.</p>;
  }

  return (
    <div className="chart-container">
      <ResponsiveContainer width="100%" height={240}>
        <BarChart data={data} margin={{ top: 10, right: 16, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="status" tick={{ fontSize: 12 }} />
          <YAxis allowDecimals={false} tick={{ fontSize: 12 }} />
          <Tooltip />
          <Bar dataKey="count" name="Transactions">
            {data.map((entry) => (
              <Cell key={entry.status} fill={STATUS_COLOR[entry.status]} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
