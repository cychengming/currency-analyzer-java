package com.currencyanalyzer.web;

import com.currencyanalyzer.ai.chat.AiResponseInProgressException;
import com.currencyanalyzer.ai.chat.AiUsageLimitException;
import com.currencyanalyzer.alerts.AlertDeliveryException;
import com.currencyanalyzer.storage.StorageException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AiResponseInProgressException.class)
    public ResponseEntity<Map<String, Object>> responseInProgress(
        AiResponseInProgressException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
            "success", false,
            "error", exception.getMessage()
        ));
    }

    @ExceptionHandler(AiUsageLimitException.class)
    public ResponseEntity<Map<String, Object>> usageLimit(
        AiUsageLimitException exception
    ) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
            "success", false,
            "error", exception.getMessage() == null ? "Daily AI limit reached" : exception.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(
        IllegalArgumentException exception,
        HttpServletRequest request
    ) {
        log.debug("Bad request for {}", request.getRequestURI(), exception);
        return ResponseEntity.badRequest().body(Map.of(
            "success", false,
            "error", exception.getMessage() == null ? "Invalid request" : exception.getMessage()
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> forbidden(
        AccessDeniedException exception,
        HttpServletRequest request
    ) {
        log.debug("Access denied for {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
            "success", false,
            "error", exception.getMessage() == null ? "Access denied" : exception.getMessage()
        ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> payloadTooLarge(
        MaxUploadSizeExceededException exception,
        HttpServletRequest request
    ) {
        log.debug("Upload exceeds size limit for {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of(
            "success", false,
            "error", "Uploaded file exceeds the maximum allowed size"
        ));
    }

    @ExceptionHandler(StorageException.class)
    public ResponseEntity<Map<String, Object>> storageError(
        StorageException exception,
        HttpServletRequest request
    ) {
        log.warn("Storage operation failed for {}: {}", request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
            "success", false,
            "error", exception.getMessage() == null ? "Storage operation failed" : exception.getMessage()
        ));
    }

    @ExceptionHandler(AlertDeliveryException.class)
    public ResponseEntity<Map<String, Object>> alertDeliveryError(
        AlertDeliveryException exception,
        HttpServletRequest request
    ) {
        log.warn("Alert notification delivery failed for {}: {}", request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
            "success", false,
            "error", exception.getMessage() == null ? "Alert delivery failed" : exception.getMessage()
        ));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(
        NoResourceFoundException exception,
        HttpServletRequest request
    ) {
        log.debug("No handler for {}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "success", false,
            "error", "Not found"
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internalError(
        Exception exception,
        HttpServletRequest request
    ) {
        log.error("Unhandled request failure for {}", request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
            "success", false,
            "error", "Internal server error"
        ));
    }
}
