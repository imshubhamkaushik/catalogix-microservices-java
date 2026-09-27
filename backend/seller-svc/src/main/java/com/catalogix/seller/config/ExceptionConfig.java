package com.catalogix.seller.config;

import com.catalogix.seller.exception.ForbiddenException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class ExceptionConfig {
  @ExceptionHandler(ForbiddenException.class)
  public ResponseEntity<?> forbidden(ForbiddenException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler({ IllegalArgumentException.class, IllegalStateException.class })
  public ResponseEntity<?> bad(RuntimeException e) {
    return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
  }
}
