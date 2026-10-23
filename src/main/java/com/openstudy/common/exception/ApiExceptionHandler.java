package com.openstudy.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import com.openstudy.common.web.ApiRequestFilter;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> explicit(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(exception.error(ApiRequestFilter.requestId(request)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException exception, HttpServletRequest request) {
        int status = exception.getStatusCode().value();
        String code = switch (status) {
            case 401 -> "SESSION_EXPIRED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "INVALID_STATE";
            case 422 -> "VALIDATION_FAILED";
            case 429 -> "RATE_LIMITED";
            case 501 -> "PREVIEW_READ_ONLY";
            default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_FAILED";
        };
        String message = switch (status) {
            case 401 -> "다시 로그인해 주세요.";
            case 403 -> "접근 권한이 없습니다.";
            case 404 -> "요청한 항목을 찾을 수 없습니다.";
            case 409 -> "현재 상태에서는 요청을 처리할 수 없습니다.";
            case 429 -> "요청이 많습니다. 잠시 후 다시 시도해 주세요.";
            case 501 -> "로컬 샘플은 조회 전용입니다. 저장 기능은 아직 구현되지 않았습니다.";
            default -> status >= 500 ? "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요." : "입력 형식을 확인해 주세요.";
        };
        return response(status, code, message, null, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> authentication(AuthenticationException exception, HttpServletRequest request) {
        return response(401, "AUTHENTICATION_FAILED", "이메일 또는 비밀번호를 확인해 주세요.", null, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(AccessDeniedException exception, HttpServletRequest request) {
        return response(403, "FORBIDDEN", "접근 권한이 없습니다.", null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> fields.putIfAbsent(error.getField(), "입력값을 확인해 주세요."));
        return response(422, "VALIDATION_FAILED", "입력 형식을 확인해 주세요.", fields, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformed(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return response(422, "VALIDATION_FAILED", "입력 형식을 확인해 주세요.",
                Map.of("$body", "허용된 필드와 JSON 형식을 확인해 주세요."), request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> parameter(Exception exception, HttpServletRequest request) {
        String name = exception instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
                : ((MissingServletRequestParameterException) exception).getParameterName();
        return response(422, "VALIDATION_FAILED", "입력 형식을 확인해 주세요.", Map.of(name, "요청 값을 확인해 주세요."), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missing(NoResourceFoundException exception, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "요청한 항목을 찾을 수 없습니다.", null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return response(405, "METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다.", null, request);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> methodValidation(Exception exception, HttpServletRequest request) {
        if (exception instanceof HandlerMethodValidationException validation && validation.isForReturnValue()) {
            return unexpected(exception, request);
        }
        return response(422, "VALIDATION_FAILED", "입력 형식을 확인해 주세요.",
                Map.of("$request", "요청 값을 확인해 주세요."), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> media(HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return response(415, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식입니다.", null, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        // SQL/credentials can occur in message and cause chains; never log either.
        log.error("API failure requestId={} type={}", ApiRequestFilter.requestId(request), exception.getClass().getName());
        return response(500, "INTERNAL_ERROR", "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.", null, request);
    }

    private ResponseEntity<ApiError> response(int status, String code, String message,
            Map<String, String> fields, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ApiError(code, message, ApiRequestFilter.requestId(request), fields, null, null));
    }
}
