package com.catalogix.catalog.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.*;
import java.time.Instant;

// Mirrors user-svc's GlobalExceptionHandler.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String MESSAGE = "message";
    private static final String TIMESTAMP = "timestamp";
    private static final String STATUS = "status";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(fe.getField() + ": " + fe.getDefaultMessage());
        }
        Map<String, Object> body = new HashMap<>();
        body.put(MESSAGE, "Validation failed");
        body.put("errors", errors);
        body.put(TIMESTAMP, Instant.now().toString());
        body.put(STATUS, HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.badRequest().body(body);
    }

    // Added alongside GET /products' new query params (minPrice, maxPrice,
    // sortBy): without this, an unparseable value — a non-numeric
    // minPrice, or a sortBy that isn't one of ProductSortOption's real
    // values — would fall through to the generic handleAll() below and
    // come back as a confusing 500, even though it's really the client
    // sending a bad request. Spring resolves query params BEFORE the
    // controller method runs, so this is the only place to catch it.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Class<?> requiredType = ex.getRequiredType();

        String message;
        if (requiredType != null && requiredType.isEnum()) {
            message = "Invalid value for '" + ex.getName() + "': " + ex.getValue()
                    + ". Valid values: " + Arrays.toString(requiredType.getEnumConstants());
        } else {
            message = "Invalid value for '" + ex.getName() + "': " + ex.getValue();
        }
        Map<String, Object> body = new HashMap<>();
        body.put(MESSAGE, message);
        body.put(TIMESTAMP, Instant.now().toString());
        body.put(STATUS, HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadArg(IllegalArgumentException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put(MESSAGE, ex.getMessage());
        body.put(STATUS, HttpStatus.CONFLICT.value());
        body.put(TIMESTAMP, Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put(MESSAGE, ex.getMessage());
        body.put(TIMESTAMP, Instant.now().toString());
        body.put(STATUS, HttpStatus.FORBIDDEN.value());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ProductNotFoundException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put(MESSAGE, ex.getMessage());
        body.put(TIMESTAMP, Instant.now().toString());
        body.put(STATUS, HttpStatus.NOT_FOUND.value());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientStock(InsufficientStockException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put(MESSAGE, ex.getMessage());
        body.put(TIMESTAMP, Instant.now().toString());
        body.put(STATUS, HttpStatus.CONFLICT.value());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAll(Exception ex) {
        Map<String, Object> body = new HashMap<>();
        body.put(MESSAGE, ex.getMessage());
        body.put(TIMESTAMP, Instant.now().toString());
        body.put(STATUS, HttpStatus.INTERNAL_SERVER_ERROR.value());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
