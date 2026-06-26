package com.forward.model;

public class TriggerMsgToMqResponse {

    private Integer numberOfTxnDispatched;

    public TriggerMsgToMqResponse(Integer numberOfTxnDispatched) {
        this.numberOfTxnDispatched = numberOfTxnDispatched;
    }

    public Integer getNumberOfTxnDispatched() {
        return numberOfTxnDispatched;
    }

    public void setNumberOfTxnDispatched(Integer numberOfTxnDispatched) {
        this.numberOfTxnDispatched = numberOfTxnDispatched;
    }
}
