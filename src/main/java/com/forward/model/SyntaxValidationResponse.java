package com.forward.model;

public class SyntaxValidationResponse {

    private final String status;    // "VALID" | "INVALID"
    private final String errorCode; // null when VALID, e.g. "SVE_001" when INVALID

    private SyntaxValidationResponse(String status, String errorCode) {
        this.status    = status;
        this.errorCode = errorCode;
    }

    public static SyntaxValidationResponse valid() {
        return new SyntaxValidationResponse("VALID", null);
    }

    public static SyntaxValidationResponse invalid(String errorCode) {
        return new SyntaxValidationResponse("INVALID", errorCode);
    }

    public String getStatus()    { return status; }
    public String getErrorCode() { return errorCode; }

    @Override
    public String toString() {
        return "SyntaxValidationResponse{status='" + status + "', errorCode='" + errorCode + "'}";
    }
}