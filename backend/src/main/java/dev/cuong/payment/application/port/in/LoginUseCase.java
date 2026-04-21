package dev.cuong.payment.application.port.in;

import dev.cuong.payment.application.dto.AuthResult;
import dev.cuong.payment.application.dto.LoginCommand;

/**
 * Input port: user login.
 * Validates credentials and returns a JWT on success.
 */
public interface LoginUseCase {

    AuthResult login(LoginCommand command);
}
