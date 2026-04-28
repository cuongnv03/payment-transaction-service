import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { useAuthStore } from '@/store/auth';

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
