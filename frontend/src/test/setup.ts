import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { useAuthStore } from '@/store/auth';

/**
 * Polyfill <dialog>.showModal() and .close() for jsdom 25.
 *
 * jsdom 25 recognises HTMLDialogElement but does not implement the two methods
 * — they landed in jsdom 26. {@link CreateTransactionModal} calls showModal()
 * inside a useEffect on mount; without the polyfill every modal test crashes
 * with "showModal is not a function".
 *
 * This polyfill is intentionally minimal: just toggle the {@code open} attribute
 * and dispatch the {@code close} event. No focus trap, no inert behaviour, no
 * ::backdrop — none of which our component tests assert on. Remove this block
 * when jsdom is bumped to 26+.
 */
if (typeof HTMLDialogElement !== 'undefined') {
  if (typeof HTMLDialogElement.prototype.showModal !== 'function') {
    HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  }
  if (typeof HTMLDialogElement.prototype.close !== 'function') {
    HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
      this.removeAttribute('open');
      this.dispatchEvent(new Event('close'));
    };
  }
}

/**
 * Global test setup.
 *
 * - Imports jest-dom matchers (toBeInTheDocument, toHaveValue, ...).
 * - Cleans up the rendered DOM between tests (Testing Library's @testing-library/react
 *   does this automatically via afterEach when running with vitest's globals).
 * - Resets the persisted auth store between tests so test order doesn't matter.
 */
afterEach(() => {
  cleanup();
  useAuthStore.getState().clear();
  localStorage.clear();
});
