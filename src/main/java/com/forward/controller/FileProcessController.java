package com.forward.controller;


import com.forward.model.TriggerMsgToMqRequest;
import com.forward.model.TriggerMsgToMqResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/v1")
public class FileProcessController {

    @PostMapping("/triggerToMq")
    public ResponseEntity<TriggerMsgToMqResponse> triggerMsgToMq(@RequestBody TriggerMsgToMqRequest req){

        System.out.println("FileProcessController triggerMsgToMq called");
        // TODO : Implement triggering message to mq
        TriggerMsgToMqResponse resp = new TriggerMsgToMqResponse(1);
        return new ResponseEntity<>(resp, HttpStatus.CREATED);
    }

}
