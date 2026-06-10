package com.taverntales.dto.request;

/**
 * PLAYER_ENTER 消息的 payload。
 * 连接建立后的首条消息，完成玩家身份注册。
 */
public record PlayerEnterRequest(String playerId, String playerName) {
}
