package com.taverntales.service.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * AI 对话响应 DTO。
 *
 * 由 Python 服务返回，Java 侧反序列化使用。
 * Python (FastAPI/Pydantic) 输出 snake_case，Java 用 camelCase，
 * 通过 @JsonProperty 做映射。
 *
 * fallback=true 表示 AI 不可用，使用了兜底回复。
 */
public record DialogueResponse(
        @JsonProperty("npc_response") String npcResponse,
        @JsonProperty("emotion_change") String emotionChange,
        @JsonProperty("new_memories") String newMemories,
        @JsonProperty("actions") List<String> actions,
        @JsonProperty("fallback") boolean fallback
) {
    /** 创建兜底回复 */
    public static DialogueResponse fallback(String npcResponse) {
        return new DialogueResponse(npcResponse, null, null, List.of(), true);
    }
}
