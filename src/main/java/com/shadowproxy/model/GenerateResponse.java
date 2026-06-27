package com.shadowproxy.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GenerateResponse {

    @JsonProperty("request_id")
    private final String requestId;

    private final String response;

    public GenerateResponse(String requestId, String response) {
        this.requestId = requestId;
        this.response = response;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getResponse() {
        return response;
    }
}
