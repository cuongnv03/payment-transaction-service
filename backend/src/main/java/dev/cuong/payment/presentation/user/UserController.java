package dev.cuong.payment.presentation.user;

import dev.cuong.payment.application.dto.UserProfileResult;
import dev.cuong.payment.application.port.in.GetUserProfileUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Exposes read-only user profile data for the authenticated user.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final GetUserProfileUseCase getUserProfileUseCase;

    /**
     * Returns the authenticated user's profile.
     *
     * @return 200 with profile; 401 if unauthenticated; 404 if user record missing
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> getMe(@AuthenticationPrincipal UUID userId) {
        UserProfileResult result = getUserProfileUseCase.getProfile(userId);
        return ResponseEntity.ok(new UserProfileResponse(
                result.id().toString(),
                result.username(),
                result.email(),
                result.role(),
                result.createdAt().toString()));
    }
}
