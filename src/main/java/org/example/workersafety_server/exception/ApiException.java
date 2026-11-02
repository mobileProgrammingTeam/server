package org.example.workersafety_server.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public ApiException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    public static ApiException unauthorized() { return new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_FAILED", "인증에 실패했습니다."); }
    public static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    public static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }
}
