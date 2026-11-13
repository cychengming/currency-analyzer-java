package com.currencyanalyzer.web;

import com.currencyanalyzer.ai.chat.AiResponseInProgressException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
