package com.openstudy.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.openstudy.common.web.ApiRequestFilter;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    private static final String CHECK_INPUT_MESSAGE = "입력 형식을 확인해 주세요.";
    private static final String FORBIDDEN_MESSAGE = "접근 권한이 없습니다.";
    private static final String NOT_FOUND_MESSAGE = "요청한 항목을 찾을 수 없습니다.";
    private static final String INTERNAL_ERROR_MESSAGE = "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status())
                .body(exception.error(ApiRequestFilter.requestId(request)));
    }

    /** M13 409 with its target information (OperationsConflictError). */
    @ExceptionHandler(OperationsConflict.class)
    ResponseEntity<Map<String, Object>> handleOperationsConflict(
            OperationsConflict exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(409)
                .body(exception.body(ApiRequestFilter.requestId(request)));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException exception, HttpServletRequest request) {
        int status = exception.getStatusCode().value();

        String code = switch (status) {
            case 401 -> "SESSION_EXPIRED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "INVALID_STATE";
            case 422 -> VALIDATION_FAILED;
            case 429 -> "RATE_LIMITED";
            case 501 -> "PREVIEW_READ_ONLY";
            default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_FAILED";
        };

        String message = switch (status) {
            case 401 -> "다시 로그인해 주세요.";
            case 403 -> FORBIDDEN_MESSAGE;
            case 404 -> NOT_FOUND_MESSAGE;
            case 409 -> "현재 상태에서는 요청을 처리할 수 없습니다.";
            case 429 -> "요청이 많습니다. 잠시 후 다시 시도해 주세요.";
            case 501 -> "로컬 샘플은 조회 전용입니다. 저장 기능은 아직 구현되지 않았습니다.";
            default -> status >= 500 ? INTERNAL_ERROR_MESSAGE : CHECK_INPUT_MESSAGE;
        };

        return createResponse(status, code, message, null, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> handleAuthentication(AuthenticationException exception, HttpServletRequest request) {
        return createResponse(401, "AUTHENTICATION_FAILED", "이메일 또는 비밀번호를 확인해 주세요.", null, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return createResponse(403, "FORBIDDEN", FORBIDDEN_MESSAGE, null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), "입력값을 확인해 주세요."));

        return createResponse(422, VALIDATION_FAILED, CHECK_INPUT_MESSAGE, fields, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        Map<String, String> fields = Map.of("$body", "허용된 필드와 JSON 형식을 확인해 주세요.");

        return createResponse(422, VALIDATION_FAILED, CHECK_INPUT_MESSAGE, fields, request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> handleInvalidParameter(Exception exception, HttpServletRequest request) {
        String name = exception instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : ((MissingServletRequestParameterException) exception).getParameterName();

        Map<String, String> fields = Map.of(name, "요청 값을 확인해 주세요.");

        return createResponse(422, VALIDATION_FAILED, CHECK_INPUT_MESSAGE, fields, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException exception, HttpServletRequest request) {
        return createResponse(404, "NOT_FOUND", NOT_FOUND_MESSAGE, null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) {
        return createResponse(405, "METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다.", null, request);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> handleMethodValidation(Exception exception, HttpServletRequest request) {
        if (exception instanceof HandlerMethodValidationException validation && validation.isForReturnValue()) {
            return handleUnexpected(exception, request);
        }

        Map<String, String> fields = Map.of("$request", "요청 값을 확인해 주세요.");

        return createResponse(422, VALIDATION_FAILED, CHECK_INPUT_MESSAGE, fields, request);
    }

    /** An upload past the servlet limit is the same refusal as the photo size check (#120). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleUploadTooLarge(Exception exception, HttpServletRequest request) {
        Map<String, String> fields = Map.of("file", "5MB 이하의 이미지를 선택해 주세요.");

        return createResponse(422, "AVATAR_TOO_LARGE", "사진은 5MB 이하여야 합니다.", fields, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request
    ) {
        return createResponse(415, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식입니다.", null, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        // SQL/credentials can occur in message and cause chains; never log either.
        log.error(
                "API failure requestId={} type={}",
                ApiRequestFilter.requestId(request),
                exception.getClass().getName()
        );

        return createResponse(500, "INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE, null, request);
    }

    private ResponseEntity<ApiError> createResponse(
            int status,
            String code,
            String message,
            Map<String, String> fields,
            HttpServletRequest request
    ) {
        ApiError error = new ApiError(code, message, ApiRequestFilter.requestId(request), fields, null, null);

        return ResponseEntity.status(status).body(error);
    }
}
