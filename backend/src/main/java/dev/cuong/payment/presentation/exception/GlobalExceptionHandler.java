package dev.cuong.payment.presentation.exception;

import dev.cuong.payment.domain.exception.AccountNotFoundException;
import dev.cuong.payment.domain.exception.InvalidCredentialsException;
import dev.cuong.payment.domain.exception.InvalidTransactionStateException;
import dev.cuong.payment.domain.exception.TransactionNotFoundException;
import dev.cuong.payment.domain.exception.UserAlreadyExistsException;
import dev.cuong.payment.domain.exception.UserNotFoundException;
import dev.cuong.payment.domain.exception.InsufficientFundsException;
import dev.cuong.payment.domain.exception.RateLimitExceededException;
import dev.cuong.payment.domain.exception.SameAccountTransferException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * Maps domain exceptions to HTTP status codes in one place.
 * Controllers throw; this class decides the status code and response shape.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleUserAlreadyExists(UserAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("USER_ALREADY_EXISTS", e.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("INVALID_CREDENTIALS", e.getMessage()));
    }

    @ExceptionHandler({TransactionNotFoundException.class, AccountNotFoundException.class, UserNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(SameAccountTransferException.class)
    public ResponseEntity<ApiError> handleSameAccountTransfer(SameAccountTransferException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("SAME_ACCOUNT_TRANSFER", e.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ApiError> handleInsufficientFunds(InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiError("INSUFFICIENT_FUNDS", e.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException e) {
        return ResponseEntity.status(429)
                .header("Retry-After", "60")
                .body(new ApiError("RATE_LIMIT_EXCEEDED", e.getMessage()));
    }

    @ExceptionHandler(InvalidTransactionStateException.class)
    public ResponseEntity<ApiError> handleInvalidState(InvalidTransactionStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("INVALID_TRANSACTION_STATE", e.getMessage()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("Optimistic lock conflict on {}: {}", e.getPersistentClassName(), e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("CONFLICT_CONCURRENT_UPDATE",
                        "The operation conflicted with a concurrent update. Please retry."));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("MISSING_HEADER", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "Invalid value '" + e.getValue() + "' for parameter '" + e.getName() + "'";
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_PARAMETER", message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_ERROR", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unhandled exception: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
