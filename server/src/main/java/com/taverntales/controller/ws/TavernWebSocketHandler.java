package com.taverntales.controller.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taverntales.domain.player.Player;
import com.taverntales.domain.player.PlayerService;
import com.taverntales.domain.world.Room;
import com.taverntales.domain.world.WorldService;
import com.taverntales.dto.WebSocketMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

/**
 * WebSocket 处理器 — 游戏通信的核心入口。
 *
 * 处理流程：
 * 1. 客户端连接 → 解析 playerId → 注册会话
 * 2. 客户端发送消息 → 按 type 路由到对应处理方法
 * 3. 客户端断开 → 清理会话 + 标记玩家离线
 *
 * 为什么在 Handler 中直接注入 Service？
 *  此 Handler 是游戏服务器的入口点，和 Controller 类似，注入 Service 处理业务逻辑是合理设计。
 *  后续如果消息种类增多（>10 种），拆分为独立的消息路由器。
 *
 * 注意：所有 send* 方法都是异步的，不阻塞事件循环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TavernWebSocketHandler implements WebSocketHandler {

    private final ObjectMapper objectMapper;
    private final WebSocketSessionManager sessionManager;
    private final PlayerService playerService;
    private final WorldService worldService;

    /**
     * 建立 WebSocket 连接后的主处理入口。
     * 解析查询参数中的 playerId，注册会话，处理消息流。
     *
     * query 参数: ?playerId={playerId}&playerName={name}
     */
    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String playerId = resolvePlayerId(session);
        if (playerId == null) {
            return session.close();
        }

        // 注册会话 + 玩家进入酒馆
        sessionManager.register(playerId, session);
        playerService.enterTavern(playerId, resolvePlayerName(session, playerId),
                getDefaultRoomId(), getDefaultSpawnX(), getDefaultSpawnY());
        sendWorldState(session, playerId);

        // 处理消息流：接收 → 路由 → 清理
        return session.receive()
                .doOnNext(msg -> handleMessage(session, playerId, msg.getPayloadAsText()))
                .doOnError(error -> log.error("玩家 [{}] WebSocket 异常", playerId, error))
                .doOnComplete(() -> handleSessionCleanup(playerId, true))
                .doOnCancel(() -> handleSessionCleanup(playerId, false))
                .then()
                .onErrorResume(error -> {
                    handleSessionCleanup(playerId, false);
                    return Mono.empty();
                });
    }

    /**
     * 从查询参数提取并校验 playerId，不存在则返回 null。
     */
    private String resolvePlayerId(WebSocketSession session) {
        String playerId = extractQueryParam(session, "playerId");
        if (playerId == null || playerId.isBlank()) {
            log.warn("连接缺少 playerId 参数，拒绝连接");
            return null;
        }
        return playerId;
    }

    /**
     * 从查询参数提取 playerName，缺失则生成默认名称。
     */
    private String resolvePlayerName(WebSocketSession session, String playerId) {
        String name = extractQueryParam(session, "playerName");
        return (name != null && !name.isBlank())
                ? name
                : "旅人_" + playerId.substring(0, Math.min(4, playerId.length()));
    }

    /**
     * 获取默认房间 ID，兜底用。
     */
    private String getDefaultRoomId() {
        return worldService.getAllRooms().stream().findFirst()
                .map(Room::getId).orElse("tavern");
    }

    /**
     * 获取默认出生点 X。
     */
    private int getDefaultSpawnX() {
        return worldService.getAllRooms().stream().findFirst()
                .map(Room::getDefaultSpawnX).orElse(50);
    }

    /**
     * 获取默认出生点 Y。
     */
    private int getDefaultSpawnY() {
        return worldService.getAllRooms().stream().findFirst()
                .map(Room::getDefaultSpawnY).orElse(300);
    }

    /**
     * 玩家断连后的清理工作。
     *
     * @param broadcast true=正常关闭需广播离开；false=异常中断不广播（连接可能已不可靠）
     */
    private void handleSessionCleanup(String playerId, boolean broadcast) {
        sessionManager.unregister(playerId);
        playerService.leaveTavern(playerId);
        if (broadcast) {
            broadcastPlayerLeft(playerId);
        }
    }

    /**
     * 处理接收到的消息，按 type 路由。
     */
    private void handleMessage(WebSocketSession session, String playerId, String messageText) {
        try {
            WebSocketMessage message = objectMapper.readValue(messageText, WebSocketMessage.class);
            log.debug("收到玩家 [{}] 消息: type={}, requestId={}", playerId, message.getType(), message.getRequestId());

            switch (message.getType()) {
                case "PLAYER_MOVE" -> handlePlayerMove(session, playerId, message);
                case "PLAYER_ENTER" -> handlePlayerEnter(session, playerId, message);
                case "PING" -> handlePing(session, message);
                default -> log.warn("未知消息类型: {} (from player {})", message.getType(), playerId);
            }
        } catch (Exception e) {
            log.error("解析玩家 [{}] 消息失败: {}", playerId, e.getMessage());
            sendMessage(session, WebSocketMessage.builder()
                    .type("SYSTEM")
                    .requestId(UUID.randomUUID().toString())
                    .timestamp(Instant.now().toEpochMilli())
                    .payload(Map.of("level", "error", "message", "消息格式错误"))
                    .build());
        }
    }

    /**
     * 处理玩家移动。
     * 更新玩家位置，并广播给所有在线玩家。
     */
    private void handlePlayerMove(WebSocketSession session, String playerId, WebSocketMessage message) {
        Map<String, Object> payload = message.getPayload();
        int targetX = payload.get("x") instanceof Number n ? n.intValue() : 0;
        int targetY = payload.get("y") instanceof Number n2 ? n2.intValue() : 0;

        // 获取房间边界（默认 800x600）
        int roomW = 800, roomH = 600;
        var roomOpt = playerService.getPlayer(playerId);
        if (roomOpt.isPresent()) {
            var room = worldService.getRoom(roomOpt.get().getCurrentRoomId());
            if (room.isPresent()) {
                roomW = room.get().getWidth();
                roomH = room.get().getHeight();
            }
        }

        playerService.updatePosition(playerId, targetX, targetY, roomW, roomH).ifPresent(player -> {
            // 广播位置给所有在线玩家
            broadcast(WebSocketMessage.builder()
                    .type("PLAYER_MOVED")
                    .requestId(message.getRequestId())
                    .timestamp(Instant.now().toEpochMilli())
                    .payload(Map.of(
                            "playerId", playerId,
                            "x", player.getX(),
                            "y", player.getY()
                    ))
                    .build());
        });
    }

    /**
     * 处理玩家进入（重新发送世界状态）。
     */
    private void handlePlayerEnter(WebSocketSession session, String playerId, WebSocketMessage message) {
        sendWorldState(session, playerId);
    }

    /**
     * 处理 Ping（心跳）。
     */
    private void handlePing(WebSocketSession session, WebSocketMessage message) {
        sendMessage(session, WebSocketMessage.builder()
                .type("PONG")
                .requestId(message.getRequestId())
                .timestamp(Instant.now().toEpochMilli())
                .payload(Map.of())
                .build());
    }

    /**
     * 发送完整世界状态给指定玩家（进入时调用）。
     */
    private void sendWorldState(WebSocketSession session, String playerId) {
        List<Room> rooms = worldService.getAllRooms();
        List<Player> onlinePlayers = playerService.getOnlinePlayers();
        var currentPlayerOpt = playerService.getPlayer(playerId);

        Map<String, Object> worldPayload = new HashMap<>();
        worldPayload.put("rooms", rooms.stream().map(this::roomToMap).toList());
        worldPayload.put("players", onlinePlayers.stream().map(this::playerToMap).toList());
        worldPayload.put("yourPlayerId", playerId);
        currentPlayerOpt.ifPresent(p -> {
            worldPayload.put("yourPosition", Map.of("x", p.getX(), "y", p.getY(), "roomId", p.getCurrentRoomId()));
        });

        sendMessage(session, WebSocketMessage.builder()
                .type("WORLD_STATE")
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toEpochMilli())
                .payload(worldPayload)
                .build());
    }

    /**
     * 广播玩家离开通知。
     */
    private void broadcastPlayerLeft(String playerId) {
        broadcast(WebSocketMessage.builder()
                .type("PLAYER_LEFT")
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toEpochMilli())
                .payload(Map.of("playerId", playerId))
                .build());
    }

    /**
     * 发送消息到指定会话。
     */
    private void sendMessage(WebSocketSession session, WebSocketMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.send(Mono.just(session.textMessage(json)))
                    .subscribe(null, e -> log.error("发送消息失败", e));
        } catch (Exception e) {
            log.error("序列化消息失败", e);
        }
    }

    /**
     * 广播消息给所有在线玩家。
     */
    private void broadcast(WebSocketMessage message) {
        ConcurrentMap<String, WebSocketSession> allSessions = sessionManager.getAllSessions();
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("广播消息序列化失败", e);
            return;
        }
        String finalJson = json;
        allSessions.forEach((pid, session) -> {
            if (session.isOpen()) {
                session.send(Mono.just(session.textMessage(finalJson)))
                        .subscribe(null, e -> log.error("广播消息给 [{}] 失败", pid, e));
            }
        });
    }

    /**
     * Room 转 Map（避免序列化整个实体暴露内部字段）。
     * objectsJson 是 JSON 字符串，保持字符串格式传给前端自行解析。
     */
    private Map<String, Object> roomToMap(Room room) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", room.getId());
        map.put("name", room.getName());
        map.put("description", room.getDescription());
        map.put("width", room.getWidth());
        map.put("height", room.getHeight());
        map.put("objectsJson", room.getObjectsJson());
        return map;
    }

    /**
     * Player 转 Map。
     */
    private Map<String, Object> playerToMap(Player player) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", player.getId());
        map.put("name", player.getName());
        map.put("x", player.getX());
        map.put("y", player.getY());
        map.put("roomId", player.getCurrentRoomId());
        return map;
    }

    /**
     * 从 WebSocket 查询参数中提取值。
     * URL 格式: ws://host/ws/tavern?playerId=xxx&playerName=yyy
     */
    private String extractQueryParam(WebSocketSession session, String key) {
        String query = session.getHandshakeInfo().getUri().getQuery();
        if (query == null) return null;
        for (String param : query.split("&")) {
            String[] parts = param.split("=", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return parts[1];
            }
        }
        return null;
    }
}
