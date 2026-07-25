package com.jobseekercopilot.cvcoverletter.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            InvalidGenerationInputException.class
    })
    ResponseEntity<ApiError> badRequest(Exception exception, HttpServletRequest request) {
        String message = exception instanceof MethodArgumentNotValidException validation
                ? validation.getBindingResult().getFieldErrors().stream()
                    .findFirst().map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .orElse("Invalid request")
                : exception instanceof InvalidGenerationInputException
                    ? exception.getMessage()
                    : "Request body is not valid JSON";
        return response(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler({InvalidLlmResponseException.class, DownstreamServiceException.class})
    ResponseEntity<ApiError> badGateway(RuntimeException exception, HttpServletRequest request) {
        if (exception.getCause() != null) {
            log.error("Downstream CV/cover-letter generation error for {}", request.getRequestURI(), exception);
        }
        return response(HttpStatus.BAD_GATEWAY, exception.getMessage(), request);
    }

    @ExceptionHandler(PaymentRequiredException.class)
    ResponseEntity<ApiError> paymentRequired(PaymentRequiredException exception, HttpServletRequest request) {
        return response(HttpStatus.PAYMENT_REQUIRED, exception.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internalError(Exception exception, HttpServletRequest request) {
        log.error("Unexpected CV/cover-letter generation error for {}", request.getRequestURI(), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
                exception.getClass().getSimpleName() + ": " + exception.getMessage(), request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiError.builder()
                .timestamp(OffsetDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .build());
    }
}
