package com.taverntales.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * DeepSeek LLM 提供商 — 通过 Python FastAPI 调用 DeepSeek API。
 *
 * 为什么不直接从 Java 调 DeepSeek？
 * 见 CLAUDE.md "已决策的设计取舍" — Python 层存在是出于团队和未来 ML 生态考虑。
 *
 * 为什么用 WebClient 而非 RestTemplate？
 * WebClient 是 WebFlux 的标配 HTTP 客户端，非阻塞异步，不占用 Netty 事件循环线程池。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "game.llm.provider", havingValue = "deepseek")
public class DeepSeekLlmProvider implements LlmProvider {

    private final WebClient webClient;

    public DeepSeekLlmProvider(
            @Value("${game.llm.deepseek.base-url:http://localhost:8000}") String baseUrl,
            @Value("${game.llm.deepseek.timeout:5000}") long timeoutMs) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();
        log.info("DeepSeek LLM 提供商已初始化: baseUrl={}, timeout={}ms", baseUrl, timeoutMs);
    }

    @Override
    public Mono<DialogueResponse> sendDialogue(Map<String, Object> dialogueRequest) {
        return webClient.post()
                .uri("/api/dialogue")
                .bodyValue(dialogueRequest)
                .retrieve()
                .bodyToMono(DialogueResponse.class)
                .doOnNext(r -> log.debug("Python AI 返回: {}", r.npcResponse()))
                .doOnError(e -> log.warn("Python AI 调用失败: {}", e.getMessage()));
    }

    @Override
    public String getName() {
        return "deepseek";
    }
}
