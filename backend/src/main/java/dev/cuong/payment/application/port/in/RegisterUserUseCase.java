package dev.cuong.payment.application.port.in;

import dev.cuong.payment.application.dto.AuthResult;
import dev.cuong.payment.application.dto.RegisterUserCommand;

/**
 * Input port: user registration.
 * Validates uniqueness, hashes the password, persists the user, and returns a JWT.
 */
public interface RegisterUserUseCase {

    AuthResult register(RegisterUserCommand command);
}
