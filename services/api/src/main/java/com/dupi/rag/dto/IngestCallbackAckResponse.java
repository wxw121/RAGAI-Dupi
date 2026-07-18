package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IngestCallbackAckResponse {
    private String status;
    private boolean ignored;
    private String reason;

    public static IngestCallbackAckResponse ok() {
        return IngestCallbackAckResponse.builder().status("ok").ignored(false).build();
    }

    public static IngestCallbackAckResponse ignored(String reason) {
        return IngestCallbackAckResponse.builder().status("ignored").ignored(true).reason(reason).build();
    }
}
