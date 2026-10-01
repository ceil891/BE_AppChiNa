package com.chineselearning.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class FieldValidationException extends ResponseStatusException {
    private final String field;
    public FieldValidationException(String field,String message) { super(HttpStatus.BAD_REQUEST,message);this.field=field; }
    public String field() { return field; }
}
