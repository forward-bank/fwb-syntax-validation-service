package com.forward.model;

public class SyntaxValidationRequest {

    private String paymentFilePath;

    public SyntaxValidationRequest() {
    }

    public SyntaxValidationRequest(String paymentFilePath) {
        this.paymentFilePath = paymentFilePath;
    }

    public String getPaymentFilePath() {
        return paymentFilePath;
    }

    public void setPaymentFilePath(String paymentFilePath) {
        this.paymentFilePath = paymentFilePath;
    }
}
