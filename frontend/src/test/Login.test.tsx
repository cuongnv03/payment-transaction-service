import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { authApi } from '@/api/authApi';
import Login from '@/pages/Login';
import { useAuthStore } from '@/store/auth';

vi.mock('@/api/authApi', () => ({
  authApi: {
    register: vi.fn(),
    login: vi.fn(),
  },
}));

const mockedLogin = vi.mocked(authApi.login);

function renderLogin(initialEntries: string[] = ['/login']) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/home" element={<div>HOME PAGE</div>} />
        <Route path="/admin" element={<div>ADMIN PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Login', () => {
  it('should_store_token_and_navigate_home_when_credentials_are_valid', async () => {
    mockedLogin.mockResolvedValue({
      token: 'jwt-abc',
      userId: '11111111-1111-1111-1111-111111111111',
      username: 'alice',
      role: 'USER',
    });

    renderLogin();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/password/i), 'password123');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => {
      expect(screen.getByText('HOME PAGE')).toBeInTheDocument();
    });

    expect(mockedLogin).toHaveBeenCalledWith({
      username: 'alice',
      password: 'password123',
    });
    expect(useAuthStore.getState().token).toBe('jwt-abc');
    expect(useAuthStore.getState().username).toBe('alice');
  });

  it('should_show_backend_error_message_when_login_fails', async () => {
    mockedLogin.mockRejectedValue({
      isAxiosError: true,
      response: { data: { code: 'INVALID_CREDENTIALS', message: 'Bad username or password' } },
      message: 'Request failed with status code 401',
    });

    renderLogin();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/username/i), 'alice');
    await user.type(screen.getByLabelText(/password/i), 'wrongpass');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Bad username or password');
    });
    expect(useAuthStore.getState().token).toBeNull();
  });

  it('should_navigate_to_originally_requested_path_when_login_succeeds_after_redirect', async () => {
    mockedLogin.mockResolvedValue({
      token: 'jwt-xyz',
      userId: '22222222-2222-2222-2222-222222222222',
      username: 'admin',
      role: 'ADMIN',
    });

    // Simulate ProtectedRoute bouncing the user from /admin to /login with state.from
    render(
      <MemoryRouter
        initialEntries={[{ pathname: '/login', state: { from: '/admin' } }]}
      >
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/home" element={<div>HOME PAGE</div>} />
          <Route path="/admin" element={<div>ADMIN PAGE</div>} />
        </Routes>
      </MemoryRouter>,
    );

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/username/i), 'admin');
    await user.type(screen.getByLabelText(/password/i), 'password123');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => {
      expect(screen.getByText('ADMIN PAGE')).toBeInTheDocument();
    });
  });
});
