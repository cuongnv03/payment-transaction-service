package dev.cuong.payment.application.port.in;

import dev.cuong.payment.application.dto.AccountResult;

import java.util.UUID;

/**
 * Input port: retrieve the authenticated user's account balance and details.
 */
public interface GetAccountUseCase {

    AccountResult getAccount(UUID userId);
}
