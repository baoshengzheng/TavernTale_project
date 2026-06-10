package com.taverntales.dto.request;

/**
 * PLAYER_TALK_END 消息的 payload。
 * 玩家主动结束与 NPC 的对话。
 */
public record PlayerTalkEndRequest(String npcId) {
}
