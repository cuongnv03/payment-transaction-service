import type { Transaction } from '@/api/types';
import { StatusBadge } from './StatusBadge';

/**
 * Pure presentational table. Receives a list of transactions and renders rows.
 * Empty state is rendered when {@code transactions} is empty AND
 * {@code loading} is false — during the very first load the parent shows
 * its own loading state.
 */
interface TransactionTableProps {
  transactions: Transaction[];
  loading: boolean;
}

export function TransactionTable({ transactions, loading }: TransactionTableProps) {
  if (loading && transactions.length === 0) {
    return <p>Loading transactions…</p>;
  }

  if (transactions.length === 0) {
    return <p className="tx-table__empty">No transactions yet.</p>;
  }

  return (
    <table className="tx-table" aria-label="transactions">
      <thead>
        <tr>
          <th>Created</th>
          <th>Status</th>
          <th>Amount</th>
          <th>To account</th>
          <th>Description</th>
        </tr>
      </thead>
      <tbody>
        {transactions.map((tx) => (
          <tr key={tx.id}>
            <td title={tx.createdAt}>{formatTimestamp(tx.createdAt)}</td>
            <td>
              <StatusBadge status={tx.status} />
            </td>
            <td>{formatAmount(tx.amount, tx.currency)}</td>
            <td>
              <code title={tx.toAccountId}>{shortId(tx.toAccountId)}</code>
            </td>
            <td>
              {tx.description ?? <span className="tx-table__missing">—</span>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function formatTimestamp(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

function formatAmount(amount: number, currency: string): string {
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency,
    }).format(amount);
  } catch {
    // Intl throws on unknown currency codes — fall back to plain decimal + code.
    return `${amount.toFixed(2)} ${currency}`;
  }
}

function shortId(id: string): string {
  return id.length <= 12 ? id : `${id.slice(0, 8)}…${id.slice(-4)}`;
}
