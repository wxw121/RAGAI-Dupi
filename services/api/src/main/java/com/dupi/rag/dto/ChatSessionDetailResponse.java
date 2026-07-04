package com.dupi.rag.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ChatSessionDetailResponse {
    private ChatSessionResponse session;
    private List<ChatMessageResponse> messages;
}
