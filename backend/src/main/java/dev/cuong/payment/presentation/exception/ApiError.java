package dev.cuong.payment.presentation.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, Instant timestamp) {

    public ApiError(String code, String message) {
        this(code, message, Instant.now());
    }
}
