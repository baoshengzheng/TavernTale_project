package com.taverntales.controller.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话管理器。
 *
 * 维护 playerId → WebSocketSession 的映射，支持广播和单播。
 *
 * 为什么用 ConcurrentHashMap 而非 Redis？
 *  Iteration 0 单机部署，ConcurrentHashMap 足够。后续多节点部署时替换为 Redis Pub/Sub。
 *
 * 线程安全说明：
 *  WebFlux 的 WebSocket 处理在 Netty 事件循环线程中执行，
 *  ConcurrentHashMap 保证所有操作的可见性和原子性。
 */
@Slf4j
@Component
public class WebSocketSessionManager {

    /** playerId → WebSocketSession */
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * 注册会话。一个玩家只能有一个活跃会话，旧会话会被关闭。
     */
    public void register(String playerId, WebSocketSession session) {
        // 如果该玩家已有连接，关闭旧连接
        WebSocketSession oldSession = sessions.put(playerId, session);
        if (oldSession != null && oldSession.isOpen()) {
            log.warn("玩家 [{}] 已有活跃连接，关闭旧连接", playerId);
            // 旧连接可能已被浏览器关闭（如前端热更新），close 失败是预期行为，不记录 ERROR
            oldSession.close().subscribe(null, e ->
                    log.debug("关闭玩家 [{}] 旧连接失败（预期行为，忽略）", playerId)
            );
        }
        log.info("玩家 [{}] WebSocket 已连接，当前在线: {}", playerId, sessions.size());
    }

    /**
     * 移除会话。
     */
    public void unregister(String playerId) {
        sessions.remove(playerId);
        log.info("玩家 [{}] WebSocket 已断开，当前在线: {}", playerId, sessions.size());
    }

    /**
     * 获取玩家的会话。
     */
    public WebSocketSession getSession(String playerId) {
        return sessions.get(playerId);
    }

    /**
     * 判断玩家是否在线。
     */
    public boolean isOnline(String playerId) {
        return sessions.containsKey(playerId) && sessions.get(playerId).isOpen();
    }

    /**
     * 获取当前在线玩家数。
     */
    public int onlineCount() {
        return sessions.size();
    }

    /**
     * 获取所有活跃会话。
     */
    public ConcurrentHashMap<String, WebSocketSession> getAllSessions() {
        return sessions;
    }
}
