import { render, screen } from '@testing-library/react';
import { CircuitBreakerCard } from '@/components/CircuitBreakerCard';
import type { CircuitBreakerStatus } from '@/api/types';

function makeStatus(overrides: Partial<CircuitBreakerStatus> = {}): CircuitBreakerStatus {
  return {
    name: 'payment-gateway',
    state: 'CLOSED',
    failureRate: 5.5,
    slowCallRate: 12.3,
    bufferedCalls: 10,
    failedCalls: 1,
    ...overrides,
  };
}

describe('CircuitBreakerCard', () => {
  it('should_show_loading_message_when_status_is_null_and_loading', () => {
    render(<CircuitBreakerCard status={null} loading={true} />);
    expect(screen.getByText(/loading circuit breaker status/i)).toBeInTheDocument();
  });

  it('should_render_state_and_metrics_when_status_present', () => {
    render(<CircuitBreakerCard status={makeStatus()} loading={false} />);
    expect(screen.getByText('payment-gateway')).toBeInTheDocument();
    expect(screen.getByText('CLOSED')).toBeInTheDocument();
    expect(screen.getByText('5.5%')).toBeInTheDocument();
    expect(screen.getByText('12.3%')).toBeInTheDocument();
    expect(screen.getByText('10')).toBeInTheDocument(); // bufferedCalls
    expect(screen.getByText('1')).toBeInTheDocument();  // failedCalls
  });

  it('should_render_dash_when_rate_is_negative_one_meaning_insufficient_data', () => {
    render(
      <CircuitBreakerCard
        status={makeStatus({ failureRate: -1, slowCallRate: -1 })}
        loading={false}
      />,
    );
    // Both rates render as "—"
    const dashes = screen.getAllByText('—');
    expect(dashes).toHaveLength(2);
  });

  it('should_carry_data_state_attribute_for_state_keyed_styling', () => {
    const { container } = render(
      <CircuitBreakerCard status={makeStatus({ state: 'OPEN' })} loading={false} />,
    );
    const card = container.querySelector('.cb-card');
    expect(card).toHaveAttribute('data-state', 'OPEN');
  });
});
