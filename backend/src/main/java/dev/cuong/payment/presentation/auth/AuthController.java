package dev.cuong.payment.presentation.auth;

import dev.cuong.payment.application.dto.AuthResult;
import dev.cuong.payment.application.dto.LoginCommand;
import dev.cuong.payment.application.dto.RegisterUserCommand;
import dev.cuong.payment.application.port.in.LoginUseCase;
import dev.cuong.payment.application.port.in.RegisterUserUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles user registration and login.
 * All endpoints are public (no authentication required).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RegisterUserUseCase registerUserUseCase;
    private final LoginUseCase loginUseCase;

    /**
     * Registers a new user and returns a JWT.
     *
     * @return 201 with token on success; 409 if username/email taken; 400 on validation error
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResult result = registerUserUseCase.register(
                new RegisterUserCommand(request.username(), request.email(), request.password()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    /**
     * Authenticates an existing user and returns a JWT.
     *
     * @return 200 with token on success; 401 on bad credentials
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = loginUseCase.login(
                new LoginCommand(request.username(), request.password()));
        return ResponseEntity.ok(toResponse(result));
    }

    private AuthResponse toResponse(AuthResult result) {
        return new AuthResponse(
                result.token(),
                result.userId().toString(),
                result.username(),
                result.role());
    }
}
