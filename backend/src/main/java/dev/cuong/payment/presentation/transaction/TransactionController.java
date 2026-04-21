package dev.cuong.payment.presentation.transaction;

import dev.cuong.payment.application.dto.CreateTransactionCommand;
import dev.cuong.payment.application.dto.TransactionResult;
import dev.cuong.payment.application.port.in.CreateTransactionUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Handles transaction lifecycle operations.
 * All endpoints require a valid JWT. Write operations require {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final CreateTransactionUseCase createTransactionUseCase;

    /**
     * Creates a new transaction and updates the account balance atomically.
     *
     * <p>The {@code Idempotency-Key} header is required. Submitting the same key
     * twice returns the original 201 response without re-executing the operation.
     *
     * @return 201 with transaction details; 400 on missing header or validation error;
     *         401 if unauthenticated; 422 if balance is insufficient
     */
    @PostMapping
    public ResponseEntity<TransactionResponse> createTransaction(
            @AuthenticationPrincipal UUID userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransactionRequest request) {

        TransactionResult result = createTransactionUseCase.createTransaction(
                new CreateTransactionCommand(
                        userId,
                        request.amount(),
                        request.type(),
                        request.description(),
                        idempotencyKey));

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    private TransactionResponse toResponse(TransactionResult r) {
        return new TransactionResponse(
                r.id().toString(),
                r.userId().toString(),
                r.accountId().toString(),
                r.amount(),
                r.currency(),
                r.type(),
                r.status(),
                r.description(),
                r.gatewayReference(),
                r.failureReason(),
                r.processedAt() != null ? r.processedAt().toString() : null,
                r.refundedAt() != null ? r.refundedAt().toString() : null,
                r.createdAt().toString(),
                r.updatedAt().toString());
    }
}
