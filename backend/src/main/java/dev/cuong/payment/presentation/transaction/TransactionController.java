package dev.cuong.payment.presentation.transaction;

import dev.cuong.payment.application.dto.CreateTransactionCommand;
import dev.cuong.payment.application.dto.PagedResult;
import dev.cuong.payment.application.dto.TransactionResult;
import dev.cuong.payment.application.port.in.CreateTransactionUseCase;
import dev.cuong.payment.application.port.in.GetTransactionUseCase;
import dev.cuong.payment.domain.vo.TransactionStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Handles transaction lifecycle operations.
 * All endpoints require a valid JWT. Write operations require an {@code Idempotency-Key} header.
 */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final CreateTransactionUseCase createTransactionUseCase;
    private final GetTransactionUseCase getTransactionUseCase;

    /**
     * Creates a P2P transaction: debits the sender's account and creates a PENDING record.
     * The Kafka consumer credits the receiver's account on SUCCESS (async, Task 12).
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
                        request.toAccountId(),
                        request.amount(),
                        request.description(),
                        idempotencyKey));

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    /**
     * Returns the authenticated user's own transactions, newest first.
     * Optionally filtered by {@code status}; paginated via {@code page} and {@code size}.
     *
     * @return 200 with paginated list; 400 if {@code status} is not a valid enum constant;
     *         401 if unauthenticated
     */
    @GetMapping
    public ResponseEntity<PagedResult<TransactionResponse>> getMyTransactions(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        PagedResult<TransactionResult> result =
                getTransactionUseCase.getMyTransactions(userId, status, page, size);

        PagedResult<TransactionResponse> response = new PagedResult<>(
                result.data().stream().map(this::toResponse).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages());

        return ResponseEntity.ok(response);
    }

    /**
     * Returns a single transaction by ID, scoped to the authenticated user.
     * Returns 404 — not 403 — when the transaction belongs to a different user,
     * so we never reveal whether a transaction with a given ID exists for another account.
     *
     * @return 200 with transaction; 404 if not found or owned by another user;
     *         401 if unauthenticated
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getMyTransaction(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID transactionId) {

        return ResponseEntity.ok(
                toResponse(getTransactionUseCase.getMyTransaction(userId, transactionId)));
    }

    private TransactionResponse toResponse(TransactionResult r) {
        return new TransactionResponse(
                r.id().toString(),
                r.fromAccountId().toString(),
                r.toAccountId().toString(),
                r.amount(),
                r.currency(),
                r.status(),
                r.description(),
                r.gatewayReference(),
                r.failureReason(),
                r.retryCount(),
                r.processedAt() != null ? r.processedAt().toString() : null,
                r.refundedAt()  != null ? r.refundedAt().toString()  : null,
                r.createdAt().toString(),
                r.updatedAt().toString());
    }
}
