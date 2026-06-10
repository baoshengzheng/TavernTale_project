package com.taverntales.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Mock LLM 提供商 — 开发用。
 *
 * 为什么默认启用 (matchIfMissing=true)？
 * Iteration 1 开发时不想每次都启动 Python 服务。
 * 前端开发者可以直接用 Mock 验证完整的对话 UI 链路。
 * 需要真实 AI 时设 game.llm.provider=deepseek。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "game.llm.provider", havingValue = "mock", matchIfMissing = true)
@RequiredArgsConstructor
public class MockLlmProvider implements LlmProvider {

    private final Random random = new Random();

    /** Edith 的预制回复，按不同 NPC 返回不同风格 */
    private static final Map<String, List<String>> MOCK_REPLIES = Map.of(
            "edith", List.of(
                    "新来的？坐吧。今天有刚炖好的野猪肉，配黑麦面包——如果你能付得起的话。",
                    "（头也不抬地继续擦杯子）想喝什么？不过我先说好，赊账的规矩三年前就取消了。",
                    "（扫了你一眼）嗯，最近镇上不太平。你看起来不像是本地人。",
                    "壁炉边那个位置是 Roderick 的专座，你最好别去碰。那老酒鬼虽然醉醺醺的，但以前可不是好惹的。",
                    "（放下杯子，盯着你看了一瞬）你让我想起一个人……算了，不提了。要点什么？"
            )
    );

    @Override
    public Mono<DialogueResponse> sendDialogue(Map<String, Object> dialogueRequest) {
        String npcId = (String) dialogueRequest.getOrDefault("npc_id", "edith");
        List<String> replies = MOCK_REPLIES.getOrDefault(npcId, List.of("……（沉默）"));
        String reply = replies.get(random.nextInt(replies.size()));

        // 模拟 AI 处理延迟 300-800ms，让 DIALOGUE_PENDING 可见
        long delay = 300 + random.nextInt(500);
        return Mono.just(new DialogueResponse(reply, "joy: +5", "玩家和你聊了几句",
                        List.of("npc_wipes_glass"), false))
                .delayElement(Duration.ofMillis(delay))
                .doOnNext(r -> log.debug("Mock LLM 返回 NPC [{}] 回复 (延迟{}ms)", npcId, delay));
    }

    @Override
    public String getName() {
        return "mock";
    }
}
