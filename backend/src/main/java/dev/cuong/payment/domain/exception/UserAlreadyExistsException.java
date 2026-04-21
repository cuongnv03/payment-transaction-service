package dev.cuong.payment.domain.exception;

public class UserAlreadyExistsException extends DomainException {

    public UserAlreadyExistsException(String field, String value) {
        super(String.format("User already exists with %s: %s", field, value));
    }
}
