package com.taverntales.dto.request;

/**
 * PLAYER_MOVE 消息的 payload。
 * 玩家移动目标坐标。
 */
public record PlayerMoveRequest(int x, int y) {
}
