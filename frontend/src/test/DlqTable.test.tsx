import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DlqTable } from '@/components/DlqTable';
import type { DlqEvent } from '@/api/types';

function makeEvent(overrides: Partial<DlqEvent> = {}): DlqEvent {
  return {
    id: 'dlq-1',
    topic: 'payment.transaction.events',
    kafkaPartition: 0,
    kafkaOffset: 42,
    payload: '{"transactionId":"abc","eventType":"PROCESSING"}',
    transactionId: 'abc12345-abcd-abcd-abcd-abcdef123456',
    eventType: 'PROCESSING',
    errorMessage: 'SIMULATED_FAILURE',
    retryCount: 0,
    createdAt: '2026-04-28T12:00:00Z',
    resolvedAt: null,
    resolvedBy: null,
    ...overrides,
  };
}

describe('DlqTable', () => {
  it('should_show_loading_message_when_loading_and_no_events_yet', () => {
    render(<DlqTable events={[]} loading={true} onRetry={vi.fn()} />);
    expect(screen.getByText(/loading dead-letter events/i)).toBeInTheDocument();
  });

  it('should_show_empty_state_when_no_events_and_not_loading', () => {
    render(<DlqTable events={[]} loading={false} onRetry={vi.fn()} />);
    expect(screen.getByText(/no dead-letter events/i)).toBeInTheDocument();
  });

  it('should_render_a_retry_button_only_for_unresolved_events', () => {
    const events = [
      makeEvent({ id: 'unresolved' }),
      makeEvent({
        id: 'resolved',
        resolvedAt: '2026-04-28T12:30:00Z',
        resolvedBy: 'admin-retry',
      }),
    ];
    render(<DlqTable events={events} loading={false} onRetry={vi.fn()} />);
    const retryButtons = screen.getAllByRole('button', { name: /retry/i });
    expect(retryButtons).toHaveLength(1);
  });

  it('should_call_onRetry_with_event_id_when_retry_button_clicked', async () => {
    const onRetry = vi.fn().mockResolvedValue(undefined);
    render(<DlqTable events={[makeEvent({ id: 'dlq-77' })]} loading={false} onRetry={onRetry} />);

    await userEvent.setup().click(screen.getByRole('button', { name: /retry/i }));

    await waitFor(() => {
      expect(onRetry).toHaveBeenCalledWith('dlq-77');
    });
  });

  it('should_disable_retry_button_while_retry_is_in_flight', async () => {
    let resolveRetry: () => void = () => {};
    const onRetry = vi.fn().mockReturnValue(
      new Promise<void>((resolve) => {
        resolveRetry = resolve;
      }),
    );

    render(<DlqTable events={[makeEvent()]} loading={false} onRetry={onRetry} />);
    const button = screen.getByRole('button', { name: /retry/i });

    await userEvent.setup().click(button);

    // While the promise is pending, button shows "Retrying…" and is disabled
    expect(button).toBeDisabled();
    expect(button).toHaveTextContent(/retrying/i);

    resolveRetry();
    await waitFor(() => {
      expect(button).not.toBeDisabled();
    });
  });

  it('should_render_unparseable_label_when_event_type_is_null', () => {
    render(
      <DlqTable
        events={[makeEvent({ eventType: null, transactionId: null })]}
        loading={false}
        onRetry={vi.fn()}
      />,
    );
    expect(screen.getByText(/unparseable/i)).toBeInTheDocument();
  });
});
