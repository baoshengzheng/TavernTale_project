package com.taverntales.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 消息的统一外壳。
 *
 * 所有客户端↔服务器的通信都使用此格式，version 和 requestId 保证协议可演进和请求可追踪。
 *
 * 为什么用 Map 做 payload 而不是强类型？
 *  Iteration 0-1 阶段消息种类少且变化快，Map 避免频繁新增 DTO 类。
 *  后续消息稳定后，可替换为 Jackson 多态类型（@JsonTypeInfo）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSocketMessage {

    /**
     * 协议版本号。当前 "1.0"。
     * 设计理由：版本号让服务端可以同时兼容多个客户端版本，
     * 避免强制客户端更新。版本变更时在 WebSocketSessionManager 中做路由。
     */
    @Builder.Default
    private String version = "1.0";

    /**
     * 请求唯一标识（UUID）。客户端生成，服务器回复时带回。
     * 用途：客户端将回复匹配到待处理的请求；调试时追踪整条链路的耗时。
     */
    private String requestId;

    /**
     * 消息类型。参见 api-protocol.md 定义。
     * 客户端→服务器：PLAYER_ENTER, PLAYER_MOVE, PLAYER_TALK, PLAYER_GIFT
     * 服务器→客户端：WORLD_STATE, DIALOGUE, DIALOGUE_PENDING, SYSTEM
     */
    private String type;

    /**
     * 消息时间戳（毫秒）。
     */
    private long timestamp;

    /**
     * 消息体。不同类型对应不同结构，在处理器中按 type 转换。
     */
    @Builder.Default
    private Map<String, Object> payload = new HashMap<>();

    /**
     * 反序列化时忽略未知字段，避免新旧版本兼容问题。
     * @JsonAnySetter 捕获 payload 中未显式声明的字段
     */
    @JsonAnySetter
    public void setPayloadField(String key, Object value) {
        this.payload.put(key, value);
    }
}
