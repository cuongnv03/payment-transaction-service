package dev.cuong.payment.application.service;

import dev.cuong.payment.application.dto.UserProfileResult;
import dev.cuong.payment.application.port.in.GetUserProfileUseCase;
import dev.cuong.payment.application.port.out.UserRepository;
import dev.cuong.payment.domain.exception.UserNotFoundException;
import dev.cuong.payment.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserApplicationService implements GetUserProfileUseCase {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserProfileResult getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        log.info("Profile retrieved: userId={}", userId);
        return new UserProfileResult(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole().name(),
                user.getCreatedAt());
    }
}
