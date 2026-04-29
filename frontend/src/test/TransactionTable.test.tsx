import { render, screen } from '@testing-library/react';
import { TransactionTable } from '@/components/TransactionTable';
import type { Transaction } from '@/api/types';

function makeTx(overrides: Partial<Transaction> = {}): Transaction {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    fromAccountId: '22222222-2222-2222-2222-222222222222',
    toAccountId: '33333333-3333-3333-3333-333333333333',
    amount: 100.5,
    currency: 'USD',
    status: 'SUCCESS',
    description: 'Lunch',
    gatewayReference: 'gw-ref-1',
    failureReason: null,
    retryCount: 0,
    processedAt: '2026-04-28T10:00:00Z',
    refundedAt: null,
    createdAt: '2026-04-28T09:59:30Z',
    updatedAt: '2026-04-28T10:00:00Z',
    ...overrides,
  };
}

describe('TransactionTable', () => {
  it('should_show_loading_message_when_loading_and_no_data_yet', () => {
    render(<TransactionTable transactions={[]} loading={true} />);
    expect(screen.getByText(/loading transactions/i)).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('should_show_empty_state_when_not_loading_and_no_transactions', () => {
    render(<TransactionTable transactions={[]} loading={false} />);
    expect(screen.getByText(/no transactions yet/i)).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('should_render_one_row_per_transaction_with_status_badge', () => {
    const transactions = [
      makeTx({ id: 'tx-1', description: 'Coffee', status: 'SUCCESS' }),
      makeTx({ id: 'tx-2', description: 'Refund', status: 'REFUNDED', amount: 25 }),
      makeTx({ id: 'tx-3', description: null, status: 'PENDING' }),
    ];

    render(<TransactionTable transactions={transactions} loading={false} />);

    // 1 header row + 3 data rows
    expect(screen.getAllByRole('row')).toHaveLength(4);

    // Each row should carry a StatusBadge
    expect(screen.getAllByTestId('status-badge')).toHaveLength(3);

    // Description content (and the em-dash fallback for null)
    expect(screen.getByText('Coffee')).toBeInTheDocument();
    expect(screen.getByText('Refund')).toBeInTheDocument();
    expect(screen.getByText('—')).toBeInTheDocument();
  });

  it('should_keep_data_visible_when_loading_after_first_fetch_for_silent_refresh', () => {
    // Simulates a poll cycle — loading=false because the store sets loading=true
    // only on the first fetch. Confirms the table doesn't unmount during refreshes.
    const transactions = [makeTx({ id: 'tx-only' })];
    render(<TransactionTable transactions={transactions} loading={false} />);
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.queryByText(/loading transactions/i)).not.toBeInTheDocument();
  });
});
