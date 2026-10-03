package com.platform.portal.common;

import org.springframework.http.HttpStatus;

/** Business exception that maps directly onto an HTTP status and a client-facing message. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    public static ApiException upstream(String message, Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY, message, cause);
    }

    public static ApiException notConfigured(String integration) {
        return new ApiException(HttpStatus.FAILED_DEPENDENCY,
                integration + " integration is not configured. Configure it under Settings.");
    }
}
