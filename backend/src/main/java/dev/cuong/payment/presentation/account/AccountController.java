package dev.cuong.payment.presentation.account;

import dev.cuong.payment.application.dto.AccountResult;
import dev.cuong.payment.application.port.in.GetAccountUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Exposes account balance and metadata for the authenticated user.
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final GetAccountUseCase getAccountUseCase;

    /**
     * Returns the authenticated user's account details and current balance.
     *
     * @return 200 with account; 401 if unauthenticated; 404 if account record missing
     */
    @GetMapping("/me")
    public ResponseEntity<AccountResponse> getMe(@AuthenticationPrincipal UUID userId) {
        AccountResult result = getAccountUseCase.getAccount(userId);
        return ResponseEntity.ok(new AccountResponse(
                result.id().toString(),
                result.userId().toString(),
                result.balance(),
                result.currency(),
                result.createdAt().toString()));
    }
}
