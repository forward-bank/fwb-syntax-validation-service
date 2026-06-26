package com.forward.model;

public class DebulkingResponse {

    private final String status;    // "VALID" | "INVALID"
    private final String errorCode; // null when VALID, e.g. "SYNTAX_ERROR_001" when INVALID

    private DebulkingResponse(String status, String errorCode) {
        this.status    = status;
        this.errorCode = errorCode;
    }

    public static DebulkingResponse valid() {
        return new DebulkingResponse("VALID", null);
    }

    public static DebulkingResponse invalid(String errorCode) {
        return new DebulkingResponse("INVALID", errorCode);
    }

    public String getStatus()    { return status; }
    public String getErrorCode() { return errorCode; }

    @Override
    public String toString() {
        return "SyntaxValidationResponse{status='" + status + "', errorCode='" + errorCode + "'}";
    }
}