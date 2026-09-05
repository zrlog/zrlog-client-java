package com.zrlog.client;

public class ApiException extends RuntimeException {

    private final int exitCode;
    private final Integer httpStatus;
    private final Integer apiError;

    public ApiException(String message, int exitCode, Integer httpStatus, Integer apiError) {
        super(message);
        this.exitCode = exitCode;
        this.httpStatus = httpStatus;
        this.apiError = apiError;
    }

    public ApiException(String message, int exitCode, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
        this.httpStatus = null;
        this.apiError = null;
    }

    public int exitCode() { return exitCode; }
    public Integer httpStatus() { return httpStatus; }
    public Integer apiError() { return apiError; }
}
