package com.catalogix.seller.exception;

public class ForbiddenException extends RuntimeException {
  public ForbiddenException(String m) {
    super(m);
  }
}
