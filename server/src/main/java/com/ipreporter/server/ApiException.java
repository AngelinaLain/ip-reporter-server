package com.ipreporter.server;

import org.springframework.http.HttpStatus;

public final class ApiException extends RuntimeException {
    private final HttpStatus status;
    public ApiException(HttpStatus status, String message) { super(message); this.status=status; }
    public HttpStatus status() { return status; }
    public static ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST,message); }
    public static ApiException missing(String message) { return new ApiException(HttpStatus.NOT_FOUND,message); }
}
