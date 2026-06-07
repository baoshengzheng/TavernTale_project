package com.taverntales.config;

import com.taverntales.controller.ws.TavernWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 路由配置。
 *
 * 将 {playerId} 作为查询参数传递，见 TavernWebSocketHandler 的 handle 方法。
 *
 * 为什么用 SimpleUrlHandlerMapping 而非注解？
 *  Spring WebFlux 的 WebSocket 端点通过 URL mapping 注册，与 MVC 的 @MessageMapping 不同。
 *  这也是 WebFlux 推荐的配置方式。
 */
@Configuration
public class WebSocketConfig {

    /**
     * 注册 WebSocket 路径映射。
     * /ws/tavern → TavernWebSocketHandler
     */
    @Bean
    public SimpleUrlHandlerMapping webSocketHandlerMapping(TavernWebSocketHandler handler) {
        Map<String, WebSocketHandler> map = new HashMap<>();
        map.put("/ws/tavern", handler);

        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(map);
        mapping.setOrder(-1); // 高优先级
        return mapping;
    }

    /**
     * WebSocket 处理器适配器。
     * 必须显式声明，否则 WebFlux 不会处理 WebSocket 握手。
     */
    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
