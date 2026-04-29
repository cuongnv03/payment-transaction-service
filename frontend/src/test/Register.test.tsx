import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { authApi } from '@/api/authApi';
import Register from '@/pages/Register';
import { useAuthStore } from '@/store/auth';

vi.mock('@/api/authApi', () => ({
  authApi: {
    register: vi.fn(),
    login: vi.fn(),
  },
}));

const mockedRegister = vi.mocked(authApi.register);

function renderRegister() {
  return render(
    <MemoryRouter initialEntries={['/register']}>
      <Routes>
        <Route path="/register" element={<Register />} />
        <Route path="/dashboard" element={<div>DASHBOARD PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Register', () => {
  it('should_auto_login_and_navigate_home_when_registration_succeeds', async () => {
    mockedRegister.mockResolvedValue({
      token: 'jwt-new',
      userId: '33333333-3333-3333-3333-333333333333',
      username: 'newbie',
      role: 'USER',
    });

    renderRegister();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/username/i), 'newbie');
    await user.type(screen.getByLabelText(/email/i), 'newbie@example.com');
    await user.type(screen.getByLabelText(/password/i), 'password123');
    await user.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => {
      expect(screen.getByText('DASHBOARD PAGE')).toBeInTheDocument();
    });

    expect(mockedRegister).toHaveBeenCalledWith({
      username: 'newbie',
      email: 'newbie@example.com',
      password: 'password123',
    });
    expect(useAuthStore.getState().token).toBe('jwt-new');
  });

  it('should_show_backend_error_when_username_or_email_already_taken', async () => {
    mockedRegister.mockRejectedValue({
      isAxiosError: true,
      response: { data: { code: 'USER_ALREADY_EXISTS', message: 'Username already in use' } },
      message: 'Request failed with status code 409',
    });

    renderRegister();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/username/i), 'taken');
    await user.type(screen.getByLabelText(/email/i), 'taken@example.com');
    await user.type(screen.getByLabelText(/password/i), 'password123');
    await user.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Username already in use');
    });
    expect(useAuthStore.getState().token).toBeNull();
  });
});
