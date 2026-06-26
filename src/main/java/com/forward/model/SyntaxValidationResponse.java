package com.forward.model;

/**
 * Response model written to SYNTAX.VALIDATION.RESPONSE.QUEUE.
 *
 * JSON shape:
 * <pre>
 * {
 *   "status":       "VALID" | "INVALID",
 *   "errorCode":    null | "SVE_001" | "SVE_002" | "SVE_003" | "SVE_INTERNAL_ERROR",
 *   "errorMessage": null | "<human-readable detail>"
 * }
 * </pre>
 *
 * Error codes:
 *   SVE_001 — empty or null XML content (e.g. S3 returned an empty file)
 *   SVE_002 — XSD schema validation failed (includes line-level detail in errorMessage)
 *   SVE_003 — IO error reading the XML bytes
 *   SVE_004 — S3 download failed (object not found, access denied, etc.)
 *   SVE_INTERNAL_ERROR — unexpected runtime exception
 */
public class SyntaxValidationResponse {

    private final String status;        // "VALID" | "INVALID"
    private final String errorCode;     // null when VALID
    private final String errorMessage;  // null when VALID, human-readable detail when INVALID

    private SyntaxValidationResponse(String status, String errorCode, String errorMessage) {
        this.status       = status;
        this.errorCode    = errorCode;
        this.errorMessage = errorMessage;
    }

    public static SyntaxValidationResponse valid() {
        return new SyntaxValidationResponse("VALID", null, null);
    }

    public static SyntaxValidationResponse invalid(String errorCode, String errorMessage) {
        return new SyntaxValidationResponse("INVALID", errorCode, errorMessage);
    }

    /** Convenience overload — use when no detailed message is available. */
    public static SyntaxValidationResponse invalid(String errorCode) {
        return invalid(errorCode, null);
    }

    public String getStatus()       { return status; }
    public String getErrorCode()    { return errorCode; }
    public String getErrorMessage() { return errorMessage; }

    @Override
    public String toString() {
        return "SyntaxValidationResponse{status='" + status
                + "', errorCode='" + errorCode
                + "', errorMessage='" + errorMessage + "'}";
    }
}
