import { render, screen } from '@testing-library/react';
import { StatusBadge } from '@/components/StatusBadge';
import type { TransactionStatus } from '@/api/types';

describe('StatusBadge', () => {
  const cases: TransactionStatus[] = [
    'PENDING',
    'PROCESSING',
    'SUCCESS',
    'FAILED',
    'TIMEOUT',
    'REFUNDED',
  ];

  it.each(cases)(
    'should_render_status_label_and_data_status_attribute_when_status_is_%s',
    (status) => {
      render(<StatusBadge status={status} />);
      const badge = screen.getByTestId('status-badge');
      expect(badge).toHaveTextContent(status);
      expect(badge).toHaveAttribute('data-status', status);
    },
  );

  it('should_render_with_status_badge_class_for_css_styling', () => {
    render(<StatusBadge status="SUCCESS" />);
    expect(screen.getByTestId('status-badge')).toHaveClass('status-badge');
  });
});
