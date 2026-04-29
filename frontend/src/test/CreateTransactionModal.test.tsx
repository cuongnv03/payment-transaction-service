import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { transactionsApi } from '@/api/transactionsApi';
import { CreateTransactionModal } from '@/components/CreateTransactionModal';

vi.mock('@/api/transactionsApi', () => ({
  transactionsApi: {
    getMyAccount: vi.fn(),
    getMyTransactions: vi.fn(),
    createTransaction: vi.fn(),
  },
}));

const mockedCreate = vi.mocked(transactionsApi.createTransaction);

const VALID_TO_ACCOUNT = '33333333-3333-3333-3333-333333333333';

/**
 * jsdom v25 only partially implements <dialog>. {@code showModal} doesn't
 * trap focus or apply ::backdrop, but the {@code open} attribute toggles
 * and the {@code close} event fires — sufficient for behavioural tests.
 */
describe('CreateTransactionModal', () => {
  it('should_send_idempotency_key_header_and_payload_when_form_submitted', async () => {
    mockedCreate.mockResolvedValue({
      id: 'tx-new',
      fromAccountId: 'from',
      toAccountId: VALID_TO_ACCOUNT,
      amount: 50,
      currency: 'USD',
      status: 'PENDING',
      description: 'Lunch',
      gatewayReference: null,
      failureReason: null,
      retryCount: 0,
      processedAt: null,
      refundedAt: null,
      createdAt: '2026-04-28T12:00:00Z',
      updatedAt: '2026-04-28T12:00:00Z',
    });

    const onClose = vi.fn();
    const onCreated = vi.fn();
    render(<CreateTransactionModal onClose={onClose} onCreated={onCreated} />);

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/recipient account id/i), VALID_TO_ACCOUNT);
    await user.type(screen.getByLabelText(/^amount$/i), '50');
    await user.type(screen.getByLabelText(/description/i), 'Lunch');
    await user.click(screen.getByRole('button', { name: /^create$/i }));

    await waitFor(() => {
      expect(mockedCreate).toHaveBeenCalledTimes(1);
    });

    const [payload, idempotencyKey] = mockedCreate.mock.calls[0];
    expect(payload).toEqual({
      toAccountId: VALID_TO_ACCOUNT,
      amount: 50,
      description: 'Lunch',
    });
    // crypto.randomUUID() returns a UUID v4 — the variant nibble (14th hex
    // digit) is one of 8/9/a/b and the version nibble (13th) is 4.
    expect(idempotencyKey).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    );
    expect(onCreated).toHaveBeenCalledOnce();
  });

  it('should_send_same_idempotency_key_when_user_retries_after_a_failure', async () => {
    // First attempt fails with a network error; the user resubmits the same
    // form — the same key must be sent so the server (if it persisted the
    // first attempt) returns the original transaction instead of duplicating.
    mockedCreate
      .mockRejectedValueOnce({
        isAxiosError: true,
        response: { data: { code: 'NETWORK_ERROR', message: 'Service unavailable' } },
        message: 'Network Error',
      })
      .mockResolvedValueOnce({
        id: 'tx-retry',
        fromAccountId: 'from',
        toAccountId: VALID_TO_ACCOUNT,
        amount: 50,
        currency: 'USD',
        status: 'PENDING',
        description: null,
        gatewayReference: null,
        failureReason: null,
        retryCount: 0,
        processedAt: null,
        refundedAt: null,
        createdAt: '2026-04-28T12:00:00Z',
        updatedAt: '2026-04-28T12:00:00Z',
      });

    render(<CreateTransactionModal onClose={vi.fn()} />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/recipient account id/i), VALID_TO_ACCOUNT);
    await user.type(screen.getByLabelText(/^amount$/i), '50');

    await user.click(screen.getByRole('button', { name: /^create$/i }));
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/Service unavailable/i);
    });

    await user.click(screen.getByRole('button', { name: /^create$/i }));
    await waitFor(() => {
      expect(mockedCreate).toHaveBeenCalledTimes(2);
    });

    const firstKey = mockedCreate.mock.calls[0][1];
    const secondKey = mockedCreate.mock.calls[1][1];
    expect(secondKey).toBe(firstKey);
  });

  it('should_generate_a_new_idempotency_key_when_modal_remounts', async () => {
    mockedCreate.mockResolvedValue({
      id: 'tx',
      fromAccountId: 'from',
      toAccountId: VALID_TO_ACCOUNT,
      amount: 50,
      currency: 'USD',
      status: 'PENDING',
      description: null,
      gatewayReference: null,
      failureReason: null,
      retryCount: 0,
      processedAt: null,
      refundedAt: null,
      createdAt: '2026-04-28T12:00:00Z',
      updatedAt: '2026-04-28T12:00:00Z',
    });

    const fillAndSubmit = async () => {
      const user = userEvent.setup();
      await user.type(screen.getByLabelText(/recipient account id/i), VALID_TO_ACCOUNT);
      await user.type(screen.getByLabelText(/^amount$/i), '50');
      await user.click(screen.getByRole('button', { name: /^create$/i }));
    };

    const first = render(<CreateTransactionModal onClose={vi.fn()} />);
    await fillAndSubmit();
    await waitFor(() => expect(mockedCreate).toHaveBeenCalledTimes(1));
    first.unmount();

    render(<CreateTransactionModal onClose={vi.fn()} />);
    await fillAndSubmit();
    await waitFor(() => expect(mockedCreate).toHaveBeenCalledTimes(2));

    const firstKey = mockedCreate.mock.calls[0][1];
    const secondKey = mockedCreate.mock.calls[1][1];
    expect(secondKey).not.toBe(firstKey);
  });

  it('should_show_backend_error_message_when_create_fails_with_business_error', async () => {
    mockedCreate.mockRejectedValue({
      isAxiosError: true,
      response: {
        data: { code: 'INSUFFICIENT_FUNDS', message: 'Account balance is insufficient' },
      },
      message: 'Request failed with status code 422',
    });

    render(<CreateTransactionModal onClose={vi.fn()} />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/recipient account id/i), VALID_TO_ACCOUNT);
    await user.type(screen.getByLabelText(/^amount$/i), '999');
    await user.click(screen.getByRole('button', { name: /^create$/i }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Account balance is insufficient');
    });
  });
});
