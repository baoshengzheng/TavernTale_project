package com.taverntales.dto.request;

/**
 * PLAYER_TALK 消息的 payload。
 * 玩家对指定 NPC 发送对话。
 */
public record PlayerTalkRequest(String npcId, String message) {
}
