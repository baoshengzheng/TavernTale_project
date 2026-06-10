package com.taverntales.service.ai;

import com.taverntales.controller.ws.WebSocketSessionManager;
import com.taverntales.domain.npc.NpcFactory;
import com.taverntales.domain.npc.NpcInstance;
import com.taverntales.dto.WebSocketMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * AI 对话编排服务 — 异步提交对话请求，处理超时和兜底。
 *
 * 为什么不在 Handler 中直接调用 LlmProvider？
 * Handler 职责是消息路由，不应包含 AI 调用编排逻辑。
 * AiIntegrationService 封装了构建请求、超时处理、状态更新、消息发送的全流程。
 *
 * 为什么 subscribe() 不在当前线程阻塞？
 * 异步提交后立即返回，不阻塞 Netty 事件循环。
 * 结果通过 session.send() 写回，Netty 保证 channel 写是线程安全的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiIntegrationService {

    private final LlmProvider llmProvider;
    private final NpcFactory npcFactory;
    private final WebSocketSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    @Value("${game.ai-timeout:3000}")
    private long aiTimeoutMs;

    /**
     * 异步提交对话请求。
     *
     * 流程：
     * 1. 构建请求 payload（NPC 信息 + 玩家消息 + 记忆/好感度）
     * 2. 提交到 LlmProvider（异步，不阻塞）
     * 3. 超时（game.ai-timeout）→ 发送兜底回复 + 解锁 NPC
     * 4. 成功 → 更新 NPC 状态 + 发送回复 + 解锁 NPC
     */
    public void submitDialogue(String npcId, String playerId, String playerMessage,
                                WebSocketSession playerSession) {
        NpcInstance npc = npcFactory.getNpc(npcId);
        if (npc == null) {
            log.warn("NPC [{}] 不存在，无法对话", npcId);
            return;
        }

        Map<String, Object> request = buildDialogueRequest(npc, playerId, playerMessage);

        llmProvider.sendDialogue(request)
                .timeout(Duration.ofMillis(aiTimeoutMs))
                .subscribe(
                        response -> handleDialogueSuccess(npcId, playerId, playerSession, response),
                        error -> handleDialogueError(npcId, playerId, playerSession, error)
                );
    }

    /**
     * 构建发送给 Python AI 服务的请求。
     * 包含 NPC 定义信息、运行时状态、玩家消息。
     */
    private Map<String, Object> buildDialogueRequest(NpcInstance npc, String playerId,
                                                      String playerMessage) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("npc_id", npc.getId());
        request.put("npc_name", npc.getName());
        request.put("npc_title", npc.getTitle());
        request.put("player_id", playerId);
        request.put("player_name", "旅人");
        request.put("player_message", playerMessage);
        request.put("background", npc.getBackground());
        request.put("speaking_style", npc.getSpeakingStyle());

        // OCEAN 人格
        request.put("personality", Map.of(
                "openness", npc.getPersonality().getOpenness(),
                "conscientiousness", npc.getPersonality().getConscientiousness(),
                "extraversion", npc.getPersonality().getExtraversion(),
                "agreeableness", npc.getPersonality().getAgreeableness(),
                "neuroticism", npc.getPersonality().getNeuroticism()
        ));

        request.put("current_emotion", npc.getCurrentEmotionJson());
        request.put("relationship_score", 50); // TODO: 从 npcState 中解析实际好感度
        request.put("recent_memories", npc.getPlayerMemoriesJson());
        request.put("world_context", "傍晚，酒馆里有几个客人。壁炉里的火噼啪作响。");

        return request;
    }

    /**
     * AI 调用成功：更新 NPC 状态 + 发送回复 + 解锁。
     */
    private void handleDialogueSuccess(String npcId, String playerId,
                                        WebSocketSession session, DialogueResponse response) {
        try {
            if (!sessionManager.isOnline(playerId)) {
                // 玩家已在等待期间断连，释放 NPC 锁定
                npcFactory.unlockNpc(npcId);
                return;
            }

            // 更新 NPC 状态（情绪、好感度、记忆）
            NpcInstance npc = npcFactory.getNpc(npcId);
            if (npc != null && !response.fallback()) {
                // TODO: 解析 emotionChange 和 newMemories，更新 NpcState JSON 字段
                npcFactory.saveNpcState(npc.getState());
            }

            // 解锁 NPC
            npcFactory.unlockNpc(npcId);

            // 发送 DIALOGUE 消息给玩家
            sendDialogueMessage(session, npcId, response);

        } catch (Exception e) {
            log.error("处理对话成功回调异常", e);
            npcFactory.unlockNpc(npcId);
        }
    }

    /**
     * AI 调用失败/超时：发送兜底回复 + 解锁。
     */
    private void handleDialogueError(String npcId, String playerId,
                                      WebSocketSession session, Throwable error) {
        log.warn("NPC [{}] 对话超时/失败 (player {}): {}", npcId, playerId, error.getMessage());

        npcFactory.unlockNpc(npcId);

        if (sessionManager.isOnline(playerId)) {
            DialogueResponse fallback = DialogueResponse.fallback("…（沉默了一会儿，没有回答）");
            sendDialogueMessage(session, npcId, fallback);
        }
    }

    /**
     * 发送 DIALOGUE 消息到指定玩家。
     */
    private void sendDialogueMessage(WebSocketSession session, String npcId,
                                      DialogueResponse response) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("npcId", npcId);
            payload.put("message", response.npcResponse());
            payload.put("emotion", response.emotionChange());
            payload.put("fallback", response.fallback());

            WebSocketMessage msg = WebSocketMessage.builder()
                    .type("DIALOGUE")
                    .requestId(UUID.randomUUID().toString())
                    .timestamp(Instant.now().toEpochMilli())
                    .payload(payload)
                    .build();

            String json = objectMapper.writeValueAsString(msg);
            session.send(Mono.just(session.textMessage(json)))
                    .subscribe(null, e -> log.error("发送 DIALOGUE 失败", e));
        } catch (Exception e) {
            log.error("序列化 DIALOGUE 消息失败", e);
        }
    }
}
