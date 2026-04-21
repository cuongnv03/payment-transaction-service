package dev.cuong.payment.application.port.in;

import dev.cuong.payment.application.dto.UserProfileResult;

import java.util.UUID;

/**
 * Input port: retrieve the authenticated user's profile.
 */
public interface GetUserProfileUseCase {

    UserProfileResult getProfile(UUID userId);
}
