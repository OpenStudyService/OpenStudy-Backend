package com.openstudy.common.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException exception) {
        String code = switch (exception.getStatusCode().value()) {
            case 429 -> "RATE_LIMITED";
            case 404 -> "NOT_FOUND";
            case 501 -> "PREVIEW_READ_ONLY";
            default -> "REQUEST_FAILED";
        };
        return ResponseEntity.status(exception.getStatusCode()).body(new ApiError(code, exception.getReason()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> authentication(AuthenticationException exception) {
        return ResponseEntity.status(401).body(new ApiError("INVALID_CREDENTIALS", "이메일 또는 비밀번호를 확인해 주세요."));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiError> validation(Exception exception) {
        return ResponseEntity.unprocessableContent().body(new ApiError("VALIDATION_ERROR", "입력 형식을 확인해 주세요."));
    }
}
