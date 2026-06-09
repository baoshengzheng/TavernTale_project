package com.taverntales.controller.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taverntales.domain.player.Player;
import com.taverntales.domain.player.PlayerService;
import com.taverntales.domain.world.Room;
import com.taverntales.domain.world.WorldService;
import com.taverntales.dto.WebSocketMessage;
import com.taverntales.dto.request.PlayerEnterRequest;
import com.taverntales.dto.request.PlayerMoveRequest;
import com.taverntales.dto.request.PlayerTalkEndRequest;
import com.taverntales.dto.request.PlayerTalkRequest;
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
 * 1. 客户端连接 → 等待首条消息（必须是 PLAYER_ENTER）
 * 2. PLAYER_ENTER → 从 payload 提取 playerId/playerName → 注册会话 + 推送世界状态
 * 3. 后续消息 → 按 type 路由到对应处理方法
 * 4. 客户端断开 → 清理会话 + 标记玩家离线
 *
 * 为什么不在 URL 查询参数中传 playerId？
 *  查询参数方案对中文需要 URL 编码，扩展性差，解析脆弱。
 *  改为连接后首条 PLAYER_ENTER 消息携带认证信息，payload 直接用 JSON + 强类型 DTO。
 *
 * 注意：所有 send* 方法都是异步的，不阻塞 Netty 事件循环。
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
     *
     * <h3>为什么不用查询参数传 playerId？</h3>
     * 查询参数方案对中文需要 URL 编码，扩展性差，手动 split 解析脆弱。
     * 改为连接后首条 PLAYER_ENTER 消息携带认证信息，payload 走 JSON + 强类型 DTO。
     *
     * <h3>为什么用单元素数组而非普通变量？</h3>
     * playerId 在连接时未知，需要在首条消息处理后才确定。
     * Java 要求 lambda 内捕获的变量是 effectively final，普通 String 变量无法在 lambda 内赋值。
     * {@code String[]} 引用本身是 final，内容可以修改——这是 Java 中"在 lambda 内传可变值"的最小开销写法。
     * <b>不需要线程安全</b>：Netty 保证同一会话的所有事件在同一个事件循环线程上顺序处理。
     *
     * <h3>Reactor 生命周期</h3>
     * 这个方法返回的是 Mono&lt;Void&gt;——一个"声明式管道"，不会立即执行。
     * Spring WebFlux 订阅这个 Mono 后开始接收 WebSocket 帧，每个帧触发一个回调：
     * <ul>
     *   <li>{@code doOnNext} — 每个消息触发一次，按 playerIdRef 是否已设置分流处理</li>
     *   <li>{@code doOnError} — 传输层异常（网络断开、协议错误），与 doOnNext 中的业务异常独立</li>
     *   <li>{@code doOnComplete} — 客户端正常关闭连接（浏览器关标签页）</li>
     *   <li>{@code doOnCancel} — 客户端异常中断（断网、强制杀进程），与 complete 互斥</li>
     *   <li>{@code onErrorResume} — 将错误转为空 Mono，防止异常传播到 Netty 导致连接泄漏</li>
     * </ul>
     *
     * @param session WebFlux WebSocket 会话，由 Netty 管理
     * @return Mono 管道，由 Spring 框架订阅并驱动
     */
    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // String[1] 承载 playerId：连接时未知，首条消息后确定
        // 不需要线程安全：Netty 保证同一会话事件在单线程上顺序处理
        String[] playerIdRef = new String[1];

        return session.receive()
                .doOnNext(msg -> {
                    String raw = msg.getPayloadAsText();
                    // playerIdRef[0] == null → 等待首条消息；非 null → 已认证
                    if (playerIdRef[0] == null) {
                        handleFirstMessage(session, raw, playerIdRef);
                    } else {
                        handleMessage(session, playerIdRef[0], raw);
                    }
                })
                // 传输层异常：可能发生在认证前（"未认证"）或认证后（playerId 已知）
                .doOnError(error -> log.warn("WebSocket 异常 (player={}): {}",
                        Objects.requireNonNullElse(playerIdRef[0], "未认证"), error.getMessage()))
                // complete 和 cancel 互斥：正常关闭走 complete，异常中断走 cancel
                .doOnComplete(() -> cleanupIfRegistered(playerIdRef[0], true))
                .doOnCancel(() -> cleanupIfRegistered(playerIdRef[0], false))
                // 兜底：确保任何未捕获异常都会触发清理，不会泄漏会话
                .then()
                .onErrorResume(error -> {
                    cleanupIfRegistered(playerIdRef[0], false);
                    return Mono.empty();
                });
    }

    /**
     * 仅在玩家已认证（playerId 非 null）时执行会话清理。
     * 避免未认证连接触发 NPE 或重复清理。
     */
    private void cleanupIfRegistered(String playerId, boolean broadcast) {
        if (playerId != null) {
            handleSessionCleanup(playerId, broadcast);
        }
    }

    /**
     * 处理连接后的首条消息——完成玩家身份注册。
     *
     * <h3>为什么首条消息必须是 PLAYER_ENTER？</h3>
     * 连接本身只是 TCP + WebSocket 握手，不携带任何业务身份。
     * 客户端必须在应用层"自我介绍"（playerId + playerName），
     * 服务端在此之前不知道是谁连进来了。
     *
     * <h3>为什么非 PLAYER_ENTER 要关闭连接？</h3>
     * 没有身份就没有游戏上下文，后续所有操作（移动、对话）都无意义。
     * 直接关闭连接比维持一个半初始化状态更简单，客户端重新连接即可。
     *
     * @param session WebSocket 会话
     * @param raw 首条消息的原始 JSON 文本
     * @param playerIdRef 单元素数组，用于将 playerId 回传给外层 handle 方法
     */
    private void handleFirstMessage(WebSocketSession session, String raw,
                                     String[] playerIdRef) {
        try {
            WebSocketMessage message = objectMapper.readValue(raw, WebSocketMessage.class);
            if (!"PLAYER_ENTER".equals(message.getType())) {
                log.warn("首条消息不是 PLAYER_ENTER，收到: {}", message.getType());
                sendSystem(session, "error", "请先发送 PLAYER_ENTER 完成身份注册");
                session.close().subscribe();
                return;
            }
            PlayerEnterRequest req = convertPayload(message, PlayerEnterRequest.class);
            String playerId = req.playerId();
            String playerName = (req.playerName() != null && !req.playerName().isBlank())
                    ? req.playerName()
                    : "旅人_" + playerId.substring(0, Math.min(4, playerId.length()));

            playerIdRef[0] = playerId;
            sessionManager.register(playerId, session);

            Room defaultRoom = getDefaultRoom();
            playerService.enterTavern(playerId, playerName, defaultRoom.getId(),
                    defaultRoom.getDefaultSpawnX(), defaultRoom.getDefaultSpawnY());
            sendWorldState(session, playerId);

            log.info("玩家 [{}] {} 通过 PLAYER_ENTER 注册", playerId, playerName);
        } catch (Exception e) {
            log.error("解析首条消息失败", e);
            sendSystem(session, "error", "消息格式错误，需要 PLAYER_ENTER");
            session.close().subscribe();
        }
    }

    /**
     * 处理已认证玩家的消息，按 type 路由。
     */
    private void handleMessage(WebSocketSession session, String playerId, String messageText) {
        try {
            WebSocketMessage message = objectMapper.readValue(messageText, WebSocketMessage.class);
            log.debug("收到玩家 [{}] 消息: type={}, requestId={}", playerId, message.getType(), message.getRequestId());

            switch (message.getType()) {
                case "PLAYER_ENTER" -> handlePlayerEnter(session, playerId, message);
                case "PLAYER_MOVE"  -> handlePlayerMove(session, playerId, message);
                case "PLAYER_TALK"  -> handlePlayerTalk(session, playerId, message);
                case "PLAYER_TALK_END" -> handlePlayerTalkEnd(session, playerId, message);
                case "PING"         -> handlePing(session, message);
                default -> log.warn("未知消息类型: {} (from player {})", message.getType(), playerId);
            }
        } catch (Exception e) {
            log.error("解析玩家 [{}] 消息失败: {}", playerId, e.getMessage());
            sendSystem(session, "error", "消息格式错误");
        }
    }

    /**
     * 处理玩家重新进入（已认证状态下再次发送 PLAYER_ENTER）。
     * 重新推送世界状态，不重新注册。
     */
    private void handlePlayerEnter(WebSocketSession session, String playerId, WebSocketMessage message) {
        sendWorldState(session, playerId);
    }

    /**
     * 处理玩家移动。
     * 将 payload 转为 PlayerMoveRequest，更新位置并广播。
     */
    private void handlePlayerMove(WebSocketSession session, String playerId, WebSocketMessage message) {
        PlayerMoveRequest req = convertPayload(message, PlayerMoveRequest.class);
        Room room = getRoomForPlayer(playerId);

        playerService.updatePosition(playerId, req.x(), req.y(), room.getWidth(), room.getHeight())
                .ifPresent(player -> broadcast(WebSocketMessage.builder()
                        .type("PLAYER_MOVED")
                        .requestId(message.getRequestId())
                        .timestamp(Instant.now().toEpochMilli())
                        .payload(Map.of("playerId", playerId, "x", player.getX(), "y", player.getY()))
                        .build()));
    }

    /**
     * 处理玩家对 NPC 说话。
     * Iteration 1 实现：校验 NPC 存在性 + 锁定状态 + 提交 AI 请求。
     */
    private void handlePlayerTalk(WebSocketSession session, String playerId, WebSocketMessage message) {
        PlayerTalkRequest req = convertPayload(message, PlayerTalkRequest.class);
        // Iteration 1 TODO: NPC 存在性校验、对话锁定、异步 AI 调用
        log.info("玩家 [{}] 对 NPC [{}] 说话: {}", playerId, req.npcId(), req.message());
        sendSystem(session, "info", "对话功能将在 Iteration 1 实现");
    }

    /**
     * 处理玩家主动结束对话。
     */
    private void handlePlayerTalkEnd(WebSocketSession session, String playerId, WebSocketMessage message) {
        PlayerTalkEndRequest req = convertPayload(message, PlayerTalkEndRequest.class);
        // Iteration 1 TODO: 释放 NPC 对话锁定
        log.info("玩家 [{}] 结束与 NPC [{}] 的对话", playerId, req.npcId());
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

    // ==================== 会话清理 ====================

    /**
     * 玩家断连后的清理工作。
     */
    private void handleSessionCleanup(String playerId, boolean broadcast) {
        sessionManager.unregister(playerId);
        playerService.leaveTavern(playerId);
        if (broadcast) {
            broadcast(WebSocketMessage.builder()
                    .type("PLAYER_LEFT")
                    .requestId(UUID.randomUUID().toString())
                    .timestamp(Instant.now().toEpochMilli())
                    .payload(Map.of("playerId", playerId))
                    .build());
        }
    }

    // ==================== 世界状态 ====================

    /**
     * 发送完整世界状态给指定玩家。
     */
    private void sendWorldState(WebSocketSession session, String playerId) {
        List<Room> rooms = worldService.getAllRooms();
        List<Player> onlinePlayers = playerService.getOnlinePlayers();
        var currentPlayerOpt = playerService.getPlayer(playerId);

        Map<String, Object> worldPayload = new HashMap<>();
        worldPayload.put("rooms", rooms.stream().map(this::roomToMap).toList());
        worldPayload.put("players", onlinePlayers.stream().map(this::playerToMap).toList());
        worldPayload.put("yourPlayerId", playerId);
        currentPlayerOpt.ifPresent(p -> worldPayload.put("yourPosition",
                Map.of("x", p.getX(), "y", p.getY(), "roomId", p.getCurrentRoomId())));

        sendMessage(session, WebSocketMessage.builder()
                .type("WORLD_STATE")
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toEpochMilli())
                .payload(worldPayload)
                .build());
    }

    // ==================== 辅助方法 ====================

    /**
     * 将消息 payload 转为强类型 DTO。
     * 使用 ObjectMapper.convertValue 而非手动从 Map 取值。
     */
    private <T> T convertPayload(WebSocketMessage message, Class<T> targetClass) {
        return objectMapper.convertValue(message.getPayload(), targetClass);
    }

    /**
     * 获取默认房间（启动时加载的第一个房间）。
     * 一次查询缓存结果，避免三个方法重复 stream。
     */
    private Room getDefaultRoom() {
        return worldService.getAllRooms().stream().findFirst()
                .orElse(Room.builder()
                        .id("tavern").name("酒馆").width(800).height(600)
                        .defaultSpawnX(50).defaultSpawnY(300)
                        .build());
    }

    /**
     * 获取玩家当前所在房间，用于边界校验。
     */
    private Room getRoomForPlayer(String playerId) {
        return playerService.getPlayer(playerId)
                .flatMap(p -> worldService.getRoom(p.getCurrentRoomId()))
                .orElse(getDefaultRoom());
    }

    /**
     * Room 转 Map，控制暴露给前端的字段。
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

    // ==================== 消息发送 ====================

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
     * 发送系统消息给指定会话。
     */
    private void sendSystem(WebSocketSession session, String level, String msg) {
        sendMessage(session, WebSocketMessage.builder()
                .type("SYSTEM")
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toEpochMilli())
                .payload(Map.of("level", level, "message", msg))
                .build());
    }
}
