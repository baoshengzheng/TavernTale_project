package com.taverntales.service.ai;

import com.taverntales.controller.ws.WebSocketSessionManager;
import com.taverntales.domain.npc.NpcFactory;
import com.taverntales.domain.npc.NpcInstance;
import com.taverntales.domain.npc.NpcState;
import com.taverntales.dto.WebSocketMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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
        request.put("relationship_score", getRelationshipScore(npc, playerId));
        request.put("recent_memories", getPlayerMemories(npc.getPlayerMemoriesJson(), playerId));
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

            // 更新 NPC 状态：好感度 +1、情绪变化、记忆追加
            NpcInstance npc = npcFactory.getNpc(npcId);
            if (npc != null && !response.fallback()) {
                NpcState state = npc.getState();
                // 好感度 +1
                state.setPlayerRelationshipsJson(
                        incrementRelationship(state.getPlayerRelationshipsJson(), playerId, npc.getInitialRelationship()));
                // 更新情绪
                if (response.emotionChange() != null && !response.emotionChange().isBlank()) {
                    state.setCurrentEmotionJson(
                            updateEmotion(state.getCurrentEmotionJson(), response.emotionChange()));
                }
                // 追加记忆
                if (response.newMemories() != null && !response.newMemories().isBlank()) {
                    state.setPlayerMemoriesJson(
                            appendMemory(state.getPlayerMemoriesJson(), playerId, response.newMemories()));
                }
                npcFactory.saveNpcState(state);
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

    // ==================== JSON 状态解析与更新 ====================

    /**
     * 从 playerRelationshipsJson 中获取指定玩家的好感度。
     * 如果没有历史记录，回退到 NPC 定义的 initialRelationship。
     */
    private int getRelationshipScore(NpcInstance npc, String playerId) {
        try {
            String json = npc.getPlayerRelationshipsJson();
            if (json == null || json.isBlank() || "{}".equals(json.trim())) {
                return npc.getInitialRelationship();
            }
            Map<String, Map<String, Object>> relationships = objectMapper.readValue(
                    json, new TypeReference<Map<String, Map<String, Object>>>() {});
            Map<String, Object> rel = relationships.get(playerId);
            if (rel != null && rel.get("score") instanceof Number score) {
                return score.intValue();
            }
        } catch (Exception e) {
            log.warn("解析好感度 JSON 失败 (npc={}, player={}): {}", npc.getId(), playerId, e.getMessage());
        }
        return npc.getInitialRelationship();
    }

    /**
     * 从 playerMemoriesJson 中提取指定玩家的记忆列表。
     * 格式化为 AI 可读的文本摘要。
     */
    private String getPlayerMemories(String json, String playerId) {
        try {
            if (json == null || json.isBlank() || "{}".equals(json.trim())) {
                return "无（初次见面）";
            }
            Map<String, List<Map<String, Object>>> memories = objectMapper.readValue(
                    json, new TypeReference<Map<String, List<Map<String, Object>>>>() {});
            List<Map<String, Object>> playerMemories = memories.getOrDefault(playerId, List.of());
            if (playerMemories.isEmpty()) return "无（初次见面）";
            // 取最近 5 条记忆
            int from = Math.max(0, playerMemories.size() - 5);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < playerMemories.size(); i++) {
                sb.append("- ").append(playerMemories.get(i).get("event")).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("解析记忆 JSON 失败 (player={}): {}", playerId, e.getMessage());
            return "无";
        }
    }

    /**
     * 玩家好感度 +1 并返回更新后的 JSON。
     *
     * 为什么每次对话只 +1？
     * 防止玩家反复刷对话刷满好感度。好感度应通过多个维度（送礼、任务）
     * 逐步积累，对话只是其中一个温和的加分项。
     */
    private String incrementRelationship(String json, String playerId, int initialScore) {
        try {
            Map<String, Map<String, Object>> relationships;
            if (json == null || json.isBlank() || "{}".equals(json.trim())) {
                relationships = new LinkedHashMap<>();
            } else {
                relationships = objectMapper.readValue(json,
                        new TypeReference<Map<String, Map<String, Object>>>() {});
            }
            Map<String, Object> rel = relationships.containsKey(playerId)
                    ? relationships.get(playerId)
                    : new LinkedHashMap<>();
            int current = rel.get("score") instanceof Number s ? s.intValue() : initialScore;
            rel.put("score", Math.min(100, current + 1));
            rel.put("lastUpdated", LocalDateTime.now().toString());
            relationships.put(playerId, rel);
            return objectMapper.writeValueAsString(relationships);
        } catch (Exception e) {
            log.warn("更新好感度 JSON 失败 (player={}): {}", playerId, e.getMessage());
            return json;
        }
    }

    /**
     * 根据情绪变化描述更新 currentEmotionJson。
     * 变化格式："joy: +5" 或 "joy: +5, sadness: -2"。
     */
    private String updateEmotion(String currentJson, String changeDescription) {
        if (changeDescription == null || changeDescription.isBlank()) return currentJson;
        try {
            Map<String, Integer> emotions;
            if (currentJson == null || currentJson.isBlank() || "{}".equals(currentJson.trim())) {
                emotions = new LinkedHashMap<>();
            } else {
                emotions = objectMapper.readValue(currentJson, new TypeReference<Map<String, Integer>>() {});
            }
            for (String part : changeDescription.split(",")) {
                String[] kv = part.trim().split(":");
                if (kv.length == 2) {
                    String emotion = kv[0].trim();
                    int delta = Integer.parseInt(kv[1].trim().replace("+", ""));
                    emotions.merge(emotion, delta, Integer::sum);
                    emotions.put(emotion, Math.max(0, Math.min(100, emotions.get(emotion))));
                }
            }
            return objectMapper.writeValueAsString(emotions);
        } catch (Exception e) {
            log.warn("更新情绪 JSON 失败: {}", e.getMessage());
            return currentJson;
        }
    }

    /**
     * 追加一条玩家记忆并返回更新后的 JSON。
     * 最多保留最近 20 条记忆，防止 JSON 无限膨胀。
     */
    private String appendMemory(String currentJson, String playerId, String newMemory) {
        if (newMemory == null || newMemory.isBlank()) return currentJson;
        try {
            Map<String, List<Map<String, Object>>> memories;
            if (currentJson == null || currentJson.isBlank() || "{}".equals(currentJson.trim())) {
                memories = new LinkedHashMap<>();
            } else {
                memories = objectMapper.readValue(currentJson,
                        new TypeReference<Map<String, List<Map<String, Object>>>>() {});
            }
            List<Map<String, Object>> list = memories.getOrDefault(playerId, new ArrayList<>());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("event", newMemory);
            entry.put("importance", 5);
            entry.put("time", LocalDateTime.now().toString());
            list.add(entry);
            if (list.size() > 20) {
                list = new ArrayList<>(list.subList(list.size() - 20, list.size()));
            }
            memories.put(playerId, list);
            return objectMapper.writeValueAsString(memories);
        } catch (Exception e) {
            log.warn("更新记忆 JSON 失败 (player={}): {}", playerId, e.getMessage());
            return currentJson;
        }
    }
}
