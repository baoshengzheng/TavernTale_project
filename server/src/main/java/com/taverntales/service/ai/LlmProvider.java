package com.taverntales.service.ai;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * LLM 提供商接口。
 *
 * 为什么要抽象？
 * 1. 开发时不启动 Python 也能用 Mock 跑通前端
 * 2. 后续换模型（DeepSeek → 其他）只需加一个实现类
 * 3. @ConditionalOnProperty 在运行时选择实现，不需要改代码
 *
 * 为什么返回 Mono 而非同步阻塞？
 * WebFlux 基于 Netty 事件循环，同步阻塞会拖死整个服务。
 * Mono 是非阻塞异步结果，Netty 线程提交请求后继续处理其他事件。
 */
public interface LlmProvider {

    /**
     * 发送对话请求到 AI 服务。
     *
     * @param dialogueRequest 包含 NPC 信息、玩家消息、上下文
     * @return Mono 包装的对话响应，超时或失败时走 onError
     */
    Mono<DialogueResponse> sendDialogue(Map<String, Object> dialogueRequest);

    /** 提供商标识，用于日志和调试 */
    String getName();
}
