package com.forward.debulk;

import com.forward.model.DebulkingResponse;

import java.util.Map;

public class FileDebulkingProcessor {

    public DebulkingResponse process(Map<String,Object> requestMap) {
        System.out.println("  Debulking : " + requestMap);

        if(requestMap.get("custId") != null && requestMap.get("fileId") != null
                && requestMap.get("fileS3Path") != null) {
            return DebulkingResponse.valid();
        } else {
            return DebulkingResponse.invalid("SVE_MISSING_FIELDS");
        }
    }
}