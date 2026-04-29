import { FormEvent, useEffect, useRef, useState } from 'react';
import { toApiError } from '@/api/client';
import { transactionsApi } from '@/api/transactionsApi';

interface CreateTransactionModalProps {
  /** Called when the user closes the modal (Esc, cancel button, or successful submit). */
  onClose: () => void;
  /** Called immediately after a successful create — caller can refetch the list early
   *  if it doesn't want to wait for the next poll tick. */
  onCreated?: () => void;
}

/**
 * Form modal for creating a P2P transaction.
 *
 * <p>Mounted only when the user opens it (parent uses conditional rendering),
 * so each opening is a fresh component instance with a freshly-generated
 * {@code Idempotency-Key}. Resubmits of the same form (e.g. after a network
 * error) re-use the same key — the backend's idempotency check returns the
 * original result instead of creating a duplicate.
 *
 * <p>Uses the native {@code <dialog>} element for accessibility (focus trap,
 * Esc-to-close, aria-modal) — no portal, no focus-management library.
 */
export function CreateTransactionModal({ onClose, onCreated }: CreateTransactionModalProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  const [toAccountId, setToAccountId] = useState('');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // One key per modal opening — survives form retries, regenerates on next open.
  // Lazy useState initializer runs exactly once per mount.
  const [idempotencyKey] = useState(() => crypto.randomUUID());

  // Open the native dialog after mount.
  useEffect(() => {
    dialogRef.current?.showModal();
  }, []);

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await transactionsApi.createTransaction(
        {
          toAccountId: toAccountId.trim(),
          amount: Number(amount),
          description: description.trim() || undefined,
        },
        idempotencyKey,
      );
      onCreated?.();
      // Native close — fires the dialog's `close` event, which calls onClose.
      dialogRef.current?.close();
    } catch (err) {
      setError(toApiError(err).message);
    } finally {
      setSubmitting(false);
    }
  }

  function handleCancel() {
    dialogRef.current?.close();
  }

  return (
    <dialog
      ref={dialogRef}
      className="modal"
      onClose={onClose}
      aria-labelledby="create-tx-title"
    >
      <h2 id="create-tx-title">New transaction</h2>

      <form onSubmit={handleSubmit} className="form" aria-label="create transaction form">
        <label>
          Recipient account ID
          <input
            name="toAccountId"
            type="text"
            value={toAccountId}
            onChange={(e) => setToAccountId(e.target.value)}
            required
            // UUID v4: 8-4-4-4-12 hex characters, RFC 4122 layout
            pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
            placeholder="00000000-0000-0000-0000-000000000000"
            disabled={submitting}
          />
        </label>

        <label>
          Amount
          <input
            name="amount"
            type="number"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            required
            min="0.01"
            step="0.01"
            disabled={submitting}
          />
        </label>

        <label>
          Description (optional)
          <input
            name="description"
            type="text"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            maxLength={255}
            disabled={submitting}
          />
        </label>

        {error && (
          <p className="form__error" role="alert">
            {error}
          </p>
        )}

        <div className="modal__actions">
          <button type="button" onClick={handleCancel} disabled={submitting}>
            Cancel
          </button>
          <button type="submit" disabled={submitting}>
            {submitting ? 'Creating…' : 'Create'}
          </button>
        </div>
      </form>
    </dialog>
  );
}
