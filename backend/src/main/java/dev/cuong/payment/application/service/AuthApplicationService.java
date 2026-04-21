package dev.cuong.payment.application.service;

import dev.cuong.payment.application.dto.AuthResult;
import dev.cuong.payment.application.dto.LoginCommand;
import dev.cuong.payment.application.dto.RegisterUserCommand;
import dev.cuong.payment.application.port.in.LoginUseCase;
import dev.cuong.payment.application.port.in.RegisterUserUseCase;
import dev.cuong.payment.application.port.out.UserRepository;
import dev.cuong.payment.domain.exception.InvalidCredentialsException;
import dev.cuong.payment.domain.exception.UserAlreadyExistsException;
import dev.cuong.payment.domain.model.User;
import dev.cuong.payment.domain.vo.UserRole;
import dev.cuong.payment.infrastructure.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthApplicationService implements RegisterUserUseCase, LoginUseCase {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    @Transactional
    public AuthResult register(RegisterUserCommand command) {
        if (userRepository.existsByUsername(command.username())) {
            throw new UserAlreadyExistsException("username", command.username());
        }
        if (userRepository.existsByEmail(command.email())) {
            throw new UserAlreadyExistsException("email", command.email());
        }

        Instant now = Instant.now();
        User user = User.builder()
                .username(command.username())
                .email(command.email())
                .passwordHash(passwordEncoder.encode(command.password()))
                .role(UserRole.USER)
                .createdAt(now)
                .updatedAt(now)
                .build();

        User saved = userRepository.save(user);
        String token = jwtService.generateToken(saved.getId(), saved.getUsername(), saved.getRole());

        log.info("User registered: userId={}, username={}", saved.getId(), saved.getUsername());
        return new AuthResult(token, saved.getId(), saved.getUsername(), saved.getRole().name());
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResult login(LoginCommand command) {
        User user = userRepository.findByUsername(command.username())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            // Intentionally same exception as "user not found" — prevents username enumeration
            throw new InvalidCredentialsException();
        }

        String token = jwtService.generateToken(user.getId(), user.getUsername(), user.getRole());

        log.info("User logged in: userId={}, username={}", user.getId(), user.getUsername());
        return new AuthResult(token, user.getId(), user.getUsername(), user.getRole().name());
    }
}
