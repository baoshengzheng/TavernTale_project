package com.taverntales.domain.npc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * NPC 不可变定义 — 从 JSON 配置文件加载。
 *
 * 为什么不是 JPA 实体？
 * NPC 的定义（性格、背景、说话风格）是静态配置，和 rooms.json 一样。
 * 只有运行时可变的状态（位置、情绪、好感度）才需要 JPA 持久化。
 * 分离定义和状态意味着修改 NPC 性格不会丢失他在游戏中的记忆。
 *
 * 为什么用 @JsonIgnoreProperties(ignoreUnknown = true)？
 * 允许在 JSON 中添加 _comment 等注释字段而不影响反序列化。
 * 保证前后版本 JSON 兼容。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NpcDefinition {

    private String id;
    private String name;
    private String title;
    private String race;

    /** 外观描述，用于前端渲染和 AI 上下文 */
    private String description;

    /** 背景故事，注入 Prompt 作为 NPC 的"前世" */
    private String background;

    /** 说话风格描述，控制 AI 回复的语气和措辞 */
    private String speakingStyle;

    /** 初始好感度（0-100），玩家第一次见到此 NPC 时的基础值 */
    @JsonProperty("initialRelationship")
    private int initialRelationship;

    /** OCEAN 五因素人格 */
    private OCEAN personality;

    /** 初始位置 */
    private Position position;

    /** 触发半径（像素），玩家进入此范围可对话 */
    private int triggerRadius;

    /**
     * OCEAN 五因素人格维度，范围 0-100。
     * 这些值影响 Prompt 生成、情绪变化方向和对话风格。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OCEAN {
        private int openness;          // O：开放性
        private int conscientiousness; // C：尽责性
        private int extraversion;      // E：外向性
        private int agreeableness;     // A：宜人性
        private int neuroticism;       // N：神经质
    }

    /**
     * NPC 初始位置。
     * 运行时位置存在 NpcState 中，这里只是"出生点"。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Position {
        private String roomId;
        private int x;
        private int y;
    }
}
