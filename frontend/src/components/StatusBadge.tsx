import type { TransactionStatus } from '@/api/types';

/**
 * A small coloured pill rendering a transaction status.
 *
 * <p>Colours follow the convention success = green, in-flight = blue/orange,
 * terminal failure = red, compensating = grey. Defined as CSS attribute
 * selectors on {@code data-status} in {@code styles.css} so the visual rules
 * live with the rest of the stylesheet.
 */
interface StatusBadgeProps {
  status: TransactionStatus;
}

export function StatusBadge({ status }: StatusBadgeProps) {
  return (
    <span className="status-badge" data-testid="status-badge" data-status={status}>
      {status}
    </span>
  );
}
